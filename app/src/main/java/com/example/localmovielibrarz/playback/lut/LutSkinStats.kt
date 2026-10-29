package com.example.localmovielibrarz.playback.lut

import kotlin.math.max
import kotlin.math.min

/**
 * 一帧画面里「被判定为皮肤」的像素集合，以及由它算出的 7 个特征。
 *
 * 逐项对应参考实现 `_sample_skin_rgb_statistics`（mpv_realtime_lut.py:488-510）返回的
 * 字典，另外带上 [extract_skin] 附在同一个字典里的三项：识别区域名、高光像素数、
 * 高光像素的 R/B。
 *
 * ## 为什么字段是 Double 而不是 Float
 *
 * 参考实现里 `skin_rgb` 是 `np.float32` 数组，但 `np.mean()` 的结果会被
 * `float(...)` 转成 Python 的 float（float64）再参与后续标量运算；`skin_rb` 之类的
 * 比值、目标区间比较、平方误差也全在 float64 里算。这里照抄这个分工：
 * **像素是 Float（与 numpy float32 对齐），统计量与判据是 Double**。
 * 把统计量也压成 Float 会让 1e-7 量级的差异被参数搜索的整数阶梯放大成 ±1 档。
 *
 * @property skinRgb 展平的 RGB，每像素 3 个 0f..1f 分量，顺序与参考实现一致。
 * @property skinRb 肤色均值 R/B。
 * @property skinRg 肤色均值 R/G。
 * @property skinLum 肤色均值的 Rec.709 亮度。
 * @property skinSat 逐像素 (max-min)/max 的均值。
 * @property fullContrast 逐像素亮度的标准差（总体标准差，ddof=0）。
 * @property fullHlRatio 亮度 > 0.65 的像素占比。
 * @property fullShRatio 亮度 < 0.20 的像素占比。
 * @property regionName 肤色来自哪块区域，见 [LutSkinAnalyzer.REGION_CENTER]。
 * @property highPixelCount 亮度 > 0.70 的高光像素数（只统计，不参与判定）。
 * @property highRb 高光像素的 R/B 均值；高光像素不足 20 个时是 1.0。
 */
class LutSkinFeatures(
    val skinRgb: FloatArray,
    val skinRb: Double,
    val skinRg: Double,
    val skinLum: Double,
    val skinSat: Double,
    val fullContrast: Double,
    val fullHlRatio: Double,
    val fullShRatio: Double,
    val regionName: String = LutSkinAnalyzer.REGION_CENTER,
    val highPixelCount: Int = 0,
    val highRb: Double = 1.0
) {

    /** 参与统计的肤色像素个数。 */
    val skinPixelCount: Int get() = skinRgb.size / 3

    /** 画面里有没有可用的肤色数据。 */
    val hasSkin: Boolean get() = skinRgb.isNotEmpty()

    override fun toString(): String = buildString {
        append("LutSkinFeatures($regionName, n=").append(skinPixelCount)
        append(", R/B=").append(fmt(skinRb))
        append(", R/G=").append(fmt(skinRg))
        append(", lum=").append(fmt(skinLum))
        append(", sat=").append(fmt(skinSat))
        append(", contrast=").append(fmt(fullContrast))
        append(", hl=").append(fmt(fullHlRatio))
        append(", sh=").append(fmt(fullShRatio))
        append(", highRb=").append(fmt(highRb)).append(')')
    }

    private fun fmt(v: Double): String = String.format(java.util.Locale.US, "%.3f", v)
}

/**
 * 肤色统计与三个场景判据。
 *
 * 参考实现对应用 `_sample_skin_rgb_statistics` / `_range_error` /
 * `get_dynamic_hl_target` / `is_extreme_dark` / `is_high_match`
 * （mpv_realtime_lut.py:252-304, 488-510）。数值系数一字未改。
 *
 * ## float32 的模拟方式
 *
 * numpy 对 `float32` 数组求均值/标准差时是**先按 float32 累加、再返回 float32**，
 * 之后才被 `float(...)` 拓宽。这里用 Double 累加（比 float32 累加更准），
 * **末尾只舍入一次**到 Float 再拓宽——等价于「正确舍入的 float32 均值」，
 * 与 numpy 的成对求和最多差 1 ulp（实测该量级差异不改变任何一个参数，
 * 见 `work/autolutspec/sens.py`）。
 */
