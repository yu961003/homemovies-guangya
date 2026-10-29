package com.example.localmovielibrarz.playback

import android.net.Uri

object PickcodeExtractor {
    private val supportedRoutes = setOf("download_m3u", "play", "video_proxy", "guangya_res", "guangya_vod")

    fun extract(url: String): String? {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
        if (uri.host in supportedRoutes) {
            return uri.pathSegments.firstOrNull()?.trim()?.takeIf { it.isNotBlank() }
        }
        val segments = uri.pathSegments
        for (index in segments.indices) {
            if (segments[index] in supportedRoutes && index + 1 < segments.size) {
                return segments[index + 1].trim().takeIf { it.isNotBlank() }
            }
        }
        return null
    }

    /** 返回命中的路由段（P0-2 修正：供 PlaybackResolver 正确分流 115 vs 光鸭）。 */
    fun routeOf(url: String): String? {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
        if (uri.host in supportedRoutes) return uri.host
        for (seg in uri.pathSegments) if (seg in supportedRoutes) return seg
        return null
    }
}
