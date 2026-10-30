package com.example.localmovielibrarz.scraper

/**
 * 片商官网详情页的**通用**抽取器。
 *
 * 这个文件替代的是 peach 那二十九份手写解析器。它不认站点，只认页面自带的机器可读标记：
 *
 * 1. `<script type="application/ld+json">` 里的 `Product` / `Movie`（schema.org 的字段名：
 *    `name` / `description` / `datePublished` / `duration` / `actor[]` / `director[]` /
 *    `brand` / `aggregateRating.ratingValue` / `image`）；
 * 2. OpenGraph 与常见 `meta`（`og:title` / `og:description` / `og:image` /
 *    `article:published_time` / `product:release_date` / `product:brand`，外加 `itemprop` 微数据）；
 * 3. `dt`/`dd`（定义列表）与 `th`/`td`（表格）的标签文字配对——标签词表是日文站通用的那几个：
 *    発売日 / 配信開始日 / 収録時間 / メーカー / レーベル / 出演者 / ジャンル / シリーズ / 監督。
 *
 * ## 为什么是「三层降级 + 逐字段先到先得」，而不是「整层切换」
 *
 * 站点的机器可读段很少是完整的：JSON-LD 常缺 `duration` 与演员，OpenGraph 常缺发行日，
 * 定义列表里有厂牌但没有封面。如果按「这一层有没有 title」整层切换，就会拿到一份
 * 「标题对、其余全空」的记录，而链上（`ScrapeRouteRules.missingRequiredFields`）会认为
 * 这一家没给全、继续问下一家——官网明明有一手资料却没用上。
 *
 * 所以是**逐字段**降级：每一层只填自己有的字段，先到先得（层的顺序由
 * [OfficialMakerSite.profile] 决定）。唯一的例外是封面：契约明确给了
 * `og:image → product:image → JSON-LD image` 这条顺序，与层序无关，所以它单独抽。
 *
 * ## 为什么不用 Jsoup
 *
 * 本工程不加依赖（与 [PeachHtml] 的取舍同源）；这里需要的定位只有「某段 `<script>` 的正文」
 * 「某个标记的属性」「成对标签之间的文字」，正则 + 一次扫描足够。而且本文件要进
 * `work/compile-pure-jvm.sh` 的 Tier 1 编译集——那里的 classpath 只有 JUnit 与协程，
 * 引入 Jsoup 会让这一整段（最容易因站点改版失效的一段）失去离线回归。
 *
 * ## 与 peach `amane` 的一处有意分叉
 *
 * amane 的每个解析器直接读站点自己的字段名，拿不到就留空。这里多一层「标签文字配对」，
 * 因为那是二十九家里大多数站**唯一**的结构（WordPress 系站点把资料放在 `<dl>` 里）。
 * 反过来，这里**不**猜：认不出的字段一律空串，交给链上的下一家（[ScrapeSource] 的官方档
 * 之后还有 r18.dev / DMM），而不是用「第一个看起来像日期的文本」去填发行日。
 */
object OfficialPageExtractor {

    /**
     * 从一页作品详情 HTML 抽元数据。三层降级，先能用的先赢：
     * ① `<script type="application/ld+json">` 里的 Product/Movie（name/description/datePublished/
     * duration/actor[].name/director[].name/brand/aggregateRating.ratingValue）
     * ② OpenGraph + 常见 meta（og:title / og:description / og:image / article:published_time /
     * product:release_date）
     * ③ `dt`/`dd`、`th`/`td` 的标签文字配对（标签词表：発売日／配信開始日／収録時間／メーカー／
     * レーベル／出演者／ジャンル／シリーズ／監督）
     * 都抽不到 title → Failed(NoUsableMetadata, ...)
     *
     * @param html 页面正文（`null` 与空串同义）。
     * @param code 用户给的番号，原样参与 [ScrapedMovieInfo.number] 与标题剥番号。
     * @param finalUrl 这一页的最终地址（跟随重定向之后），写进 [ScrapedMovieInfo.website]。
     * @param source 本步属于哪个来源，只用于失败文案。
     * @param site 命中的片商条目（提供 `studio` 兜底与 [OfficialMakerSite.releaseIsDeliveryDate]）。
     */
    fun extract(
        html: String?,
        code: String,
        finalUrl: String,
        source: ScrapeSource,
        site: OfficialMakerSite
    ): PeachParseResult {
        val text = html.orEmpty()
        val where = "${source.label}/${site.label}"
        if (text.isBlank()) {
            return PeachParseResult.Failed(
                PeachFailureReason.ParseError,
                "$where：详情页是空的，没有正文可以抽"
            )
        }
        // 取页层已经判过一次验证页（`BrowserFallbackPolicy.isRefused`），但「HTTP 200 + 验证页正文」
        // 也可能走到这里（浏览器通道取回来的一页），再判一次把失败档从 ParseError 抬到
        // CloudflareChallenge——两者在链上一个是 5 分钟冷却、一个是整源冷却，不能混。
        if (ChallengePageDetector.isChallengePage(text)) {
            return PeachParseResult.Failed(
                PeachFailureReason.CloudflareChallenge,
                "$where：回的是站点验证页，不是作品页"
            )
        }

        val facts = Facts()
        for (produce in layersFor(site.profile, text)) {
            facts.merge(produce())
        }

        val title = pickTitle(facts.titles, code)
        if (title.isEmpty()) {
            return PeachParseResult.Failed(
                PeachFailureReason.NoUsableMetadata,
                "$where：JSON-LD / OpenGraph / 定义列表 / h1 都没有抽到标题"
            )
        }

        // releaseIsDeliveryDate 的站：页面上的「発売日/配信開始日」是配信开始日，早于发行日，
        // 写进 premiered 会让账本把所有这类片判成早一个月发行（peach 对 prestige 的实测结论）。
        // 值本身没有第二个可去的字段，按契约丢弃，不在 plot 里补一句「実質発売日」。
        val premiered = if (site.releaseIsDeliveryDate) "" else toIsoDay(facts.release)
        val coverUrls = coverCandidates(text)
        val cover = coverUrls.firstOrNull().orEmpty()
        val runtime = Fc2Parser.runtimeMinutes(toClock(facts.runtime))
        val studio = facts.studio.ifBlank { site.studio }
        // publisher 是「レーベル」，与 studio（メーカー）不是一个概念；页面没给就退回片商名，
        // 不留空——NFO 的 `<label>` 为空时播放器会显示成未知厂牌。
        val publisher = facts.publisher.ifBlank { site.studio }
        val genres = distinctClean(facts.genres)
        val actors = distinctClean(facts.actors)
        val directors = distinctClean(facts.directors)

        val info = ScrapedMovieInfo(
            number = code.trim(),
            title = title,
            originalTitle = title,
            plot = facts.description,
            outline = facts.description,
            year = if (premiered.length >= 4) premiered.substring(0, 4) else "",
            premiered = premiered,
            runtime = runtime,
            studio = studio,
            publisher = publisher,
            series = facts.series.trim(),
            directors = directors,
            actors = actors,
            genres = genres,
            // 标签与类型在这批站上没有区别（站点只给「ジャンル」一栏），两列同源，
            // 与 [OfficialScraper] 的处理一致：下游按 tags 展示、按 genres 过滤，缺一列就会少一条。
            tags = genres,
            rating = facts.rating.trim(),
            website = finalUrl,
            source = site.label,
            thumbUrl = cover,
            posterUrl = cover,
            thumbImageUrls = coverUrls,
            posterImageUrls = coverUrls
        )
        return PeachParseResult.Record(info = info, sourceUrl = finalUrl, coverUrls = coverUrls)
    }

