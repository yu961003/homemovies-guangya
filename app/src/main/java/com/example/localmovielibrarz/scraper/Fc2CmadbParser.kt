package com.example.localmovielibrarz.scraper

/**
 * FC2CMADB 商品页（`https://fc2cmadb.com/articles/{裸数字}`）的解析。
 *
 * 为什么这一家要单独解析：
 * - 页面是 Inertia（Laravel + Vue 的整页内嵌 JSON）。正文 DOM 里只有骨架与占位文案，
 *   标题、发售日、时长、卖家、标签、封面全在 `<script type="application/json">` 的那一个
 *   JSON 对象里；用正则从 HTML 抠字段只会抠到「読み込み中」。
 * - 一页里常常不止一段 `application/json`（埋点、统计各占一段，而且多半没有 `props`），
 *   所以必须**遍历全部**这种 script、取第一个 `props` 是对象的，不能只取第一个。
 * - 女优不在这一页渲染：Inertia 的 partial reload 要求带 `X-Inertia` 与
 *   `X-Inertia-Partial-Data: actresses` 再问一次，[partialHeaders] 拼那次请求的头，
 *   [parseActresses] 吃那次响应；两条路径读的是同一份 JSON 形状。
 *
 * 判定顺序为什么不能动：
 * 1. 先认番号（[Fc2Parser.videoId]）。认不出就不该发请求，发出去只会把「号码写错」记成
 *    一次站点失败。
 * 2. 再看 `props.article` 与 `article.video_id`。站上不存在的商品仍然回 200：Inertia 的
 *    软 404 会渲染上一次那个文章（`article` 在，`video_id` 是别人）。这两种必须归
 *    `NotFound`——它的含义是「换下一家问」；若归成 `ParseError`，整源会被打进冷却，
 *    而这个番号在别家可能立刻查得到。
 * 3. 找不到 Inertia JSON 才是 `ParseError`（改版或验证页）：那时连「有没有这个商品」
 *    都判断不了，判 `NotFound` 会让一个只是过期的页面永久性地遮蔽这个番号。
 */
object Fc2CmadbParser {

    const val BASE_URL = "https://fc2cmadb.com"

    /** 这一页的 Inertia 组件名；partial reload 的头里也要带它。 */
    private const val COMPONENT = "Articles/Show"

    /** 补问女优时 `X-Inertia-Partial-Data` 要的那一项。 */
    private const val ACTRESSES_FIELD = "actresses"

    private const val SOURCE = "FC2CMADB"

    private const val NOT_FOUND = "FC2CMADB 上没有这个商品"

