package com.example.localmovielibrarz.playback.engine

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log

/**
 * 热管理（参考 mpvRx「ThermalMonitor / Anime4K 热降级 / 4K 守卫」能力，独立实现）。
 *
 * ## 为什么需要
 *
 * Anime4K 的 CNN 着色器每帧都在 GPU 上跑，手机连续播放 20~30 分钟后进入温控降频，
 * 表现为「越看越卡、掉帧、机身烫」。等用户自己发现再去关，体验已经坏了。
 * Android 11+ 的 [PowerManager.getThermalHeadroom] 能给出未来 N 秒的热余量预测
 * （1.0 = 达到严重降频阈值），据此**提前**降档。
 *
 * ## 策略
 *
 * | headroom | 等级 | 动作 |
 * |---|---|---|
 * | < 0.80 | 正常 | 不动 |
 * | 0.80 ~ 1.0 | 偏热 | Anime4K 降到「性能优先」档（S 模型） |
 * | ≥ 1.0 | 过热 | 关闭 Anime4K |
 *
 * 只降不升：恢复到正常后不自动升回去（升档要重编着色器，有一次卡顿；热量也会马上再上来）。
 * 下次起播按用户设置重新开始。
 *
 * 采样受平台限频（频繁调用会返回 NaN），这里最少间隔 [SAMPLE_INTERVAL_MS]，NaN 沿用上一次有效值。
 */
object ThermalGuard {

    private const val TAG = "ThermalGuard"
    const val SAMPLE_INTERVAL_MS = 10_000L
    private const val FORECAST_SECONDS = 10

    enum class Level { NORMAL, WARM, HOT }

    private var lastSampleAt = -SAMPLE_INTERVAL_MS
    private var lastHeadroom = 0f

    /** 当前热余量（0 表示读不到或平台不支持）。 */
    @Synchronized
    fun headroom(context: Context): Float {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return 0f
        val now = SystemClock.elapsedRealtime()
        if (now - lastSampleAt < SAMPLE_INTERVAL_MS) return lastHeadroom
        lastSampleAt = now
        runCatching {
            val pm = context.getSystemService(PowerManager::class.java)
            val sample = pm?.getThermalHeadroom(FORECAST_SECONDS)
            if (sample != null && sample.isFinite()) lastHeadroom = sample.coerceAtLeast(0f)
        }.onFailure { Log.w(TAG, "读取热余量失败：${it.message}") }
        return lastHeadroom
    }

    fun level(headroom: Float): Level = when {
        headroom >= 1.0f -> Level.HOT
        headroom >= 0.8f -> Level.WARM
        else -> Level.NORMAL
    }

    /**
     * 高分辨率守卫：Anime4K 的放大 pass 会把 4K 片源推到 8K 中间纹理，
     * 手机 GPU 必然掉帧。像素数超过 2560×1440 时直接不启用。
     */
    fun anime4kAllowedFor(width: Int, height: Int): Boolean =
        width <= 0 || height <= 0 || width.toLong() * height <= 2560L * 1440L
}

/**
 * 自动黑边检测（参考 mpvRx「Automatic Black-Bar Cropping」能力，独立实现）。
 *
 * 纯函数、可单测。原则与上游相同：**宁可漏裁，绝不误裁**——暗场景可能留下一条边，
 * 但绝不允许把真实画面裁掉。具体判据：
 * - 一行（列）被视为黑边，需同时满足：亮于 [CONTENT_LUMA] 的像素占比 < 1.5%，
 *   且该行最大亮度 < [MAX_BAR_LUMA]（字幕压在黑边上时整行不算黑边）；
 * - 从边缘向内扫描，连续 2 行不满足即停止；单边最多 30%；
 * - 中央 50% 区域几乎全黑（暗场景）时放弃检测。
 */
object AutoCropDetector {

    private const val CONTENT_LUMA = 30
    private const val MAX_BAR_LUMA = 48
    private const val MAX_BRIGHT_RATIO = 0.015f
    private const val MAX_EDGE = 0.30f
    private const val MIN_EDGE = 0.01f

