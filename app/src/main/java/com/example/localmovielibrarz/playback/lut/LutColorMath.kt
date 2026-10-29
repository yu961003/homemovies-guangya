package com.example.localmovielibrarz.playback.lut

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 实时调色的色彩数学。
 *
 * 逐项移植自参考实现 `mpv_realtime_lut.py`（`apply_temp_tint` / `apply_saturation` /
 * `apply_brightness` / `apply_contrast` / `apply_highlight_shadow` / `apply_all`），
 * 系数与阈值一一对应，**不做"优化"**——调色参数是主观审美，任何系数改动都会让
 * 结果偏离用户已经习惯的那一版。每个函数的 KDoc 都写明参考实现的行号，便于回溯。
 *
 * ## 为什么是标量
 *
 * 参考实现是 numpy 向量化的（一次处理几万个肤色像素）。这里处理的是 LUT 格点，
 * 一个 33³ 的 LUT 有 35937 个格点、每格 3 个通道，但**每个格点只算一次**，
 * 而且最耗时的部分（高光衰减）在标量下只是一个 if。向量化的收益抵不过引入
 * 一个矩阵库的成本，所以全部写成 `Float` 标量。
 *
 * ## 顺序不能换
 *
 * [applyAll] 的算子顺序（亮度 → 高光阴影 → 对比度 → 饱和度 → 色温色调 → 过曝保护）
 * 与参考实现 `apply_all` 一致。调色算子是**非交换**的：把饱和度放到色温前面，
 * 结果会随参数增大而明显分叉。这条顺序是行为兼容的一部分。
 */
object LutColorMath {

    // Rec.709 亮度系数（参考实现 mpv_realtime_lut.py:118-124）。
    private const val KR = 0.2126f
    private const val KG = 0.7152f
    private const val KB = 0.0722f

    /** 过曝保护阈值（参考实现 mpv_realtime_lut.py:241）。 */
    private const val OVEREXPOSE_LIMIT = 0.92f

    // 亮度算子的高光/阴影衰减参数（mpv_realtime_lut.py:234-239）。
    private const val HL_BRIGHT_DAMP_START = 0.7f
    private const val HL_BRIGHT_DAMP_STRENGTH = 0.7f
    private const val HL_BRIGHT_DAMP_MIN = 0.3f

    private const val HL_CONTRAST_DAMP_STRENGTH = 0.5f
    private const val HL_CONTRAST_DAMP_MIN = 0.5f

    /** 色温算子的高光保护起点与强度（mpv_realtime_lut.py:331-338）。 */
    private const val TEMP_HL_START = 0.70f
    private const val TEMP_HL_PROTECT = 0.95f

    fun luma(r: Float, g: Float, b: Float): Float = KR * r + KG * g + KB * b

    /**
     * 色温 / 色调。参考实现 mpv_realtime_lut.py:310-344。
     *
     * 两个关键设计：
     * 1. **亮度守恒**：RGB 三个增益算完后按亮度系数重新归一，否则调色温会顺带改整体亮度，
     *    用户会看到"越来越亮"而找不到原因。
     * 2. **高光保护**：亮度超过 0.70 的像素按比例减弱增益（最多减弱 95%），
     *    避免高光区被打成染色块。
     */
    fun applyTempTint(r: Float, g: Float, b: Float, temp: Float, tint: Float): Triple<Float, Float, Float> {
        val t = temp / 50f
        val ti = tint / 50f

        var rGain = 1f + t * 0.23f
        var gGain = 1f + t * 0.058f
        var bGain = 1f - t * 0.23f

        rGain *= 1f + ti * 0.045f
        gGain *= 1f - ti * 0.09f
        bGain *= 1f + ti * 0.045f

        val lumBefore = KR + KG + KB
        val lumAfter = KR * rGain + KG * gGain + KB * bGain
        if (lumAfter > 0.001f) {
            val scale = lumBefore / lumAfter
            rGain *= scale
            gGain *= scale
            bGain *= scale
        }

        val lum = luma(r, g, b)
        val hlWeight = if (lum > TEMP_HL_START) {
            ((lum - TEMP_HL_START) / (1f - TEMP_HL_START)).coerceIn(0f, 1f)
        } else {
            0f
        }
        val hlDamp = 1f - hlWeight * TEMP_HL_PROTECT

        val rEff = 1f + (rGain - 1f) * hlDamp
        val gEff = 1f + (gGain - 1f) * hlDamp
        val bEff = 1f + (bGain - 1f) * hlDamp

        return Triple(r * rEff, g * gEff, b * bEff)
    }

