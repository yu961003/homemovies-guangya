package com.example.localmovielibrarz.playback.lut

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * 从一帧画面里提取肤色像素，得到 [LutSkinFeatures]。
 *
 * 参考实现是 `extract_skin`（mpv_realtime_lut.py:1515-1619）：先把画面缩到长边 640，
 * 再取中心 50% 区域（或人脸框），在该区域内用「Cr 动态分位区间 + RGB 比例 +
 * 亮度上下限」筛皮肤，最后交给 [LutSkinStats.sample] 算 7 个特征。
 *
 * ## 与参考实现的两处已知差异（均为有意为之）
 *
 * 1. **人脸框路径未移植**。参考实现用 MediaPipe FaceMesh 找鼻子区域的皮肤，
 *    失败时回退到「中心 90% 区域」。MediaPipe 需要约 3 MB 的 task 模型和额外依赖，
 *    而中心区域路径本来就是它的兜底。这里直接走中心区域，取 50%（比兜底的 90%
 *    更严格：中心区域越小，混进背景肤色（木地板、墙壁）的概率越低）。
 * 2. **缩放用普通双线性**，不是 OpenCV 的定点实现。两者像素值可能差 1，
 *    对统计量的影响远小于 [LutSkinStats] 里说明的 float32 舍入量级。
 *
 * 参考实现里的 `cv2.cvtColor(frame, COLOR_BGR2RGB)` 之所以存在，是因为它用
 * `cv2.imread` 读盘得到的是 BGR；本移植从 Android 的 `Bitmap` 取 ARGB，
 * 直接用 R/G/B 通道，没有 BGR 往返，也就没有这个转换。
 */
object LutSkinAnalyzer {

    /** 采样前把长边缩到这个大小。参考实现 `max_long_side = 640`。 */
    const val MAX_SAMPLE_DIM = 640

    /** 少于这么多皮肤像素就认为「这一帧没有可用肤色」。参考实现 `min_skin_pixels = 150`。 */
    const val MIN_SKIN_PIXELS = 150

    /** 中心区域占画面的比例（参考实现兜底路径是 0.9，这里更严格）。 */
    const val CENTER_ROI_RATIO = 0.5

    /** 中心区域的肤色亮度上限；人脸区域用 [LutSkinStats.FACE_SKIN_LUM_MAX]。 */
    const val CENTER_SKIN_LUM_MAX = 0.72

    /** 高光像素阈值，用于统计 highPixelCount / highRb。参考实现 `lum > 0.70`。 */
    const val HIGH_PIXEL_LUM = 0.70

    /** 高光像素少于这么多就不算 highRb，避免 1-2 个像素把比值带飞。 */
    const val MIN_HIGH_PIXELS = 20

    const val REGION_CENTER = "center"

    /** Cr 分位区间的硬边界：肤色经验区间 [120,190]，跨度不足 30 时退回 [130,180]。 */
    private const val CR_ABS_MIN = 120.0
    private const val CR_ABS_MAX = 190.0
    private const val CR_MIN_SPAN = 30.0
    private const val CR_FALLBACK_LO = 130.0
    private const val CR_FALLBACK_HI = 180.0

    /** R/G/B 比例判据（参考实现 452-456 行）。 */
    private const val SKIN_MIN_RB = 1.05
    private const val SKIN_MAX_RB = 1.90
    private const val SKIN_MIN_RG = 1.05
    private const val SKIN_MAX_RG = 1.25

