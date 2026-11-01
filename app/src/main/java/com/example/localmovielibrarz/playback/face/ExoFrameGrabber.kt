package com.example.localmovielibrarz.playback.face

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Log
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.max

/**
 * exo 内核下的「截一帧原片」。
 *
 * ## 为什么不直接 PixelCopy 播放画面
 *
 * exo 侧的 LUT 是作为 `setVideoEffects` 挂在渲染管线里的，SurfaceView 上看到的已经是**调过色**
 * 的画面；拿它去做自动分析，会出现「越分析越偏」的正反馈（分析的是自己上一次的输出）。
 * mpv 侧用的 `screenshot-to-file … video` 截的是片源帧，两边必须同口径，所以这里用
 * [MediaMetadataRetriever] 直接从媒体源解当前时间点附近的关键帧——它拿到的一定是原片。
 *
 * ## 代价与边界
 *
 * - 取的是最近**同步帧**（`OPTION_CLOSEST_SYNC`），与当前画面可能差零点几秒，对「统计肤色」无影响；
 * - 网络直链需要带上播放用的请求头（115/光鸭校验 UA/Referer/Cookie），由调用方传入；
 * - `MediaMetadataRetriever` 的网络读取不可中断，这里放在独立线程并设硬超时，超时后放弃
 *   （retriever 在后台自行 release），不阻塞分析流程。
 */
object ExoFrameGrabber {

    private const val TAG = "ExoFrameGrabber"
    private const val TIMEOUT_MS = 8_000L

    private val executor = Executors.newCachedThreadPool { r ->
        Thread(r, "exo-frame-grab").apply { isDaemon = true }
    }

    /** 最近一次失败的原因（给 UI 展示）。 */
    @Volatile
    var lastFailure: String? = null
        private set

    /**
     * 取 [positionMs] 附近的一帧，长边不超过 [maxSide]。必须在后台线程调用。
     */
    fun grab(
        context: Context,
        mediaUri: String,
        headers: Map<String, String>,
        positionMs: Long,
        maxSide: Int = 960
    ): Bitmap? {
        val future = executor.submit(Callable { grabBlocking(context.applicationContext, mediaUri, headers, positionMs, maxSide) })
        return try {
            future.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (t: Throwable) {
            future.cancel(true)
            lastFailure = "抽帧超时或失败（${t.javaClass.simpleName}）"
            Log.w(TAG, lastFailure!!, t)
            null
        }
    }

    private fun grabBlocking(
        context: Context,
        mediaUri: String,
        headers: Map<String, String>,
        positionMs: Long,
        maxSide: Int
    ): Bitmap? {
        val retriever = MediaMetadataRetriever()
        try {
            val uri = Uri.parse(mediaUri)
            when (uri.scheme?.lowercase()) {
                "http", "https" -> retriever.setDataSource(mediaUri, headers)
                "file" -> retriever.setDataSource(uri.path)
                null, "" -> retriever.setDataSource(mediaUri)
                else -> retriever.setDataSource(context, uri)
            }
            val timeUs = positionMs.coerceAtLeast(0L) * 1000L
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val frame = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1 && width > 0 && height > 0) {
                val scale = (maxSide.toFloat() / max(width, height)).coerceAtMost(1f)
                retriever.getScaledFrameAtTime(
                    timeUs,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    (width * scale).toInt().coerceAtLeast(1),
                    (height * scale).toInt().coerceAtLeast(1)
                )
            } else {
                retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            }
            if (frame == null) {
                lastFailure = "媒体源没有返回可用帧（可能是不支持的封装或 DRM）"
                return null
            }
            lastFailure = null
            return if (frame.config == Bitmap.Config.ARGB_8888) frame else frame.copy(Bitmap.Config.ARGB_8888, false).also { frame.recycle() }
        } catch (t: Throwable) {
            lastFailure = "抽帧失败：${t.javaClass.simpleName} ${t.message.orEmpty()}"
            Log.w(TAG, lastFailure!!, t)
            return null
        } finally {
            runCatching { retriever.release() }
        }
    }
}