    /** 饱和度。参考实现 mpv_realtime_lut.py:347-355。围绕亮度灰色点做线性扩张。 */
    fun applySaturation(r: Float, g: Float, b: Float, sat: Float): Triple<Float, Float, Float> {
        if (sat == 0f) return Triple(r, g, b)
        val gray = luma(r, g, b)
        val scale = 1f + sat / 50f * 0.82f
        return Triple(
            gray + scale * (r - gray),
            gray + scale * (g - gray),
            gray + scale * (b - gray)
        )
    }

    /**
     * 亮度。参考实现 mpv_realtime_lut.py:358-385。
     *
     * 高光侧按 `(lum-0.7)/(1-0.7)` 衰减、下限 0.3；阴影侧在 `lum<0.18` 时把增益压到
     * 0.35..1.0。两侧都压住是为了「提亮时不把白衣打爆、不提亮时不死黑」。
     * 额外一条：`bright>0` 且 `lum>0.85` 时把增益线性拉回 1，防止近白区溢出。
     */
    fun applyBrightness(r: Float, g: Float, b: Float, bright: Float): Triple<Float, Float, Float> {
        if (bright == 0f) return Triple(r, g, b)
        val lum = luma(r, g, b)

        val hlT = if (lum > HL_BRIGHT_DAMP_START) {
            (lum - HL_BRIGHT_DAMP_START) / (1f - HL_BRIGHT_DAMP_START)
        } else {
            0f
        }
        val hlDamp = max(HL_BRIGHT_DAMP_MIN, 1f - hlT * HL_BRIGHT_DAMP_STRENGTH)

        val shadowStart = 0.18f
        val shadowMin = 0.35f
        val shDamp = if (lum < shadowStart) {
            shadowMin + (1f - shadowMin) * (lum / shadowStart)
        } else {
            1f
        }

        var scale = 1f + bright / 50f * 0.67f * hlDamp * shDamp

        val veryHigh = 0.85f
        if (bright > 0f) {
            val protectW = ((lum - veryHigh) / (1f - veryHigh)).coerceIn(0f, 1f)
            scale = scale * (1f - protectW) + protectW
        }

        return Triple(r * scale, g * scale, b * scale)
    }

    /**
     * 对比度。参考实现 mpv_realtime_lut.py:388-401。
     *
     * 绕 0.5 中点扩张，并对高光做 0.5 下限的衰减（对比度拉高最容易先炸高光）。
     */
    fun applyContrast(r: Float, g: Float, b: Float, contrast: Float): Triple<Float, Float, Float> {
        if (contrast == 0f) return Triple(r, g, b)
        val lum = luma(r, g, b)
        val hlT = if (lum > HL_BRIGHT_DAMP_START) {
            (lum - HL_BRIGHT_DAMP_START) / (1f - HL_BRIGHT_DAMP_START)
        } else {
            0f
        }
        val hlDamp = max(HL_CONTRAST_DAMP_MIN, 1f - hlT * HL_CONTRAST_DAMP_STRENGTH)
        val scale = 1f + contrast / 50f * 0.57f * hlDamp
        return Triple(
            0.5f + scale * (r - 0.5f),
            0.5f + scale * (g - 0.5f),
            0.5f + scale * (b - 0.5f)
        )
    }

    /**
     * 高光 / 阴影。参考实现 mpv_realtime_lut.py:404-420。
     *
     * 与 [applyBrightness] 的区别：这里按亮度分段**乘性**调整，亮度 0.5 为分界，
     * 权重线性递增到边界。两者叠加才能做出「高光压一点、阴影提一点」而中间调不动。
     */
    fun applyHighlightShadow(
        r: Float, g: Float, b: Float, highlight: Float, shadow: Float
    ): Triple<Float, Float, Float> {
        if (highlight == 0f && shadow == 0f) return Triple(r, g, b)
        val lum = luma(r, g, b)
        var outR = r
        var outG = g
        var outB = b
        if (highlight != 0f) {
            val w = if (lum > 0.5f) (lum - 0.5f) / 0.5f else 0f
            val s = 1f + highlight / 50f * 0.7f * w
            outR *= s
            outG *= s
            outB *= s
        }
        if (shadow != 0f) {
            val w = if (lum < 0.5f) (0.5f - lum) / 0.5f else 0f
            val s = 1f + shadow / 50f * 0.7f * w
            outR *= s
            outG *= s
            outB *= s
        }
        return Triple(outR, outG, outB)
    }

