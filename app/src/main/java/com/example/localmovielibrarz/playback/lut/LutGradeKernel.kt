package com.example.localmovielibrarz.playback.lut

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 调色内核：把「一组参数」预编译成一段无分配的逐像素运算。
 *
 * ## 为什么要有这一层（v2 重构）
 *
 * 旧实现里同一条调色链写了两份：[LutColorMath.applyAll]（生成 LUT，每个算子返回一个
 * `Triple`）和 `LutParamPredictor` 的 `apply*InPlace`（搜参，原地写数组），常量也各抄一份。
 * 两份代码只要有一处系数改漏，「搜参用的算子」就和「最终渲染用的算子」不一致，
 * 自动分析会搜出一组在画面上并不成立的参数。而且旧的生成路径：
 * - 每个格点 6 次 `Triple` 装箱（65³ 时约 165 万个对象，ART 上 GC 抖动明显）；
 * - 色温/色调的 RGB 增益（含一次归一化除法）在**每个格点**上重算，而它只取决于参数。
 *
 * 现在：参数相关的量在构造时算一次，[apply] 只做逐像素必须的运算、零分配；
 * 生成与搜参共用本类，差异只剩一个显式开关 [skinSearchMode]。
 *
 * ## 与旧版逐位一致
 *
 * 参考调色链（亮度 → 高光阴影 → 对比度 → 饱和度 → 色温色调 → 过曝保护 → 裁剪）的每一个
 * 浮点表达式都按原顺序书写（例如 `bright / 50f * 0.67f * hlDamp * shDamp` 拆成
 * 预计算的 `bright / 50f * 0.67f` 再依次乘，结合顺序不变），所以创意参数为中性时，
 * 输出与 v1 的 `applyAll` **逐位相同**——这一点由 `LutKernelEquivalenceTest` 守住。
 *
 * ## 创意层（Oklab）
 *
 * 色相旋转、自然饱和度、分离色调在 Oklab 里做：它的明度与色度近似正交，
 * 旋转色相或拉色度时亮度基本不动（在 RGB/HSV 里转色相会让黄色明显变亮、蓝色变暗）。
 * 进出 Oklab 用 2.2 幂近似视频的显示伽马，对「改色度」这类相对运算足够。
 *
 * @param skinSearchMode 搜参模式：过曝保护对**所有**亮度超过阈值的像素生效（与参考实现
 *   `_apply_params_to_skin` 一致），且不叠加创意层。LUT 生成必须为 false：只压
 *   「原本没过曝、调完才过曝」的格点，否则会把片源自身的高光细节压平。
 */
