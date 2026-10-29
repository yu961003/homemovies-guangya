package com.example.localmovielibrarz.scraper

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 「这台设备现在能不能连外网」的探测：请求 `generate_204`，只看状态码。
 *
 * 它和刮削请求**共用同一个 OkHttpClient**（同一个 [ScrapeProxySelector]、同一个连接池），
 * 所以探测结论与刮削的实际通路一致——这正是它存在的意义：用户报「刮削全是网络错误」时，
 * 先分清是整台设备出不去，还是只有某一家站被拦。
 *
 * 超时另算：从注入的客户端 [OkHttpClient.newBuilder] 派生一个 5 秒的短超时副本。
 * 刮削那侧的 30 秒是给单页传输留的余量，用来等一个探测结论就太久了——
 * 探测失败本来就意味着「大概率不通」，多等 25 秒换不来更多信息。
 * 派生而不是新建，是为了不丢 [ScrapeProxySelector]：带代理探测才有意义。
 */
class NetworkProbe(
    private val client: OkHttpClient = OkHttpClient(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val probeClient: OkHttpClient by lazy {
        client.newBuilder()
            .connectTimeout(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    suspend fun canReachGoogle(): Boolean = withContext(ioDispatcher) {
        val request = Request.Builder()
            .url("https://www.google.com/generate_204")
            .get()
            .build()
        runCatching {
            probeClient.newCall(request).execute().use { response ->
                response.code in 200..399
            }
        }.getOrDefault(false)
    }

    private companion object {
        /** 与 `StrmScrapeRepository.canReachGoogle` 里日志写的「timeout 5s」保持一致。 */
        const val PROBE_TIMEOUT_SECONDS = 5L
    }
}