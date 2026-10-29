package com.example.localmovielibrarz.scraper

/**
 * FC2 内容市场商品页的解析（peach `sources/fc2.py` 的 Kotlin 移植）。
 *
 * 为什么 FC2 必须是链上的第一家而不是交给目录站：FC2 不是 JAV，番号是卖家自己的投稿号。
 * 目录站按它去查要么没有、要么撞上别的片——peach 实测 r18.dev 对 85 条 FC2 番号问了 85 条
 * 全空，AVBase 与 JavBus 一律回「没有这个番号」。发行方自己那一页才是这批番号的正主：
 * 不要凭据，一次请求给全标题、说明、卖家、商品标签、时长、販売日和封面原图。
 *
 * 页面主体在 `<script type="application/ld+json">` 的 Product 里，正文 DOM 只补它没有的那几项。
 * 这一点决定了实现形状：**必须先把那段 JSON 单独取出来解析**，不能整体当 HTML 扫——
 * 说明文字里带引号与换行是常态，用正则从 HTML 里抠字段会在这里出错。
 *
 * 下架的商品仍回 200，靠正文里没有 Product 判定，归 `not_found`，**不当成抓取失败**：
 * 那种情况下换下一家（fc2cmadb / FC2PPV-DB / JAVten / JavArchive）问才是对的，
 * 而不是把整站打进冷却。
 *
 * 带分段后缀的番号（`FC2-PPV-3312576-1`）一律认不出商品号，于是一处都不问。这是对的：
 * 合集的封面套给每个分段，屏幕上就是 21 个不同内容顶着同一张图。
 *
 * 官方那一页**不取演员**：它没有演员栏，标题里那个名字是卖家自己写的宣传语，
 * `みお(19)` 这样的写法既不是艺名也没有第二处可以印证。
 */
object Fc2Parser {

    const val BASE_URL = "https://adult.contents.fc2.com"

    /** 账本里 FC2 一律记在这个厂牌下，新抓的跟着走，免得同一批内容分裂成两个厂牌实体。 */
    const val STUDIO = "FC2-PPV"

    /** 番号认不出商品号时三站共用的那一句。 */
    const val UNRECOGNISED = "这个番号认不出 FC2 商品号"

    private val VIDEO_ID = Regex("^FC2(?:[-_. ]?PPV)?[-_. ]?(\\d{5,})$", RegexOption.IGNORE_CASE)

    /**
     * `41:50` 与 `1:23:45` 两种都有。
     *
     * 不锚定整串：调用方交给 [runtimeMinutes] 的通常是「収録時間：41:50」这样带字段名的正文，
     * 而 peach 那边拿到的是 `p.items_article_info` 整段的 join 文本——它同样带着字段名，
     * 却用了 `^…$`。这里是刻意分叉：字段名是站方文案，改一个汉字就会让整条时长归零，
     * 而时长本身对账本（去重、同片比对）是有用的证据，不值得为对齐 peach 的正则写法丢掉。
     * 两侧的边界（前后不接数字）保证不会从 `2024/01/02` 这类日期里抠出时长。
     */
    private val RUNTIME = Regex("(?<![\\d:])(?:(\\d{1,2}):)?(\\d{1,2}):(\\d{2})(?!\\d)")

    private val SOLD_ON = Regex("販売日\\s*[:：]\\s*(\\d{4})/(\\d{2})/(\\d{2})")

    /**
     * 缩略图服务 → 原件地址：`https://contents-thumbnail2.fc2.com/w276/storage92000.contents.fc2.com/file/…`
     * 前半截是缩放服务，后半截就是原件。
     */
    private val THUMBNAIL_WRAPPER = Regex(
        "^https?://contents-thumbnail\\d*\\.fc2\\.com/w\\d+/(storage[\\w.-]+\\.fc2\\.com/.+)$",
        RegexOption.IGNORE_CASE
    )

    /** 站方的占位件（没有商品图时回的那张），当封面交下去只会得到「来源连接未取得」。 */
    private val NO_IMAGE = Regex("/no-image\\.\\w+$", RegexOption.IGNORE_CASE)