object LutSkinStats {

    // Rec.709 亮度系数（mpv_realtime_lut.py:118-120）。统计量在 Double 域计算。
    const val KR = 0.2126
    const val KG = 0.7152
    const val KB = 0.0722

    /** 高光像素阈值（mpv_realtime_lut.py:243）。 */
    const val HL_THRESHOLD = 0.65

    /** 阴影像素阈值（mpv_realtime_lut.py:244）。 */
    const val SH_THRESHOLD = 0.2

    /** 人脸区域的肤色亮度上限（mpv_realtime_lut.py:246）。中心区域另有 0.72 的限制。 */
    const val FACE_SKIN_LUM_MAX = 0.9

    /** 逐像素 float32 亮度，与 numpy 的 `KR*r + KG*g + KB*b` 同域。 */
    fun pixelLuma(r: Float, g: Float, b: Float): Float =
        (KR.toFloat() * r) + (KG.toFloat() * g) + (KB.toFloat() * b)

    /**
     * 由肤色像素算 7 个特征。参考实现 `_sample_skin_rgb_statistics`。
     *
     * @throws IllegalArgumentException 像素数组长度不是 3 的倍数。
     */
    fun sample(skinRgb: FloatArray): LutSkinFeatures {
        require(skinRgb.size % 3 == 0) { "肤色像素数组长度必须是 3 的倍数：${skinRgb.size}" }
        val n = skinRgb.size / 3
        require(n > 0) { "没有肤色像素，无法统计" }

        var sr = 0.0
        var sg = 0.0
        var sb = 0.0
        var satSum = 0.0
        var hlCount = 0
        var shCount = 0
        // 标准差要两趟：先求亮度均值，再求偏差平方和。
        var lumSum = 0.0

        for (i in 0 until n) {
            val base = i * 3
            val r = skinRgb[base]
            val g = skinRgb[base + 1]
            val b = skinRgb[base + 2]
            sr += r
            sg += g
            sb += b

            val mx = max(r, max(g, b))
            val mn = min(r, min(g, b))
            // np.maximum(s_max, 1e-6)：纯黑像素不产生除零。
            val safeMax = max(mx, 1e-6f)
            satSum += ((mx - mn) / safeMax)

            val lum = pixelLuma(r, g, b)
            lumSum += lum
            if (lum > HL_THRESHOLD) hlCount++
            if (lum < SH_THRESHOLD) shCount++
        }

        // 一次舍入到 float32，再拓宽成 Double——对齐 `float(np.mean(...))`。
        val mR = (sr / n).toFloat().toDouble()
        val mG = (sg / n).toFloat().toDouble()
        val mB = (sb / n).toFloat().toDouble()

        val rb = if (mB > 1e-6) mR / mB else 1.0
        val rg = if (mG > 1e-6) mR / mG else 1.0
        val lum = KR * mR + KG * mG + KB * mB
        val sat = (satSum / n).toFloat().toDouble()

        // 第二趟：相对 float32 均值（numpy 的 np.std 也是相对同一个均值算偏差）。
        val meanLumF = (lumSum / n).toFloat()
        var devSq = 0.0
        for (i in 0 until n) {
            val base = i * 3
            val d = pixelLuma(skinRgb[base], skinRgb[base + 1], skinRgb[base + 2]) - meanLumF
            devSq += (d * d).toDouble()
        }
        val contrast = kotlin.math.sqrt(devSq / n).toFloat().toDouble()

        return LutSkinFeatures(
            skinRgb = skinRgb,
            skinRb = rb,
            skinRg = rg,
            skinLum = lum,
            skinSat = sat,
            fullContrast = contrast,
            fullHlRatio = hlCount.toDouble() / n,
            fullShRatio = shCount.toDouble() / n
        )
    }

    /**
     * 区间外的距离；区间内为 0。参考实现 `_range_error`（mpv_realtime_lut.py:252-258）。
     *
     * 参数搜索的每一项误差都由「区间误差²」加权构成——它把「落在目标区间里」
     * 当作 0 代价，避免在区间内部继续优化到某个点而偏离整段目标。
     */
    fun rangeError(value: Double, lo: Double, hi: Double): Double = when {
        value < lo -> lo - value
        value > hi -> value - hi
        else -> 0.0
    }

