package com.example.localmovielibrarz.scraper

/**
 * JAVten（`https://javten.com`）的搜索、作品链接归一与作品页解析。
 *
 * 为什么要单独解析：这一家对 FC2 番号的回答是「搜索页 + 多语言镜像」两层结构。
 * 搜索页本身只有卡片，卡片链接带 `id{裸数字}` 段，并且可能来自 `/tw/`、`/en/`、`/ko/`
 * 三种语言前缀；作品页又把番号写在 `h1.fc2-id` 里、把时长与卖家塞进 meta description
 * 的一条 `| By 卖家 | 41:50 |` 里。按「URL 段 + 整串相等 + meta 尾部」三处分开取，
 * 才能既收下日文原页，又不把译文页的资料写进账本。
 *
 * 判定顺序为什么不能动：
 * 1. **验证页先判**。Cloudflare 挑战页回 200，正文没有 `h1.fc2-id`，先走第 2 步会得到
 *    `NotFound`；而 `NotFound` 不冷却，链会立刻重打，越打越深。归 `CloudflareChallenge`
 *    才会让整源冷却，用户看到的理由也才是「更新 Cookie」。
 * 2. **再核 h1 整串**。搜索页会回一堆卡片，软 404 会回上一部作品；两者都可能带着别的
 *    `fc2-id`。只要不是**整串等于** `FC2-PPV-<裸数字>` 就归 `NotFound`（换源），
 *    而不是 `ParseError`（改版冷却）。
 * 3. **最后判译文页**。译文页的 `h1.fc2-id` 与日文页完全一样，第 2 步放得过；但标题是
 *    机翻的、时长与卖家字段名也换了语言，写进账本会把同一条记录翻来覆去地改。
 *    这一档归 `ParseError`：换一家问，别把这个号记成「站上没有」。
 *
 * 封面：`og:image` 是主候选，`a[data-fancybox][href]` 是大图；两者都过
 * [Fc2Parser.storageOriginal]，因为 JAVten 的 og:image 常常仍是 FC2 的缩略图服务地址。
 */
object JavtenParser {

    const val BASE_URL = "https://javten.com"

    private const val SOURCE = "JAVten"

    private const val NOT_FOUND = "JAVten 上没有这个商品"

    private const val CHALLENGE = "JAVten 要求 Cloudflare 验证，请在采集设置里更新 Cookie"

    private const val TRANSLATED = "JAVten 回的是译文页，只收日文原页"

    /** 搜索页：`kw` 只放裸数字，别把连字符写进去。 */
    fun searchUrl(code: String): String {
        val wanted = Fc2Parser.videoId(code)
        return if (wanted.isEmpty()) "" else "$BASE_URL/search?kw=$wanted"
    }

    /**
     * 搜索页里的作品链接，只留 `id` 段与裸数字完全相同的那几条。
     *
     * 统一折算成不带语言前缀的写法：同一个作品在 `/tw/`、`/en/`、`/ko/` 下各有一份链接，
     * 不折算的话搜索页一张卡片就能让账本里出现四条同一部片。
     */
    fun workLinks(html: String?, code: String): List<String> {
        val wanted = Fc2Parser.videoId(code)
        if (wanted.isEmpty() || html.isNullOrBlank()) return emptyList()
        val links = LinkedHashSet<String>()
        for (match in WORK_LINK.findAll(html)) {
            if (match.groupValues[3] != wanted) continue
            links += "$BASE_URL/video/${match.groupValues[2]}/id$wanted/${match.groupValues[4]}"
        }
        return links.toList()
    }