    /**
     * 从搜索结果页里取第一个作品链接。搜索模板命中后，[OfficialSiteScraper] 用它走第二跳。
     *
     * 判据两条，先后有序：
     * 1. 链接地址或链接文字里带这个番号（去掉分隔符后比对，`SSIS-001` 与 `ssis001` 同一条）；
     * 2. 都不带番号时，取第一个「像作品详情页」的链接（路径含 `/works/`、`/detail`、`/product` 等）。
     *
     * 为什么第 2 条要有：不少站的结果页链接是 `/works/detail/<站内 id>`，番号只在卡片标题里；
     * 此时「第一个作品链接」就是契约要的那一条。反之，如果按「第一个 `<a>`」不看路径，
     * 会拿到导航栏或广告位，抓回来的就是另一页。
     *
     * 拿不到时返回空串（调用方记一条 NotFound 换下一家，不要去猜 URL）。
     */
    fun firstWorkUrl(html: String?, baseUrl: String, code: String): String {
        val text = html.orEmpty()
        if (text.isBlank()) return ""
        val compact = OfficialMakerCatalog.compactCode(code)
        var pathFallback = ""
        for (match in ANCHOR.findAll(text)) {
            val href = match.groupValues[1].trim()
            if (href.isEmpty() || href.startsWith("#") || href.startsWith("javascript:", ignoreCase = true)) {
                continue
            }
            val absolute = absoluteUrl(href, baseUrl)
            if (absolute.isEmpty()) continue
            if (compact.isNotEmpty()) {
                val linkToken = OfficialMakerCatalog.compactCode(absolute)
                val labelToken = OfficialMakerCatalog.compactCode(PeachHtml.text(match.groupValues[2]))
                if (linkToken.contains(compact) || labelToken.contains(compact)) return absolute
            }
            if (pathFallback.isEmpty() && looksLikeDetailPath(absolute)) pathFallback = absolute
        }
        return pathFallback
    }

    // ── 层的编排 ─────────────────────────────────────────────────────────────

    /**
     * 按抽取档给出层序。每层是一段惰性求值的闭包：抽不到东西的层几乎不花时间
     * （正则扫一遍页面），所以不做「命中就停」——见文件头「逐字段降级」的理由。
     */
    private fun layersFor(
        profile: OfficialExtractionProfile,
        text: String
    ): List<() -> Raw> = when (profile) {
        // S1 系的前端把数据放在 `<script type="application/json">` 的 props 里，比 ld+json 全。
        OfficialExtractionProfile.InertiaJson -> listOf(
            { inertiaRaw(text) },
            { jsonLdRaw(text) },
            { metaRaw(text) },
            { definitionRaw(text) }
        )
        // 只有定义列表/表格的站（WordPress 系）：先读人写的标签，再退回结构化数据。
        OfficialExtractionProfile.DefinitionList -> listOf(
            { definitionRaw(text) },
            { metaRaw(text) },
            { jsonLdRaw(text) }
        )
        OfficialExtractionProfile.Generic -> listOf(
            { jsonLdRaw(text) },
            { metaRaw(text) },
            { definitionRaw(text) }
        )
        // S1 系：一手资料在三处——`h2.p-workPage__title`（标题）、`p.p-workPage__text`（剧情）、
        // `div.th`/`div.td`（资料栏）。把它们放到最前：`<title>` 与 meta 在这一系站点上
        // 只剩站点名和一句宣传语，先读会把一手资料挡在后面（逐字段先到先得）。
        OfficialExtractionProfile.WorkPageTable -> listOf(
            { workPageRaw(text) },
            { jsonLdRaw(text) },
            { metaRaw(text) },
            { definitionRaw(text) }
        )
    }

    // ── ① JSON-LD ───────────────────────────────────────────────────────────

    private fun jsonLdRaw(text: String): Raw {
        val objects = ArrayList<PeachJsonValue.Obj>()
        for (block in LD_SCRIPT.findAll(text)) {
            collectObjects(PeachJson.parse(block.groupValues[1].trim()), objects, 0)
        }
        if (objects.isEmpty()) return Raw()
        // `@graph` 里常同时有 Product、BreadcrumbList、WebSite 三种节点；先只留「作品」节点，
        // 一个都没有时才退回全部（有些站只用 `CreativeWork` 之外的 @type）。
        val nodes = objects.filter { isWorkObject(it) }.ifEmpty { objects }
        var merged = Raw()
        for (node in nodes) merged = mergeRaw(merged, rawOfJsonLd(node))
        return merged
    }

