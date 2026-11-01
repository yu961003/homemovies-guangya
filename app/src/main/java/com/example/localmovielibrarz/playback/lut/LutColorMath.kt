package com.example.localmovielibrarz.playback.lut

import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 实时调色的色彩数学（对外门面）。
 *
 * 逐项移植自参考实现 `mpv_realtime_lut.py`（`apply_temp_tint` / `apply_saturation` /
 * `apply_brightness` / `apply_contrast` / `apply_highlight_shadow` / `apply_all`），
 * 系数与阈值一一对应，**不做"优化"**——调色参数是主观审美，任何系数改动都会让
 * 结果偏离用户已经习惯的那一版。
 *
 * ## v2 变化（性能，不改结果）
 *
 * 真正的逐像素运算已经收敛到 [LutGradeKernel]，本对象只保留：
 * - 与 v1 签名一致的单算子函数（返回 `Triple`，供单测与诊断逐项比对）；
 * - LUT 生成：参数预编译一次、格点零分配、强度混合在生成时**就地**完成
 *   （v1 是「生成 → 再分配一张单位 LUT → 再分配一张混合结果」三份数组），
 *   33³ 及以上按蓝色切片并行。
 *
 * ## 顺序不能换
 *
 * 算子顺序（亮度 → 高光阴影 → 对比度 → 饱和度 → 色温色调 → 过曝保护）与参考实现
 * `apply_all` 一致；调色算子是非交换的，这条顺序是行为兼容的一部分。
 */
object LutColorMath {

    private const val KR = LutGradeKernel.KR
    private const val KG = LutGradeKernel.KG
    private const val KB = LutGradeKernel.KB

    /** 达到这个边长才并行：17³ 只有 4913 个格点，线程调度开销比计算本身还大。 */
    private const val PARALLEL_MIN_SIZE = 33

    /**
     * 生成线程池。守护线程、按核数封顶 4：LUT 生成是短促突发（几毫秒到几十毫秒），
     * 开满所有核只会和解码线程抢 CPU。
     */
    private val pool: ExecutorService by lazy {
        val n = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
        Executors.newFixedThreadPool(n) { r -> Thread(r, "lut-gen").apply { isDaemon = true } }
    }

    fun luma(r: Float, g: Float, b: Float): Float = KR * r + KG * g + KB * b

    /** 色温 / 色调。参考实现 mpv_realtime_lut.py:310-344。 */
    fun applyTempTint(r: Float, g: Float, b: Float, temp: Float, tint: Float): Triple<Float, Float, Float> =
        single(r, g, b, RawChain(temp = temp, tint = tint, forceTemp = true))

    /** 饱和度。参考实现 mpv_realtime_lut.py:347-355。 */
    fun applySaturation(r: Float, g: Float, b: Float, sat: Float): Triple<Float, Float, Float> =
        if (sat == 0f) Triple(r, g, b) else single(r, g, b, RawChain(sat = sat))

    /** 亮度。参考实现 mpv_realtime_lut.py:358-385。 */
    fun applyBrightness(r: Float, g: Float, b: Float, bright: Float): Triple<Float, Float, Float> =
        if (bright == 0f) Triple(r, g, b) else single(r, g, b, RawChain(bright = bright))

    /** 对比度。参考实现 mpv_realtime_lut.py:388-401。 */
    fun applyContrast(r: Float, g: Float, b: Float, contrast: Float): Triple<Float, Float, Float> =
        if (contrast == 0f) Triple(r, g, b) else single(r, g, b, RawChain(contrast = contrast))

    /** 高光 / 阴影。参考实现 mpv_realtime_lut.py:404-420。 */
    fun applyHighlightShadow(
        r: Float, g: Float, b: Float, highlight: Float, shadow: Float
    ): Triple<Float, Float, Float> =
        if (highlight == 0f && shadow == 0f) {
            Triple(r, g, b)
        } else {
            single(r, g, b, RawChain(highlight = highlight, shadow = shadow))
        }

    /** 单算子评估：只跑调色链里的这一段，不做过曝保护与裁剪（与 v1 单算子函数语义一致）。 */
    private fun single(r: Float, g: Float, b: Float, chain: RawChain): Triple<Float, Float, Float> {
        val out = FloatArray(3)
        chain.apply(r, g, b, out)
        return Triple(out[0], out[1], out[2])
    }

