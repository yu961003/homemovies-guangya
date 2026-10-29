package com.example.localmovielibrarz.playback

import android.content.ContentResolver
import android.net.Uri
import com.example.localmovielibrarz.data.repository.GuangyaDirectLinkResolver
import com.example.localmovielibrarz.data.repository.DirectLinkRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale
import java.util.concurrent.TimeUnit
import com.example.localmovielibrarz.util.HttpClients

class PlaybackResolver(
    private val contentResolver: ContentResolver,
    private val directLinkRepository: DirectLinkRepository,
    private val guangyaDirectLinkResolver: GuangyaDirectLinkResolver,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .connectionPool(HttpClients.sharedConnectionPool)
        .dispatcher(HttpClients.sharedDispatcher)
        .build()
) {
    private val strmParser = StrmParser(contentResolver)
    private val m3uParser = M3uParser()

    /**
     * 直链租约表：把「刚解析出来的直链」按 `route:id` 记在内存里，带失效时刻。
     *
     * 仓库层（[DirectLinkRepository] / [GuangyaDirectLinkResolver]）本来就有落库缓存，
     * 但那是「秒级过期才失效」的粗粒度判断，且**并发同一 pickcode 会各打一次接口**。
     * 这一层补两件事：提前续期（剩余寿命不足 [RENEWAL_LEAD_MILLIS] 就认作不可用），
     * 以及让下面的单飞把并发请求合并成一次。
     */
    private val leaseStore = DirectLinkLeaseStore(renewalLeadMillis = RENEWAL_LEAD_MILLIS)

    /** 同一 key 的并发解析合并器：只让一个协程真正去取链，其余等它的结果。 */
    private val singleFlight = DirectLinkSingleFlight()

    suspend fun resolve(
        mediaUri: String,
        title: String?,
        fileName: String?,
        forceRefresh: Boolean = false
    ): Result<PlaybackRequest> =
        withContext(Dispatchers.IO) {
            runCatching {
                // P0-2 修正：抽出 id 后立即按 route 分流，避免光鸭被误送 115 解析器
                PickcodeExtractor.extract(mediaUri)?.let { id ->
                    when (val route = PickcodeExtractor.routeOf(mediaUri)) {
                        "guangya_res", "guangya_vod" ->
                            return@runCatching resolveGuangya(id, route == "guangya_vod", title, forceRefresh)
                        else ->
                            return@runCatching resolve115Pickcode(id, title, forceRefresh)
                    }
                }

                val isStrm = fileName?.substringAfterLast('.', "")?.equals("strm", ignoreCase = true) == true ||
                    mediaUri.substringBefore('?').lowercase(Locale.ROOT).endsWith(".strm")

                if (!isStrm) {
                    return@runCatching directRequest(mediaUri, title)
                }

                val entryUrl = strmParser.readEntryUrl(Uri.parse(mediaUri)).getOrElse { throw it }
                if (!entryUrl.isHttpUrl()) {
                    return@runCatching directRequest(entryUrl, title)
                }

                // 读取 STR M 内容后（光鸭 STRM 走这里），同样按 route 分流
                val pickcode = PickcodeExtractor.extract(entryUrl)
                if (pickcode != null) {
                    when (val route = PickcodeExtractor.routeOf(entryUrl)) {
                        ROUTE_GUANGYA_RES, ROUTE_GUANGYA_VOD ->
                            return@runCatching resolveGuangya(pickcode, route == ROUTE_GUANGYA_VOD, title, forceRefresh)
                        else ->
                            return@runCatching resolve115Pickcode(pickcode, title, forceRefresh)
                    }
                }

                resolveNetworkEntry(entryUrl, title)
            }
        }

    suspend fun invalidatePickcode(pickcode: String) {
        directLinkRepository.invalidate(pickcode)
        // 仓库缓存清了，内存租约也必须跟着清：否则下一毫秒又从那层命中旧链，
        // 403 之后的「刷新链接」会变成什么都没发生。
        leaseStore.invalidate("$ROUTE_115:$pickcode")
    }

    /**
     * 按路由失效直链缓存（H 轮）：光鸭 fileId 走 [guangyaDirectLinkResolver]，115 pickcode 走
     * [directLinkRepository]。此前统一按 115 处理，光鸭 403 重试时清不掉缓存，会一直拿到旧直链。
     */
    suspend fun invalidateRoute(route: String?, pickcode: String?) {
        val id = pickcode?.takeIf { it.isNotBlank() } ?: return
        when (route) {
            ROUTE_GUANGYA_RES, ROUTE_GUANGYA_VOD -> {
                guangyaDirectLinkResolver.invalidate(id)
                // 光鸭两条路由共用同一个 fileId，但租约按 route 分开存，所以两条都要清。
                leaseStore.invalidate("$ROUTE_GUANGYA_RES:$id")
                leaseStore.invalidate("$ROUTE_GUANGYA_VOD:$id")
            }
            else -> {
                directLinkRepository.invalidate(id)
                leaseStore.invalidate("$ROUTE_115:$id")
            }
        }
    }

    private suspend fun resolve115Pickcode(
        pickcode: String,
        title: String?,
        forceRefresh: Boolean
    ): PlaybackRequest {
        val directUrl = cachedDirectUrl(
            route = ROUTE_115,
            id = pickcode,
            label = ROUTE_115,
            forceRefresh = forceRefresh,
            expiresAtSecondsOf = { url, nowSeconds -> DirectLinkExpiryParser.parseExpiresAt(url, nowSeconds) }
        ) { directLinkRepository.resolve(pickcode, forceRefresh) }
        return PlaybackRequest(
            mediaUri = directUrl,
            title = title,
            userAgent = USER_AGENT,
            referer = null,
            cookie = null,
            pickcode = pickcode,
            headers = mapOf("User-Agent" to USER_AGENT),
            route = ROUTE_115
        )
    }

    private suspend fun resolveGuangya(
        fileId: String,
        isVod: Boolean,
        title: String?,
        forceRefresh: Boolean
    ): PlaybackRequest {
        val directUrl = cachedDirectUrl(
            route = if (isVod) ROUTE_GUANGYA_VOD else ROUTE_GUANGYA_RES,
            id = fileId,
            label = if (isVod) ROUTE_GUANGYA_VOD else ROUTE_GUANGYA_RES,
            forceRefresh = forceRefresh,
            expiresAtSecondsOf = { url, nowSeconds -> GuangyaDirectLinkExpiryParser.parse(url, nowSeconds) }
        ) { guangyaDirectLinkResolver.resolve(fileId, isVod, forceRefresh) }
        return PlaybackRequest(
            mediaUri = directUrl,
            title = title,
            userAgent = USER_AGENT,
            referer = null,
            cookie = null,
            // H 轮：补 pickcode（=fileId）。光鸭 STRM 记录同样以 fileId 为主键，
            // 带上它才能启用「库字幕 / 网盘字幕」能力，并让 403 重试失效到正确的缓存。
            pickcode = fileId,
            headers = mapOf("User-Agent" to USER_AGENT),
            route = if (isVod) ROUTE_GUANGYA_VOD else ROUTE_GUANGYA_RES
        )
    }

    /**
     * 取一条可播的直链：先看内存租约，不在（或快到续期点）就走单飞重新解析。
     *
     * 三个语义细节：
     * - **forceRefresh 只跳过第一层缓存**，不跳过单飞：用户连点两次「刷新链接」时，
     *   第二次应该并入第一次的请求，否则后端会收到两路一模一样的签发请求。
     * - **单飞内部再做一次可用检查**：等锁期间领先的协程可能已经把链填好了，
     *   不复查就会白打一次接口。
     * - **过期时刻从 URL 里解**（各路由的签名参数不同，见 [DirectLinkExpiryParser] /
     *   [GuangyaDirectLinkExpiryParser]），解析不出来时交给解析器自己的兜底（通常是 2 小时），
     *   这样租约的失效判断与仓库缓存的口径一致，不会出现「仓库说能用、租约说已过期」。
     */
    private suspend fun cachedDirectUrl(
        route: String,
        id: String,
        label: String,
        forceRefresh: Boolean,
        expiresAtSecondsOf: (String, Long) -> Long,
        loader: suspend () -> String
    ): String {
        val key = "$route:$id"
        if (!forceRefresh) {
            leaseStore.available(key)?.let { lease ->
                if (!lease.needsRenewal(System.currentTimeMillis(), RENEWAL_LEAD_MILLIS)) return lease.url
            }
        }

        return singleFlight.run(key) {
            if (!forceRefresh) {
                leaseStore.available(key)?.let { lease ->
                    if (!lease.needsRenewal(System.currentTimeMillis(), RENEWAL_LEAD_MILLIS)) {
                        return@run lease.url
                    }
                }
            }
            val url = loader()
            val nowMillis = System.currentTimeMillis()
            val nowSeconds = nowMillis / 1000L
            // 解析器返回的是「秒级时间戳」，且自带提前量（115 的 t 参数减去 600s），
            // 这里统一换算成毫秒作为租约失效时刻，避免同一份链在两处算出不同的过期点。
            val expiresAtMillis = expiresAtSecondsOf(url, nowSeconds) * 1000L
            leaseStore.put(
                key,
                DirectLinkLease(
                    url = url,
                    issuedAtMillis = nowMillis,
                    expiresAtMillis = expiresAtMillis.coerceAtLeast(nowMillis),
                    sourceLabel = label
                )
            )
            url
        }
    }

    private fun resolveNetworkEntry(entryUrl: String, title: String?): PlaybackRequest {
        val request = Request.Builder()
            .url(entryUrl)
            .header("User-Agent", DEFAULT_USER_AGENT)
            .header("Accept", "*/*")
            .header("Range", "bytes=0-262143")
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Unable to fetch playback address")

            val contentType = response.header("Content-Type").orEmpty()
            val preview = response.peekBody(256 * 1024).string()
            val looksLikeM3u = isM3uResponse(entryUrl, contentType, preview)

            if (!looksLikeM3u) {
                return directRequest(entryUrl, title)
            }

            val parsed = m3uParser.parse(preview).getOrElse {
                throw IllegalStateException(it.message ?: "No playable media URL found in M3U")
            }
            val headers = buildHeaders(parsed.userAgent, parsed.referer, parsed.cookie)
            return PlaybackRequest(
                mediaUri = parsed.mediaUrl,
                title = title,
                userAgent = parsed.userAgent,
                referer = parsed.referer,
                cookie = parsed.cookie,
                headers = headers
            )
        }
    }

    private fun directRequest(mediaUri: String, title: String?): PlaybackRequest =
        PlaybackRequest(
            mediaUri = mediaUri,
            title = title,
            userAgent = null,
            referer = null,
            cookie = null,
            headers = emptyMap()
        )

    private fun isM3uResponse(url: String, contentType: String, bodyPreview: String): Boolean {
        val lowerUrl = url.substringBefore('?').lowercase(Locale.ROOT)
        val lowerType = contentType.lowercase(Locale.ROOT)
        return lowerUrl.endsWith(".m3u") ||
            lowerUrl.endsWith(".m3u8") ||
            lowerType.contains("application/vnd.apple.mpegurl") ||
            lowerType.contains("application/x-mpegurl") ||
            lowerType.contains("audio/x-mpegurl") ||
            lowerType.contains("audio/mpegurl") ||
            lowerType.contains("text/plain") ||
            bodyPreview.trimStart().startsWith("#EXTM3U", ignoreCase = true)
    }

    private fun buildHeaders(userAgent: String?, referer: String?, cookie: String?): Map<String, String> =
        buildMap {
            userAgent?.takeIf { it.isNotBlank() }?.let { put("User-Agent", it) }
            referer?.takeIf { it.isNotBlank() }?.let { put("Referer", it) }
            cookie?.takeIf { it.isNotBlank() }?.let { put("Cookie", it) }
        }

    private companion object {
        /**
         * 提前续期窗口：剩余寿命不足 90 秒就重新取链。
         *
         * 播放器建连 + 起播缓冲通常要 1~3 秒，但 115 的签名链在服务端侧有传输延迟，
         * 留 90 秒也是为了覆盖「用户按下播放时，链已经只剩一分钟」这种从详情页停留很久才点播放的场景。
         */
        const val RENEWAL_LEAD_MILLIS: Long = 90_000L
    }
}