    /** `@type` 可能是字符串也可能是数组；统一成小写集合。 */
    private fun typesOf(obj: PeachJsonValue.Obj): Set<String> {
        val raw = obj["@type"] ?: return emptySet()
        val values = when (raw) {
            is PeachJsonValue.Str -> listOf(raw.value)
            is PeachJsonValue.Arr -> raw.items.map { it.stringOrEmpty() }
            else -> emptyList()
        }
        return values.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
    }

    private fun isWorkObject(obj: PeachJsonValue.Obj): Boolean {
        val types = typesOf(obj)
        if (types.any { it in WORK_TYPES }) return true
        // 没有 @type 时只看「有 name」——太少，但比把面包屑当作品好。
        return types.isEmpty() && obj["name"].stringOrEmpty().isNotBlank()
    }

    private fun rawOfJsonLd(obj: PeachJsonValue.Obj): Raw {
        val rating = obj["aggregateRating"].objOrNull()?.get("ratingValue").stringOrEmpty()
            .ifBlank { obj["ratingValue"].stringOrEmpty() }
        val seriesName = namesOf(obj["isPartOf"]).firstOrNull().orEmpty()
        return Raw(
            titles = listOf(
                firstText(obj, "name"),
                firstText(obj, "headline")
            ).filter { it.isNotBlank() }.distinct(),
            description = firstText(obj, "description", "abstract"),
            release = firstText(obj, "datePublished", "releaseDate", "uploadDate", "dateCreated"),
            runtime = firstText(obj, "duration", "timeRequired"),
            // brand / manufacturer 是 schema.org 里「メーカー」的两位；productionCompany 在部分
            // 站里放的是发行方，同一栏就够了。provider 不放：它多数时候是站点主体，不是厂牌。
            studio = firstName(obj, "brand", "manufacturer", "productionCompany"),
            // 不把 JSON-LD 的 publisher 当 レーベル：这个键在片商站上填的是站点主体或发行方，
            // 与「レーベル」不是一个概念，写进 publisher 会让 NFO 的 label 变成站点名。
            publisher = "",
            series = seriesName,
            rating = rating,
            directors = namesOf(obj["director"]),
            actors = namesOf(obj["actor"]) + namesOf(obj["performer"]),
            genres = namesOf(obj["genre"]) + namesOf(obj["keywords"])
        )
    }

    private fun collectObjects(value: PeachJsonValue?, out: MutableList<PeachJsonValue.Obj>, depth: Int) {
        if (value == null || depth > MAX_JSON_DEPTH) return
        when (value) {
            is PeachJsonValue.Obj -> {
                out += value
                value["@graph"].arrayOrEmpty().forEach { collectObjects(it, out, depth + 1) }
            }
            is PeachJsonValue.Arr -> value.items.forEach { collectObjects(it, out, depth + 1) }
            else -> Unit
        }
    }

    // ── ② OpenGraph / meta / h1 ─────────────────────────────────────────────

    private fun metaRaw(text: String): Raw {
        val tags = META_TAG.findAll(text).map { attributes(it.value) }.toList()
        fun meta(vararg keys: String): String = metaValue(tags, keys.toList())
        val h1 = H1.find(text)?.groupValues?.get(1).orEmpty()
        val docTitle = DOC_TITLE.find(text)?.groupValues?.get(1).orEmpty()
        return Raw(
            // 顺序：og:title → twitter:title → h1 → <title>。<title> 放最后是因为它一定带站点名，
            // 只在整页没有别的标题时才用；真正落盘前由 pickTitle 再过一次剥番号。
            titles = listOf(
                meta("og:title", "twitter:title"),
                PeachHtml.text(h1),
                PeachHtml.text(docTitle)
            ).map { stripDocumentSuffix(it) }.filter { it.isNotBlank() }.distinct(),
            description = meta("og:description", "twitter:description", "description"),
            release = meta(
                "product:release_date", "product:releasedate", "article:published_time",
                "og:published_time", "datepublished", "releasedate"
            ),
            runtime = meta("product:duration", "duration"),
            studio = meta("product:brand", "product:manufacturer"),
            publisher = meta("product:label"),
            series = meta("product:series"),
            rating = meta("product:rating_value", "ratingvalue"),
            directors = emptyList(),
            actors = emptyList(),
            genres = emptyList()
        )
    }

    private fun metaValue(tags: List<Map<String, String>>, keys: List<String>): String {
        val wanted = keys.map { it.lowercase() }.toSet()
        for (attrs in tags) {
            val key = (attrs["property"] ?: attrs["name"] ?: attrs["itemprop"] ?: "").trim().lowercase()
            if (key.isEmpty() || key !in wanted) continue
            val value = attrs["content"].orEmpty().trim()
            if (value.isNotEmpty()) return PeachHtml.text(value)
        }
        return ""
    }

    private fun attributes(tag: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (match in ATTR.findAll(tag)) {
            val key = match.groupValues[1].lowercase()
            if (key.isEmpty() || out.containsKey(key)) continue
            val value = match.groupValues[2].ifEmpty { match.groupValues[3] }
            out[key] = value
        }
        return out
    }

    // ── ③ dt/dd、th/td ─────────────────────────────────────────────────────

