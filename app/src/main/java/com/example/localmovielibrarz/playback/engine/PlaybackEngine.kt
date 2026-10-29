package com.example.localmovielibrarz.playback.engine

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.localmovielibrarz.mpv.MPVLib
import java.io.File

/**
 * 播放内核标识、播放快照与可用性三个纯数据类型定义在 EngineTypes.kt，
 * 以便切换决策的单测能在纯 JVM 上运行（不引入 Android 依赖）。
 * 本文件只保留需要 Android / media3 类型的引擎接口与工厂。
 */

/**
 * 统一的引擎能力面。
 *
 * 刻意保持「窄」：只声明 UI 与 ViewModel 真正用到的成员。
 * 不为 mpv 造 media3 `Player` 的兼容壳——那需要 150+ 个方法，且会把 mpv 语义硬塞进
 * media3 的状态机，是典型的过度设计。
 */
interface PlaybackEngine {

    val kind: PlaybackEngineKind

    /** 观测当前播放事实。UI 侧合并进 PlayerUiState 后只读这里。 */
    val snapshot: kotlinx.coroutines.flow.StateFlow<PlaybackSnapshot>

    fun play()
    fun pause()
    fun setPlayWhenReady(playWhenReady: Boolean)
    fun seekTo(positionMs: Long)
    fun seekBy(deltaMs: Long)

    /** @return 实际生效的倍速（可能被引擎夹取）。 */
    fun setSpeed(speed: Float): Float

    /**
     * 装载媒体。
     * @param uri 媒体地址（http(s) / file）
     * @param headers 额外请求头（115/光鸭直链需要 Referer/Cookie）
     */
    fun load(uri: String, headers: Map<String, String>, startPositionMs: Long, autoPlay: Boolean)

    /** 挂载外挂字幕（内部负责把 content:// 落成 mpv 可读的本地文件）。 */
    fun setExternalSubtitle(subtitle: ExternalSubtitle, offsetMs: Long)
    fun clearExternalSubtitle()
    fun setSubtitleOffset(offsetMs: Long)
    fun setSubtitleStyle(style: SubtitleStyle)
    fun setSubtitleEnabled(enabled: Boolean)

    /** 选择音频轨；`id = null` 表示交还引擎自动选择。 */
    fun selectAudioTrack(trackId: Int?)
    fun release()
}

/** 外挂字幕的引擎无关描述。 */
data class ExternalSubtitle(
    val name: String,
    val uri: Uri,
    /** 原始 content:// 需要落盘才能交给 mpv，file:// 可直接用。 */
    val localFile: File? = null
)

/** 字幕外观。两个引擎各自的映射见 ExoPlayer 的 applyExternalSubtitleStyle 与 MPV 的 sub-* 属性。 */
data class SubtitleStyle(
    val fontSizeSp: Int = 18,
    val bottomPaddingPercent: Int = 8,
    val backgroundAlphaPercent: Int = 60
)

/**
 * 引擎工厂 + 全局能力探测。
 *
 * 只在首次调用时探测一次（loadLibrary + isAvailable），结果缓存。
 * 探测失败绝不让异常上抛：mpv 缺失是「功能降级」而不是「启动失败」。
 */
object PlaybackEngineFactory {

    private const val TAG = "PlaybackEngineFactory"

    @Volatile
    private var cachedAvailability: EngineAvailability? = null

    fun availability(context: Context, kind: PlaybackEngineKind): EngineAvailability {
        if (kind == PlaybackEngineKind.EXO) {
            return EngineAvailability(PlaybackEngineKind.EXO, available = true)
        }
        cachedAvailability?.let { return it }

        val result = synchronized(this) {
            cachedAvailability ?: probeMpv(context).also { cachedAvailability = it }
        }
        Log.i(TAG, "mpv 可用性: ${result.available} ${result.reason.orEmpty()}")
        return result
    }

    private fun probeMpv(context: Context): EngineAvailability {
        if (!MPVLib.probe()) {
            return EngineAvailability(
                PlaybackEngineKind.MPV,
                available = false,
                reason = "未检测到 libmpv.so（或 libplayer.so 加载失败）：" +
                    (MPVLib.loadErrorMessage() ?: "原生库缺失")
            )
        }
        return EngineAvailability(PlaybackEngineKind.MPV, available = true)
    }

    /** 清空缓存，仅用于「设置里重新检测引擎」这类显式操作。 */
    fun resetProbe() {
        cachedAvailability = null
    }
}
