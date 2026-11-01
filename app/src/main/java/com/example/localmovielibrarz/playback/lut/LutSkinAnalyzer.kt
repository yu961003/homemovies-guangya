package com.example.localmovielibrarz.playback.lut

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * 从一帧画面里提取肤色像素，得到 [LutSkinFeatures]。
 *
 * 参考实现是 `extract_skin`（mpv_realtime_lut.py:1515-1619）：先把画面缩到长边 640，
 * 再取人脸区域（MediaPipe FaceMesh）或兜底区域，在该区域内用「Cr 动态分位区间 + RGB 比例 +
 * 亮度上下限」筛皮肤，最后交给 [LutSkinStats.sample] 算 7 个特征。
 *
 * ## v2：人脸路径已接入（v1 的已知差异 1 已消除）
 *
 * v1 没有人脸检测，只能整帧找肤色：木地板、墙面、暖光下的床单都会混进来，R/B 与亮度
 * 统计被背景拉偏，自动分析搜出来的色温经常偏冷一档。现在调用方可以传入
 * [LutFaceRegion]（由 `playback/face/MediaPipeFaceRegionDetector` 产出）：
 *
 * 1. **人脸路径**：只在「脸部轮廓 − 眼/眉/唇」的掩码里统计，亮度上限用参考实现的人脸档
 *    [LutSkinStats.FACE_SKIN_LUM_MAX]；Cr 分位区间也只在脸上算，背景不再影响阈值。
 * 2. **兜底路径**：没有人脸、或人脸区域里有效肤色像素不足 [MIN_SKIN_PIXELS] 时，
 *    回到 v1 的整帧路径（行为与 v1 完全一致）。
 *
 * ## v2：性能
 *
 * - Cr 值每像素只算一次（v1 在分位统计和逐像素判定里各算一遍）；
 * - 两个分位数共用一次排序（v1 每取一个分位就整体排序一次）。
 *
 * 参考实现里的 `cv2.cvtColor(frame, COLOR_BGR2RGB)` 之所以存在，是因为它用
 * `cv2.imread` 读盘得到的是 BGR；本移植从 Android 的 `Bitmap` 取 ARGB，没有这个转换。
 */
object LutSkinAnalyzer {

    /** 采样前把长边缩到这个大小。参考实现 `max_long_side = 640`。 */
    const val MAX_SAMPLE_DIM = 640

    /** 少于这么多皮肤像素就认为「这一帧没有可用肤色」。参考实现 `min_skin_pixels = 150`。 */
    const val MIN_SKIN_PIXELS = 150

    /**
     * 兜底区域占画面的比例。
     *
     * 曾经是 0.5，但日式片源的脸经常偏到画面一侧，0.5 会导致「明明有脸却提示没找到人脸」。
     * 现在取全画面 1.0：没有人脸信息时把「找肤色」交给后面的判据。
     */
    const val CENTER_ROI_RATIO = 1.0

    /** 兜底路径的肤色亮度上限（与人脸档一致，见 v1 注释）。 */
    const val CENTER_SKIN_LUM_MAX = 0.90

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

    /** R/G 上限：放宽到 1.40 覆盖亚洲片源常见的偏暖肤色，仍能挡掉纯红物体。 */
    private const val SKIN_MAX_RG = 1.40

    /**
     * 分析一帧（无人脸信息）。行为与 v1 完全一致。
     *
     * @param argb 行优先的 ARGB 像素，长度必须是 width*height。
     * @return 肤色特征；画面里肤色像素不足 [MIN_SKIN_PIXELS] 时返回 null。
     */
    fun analyze(argb: IntArray, width: Int, height: Int): LutSkinFeatures? =
        analyze(argb, width, height, emptyList())

