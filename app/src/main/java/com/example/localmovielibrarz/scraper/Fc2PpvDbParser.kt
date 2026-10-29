package com.example.localmovielibrarz.scraper

/**
 * FC2PPVDB 商品页（`https://fc2ppv-db.com/ja/videos/{裸数字}`）的解析。
 *
 * 为什么要单独解析：这一家是纯服务端渲染的 PHP 页面，没有内嵌 JSON，字段散在
 * `<h1>`、`<p>` 与 meta description 里，而且在三处用了不同写法（`2026年9月21日`、
 * `販売者: X / 公開日: Y / 再生時間: Z`、`<p>ラベル</p><p>値</p>`）。用一条通吃的正则
 * 抠不出来，只能按字段各给一条口径，把「取不到就降级」写清楚。
 *
 * 判定顺序为什么不能动（1 → 2 → 3 → 4）：
 * 1. **验证页先判**。Cloudflare 的挑战页同样回 200、HTML 也完整，直接往 h1 走会得到
 *    「h1 里没有这个番号」→ 判成 `NotFound`；而 `NotFound` 不冷却，链会立刻换源或重打，
 *    越打越深。归 `CloudflareChallenge` 才会让整源进冷却，用户看到的理由也才是
 *    「该更新 Cookie」。
 * 2. **再判年齢確認跳转**。年齢確認页有自己的 h1（「年齢確認」），第 3 步会把它判成
 *    `NotFound`，但那不是「站上没有」，而是「没有点过那道门」。这两档的处置完全不同：
 *    `AuthRequired` 重试也不会成功，只能让用户去浏览器点一次。
 * 3. **再核 h1 里的番号**。站上不存在的商品仍然回 200：要么是「見つかりませんでした」，
 *    要么软 404 渲染最近一部作品（h1 是别的番号）。两种都必须 `NotFound`（换源），
 *    而不是 `ParseError`（改版冷却）。
 * 4. **最后才是字段**。h1 已经确认「这一页就是这部片」之后，任何一块 DOM 取不到都只
 *    降级成空字段、不失败：为了一个标签丢整条记录，比少一个标签糟得多。
 *
 * 封面为什么必须为空：站上给的 360×360 缩略图过不了 peach 的封面门槛，交上去只会让
 * 探测白白跑一轮再被丢弃；这一家的封面由链上别家（官方文章页 / JAVten）补。
 */
object Fc2PpvDbParser {

    const val BASE_URL = "https://fc2ppv-db.com"

    private const val SOURCE = "FC2PPVDB"

    private const val NOT_FOUND = "FC2PPVDB 上没有这个商品"

    private const val CHALLENGE =
        "FC2PPVDB 要求 Cloudflare 验证，请在采集设置里更新 Cookie"

    private const val AGE_GATE = "FC2PPVDB 送到了年齢確認页，浏览器没有点过去"

    /** 年齢確認跳转的 URL 片段。 */
    private const val AGE_VERIFY_PATH = "/age-verify"

    private const val SOLD_LABEL = "販売日"

    private const val RUNTIME_LABEL = "再生時間"

    private const val ACTRESS_LABEL = "出演女優"

    private const val TAG_LABEL = "タグ"

    /** 取块时最多往前看这么多字符：块由「下一个字段标签」截断，窗口只是兜底。 */
    private const val BLOCK_WINDOW = 3_000

    /** 页面上可能紧跟在某个值块之后的字段标签，用来给 [blockAfter] 收口。 */
    private val SIBLING_LABELS = listOf(
        "出演女優", "タグ", "販売日", "配信日", "再生時間", "収録時間",
        "商品番号", "メーカー", "シリーズ", "ジャンル", "ファイル形式", "ファイルサイズ",
        "サンプル", "レビュー", "おすすめ", "関連作品"
    )

    /**
     * 字段标签的「文本节点」正则，构建一次。
     *
     * 锚定在 `>` 之后、`<` 之前：站上属性值里也会出现同样的词（`title="タグ"`），只按
     * 字面找会把块截在属性上。`[^<]{0,8}` 允许标签文字后面跟一点空白或冒号。
     */
    private val LABEL_NODES: Map<String, Regex> = SIBLING_LABELS
        .distinct()
        .associateWith { label -> Regex(""">\s*${Regex.escape(label)}[^<]{0,8}<""") }