    private fun definitionRaw(text: String): Raw {
        val pairs = ArrayList<Pair<String, String>>()
        for (match in DL_PAIR.findAll(text)) {
            pairs += PeachHtml.text(match.groupValues[1]) to PeachHtml.text(match.groupValues[2])
        }
        for (match in TABLE_PAIR.findAll(text)) {
            pairs += PeachHtml.text(match.groupValues[1]) to PeachHtml.text(match.groupValues[2])
        }
        if (pairs.isEmpty()) return Raw()
        return Raw(
            // 标题不从这里取：定义列表里没有标题栏，h1 与 og:title 已经覆盖。
            release = pairValue(pairs, "発売日", "発売開始日", "リリース日", "発売"),
            delivery = pairValue(pairs, "配信開始日", "配信日", "配信開始"),
            runtime = pairValue(pairs, "収録時間", "再生時間", "収録"),
            studio = pairValue(pairs, "メーカー", "メーカー名", "ブランド"),
            publisher = pairValue(pairs, "レーベル", "レーベル名"),
            series = pairValue(pairs, "シリーズ", "シリーズ名"),
            directors = splitList(pairValue(pairs, "監督", "監督名")),
            actors = splitList(pairValue(pairs, "出演者", "出演", "出演女優", "主演")),
            genres = splitList(pairValue(pairs, "ジャンル", "カテゴリ", "カテゴリー", "タグ"))
        )
    }

    // ── ③b `div.th` / `div.td` 资料栏（S1 系）──────────────────────────────

    /**
     * S1 系作品页的资料栏：`<div class="th">発売日</div><div class="td">2014年9月7日</div>`。
     *
     * 三处与 [definitionRaw] 不同，所以单开一层而不是改那条正则：
     * 1. 值是**嵌套块**（`div.td` 里还有 `div.item`、多值 `<a>`），非贪婪的
     *    `<div class="th">(.*?)</div>\s*<div class="td">(.*?)</div>` 会在内层第一个 `</div>`
     *    收尾，`ジャンル` 只留下第一项。
     * 2. 标题在 `h2.p-workPage__title`、剧情在 `p.p-workPage__text`，而这两个类名只出现在
     *    这一系站点上（`og:title` 为 0、`og:image` 是站点 logo）。
     * 3. 多值字段（`ジャンル`）每一项是一个 `<a>`，按 `<a>` 收比按分隔符切准。
     *
     * 取值窗口用「下一个 `div.th` 的位置」封闭，因为跨块的 `</div>` 数量不可数；窗口仍设上限，
     * 防止最后一块被后续版面吞进去。
     */
    private fun workPageRaw(text: String): Raw {
        val titles = WORK_PAGE_TITLE.findAll(text)
            .map { PeachHtml.text(it.groupValues[1]) }
            .filter { it.isNotEmpty() }
            .toList()
        val description = WORK_PAGE_TEXT.find(text)?.groupValues?.get(1).orEmpty().let { PeachHtml.text(it) }

        val heads = WORK_PAGE_TH.findAll(text).toList()
        if (heads.isEmpty()) {
            // 只有标题/简介、没有资料栏的页也要能用：不要因为缺 th 就整层返回空。
            return Raw(titles = titles, description = description)
        }
        // 标签 → 值块的原始 HTML。窗口用「下一个 div.th 的位置」封闭：块内嵌着数量不定的
        // `</div>`，靠结束标签配对会在第一层就截断；末尾那块再用定长上限封住，防吃到页尾。
        val blocks = ArrayList<Pair<String, String>>(heads.size)
        for ((index, head) in heads.withIndex()) {
            val label = PeachHtml.text(head.groupValues[1])
            if (label.isEmpty()) continue
            val from = head.range.last + 1
            val to = if (index + 1 < heads.size) heads[index + 1].range.first else minOf(text.length, from + WORK_VALUE_LIMIT)
            if (to <= from) continue
            blocks += label to text.substring(from, to)
        }
        // 多值字段（ジャンル/女優）每项一个 `<a>`，按 `<a>` 收；日期、时长、品番没有 `<a>`，
        // 退回整块纯文本。
        fun valuesOf(vararg labels: String): List<String> {
            for (want in labels) {
                val block = blockOf(blocks, want) ?: continue
                val links = WORK_PAGE_ANCHOR.findAll(block)
                    .map { PeachHtml.text(it.groupValues[1]) }
                    .filter { it.isNotEmpty() }
                    .toList()
                return if (links.isNotEmpty()) links else splitList(PeachHtml.text(block))
            }
            return emptyList()
        }
        fun textOf(vararg labels: String): String = valuesOf(*labels).firstOrNull().orEmpty()
        return Raw(
            titles = titles,
            description = description,
            release = textOf("発売日", "発売開始日", "リリース日", "発売"),
            delivery = textOf("配信開始日", "配信日", "配信開始"),
            runtime = textOf("収録時間", "再生時間", "収録"),
            studio = textOf("メーカー", "メーカー名", "ブランド", "製作商"),
            publisher = textOf("レーベル", "レーベル名", "ブランド"),
            series = textOf("シリーズ", "シリーズ名"),
            directors = valuesOf("監督", "監督名"),
            actors = valuesOf("女優", "出演者", "出演", "出演女優", "主演"),
            genres = valuesOf("ジャンル", "カテゴリ", "カテゴリー", "タグ")
        )
    }

    /**
     * 资料栏取值块：先按 [normalizeLabel] 精确配，再退「标签里含关键词」。
     *
     * 顺序与 [pairValue] 一致，行为才对得上：S1 写 `発売日`、别的站写 `発売日 (販売日)`。
     */
    private fun blockOf(blocks: List<Pair<String, String>>, want: String): String? {
        blocks.firstOrNull { normalizeLabel(it.first) == normalizeLabel(want) }?.let { return it.second }
        return blocks.firstOrNull { normalizeLabel(it.first).contains(normalizeLabel(want)) }?.second
    }

    /**
     * 标签 → 值。先精确匹配，再「标签里含关键词」——站点会写 `発売日：`（带冒号）或
     * `発売日 (販売日)`（带补充说明），全角冒号已在 [PeachHtml.text] 之外单独处理。
     */
    private fun pairValue(pairs: List<Pair<String, String>>, vararg labels: String): String {
        for (label in labels) {
            val hit = pairs.firstOrNull { normalizeLabel(it.first) == normalizeLabel(label) }
            if (hit != null && hit.second.isNotBlank()) return hit.second
        }
        for (label in labels) {
            val hit = pairs.firstOrNull { normalizeLabel(it.first).contains(normalizeLabel(label)) }
            if (hit != null && hit.second.isNotBlank()) return hit.second
        }
        return ""
    }