    /**
     * 完整调色链。参考实现 mpv_realtime_lut.py:449-485。
     *
     * 末尾的过曝保护只对「原本没过曝、调整后过曝」的格点生效；最后无条件 clamp 到 0..1。
     */
    fun applyAll(rIn: Float, gIn: Float, bIn: Float, params: LutParams): Triple<Float, Float, Float> {
        val out = FloatArray(3)
        LutGradeKernel(params).apply(rIn, gIn, bIn, out, 0)
        return Triple(out[0], out[1], out[2])
        }

    /** 完整调色链（含创意层）。 */
    fun applyAll(
        rIn: Float, gIn: Float, bIn: Float, params: LutParams, creative: LutCreativeParams
    ): Triple<Float, Float, Float> {
        val out = FloatArray(3)
        LutGradeKernel(params, creative).apply(rIn, gIn, bIn, out, 0)
        return Triple(out[0], out[1], out[2])
    }

    /**
     * 按参数生成一张 LUT。参考实现 mpv_realtime_lut.py:1452-1464。
     *
     * 格点遍历顺序（b 外层、r 内层）与 [CubeLut] 的序列化顺序一致。
     */
    fun generateLut(size: Int, params: LutParams): CubeLut =
        generate(size, LutGradeKernel(params), strength = 1f, overlay = null, overlayMix = 0f)

    /**
     * 生成「已按强度混合」的最终 LUT。
     *
     * 强度在生成阶段就混进数据里，因为 mpv 的 `--target-lut` 只能加载一张文件，
     * 没有不透明度参数（见 [CubeLut.blendTowards] 的说明）。
     */
    fun generateScaledLut(size: Int, params: LutParams, strengthPercent: Int): CubeLut =
        generateScaledLut(size, params, LutCreativeParams.NEUTRAL, strengthPercent, null, 0)

    /**
     * v2 完整入口：参考参数 + 创意参数 + 可选外部风格 LUT + 强度。
     *
     * @param overlay 用户导入的 `.cube` 风格 LUT；在参数调色**之后**以四面体插值采样
     *   （调色师的常规顺序：先做技术校正，再套创意 Look）。
     * @param overlayMixPercent 风格 LUT 的混合比例 0..100。
     */
    fun generateScaledLut(
        size: Int,
        params: LutParams,
        creative: LutCreativeParams,
        strengthPercent: Int,
        overlay: CubeLut?,
        overlayMixPercent: Int
    ): CubeLut {
        val k = strengthPercent.coerceIn(0, 100) / 100f
        val kernel = LutGradeKernel(params, creative)
        val mix = if (overlay == null) 0f else overlayMixPercent.coerceIn(0, 100) / 100f
        return generate(size, kernel, strength = k, overlay = overlay.takeIf { mix > 0f }, overlayMix = mix)
    }

    /**
     * 生成核心。
     *
     * 强度混合沿用 v1 [CubeLut.blendTowards] 的公式 `a + (id - a) * (1 - k)`，
     * 两端短路也保持一致（k=0 直接得单位值、k=1 直接得原值），因此结果与 v1 逐位相同。
     */
    private fun generate(
        size: Int,
        kernel: LutGradeKernel,
        strength: Float,
        overlay: CubeLut?,
        overlayMix: Float
    ): CubeLut {
        val values = FloatArray(size * size * size * 3)
        val denom = (size - 1).toFloat()
        val axis = FloatArray(size) { it / denom }
        val t = 1f - strength

        fun slice(bFrom: Int, bTo: Int) {
            val tmp = FloatArray(3)
            for (b in bFrom until bTo) {
                val bIn = axis[b]
                var i = b * size * size * 3
                for (g in 0 until size) {
                    val gIn = axis[g]
                    for (r in 0 until size) {
                        val rIn = axis[r]
                        if (strength == 0f) {
                            values[i] = rIn
                            values[i + 1] = gIn
                            values[i + 2] = bIn
                        } else {
                            kernel.apply(rIn, gIn, bIn, values, i)
                            if (overlay != null) {
                                overlay.sample(values[i], values[i + 1], values[i + 2], tmp)
                                values[i] += (tmp[0] - values[i]) * overlayMix
                                values[i + 1] += (tmp[1] - values[i + 1]) * overlayMix
                                values[i + 2] += (tmp[2] - values[i + 2]) * overlayMix
                            }
                            if (strength < 1f) {
                                val a0 = values[i]
                                val a1 = values[i + 1]
                                val a2 = values[i + 2]
                                values[i] = a0 + (rIn - a0) * t
                                values[i + 1] = a1 + (gIn - a1) * t
                                values[i + 2] = a2 + (bIn - a2) * t
                            }
                        }
                        i += 3
                    }
                }
            }
        }

        if (size < PARALLEL_MIN_SIZE || (kernel.isIdentity && overlay == null)) {
            slice(0, size)
        } else {
            val workers = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
            val step = (size + workers - 1) / workers
            val tasks = (0 until size step step).map { from ->
                Callable { slice(from, min(size, from + step)) }
            }
            runCatching { pool.invokeAll(tasks).forEach { it.get() } }
                .onFailure {
                    // 线程池被拒绝（进程退出时）或中断：退回单线程，生成必须得到结果。
                    slice(0, size)
                }
        }
        return CubeLut(size, values)
    }