    /**
     * 分析一帧，优先使用 [faces] 指定的人脸区域。
     *
     * @param faces 归一化坐标的人脸区域；空列表等价于 v1 的整帧分析。
     */
    fun analyze(argb: IntArray, width: Int, height: Int, faces: List<LutFaceRegion>): LutSkinFeatures? {
        require(width > 0 && height > 0) { "画面尺寸非法：${width}x$height" }
        require(argb.size == width * height) { "像素数与尺寸不匹配：${argb.size} != ${width * height}" }

        val frame = downscale(argb, width, height)

        if (faces.isNotEmpty()) {
            val raster = LutFaceGeometry.rasterize(faces, frame.width, frame.height)
            if (raster != null) {
                val (mask, _) = raster
                val usable = faces.filter { it.areaFraction >= LutFaceGeometry.MIN_FACE_AREA_FRACTION }
                val regionName = if (usable.any { it.source == LutFaceRegion.Source.MESH }) {
                    LutFaceRegion.Source.MESH.regionName
                } else {
                    LutFaceRegion.Source.BOX.regionName
                }
                val bounds = maskBounds(mask, frame.width, frame.height)
                if (bounds != null) {
                    val features = collect(
                        frame = frame,
                        x1 = bounds[0], y1 = bounds[1], x2 = bounds[2], y2 = bounds[3],
                        mask = mask,
                        lumLimit = LutSkinStats.FACE_SKIN_LUM_MAX,
                        regionName = regionName,
                        faceCount = usable.size
                    )
                    if (features != null) return features
                }
            }
        }

        // 兜底：整帧（与 v1 一致）。Python 的 // 是向下取整，正值与整数除法等价。
        val w = frame.width
        val h = frame.height
        val cw = (w * CENTER_ROI_RATIO).toInt()
        val ch = (h * CENTER_ROI_RATIO).toInt()
        val x1 = max(0, (w - cw) / 2)
        val y1 = max(0, (h - ch) / 2)
        val x2 = min(w, (w + cw) / 2)
        val y2 = min(h, (h + ch) / 2)
        if (x2 <= x1 || y2 <= y1) return null
        return collect(frame, x1, y1, x2, y2, mask = null, lumLimit = CENTER_SKIN_LUM_MAX, regionName = REGION_CENTER, faceCount = 0)
    }

    /**
     * 在矩形 [x1,x2)×[y1,y2)（且掩码为真）的像素里筛肤色并统计。
     */
    private fun collect(
        frame: Frame,
        x1: Int, y1: Int, x2: Int, y2: Int,
        mask: BooleanArray?,
        lumLimit: Double,
        regionName: String,
        faceCount: Int
    ): LutSkinFeatures? {
        val w = frame.width
        val px = frame.pixels

        // 第一趟：区域内每个像素的 Cr（只算一次，第二趟复用）。
        val capacity = (x2 - x1) * (y2 - y1)
        val crs = DoubleArray(capacity)
        val idx = IntArray(capacity)
        var n = 0
        for (y in y1 until y2) {
            val rowBase = y * w
            for (x in x1 until x2) {
                val i = rowBase + x
                if (mask != null && !mask[i]) continue
                val p = px[i]
                val r = (p ushr 16) and 0xFF
                val g = (p ushr 8) and 0xFF
                val b = p and 0xFF
                val yy = 0.299 * r + 0.587 * g + 0.114 * b
                crs[n] = (r - yy) * 0.713 + 128.0
                idx[n] = i
                n++
            }
        }
        if (n == 0) return null

        // Cr 的动态分位区间（参考实现 435-449 行）：一次排序取两个分位。
        val sorted = crs.copyOf(n)
        sorted.sort()
        var crLo = percentileSorted(sorted, 0.05)
        var crHi = percentileSorted(sorted, 0.95)
        crLo = max(CR_ABS_MIN, crLo)
        crHi = min(CR_ABS_MAX, crHi)
        if (crHi - crLo < CR_MIN_SPAN) {
            crLo = CR_FALLBACK_LO
            crHi = CR_FALLBACK_HI
        }

        var skin = FloatArray(min(4096, n * 3).coerceAtLeast(3))
        var skinCount = 0
        var highCount = 0
        var highRbSum = 0.0

        for (k in 0 until n) {
            val p = px[idx[k]]
                val r = (p ushr 16) and 0xFF
                val g = (p ushr 8) and 0xFF
                val b = p and 0xFF

                val lum = LutSkinStats.KR * r / 255.0 + LutSkinStats.KG * g / 255.0 + LutSkinStats.KB * b / 255.0
                if (lum > HIGH_PIXEL_LUM) {
                    highCount++
                    highRbSum += r / max(b.toDouble(), 1.0)
                }
                if (lum >= lumLimit) continue
            if (!isSkinColor(r.toDouble(), g.toDouble(), b.toDouble(), crs[k], crLo, crHi)) continue

                if (skinCount * 3 + 3 > skin.size) {
                    skin = skin.copyOf(max(skin.size * 2, skinCount * 3 + 3))
                }
                val base = skinCount * 3
                skin[base] = (r / 255.0).toFloat()
                skin[base + 1] = (g / 255.0).toFloat()
                skin[base + 2] = (b / 255.0).toFloat()
                skinCount++
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
            regionName = regionName,
            highPixelCount = highCount,
            highRb = highRb,
            faceCount = faceCount
        )
    }

    /**
     * 肤色判据。参考实现 `is_skin_color`（mpv_realtime_lut.py:1503-1512）。
     *
     * 三条同时成立才算皮肤：Cr 落在区域分位区间内、R/B 在 1.05-1.90、R/G 在 1.05-1.40。
     * [cr] 由调用方预先算好（与 v1 内部重算的值逐位相同）。
     */
    private fun isSkinColor(r: Double, g: Double, b: Double, cr: Double, crLo: Double, crHi: Double): Boolean {
        if (r <= 1e-6 || b <= 1e-6 || g <= 1e-6) return false
        if (cr < crLo || cr > crHi) return false
        val rb = r / b
        if (rb < SKIN_MIN_RB || rb > SKIN_MAX_RB) return false
        val rg = r / g
        return rg >= SKIN_MIN_RG && rg <= SKIN_MAX_RG
    }

