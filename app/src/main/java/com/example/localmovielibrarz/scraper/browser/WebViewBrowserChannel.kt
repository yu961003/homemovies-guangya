package com.example.localmovielibrarz.scraper.browser

import android.content.Context
import android.webkit.WebView
import com.example.localmovielibrarz.scraper.BrowserChannel
import com.example.localmovielibrarz.scraper.BrowserChannelState
import com.example.localmovielibrarz.scraper.PeachFailureReason
import com.example.localmovielibrarz.scraper.SourceFetchException
import com.example.localmovielibrarz.scraper.SourcePageRequest
import com.example.localmovielibrarz.scraper.SourcePageResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.coroutines.resume

/**
 * 用 WebView 取一页「HTTP 取不到」的网页。
 *
 * ## 它到底解决什么问题
 *
 * 有一类站（本题里是 fc2ppvdb / javten，以及 faleno/dahlia 的部分片商站）会先回一个
 * Cloudflare 托管挑战页：状态码 200 或 403，正文里没有作品数据，只有一个跑 JS 的验证脚本。
 * OkHttp 无法执行 JS，所以永远拿不到正文。**唯一在纯客户端可行的解法是把页面加载进 WebView**，
 * 让 Chromium 自己跑完挑战，通过后我们再把 `document.documentElement.outerHTML` 读出来。
 *
 * ## 时间轴
 *
 * 逐秒轮询，每秒构造一份 [BrowserChallengeTimeline.Observation] 交给
 * [BrowserChallengeTimeline.decide]，按决策行事：
 * 挑战页 →
 * 15 秒还没过就清 Cookie 重载一次 →
 * 40 秒还没过就把窗口提到前台让人点（若该 host 上次弹过没人点则直接放弃）→
 * 弹窗后 120 秒还没过放弃。每一步的阈值都在 `BrowserChallengeTimeline` 里，本类不重复判断。
 *
 * ## 资源拦截
 *
 * 挑战页大量加载图片、字体与埋点脚本，在移动网络下会把 15 秒的窗口吃光。所以未弹窗前
 * 用 [WebViewClient] 的 `shouldInterceptRequest` 把图片/字体/媒体挡掉（**不挡 JS/CSS**，
 * 挡了就永远过不了挑战）；弹窗之后必须放开——那时要渲染给用户看。
 */
