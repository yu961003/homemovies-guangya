package com.example.localmovielibrarz.scraper

/**
 * AVBase 作品搜索页的解析（peach `sources/avbase.py` 的 Kotlin 移植）。
 *
 * 为什么解析这一个就够了、不必进作品页：`/works?q=<番号>` 是 Next.js 页，
 * `__NEXT_DATA__` 里 `props.pageProps.works[]` 就是命中的作品，每部带 `work_id`、日文标题、
 * `actors[]`，以及它在各家店铺的商品 `products[]`（`source` 为 `fanza` / `mgstage` / `duga`，
 * 各自给 `image_url`、`maker`、`label`、`series`、`date`、`iteminfo.director`）。一次请求给全。
 *
 * 它**不给时长**：`runtime` 留空是数据本身的缺口，不是解析漏了。留空之后 [ScrapeRouteRules]
 * 的必填标量判断若要求时长，这一家就永远结算不了——所以这里如实留空，让后面的
 * Javbus / JavDB 去补，而不是编一个数字出来。
 *
 * 商品里会混进收录这段内容的**合集**（素人系常见）：合集的标题与番号都对不上本作，
 * 按这两条筛掉，否则片名、厂牌、系列、发行日会全变成那张合集的。
 */
object AvbaseParser {

    /** 同一部作品在多家店铺上架时的取用顺序：FANZA 与 r18.dev 同源，写法最接近账本。 */
    private val PRODUCT_ORDER = listOf("fanza", "mgstage", "duga")

    const val BASE_URL = "https://www.avbase.net"

    /** 页面结构对不上时的措辞。Cloudflare 验证页与站点改版都落到这一句，细档分开记。 */
    const val UNRECOGNISED = "AVBase 页面结构未识别，可能在验证访问"