    /**
     * 分析一帧。
     *
     * @param argb 行优先的 ARGB 像素，长度必须是 width*height。
     * @return 肤色特征；画面里肤色像素不足 [MIN_SKIN_PIXELS] 时返回 null。
     * @throws IllegalArgumentException 尺寸与像素数不匹配。
     */
    fun analyze(argb: IntArray, width: Int, height: Int): LutSkinFeatures? {
        require(width > 0 && height > 0) { "画面尺寸非法：${width}x$height" }
        require(argb.size == width * height) { "像素数与尺寸不匹配：${argb.size} != ${width * height}" }

        val (frame, w, h) = downscale(argb, width, height)

        // 中心 50% 区域。Python 的 // 是向下取整，正值与整数除法等价。
        val cw = (w * CENTER_ROI_RATIO).toInt()
        val ch = (h * CENTER_ROI_RATIO).toInt()
        val x1 = max(0, (w - cw) / 2)
        val y1 = max(0, (h - ch) / 2)
        val x2 = min(w, (w + cw) / 2)
        val y2 = min(h, (h + ch) / 2)
        if (x2 <= x1 || y2 <= y1) return null

        val (crLo, crHi) = dynamicCrRange(frame, w, x1, y1, x2, y2)
        val lumLimit = CENTER_SKIN_LUM_MAX

        var skin = FloatArray(min(4096, (x2 - x1) * (y2 - y1) * 3).coerceAtLeast(3))
        var skinCount = 0
        var highCount = 0
        var highRbSum = 0.0

        for (y in y1 until y2) {
            val rowBase = y * w
            for (x in x1 until x2) {
                val p = frame[rowBase + x]
                val r = (p ushr 16) and 0xFF
                val g = (p ushr 8) and 0xFF
                val b = p and 0xFF

                // Python 里是 r/255 + 0.0 的 float64 运算。
                val lum = LutSkinStats.KR * r / 255.0 + LutSkinStats.KG * g / 255.0 + LutSkinStats.KB * b / 255.0

                if (lum > HIGH_PIXEL_LUM) {
                    highCount++
                    highRbSum += r / max(b.toDouble(), 1.0)
                }
                if (lum >= lumLimit) continue

                // 查表命中就是肤色，和 numpy 的向量化掩码等价。
                if (!isSkinColor(r.toDouble(), g.toDouble(), b.toDouble(), crLo, crHi)) continue

                if (skinCount * 3 + 3 > skin.size) {
                    skin = skin.copyOf(max(skin.size * 2, skinCount * 3 + 3))
                }
                val base = skinCount * 3
                skin[base] = (r / 255.0).toFloat()
                skin[base + 1] = (g / 255.0).toFloat()
                skin[base + 2] = (b / 255.0).toFloat()
                skinCount++
            }
        }

        if (skinCount < MIN_SKIN_PIXELS) return null

        val pixels = if (skin.size == skinCount * 3) skin else skin.copyOf(skinCount * 3)
        val base = LutSkinStats.sample(pixels)
        val highRb = if (highCount >= MIN_HIGH_PIXELS) highRbSum / highCount else 1.0
        return LutSkinFeatures(
            skinRgb = base.skinRgb,
            skinRb = base.skinRb,
            skinRg = base.skinRg,
            skinLum = base.skinLum,
            skinSat = base.skinSat,
            fullContrast = base.fullContrast,
            fullHlRatio = base.fullHlRatio,
            fullShRatio = base.fullShRatio,
            regionName = REGION_CENTER,
            highPixelCount = highCount,
            highRb = highRb
        )
    }

    /**
     * Cr 的动态分位区间：取区域内 Cr 的 5%/95% 分位，再夹到 [120,190]。
     *
     * 参考实现 435-449 行。用分位而不是固定阈值，是为了让不同色温的片源都能选中
     * 「这一帧里偏红的那一撮像素」，同时对极端画面（全白、全蓝）有硬边界兜底。
     */
    private fun dynamicCrRange(argb: IntArray, w: Int, x1: Int, y1: Int, x2: Int, y2: Int): Pair<Double, Double> {
        val n = (x2 - x1) * (y2 - y1)
        val crs = DoubleArray(n)
        var i = 0
        for (y in y1 until y2) {
            val rowBase = y * w
            for (x in x1 until x2) {
                val p = argb[rowBase + x]
                val r = (p ushr 16) and 0xFF
                val g = (p ushr 8) and 0xFF
                val b = p and 0xFF
                val yy = 0.299 * r + 0.587 * g + 0.114 * b
                crs[i++] = (r - yy) * 0.713 + 128.0
            }
        }
        var lo = percentile(crs, 0.05)
        var hi = percentile(crs, 0.95)
        lo = max(CR_ABS_MIN, lo)
        hi = min(CR_ABS_MAX, hi)
        if (hi - lo < CR_MIN_SPAN) {
            lo = CR_FALLBACK_LO
            hi = CR_FALLBACK_HI
        }
        return Pair(lo, hi)
    }

