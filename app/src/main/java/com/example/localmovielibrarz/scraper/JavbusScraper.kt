package com.example.localmovielibrarz.scraper

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import java.io.IOException
import java.nio.charset.Charset
import java.util.Locale
import java.util.concurrent.TimeUnit
import com.example.localmovielibrarz.util.HttpClients
import com.example.localmovielibrarz.util.TextSanitizer

class JavbusScraper(
    private val client: OkHttpClient = defaultClient(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : MovieScraper {
    override val source: ScrapeSource = ScrapeSource.Javbus

    override suspend fun scrape(number: String): ScrapedMovieInfo = withContext(ioDispatcher) {
        val normalized = ScraperResponseGuard.requireSafeScrapeNumber(number).let(::normalizeNumber)
        // javbus 域名频繁更换 + 对异常连接直接 RST/EOF（借鉴 mdcz 的做法）：
        // 1) 同域名瞬时断连：短暂延迟重试 1 次（EOF 常见为瞬时风控/断流）；
        // 2) 仍失败则逐个切换候选域名；
        // 3) HTTP 4xx/5xx 与内容解析错误直接抛出（域名可达，不是域名问题）。
        var lastConnectionError: Throwable? = null
        for (base in BASE_URL_CANDIDATES) {
            val url = "$base/$normalized"
            var attempt = 0
            while (true) {
                try {
                    val html = fetch(url)
                    return@withContext parseDetail(normalized, url, html, base)
                } catch (e: IOException) {
                    lastConnectionError = e
                    attempt += 1
                    if (attempt < SAME_HOST_RETRY_COUNT) {
                        delay(SAME_HOST_RETRY_DELAY_MS)
                        continue // 同域名重试
                    }
                    break // 换下一个域名
                }
            }
        }
        throw lastConnectionError
            ?: IllegalStateException("JavBus 所有镜像域名均不可用：$normalized")
    }

    private fun parseDetail(number: String, url: String, html: String, base: String): ScrapedMovieInfo {
        val title = h3Title(html).ifBlank { titleTag(html) }
            .removeSuffix(" - JavBus")
            .cleanText()
        if (title.isBlank()) error("JavBus 没有解析到标题：$number")

        val coverUrl = absoluteUrl(
            Regex("""<a[^>]+class=["'][^"']*\bbigImage\b[^"']*["'][^>]+href=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                .find(html)
                ?.groupValues
                ?.getOrNull(1)
                .orEmpty(),
            base
        )
        val posterUrl = buildPosterUrl(coverUrl, base)
        val fields = parseInfoFields(html)
        val actors = parseActors(html)
        val actorImageUrls = parseActorImageUrls(html, base)
        val genres = parseGenres(html)
        val release = fields.firstValue("發行日期", "发行日期")
        val runtime = fields.firstValue("長度", "长度").digitsOnly()
        val plot = metaContent(html, "description").cleanText()

        return ScrapedMovieInfo(
            number = number,
            title = title,
            originalTitle = title,
            plot = plot,
            outline = plot,
            year = Regex("""\d{4}""").find(release)?.value.orEmpty(),
            premiered = release,
            runtime = runtime,
            studio = fields.firstValue("製作商", "制作商"),
            publisher = fields.firstValue("發行商", "发行商"),
            series = fields.firstValue("系列"),
            directors = fields.firstValue("導演", "导演").splitNames(),
            actors = actors,
            actorImageUrls = actorImageUrls,
            genres = genres,
            tags = genres,
            website = url,
            source = "javbus",
            thumbUrl = coverUrl,
            posterUrl = posterUrl.ifBlank { coverUrl }
        )
    }

    private fun fetch(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8")
            // 与 peach 一致用全局的语言偏好：peach 用同一个值抓 JavBus，拿回的仍是
            // 繁体页（解析器读「發行日期 / 發行商」），所以这里不需要单留 zh-CN。
            .header("Accept-Language", ScrapeUserAgent.ACCEPT_LANGUAGE)
            .header("Referer", "$BASE_URL/")
            .header("Priority", "u=0, i")
            // 主版本号必须与 User-Agent 一致：只改 UA 不改 CH，等于自称 Chrome 153
            // 却在客户端提示里报 148，比不发 CH 更像伪装。
            .header(
                "Sec-CH-UA",
                "\"Chromium\";v=\"${ScrapeUserAgent.MAJOR_VERSION}\", " +
                    "\"Google Chrome\";v=\"${ScrapeUserAgent.MAJOR_VERSION}\", \"Not/A)Brand\";v=\"99\""
            )
            .header("Sec-CH-UA-Mobile", "?0")
            .header("Sec-CH-UA-Platform", "\"Windows\"")
            .header("Sec-Fetch-Dest", "document")
            .header("Sec-Fetch-Mode", "navigate")
            .header("Sec-Fetch-Site", "none")
            .header("Sec-Fetch-User", "?1")
            .header("Upgrade-Insecure-Requests", "1")
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("JavBus 请求失败 HTTP ${response.code}: $url")
            val body = response.body ?: error("JavBus 响应为空：$url")
            val bytes = ScraperResponseGuard.readLimited(body, ScrapeSource.Javbus)
            val html = String(bytes, body.contentType()?.charset() ?: detectCharset(bytes))
            // Cloudflare 挑战页先过通用闸门：JavBus 自己的检测只认「年龄验证」页，
            // 认不出「Just a moment…」。两者互补，顺序上先通用后专属——通用命中就无需再看专属。
            ChallengePageDetector.requireNotChallenge(html, ScrapeSource.Javbus)
            // 封锁/验证页检测（借鉴 mdcz javbusPage.ts）：给出明确错误而非笼统 EOF/解析失败
            javbusBlockedPageMessage(classifyJavbusPage(html))?.let { error(it) }
            html
        }
    }

    private fun parseInfoFields(html: String): Map<String, String> {
        val infoBlock = Regex(
            """<div[^>]+class=["'][^"']*\binfo\b[^"']*["'][^>]*>([\s\S]*?)(?:<p class=["']star-show|</div>\s*</div>)""",
            RegexOption.IGNORE_CASE
        ).find(html)?.groupValues?.getOrNull(1).orEmpty()
        return Regex(
            """<p[^>]*>\s*<span[^>]+class=["'][^"']*\bheader\b[^"']*["'][^>]*>([\s\S]*?)</span>([\s\S]*?)</p>""",
            RegexOption.IGNORE_CASE
        ).findAll(infoBlock)
            .mapNotNull { match ->
                val key = cleanHtml(match.groupValues[1]).trimEnd(':', '：')
                val valueHtml = match.groupValues[2]
                val value = linksIn(valueHtml).firstOrNull().orEmpty()
                    .ifBlank { cleanHtml(valueHtml) }
                    .trim()
                if (key.isBlank() || value.isBlank()) null else key to value
            }
            .toMap()
    }

    private fun parseGenres(html: String): List<String> =
        Regex(
            """<span[^>]+class=["'][^"']*\bgenre\b[^"']*["'][^>]*>\s*<label>[\s\S]*?<a[^>]*>([\s\S]*?)</a>[\s\S]*?</label>""",
            RegexOption.IGNORE_CASE
        ).findAll(html)
            .map { cleanHtml(it.groupValues[1]) }
            .filter { it.isNotBlank() && it != "多選提交" }
            .distinct()
            .toList()

    private fun parseActors(html: String): List<String> =
        Regex(
            """<div[^>]+class=["'][^"']*\bstar-name\b[^"']*["'][^>]*>\s*<a[^>]*>([\s\S]*?)</a>""",
            RegexOption.IGNORE_CASE
        ).findAll(html)
            .map { cleanHtml(it.groupValues[1]) }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()

    private fun parseActorImageUrls(html: String, base: String): Map<String, String> =
        Regex(
            """<div[^>]+class=["'][^"']*\bstar-box\b[^"']*["'][^>]*>[\s\S]*?<img[^>]+src=["']([^"']+)["'][^>]+title=["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        ).findAll(html)
            .mapNotNull { match ->
                val url = absoluteUrl(match.groupValues[1], base)
                val name = cleanHtml(match.groupValues[2])
                if (name.isBlank() || url.isBlank()) null else name to url
            }
            .distinctBy { it.first }
            .toMap()

    private fun h3Title(html: String): String =
        Regex("""<h3[^>]*>([\s\S]*?)</h3>""", RegexOption.IGNORE_CASE)
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.let(::cleanHtml)
            .orEmpty()

    private fun titleTag(html: String): String =
        Regex("""<title[^>]*>([\s\S]*?)</title>""", RegexOption.IGNORE_CASE)
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.let(::cleanHtml)
            .orEmpty()

    private fun metaContent(html: String, name: String): String =
        Regex("""<meta[^>]+(?:property|name)=["']${Regex.escape(name)}["'][^>]+content=["']([^"']*)["']""", RegexOption.IGNORE_CASE)
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.let(::cleanHtml)
            .orEmpty()

    private fun linksIn(html: String): List<String> =
        Regex("""<a[^>]*>([\s\S]*?)</a>""", RegexOption.IGNORE_CASE)
            .findAll(html)
            .map { cleanHtml(it.groupValues[1]) }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()

    private fun buildPosterUrl(coverUrl: String, base: String): String {
        if (coverUrl.isBlank()) return ""
        val match = Regex("""/pics/cover/([^/?#]+)_b(\.[a-z0-9]+)(?:[?#].*)?$""", RegexOption.IGNORE_CASE)
            .find(coverUrl)
            ?: return ""
        return "$base/pics/thumb/${match.groupValues[1]}${match.groupValues[2]}"
    }

    private fun absoluteUrl(value: String, base: String): String {
        val clean = value.cleanText()
        return when {
            clean.isBlank() -> ""
            clean.startsWith("//") -> "https:$clean"
            clean.startsWith("/") -> "$base$clean"
            clean.startsWith("http", ignoreCase = true) -> clean
            else -> "$base/$clean"
        }
    }

    private fun normalizeNumber(number: String): String {
        val match = Regex("""(?i)([a-z]{2,12})[-_ ]?(\d{2,6})""").find(number)
            ?: return number.trim().uppercase(Locale.ROOT)
        val digits = match.groupValues[2].trimStart('0').ifBlank { "0" }.padStart(3, '0')
        return "${match.groupValues[1].uppercase(Locale.ROOT)}-$digits"
    }

    private fun Map<String, String>.firstValue(vararg keys: String): String =
        keys.firstNotNullOfOrNull { key -> this[key]?.takeIf { it.isNotBlank() } }.orEmpty()

    private fun String.splitNames(): List<String> =
        split(Regex("[,，、|;\\r\\n\\t]+"))
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()

    private fun String.digitsOnly(): String = Regex("""\d+""").find(this)?.value.orEmpty()

    private fun String.cleanText(): String = cleanHtml(this)

    /**
     * ★ 需求 2 修复：原实现「先剥标签、后反转义」，当源站把 `<br>` 写成 `&lt;br&gt;` 时，
     * 反转义后新浮现的标签已无标签可剥，于是 "<br>" 原样残留。
     * 现委托 [TextSanitizer]（先反转义 → 再剥标签 → 再反转义），并压成单行。
     */
    private fun cleanHtml(value: String): String = TextSanitizer.cleanSingleLine(value)

    private fun detectCharset(bytes: ByteArray): Charset {
        val head = bytes.decodeToString(endIndex = minOf(bytes.size, 4096))
        val charsetName = Regex("""charset=["']?([A-Za-z0-9_\-]+)""", RegexOption.IGNORE_CASE)
            .find(head)
            ?.groupValues
            ?.getOrNull(1)
        return runCatching { charsetName?.let { Charset.forName(it) } }.getOrNull() ?: Charsets.UTF_8
    }

    private companion object {
        /** 主域名（兼容旧引用）。 */
        const val BASE_URL = "https://www.javbus.com"

        /** 同域名瞬时断连（EOF）重试次数与间隔。 */
        const val SAME_HOST_RETRY_COUNT = 2
        const val SAME_HOST_RETRY_DELAY_MS = 800L

        /**
         * javbus 候选域名（按优先级）。
         * 经实测（2026-08）：仅 www.javbus.com 可用，.app/.cam/.org/.se/.cloud 镜像均不可达，已移除。
         * 若未来 javbus 换域名，在此追加并按优先级排序即可（连接层失败会自动切换）。
         */
        val BASE_URL_CANDIDATES = listOf(
            "https://www.javbus.com"
        )
        const val USER_AGENT = ScrapeUserAgent.VALUE

        /**
         * 默认 OkHttpClient：强制 HTTP/1.1（禁 HTTP/2），降低 TLS/ALPN 指纹特征，
         * 减少 javbus 等站点对异常连接直接 RST/EOF 的概率（借鉴 mdcz 的浏览器指纹思路）。
         */
        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(12, TimeUnit.SECONDS)
            .protocols(listOf(Protocol.HTTP_1_1))
            .connectionPool(HttpClients.sharedConnectionPool)
            .dispatcher(HttpClients.sharedDispatcher)
            .build()

        // ---- javbus 封锁/验证页识别（移植自 mdcz javbusPage.ts，正则精简）----

        private val JAVBUS_VERIFICATION_PATTERNS = listOf(
            Regex("""Age Verification JavBus""", RegexOption.IGNORE_CASE),
            Regex("""id=["']ageVerify["']""", RegexOption.IGNORE_CASE),
            Regex("""你是否已經成年"""),
            Regex("""你是否已经成年"""),
            Regex("""(?:駕駛|驾驶)[\s\S]{0,80}(?:考試|考试|題|题|驗證|验证)""")
        )

        private fun classifyJavbusPage(html: String): String = when {
            JAVBUS_VERIFICATION_PATTERNS.any { it.containsMatchIn(html) } -> "verification_required"
            html.contains("movie-box", ignoreCase = true) || html.contains("id=\"waterfall\"", ignoreCase = true) -> "content"
            else -> "unknown"
        }

        private fun javbusBlockedPageMessage(page: String): String? = when (page) {
            "verification_required" ->
                "JavBus 需要完成年龄/地区验证（Age Verification）。请在浏览器完成验证后使用可用 Cookie，App 内无法绕过。"
            else -> null
        }
    }
}