class WebViewBrowserChannel(
    private val appContext: Context,
    private val enabled: () -> Boolean = { true },
    private val timeoutMillis: Long = 60_000L
) : BrowserChannel {

    /** 该 host 是否弹过窗但没点过去——弹过一次没成功，下次不再浪费用户 40 秒。 */
    private val failedHosts = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /** 弹窗后放开资源拦截，弹窗前挡。跨轮询共享。 */
    @Volatile
    private var blockResources = true

    override fun state(): BrowserChannelState = when {
        !enabled() -> BrowserChannelState.Disabled
        !ForegroundActivityTracker.hasForeground -> BrowserChannelState.NoForeground
        else -> BrowserChannelState.Available
    }

    override suspend fun fetch(request: SourcePageRequest): SourcePageResponse {
        val activity = ForegroundActivityTracker.current()
        if (activity == null) {
            throw SourceFetchException(
                message = "${request.sourceLabel} 需要浏览器通道，但当前没有前台界面",
                reason = PeachFailureReason.Network,
                browserAttempted = false
            )
        }
        val state = state()
        if (state != BrowserChannelState.Available) {
            throw SourceFetchException(
                message = "${request.sourceLabel} 需要浏览器通道，但${state.label}",
                reason = PeachFailureReason.Network,
                browserAttempted = false
            )
        }
        blockResources = true
        val lease = withContext(Dispatchers.Main) {
            try {
                HiddenWebViewHolder.acquire(activity)
            } catch (error: Throwable) {
                null
            }
        } ?: throw SourceFetchException(
            message = "${request.sourceLabel} 的浏览器通道初始化失败（系统 WebView 不可用）",
            reason = PeachFailureReason.Network,
            browserAttempted = false
        )

        try {
            return load(lease, request, activity)
        } finally {
            withContext(Dispatchers.Main) { lease.release() }
        }
    }

    private suspend fun load(
        lease: HiddenWebViewHolder.Lease,
        request: SourcePageRequest,
        activity: android.app.Activity
    ): SourcePageResponse {
        val host = runCatching { java.net.URI(request.url).host.orEmpty() }.getOrDefault("")
        val hostAlreadyFailed = failedHosts[host] == true

        var elapsed = 0L
        var cleared = false
        var shownAt: Long? = null
        var promoted = false

        withContext(Dispatchers.Main) {
            installClient(lease.webView, request, ::isBlocked)
            val headers = buildMap {
                putAll(request.extraHeaders)
                if (request.referer.isNotBlank() && !containsKey("Referer")) put("Referer", request.referer)
            }
            if (headers.isEmpty()) {
                lease.webView.loadUrl(request.url)
            } else {
                lease.webView.loadUrl(request.url, headers)
            }
        }

        while (true) {
            delay(1_000)
            elapsed += 1_000
            val observation = withContext(Dispatchers.Main) { observe(lease.webView, elapsed) }
            val decision = BrowserChallengeTimeline.decide(
                observation = observation.timeline,
                cleared = cleared,
                shownAtMillis = shownAt,
                hostAlreadyFailed = hostAlreadyFailed,
                timeoutMillis = timeoutMillis
            )
            when (decision) {
                BrowserChallengeTimeline.Decision.KeepWaiting -> Unit

                BrowserChallengeTimeline.Decision.ClearCookiesAndReload -> {
                    withContext(Dispatchers.Main) {
                        clearSiteCookies(observation.documentUrl.ifBlank { request.url })
                        lease.webView.loadUrl(request.url)
                    }
                    cleared = true
                    elapsed = 0L
                    shownAt = null
                }

                is BrowserChallengeTimeline.Decision.PromoteToForeground -> {
                    blockResources = false
                    cleared = true
                    // 需要重新计时：弹窗前的 40 秒不能计入弹窗后的 120 秒。
                    elapsed = 0L
                    shownAt = 0L
                    if (!promoted) {
                        withContext(Dispatchers.Main) { lease.promote() }
                        promoted = true
                    }
                }

                BrowserChallengeTimeline.Decision.ChallengeSolved -> {
                    return withContext(Dispatchers.Main) { readPage(lease.webView, request) }
                }

                is BrowserChallengeTimeline.Decision.GiveUp -> {
                    if (promoted) failedHosts[host] = true
                    withContext(Dispatchers.Main) { lease.webView.loadUrl("about:blank") }
                    throw SourceFetchException(
                        message = "${request.sourceLabel}：${decision.message}",
                        reason = PeachFailureReason.CloudflareChallenge,
                        browserAttempted = true,
                        browserBlocked = true
                    )
                }
            }
            // activity 可能在等待期间被回收；那时继续转没有意义。
            if (!ForegroundActivityTracker.hasForeground) {
                throw SourceFetchException(
                    message = "${request.sourceLabel}：取页过程中前台界面被回收",
                    reason = PeachFailureReason.Network,
                    browserAttempted = true
                )
            }
            @Suppress("UNUSED_EXPRESSION")
            activity.hashCode()
        }
    }

    /** 未弹窗前挡掉图片/字体/媒体；JS 与 CSS 一律放行（挡了就过不了挑战）。 */
    private fun isBlocked(url: String?): Boolean {
        if (!blockResources || url.isNullOrBlank()) return false
        val path = url.substringBefore('?').lowercase()
        return BLOCKED_EXTENSIONS.any { path.endsWith(it) }
    }

    private fun installClient(webView: WebView, request: SourcePageRequest, blocked: (String?) -> Boolean) {
        webView.webViewClient = object : android.webkit.WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                req: android.webkit.WebResourceRequest?
            ): android.webkit.WebResourceResponse? {
                val url = req?.url?.toString()
                if (blocked(url)) {
                    return android.webkit.WebResourceResponse(
                        "text/plain",
                        "utf-8",
                        java.io.ByteArrayInputStream(ByteArray(0))
                    )
                }
                return null
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                req: android.webkit.WebResourceRequest?
            ): Boolean = false

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                url: String?
            ): Boolean = false
        }
    }

    /** 一次轮询的观测：时间轴需要的三样 + 清 Cookie 要用的当前 URL。 */
    private data class Probe(
        val timeline: BrowserChallengeTimeline.Observation,
        val documentUrl: String
    )

    private suspend fun observe(webView: WebView, elapsedMillis: Long): Probe {
        val json = evalJson(webView, OBSERVE_SCRIPT) ?: return Probe(
            timeline = BrowserChallengeTimeline.Observation(
                elapsedMillis = elapsedMillis,
                title = "",
                readyState = "loading",
                headExcerpt = ""
            ),
            documentUrl = ""
        )
        return Probe(
            timeline = BrowserChallengeTimeline.Observation(
                elapsedMillis = elapsedMillis,
                title = json.optString("title"),
                readyState = json.optString("ready"),
                headExcerpt = json.optString("head")
            ),
            documentUrl = json.optString("url")
        )
    }

    private suspend fun readPage(webView: WebView, request: SourcePageRequest): SourcePageResponse {
        val json = evalJson(webView, READ_SCRIPT)
            ?: throw SourceFetchException(
                message = "${request.sourceLabel}：页面读不到正文（JS 求值失败）",
                reason = PeachFailureReason.ParseError,
                browserAttempted = true
            )
        val html = json.optString("html")
        if (html.isBlank()) {
            throw SourceFetchException(
                message = "${request.sourceLabel}：页面正文为空",
                reason = PeachFailureReason.ParseError,
                browserAttempted = true
            )
        }
        val status = json.optInt("status", 0).let { if (it == 0) 200 else it }
        val finalUrl = json.optString("url").ifBlank { request.url }
        val body = if (html.length.toLong() > request.maxBytes) {
            // 截断而不是丢弃：挑战通过后拿到的页通常就是详情页，标题/日期都在前半段；
            // 超限说明页面异常大，截到上限仍比整页丢掉有用。
            html.substring(0, request.maxBytes.toInt().coerceAtMost(html.length))
        } else {
            html
        }
        return SourcePageResponse(url = finalUrl, status = status, body = body, viaBrowser = true)
    }

    private suspend fun evalJson(webView: WebView, script: String): JSONObject? =
        suspendCancellableCoroutine { continuation ->
            var resumed = false
            webView.evaluateJavascript(script) { value ->
                if (resumed) return@evaluateJavascript
                resumed = true
                val text = unwrap(value)
                if (continuation.isActive) {
                    continuation.resume(text?.let { runCatching { JSONObject(it) }.getOrNull() })
                }
            }
            // evaluateJavascript 在 WebView 被销毁时不会回调，靠超时兜住，避免永久挂起。
            continuation.invokeOnCancellation { resumed = true }
        }

    /** `evaluateJavascript` 回的是 JSON 编码后的字符串（含外层引号与转义），要剥一层。 */
    private fun unwrap(value: String?): String? {
        if (value == null || value == "null") return null
        val trimmed = value.trim()
        if (trimmed.length >= 2 && trimmed.first() == '"' && trimmed.last() == '"') {
            return runCatching { org.json.JSONTokener(trimmed).nextValue() as? String }.getOrNull()
        }
        return trimmed
    }

    private fun clearSiteCookies(url: String) {
        val manager = android.webkit.CookieManager.getInstance()
        val all = manager.getCookie(url).orEmpty()
        if (all.isBlank()) return
        all.split(';').forEach { pair ->
            val name = pair.substringBefore('=').trim()
            if (name.isNotEmpty()) {
                manager.setCookie(url, "$name=; Max-Age=0; Path=/")
            }
        }
        manager.flush()
    }

    private companion object {
        val BLOCKED_EXTENSIONS = listOf(
            ".jpg", ".jpeg", ".png", ".gif", ".webp", ".avif", ".bmp", ".ico", ".svg",
            ".woff", ".woff2", ".ttf", ".otf", ".eot",
            ".mp4", ".webm", ".m3u8", ".ts", ".mp3", ".m4a", ".ogg"
        )

        /** 只取 title / readyState / URL 与 head 前 4000 字符：挑战标记都在 head 附近。 */
        const val OBSERVE_SCRIPT =
            "(function(){try{return JSON.stringify({title:document.title," +
                "ready:document.readyState,url:location.href," +
                "head:document.documentElement?document.documentElement.outerHTML.slice(0,4000):''})}catch(e){return null}})()"

        /** 读整页正文 + 导航状态码。`responseStatus` 在部分 WebView 上不支持，取不到就回 0。 */
        const val READ_SCRIPT =
            "(function(){try{var e=performance.getEntriesByType('navigation');" +
                "var s=(e&&e.length&&e[0].responseStatus)||0;return JSON.stringify({" +
                "url:location.href,status:s," +
                "html:'<!DOCTYPE html>\\n'+(document.documentElement?document.documentElement.outerHTML:'')})}" +
                "catch(err){return null}})()"
    }
}