    /**
     * 由「当前肤色亮度」推出这一帧希望的高光占比区间。
     *
     * 参考实现 `get_dynamic_hl_target`（mpv_realtime_lut.py:261-267）：皮肤越亮，
     * 允许的高光占比上限越高（0.35→0.05，0.54→0.20）。皮肤已经够亮时再压高光
     * 会把脸压灰，所以上限随亮度线性放宽。
     *
     * @return `(0.0, hlHi)`。
     */
    fun dynamicHlTarget(skinLum: Double): Pair<Double, Double> {
        val lumLo = 0.35
        val lumHi = 0.54
        val hlHiAtLo = 0.05
        val hlHiAtHi = 0.20
        val span = max(lumHi - lumLo, 1e-6)
        val ratio = ((skinLum - lumLo) / span).coerceIn(0.0, 1.0)
        return Pair(0.0, hlHiAtLo + (hlHiAtHi - hlHiAtLo) * ratio)
    }

    /**
     * 是否极暗片源。参考实现 `is_extreme_dark`（mpv_realtime_lut.py:270-279）。
     *
     * 三个条件同时成立：皮肤很暗、几乎没有高光、阴影占了画面四分之一以上。
     */
    fun isExtremeDark(f: LutSkinFeatures?): Boolean {
        if (f == null || !f.hasSkin) return false
        return f.skinLum < 0.28 && f.fullHlRatio < 0.01 && f.fullShRatio > 0.24
    }

    /**
     * 是否「高匹配」片源（色温本来就接近目标，不需要大幅偏冷）。
     *
     * 参考实现 `is_high_match`（mpv_realtime_lut.py:282-304）。除了四个区间都命中，
     * 还有一条捷径：冷色场景（R/B、R/G 都 < 1.30、饱和度 < 0.25、亮度 > 0.15）
     * 直接算高匹配——这种画面套标准模板会越调越冷。
     */
    fun isHighMatch(f: LutSkinFeatures?): Boolean {
        if (f == null || !f.hasSkin) return false
        if (isExtremeDark(f)) return false

        val isColdScene = f.skinRb < 1.30 && f.skinRg < 1.30 && f.skinSat < 0.25 && f.skinLum > 0.15
        if (isColdScene) return true

        val rbOk = f.skinRb in LutTargets.HIGH_MATCH.skinRbRange
        val rgOk = f.skinRg in LutTargets.HIGH_MATCH.skinRgRange
        val lumOk = f.skinLum in LutTargets.HIGH_MATCH.skinLumRange
        val satOk = f.skinSat in LutTargets.HIGH_MATCH.skinSatRange
        return rbOk && rgOk && lumOk && satOk
    }

    /** 参考实现的取整：Python 3 的 `round()` 是「四舍六入五成双」，不是 `Math.round`。 */
    fun pyRound(v: Double): Int = Math.rint(v).toInt()
}

/**
 * 三个模板各自的目标区间（mpv_realtime_lut.py:127-135 / 177-185 / 207-215）。
 *
 * 与 [LutPreset] 的三个预设一一对应：预设是「参数起点」，这里是「参数要往哪儿走」。
 */
internal class LutTargets(
    val skinRbRange: ClosedFloatingPointRange<Double>,
    val skinRgRange: ClosedFloatingPointRange<Double>,
    val skinLumRange: ClosedFloatingPointRange<Double>,
    val skinSatRange: ClosedFloatingPointRange<Double>,
    val fullContrastRange: ClosedFloatingPointRange<Double>,
    val fullHlRange: ClosedFloatingPointRange<Double>,
    val fullShRange: ClosedFloatingPointRange<Double>
) {
    companion object {
        val STANDARD = LutTargets(
            skinRbRange = 1.24..1.50,
            skinRgRange = 1.20..1.35,
            skinLumRange = 0.35..0.54,
            skinSatRange = 0.22..0.34,
            fullContrastRange = 0.10..0.20,
            fullHlRange = 0.00..0.22,
            fullShRange = 0.00..0.40
        )

        val HIGH_MATCH = LutTargets(
            skinRbRange = 1.03..1.36,
            skinRgRange = 1.10..1.30,
            skinLumRange = 0.38..0.62,
            skinSatRange = 0.12..0.42,
            fullContrastRange = 0.12..0.24,
            fullHlRange = 0.00..0.30,
            fullShRange = 0.00..0.24
        )

        val EXTREME_DARK = LutTargets(
            skinRbRange = 1.05..1.32,
            skinRgRange = 1.12..1.28,
            skinLumRange = 0.42..0.60,
            skinSatRange = 0.14..0.25,
            fullContrastRange = 0.13..0.23,
            fullHlRange = 0.00..0.30,
            fullShRange = 0.14..0.35
        )
    }
}