    /**
     * 解析作品页。
     *
     * @param html 作品页正文。
     * @param code 查询用的账本番号。
     * @param finalUrl 跟随跳转之后的最终地址；带 `/tw/` `/en/` `/ko/` 的判成译文页。
     */
    fun parse(html: String?, code: String, finalUrl: String): PeachParseResult {
        val wanted = Fc2Parser.videoId(code)
        if (wanted.isEmpty()) {
            return PeachParseResult.Failed(PeachFailureReason.NotFound, Fc2Parser.UNRECOGNISED)
        }
        val text = html.orEmpty()

        // 1. 验证页：200 + 正常 HTML，只能看正文标题。
        if (ChallengePageDetector.isChallengePage(text)) {
            return PeachParseResult.Failed(PeachFailureReason.CloudflareChallenge, CHALLENGE)
        }
        if (text.isBlank()) {
            return PeachParseResult.Failed(PeachFailureReason.ParseError, "JAVten 页面为空")
        }
        // 2. 番号要整串相等：搜索页与软 404 都可能带着别的 fc2-id。
        val expected = "${Fc2Parser.STUDIO}-$wanted"
        if (idHeading(text).uppercase() != expected) {
            return PeachParseResult.Failed(PeachFailureReason.NotFound, NOT_FOUND)
        }
        // 3. 译文页：番号一样，但标题与字段名都不是日文原文。
        if (isTranslated(finalUrl)) {
            return PeachParseResult.Failed(PeachFailureReason.ParseError, TRANSLATED)
        }

        val canonical = Fc2Parser.canonicalCode(wanted)
        val by = byLine(text)
        val title = Fc2Parser.stripCode(titleText(text), wanted)
        val tags = tagNames(text)
        val covers = coverCandidates(text)
        val website = finalUrl
        // 前 10 字符必须是 ISO 日期形状；不合法就留空，不算失败（发行日错一个月比空着更糟）。
        val premiered = PeachHtml.isoDay(metaContent(text, PUBLISHED_TIME))

        val info = ScrapedMovieInfo(
            number = canonical,
            title = title.ifBlank { canonical },
            originalTitle = title.ifBlank { canonical },
            plot = "",
            outline = "",
            year = YEAR.find(premiered)?.value.orEmpty(),
            premiered = premiered,
            runtime = Fc2Parser.runtimeMinutes(by?.groupValues?.get(2).orEmpty()),
            studio = Fc2Parser.STUDIO,
            publisher = Fc2Parser.STUDIO,
            series = "",
            // 这一页没有演员栏（FC2 索引用的是「卖家」，不是女优）。
            actors = emptyList(),
            genres = tags,
            tags = tags,
            website = website,
            source = SOURCE,
            thumbUrl = covers.firstOrNull().orEmpty(),
            posterUrl = covers.firstOrNull().orEmpty()
        )
        return PeachParseResult.Record(
            info = info,
            sourceUrl = website,
            coverUrls = covers
        )
    }

    /**
     * 卖家名：meta description 里 `| By 卖家 | 41:50 |` 的第一个字段，取不到再退到
     * `a[href*="/seller/"]` 的链接文字。
     *
     * 为什么不写进 [ScrapedMovieInfo]：那个模型没有 label / seller 字段，而本解析器按
     * 站点口径固定 `studio = publisher = FC2-PPV`（发行方），把卖家写进 studio 会让账本里
     * 的厂牌炸成几百份。需要时由调用方从这里取（与 [Fc2Parser] 把卖家当证据一致）。
     */
    fun seller(html: String?): String {
        val text = html.orEmpty()
        if (text.isBlank()) return ""
        val described = PeachHtml.text(byLine(text)?.groupValues?.get(1).orEmpty())
        if (described.isNotEmpty()) return described
        val anchor = anchors(text).firstOrNull { it.href.contains("/seller/", ignoreCase = true) }
        return if (anchor == null) "" else PeachHtml.text(anchor.inner)
    }

    /**
     * 作品页的编号标题。
     *
     * 先找 `h1.fc2-id`；站点改版把 class 挪走时退到第一条 `<h1>`——比对仍然是**整串相等**，
     * 所以退路不会放过译文页或别家番号，只是免得一次模板调整就把这家的解析整段废掉。
     */
    private fun idHeading(html: String): String {
        for (match in H1.findAll(html)) {
            val tokens = attributeValue(match.groupValues[1], CLASS)
            if (hasToken(tokens, "fc2-id")) return PeachHtml.text(match.groupValues[2])
        }
        return PeachHtml.text(H1.find(html)?.groupValues?.get(2).orEmpty())
    }

    /** 标题：`og:title` 优先；没有就取 `h1.card-title`（排除 `h1.fc2-id`，那条是番号）。 */
    private fun titleText(html: String): String {
        val og = metaContent(html, "og:title")
        if (og.isNotEmpty()) return og
        for (match in H1.findAll(html)) {
            val tokens = attributeValue(match.groupValues[1], CLASS)
            if (!hasToken(tokens, "card-title") || hasToken(tokens, "fc2-id")) continue
            return PeachHtml.text(match.groupValues[2])
        }
        return ""
    }

    /** 封面候选：`og:image` + `a[data-fancybox]` 的大图，过一遍原件化再按出现顺序去重。 */
    private fun coverCandidates(html: String): List<String> {
        val out = LinkedHashSet<String>()
        val primary = imageUrl(metaContent(html, "og:image"))
        if (primary.isNotEmpty()) out += primary
        for (anchor in anchors(html)) {
            if (!FANCYBOX.containsMatchIn(anchor.attrs) || anchor.href.isEmpty()) continue
            val candidate = imageUrl(anchor.href)
            if (candidate.isNotEmpty()) out += candidate
        }
        return out.toList()
    }