    /** 掩码的包围盒 `[x1, y1, x2, y2)`；空掩码返回 null。 */
    private fun maskBounds(mask: BooleanArray, w: Int, h: Int): IntArray? {
        var minX = w
        var minY = h
        var maxX = -1
        var maxY = -1
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                if (mask[row + x]) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        if (maxX < 0) return null
        return intArrayOf(minX, minY, maxX + 1, maxY + 1)
    }

    /**
     * 线性插值分位数，与 `np.percentile(..., method='linear')` 同式。会对入参排序。
     */
    internal fun percentile(values: DoubleArray, q: Double): Double {
        if (values.isEmpty()) return Double.NaN
        values.sort()
        return percentileSorted(values, q)
    }

    /** [percentile] 的已排序版本（不再排序）。 */
    private fun percentileSorted(sorted: DoubleArray, q: Double): Double {
        if (sorted.isEmpty()) return Double.NaN
        val idx = (sorted.size - 1) * q
        val lo = floor(idx).toInt()
        val hi = min(sorted.size - 1, lo + 1)
        if (lo == hi) return sorted[lo]
        val frac = idx - lo
        return sorted[lo] + (sorted[hi] - sorted[lo]) * frac
    }

    /** 缩放后的画面（替代 v1 的 `Triple<IntArray, Int, Int>`，语义更清晰）。 */
    private class Frame(val pixels: IntArray, val width: Int, val height: Int)

    /**
     * 长边超过 [MAX_SAMPLE_DIM] 时双线性缩小，否则原样返回。
     *
     * `int(w * scale)` 与参考实现的 `int()` 一致（正数截断=向下取整）；
     * 半像素中心对齐与 cv2.resize 的 INTER_LINEAR 采样位置一致。
     */
    private fun downscale(argb: IntArray, width: Int, height: Int): Frame {
        val longSide = max(width, height)
        if (longSide <= MAX_SAMPLE_DIM) return Frame(argb, width, height)

        val scale = MAX_SAMPLE_DIM.toDouble() / longSide
        val newW = max(1, (width * scale).toInt())
        val newH = max(1, (height * scale).toInt())
        if (newW == width && newH == height) return Frame(argb, width, height)

        val out = IntArray(newW * newH)
        val scaleX = width.toDouble() / newW
        val scaleY = height.toDouble() / newH
        // 列方向的采样位置与权重只取决于 dx，预计算一次（v1 在每一行里重复计算）。
        val x0s = IntArray(newW)
        val x1s = IntArray(newW)
        val wxs = DoubleArray(newW)
        for (dx in 0 until newW) {
            val fx = (dx + 0.5) * scaleX - 0.5
            val x0 = floor(max(0.0, fx)).toInt().coerceAtMost(width - 1)
            x0s[dx] = x0
            x1s[dx] = min(width - 1, x0 + 1)
            wxs[dx] = (fx - x0).coerceIn(0.0, 1.0)
        }
        for (dy in 0 until newH) {
            val fy = (dy + 0.5) * scaleY - 0.5
            val y0 = floor(max(0.0, fy)).toInt().coerceAtMost(height - 1)
            val y1 = min(height - 1, y0 + 1)
            val wy = (fy - y0).coerceIn(0.0, 1.0)
            val row0 = y0 * width
            val row1 = y1 * width
            for (dx in 0 until newW) {
                val x0 = x0s[dx]
                val x1 = x1s[dx]
                val wx = wxs[dx]
                val p00 = argb[row0 + x0]
                val p01 = argb[row0 + x1]
                val p10 = argb[row1 + x0]
                val p11 = argb[row1 + x1]
                val r = blend(blend((p00 ushr 16) and 0xFF, (p01 ushr 16) and 0xFF, wx),
                    blend((p10 ushr 16) and 0xFF, (p11 ushr 16) and 0xFF, wx), wy)
                val g = blend(blend((p00 ushr 8) and 0xFF, (p01 ushr 8) and 0xFF, wx),
                    blend((p10 ushr 8) and 0xFF, (p11 ushr 8) and 0xFF, wx), wy)
                val b = blend(blend(p00 and 0xFF, p01 and 0xFF, wx),
                    blend(p10 and 0xFF, p11 and 0xFF, wx), wy)
                out[dy * newW + dx] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return Frame(out, newW, newH)
    }

    private fun blend(a: Int, b: Int, t: Double): Int = (a + (b - a) * t).toInt().coerceIn(0, 255)
}
