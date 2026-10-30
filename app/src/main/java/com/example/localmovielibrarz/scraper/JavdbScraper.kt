package com.example.localmovielibrarz.scraper

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.URLEncoder
import java.util.Locale
import com.example.localmovielibrarz.util.TextSanitizer

class JavdbScraper(
    private val client: OkHttpClient = OkHttpClient(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /**
     * 端点与海报口径都做成取值函数而不是构造期常量：刮削器由 AppContainer 以单例
     * 持有，用户在设置页改完地址/开关后不可能为了生效去重建整条依赖图，
     * 所以每次刮削现取一次设置（SharedPreferences 读是内存操作，成本可忽略）。
     */
    private val apiUrlProvider: () -> String = { API_URL },
    private val preferUnwatermarkedCover: () -> Boolean = { false }
) : MovieScraper {
    override val source: ScrapeSource = ScrapeSource.TheJavDB

    override suspend fun scrape(number: String): ScrapedMovieInfo = withContext(ioDispatcher) {
        val normalized = normalizeJavdbNumber(number)
        val endpoint = apiUrlProvider().trim().ifBlank { API_URL }
        val url = "$endpoint?q=${URLEncoder.encode(normalized, "UTF-8")}"
        val root = fetchJson(url)
        parseJavdbMovieInfo(
            requestedNumber = normalized,
            movie = extractJavdbMovieObject(root).toJavdbApiMovie(),
            preferFullCover = preferUnwatermarkedCover()
        )
    }

    private fun fetchJson(url: String): Any {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            // 镜像 API 的 Referer 用主站地址：peach 的 Referer 恒为 `base_url + "/"`。
            .header("Referer", "https://javdb.com/")
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("TheJavDB request failed HTTP ${response.code}: $url")
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) error("TheJavDB response is empty: $url")
            JSONTokener(body).nextValue()
        }
    }

    private companion object {
        const val API_URL = "https://api.thejavdb.net/v1/movies"
        const val USER_AGENT = ScrapeUserAgent.VALUE
    }
}

internal data class JavdbApiMovie(
    val universalId: String = "",
    val title: String = "",
    val description: String = "",
    val fullCoverUrl: String = "",
    val frontCoverUrl: String = "",
    val releaseDate: String = "",
    val duration: String = "",
    val sourceUrl: String = "",
    val maker: String = "",
    val label: String = "",
    val series: String = "",
    val actresses: List<String> = emptyList(),
    val directors: List<String> = emptyList(),
    val genres: List<String> = emptyList()
)

internal fun parseJavdbMovieInfo(
    requestedNumber: String,
    movie: JavdbApiMovie,
    /**
     * 海报优先取无水印大图。
     *
     * `frontcover_url` 是站方竖版封面（带角标水印），`fullcover_url` 是原始大图
     * （通常无水印，但比例多为横版）。开启后优先 fullcover、拿不到再回落 frontcover；
     * 关闭时保持原行为（frontcover 优先）。不做 URL 改写——接口没有给出可推导的
     * 水印参数规则，凭空拼接只会得到 404。
     */
    preferFullCover: Boolean = false
): ScrapedMovieInfo {
    val number = movie.universalId.cleanText()
        .ifBlank { normalizeJavdbNumber(requestedNumber) }
    val title = movie.title.cleanText().ifBlank { number }
    val plot = movie.description.cleanText()
    val releaseDate = movie.releaseDate.cleanText()
    val duration = movie.duration.cleanText().digitsOnly()
    val fullCoverUrl = movie.fullCoverUrl.cleanText()
    val frontCoverUrl = movie.frontCoverUrl.cleanText()
    val posterUrl = if (preferFullCover) {
        fullCoverUrl.ifBlank { frontCoverUrl }
    } else {
        frontCoverUrl.ifBlank { fullCoverUrl }
    }
    val thumbUrl = fullCoverUrl.ifBlank { posterUrl }
    val genres = movie.genres.cleanStringList()

    return ScrapedMovieInfo(
        number = number,
        title = title,
        originalTitle = title,
        plot = plot,
        outline = plot,
        year = Regex("""\d{4}""").find(releaseDate)?.value.orEmpty(),
        premiered = releaseDate,
        runtime = duration,
        studio = movie.maker.cleanText(),
        publisher = movie.label.cleanText(),
        series = movie.series.cleanText(),
        directors = movie.directors.cleanStringList(),
        actors = movie.actresses.cleanStringList(),
        genres = genres,
        tags = genres,
        website = movie.sourceUrl.cleanText(),
        source = "javdb",
        thumbUrl = thumbUrl,
        posterUrl = posterUrl,
        thumbImageUrls = listOf(thumbUrl).filter { it.isNotBlank() },
        posterImageUrls = listOf(posterUrl).filter { it.isNotBlank() }
    )
}