/**
 * 模板与可调范围（mpv_realtime_lut.py:137-205）。
 *
 * 模板是搜索起点：色温先给一个「预设偏好」，再在 [adjustRange] 内逐档搜。
 * 注意高匹配模板的 `temp` 范围是 `(-3, -8)`——lo > hi，`range(lo, hi+1)` 是空的。
 * 这不是笔误，参考实现就靠这个「空搜索」把色温固定在 [LutPreset.HighMatch] 的 -3 上
 * （真正的色温搜索在它之前已经单独做过一轮）。移植时保留同样的空区间语义。
 */
internal class LutTemplate(
    val temp: Int,
    val tint: Int,
    val sat: Int,
    val bright: Int,
    val contrast: Int,
    val highlight: Int,
    val shadow: Int,
    val adjustRange: Map<LutParamKey, ClosedRange<Int>>
) {
    fun valueOf(key: LutParamKey): Int = when (key) {
        LutParamKey.Temperature -> temp
        LutParamKey.Tint -> tint
        LutParamKey.Saturation -> sat
        LutParamKey.Brightness -> bright
        LutParamKey.Contrast -> contrast
        LutParamKey.Highlight -> highlight
        LutParamKey.Shadow -> shadow
    }

    fun rangeOf(key: LutParamKey): ClosedRange<Int> =
        adjustRange[key] ?: error("模板缺少参数范围：$key")

    /** 把搜索出来的 7 个值打包成参数对象。 */
    fun build(
        temp: Int = this.temp,
        tint: Int = this.tint,
        sat: Int = this.sat,
        bright: Int = this.bright,
        contrast: Int = this.contrast,
        highlight: Int = this.highlight,
        shadow: Int = this.shadow
    ): LutParams = LutParams(
        temperature = temp, tint = tint, saturation = sat, brightness = bright,
        contrast = contrast, highlight = highlight, shadow = shadow
    )

    companion object {
        /** `JY_TEMPLATE` + `JY_ADJUST_RANGE`。数值与 [LutPreset.Standard] 一致。 */
        val STANDARD = LutTemplate(
            temp = -20, tint = 6, sat = 0, bright = 0, contrast = 0, highlight = -2, shadow = 2,
            adjustRange = mapOf(
                LutParamKey.Temperature to -20..5,
                LutParamKey.Tint to -5..5,
                LutParamKey.Saturation to -5..3,
                LutParamKey.Brightness to -5..18,
                LutParamKey.Contrast to -3..2,
                LutParamKey.Highlight to -5..3,
                LutParamKey.Shadow to -5..10
            )
        )

        /** `JY_TEMPLATE_HIGH_MATCH` + `JY_ADJUST_RANGE_HIGH_MATCH`。 */
        val HIGH_MATCH = LutTemplate(
            temp = -3, tint = 6, sat = 0, bright = 5, contrast = 0, highlight = -2, shadow = 2,
            adjustRange = mapOf(
                LutParamKey.Temperature to -3..-8,
                LutParamKey.Tint to -5..5,
                LutParamKey.Saturation to -5..3,
                LutParamKey.Brightness to -5..5,
                LutParamKey.Contrast to -3..2,
                LutParamKey.Highlight to -2..2,
                LutParamKey.Shadow to -5..5
            )
        )

        /** `JY_TEMPLATE_EXTREME_DARK` + `JY_ADJUST_RANGE_EXTREME_DARK`。 */
        val EXTREME_DARK = LutTemplate(
            temp = -18, tint = 3, sat = -1, bright = 25, contrast = -2, highlight = -1, shadow = 20,
            adjustRange = mapOf(
                LutParamKey.Temperature to -10..4,
                LutParamKey.Tint to -3..5,
                LutParamKey.Saturation to -5..1,
                LutParamKey.Brightness to -15..10,
                LutParamKey.Contrast to -3..2,
                LutParamKey.Highlight to -3..2,
                LutParamKey.Shadow to -15..10
            )
        )
    }
}