    /**
     * 完整调色链。参考实现 mpv_realtime_lut.py:449-485。
     *
     * 末尾的过曝保护只对「原本没过曝、调整后过曝」的格点生效（`pushed_over`）：
     * 本来就已经超过 0.92 的输入不动，否则会把片源本身的高光细节压平。
     *
     * 最后无条件 clamp 到 0..1：`.cube` 的定义域就是 0..1，写文件前不夹会让 mpv
     * 读到越界值并按自己的规则裁剪，结果与我们算的不一致。
     */
    fun applyAll(rIn: Float, gIn: Float, bIn: Float, params: LutParams): Triple<Float, Float, Float> {
        val lumBefore = luma(rIn, gIn, bIn)

        var (r, g, b) = applyBrightness(rIn, gIn, bIn, params.brightness.toFloat())
        run {
            val t = applyHighlightShadow(r, g, b, params.highlight.toFloat(), params.shadow.toFloat())
            r = t.first; g = t.second; b = t.third
        }
        run {
            val t = applyContrast(r, g, b, params.contrast.toFloat())
            r = t.first; g = t.second; b = t.third
        }
        run {
            val t = applySaturation(r, g, b, params.saturation.toFloat())
            r = t.first; g = t.second; b = t.third
        }
        run {
            val t = applyTempTint(r, g, b, params.temperature.toFloat(), params.tint.toFloat())
            r = t.first; g = t.second; b = t.third
        }

        if (!params.isNeutral()) {
            val lumAfter = luma(r, g, b)
            if (lumBefore <= OVEREXPOSE_LIMIT && lumAfter > OVEREXPOSE_LIMIT) {
                val scale = OVEREXPOSE_LIMIT / max(lumAfter, 1e-6f)
                r *= scale
                g *= scale
                b *= scale
            }
        }

        return Triple(
            r.coerceIn(0f, 1f),
            g.coerceIn(0f, 1f),
            b.coerceIn(0f, 1f)
        )
    }

    /**
     * 按参数生成一张 LUT。参考实现 mpv_realtime_lut.py:1452-1464。
     *
     * 格点遍历顺序（b 外层、r 内层）与 [CubeLut] 的序列化顺序一致，改一个就必须改另一个。
     */
    fun generateLut(size: Int, params: LutParams): CubeLut {
        val values = FloatArray(size * size * size * 3)
        val denom = (size - 1).toFloat()
        var i = 0
        for (b in 0 until size) {
            val bIn = b / denom
            for (g in 0 until size) {
                val gIn = g / denom
                for (r in 0 until size) {
                    val rIn = r / denom
                    val out = applyAll(rIn, gIn, bIn, params)
                    values[i] = out.first
                    values[i + 1] = out.second
                    values[i + 2] = out.third
                    i += 3
                }
            }
        }
        return CubeLut(size, values)
    }

    /**
     * 生成「已按强度混合」的最终 LUT。
     *
     * 强度在生成阶段就混进数据里，因为 mpv 的 `--target-lut` 只能加载一张文件，
     * 没有不透明度参数（见 [CubeLut.blendTowards] 的说明）。
     */
    fun generateScaledLut(size: Int, params: LutParams, strengthPercent: Int): CubeLut {
        val base = generateLut(size, params)
        val k = strengthPercent.coerceIn(0, 100) / 100f
        return if (k >= 1f) base else base.blendTowards(base.identity(), 1f - k)
    }

    /**
     * 从「绝对参数」换算成参考实现的预设值。
     *
     * 参考实现的参数是相对模板的绝对偏移，模板本身不是全 0（`JY_TEMPLATE` 是
     * temp=-20 / tint=+6 / shadow=+2）。用户点「标准模板」时要看到模板的实际数值，
     * 而不是一堆 0——所以预设直接给出模板值。
     */
    fun presetValues(preset: LutPreset): LutParams = preset.params

    /**
     * 参数是否已经"看得见"。
     *
     * 全 0（或强度 0）时下发 LUT 只会白白让 mpv 重载一次文件，画面还闪一下。
     */
    fun paramsWillChangeImage(params: LutParams, strengthPercent: Int): Boolean =
        strengthPercent > 0 && !params.isNeutral()

    /** 与参考实现一致的取整，供测试与日志断言使用。 */
    fun quantize6(v: Float): Int = (v.coerceIn(0f, 1f) * 1_000_000f).roundToInt()

    /** 仅用于诊断：两个参数是否在容差内相等。 */
    fun nearlyEquals(a: Float, b: Float, epsilon: Float = 1e-6f): Boolean = abs(a - b) <= epsilon
}
