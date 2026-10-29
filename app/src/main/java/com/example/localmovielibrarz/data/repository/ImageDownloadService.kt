package com.example.localmovielibrarz.data.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.random.Random
import com.example.localmovielibrarz.scraper.ScrapeUserAgent

class ImageDownloadService(
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val retryCountProvider: () -> Int,
    private val logger: (String) -> Unit = {},
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    suspend fun downloadImageBytes(url: String, referer: String? = null): ByteArray = withContext(ioDispatcher) {
        var lastError: Throwable? = null
        val retryCount = retryCountProvider().coerceAtLeast(1)
        repeat(retryCount) { attempt ->
            val request = buildRequest(url, referer)
            runCatching {
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("图片下载失败 HTTP ${response.code}: $url")
                    return@withContext response.body?.bytes() ?: error("图片响应为空：$url")
                }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                lastError = error
                logger("图片下载第 ${attempt + 1} 次失败：${error.message ?: error::class.java.simpleName}")
                if (attempt < retryCount - 1) {
                    val delayMillis = Random.nextLong(1_000L, 2_001L)
                    logger("等待 ${delayMillis}ms 后重试图片下载")
                    delay(delayMillis)
                }
            }
        }
        throw lastError ?: IllegalStateException("图片下载失败：$url")
    }

    private fun buildRequest(url: String, referer: String?): Request {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", IMAGE_USER_AGENT)
            .header("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
            .header("Accept-Language", ScrapeUserAgent.ACCEPT_LANGUAGE)
        val effectiveReferer = referer?.takeIf { it.isNotBlank() }
            ?: DMM_IMAGE_REFERER.takeIf { url.contains("dmm.co.jp", ignoreCase = true) }
        if (!effectiveReferer.isNullOrBlank()) {
            builder.header("Referer", effectiveReferer)
        }
        return builder.build()
    }

    private companion object {
        // 与刮削共用同一指纹：peach 抓封面走的是同一个 USER_AGENT 与 Accept-Language
        // （jav_cover_fetch.py:282-283），封面主机和作品页同源同风控。
        const val IMAGE_USER_AGENT = ScrapeUserAgent.VALUE
        const val DMM_IMAGE_REFERER = "https://www.dmm.co.jp/"
    }
}
