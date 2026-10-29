package com.example.localmovielibrarz.data.repository

import com.example.localmovielibrarz.cloudguangya.GuangyaApiClient
import com.example.localmovielibrarz.data.local.GuangyaDirectLinkDao
import com.example.localmovielibrarz.data.local.GuangyaDirectLinkEntity
import com.example.localmovielibrarz.playback.GuangyaDirectLinkExpiryParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 光鸭直链解析器（镜像 DirectLinkRepository）：fileId → signedURL，带缓存与过期。
 * 与 115 的 pickcode 解析平行，由 PlaybackResolver 按 route 分派调用。
 */
class GuangyaDirectLinkResolver(
    private val dao: GuangyaDirectLinkDao,
    private val apiClient: GuangyaApiClient
) {
    suspend fun resolve(fileId: String, isVod: Boolean, forceRefresh: Boolean = false): String =
        withContext(Dispatchers.IO) {
            val now = nowSeconds()
            if (!forceRefresh) dao.get(fileId)?.let {
                if (it.url.isNotBlank() && it.expiresAt > now) return@withContext it.url
            }
            val directUrl = if (isVod) apiClient.fetchVodUrl(fileId, null)
            else apiClient.fetchDirectUrl(fileId)
            dao.upsert(
                GuangyaDirectLinkEntity(
                    fileId = fileId,
                    url = directUrl,
                    expiresAt = GuangyaDirectLinkExpiryParser.parse(directUrl, now),
                    updatedAt = now
                )
            )
            directUrl
        }

    suspend fun invalidate(fileId: String) = withContext(Dispatchers.IO) { dao.delete(fileId) }

    private fun nowSeconds(): Long = System.currentTimeMillis() / 1000L
}