internal class LutGradeKernel(
    params: LutParams,
    creative: LutCreativeParams = LutCreativeParams.NEUTRAL,
    private val skinSearchMode: Boolean = false
) {

    // ── 参考调色链的预计算量 ───────────────────────────────────────────────
    private val bright = params.brightness
    private val doBright = bright != 0
    private val kBright = bright / 50f * 0.67f

    private val highlight = params.highlight
    private val shadow = params.shadow
    private val doHs = highlight != 0 || shadow != 0
    private val kHighlight = highlight / 50f * 0.7f
    private val kShadow = shadow / 50f * 0.7f

    private val doContrast = params.contrast != 0
    private val kContrast = params.contrast / 50f * 0.57f

    private val doSat = params.saturation != 0
    private val satScale = 1f + params.saturation / 50f * 0.82f

    private val doTemp = params.temperature != 0 || params.tint != 0
    private val rGain: Float
    private val gGain: Float
    private val bGain: Float

    /** v1 的 `if (!params.isNeutral())` —— 创意层非中性时同样需要过曝保护。 */
    private val protectOverexposure = !params.isNeutral() || !creative.isNeutral()

    // ── 创意层预计算量 ─────────────────────────────────────────────────────
    private val creativeActive = !skinSearchMode && !creative.isNeutral()
    private val chromaticActive = creativeActive && creative.hasChromaticOps()
    private val doHue = creative.hue != 0
    private val hueCos: Float
    private val hueSin: Float
    private val vibrance = creative.vibrance / 100f
    private val doSplit = creative.shadowTintAmount != 0 || creative.highlightTintAmount != 0
    private val shadowTintA: Float
    private val shadowTintB: Float
    private val highlightTintA: Float
    private val highlightTintB: Float
    private val splitPivot = 0.5f + 0.25f * (creative.splitBalance / 100f)
    private val fadeLift = creative.fade / 100f * 0.10f
    private val fadeCompress = creative.fade / 100f * 0.06f
    private val skinProtect = if (chromaticActive || doSat || doTemp) creative.skinProtect / 100f else 0f
    private val doSkinProtect = creativeActive && skinProtect > 0f

    /** 肤色保护要算一条「不改色度」的旁路；只在需要时才构造。 */
    private val protectedKernel: LutGradeKernel? =
        if (doSkinProtect) {
            LutGradeKernel(
                params.copy(saturation = 0, temperature = 0, tint = 0),
                LutCreativeParams(fade = creative.fade),
                skinSearchMode = false
            )
        } else {
            null
        }

    init {
        // 与 v1 `applyTempTint` 逐式一致（只是从「每格点一次」挪到「每张 LUT 一次」）。
        val t = params.temperature / 50f
        val ti = params.tint / 50f
        var rg = 1f + t * 0.23f
        var gg = 1f + t * 0.058f
        var bg = 1f - t * 0.23f
        rg *= 1f + ti * 0.045f
        gg *= 1f - ti * 0.09f
        bg *= 1f + ti * 0.045f
        val lumBefore = KR + KG + KB
        val lumAfter = KR * rg + KG * gg + KB * bg
        if (lumAfter > 0.001f) {
            val scale = lumBefore / lumAfter
            rg *= scale
            gg *= scale
            bg *= scale
        }
        rGain = rg
        gGain = gg
        bGain = bg

        val angle = Math.toRadians(creative.hue / 100.0 * MAX_HUE_SHIFT_DEG)
        hueCos = cos(angle).toFloat()
        hueSin = sin(angle).toFloat()

        val sh = tintDirection(creative.shadowTintHue)
        val hl = tintDirection(creative.highlightTintHue)
        val shK = creative.shadowTintAmount / 100f * MAX_TINT_CHROMA
        val hlK = creative.highlightTintAmount / 100f * MAX_TINT_CHROMA
        shadowTintA = sh[0] * shK
        shadowTintB = sh[1] * shK
        highlightTintA = hl[0] * hlK
        highlightTintB = hl[1] * hlK
    }

    /** 本内核是否会改变任何像素（恒等时生成可走捷径）。 */
    val isIdentity: Boolean = !doBright && !doHs && !doContrast && !doSat && !doTemp && !creativeActive

    /**
     * 处理一个像素，结果写入 `out[o..o+2]`。零分配，可被多线程并发调用（内核不可变）。
     */
    fun apply(rIn: Float, gIn: Float, bIn: Float, out: FloatArray, o: Int) {
        val lumBefore = KR * rIn + KG * gIn + KB * bIn
        var r = rIn
        var g = gIn
        var b = bIn

        if (doBright) {
            val lum = KR * r + KG * g + KB * b
            val hlT = if (lum > HL_BRIGHT_DAMP_START) (lum - HL_BRIGHT_DAMP_START) / (1f - HL_BRIGHT_DAMP_START) else 0f
            val hlDamp = max(HL_BRIGHT_DAMP_MIN, 1f - hlT * HL_BRIGHT_DAMP_STRENGTH)
            val shDamp = if (lum < SHADOW_START) SHADOW_MIN + (1f - SHADOW_MIN) * (lum / SHADOW_START) else 1f
            var scale = 1f + kBright * hlDamp * shDamp
            if (bright > 0) {
                val protectW = ((lum - VERY_HIGH) / (1f - VERY_HIGH)).coerceIn(0f, 1f)
                scale = scale * (1f - protectW) + protectW
            }
            r *= scale
            g *= scale
            b *= scale
        }

        if (doHs) {
            val lum = KR * r + KG * g + KB * b
            if (highlight != 0) {
                val w = if (lum > 0.5f) (lum - 0.5f) / 0.5f else 0f
                val s = 1f + kHighlight * w
                r *= s
                g *= s
                b *= s
            }
            if (shadow != 0) {
                val w = if (lum < 0.5f) (0.5f - lum) / 0.5f else 0f
                val s = 1f + kShadow * w
                r *= s
                g *= s
                b *= s
            }
        }

        if (doContrast) {
            val lum = KR * r + KG * g + KB * b
            val hlT = if (lum > HL_BRIGHT_DAMP_START) (lum - HL_BRIGHT_DAMP_START) / (1f - HL_BRIGHT_DAMP_START) else 0f
            val hlDamp = max(HL_CONTRAST_DAMP_MIN, 1f - hlT * HL_CONTRAST_DAMP_STRENGTH)
            val scale = 1f + kContrast * hlDamp
            r = 0.5f + scale * (r - 0.5f)
            g = 0.5f + scale * (g - 0.5f)
            b = 0.5f + scale * (b - 0.5f)
        }

        if (doSat) {
            val gray = KR * r + KG * g + KB * b
            r = gray + satScale * (r - gray)
            g = gray + satScale * (g - gray)
            b = gray + satScale * (b - gray)
        }

        if (doTemp) {
            val lum = KR * r + KG * g + KB * b
            val hlWeight = if (lum > TEMP_HL_START) ((lum - TEMP_HL_START) / (1f - TEMP_HL_START)).coerceIn(0f, 1f) else 0f
            val hlDamp = 1f - hlWeight * TEMP_HL_PROTECT
            r *= 1f + (rGain - 1f) * hlDamp
            g *= 1f + (gGain - 1f) * hlDamp
            b *= 1f + (bGain - 1f) * hlDamp
        }

        if (skinSearchMode) {
            // 参考实现 `_apply_params_to_skin`：无条件过曝保护 + 裁剪。
            val lum = KR * r + KG * g + KB * b
            if (lum > OVEREXPOSE_LIMIT) {
                val scale = OVEREXPOSE_LIMIT / max(lum, 1e-6f)
                r *= scale
                g *= scale
                b *= scale
            }
            out[o] = r.coerceIn(0f, 1f)
            out[o + 1] = g.coerceIn(0f, 1f)
            out[o + 2] = b.coerceIn(0f, 1f)
            return
        }

        if (creativeActive) {
            out[o] = r
            out[o + 1] = g
            out[o + 2] = b
            applyCreative(out, o)
            r = out[o]
            g = out[o + 1]
            b = out[o + 2]
        }

        if (protectOverexposure) {
            val lumAfter = KR * r + KG * g + KB * b
            if (lumBefore <= OVEREXPOSE_LIMIT && lumAfter > OVEREXPOSE_LIMIT) {
                val scale = OVEREXPOSE_LIMIT / max(lumAfter, 1e-6f)
                r *= scale
                g *= scale
                b *= scale
            }
        }

        if (creativeActive) {
            softClip(r, g, b, out, o)
        } else {
            out[o] = r.coerceIn(0f, 1f)
            out[o + 1] = g.coerceIn(0f, 1f)
            out[o + 2] = b.coerceIn(0f, 1f)
        }

        val protectedPass = protectedKernel
        if (protectedPass != null) {
            val w = skinWeight(rIn, gIn, bIn) * skinProtect
            if (w > 0f) {
                val pr = out[o]
                val pg = out[o + 1]
                val pb = out[o + 2]
                protectedPass.apply(rIn, gIn, bIn, out, o)
                out[o] = pr + (out[o] - pr) * w
                out[o + 1] = pg + (out[o + 1] - pg) * w
                out[o + 2] = pb + (out[o + 2] - pb) * w
            }
        }
    }

    /** 原地版本：`buf[o..o+2]` 既是输入也是输出（搜参热循环用）。 */
    fun applyInPlace(buf: FloatArray, o: Int) = apply(buf[o], buf[o + 1], buf[o + 2], buf, o)

    // ─────────────────────────────────────────────────────────────────────────
    // 创意层
    // ─────────────────────────────────────────────────────────────────────────

    private fun applyCreative(buf: FloatArray, o: Int) {
        if (chromaticActive) {
            // 伽马域 → 线性 → Oklab
            val lr = toLinear(buf[o])
            val lg = toLinear(buf[o + 1])
            val lb = toLinear(buf[o + 2])
            val l = cbrt(0.4122214708f * lr + 0.5363325363f * lg + 0.0514459929f * lb)
            val m = cbrt(0.2119034982f * lr + 0.6806995451f * lg + 0.1073969566f * lb)
            val s = cbrt(0.0883024619f * lr + 0.2817188376f * lg + 0.6299787005f * lb)
            val okL = 0.2104542553f * l + 0.7936177850f * m - 0.0040720468f * s
            var okA = 1.9779984951f * l - 2.4285922050f * m + 0.4505937099f * s
            var okB = 0.0259040371f * l + 0.7827717662f * m - 0.8086757660f * s

            if (doHue) {
                val na = okA * hueCos - okB * hueSin
                val nb = okA * hueSin + okB * hueCos
                okA = na
                okB = nb
            }

            if (vibrance != 0f) {
                val chroma = sqrt(okA * okA + okB * okB)
                val k = if (vibrance > 0f) {
                    // 低饱和区域加得多、已饱和区域几乎不加；0.22 约等于 sRGB 纯色的色度量级。
                    vibrance * VIBRANCE_GAIN * (1f - smoothstep(0f, VIBRANCE_KNEE, chroma))
                } else {
                    vibrance * 0.85f
                }
                val f = (1f + k).coerceAtLeast(0f)
                okA *= f
                okB *= f
            }

            if (doSplit) {
                val lumT = okL.coerceIn(0f, 1f)
                val ws = if (lumT < splitPivot) smoothstep(0f, 1f, (splitPivot - lumT) / splitPivot) else 0f
                val wh = if (lumT > splitPivot) smoothstep(0f, 1f, (lumT - splitPivot) / (1f - splitPivot)) else 0f
                okA += shadowTintA * ws + highlightTintA * wh
                okB += shadowTintB * ws + highlightTintB * wh
            }

            // Oklab → 线性 → 伽马域
            val l2 = okL + 0.3963377774f * okA + 0.2158037573f * okB
            val m2 = okL - 0.1055613458f * okA - 0.0638541728f * okB
            val s2 = okL - 0.0894841775f * okA - 1.2914855480f * okB
            val l3 = l2 * l2 * l2
            val m3 = m2 * m2 * m2
            val s3 = s2 * s2 * s2
            buf[o] = toGamma(4.0767416621f * l3 - 3.3077115913f * m3 + 0.2309699292f * s3)
            buf[o + 1] = toGamma(-1.2684380046f * l3 + 2.6097574011f * m3 - 0.3413193965f * s3)
            buf[o + 2] = toGamma(-0.0041960863f * l3 - 0.7034186147f * m3 + 1.7076147010f * s3)
        }
        if (fadeLift > 0f || fadeCompress > 0f) {
            val span = 1f - fadeLift - fadeCompress
            buf[o] = fadeLift + buf[o] * span
            buf[o + 1] = fadeLift + buf[o + 1] * span
            buf[o + 2] = fadeLift + buf[o + 2] * span
        }
    }

    /**
     * 保色相的软裁剪：越界时沿「像素 → 同亮度灰点」方向收色度，而不是逐通道硬切。
     * 硬切会改变通道比例（饱和橙被切成黄），这是 RGB 调色器最常见的偏色来源。
     */
    private fun softClip(r: Float, g: Float, b: Float, out: FloatArray, o: Int) {
        val lum = (KR * r + KG * g + KB * b).coerceIn(0f, 1f)
        var t = 1f
        t = min(t, clipFactor(r, lum))
        t = min(t, clipFactor(g, lum))
        t = min(t, clipFactor(b, lum))
        out[o] = (lum + (r - lum) * t).coerceIn(0f, 1f)
        out[o + 1] = (lum + (g - lum) * t).coerceIn(0f, 1f)
        out[o + 2] = (lum + (b - lum) * t).coerceIn(0f, 1f)
    }

    private fun clipFactor(c: Float, lum: Float): Float {
        val d = c - lum
        return when {
            c > 1f && d > 1e-6f -> ((1f - lum) / d).coerceIn(0f, 1f)
            c < 0f && d < -1e-6f -> (lum / -d).coerceIn(0f, 1f)
            else -> 1f
        }
    }

    companion object {
        // Rec.709 亮度系数（参考实现 mpv_realtime_lut.py:118-124）。
        const val KR = 0.2126f
        const val KG = 0.7152f
        const val KB = 0.0722f

        const val OVEREXPOSE_LIMIT = 0.92f
        const val HL_BRIGHT_DAMP_START = 0.7f
        const val HL_BRIGHT_DAMP_STRENGTH = 0.7f
        const val HL_BRIGHT_DAMP_MIN = 0.3f
        const val HL_CONTRAST_DAMP_STRENGTH = 0.5f
        const val HL_CONTRAST_DAMP_MIN = 0.5f
        const val TEMP_HL_START = 0.70f
        const val TEMP_HL_PROTECT = 0.95f
        const val SHADOW_START = 0.18f
        const val SHADOW_MIN = 0.35f
        const val VERY_HIGH = 0.85f

        private const val MAX_HUE_SHIFT_DEG = 30.0
        private const val MAX_TINT_CHROMA = 0.07f
        private const val VIBRANCE_GAIN = 0.9f
        private const val VIBRANCE_KNEE = 0.22f
        private const val GAMMA = 2.2

        private fun smoothstep(e0: Float, e1: Float, x: Float): Float {
            val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }

        private fun toLinear(v: Float): Float =
            if (v >= 0f) v.toDouble().pow(GAMMA).toFloat() else -(-v).toDouble().pow(GAMMA).toFloat()

        private fun toGamma(v: Float): Float =
            if (v >= 0f) v.toDouble().pow(1.0 / GAMMA).toFloat() else -(-v).toDouble().pow(1.0 / GAMMA).toFloat()

        /**
         * 色轮角度（HSV 语义：0 红 / 120 绿 / 240 蓝）→ Oklab 的单位色度方向。
         *
         * 用户在色轮上选的是「感知上的那个颜色」，而 Oklab 的色相角与 HSV 不同
         * （例如 HSV 红 0° 在 Oklab 约 29°）。这里直接把该色相的满饱和颜色转进 Oklab
         * 取方向，保证「选橙色就染橙色」。
         */
        internal fun tintDirection(hueDeg: Int): FloatArray {
            val h = ((hueDeg % 360) + 360) % 360 / 60f
            val x = 1f - abs(h % 2f - 1f)
            val (r, g, b) = when (h.toInt()) {
                0 -> Triple(1f, x, 0f)
                1 -> Triple(x, 1f, 0f)
                2 -> Triple(0f, 1f, x)
                3 -> Triple(0f, x, 1f)
                4 -> Triple(x, 0f, 1f)
                else -> Triple(1f, 0f, x)
            }
            val lr = toLinear(r)
            val lg = toLinear(g)
            val lb = toLinear(b)
            val l = cbrt(0.4122214708f * lr + 0.5363325363f * lg + 0.0514459929f * lb)
            val m = cbrt(0.2119034982f * lr + 0.6806995451f * lg + 0.1073969566f * lb)
            val s = cbrt(0.0883024619f * lr + 0.2817188376f * lg + 0.6299787005f * lb)
            val a = 1.9779984951f * l - 2.4285922050f * m + 0.4505937099f * s
            val bb = 0.0259040371f * l + 0.7827717662f * m - 0.8086757660f * s
            val len = sqrt(a * a + bb * bb).coerceAtLeast(1e-6f)
            return floatArrayOf(a / len, bb / len)
        }

        /**
         * 肤色权重（0..1），按**原始输入**判定：调色前就是皮肤的格点才受保护。
         *
         * 判据是 HSV 色相 5°..50°（偏红到偏橙黄）、饱和度 0.12..0.75、明度 > 0.12，
         * 边缘用平滑过渡，避免在肤色边界上出现可见的「保护断层」。
         */
        internal fun skinWeight(r: Float, g: Float, b: Float): Float {
            val mx = max(r, max(g, b))
            val mn = min(r, min(g, b))
            if (mx < 0.12f) return 0f
            val d = mx - mn
            if (d <= 1e-6f) return 0f
            val sat = d / mx
            if (mx != r) return 0f // 肤色一定是红通道最大
            var hue = 60f * ((g - b) / d)
            if (hue < 0f) hue += 360f
            if (hue > 60f) return 0f
            val hueW = smoothstep(0f, 5f, hue) * (1f - smoothstep(42f, 55f, hue))
            val satW = smoothstep(0.08f, 0.16f, sat) * (1f - smoothstep(0.68f, 0.80f, sat))
            val valW = smoothstep(0.10f, 0.20f, mx)
            return hueW * satW * valW
        }

        /** 用于诊断与 UI：当前亮度系数下的亮度。 */
        fun luma(r: Float, g: Float, b: Float): Float = KR * r + KG * g + KB * b

        @Suppress("unused")
        private fun hueAngle(a: Float, b: Float): Float = atan2(b, a)
    }
}