    private fun normalizeLabel(raw: String): String =
        raw.replace("：", "").replace(":", "").replace(" ", "").replace("　", "").trim()

    // ── 封面 ───────────────────────────────────────────────────────────────

    /**
     * 封面候选，顺序固定：`og:image` → `product:image` → JSON-LD `image` → `twitter:image`。
     *
     * 为什么封面不跟层序走：契约给的这条顺序是按「图源质量」排的（og:image 是站方给分享图选的
     * 主图，几乎总是竖版封面包装），而层序是按「字段完整度」排的。两者混在一起会让
     * DefinitionList 档的站拿不到任何封面。
     */
    private fun coverCandidates(text: String): List<String> {
        val out = LinkedHashSet<String>()
        val tags = META_TAG.findAll(text).map { attributes(it.value) }.toList()
        fun collect(vararg keys: String) {
            for (attrs in tags) {
                val key = (attrs["property"] ?: attrs["name"] ?: "").trim().lowercase()
                if (key.isEmpty() || key !in keys) continue
                putCover(out, attrs["content"].orEmpty())
            }
        }
        collect("og:image", "og:image:url", "og:image:secure_url")
        collect("product:image", "product:image:url")
        for (block in LD_SCRIPT.findAll(text)) {
            collectImages(PeachJson.parse(block.groupValues[1].trim()), out)
        }
        collect("twitter:image", "twitter:image:src")
        return out.toList()
    }

    private fun collectImages(value: PeachJsonValue?, out: MutableSet<String>) {
        when (value) {
            null, PeachJsonValue.Null -> Unit
            is PeachJsonValue.Str -> putCover(out, value.value)
            is PeachJsonValue.Num -> putCover(out, value.raw)
            is PeachJsonValue.Arr -> value.items.forEach { collectImages(it, out) }
            is PeachJsonValue.Obj -> {
                val direct = value["url"].stringOrEmpty().ifBlank { value["contentUrl"].stringOrEmpty() }
                if (direct.isNotBlank()) putCover(out, direct)
                else value["@id"].stringOrEmpty().let { if (it.isNotBlank()) putCover(out, it) }
            }
            is PeachJsonValue.Bool -> Unit
        }
    }

    private fun putCover(out: MutableSet<String>, raw: String) {
        val normalized = normalizeCover(raw)
        if (normalized.isNotEmpty()) out += normalized
    }

    private fun normalizeCover(raw: String): String {
        val value = raw.trim()
        if (value.isEmpty()) return ""
        if (!value.startsWith("http://") && !value.startsWith("https://") && !value.startsWith("//")) {
            // 相对路径不猜：这里没有可信的 base（详情页 URL 与站点的图片根不是同一层），
            // 拼错会得到一条 200 但内容不是封面的地址，图源互证那一步就全错了。
            return ""
        }
        val absolute = if (value.startsWith("//")) "https:$value" else value
        // 站方常给缩略图服务的地址；`storageOriginal` 负责还原原件，占位图（no-image）回空串。
        return Fc2Parser.storageOriginal(absolute)
    }

    // ── 标题 ───────────────────────────────────────────────────────────────

    /**
     * 标题候选逐个剥番号，第一个剥完还非空的赢。
     *
     * 为什么要逐个而不是只剥第一个：`og:title` 经常就是「SSIS-001」，剥完全空；
     * 而 h1 是「独占配信 タイトル」——第一个候选剥空了不代表这一页没有标题。
     *
     * 全部剥空时退回第一个候选的**原样**（不剥）：只写番号的页面仍然有简介、演员、
     * 发行日，为一列标题丢掉整条记录不划算；那种页面的标题与番号同值，也不会误导用户。
     */
    private fun pickTitle(candidates: List<String>, code: String): String {
        for (raw in candidates) {
            val cleaned = cleanTitle(raw, code)
            if (cleaned.isNotEmpty()) return cleaned
        }
        val fallback = candidates.firstOrNull { it.isNotBlank() } ?: return ""
        return PeachHtml.text(stripDocumentSuffix(fallback))
    }

    private fun cleanTitle(raw: String, code: String): String {
        val value = PeachHtml.text(stripDocumentSuffix(raw))
        if (value.isEmpty()) return ""
        // 契约要求的这一步：Fc2Parser.stripCode 处理 `[FC2-PPV-123] 标题` 这类写法。
        // 但它只认 FC2 番号（正则里写死了 `FC2`），片商番号要另剥一次。
        val afterFc2 = Fc2Parser.stripCode(value, code)
        return stripLeadingCode(afterFc2, code).trim()
    }

    /**
     * 剥掉标题开头的本页番号（`SSIS-001 タイトル` → `タイトル`）。
     *
     * 允许的写法：大小写混排、番号段前后带括号、「字母段」与「数字段」之间可选连字符/下划线/
     * 点/空格、数字段允许前导零的任意个数（`SSIS-1` 与 `SSIS-001` 指同一部）。
     * 剥完为空就返回空串，让 [pickTitle] 去试下一个候选。
     */
    private fun stripLeadingCode(title: String, code: String): String {
        val letters = LETTERS.find(code)?.value ?: return title
        val digits = DIGITS.find(code)?.value?.trimStart('0').orEmpty()
        if (digits.isEmpty()) return title
        val head = "[\\s\\[【(（]*" + Regex.escape(letters) + "[-_. ]?0*" + Regex.escape(digits)
        val tail = "[\\s\\]】)）]*[-—:：|｜]?[\\s]*"
        val pattern = Regex("^$head$tail", RegexOption.IGNORE_CASE)
        return pattern.replace(title, "").trim()
    }

