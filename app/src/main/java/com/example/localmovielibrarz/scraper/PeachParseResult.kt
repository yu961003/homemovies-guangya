package com.example.localmovielibrarz.scraper

import com.example.localmovielibrarz.util.TextSanitizer

/**
 * 一家来源「解析这一步」的两种结果。
 *
 * 为什么把解析与取数分开成两层：取数（OkHttp、响应限额、UA、代理、Range 探测）必须
 * `import okhttp3`，一旦和解析写在同一个文件里，这个文件就会被纯 JVM 编译集排除，
 * 而那三家用的是最容易改版的 Next.js 内嵌 JSON、ld+json 与站点自研 JSON——恰恰是
 * 最需要离线回归的一段。分开之后，解析器只吃 `String`，单测可以直接喂一页样本。
 *
 * 失败用 [Failed] 而不是抛异常：链上要按 peach 的 `FailureReason` 分档决定「换源」还是
 * 「整站冷却」，异常类型表达不了这个区分，抛出去再按文案猜档在站点改文案时就会失效。
 */
sealed interface PeachParseResult {

    /** 解析成功。[coverUrls] 是**候选**，最终选哪一张由 [PeachCoverPick] 量完尺寸再定。 */
    data class Record(
        val info: ScrapedMovieInfo,
        val sourceUrl: String,
        val coverUrls: List<String> = emptyList()
    ) : PeachParseResult

    data class Failed(
        val reason: PeachFailureReason,
        val message: String
    ) : PeachParseResult
}

/**
 * 三站共用的解析小工具。
 *
 * 为什么不用 Jsoup：本工程不加依赖（硬性约束），而站点需要的是「拿一段 script 的正文」
 * 「取一个 class 里的文本」这类定位，用正则足够；用 Jsoup 反而会引入一条新的解析口径，
 * 与既有八个 scrapers 的写法分叉。
 */
internal object PeachHtml {

    /** 取某个 `<script>` 的正文：按 `type` 或 `id` 匹配标签头，再找成对的结束标签。 */
    fun scriptBody(html: String, attribute: String): String? {
        val head = Regex("<script[^>]*$attribute[^>]*>", RegexOption.IGNORE_CASE).find(html) ?: return null
        val start = head.range.last + 1
        val end = html.indexOf("</script>", start, ignoreCase = true)
        if (end <= start) return null
        return html.substring(start, end)
    }

    /**
     * 取一个「开始标记 → 结束标记」之间的正文，并做最少量的 HTML 清理。
     *
     * 站点把说明写在 `<p class="items_article_info">` 这类容器里，容器里还可能嵌 `<br>`、
     * `<a>`。链上要的是纯文本，标签留着会进标题与简介。
     */
    fun blockAfter(html: String, openMarker: String, closeMarker: String): String? {
        val start = html.indexOf(openMarker, ignoreCase = true)
        if (start < 0) return null
        val contentStart = start + openMarker.length
        val end = html.indexOf(closeMarker, contentStart, ignoreCase = true)
        if (end <= contentStart) return null
        return html.substring(contentStart, end)
    }

    /** 剥标签 + 反转义 + 压空白。 */
    fun text(raw: String?): String {
        if (raw.isNullOrEmpty()) return ""
        return TextSanitizer.cleanSingleLine(raw).trim()
    }

    /** 取所有匹配组的第 [group] 项，按出现顺序。 */
    fun allMatches(html: String, pattern: Regex, group: Int = 1): List<String> =
        pattern.findAll(html).mapNotNull { it.groupValues.getOrNull(group)?.takeIf { value -> value.isNotEmpty() } }.toList()

    /**
     * 站点的时间戳写法（`Fri May 26 2023 09:00:00 GMT+0900`）→ `2023-05-26`。
     *
     * 只认英文三字母月份：AVBase 的这个字段是 `Date#toString()` 的产物，本地化之后
     * 月份会变成别的语言，那时宁可留空也不要猜错月份（发行日错一个月比空着更糟）。
     */
    fun listingDate(raw: String?): String {
        val found = LISTING_DATE.find(raw.orEmpty()) ?: return ""
        val month = MONTHS[found.groupValues[1]] ?: return ""
        val day = found.groupValues[2].toIntOrNull() ?: return ""
        val year = found.groupValues[3]
        return "$year-${month.toString().padStart(2, '0')}-${day.toString().padStart(2, '0')}"
    }

    private val LISTING_DATE = Regex("^[A-Za-z]{3} ([A-Za-z]{3}) (\\d{1,2}) (\\d{4})")

    /**
     * 带时间的 ISO 写法（`2024-01-02 00:00:00`）→ `2024-01-02`。
     *
     * 对齐 peach `metadata.normalized_release_date`：只取前十个字符，再用 ISO 日期形状校验
     * （`2024-13-99` 这类照样要被判非法）。一本道的 `Release` 带 `00:00:00`，原样写进 NFO 的
     * `<premiered>` 会让同一个字段出现两种格式——Kodi 与 Emby 对后者都只会当成不认识的文本。
     *
     * 非法或不完整时返回空串而不是「尽力猜」：发行日错一个月比空着更糟，而下游
     * （[ScrapeRouteRules.missingRequiredFields]）把空的 `premiered` 当作「缺」，会继续问下一家。
     */
    fun isoDay(raw: String?): String {
        val value = raw.orEmpty().trim()
        if (value.length < 10) return ""
        val candidate = value.substring(0, 10)
        if (!ISO_DAY.matches(candidate)) return ""
        val month = candidate.substring(5, 7).toIntOrNull() ?: return ""
        val day = candidate.substring(8, 10).toIntOrNull() ?: return ""
        if (month !in 1..12 || day !in 1..31) return ""
        return candidate
    }

    private val ISO_DAY = Regex("^\\d{4}-\\d{2}-\\d{2}$")

    private val MONTHS = mapOf(
        "Jan" to 1, "Feb" to 2, "Mar" to 3, "Apr" to 4, "May" to 5, "Jun" to 6,
        "Jul" to 7, "Aug" to 8, "Sep" to 9, "Oct" to 10, "Nov" to 11, "Dec" to 12
    )
}