    private val LD_JSON = Regex(
        "<script[^>]*type=\"application/ld\\+json\"[^>]*>(.*?)</script>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    /** 正文里的信息段（时长写在这里）。 */
    private const val ARTICLE_INFO_MARKER = "items_article_info"

    /** 商品标签容器的 class。 */
    private const val TAG_AREA_MARKER = "items_article_TagArea"

    /** 卖家名所在的头部链接。 */
    private const val SELLER_LINK_MARKER = "/users/"

    /** 账本番号 → 商品号；认不出来的回空串。 */
    fun videoId(code: String): String =
        VIDEO_ID.find(code.trim())?.groupValues?.get(1).orEmpty()

    /** 商品号 → 账本写法。 */
    fun canonicalCode(video: String): String {
        val digits = video.trim()
        return if (digits.isNotEmpty() && digits.all { it.isDigit() }) "FC2-PPV-$digits" else ""
    }

    fun articleUrl(video: String): String =
        if (video.isEmpty()) "" else "$BASE_URL/article/$video/"

    fun sellerUrl(slug: String): String = "$BASE_URL/users/$slug/"

    /**
     * `41:50` / `1:23:45` → 分钟数；认不出或为 0 返回空串。
     *
     * 保持两位小数：FC2 的时长常是 41:50（= 41.83 分），截成 41 会把同一部片的两次抓取
     * 判成不同的值，字段级合并会把它当成「冲突」。
     */
    fun runtimeMinutes(raw: String): String {
        val found = RUNTIME.find(raw.trim()) ?: return ""
        val hours = found.groupValues[1].toIntOrNull() ?: 0
        val minutes = found.groupValues[2].toIntOrNull() ?: 0
        val seconds = found.groupValues[3].toIntOrNull() ?: 0
        val total = hours * 60 + minutes + seconds / 60.0
        if (total <= 0.0) return ""
        val rounded = Math.round(total * 100.0) / 100.0
        return if (rounded == Math.floor(rounded)) rounded.toLong().toString() else rounded.toString()
    }

    /**
     * 缩略图地址 → 原件地址。站方的占位件回空串，已经是原件的原样返回。
     */
    fun storageOriginal(url: String): String {
        val address = url.trim()
        if (address.isEmpty()) return ""
        val path = address.substringAfter("://", address).substringAfter('/', "")
        if (NO_IMAGE.containsMatchIn("/$path")) return ""
        val found = THUMBNAIL_WRAPPER.find(address)
        return if (found != null) "https://${found.groupValues[1]}" else address
    }

    /**
     * 标题开头那截番号剥掉。
     *
     * 各站写法不一（`FC2-PPV-4137487`、`FC2PPV 1863914`、`[FC2-PPV-4898837]` 都有），
     * 留着就把番号写进了标题——标题是用户最先看到的那一行。
     */
    fun stripCode(title: String, wanted: String): String {
        if (wanted.isEmpty()) return title.trim()
        val pattern = Regex(
            "^\\s*\\[?\\s*FC2[-_. ]?(?:PPV)?[-_. ]?${Regex.escape(wanted)}\\s*\\]?\\s*[-—:：]?\\s*",
            RegexOption.IGNORE_CASE
        )
        return pattern.replace(title, "").trim()
    }

    /**
     * 解析商品页。
     *
     * @param html 商品页正文。
     * @param code 查询用的账本番号。
     */
    fun parse(html: String?, code: String): PeachParseResult {
        val text = html.orEmpty()
        val wanted = videoId(code)
        if (wanted.isEmpty()) {
            return PeachParseResult.Failed(PeachFailureReason.NotFound, UNRECOGNISED)
        }
        if (text.isBlank()) {
            return PeachParseResult.Failed(PeachFailureReason.ParseError, "FC2 商品页为空")
        }

        val product = productBlock(text)
        // 下架的商品这段整个不出现；`article/<id>/` 取不到商品时 FC2 回的是一个 200 的
        // 「見つかりませんでした」页。两者都是「站上没有」，不是抓取失败。
        if (product == null) {
            val reason = if (ChallengePageDetector.isChallengePage(text)) {
                PeachFailureReason.CloudflareChallenge
            } else {
                PeachFailureReason.NotFound
            }
            return PeachParseResult.Failed(reason, "FC2 上没有这个商品")
        }
        val sku = product["sku"].stringOrEmpty().trim()
        // 番号要核：站上的商品号会被复用给别的投稿，不核就会把另一部片的资料写到这个番号头上。
        if (sku.isNotEmpty() && sku != wanted) {
            return PeachParseResult.Failed(PeachFailureReason.NotFound, "FC2 上的商品号对不上这个番号")
        }

        val title = stripCode(PeachHtml.text(product["name"].stringOrEmpty()), wanted)
        val description = PeachHtml.text(product["description"].stringOrEmpty())
        val image = product["image"] as? PeachJsonValue.Obj
        val cover = storageOriginal(image?.get("url").stringOrEmpty())
        val brand = product["brand"] as? PeachJsonValue.Obj
        // `brand.name` 实测常是 null；`brand.url` 是卖家主页，至少不会把这一栏丢掉。
        val sellerPage = brand?.get("url").stringOrEmpty().trim().orEmpty()
        val seller = sellerName(text).ifBlank { sellerPage }
        val soldOn = SOLD_ON.find(text)?.let { found ->
            "${found.groupValues[1]}-${found.groupValues[2]}-${found.groupValues[3]}"
        }.orEmpty()
        val articleInfo = PeachHtml.blockAfter(text, ARTICLE_INFO_MARKER, "</p>")
            .orEmpty()
        val tags = tagArea(text).map { PeachHtml.text(it) }.filter { it.isNotEmpty() }.distinct()

        val canonical = canonicalCode(wanted)
        val info = ScrapedMovieInfo(
            number = canonical,
            title = title.ifBlank { canonical },
            originalTitle = title.ifBlank { canonical },
            plot = description,
            outline = description,
            year = Regex("""\d{4}""").find(soldOn)?.value.orEmpty(),
            premiered = soldOn,
            runtime = runtimeMinutes(stripTags(articleInfo)),
            studio = STUDIO,
            publisher = STUDIO,
            // 卖家是这一页唯一像「厂牌」的东西，但它只作证据（peach 把它放 label），
            // 不写进 studio——studio 是 FC2 这个发行方本身。
            series = "",
            actors = emptyList(),
            genres = tags,
            tags = tags,
            website = articleUrl(wanted),
            source = "FC2",
            thumbUrl = cover,
            posterUrl = cover
        )
        return PeachParseResult.Record(
            info = info,
            sourceUrl = articleUrl(wanted),
            coverUrls = listOf(cover).filter { it.isNotEmpty() }
        )
    }

    /** 页面里那份 Product。下架的商品这一段整个不出现。 */
    private fun productBlock(html: String): PeachJsonValue.Obj? {
        for (match in LD_JSON.findAll(html)) {
            val root = PeachJson.parse(match.groupValues[1]) as? PeachJsonValue.Obj ?: continue
            // `@graph` 包一层的站点把 Product 放在数组里，这里一并认。
            val direct = if (root["@type"].stringOrEmpty() == "Product") root else null
            if (direct != null) return direct
            for (item in root["@graph"].objectsOrEmpty()) {
                if (item["@type"].stringOrEmpty() == "Product") return item
            }
        }
        return null
    }

    /** 卖家名：站上写在头部那个指向用户页的链接里。 */
    private fun sellerName(html: String): String {
        val marker = html.indexOf("items_article_headerInfo", ignoreCase = true)
        if (marker < 0) return ""
        val tail = html.substring(marker, minOf(html.length, marker + 6_000))
        val link = Regex("<a[^>]*href=\"[^\"]*${Regex.escape(SELLER_LINK_MARKER)}[^\"]*\"[^>]*>(.*?)</a>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(tail) ?: return ""
        return PeachHtml.text(link.groupValues[1])
    }

    /** 商品标签区里的每个链接文字。 */
    private fun tagArea(html: String): List<String> {
        val block = PeachHtml.blockAfter(html, TAG_AREA_MARKER, "</ul>")
            ?: PeachHtml.blockAfter(html, TAG_AREA_MARKER, "</div>")
            ?: return emptyList()
        return PeachHtml.allMatches(block, Regex("<a[^>]*>(.*?)</a>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)))
    }

    /** 时长那段可能带标签（`<p class="items_article_info">41:50</p>`），剥过之后再交给正则。 */
    private fun stripTags(raw: String): String = raw.replace(Regex("<[^>]+>"), " ").trim()
}