    private val NEXT_DATA = Regex(
        "<script id=\"__NEXT_DATA__\" type=\"application/json\">(.*?)</script>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    /** 搜索页地址。`q` 只放番号本身，不放任何 URL 修饰（引用注入的入口在调用方）。 */
    fun searchUrl(code: String): String = "$BASE_URL/works?q=${encodeQuery(code)}"

    /** 作品页地址：`prefix:work_id` 是 AVBase 自己的主键形状。 */
    fun workUrl(prefix: String, workId: String): String {
        val key = if (prefix.isBlank()) workId else "$prefix:$workId"
        return "https://www.avbase.net/works/${encodeQuery(key)}"
    }

    /**
     * 解析搜索页。
     *
     * @param code 查询用的番号（已按数字前缀规则改写过）。
     */
    fun parse(html: String?, code: String): PeachParseResult {
        val text = html.orEmpty()
        if (text.isBlank()) {
            return PeachParseResult.Failed(PeachFailureReason.ParseError, UNRECOGNISED)
        }
        val props = nextDataProps(text)
        if (props == null) {
            // 200 也可能是验证页：状态码判不出来，只能看正文。
            val reason = if (ChallengePageDetector.isChallengePage(text)) {
                PeachFailureReason.CloudflareChallenge
            } else {
                PeachFailureReason.ParseError
            }
            return PeachParseResult.Failed(reason, UNRECOGNISED)
        }

        val works = props["works"].arrayOrEmpty()
            .mapNotNull { it as? PeachJsonValue.Obj }
            .filter { PeachCodeIdentity.sameReleaseCode(code, it["work_id"].stringOrEmpty()) }
        val work = works.firstOrNull()
            ?: return PeachParseResult.Failed(PeachFailureReason.NotFound, "AVBase 没有这个番号")

        val products = ownProducts(code, work)
        val workId = work["work_id"].stringOrEmpty()
        val prefix = work["prefix"].stringOrEmpty()
        val coverUrls = covers(code, products)

        val title = products.firstNotNullOfOrNull { productText(it, "title") }
            .orEmpty()
            .ifBlank { PeachHtml.text(work["title"].stringOrEmpty()) }

        val actors = work["actors"].objectsOrEmpty()
            .map { PeachHtml.text(it["name"].stringOrEmpty()) }
            .filter { it.isNotEmpty() }

        // 发行日只认第一个商品自己的 `date`：那是这家店的上架日，再退到作品的 `min_date`
        // （最早一次上架）。两者都没有就留空——木桶里没有这一格，不能拿别的字段顶。
        val premiered = products.firstOrNull()?.let { productDate(it) }.orEmpty()
            .ifBlank { PeachHtml.listingDate(work["min_date"].stringOrEmpty()) }

        val info = ScrapedMovieInfo(
            number = workId.ifBlank { code },
            title = title,
            originalTitle = title,
            // AVBase 没有简介字段；空着比从别处借一段更像这家站。
            plot = "",
            outline = "",
            year = Regex("""\d{4}""").find(premiered)?.value.orEmpty(),
            premiered = premiered,
            runtime = "",
            studio = products.firstNotNullOfOrNull { nestedName(it, "maker") }.orEmpty(),
            publisher = products.firstNotNullOfOrNull { nestedName(it, "label") }.orEmpty(),
            series = products.firstNotNullOfOrNull { nestedName(it, "series") }.orEmpty(),
            directors = listOfNotNull(
                products.firstNotNullOfOrNull { productText(it, "director") }?.takeIf { it.isNotEmpty() }
            ),
            actors = actors,
            genres = emptyList(),
            tags = emptyList(),
            website = workUrl(prefix, workId),
            source = "AVBase",
            // 封面 URL 由调用方量过尺寸再定（[PeachCoverPick]），这里先把最像封面的那张填上，
            // 只是让「不经探测的调用路径」也有值可看；真正判胜不依赖这两个字段。
            thumbUrl = coverUrls.firstOrNull().orEmpty(),
            posterUrl = coverUrls.firstOrNull().orEmpty()
        )
        return PeachParseResult.Record(info = info, sourceUrl = workUrl(prefix, workId), coverUrls = coverUrls)
    }

    /** `props.pageProps`；拿不到（没有内嵌 JSON / 结构变了）返回 null。 */
    private fun nextDataProps(html: String): PeachJsonValue.Obj? {
        val body = NEXT_DATA.find(html)?.groupValues?.get(1)
            ?: PeachHtml.scriptBody(html, "id=\"__NEXT_DATA__\"")
            ?: return null
        val root = PeachJson.parse(body) as? PeachJsonValue.Obj ?: return null
        return root["props"]?.objOrNull()?.get("pageProps")?.objOrNull()
    }

    /**
     * 本作自己的商品条目，按 [PRODUCT_ORDER] 排。
     *
     * 商品号认得出这个番号的说了算，标题相等只是退路：AVBase 的作品标题取自名录里的
     * 某个商品，而那一件可能是收录本作的合集——按标题相等收下它，片名、厂牌、系列与
     * 发行日就全成了那张合集的。一件商品号都认不出时才比标题（DUGA 的
     * `prestige-6584` 这类自编号与番号无关，那时只有标题能认）。
     */
    internal fun ownProducts(code: String, work: PeachJsonValue.Obj): List<PeachJsonValue.Obj> {
        val products = work["products"].objectsOrEmpty()
        val named = products.filter { product ->
            PeachCodeIdentity.identifiesCode(
                code,
                identifiersFor(code, product["product_id"].stringOrEmpty())
            )
        }
        val matched = if (named.isNotEmpty()) {
            named
        } else {
            val title = PeachHtml.text(work["title"].stringOrEmpty())
            products.filter { PeachHtml.text(it["title"].stringOrEmpty()) == title }
        }
        return matched.sortedBy { product ->
            val source = product["source"].stringOrEmpty().lowercase()
            PRODUCT_ORDER.indexOf(source).let { if (it < 0) PRODUCT_ORDER.size else it }
        }
    }

    /**
     * 商品号参与番号比对时的写法。
     *
     * AVBase 的 `product_id` 是各店铺自己的编号：`fanza` 用 `118abw232`（DMM content_id）、
     * `mgstage` 用 `300MIUM-111`（带数字前缀的番号）、`duga` 用 `prestige-6584`（与本番号无关）。
     * 只有前两种能被 [PeachCodeIdentity] 认出来，第三种会被如实判成「不是这个番号」，
     * 于是退回标题比对——这正是 peach 那句「一件商品号都认不出时才比标题」的意图。
     */
    private fun identifiersFor(code: String, productId: String): List<String> {
        val cleaned = productId.trim()
        if (cleaned.isEmpty()) return emptyList()
        val found = mutableListOf(cleaned)
        // `fanza` 的 content_id 前面带厂牌数字前缀（`118abw232`），剥掉之后再比一次：
        // 账本里的写法是 `ABW-232`，不剥就只能靠正则那条宽松判据。
        val digitsPrefix = Regex("^\\d{2,3}(?=[a-z]{2,8}\\d)").find(cleaned)
        if (digitsPrefix != null) {
            val stripped = cleaned.removeRange(0, digitsPrefix.value.length)
            found += stripped
            // `abw232` → `ABW-232`：content_id 挤在一起，补一个分隔符才能走 releaseIdentity 的形态。
            val shape = Regex("^([a-z]{2,8})(\\d{1,5})([a-z]?)$").find(stripped)
            if (shape != null) {
                found += "${shape.groupValues[1].uppercase()}-${shape.groupValues[2]}${shape.groupValues[3].uppercase()}"
            }
        }
        return found
    }

    /**
     * 封面候选：要 https、不要缩略图、不要别的作品的封套，按出现顺序去重。
     *
     * 去重保留**第一个**（那个位置通常是 `fanza` 的正规封套），后同 URL 的店铺条目只是重复提交。
     */
    internal fun covers(code: String, products: List<PeachJsonValue.Obj>): List<String> {
        val found = LinkedHashSet<String>()
        for (product in products) {
            val url = product["image_url"].stringOrEmpty().trim()
            if (!url.startsWith("https://")) continue
            if (PeachCoverPick.looksLikeThumbnailOrPlaceholder(url)) continue
            if (PeachCodeIdentity.isCrossProductCover(code, url)) continue
            found += url
        }
        return found.toList()
    }

    private fun productText(product: PeachJsonValue.Obj, key: String): String? {
        val value = when (key) {
            // `director` 在 `iteminfo` 里，不在商品顶层。
            "director" -> product["iteminfo"]?.objOrNull()?.get("director").stringOrEmpty()
            else -> product[key].stringOrEmpty()
        }
        return PeachHtml.text(value).takeIf { it.isNotEmpty() }
    }

    private fun nestedName(product: PeachJsonValue.Obj, key: String): String? {
        val raw = product[key] ?: return null
        val name = when (raw) {
            is PeachJsonValue.Obj -> raw["name"].stringOrEmpty()
            else -> raw.stringOrEmpty()
        }
        return PeachHtml.text(name).takeIf { it.isNotEmpty() }
    }

    private fun productDate(product: PeachJsonValue.Obj): String =
        PeachHtml.listingDate(product["date"].stringOrEmpty())

    private fun encodeQuery(value: String): String = buildString {
        for (ch in value) {
            if (ch.isLetterOrDigit() || ch == '-' || ch == '_' || ch == '.' || ch == '~' || ch == ':') {
                append(ch)
            } else {
                // 只可能出现在番号里的字符（`_`、`-`）已经在上面放行；其余按 UTF-8 百分号编码。
                append('%')
                append(ch.code.toString(16).uppercase().padStart(2, '0'))
            }
        }
    }
}