    /**
     * 去掉文档标题尾部挂着的站点名（`作品名 | S1`）。
     *
     * 只在「分隔符之后那一段不超过 24 字」时才切：真正的标题里也有竖线（`A｜B` 是作品名的
     * 一部分），但不会是一段 24 字以内的站名。这是启发式，所以只作用于 `<title>` 与带分隔符的
     * 候选——`og:title` / h1 里的竖线多半是作品名本身。
     */
    private fun stripDocumentSuffix(raw: String): String {
        var value = raw.trim()
        for (marker in listOf("｜", " | ", "｜ ", " - ")) {
            val at = value.lastIndexOf(marker)
            if (at <= 0 || value.length - at - marker.length > 24) continue
            // 「 - 」是最弱的一条：番号本身可能被写成 `SSIS-001 - 标题`，此时切掉的是番号，
            // 后面剥番号那一步还会再处理；这里只在「尾部像站名（不含书名号/空格）时」才切。
            if (marker == " - " && value.substring(at + marker.length).contains(' ')) continue
            value = value.substring(0, at).trim()
        }
        return value
    }

    // ── 数字/日期归一 ───────────────────────────────────────────────────────

    /**
     * 时长三种写法 → 时长数字，交给 [Fc2Parser.runtimeMinutes] 统一算分钟。
     *
     * - `41:50` / `1:23:45`：原样交出去（它的正则就是吃这个的）；
     * - `PT2H10M`（schema.org 的 ISO 8601 时长）：先算成总分钟再拼成 `H:MM:SS`；
     * - `130分` / `1時間30分` / 纯数字 `130`：同上。
     *
     * 为什么必须先归一：`runtimeMinutes` 只认「`:` 分隔的时钟写法」，直接喂 `130分` 会得到空串，
     * 而时长对账本（同片比对、去重）是有用的证据。反过来，如果在这里自己算带小数的分钟，
     * 又会与 FC2 那边的口径分裂成两套——所以只做「换算成时钟写法」，分/秒的取整留给它。
     */
    private fun toClock(raw: String): String {
        val value = raw.trim()
        if (value.isEmpty()) return ""
        if (CLOCK.matches(value)) {
            // 两段的 `2:10` 有歧义：`H:MM` 还是 `M:SS`。站方写収録時間时前者远多于后者，
            // 所以「第一段 ≤ 12」按小时算，否则按分钟算（`41:50` 是 41 分 50 秒）。
            val parts = value.split(':')
            if (parts.size == 2) {
                val first = parts[0].toIntOrNull() ?: return value
                return if (first <= 12) "${parts[0]}:${parts[1].padStart(2, '0')}:00" else value
            }
            return value
        }
        val iso = ISO_DURATION.find(value.uppercase())
        if (iso != null && iso.range.first == 0) {
            val days = iso.groupValues[1].toIntOrNull() ?: 0
            val hours = iso.groupValues[2].toIntOrNull() ?: 0
            val minutes = iso.groupValues[3].toIntOrNull() ?: 0
            val total = days * 24 * 60 + hours * 60 + minutes
            if (total > 0) return clockOf(total)
        }
        val jp = JP_DURATION.find(value)
        if (jp != null) {
            val hours = jp.groupValues[1].toIntOrNull() ?: 0
            val minutes = jp.groupValues[2].toIntOrNull() ?: 0
            val total = hours * 60 + minutes
            if (total > 0) return clockOf(total)
        }
        if (value.all { it.isDigit() }) {
            val minutes = value.toIntOrNull() ?: return ""
            if (minutes in 1..1440) return clockOf(minutes)
        }
        // 认不出就原样交出去，让 runtimeMinutes 自己决定要不要认——不在这里多造一套口径。
        return value
    }

    private fun clockOf(totalMinutes: Int): String =
        "${totalMinutes / 60}:${(totalMinutes % 60).toString().padStart(2, '0')}:00"

    /**
     * 站方日期三种写法 → `YYYY-MM-DD`：`2024-01-02`（已合法）、`2024/01/02`、`2024年1月2日`。
     *
     * 校验交给 [PeachHtml.isoDay]（它会把 `2024-13-99` 判非法）：发行日错一个月比空着更糟，
     * 这一栏下游会拿去和别的源比对，错值会被当成「冲突」。
     */
    private fun toIsoDay(raw: String): String {
        val value = raw.trim()
        if (value.isEmpty()) return ""
        val direct = PeachHtml.isoDay(value)
        if (direct.isNotEmpty()) return direct
        val match = JP_DATE.find(value) ?: return ""
        val year = match.groupValues[1]
        val month = match.groupValues[2].toIntOrNull() ?: return ""
        val day = match.groupValues[3].toIntOrNull() ?: return ""
        if (month !in 1..12 || day !in 1..31) return ""
        return "$year-${month.toString().padStart(2, '0')}-${day.toString().padStart(2, '0')}"
    }

    // ── 杂项取值 ────────────────────────────────────────────────────────────

    private fun firstText(obj: PeachJsonValue.Obj, vararg keys: String): String {
        for (key in keys) {
            val text = when (val value = obj[key]) {
                is PeachJsonValue.Str -> value.value
                is PeachJsonValue.Num -> value.raw
                else -> ""
            }.trim()
            if (text.isNotEmpty()) return PeachHtml.text(text)
        }
        return ""
    }

    private fun firstName(obj: PeachJsonValue.Obj, vararg keys: String): String {
        for (key in keys) {
            val name = namesOf(obj[key]).firstOrNull().orEmpty()
            if (name.isNotEmpty()) return name
        }
        return ""
    }

    /** 名字列表：字符串/数字/数组/对象（取 `name`）/对象数组，一律摊平。 */
    private fun namesOf(value: PeachJsonValue?): List<String> = when (value) {
        null, PeachJsonValue.Null -> emptyList()
        is PeachJsonValue.Str -> splitList(value.value)
        is PeachJsonValue.Num -> listOf(value.raw)
        is PeachJsonValue.Arr -> value.items.flatMap { namesOf(it) }
        is PeachJsonValue.Obj -> namesOf(value["name"]).ifEmpty { namesOf(value["title"]) }
        is PeachJsonValue.Bool -> emptyList()
    }

    private fun splitList(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        return raw.split(LIST_SEPARATOR).map { PeachHtml.text(it) }.filter { it.isNotEmpty() }.distinct()
    }