    /** 页面上所有 `<a ...>...</a>` 的 attrs 与正文，交给下面的取值函数解析。 */
    private val ANCHOR = Regex(
        """<a\b([^>]*)>(.*?)</a>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    private val HREF = attribute("href")

    private val ALT = attribute("alt")

    private val IMG = Regex("""<img\b([^>]*)>""", RegexOption.IGNORE_CASE)

    /** 第一条 `<h1>`，正文第 1 组。 */
    private val H1 = Regex(
        """<h1\b[^>]*>(.*?)</h1>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    /** `<meta ...>` 整段，属性顺序不固定，所以按 tag 再逐个取属性。 */
    private val META_TAG = Regex("""<meta\b([^>]*)>""", RegexOption.IGNORE_CASE)

    private val PROPERTY = attribute("property")

    private val NAME = attribute("name")

    private val CONTENT = attribute("content")

    /** 紧跟在标签节点之后的第一个 `<p>`：`<p>ラベル</p><p>値</p>` 这种写法。 */
    private val PARAGRAPH = Regex(
        """<p\b[^>]*>(.*?)</p>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    private val JP_DATE = Regex("""(\d{4})年\s*(\d{1,2})月\s*(\d{1,2})日""")

    /** 链接文字开头的「販売者」前缀（有的页面把它写进链接本身）。 */
    private val SELLER_PREFIX = Regex("""^販売者\s*[:：]?\s*""")

    /**
     * 标签块里通常是导航/其它区块的链接，不参与标签收集。
     *
     * 这里**不能**写 `/tags`：标签本身的地址就是 `/ja/tags/xxx`，排掉它等于把标签全丢光。
     */
    private val NAVIGATION_FRAGMENTS = listOf(
        "/sellers/", "/actresses/", "/videos/", "/search", "/login", "/signup", "/page/"
    )

    /** 商品页地址；只在 `finalUrl` 为空时用来兜 sourceUrl。 */
    fun videoUrl(video: String): String = "$BASE_URL/ja/videos/$video"

    /**
     * 卖家。模型里没有 label / seller 字段，而本解析器按站点口径固定
     * `studio = publisher = FC2-PPV`，把卖家写进 studio 会让账本里的厂牌炸成几百份；
     * 需要时由调用方从这里取（与 [Fc2Parser] 把卖家当证据的处理一致）。
     */
    data class Seller(val name: String, val page: String)

    /**
     * 解析商品页。
     *
     * @param html 商品页正文。
     * @param code 查询用的账本番号。
     * @param finalUrl 跟随跳转之后的最终地址（用来识别年齢確認跳转，也写进 `website`）。
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
        // 2. 年齢確認：URL 才是证据，正文里只是一个没有点过的门。
        if (finalUrl.contains(AGE_VERIFY_PATH, ignoreCase = true)) {
            return PeachParseResult.Failed(PeachFailureReason.AuthRequired, AGE_GATE)
        }
        // 3. 空页与「h1 里没有这个番号」分开：前者是没取到页，后者是站上没有。
        if (text.isBlank()) {
            return PeachParseResult.Failed(PeachFailureReason.ParseError, "FC2PPVDB 页面为空")
        }
        val heading = PeachHtml.text(H1.find(text)?.groupValues?.get(1).orEmpty())
        if (heading.isEmpty() || !codeIn(heading, wanted)) {
            return PeachParseResult.Failed(PeachFailureReason.NotFound, NOT_FOUND)
        }

        // 4. 字段层：任何一块取不到都只降级成空串。
        val canonical = Fc2Parser.canonicalCode(wanted)
        val description = descriptionFields(text)
        val title = Fc2Parser.stripCode(heading, wanted)
        val premiered = japaneseDay(valueAfterLabel(text, SOLD_LABEL))
            .ifBlank { japaneseDay(description["公開日"].orEmpty()) }
        val runtime = Fc2Parser.runtimeMinutes(description[RUNTIME_LABEL].orEmpty())
            .ifBlank { Fc2Parser.runtimeMinutes(valueAfterLabel(text, RUNTIME_LABEL)) }
        val tags = tagNames(text)
        val website = finalUrl.ifBlank { videoUrl(wanted) }

        val info = ScrapedMovieInfo(
            number = canonical,
            title = title.ifBlank { canonical },
            originalTitle = title.ifBlank { canonical },
            plot = "",
            outline = "",
            year = Regex("""\d{4}""").find(premiered)?.value.orEmpty(),
            premiered = premiered,
            runtime = runtime,
            studio = Fc2Parser.STUDIO,
            publisher = Fc2Parser.STUDIO,
            series = "",
            actors = actressNames(text),
            genres = tags,
            tags = tags,
            website = website,
            source = SOURCE,
            // 站上只有 360×360 缩略图，过不了封面门槛，peach 显式不交封面。
            thumbUrl = "",
            posterUrl = ""
        )
        return PeachParseResult.Record(info = info, sourceUrl = website, coverUrls = emptyList())
    }

    /** 卖家名与卖家主页；页面为空或都没有时给空串。 */
    fun seller(html: String?): Seller {
        val text = html.orEmpty()
        if (text.isBlank()) return Seller("", "")
        val description = descriptionFields(text)
        return Seller(sellerName(text, description), sellerPage(text))
    }

    /**
     * h1 里有没有 `FC2-PPV-<裸数字>`。
     *
     * 负向前瞻 `(?!\d)` 不能省：`4137487` 是 `41374870` 的前缀，只做 contains 会把
     * 另一个番号判成本番号，于是把别人的标题、时长、女优写到这个号头上。
     */
    private fun codeIn(text: String, wanted: String): Boolean =
        Regex("""FC2-PPV-${Regex.escape(wanted)}(?!\d)""", RegexOption.IGNORE_CASE)
            .containsMatchIn(text)

    /**
     * 取某个标签节点之后、下一个字段标签之前的那一块。
     *
     * 口径（对应用户提到的 `_block_after`）：**标签节点的上两层容器才装着值**——标签节点
     * 自己那一层只有「出演女優」四个字，值在它的兄弟节点里，也就是隔着标签自己的结束标签
     * 再往上两层。所以取块时必须先跨过标签自己的结束标签，再往后取，直到遇到下一个字段
     * 标签（[SIBLING_LABELS]）或撞上 [BLOCK_WINDOW]；停在标签自身容器上会取到空块。
     *
     * 找不到标签时返回空串：调用方据此交空列表，**不**把它当成解析失败——一块 DOM 改版
     * 不该让整条记录失败。
     */
    private fun blockAfter(html: String, label: String): String {
        val node = labelNode(html, label, 0) ?: return ""
        val start = node.last
        if (start <= 0 || start >= html.length) return ""
        var end = minOf(html.length, start + BLOCK_WINDOW)
        for (other in SIBLING_LABELS) {
            if (other == label) continue
            // 从 start 往后找：同一个词在前面的字段里出现过（比如标题里带「タグ」），
            // 从 0 起找会把块截成空串。
            val next = labelNode(html, other, start) ?: continue
            if (next.first < end) end = next.first
        }
        return html.substring(start, end)
    }

    /** 标签的文本节点：要求前后是 `>` / `<`，避免命中属性值里的同一个词。 */
    private fun labelNode(html: String, label: String, from: Int): IntRange? =
        LABEL_NODES[label]?.find(html, from)?.range

    /** `出演女優` 块里的女优名：优先 `img.alt`，其次链接文字；同名去重保序。 */
    private fun actressNames(html: String): List<String> {
        val block = blockAfter(html, ACTRESS_LABEL)
        if (block.isEmpty()) return emptyList()
        return anchors(block)
            .filter { it.href.contains("/actresses/", ignoreCase = true) }
            .map { anchorName(it) }
            .filter { it.isNotEmpty() && !it.contains("情報がありません") }
            .distinct()
    }

    /** `タグ` 块里的链接文字；导航类链接与页码不参与。 */
    private fun tagNames(html: String): List<String> {
        val block = blockAfter(html, TAG_LABEL)
        if (block.isEmpty()) return emptyList()
        return anchors(block)
            .filter { !isNavigation(it.href) }
            .map { PeachHtml.text(it.inner) }
            .filter { it.isNotEmpty() && it.any { ch -> !ch.isDigit() } }
            .distinct()
    }

    private fun isNavigation(href: String): Boolean {
        if (href.isEmpty()) return false
        val value = href.lowercase()
        return NAVIGATION_FRAGMENTS.any { value.contains(it) }
    }

    private fun sellerName(html: String, description: Map<String, String>): String {
        val described = description["販売者"].orEmpty()
        if (described.isNotEmpty()) return described
        val anchor = sellerAnchor(html) ?: return ""
        return SELLER_PREFIX.replace(anchorName(anchor), "").trim()
    }

    private fun sellerPage(html: String): String {
        val anchor = sellerAnchor(html) ?: return ""
        val slug = anchor.href
            .substringBefore('?')
            .substringBefore('#')
            .trimEnd('/')
            .substringAfterLast('/')
        return if (slug.isEmpty()) "" else Fc2Parser.sellerUrl(slug)
    }

    /** 页面上第一个指向卖家页的链接。 */
    private fun sellerAnchor(html: String): Anchor? =
        anchors(html).firstOrNull { it.href.contains("/sellers/", ignoreCase = true) }

    /**
     * 链接的显示名：`img.alt` 优先，其次链接文字。
     *
     * 女优与卖家在这家站上都是「头像 + 名字」的写法，名字常常只写在 alt 里，链接正文是空的。
     */
    private fun anchorName(anchor: Anchor): String {
        val alt = IMG.find(anchor.inner)?.let { attributeValue(it.groupValues[1], ALT) }.orEmpty()
        val fromAlt = PeachHtml.text(alt)
        if (fromAlt.isNotEmpty()) return fromAlt
        return PeachHtml.text(anchor.inner)
    }

    /**
     * 取 meta description 里 `キー: 値 / キー: 値` 形式的那几项。
     *
     * 按 ` / ` 切段而不是用一条大正则：字段可能缺项、顺序也可能变（实测有的页面没有
     * `再生時間`），按段解析缺一项不影响其它项。代价是卖家名里若出现斜杠会被切断——
     * 这个字段只作证据，切断也不影响账本。
     */
    private fun descriptionFields(html: String): Map<String, String> {
        val description = metaContent(html, "description")
        if (description.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        val normalized = description.replace('｜', '/').replace('|', '/')
        for (segment in normalized.split('/')) {
            val part = segment.trim()
            if (part.isEmpty()) continue
            val colon = part.indexOfFirst { it == ':' || it == '：' }
            if (colon <= 0) continue
            val key = part.substring(0, colon).trim()
            val value = part.substring(colon + 1).trim()
            if (key.isNotEmpty() && value.isNotEmpty()) out[key] = value
        }
        return out
    }

    /** `<p>ラベル</p>` 后面那一个 `<p>` 的正文。 */
    private fun valueAfterLabel(html: String, label: String): String {
        val marker = Regex(
            """<p\b[^>]*>\s*${Regex.escape(label)}\s*</p>""",
            RegexOption.IGNORE_CASE
        ).find(html) ?: return ""
        val tail = html.substring(marker.range.last + 1)
        val next = PARAGRAPH.find(tail) ?: return ""
        return PeachHtml.text(next.groupValues[1])
    }

    /**
     * `2026年9月21日` → `2026-09-21`；已经是 ISO 的原样过一遍校验。
     *
     * 非法值（`2026年13月45日`、只有月份、空）返回空串而不是猜一个：发行日错一个月比空着
     * 更糟，而下游把空 `premiered` 当「缺」，会继续问下一家。
     */
    private fun japaneseDay(raw: String): String {
        val value = raw.trim()
        if (value.isEmpty()) return ""
        val found = JP_DATE.find(value) ?: return PeachHtml.isoDay(value)
        val year = found.groupValues[1]
        val month = found.groupValues[2].toIntOrNull() ?: return ""
        val day = found.groupValues[3].toIntOrNull() ?: return ""
        return PeachHtml.isoDay("$year-${month.toString().padStart(2, '0')}-${day.toString().padStart(2, '0')}")
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
                inner = match.groupValues[2]
            )
        }.toList()

    private data class Anchor(val href: String, val inner: String)

    /**
     * 单属性的取值正则。
     *
     * 前面要求空白或开头，是为了不让 `data-class` 被当成 `class` 命中；两个分组对应
     * 双引号与单引号两种写法，调用方取非空的那一组。属性顺序在站上不固定，所以按
     * 「属性名 → 值」无关顺序地扫，而不是拼一整条匹配整段标签的正则。
     */
    private fun attribute(name: String): Regex = Regex(
        """(?:\s|^)${Regex.escape(name)}\s*=\s*(?:"([^"]*)"|'([^']*)')""",
        RegexOption.IGNORE_CASE
    )

    private fun attributeValue(attrs: String, pattern: Regex): String {
        val found = pattern.find(attrs) ?: return ""
        return found.groupValues[1].ifEmpty { found.groupValues[2] }.trim()
    }
}