    /** 四边的裁剪量（像素）。 */
    data class Crop(val left: Int, val top: Int, val right: Int, val bottom: Int, val width: Int, val height: Int) {
        val isEmpty: Boolean get() = left == 0 && top == 0 && right == 0 && bottom == 0
        val croppedWidth: Int get() = width - left - right
        val croppedHeight: Int get() = height - top - bottom

        /** 按比例换算到另一分辨率（截帧是缩略图，mpv 的 video-crop 要原始像素）。 */
        fun scaledTo(w: Int, h: Int): Crop {
            if (width <= 0 || height <= 0) return this
            val sx = w.toFloat() / width
            val sy = h.toFloat() / height
            // 向内取整：宁可少裁一像素
            return Crop(
                left = kotlin.math.ceil(left * sx).toInt(),
                top = kotlin.math.ceil(top * sy).toInt(),
                right = kotlin.math.ceil(right * sx).toInt(),
                bottom = kotlin.math.ceil(bottom * sy).toInt(),
                width = w,
                height = h
            )
        }

        /** mpv `video-crop` 的取值：`WxH+X+Y`。 */
        fun toMpvValue(): String = "${croppedWidth}x$croppedHeight+$left+$top"
    }

    fun detect(argb: IntArray, width: Int, height: Int): Crop? {
        if (width < 32 || height < 32 || argb.size != width * height) return null
        if (!hasContent(argb, width, height)) return null
        val top = scanRows(argb, width, height, fromTop = true)
        val bottom = scanRows(argb, width, height, fromTop = false)
        val left = scanCols(argb, width, height, fromLeft = true)
        val right = scanCols(argb, width, height, fromLeft = false)
        fun keep(v: Int, total: Int) = if (v >= total * MIN_EDGE) v else 0
        val crop = Crop(keep(left, width), keep(top, height), keep(right, width), keep(bottom, height), width, height)
        if (crop.croppedWidth < width * 0.5f || crop.croppedHeight < height * 0.5f) return null
        return crop
    }

    private fun luma(p: Int): Int {
        val r = (p ushr 16) and 0xFF
        val g = (p ushr 8) and 0xFF
        val b = p and 0xFF
        return (r * 54 + g * 183 + b * 19) shr 8
    }

    private fun hasContent(px: IntArray, w: Int, h: Int): Boolean {
        var bright = 0
        var total = 0
        for (y in h / 4 until h * 3 / 4 step 2) {
            val row = y * w
            for (x in w / 4 until w * 3 / 4 step 2) {
                total++
                if (luma(px[row + x]) > CONTENT_LUMA) bright++
            }
        }
        return total > 0 && bright.toFloat() / total > 0.04f
    }

    private fun isBarRow(px: IntArray, w: Int, y: Int): Boolean {
        var bright = 0
        var max = 0
        val row = y * w
        for (x in 0 until w) {
            val l = luma(px[row + x])
            if (l > max) max = l
            if (l > CONTENT_LUMA) bright++
        }
        return max < MAX_BAR_LUMA && bright < w * MAX_BRIGHT_RATIO
    }

    private fun isBarCol(px: IntArray, w: Int, h: Int, x: Int): Boolean {
        var bright = 0
        var max = 0
        for (y in 0 until h) {
            val l = luma(px[y * w + x])
            if (l > max) max = l
            if (l > CONTENT_LUMA) bright++
        }
        return max < MAX_BAR_LUMA && bright < h * MAX_BRIGHT_RATIO
    }

    private fun scanRows(px: IntArray, w: Int, h: Int, fromTop: Boolean): Int {
        val limit = (h * MAX_EDGE).toInt()
        var accepted = 0
        var misses = 0
        for (d in 0 until limit) {
            val y = if (fromTop) d else h - 1 - d
            if (isBarRow(px, w, y)) {
                accepted = d + 1
                misses = 0
            } else if (++misses >= 2) break
        }
        return accepted
    }

    private fun scanCols(px: IntArray, w: Int, h: Int, fromLeft: Boolean): Int {
        val limit = (w * MAX_EDGE).toInt()
        var accepted = 0
        var misses = 0
        for (d in 0 until limit) {
            val x = if (fromLeft) d else w - 1 - d
            if (isBarCol(px, w, h, x)) {
                accepted = d + 1
                misses = 0
            } else if (++misses >= 2) break
        }
        return accepted
    }
}
