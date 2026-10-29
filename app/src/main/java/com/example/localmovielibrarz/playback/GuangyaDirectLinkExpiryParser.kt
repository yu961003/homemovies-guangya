package com.example.localmovielibrarz.playback

import android.net.Uri

/**
 * 从光鸭 signedURL 解析过期时间（P1-6）。
 * 优先取 URL 中的 expires/expire 参数，解析失败回退 30 分钟，对齐 115 的 DirectLinkExpiryParser 思路。
 */
object GuangyaDirectLinkExpiryParser {
    fun parse(url: String, nowSeconds: Long = System.currentTimeMillis() / 1000L): Long {
        val u = runCatching { Uri.parse(url) }.getOrNull()
        val expires = u?.getQueryParameter("expires")
            ?: u?.getQueryParameter("expire")
            ?: u?.getQueryParameter("Expires")
        return expires?.toLongOrNull()?.coerceAtLeast(nowSeconds) ?: (nowSeconds + 1800L)
    }
}