    private fun distinctClean(raw: List<String>): List<String> =
        raw.map { PeachHtml.text(it) }.filter { it.isNotEmpty() }.distinct()

    private fun looksLikeDetailPath(url: String): Boolean =
        DETAIL_PATH_HINTS.any { url.contains(it, ignoreCase = true) }

    private fun absoluteUrl(href: String, baseUrl: String): String {
        val value = href.trim()
        if (value.isEmpty()) return ""
        if (value.startsWith("http://") || value.startsWith("https://")) return value
        if (value.startsWith("//")) return "https:$value"
        val base = baseUrl.trim().ifEmpty { return "" }
        if (value.startsWith("/")) return originOf(base) + value
        val slash = base.lastIndexOf('/')
        return if (slash > 0) base.substring(0, slash + 1) + value else base + value
    }

    private fun originOf(url: String): String {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd <= 0) return ""
        val afterScheme = schemeEnd + 3
        val slash = url.indexOf('/', startIndex = afterScheme)
        return if (slash < 0) url else url.substring(0, slash)
    }

    // ── 层的中间结果 ─────────────────────────────────────────────────────────

    /** 一层的产出。字段为空串/空列表表示「这一层没有这一项」，由 [Facts] 决定要不要采用。 */
    private data class Raw(
        val titles: List<String> = emptyList(),
        val description: String = "",
        val release: String = "",
        val delivery: String = "",
        val runtime: String = "",
        val studio: String = "",
        val publisher: String = "",
        val series: String = "",
        val rating: String = "",
        val directors: List<String> = emptyList(),
        val actors: List<String> = emptyList(),
        val genres: List<String> = emptyList()
    )

    /** 逐字段「先到先得」的累加器；层序在 [layersFor] 里定，这里不判断优先级。 */
    private class Facts {
        val titles = ArrayList<String>()
        var description = ""
        var release = ""
        var delivery = ""
        var runtime = ""
        var studio = ""
        var publisher = ""
        var series = ""
        var rating = ""
        var directors: List<String> = emptyList()
        var actors: List<String> = emptyList()
        var genres: List<String> = emptyList()

        fun merge(raw: Raw) {
            for (title in raw.titles) {
                val value = title.trim()
                if (value.isNotEmpty() && titles.size < MAX_TITLE_CANDIDATES && !titles.contains(value)) {
                    titles += value
                }
            }
            if (description.isEmpty()) description = raw.description.trim()
            if (release.isEmpty()) release = raw.release.trim()
            if (delivery.isEmpty()) delivery = raw.delivery.trim()
            if (runtime.isEmpty()) runtime = raw.runtime.trim()
            if (studio.isEmpty()) studio = raw.studio.trim()
            if (publisher.isEmpty()) publisher = raw.publisher.trim()
            if (series.isEmpty()) series = raw.series.trim()
            if (rating.isEmpty()) rating = raw.rating.trim()
            if (directors.isEmpty()) directors = raw.directors
            if (actors.isEmpty()) actors = raw.actors
            if (genres.isEmpty()) genres = raw.genres
        }
    }

    /** 同一个 JSON-LD 里多个作品节点（Product + Movie）合并时也走「先到先得」。 */
    private fun mergeRaw(into: Raw, extra: Raw): Raw = Raw(
        titles = (into.titles + extra.titles).distinct(),
        description = into.description.ifEmpty { extra.description },
        release = into.release.ifEmpty { extra.release },
        delivery = into.delivery.ifEmpty { extra.delivery },
        runtime = into.runtime.ifEmpty { extra.runtime },
        studio = into.studio.ifEmpty { extra.studio },
        publisher = into.publisher.ifEmpty { extra.publisher },
        series = into.series.ifEmpty { extra.series },
        rating = into.rating.ifEmpty { extra.rating },
        directors = if (into.directors.isNotEmpty()) into.directors else extra.directors,
        actors = if (into.actors.isNotEmpty()) into.actors else extra.actors,
        genres = if (into.genres.isNotEmpty()) into.genres else extra.genres
    )

    // ── Inertia / Next.js 的内嵌 JSON ───────────────────────────────────────

    private fun inertiaRaw(text: String): Raw {
        var best: PeachJsonValue.Obj? = null
        for (block in JSON_SCRIPT.findAll(text)) {
            val root = PeachJson.parse(block.groupValues[1].trim()) ?: continue
            val budget = intArrayOf(MAX_JSON_NODES)
            val found = findWorkNode(root, 0, budget)
            if (found != null) {
                best = found
                break
            }
        }
        val node = best ?: return Raw()
        return Raw(
            titles = listOf(
                firstText(node, "title", "name", "title_name"),
                firstText(node, "original_title", "originalTitle", "title_original")
            ).filter { it.isNotBlank() }.distinct(),
            description = firstText(node, "description", "summary", "plot", "comment"),
            release = firstText(
                node, "release_date", "releaseDate", "sale_date", "saleDate",
                "start_date", "startDate", "public_start_date"
            ),
            delivery = firstText(node, "delivery_date", "deliveryDate", "streaming_start_date"),
            runtime = firstText(node, "runtime", "duration", "play_time", "playTime"),
            studio = firstName(node, "maker", "maker_name", "studio", "brand", "manufacturer"),
            publisher = firstName(node, "label", "label_name", "publisher"),
            series = firstName(node, "series", "series_name"),
            rating = firstText(node, "rating", "score", "rating_value"),
            directors = namesOf(node["director"]) + namesOf(node["directors"]),
            actors = namesOf(node["actress"]) + namesOf(node["actresses"]) +
                namesOf(node["actors"]) + namesOf(node["performer"]),
            genres = namesOf(node["genre"]) + namesOf(node["genres"]) +
                namesOf(node["tags"]) + namesOf(node["categories"])
        )
    }

    /**
     * 在内嵌 JSON 里找「作品节点」。
     *
     * 先按已知容器键（`props.work` / `article` / `product` …）下探，再退回全树扫描：
     * 容器键是快路径（S1 系就是 `props.work`），全树是兜底（站点改了层级但仍给数据）。
     * 节点数上限 [MAX_JSON_NODES] 防止一页塞了几兆 JSON 时把线程栈和 CPU 吃掉——
     * 那是崩溃，不是「解析失败」。
     */
    private fun findWorkNode(
        value: PeachJsonValue,
        depth: Int,
        budget: IntArray
    ): PeachJsonValue.Obj? {
        if (depth > MAX_JSON_DEPTH || budget[0] <= 0) return null
        budget[0] -= 1
        return when (value) {
            is PeachJsonValue.Obj -> {
                if (looksLikeWork(value)) return value
                for ((key, child) in value.fields) {
                    if (key.lowercase() in WORK_CONTAINER_KEYS) {
                        val found = findWorkNode(child, depth + 1, budget)
                        if (found != null) return found
                    }
                }
                for (child in value.fields.values) {
                    if (child is PeachJsonValue.Obj || child is PeachJsonValue.Arr) {
                        val found = findWorkNode(child, depth + 1, budget)
                        if (found != null) return found
                    }
                }
                null
            }
            is PeachJsonValue.Arr -> {
                for (item in value.items) {
                    if (item is PeachJsonValue.Obj || item is PeachJsonValue.Arr) {
                        val found = findWorkNode(item, depth + 1, budget)
                        if (found != null) return found
                    }
                }
                null
            }
            else -> null
        }
    }

    /** 有标题、且至少还有一个「作品页才有的」字段——避免把站点设置或用户资料当成作品。 */
    private fun looksLikeWork(obj: PeachJsonValue.Obj): Boolean {
        if (firstText(obj, "title", "name", "title_name").isEmpty()) return false
        val marker = firstText(
            obj, "release_date", "releaseDate", "sale_date", "delivery_date", "start_date",
            "runtime", "duration", "description", "summary", "maker", "label", "series"
        )
        return marker.isNotEmpty()
    }

    // ── 常量 ────────────────────────────────────────────────────────────────

    private val WORK_TYPES = setOf(
        "product", "movie", "creativework", "videoobject", "tvseries", "tvseason", "episode"
    )

    private val WORK_CONTAINER_KEYS = setOf(
        "work", "works", "article", "product", "item", "movie", "video", "data", "detail", "page"
    )

    private val DETAIL_PATH_HINTS = listOf(
        "/works/", "/work/", "/detail", "/product", "/item", "/movie", "/contents/", "/package", "/title"
    )

    private val LIST_SEPARATOR = Regex("[、,，／/|｜;；\n\r]+")

    private val LETTERS = Regex("[A-Za-z]+")
    private val DIGITS = Regex("\\d+")

    private val LD_SCRIPT = Regex(
        """<script[^>]*type\s*=\s*["']application/ld\+json["'][^>]*>(.*?)</script>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    private val JSON_SCRIPT = Regex(
        """<script[^>]*type\s*=\s*["']application/json["'][^>]*>(.*?)</script>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    private val META_TAG = Regex("""<meta\b[^>]*>""", RegexOption.IGNORE_CASE)

    private val ATTR = Regex("""([A-Za-z_:][-A-Za-z0-9_:.]*)\s*=\s*(?:"([^"]*)"|'([^']*)')""")

    private val H1 = Regex("""<h1\b[^>]*>(.*?)</h1>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

    private val DOC_TITLE = Regex(
        """<title\b[^>]*>(.*?)</title>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    private val DL_PAIR = Regex(
        """<dt\b[^>]*>(.*?)</dt>\s*<dd\b[^>]*>(.*?)</dd>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    private val TABLE_PAIR = Regex(
        """<tr\b[^>]*>\s*<th\b[^>]*>(.*?)</th>\s*<td\b[^>]*>(.*?)</td>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    private val WORK_PAGE_TITLE = Regex(
        """<h2\b[^>]*class\s*=\s*["'][^"']*\bp-workPage__title\b[^"']*["'][^>]*>(.*?)</h2>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    private val WORK_PAGE_TEXT = Regex(
        """<p\b[^>]*class\s*=\s*["'][^"']*\bp-workPage__text\b[^"']*["'][^>]*>(.*?)</p>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    /** 资料栏的标签格：`<div class="th">発売日</div>`（允许 class 里还有别的词）。 */
    private val WORK_PAGE_TH = Regex(
        """<div\b[^>]*class\s*=\s*["'][^"']*\bth\b[^"']*["'][^>]*>(.*?)</div>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    private val WORK_PAGE_ANCHOR = Regex(
        """<a\b[^>]*>(.*?)</a>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    /** 单个值块的扫描上限：只为封住「最后一块吃到页尾」，正常值都在几十字内。 */
    private val WORK_VALUE_LIMIT = 4_000

    private val ANCHOR = Regex(
        """<a\b[^>]*href\s*=\s*["']([^"']+)["'][^>]*>(.*?)</a>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    /** ISO 8601 时长：`PT2H10M`、`P1DT2H`。只取到分钟，秒对账本没有意义。 */
    private val ISO_DURATION = Regex("""^P(?:(\d+)D)?T?(?:(\d+)H)?(?:(\d+)M)?""", RegexOption.IGNORE_CASE)

    /** 日文时长：`1時間30分`、`130分`。 */
    private val JP_DURATION = Regex("""(?:(\d+)\s*時間)?\s*(\d+)\s*分""")

    /** 时钟写法；用 [Regex.matches] 做整串匹配，所以不需要 `$`/`^` 锚（避免 raw string 里的插值）。 */
    private val CLOCK = Regex("""\d{1,2}(?::\d{1,2}){1,2}""")

    /** `2024年1月2日` / `2024/01/02` / `2024.1.2`。 */
    private val JP_DATE = Regex("""(\d{4})\s*[年./-]\s*(\d{1,2})\s*[月./-]\s*(\d{1,2})""")

    private const val MAX_JSON_DEPTH = 8
    private const val MAX_JSON_NODES = 4000
    private const val MAX_TITLE_CANDIDATES = 8
}