    /** 页面里所有 `type="application/json"` 的 script 正文，按出现顺序。 */
    private val JSON_SCRIPT = Regex(
        """<script\b[^>]*type\s*=\s*["']application/json["'][^>]*>(.*?)</script>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    private val YEAR = Regex("""\d{4}""")

    /** 商品页地址。 */
    fun articleUrl(video: String): String = "$BASE_URL/articles/$video"

    /**
     * 卖家。这一页只在 `article.writer` 里给，正文 DOM 的卖家栏是 JS 渲染的。
     *
     * 为什么单独拿出来而不是塞进 [ScrapedMovieInfo]：那个模型没有 label / seller 字段，
     * 而本解析器按站点口径固定 `studio = publisher = FC2-PPV`（发行方），把卖家写进
     * studio 会让同一批内容在账本里分裂成几百个「厂牌」。这与 [Fc2Parser] 的处理一致：
     * 卖家只作证据（peach 把它放 label），需要时由调用方从这里取。
     */
    data class Writer(val name: String, val page: String)

    /**
     * 解析商品页。
     *
     * @param html 商品页正文。
     * @param code 查询用的账本番号（`FC2-PPV-4137487` 这类写法都认）。
     */
    fun parse(html: String?, code: String): PeachParseResult {
        val text = html.orEmpty()
        val wanted = Fc2Parser.videoId(code)
        if (wanted.isEmpty()) {
            return PeachParseResult.Failed(PeachFailureReason.NotFound, Fc2Parser.UNRECOGNISED)
        }
        if (text.isBlank()) {
            return PeachParseResult.Failed(PeachFailureReason.ParseError, "FC2CMADB 页面为空")
        }
        val page = inertiaPage(text)
        if (page == null) {
            // 200 也可能是验证页：状态码判不出来，只能看正文标题。
            val reason = if (ChallengePageDetector.isChallengePage(text)) {
                PeachFailureReason.CloudflareChallenge
            } else {
                PeachFailureReason.ParseError
            }
            return PeachParseResult.Failed(reason, "FC2CMADB 页面结构未识别，可能在验证访问")
        }
        val article = articleOf(page)
            ?: return PeachParseResult.Failed(PeachFailureReason.NotFound, NOT_FOUND)
        // 番号要核：软 404 与「浏览历史里那一篇」都会让 article 存在但 video_id 是别人。
        if (article["video_id"].stringOrEmpty().trim() != wanted) {
            return PeachParseResult.Failed(PeachFailureReason.NotFound, NOT_FOUND)
        }

        val canonical = Fc2Parser.canonicalCode(wanted)
        val title = Fc2Parser.stripCode(PeachHtml.text(article["title"].stringOrEmpty()), wanted)
        val premiered = article["release_date"].stringOrEmpty().trim()
        val tags = tagNames(article)
        val cover = Fc2Parser.storageOriginal(article["image_url"].stringOrEmpty())
        val website = articleUrl(wanted)

        val info = ScrapedMovieInfo(
            number = canonical,
            title = title.ifBlank { canonical },
            originalTitle = title.ifBlank { canonical },
            // 这一页没有简介栏；空着比从别处借一段更像这家站。
            plot = "",
            outline = "",
            year = YEAR.find(premiered)?.value.orEmpty(),
            premiered = premiered,
            runtime = Fc2Parser.runtimeMinutes(article["duration"].stringOrEmpty()),
            studio = Fc2Parser.STUDIO,
            publisher = Fc2Parser.STUDIO,
            series = "",
            // 女优要按 Inertia 的 partial reload 再问一次（[partialHeaders] + [parseActresses]）。
            actors = emptyList(),
            genres = tags,
            tags = tags,
            website = website,
            source = SOURCE,
            thumbUrl = cover,
            posterUrl = cover
        )
        return PeachParseResult.Record(
            info = info,
            sourceUrl = website,
            coverUrls = listOf(cover).filter { it.isNotEmpty() }
        )
    }

    /**
     * 补问女优那次请求要带的头。
     *
     * 为什么不是常量：`X-Inertia-Version` 是站点每次构建生成的 asset 版本号，写死会在站点
     * 发版后拿到 409（Inertia 的版本冲突响应），页面回的是整页而不是 partial。
     *
     * 组件名不是 [COMPONENT] 或版本号为空时返回空 map：那种页面不是商品页（首页、搜索页
     * 也会带 Inertia JSON），拿它的版本号去问只会得到一次无意义的 409。
     */
    fun partialHeaders(html: String?): Map<String, String> {
        val page = inertiaPage(html.orEmpty()) ?: return emptyMap()
        if (page["component"].stringOrEmpty().trim() != COMPONENT) return emptyMap()
        val version = page["version"].stringOrEmpty().trim()
        if (version.isEmpty()) return emptyMap()
        return mapOf(
            "X-Inertia" to "true",
            "X-Inertia-Version" to version,
            "X-Inertia-Partial-Component" to COMPONENT,
            "X-Inertia-Partial-Data" to ACTRESSES_FIELD,
            "Accept" to "text/html, application/xhtml+xml"
        )
    }

    /**
     * 从 partial reload 的响应里取女优名。
     *
     * 同名去重保序：站上的女优栏会同一人写两遍（主役 + 「共演」），账本里重复的演员名
     * 会让 NFO 的 `<actor>` 出两条一模一样的记录。空名直接丢——`actresses` 里混着
     * `{"name": null}` 的槽位是实测存在的。
     */
    fun parseActresses(payload: String?): List<String> {
        val page = inertiaPage(payload.orEmpty()) ?: return emptyList()
        val props = page["props"]?.objOrNull() ?: return emptyList()
        return props[ACTRESSES_FIELD].objectsOrEmpty()
            .map { PeachHtml.text(it["name"].stringOrEmpty()) }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    /**
     * 取卖家名与卖家主页。
     *
     * slug 为空时主页给空串而不是拿 [Fc2Parser.sellerUrl] 拼一个 `/users//`：那个地址在
     * 账本里会被当成一条真链接去探活，永远 404。
     */
    fun writer(html: String?): Writer {
        val article = inertiaPage(html.orEmpty())?.let { articleOf(it) } ?: return Writer("", "")
        val writer = article["writer"]?.objOrNull() ?: return Writer("", "")
        val name = PeachHtml.text(writer["name"].stringOrEmpty())
        val slug = writer["slug"].stringOrEmpty().trim()
        return Writer(name, if (slug.isEmpty()) "" else Fc2Parser.sellerUrl(slug))
    }

    /** 页面里那份 Inertia 根对象：第一个 `props` 是对象的 JSON script。 */
    private fun inertiaPage(html: String): PeachJsonValue.Obj? {
        if (html.isBlank()) return null
        for (match in JSON_SCRIPT.findAll(html)) {
            val root = PeachJson.parseObject(match.groupValues[1]) ?: continue
            if (root["props"]?.objOrNull() != null) return root
        }
        return null
    }

    private fun articleOf(page: PeachJsonValue.Obj): PeachJsonValue.Obj? =
        page["props"]?.objOrNull()?.get("article")?.objOrNull()

    /** 标签：`tags[]` 每项的 `name`，丢空去重保序。 */
    private fun tagNames(article: PeachJsonValue.Obj): List<String> =
        article["tags"].objectsOrEmpty()
            .map { PeachHtml.text(it["name"].stringOrEmpty()) }
            .filter { it.isNotEmpty() }
            .distinct()
}