package com.example.localmovielibrarz.scraper

/**
 * 一本道（1pondo.tv）作品 JSON 的解析（peach `sources/onepondo.py` 的 Kotlin 移植）。
 *
 * 为什么这家值得单独接：无码番号在 JAV 目录站上要么没有、要么给的是**转售商的上架日**
 * （`092415_001` javdb 报 2016-06-16，而番号自己写着 2015-09-24）。发行方自己那一页才是正主，
 * 而且它不要解析 HTML——站点前端读的就是 `dyn/phpauto/movie_details/movie_id/<id>.json`，
 * 一次请求给标题、说明、女优（日文与罗马字）、系列、发行日、时长和站内标签，不要凭据。
 *
 * 番号的分隔符是片商标识：一本道写 `112312_478`，カリビアンコム 写 `040221-001`。两家都按
 * `MMDDYY_nnn` 编号，光看番号分不出是谁家的，所以「要不要问这家」由路由层的证据决定
 * （见 [ScrapeRouteRules] 的无码链），这一站只负责把番号写成官网认的形状并核对返回的 `MovieID`。
 */
object OnePondoParser {

    const val BASE_URL = "https://www.1pondo.tv"

    /** 账本里这批作品的厂牌就叫这个，新抓的跟着走，免得同一家分裂成两个实体。 */
    const val STUDIO = "一本道"

    /**
     * 番号不是一本道那种日期写法时的措辞。
     *
     * 归 `NotFound` 而不是 `ParseError`：这家对「本店没有这个号」给出的就是没有，与页面结构
     * 坏掉是两回事——前者进 miss 缓存（7 天内不再问），后者只影响这一趟。
     */
    const val UNRECOGNISED = "这个番号认不出一本道的作品号"

    /** 日期式番号：`MMDDYY-NNN` / `MMDDYY_NNN`。 */
    private val DATED = Regex("^(\\d{6})[-_](\\d{2,4})$")

    /** 详情 JSON 的路径模板。 */
    fun detailUrl(movieId: String): String =
        "$BASE_URL/dyn/phpauto/movie_details/movie_id/$movieId.json"

    /** 作品页地址（写进 `website`，用户点得开）。 */
    fun pageUrl(movieId: String): String = "$BASE_URL/movies/$movieId/"

    /**
     * 账本番号 → 官网作品号；不是日期式番号的返回空串。
     *
     * 分隔符一律写成 `_`：官网自己就是这么写的（`112312_478`），用 `-` 去问会 404。
     */
    fun movieId(code: String): String {
        val found = DATED.find(code.trim()) ?: return ""
        val group = found.groupValues[1]
        val serial = found.groupValues[2]
        // 月日必须成立：`999999_001` 这种形状不是这本番号，宁可认不出也不要问一家不相干的站。
        val month = group.substring(0, 2).toIntOrNull() ?: return ""
        val day = group.substring(2, 4).toIntOrNull() ?: return ""
        if (month !in 1..12 || day !in 1..31) return ""
        return "${group}_$serial"
    }

    /**
     * 这几段文字（路径、文件名、账本厂牌）里有没有说这部片是一本道的。
     *
     * 日期式番号本身不带片商，拿另一家的番号去问官网，答回来的是同一天发行的另一部片。
     * 所以只在本机已有证据指着一本道时才问它。
     */
    fun namesThisStudio(vararg evidence: String): Boolean =
        evidence.joinToString(" ").lowercase().let { text ->
            text.contains("1pon") || text.contains("一本道") || text.contains("1pondo")
        }

    /**
     * 解析作品 JSON。
     *
     * @param body 站点返回的 JSON 正文。
     * @param code 查询用的账本番号。
     */
    fun parse(body: String?, code: String): PeachParseResult {
        val wanted = movieId(code)
            ?: return PeachParseResult.Failed(PeachFailureReason.NotFound, "这个番号不是一本道的写法")
        val root = PeachJson.parseObject(body)
            ?: return PeachParseResult.Failed(PeachFailureReason.ParseError, "一本道返回的不是作品 JSON")
        // 番号要核：番号同形的另一家片子问到这里时，官网答的是同一天发行的另一部片，
        // 那份资料一个字都不能用。
        val returned = root["MovieID"].stringOrEmpty().trim()
        if (returned != wanted) {
            return PeachParseResult.Failed(PeachFailureReason.NotFound, "一本道回的作品号对不上这个番号")
        }

        val title = PeachHtml.text(root["Title"].stringOrEmpty())
        val description = PeachHtml.text(root["Desc"].stringOrEmpty())
        // `Release` 形如 `2024-01-02 00:00:00`：账本只要日期，同 peach 的 normalized_release_date。
        val releaseDate = PeachHtml.isoDay(PeachHtml.text(root["Release"].stringOrEmpty()))
        val series = PeachHtml.text(root["Series"].stringOrEmpty())
        val tags = root["UCNAME"].stringsOrEmpty().map { PeachHtml.text(it) }.filter { it.isNotEmpty() }.distinct()
        val actors = actresses(root)
        val cover = cover(root)

        val info = ScrapedMovieInfo(
            number = wanted,
            title = title.ifBlank { wanted },
            originalTitle = title.ifBlank { wanted },
            plot = description,
            outline = description,
            year = Regex("""\d{4}""").find(releaseDate)?.value.orEmpty(),
            premiered = releaseDate,
            // 官网按秒记时长，账本按分钟。
            runtime = runtimeMinutes(root["Duration"]),
            studio = STUDIO,
            publisher = STUDIO,
            series = series,
            actors = actors,
            genres = tags,
            tags = tags,
            website = pageUrl(wanted),
            source = "一本道",
            thumbUrl = cover,
            posterUrl = cover
        )
        return PeachParseResult.Record(
            info = info,
            sourceUrl = pageUrl(wanted),
            coverUrls = listOf(cover).filter { it.isNotEmpty() }
        )
    }

    /** 女优：日文名是账本的身份写法，罗马字作别名跟着走。 */
    private fun actresses(root: PeachJsonValue.Obj): List<String> {
        val japanese = root["ActressesJa"].stringsOrEmpty().map { it.trim() }.filter { it.isNotEmpty() }
            .ifEmpty { listOfNotNull(root["Actor"].stringOrEmpty().trim().takeIf { it.isNotEmpty() }) }
        return japanese.distinct()
    }

    /**
     * 站上那张剧照。四个 `Thumb*` 字段实测同一个地址，按大到小取第一个给出来的。
     *
     * 无码片商不出封套，`thum_b.jpg` 是 120×197 的列表缩略图，所以这里按 peach 的顺序
     * 从 Ultra 往低取，而不是按 URL 里的名字猜。
     */
    private fun cover(root: PeachJsonValue.Obj): String {
        for (field in listOf("ThumbUltra", "ThumbHigh", "ThumbMed", "ThumbLow", "MovieThumb")) {
            val url = root[field].stringOrEmpty().trim()
            if (url.isNotEmpty()) return url
        }
        return ""
    }

    /** 秒 → 分钟，保留两位小数；非正数留空。 */
    private fun runtimeMinutes(value: PeachJsonValue?): String {
        val raw = (value as? PeachJsonValue.Num)?.toDoubleOrNull() ?: return ""
        if (raw <= 0.0) return ""
        val minutes = raw / 60.0
        val rounded = Math.round(minutes * 100.0) / 100.0
        return if (rounded > 0.0) trimNumber(rounded) else ""
    }

    private fun trimNumber(value: Double): String =
        if (value == Math.floor(value)) value.toLong().toString() else value.toString()
}