internal fun extractJavdbMovieObject(root: Any): JSONObject {
    return when (root) {
        is JSONObject -> {
            if (root.has("universal_id")) return root
            root.optJSONObject("data")?.let { return it }
            root.firstMovieFromArray("data")?.let { return it }
            root.firstMovieFromArray("movies")?.let { return it }
            root.firstMovieFromArray("results")?.let { return it }
            error("TheJavDB response does not contain movie data")
        }
        is JSONArray -> root.firstJSONObjectOrNull() ?: error("TheJavDB response list is empty")
        else -> error("Unsupported TheJavDB response type: ${root::class.java.simpleName}")
    }
}

internal fun normalizeJavdbNumber(number: String): String {
    val trimmed = number.trim()
    val match = Regex("""(?i)([a-z0-9]{2,12})[-_ ]+(\d{2,6})([a-z]{0,2})(?=$|[^a-z0-9])""")
        .find(trimmed)
        ?: Regex("""(?i)(\d{0,4}[a-z]{2,12})(\d{2,6})([a-z]{0,2})(?=$|[^a-z0-9])""")
            .find(trimmed)
        ?: return trimmed.uppercase(Locale.ROOT)
    val prefix = match.groupValues[1].uppercase(Locale.ROOT)
    if (!prefix.any { it.isLetter() }) return trimmed.uppercase(Locale.ROOT)
    val digits = match.groupValues[2]
        .trimStart('0')
        .ifBlank { "0" }
        .padStart(3, '0')
    val suffix = match.groupValues[3].uppercase(Locale.ROOT)
    return "$prefix-$digits$suffix"
}

private fun JSONObject.firstMovieFromArray(name: String): JSONObject? =
    optJSONArray(name)?.firstJSONObjectOrNull()

private fun JSONObject.toJavdbApiMovie(): JavdbApiMovie =
    JavdbApiMovie(
        universalId = cleanString("universal_id"),
        title = cleanString("title"),
        description = cleanString("description"),
        fullCoverUrl = cleanString("fullcover_url"),
        frontCoverUrl = cleanString("frontcover_url"),
        releaseDate = cleanString("release_date"),
        duration = cleanString("duration"),
        sourceUrl = cleanString("source_url"),
        maker = cleanString("maker"),
        label = cleanString("label"),
        series = cleanString("series"),
        actresses = optJSONArray("actresses").toCleanStringList(),
        directors = optJSONArray("directors").toCleanStringList(),
        genres = optJSONArray("genres").toCleanStringList()
    )

private fun JSONArray?.firstJSONObjectOrNull(): JSONObject? {
    if (this == null) return null
    for (index in 0 until length()) {
        val item = opt(index)
        if (item is JSONObject) return item
    }
    return null
}

private fun JSONArray?.toCleanStringList(): List<String> {
    if (this == null) return emptyList()
    return (0 until length())
        .mapNotNull { index -> optString(index).cleanText().takeIf { it.isNotBlank() } }
        .distinct()
}

private fun List<String>.cleanStringList(): List<String> =
    mapNotNull { it.cleanText().takeIf { value -> value.isNotBlank() } }
        .distinct()

private fun JSONObject.cleanString(name: String): String =
    optString(name).cleanText()

private fun String.digitsOnly(): String =
    Regex("""\d+""").find(this)?.value.orEmpty()

/**
 * ★ 需求 2 修复：JavDb 的 description 字段是 HTML（含 `<br>`、`<a>` 等），
 * 原实现只做实体反转义、不剥标签，导致简介里残留 "<br>" 字面量。
 * 统一交给 [TextSanitizer]（先反转义 → 再剥标签 → `<br>` 转为真实换行）。
 */
private fun String.cleanText(): String =
    TextSanitizer.cleanMetadataText(this)