    /** 从「绝对参数」换算成参考实现的预设值。 */
    fun presetValues(preset: LutPreset): LutParams = preset.params

    /** 参数是否已经"看得见"。 */
    fun paramsWillChangeImage(params: LutParams, strengthPercent: Int): Boolean =
        strengthPercent > 0 && !params.isNeutral()

    /** 与参考实现一致的取整，供测试与日志断言使用。 */
    fun quantize6(v: Float): Int = (v.coerceIn(0f, 1f) * 1_000_000f).roundToInt()

    /** 仅用于诊断：两个参数是否在容差内相等。 */
    fun nearlyEquals(a: Float, b: Float, epsilon: Float = 1e-6f): Boolean = abs(a - b) <= epsilon

    /**
     * 不含过曝保护与裁剪的「裸调色链」，只服务于上面的单算子函数。
     * 与 [LutGradeKernel] 的同名分支逐式一致。
     */
    private class RawChain(
        val bright: Float = 0f,
        val highlight: Float = 0f,
        val shadow: Float = 0f,
        val contrast: Float = 0f,
        val sat: Float = 0f,
        val temp: Float = 0f,
        val tint: Float = 0f,
        val forceTemp: Boolean = false
    ) {
        fun apply(rIn: Float, gIn: Float, bIn: Float, out: FloatArray) {
            var r = rIn
            var g = gIn
            var b = bIn
            if (bright != 0f) {
                val lum = luma(r, g, b)
                val hlT = if (lum > 0.7f) (lum - 0.7f) / (1f - 0.7f) else 0f
                val hlDamp = max(0.3f, 1f - hlT * 0.7f)
                val shDamp = if (lum < 0.18f) 0.35f + (1f - 0.35f) * (lum / 0.18f) else 1f
                var scale = 1f + bright / 50f * 0.67f * hlDamp * shDamp
                if (bright > 0f) {
                    val protectW = ((lum - 0.85f) / (1f - 0.85f)).coerceIn(0f, 1f)
                    scale = scale * (1f - protectW) + protectW
                }
                r *= scale; g *= scale; b *= scale
            }
            if (highlight != 0f || shadow != 0f) {
                val lum = luma(r, g, b)
                if (highlight != 0f) {
                    val w = if (lum > 0.5f) (lum - 0.5f) / 0.5f else 0f
                    val s = 1f + highlight / 50f * 0.7f * w
                    r *= s; g *= s; b *= s
                }
                if (shadow != 0f) {
                    val w = if (lum < 0.5f) (0.5f - lum) / 0.5f else 0f
                    val s = 1f + shadow / 50f * 0.7f * w
                    r *= s; g *= s; b *= s
                }
            }
            if (contrast != 0f) {
                val lum = luma(r, g, b)
                val hlT = if (lum > 0.7f) (lum - 0.7f) / (1f - 0.7f) else 0f
                val hlDamp = max(0.5f, 1f - hlT * 0.5f)
                val scale = 1f + contrast / 50f * 0.57f * hlDamp
                r = 0.5f + scale * (r - 0.5f)
                g = 0.5f + scale * (g - 0.5f)
                b = 0.5f + scale * (b - 0.5f)
            }
            if (sat != 0f) {
                val gray = luma(r, g, b)
                val scale = 1f + sat / 50f * 0.82f
                r = gray + scale * (r - gray)
                g = gray + scale * (g - gray)
                b = gray + scale * (b - gray)
            }
            if (forceTemp || temp != 0f || tint != 0f) {
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
                    rGain *= scale; gGain *= scale; bGain *= scale
                }
                val lum = luma(r, g, b)
                val hlWeight = if (lum > 0.70f) ((lum - 0.70f) / (1f - 0.70f)).coerceIn(0f, 1f) else 0f
                val hlDamp = 1f - hlWeight * 0.95f
                r *= 1f + (rGain - 1f) * hlDamp
                g *= 1f + (gGain - 1f) * hlDamp
                b *= 1f + (bGain - 1f) * hlDamp
            }
            out[0] = r
            out[1] = g
            out[2] = b
        }
    }
}
