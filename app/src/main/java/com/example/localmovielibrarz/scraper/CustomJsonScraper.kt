package com.example.localmovielibrarz.scraper

import com.example.localmovielibrarz.data.repository.AppSettingsRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import com.example.localmovielibrarz.util.TextSanitizer

class CustomJsonScraper(
    private val settingsRepository: AppSettingsRepository,
    private val client: OkHttpClient = OkHttpClient(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : MovieScraper {
    override val source: ScrapeSource = ScrapeSource.CustomJson

    override suspend fun scrape(number: String): ScrapedMovieInfo = withContext(ioDispatcher) {
        val config = settingsRepository.getCustomJsonScrapeConfig()
        val spec = config.postProcessSpec
        val (url, body) = fetchBody(config, number)
        val json = JSONObject(body)
        val root = json.select(config.resultPath).firstOrNull() ?: json
        val resultNumber = root.firstText(config.numberPath).ifBlank { number }
        val title = root.firstText(config.titlePath).pipe(CustomJsonPostProcessChains.TEXT, spec).ifBlank { resultNumber }
        // ★ 需求 2：自定义 JSON 源的字段完全由用户配置，返回 HTML 片段（含 <br>）极常见，
        // 统一净化后再入库，避免详情页显示 "<br>"。
        // 现在改由声明式后处理链完成（见 JsonFieldTransforms）：先清标签与转义，再走 TextSanitizer
        // 兜底——链负责"结构"（换行、包裹标记），TextSanitizer 负责"字符集"（全角、控制符）。
        val plot = TextSanitizer.cleanMetadataText(root.firstText(config.plotPath).pipe(CustomJsonPostProcessChains.PLOT, spec))
        val thumbUrl = root.firstText(config.thumbPath)
        val posterUrl = root.firstText(config.posterPath)
        val fanartUrl = root.firstText(config.fanartPath)
        ScrapedMovieInfo(
            number = resultNumber,
            title = title,
            originalTitle = root.firstText(config.originalTitlePath).pipe(CustomJsonPostProcessChains.TEXT, spec).ifBlank { title },
            plot = plot,
            outline = plot,
            premiered = root.firstText(config.premieredPath).pipe(CustomJsonPostProcessChains.DATE, spec).normalizedCustomJsonDate(),
            runtime = root.firstText(config.runtimePath).pipe(CustomJsonPostProcessChains.RUNTIME, spec),
            studio = root.firstText(config.studioPath).pipe(CustomJsonPostProcessChains.TEXT, spec),
            series = root.firstText(config.seriesPath).pipe(CustomJsonPostProcessChains.TEXT, spec),
            actors = root.textList(config.actorsPath).pipeList(CustomJsonPostProcessChains.LIST_ITEM, spec),
            genres = root.textList(config.genresPath).pipeList(CustomJsonPostProcessChains.LIST_ITEM, spec),
            tags = root.textList(config.tagsPath).pipeList(CustomJsonPostProcessChains.LIST_ITEM, spec),
            rating = root.firstText(config.ratingPath),
            website = url,
            source = config.name.ifBlank { "自定义 JSON" },
            thumbUrl = thumbUrl.ifBlank { fanartUrl },
            posterUrl = posterUrl,
            fanartUrl = fanartUrl,
            thumbImageUrls = listOf(thumbUrl, fanartUrl).filter { it.isNotBlank() },
            posterImageUrls = listOf(posterUrl).filter { it.isNotBlank() }
        )
    }

    /** 单值过链：内置链 + 用户紧凑串（[CustomJsonScrapeConfig.postProcessSpec]）。 */
    private fun String.pipe(
        builtIn: List<JsonFieldTransform>,
        spec: String?
    ): String = if (isBlank()) this else CustomJsonPostProcessChains.applyScalar(this, builtIn, spec)

    /** 列表过链：逐项清洗并去掉清洗后变空、重复的项。 */
    private fun List<String>.pipeList(
        builtIn: List<JsonFieldTransform>,
        spec: String?
    ): List<String> = if (isEmpty()) this else CustomJsonPostProcessChains.applyList(this, builtIn, spec)

    suspend fun previewPathCandidates(number: String): List<CustomJsonPathCandidate> = withContext(ioDispatcher) {
        val config = settingsRepository.getCustomJsonScrapeConfig()
        val (_, body) = fetchBody(config, number)
        val json = JSONObject(body)
        val root = json.select(config.resultPath).firstOrNull() ?: json
        root.flattenJsonPaths("$").take(MAX_PATH_CANDIDATES)
    }

    private fun fetchBody(config: CustomJsonScrapeConfig, number: String): Pair<String, String> {
        if (!config.enabled) error("自定义 JSON 刮削源未启用")
        val url = config.urlTemplate.replace("{number}", number)
        if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
            error("自定义 JSON 刮削地址必须以 http:// 或 https:// 开头")
        }
        val request = Request.Builder().url(url).get().build()
        val body = client.newCall(request).execute().use { response ->
            // 用户自填的接口也要限量：4 MiB（peach 的默认 page_limit）。
            // 之前是 body.string() 全量读，一个配错的地址就能把内存拉满。
            val responseBody = response.body
            val text = if (responseBody == null) {
                ""
            } else {
                val charset = responseBody.contentType()?.charset() ?: Charsets.UTF_8
                String(ScraperResponseGuard.readLimited(responseBody, ScrapeSource.CustomJson), charset)
            }
            if (!response.isSuccessful) error("自定义 JSON 刮削失败：HTTP " + response.code)
            if (text.isBlank()) error("自定义 JSON 刮削结果为空")
            text
        }
        return url to body
    }
}

data class CustomJsonPathCandidate(
    val path: String,
    val valuePreview: String
)

data class CustomJsonScrapeConfig(
    val enabled: Boolean = false,
    val name: String = "自定义 JSON",
    val urlTemplate: String = "",
    val sampleNumber: String = "SSIS-115",
    val resultPath: String = "$",
    val numberPath: String = "$.number",
    val titlePath: String = "$.title",
    val originalTitlePath: String = "",
    val plotPath: String = "$.plot",
    val premieredPath: String = "$.premiered",
    val runtimePath: String = "$.runtime",
    val studioPath: String = "$.studio",
    val seriesPath: String = "$.series",
    val actorsPath: String = "$.actors",
    val genresPath: String = "$.genres",
    val tagsPath: String = "$.tags",
    val ratingPath: String = "$.rating",
    val posterPath: String = "$.poster",
    val thumbPath: String = "$.thumb",
    val fanartPath: String = "$.fanart",

    /**
     * 用户自定义的后处理紧凑串（见 [JsonFieldTransforms.parse] 的语法速查）。
     *
     * 为什么放在配置里而不是写死：内置链（[CustomJsonPostProcessChains]）只覆盖常见脏数据，
     * 站点特有的清洗（例如「标题去掉 `[中文字幕]` 前缀」「日期是 `2024年01月02日`」）
     * 只能由用户自己写。空串表示只用内置链。
     *
     * 叠加顺序是「内置在前、用户在后」：用户规则看到的是已经洗净的文本，
     * 写 `replace` 时不必再考虑标签和实体。
     */
    val postProcessSpec: String = ""
)

private fun Any.firstText(path: String): String =
    select(path).firstOrNull()?.jsonText().orEmpty()

private fun Any.textList(path: String): List<String> =
    select(path).flatMap { value ->
        when (value) {
            is JSONArray -> (0 until value.length()).mapNotNull { index -> value.opt(index)?.jsonText() }
            else -> value.jsonText().split(',', '/', '、').map { it.trim() }
        }
    }.filter { it.isNotBlank() }.distinct()

private fun Any.select(path: String): List<Any> {
    val trimmed = path.trim()
    if (trimmed.isBlank()) return emptyList()
    if (trimmed == "$") return listOf(this)
    val tokens = trimmed.pathTokens()
    return tokens.fold(listOf(this)) { current, token ->
        current.flatMap { value -> value.selectToken(token) }
    }
}

private fun String.pathTokens(): List<String> =
    removePrefix("$.")
        .removePrefix("$")
        .trimStart('.')
        .split('.')
        .filter { it.isNotBlank() }

private fun Any.selectToken(token: String): List<Any> {
    val name = token.substringBefore('[')
    val selector = token.substringAfter('[', "").substringBefore(']', "")
    val base = when (this) {
        is JSONObject -> opt(name)
        is JSONArray -> name.toIntOrNull()?.let { opt(it) }
        else -> null
    } ?: return emptyList()
    return when {
        selector == "*" && base is JSONArray -> (0 until base.length()).mapNotNull { base.opt(it) }
        selector.isNotBlank() && base is JSONArray -> selector.toIntOrNull()?.let { listOfNotNull(base.opt(it)) }.orEmpty()
        else -> listOf(base)
    }
}

private fun Any.jsonText(): String = when (this) {
    is JSONObject -> optString("name").ifBlank { toString() }
    is JSONArray -> (0 until length()).mapNotNull { opt(it)?.jsonText() }.joinToString(", ")
    JSONObject.NULL -> ""
    else -> toString().trim()
}

private fun Any.flattenJsonPaths(path: String): List<CustomJsonPathCandidate> = when (this) {
    is JSONObject -> keys().asSequence()
        .toList()
        .sorted()
        .flatMap { key -> opt(key)?.flattenJsonPaths("$path.$key").orEmpty() }
    is JSONArray -> flattenArrayPaths(path)
    JSONObject.NULL -> emptyList()
    else -> listOf(CustomJsonPathCandidate(path, jsonText().previewText()))
}

private fun JSONArray.flattenArrayPaths(path: String): List<CustomJsonPathCandidate> {
    if (length() == 0) return emptyList()
    val values = (0 until length()).mapNotNull { index -> opt(index) }
    if (values.none { it is JSONObject || it is JSONArray }) {
        return listOf(CustomJsonPathCandidate(path, values.joinToString(", ") { it.jsonText() }.previewText()))
    }
    return values
        .flatMap { value -> value.flattenJsonPaths("$path[*]") }
        .groupBy { it.path }
        .map { (candidatePath, candidates) ->
            CustomJsonPathCandidate(
                path = candidatePath,
                valuePreview = candidates.map { it.valuePreview }.filter { it.isNotBlank() }.distinct().joinToString(", ").previewText()
            )
        }
}

private fun String.previewText(maxLength: Int = 96): String {
    val singleLine = replace(Regex("""\s+"""), " ").trim()
    return if (singleLine.length <= maxLength) singleLine else singleLine.take(maxLength - 1) + "…"
}

private fun String.normalizedCustomJsonDate(): String {
    val value = trim()
    if (value.isBlank()) return ""
    val output = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("Asia/Hong_Kong")
    }
    val patterns = listOf(
        "yyyy-MM-dd'T'HH:mm:ss.SSSX",
        "yyyy-MM-dd'T'HH:mm:ssX",
        "yyyy-MM-dd'T'HH:mmX"
    )
    patterns.forEach { pattern ->
        val parsed = runCatching {
            SimpleDateFormat(pattern, Locale.US).apply { isLenient = false }.parse(value)
        }.getOrNull()
        if (parsed != null) return output.format(parsed)
    }
    return Regex("""\d{4}-\d{2}-\d{2}""").find(value)?.value ?: value
}

private const val MAX_PATH_CANDIDATES = 240
