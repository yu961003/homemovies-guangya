package com.example.localmovielibrarz.scraper

import android.webkit.CookieManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * 取页层的真实实现：OkHttp 主通道 + WebView 回落。
 *
 * ## 两条通道各自负责什么
 *
 * - **OkHttp**：所有能直接拿到的页面。快、无界面、可并发（虽然链内是串行）。
 * - **WebView**（[BrowserChannel]）：只有当 OkHttp 拿到的是**站方的拒绝**时才启用——403、
 *   429、或者状态 200 但正文是 Cloudflare 挑战页。判据集中在 [BrowserFallbackPolicy.isRefused]，
 *   本类不重复判断。
 *
 * ## 为什么 Cookie 要从 WebView 借
 *
 * 挑战通过后站点会发 `cf_clearance`，它**只在 WebView 的 Cookie 存储里**。下一次同一站点的
 * HTTP 请求如果带上这个 Cookie，就能直接过；不带就又撞挑战、又走一遍 40 秒。
 * 所以这里在发请求前从 `CookieManager` 读一次该 URL 的 Cookie 塞进请求头。
 * 反向不做（不把 OkHttp 的 Set-Cookie 写回 WebView）：挑战 Cookie 是 Cloudflare 绑到浏览器
 * 指纹上的，写进去也不会生效，反而可能污染。
 *
 * ## 为什么「字节预算」在这里不计数
 *
 * 读取上限走 [ScraperResponseGuard.readLimited]，它内部已经把实际字节记进 [ScrapeByteMeter]。
 * 本类再记一次就是重复计数。
 */
class AndroidSourceFetch(
    private val httpClient: OkHttpClient,
    private val browser: BrowserChannel = NoBrowserChannel,
    private val throttle: ScrapeSourceThrottle? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val log: (String) -> Unit = {}
) : SourcePageFetcher {

    override suspend fun fetch(request: SourcePageRequest): SourcePageResponse = withContext(ioDispatcher) {
        val prefersBrowser = BrowserFallbackPolicy.prefersBrowser(request.source)
        if (prefersBrowser && browser.state().usable) {
            // 这几家 peach 就是「上来走浏览器」：它们的 HTTP 通道长期被拦，先打一下只会白白
            // 多一次 403 并给站点记一笔失败。
            try {
                log("[${request.sourceLabel}] 走浏览器通道取页")
                return@withContext browser.fetch(request)
            } catch (blocked: SourceFetchException) {
                // 浏览器也被拦：这是真的拦，不该退回 HTTP 再撞一次。
                if (blocked.browserBlocked) throw blocked
                log("[${request.sourceLabel}] 浏览器通道失败（${blocked.message}），改走 HTTP")
            }
        }

        val response = try {
            performHttp(request)
        } catch (error: SourceFetchException) {
            // 传输层失败（超时/DNS/断网）不是站方拒绝，回落浏览器没有意义——浏览器同样会超时。
            throw error
        }

        if (BrowserFallbackPolicy.isRefused(response.status, response.body)) {
            val refusedMessage =
                "[${request.sourceLabel}] HTTP 被站方拦下（status=${response.status}），开始浏览器回落"
            if (!BrowserFallbackPolicy.allowsFallback(request.source)) {
                log("$refusedMessage，但该来源未开启回落")
                throw SourceFetchException(
                    message = "${request.sourceLabel} 被站方拒绝（HTTP ${response.status}），该来源未开启浏览器回落",
                    reason = if (response.status == 429) PeachFailureReason.RateLimited
                    else PeachFailureReason.CloudflareChallenge,
                    statusCode = response.status
                )
            }
            if (!browser.state().usable) {
                log("$refusedMessage，但${browser.state().label}")
                throw SourceFetchException(
                    message = "${request.sourceLabel} 被站方拒绝（HTTP ${response.status}），且${browser.state().label}",
                    reason = PeachFailureReason.CloudflareChallenge,
                    statusCode = response.status
                )
            }
            log(refusedMessage)
            return@withContext browser.fetch(request)
        }

        response
    }

    private suspend fun performHttp(request: SourcePageRequest): SourcePageResponse {
        throttle?.let { limit ->
            val wait = limit.waitMillisBefore(request.source)
            if (wait > 0) delay(wait)
            limit.markRequested(request.source)
        }
        val headers = buildMap {
            putAll(request.extraHeaders)
            if (request.referer.isNotBlank() && !containsKey("Referer")) put("Referer", request.referer)
            val cookie = cookiesFor(request.url)
            if (cookie.isNotBlank() && !containsKey("Cookie")) put("Cookie", cookie)
        }
        val httpRequest = Request.Builder()
            .url(request.url)
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .build()
        try {
            httpClient.newCall(httpRequest).execute().use { raw ->
                // OkHttp 的 Response.body 是可空的；空体（如 204/HEAD 或站方空响应）
                // 不是「被拦」，按传输失败处理，避免把空页当成正常内容交给解析层。
                val rawBody = raw.body ?: throw SourceFetchException(
                    message = "${request.sourceLabel} 响应体为空（HTTP ${raw.code}）",
                    reason = PeachFailureReason.Network,
                    statusCode = raw.code
                )
                val body = ScraperResponseGuard.readLimited(rawBody, request.maxBytes)
                return SourcePageResponse(
                    url = raw.request.url.toString(),
                    status = raw.code,
                    body = body.toString(Charsets.UTF_8),
                    viaBrowser = false
                )
            }
        } catch (error: SocketTimeoutException) {
            throw SourceFetchException(
                message = "${request.sourceLabel} 请求超时：${error.message.orEmpty()}",
                reason = PeachFailureReason.Timeout,
                cause = error
            )
        } catch (error: UnknownHostException) {
            throw SourceFetchException(
                message = "${request.sourceLabel} 域名解析失败：${error.message.orEmpty()}",
                reason = PeachFailureReason.Network,
                cause = error
            )
        } catch (error: IOException) {
            throw SourceFetchException(
                message = "${request.sourceLabel} 传输失败：${error.message.orEmpty()}",
                reason = PeachFailureReason.Network,
                cause = error
            )
        }
    }

    /**
     * 从 WebView 的 Cookie 存储里取该 URL 的 Cookie。
     *
     * `CookieManager.getCookie` 必须在有 WebView 的环境下调用；纯单元测试里没有 Looper 会抛，
     * 所以整段兜住，拿不到就当没有 Cookie——它只是加速项，不是必需项。
     */
    private fun cookiesFor(url: String): String = runCatching {
        CookieManager.getInstance().getCookie(url).orEmpty()
    }.getOrDefault("")
}