    /**
     * 封面地址归一：去实体转义与空白、补协议、交给 [Fc2Parser.storageOriginal] 还原原件。
     *
     * `//` 开头的协议相对地址必须补成 `https:`：账本里的封面地址会被直接拿去下载，
     * 少了协议那一层就是一次「来源连接未取得」。
     */
    private fun imageUrl(raw: String): String {
        val value = PeachHtml.text(raw)
        if (value.isEmpty()) return ""
        val absolute = if (value.startsWith("//")) "https:$value" else value
        return Fc2Parser.storageOriginal(absolute)
    }

    private fun tagNames(html: String): List<String> =
        anchors(html)
            .filter { hasToken(it.className, "badge") && it.href.contains("/tag/", ignoreCase = true) }
            .map { PeachHtml.text(it.inner) }
            .filter { it.isNotEmpty() }
            .distinct()

    /** meta description 里 `| By 卖家 | 时长 |` 那一段；第一个分组是卖家，第二个是时长。 */
    private fun byLine(html: String): MatchResult? =
        BY_TIME.find(metaContent(html, "description"))

    private fun isTranslated(finalUrl: String): Boolean {
        val value = finalUrl.lowercase()
        return TRANSLATED_PREFIXES.any { value.contains(it) }
    }

    /** 按 `name` / `property` 取 meta 的正文；找不到返回空串。 */
    private fun metaContent(html: String, key: String): String {
        for (meta in META_TAG.findAll(html)) {
            val attrs = meta.groupValues[1]
            val property = attributeValue(attrs, PROPERTY)
            val name = attributeValue(attrs, NAME)
            if (!property.equals(key, ignoreCase = true) && !name.equals(key, ignoreCase = true)) continue
            return PeachHtml.text(attributeValue(attrs, CONTENT))
        }
        return ""
    }

    private fun anchors(html: String): List<Anchor> =
        ANCHOR.findAll(html).map { match ->
            Anchor(
                href = attributeValue(match.groupValues[1], HREF),
                className = attributeValue(match.groupValues[1], CLASS),
                attrs = match.groupValues[1],
                inner = match.groupValues[2]
            )
        }.toList()

    private data class Anchor(val href: String, val className: String, val attrs: String, val inner: String)

    /** class 里有没有某个 token（`badge` 与 `card-title` 都是 token 比对，不是子串）。 */
    private fun hasToken(className: String, token: String): Boolean =
        className.split(' ', '\t', '\n', '\r').any { it.isNotEmpty() && it.equals(token, ignoreCase = true) }

    /**
     * 单属性的取值正则。
     *
     * 前面要求空白或开头，是为了不让 `data-class` 被当成 `class` 命中；两个分组对应
     * 双引号与单引号两种写法，调用方取非空的那一组。它同时覆盖了「属性顺序不固定」这种
     * 常见改版——拼一条匹配整段标签的正则换个顺序就失效。
     */
    private fun attribute(name: String): Regex = Regex(
        """(?:\s|^)${Regex.escape(name)}\s*=\s*(?:"([^"]*)"|'([^']*)')""",
        RegexOption.IGNORE_CASE
    )

    private fun attributeValue(attrs: String, pattern: Regex): String {
        val found = pattern.find(attrs) ?: return ""
        return found.groupValues[1].ifEmpty { found.groupValues[2] }.trim()
    }

    private val YEAR = Regex("""\d{4}""")

    private const val PUBLISHED_TIME = "videos:published_time"

    /** 搜索页卡片链接：语言前缀可有可无，`id` 段才是番号。 */
    private val WORK_LINK = Regex(
        """https?://javten\.com/(?:(tw|en|ko)/)?video/(\d+)/id(\d+)/([^"'\s#?]*)""",
        RegexOption.IGNORE_CASE
    )

    private val TRANSLATED_PREFIXES = listOf("/tw/", "/en/", "/ko/")

    private val BY_TIME = Regex("""\|\s*By\s+(.*?)\s*\|\s*(\d{1,2}:\d{2}(?::\d{2})?)\s*\|""")

    private val H1 = Regex(
        """<h1\b([^>]*)>(.*?)</h1>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    private val ANCHOR = Regex(
        """<a\b([^>]*)>(.*?)</a>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    private val META_TAG = Regex("""<meta\b([^>]*)>""", RegexOption.IGNORE_CASE)

    private val FANCYBOX = Regex("""(?:\s|^)data-fancybox\b""", RegexOption.IGNORE_CASE)

    private val HREF = attribute("href")

    private val CLASS = attribute("class")

    private val PROPERTY = attribute("property")

    private val NAME = attribute("name")

    private val CONTENT = attribute("content")
}