    /**
     * 肤色判据。参考实现 `is_skin_color`（mpv_realtime_lut.py:1503-1512）。
     *
     * 三条同时成立才算皮肤：Cr 落在区域分位区间内、R/B 在 1.05-1.90、
     * R/G 在 1.05-1.25。R/G 那条是关键——它把「偏黄的木地板」和「偏红的皮肤」分开。
     */
    private fun isSkinColor(r: Double, g: Double, b: Double, crLo: Double, crHi: Double): Boolean {
        if (r <= 1e-6 || b <= 1e-6 || g <= 1e-6) return false
        val yy = 0.299 * r + 0.587 * g + 0.114 * b
        val cr = (r - yy) * 0.713 + 128.0
        if (cr < crLo || cr > crHi) return false
        val rb = r / b
        if (rb < SKIN_MIN_RB || rb > SKIN_MAX_RB) return false
        val rg = r / g
        return rg >= SKIN_MIN_RG && rg <= SKIN_MAX_RG
    }

    /**
     * 线性插值分位数，与 `np.percentile(..., method='linear')` 同式：
     * `index = (n-1) * q`，在相邻两个次序统计量之间插值。
     */
    internal fun percentile(values: DoubleArray, q: Double): Double {
        if (values.isEmpty()) return Double.NaN
        values.sort()
        val idx = (values.size - 1) * q
        val lo = floor(idx).toInt()
        val hi = min(values.size - 1, lo + 1)
        if (lo == hi) return values[lo]
        val frac = idx - lo
        return values[lo] + (values[hi] - values[lo]) * frac
    }

    /**
     * 长边超过 [MAX_SAMPLE_DIM] 时双线性缩小，否则原样返回。
     *
     * `int(w * scale)` 与参考实现的 `int()` 一致（正数截断=向下取整）。
     */
    private fun downscale(argb: IntArray, width: Int, height: Int): Triple<IntArray, Int, Int> {
        val longSide = max(width, height)
        if (longSide <= MAX_SAMPLE_DIM) return Triple(argb, width, height)

        val scale = MAX_SAMPLE_DIM.toDouble() / longSide
        val newW = max(1, (width * scale).toInt())
        val newH = max(1, (height * scale).toInt())
        if (newW == width && newH == height) return Triple(argb, width, height)

        val out = IntArray(newW * newH)
        val scaleX = width.toDouble() / newW
        val scaleY = height.toDouble() / newH
        for (dy in 0 until newH) {
            // 半像素中心对齐（与 cv2.resize 的 INTER_LINEAR 采样位置一致）。
            val fy = (dy + 0.5) * scaleY - 0.5
            val y0 = floor(max(0.0, fy)).toInt().coerceAtMost(height - 1)
            val y1 = min(height - 1, y0 + 1)
            val wy = (fy - y0).coerceIn(0.0, 1.0)
            for (dx in 0 until newW) {
                val fx = (dx + 0.5) * scaleX - 0.5
                val x0 = floor(max(0.0, fx)).toInt().coerceAtMost(width - 1)
                val x1 = min(width - 1, x0 + 1)
                val wx = (fx - x0).coerceIn(0.0, 1.0)

                val p00 = argb[y0 * width + x0]
                val p01 = argb[y0 * width + x1]
                val p10 = argb[y1 * width + x0]
                val p11 = argb[y1 * width + x1]
                val r = blend(blend((p00 ushr 16) and 0xFF, (p01 ushr 16) and 0xFF, wx),
                    blend((p10 ushr 16) and 0xFF, (p11 ushr 16) and 0xFF, wx), wy)
                val g = blend(blend((p00 ushr 8) and 0xFF, (p01 ushr 8) and 0xFF, wx),
                    blend((p10 ushr 8) and 0xFF, (p11 ushr 8) and 0xFF, wx), wy)
                val b = blend(blend(p00 and 0xFF, p01 and 0xFF, wx),
                    blend(p10 and 0xFF, p11 and 0xFF, wx), wy)
                out[dy * newW + dx] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return Triple(out, newW, newH)
    }

    private fun blend(a: Int, b: Int, t: Double): Int = (a + (b - a) * t).toInt().coerceIn(0, 255)
}
