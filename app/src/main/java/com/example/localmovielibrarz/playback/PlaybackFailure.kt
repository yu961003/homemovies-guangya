package com.example.localmovielibrarz.playback

import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.source.UnrecognizedInputFormatException

/**
 * 播放失败的结构化归因（参考 peach 的「三个异常 → 三个状态码」思路，
 * 但鸭影是本地播放器，不需要 HTTP 状态码输出，需要的是「能不能自愈」）。
 *
 * 现状问题：只有 403 被单独识别成「直链过期、可换链重试」，其余失败一律把
 * `error.message` 甩到界面上——用户看到 `Source error` 完全不知道该怎么办。
 */
enum class PlaybackFailureKind(
    /** 是否值得自动刷新直链后重试一次。 */
    val autoRefreshLink: Boolean,
    /** 给用户看的一句话建议。 */
    val userHint: String
) {
    /** 401/403：直链过期或鉴权失效，最典型的「刷新链接就能好」。 */
    LinkExpired(
        autoRefreshLink = true,
        userHint = "播放链接已失效，正在自动刷新链接"
    ),

    /** 404/410：文件被移动或删除，刷新链接也没用。 */
    LinkGone(
        autoRefreshLink = false,
        userHint = "云端文件已不存在（可能被移动或删除），请重新扫描影片库"
    ),

    /** 429/503：网盘限流，等一会儿再试比立刻重试更好。 */
    Throttled(
        autoRefreshLink = false,
        userHint = "网盘暂时限制了访问，请稍后重试"
    ),

    /** 连接超时 / DNS / TLS：与文件内容无关。 */
    Network(
        autoRefreshLink = false,
        userHint = "网络连接异常，请检查网络后重试"
    ),

    /** 拿到的字节流不是可识别格式：可能下到了验证页或错误页。 */
    UnrecognizedFormat(
        autoRefreshLink = true,
        userHint = "拿到的不是视频内容（可能是登录页或被拦页面），正在自动刷新链接"
    ),

    /** 解码器不支持该编码：换播放器内核或该文件确实无法硬解。 */
    Decoder(
        autoRefreshLink = false,
        userHint = "当前设备无法解码该视频（编码不受支持），可尝试软解或转码"
    ),

    Unknown(
        autoRefreshLink = false,
        userHint = "播放失败，请重试"
    )
}

data class PlaybackFailure(
    val kind: PlaybackFailureKind,
    val httpCode: Int? = null,
    val message: String = ""
) {
    val userMessage: String
        get() = if (message.isBlank()) kind.userHint else "${kind.userHint}（$message）"
}

object PlaybackFailureClassifier {

    fun classify(error: Throwable): PlaybackFailure {
        var current: Throwable? = error
        var depth = 0
        var httpCode: Int? = null
        var sawUnrecognizedFormat = false
        var sawTimeout = false
        while (current != null && depth < 10) {
            when (current) {
                is HttpDataSource.InvalidResponseCodeException -> {
                    if (httpCode == null) httpCode = current.responseCode
                }
                is UnrecognizedInputFormatException -> sawUnrecognizedFormat = true
                is java.net.SocketTimeoutException,
                is java.net.UnknownHostException,
                is javax.net.ssl.SSLException -> sawTimeout = true
                is ExoPlaybackException -> {
                    if (current.type == ExoPlaybackException.TYPE_SOURCE &&
                        current.cause is java.io.IOException &&
                        httpCode == null &&
                        !sawUnrecognizedFormat
                    ) {
                        // 源码层 IOException 且没有状态码：多数是连接被重置。
                        sawTimeout = true
                    }
                }
                else -> Unit
            }
            current = current.cause
            depth += 1
        }

        val kind = when {
            httpCode == 401 || httpCode == 403 -> PlaybackFailureKind.LinkExpired
            httpCode == 404 || httpCode == 410 -> PlaybackFailureKind.LinkGone
            httpCode == 429 || httpCode == 503 -> PlaybackFailureKind.Throttled
            sawUnrecognizedFormat -> PlaybackFailureKind.UnrecognizedFormat
            sawTimeout -> PlaybackFailureKind.Network
            isDecoderFailure(error) -> PlaybackFailureKind.Decoder
            else -> PlaybackFailureKind.Unknown
        }
        return PlaybackFailure(
            kind = kind,
            httpCode = httpCode,
            message = error.message.orEmpty()
        )
    }

    private fun isDecoderFailure(error: Throwable): Boolean {
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth < 10) {
            // ExoPlaybackException 没有 TYPE_DECODER 常量：解码失败统一表现为
            // TYPE_RENDERER（Renderer 内部抛的 DecoderException/MediaCodec 异常）。
            // 原先写的 TYPE_DECODER 是不存在的常量，编译期就会未解析。
            if (current is ExoPlaybackException &&
                current.type == ExoPlaybackException.TYPE_RENDERER
            ) {
                return true
            }
            val name = current::class.java.simpleName
            if (name.contains("MediaCodec", ignoreCase = true) ||
                name.contains("Decoder", ignoreCase = true)
            ) {
                return true
            }
            current = current.cause
            depth += 1
        }
        return false
    }
}
