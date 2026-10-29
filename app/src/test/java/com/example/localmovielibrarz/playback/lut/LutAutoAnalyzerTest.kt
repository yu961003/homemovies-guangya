package com.example.localmovielibrarz.playback.lut

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 「自动分析调参」三段（肤色提取 → 搜参 → 串起来）的测试。
 *
 * 这一层能证伪的东西比画面更硬：
 * - 没肤色时必须退回标准模板，而不是给一组随机数（否则「分析」等于给画面乱调色）；
 * - 三组典型肤色的搜参结果与参考实现逐参数相等——移植过程中最容易出现的是
 *   「某个环节少了一层夹取」或「顺序换了」，前者让参数越界，后者让结果差几档，
 *   两者在真机上只表现为「调完颜色有点怪」，靠肉眼根本追不出来；
 * - 肤色提取的判据（R/G 那条）一旦写松，木地板、墙壁都会被当成皮肤，参数会被
 *   整个背景带偏。
 *
 * golden 值来自 `work/autolutspec/`：用参考实现的 `predict_params` 在同样的
 * float32 肤色数组上跑一遍，取它输出。两边能对上才说明移植是同一条算法，
 * 而不只是「跑得通」。
 */
class LutAutoAnalyzerTest {

    // ───────────────────────── 搜参：无肤色的兜底 ─────────────────────────

    @Test
    fun noSkinFallsBackToStandardTemplate() {
        val prediction = LutParamPredictor.predict(null)

        // 参考实现：skin_rgb 为空 → return dict(JY_TEMPLATE)。
        assertEquals(LutAnalysisMode.Standard, prediction.mode)
        assertEquals(LutParams(-20, 6, 0, 0, 0, -2, 2), prediction.params)
    }

    // ───────────────────────── 搜参：三组 golden ─────────────────────────

    @Test
    fun warmSkinMatchesReferenceParams() {
        val features = sample(
            0.62f, 0.45f, 0.36f,
            0.66f, 0.48f, 0.38f,
            0.58f, 0.42f, 0.34f,
            0.70f, 0.52f, 0.41f
        )

        val prediction = LutParamPredictor.predict(features)

        assertEquals(LutAnalysisMode.Standard, prediction.mode)
        assertEquals(
            "参考实现 predict_params 对同一组肤色输出的 7 个参数",
            LutParams(-28, 5, 0, 16, 2, -7, 2),
            prediction.params
        )
    }

    @Test
    fun overRedSkinMatchesReferenceParams() {
        val features = sample(
            0.85f, 0.42f, 0.30f,
            0.90f, 0.45f, 0.32f,
            0.80f, 0.40f, 0.28f,
            0.88f, 0.44f, 0.31f
        )

        val prediction = LutParamPredictor.predict(features)

        // R/B 已经很高（≈2.6），色温要往下压得更狠。
        assertEquals(LutParams(-40, 3, -2, 18, 2, -7, 2), prediction.params)
    }

    @Test
    fun darkSkinMatchesReferenceParamsInStandardMode() {
        val features = darkSkin()

        // 参考实现的 analyze_and_apply 调 predict_params 时不带 use_extreme（默认 false），
        // 也就是永远从标准模板起步。这里显式指定 Standard，才能与 oracle 逐参数对上。
        val prediction = LutParamPredictor.predict(features, LutAnalysisMode.Standard)

        // 暗部样本除了提亮，还要抬阴影——这是最容易漏掉的一维。
        assertEquals(LutParams(-31, 3, -1, 18, 6, -7, 12), prediction.params)
    }

    @Test
    fun darkSkinAutoSelectsExtremeDarkTemplate() {
        val features = darkSkin()

        // 自动模式下极暗肤色应当命中极暗模板，并且比标准模板提得更亮：
        // 参考实现把这个开关留给调用方，本工程把它接到 selectMode 上。
        val auto = LutParamPredictor.predict(features)

        assertEquals(LutAnalysisMode.ExtremeDark, auto.mode)
        assertTrue(
            "极暗模板的亮度应高于标准模板：${auto.params.brightness}",
            auto.params.brightness > 20
        )
    }

    @Test
    fun predictedParamsAlwaysWithinLimits() {
        val samples = listOf(
            sample(0.62f, 0.45f, 0.36f),
            sample(0.95f, 0.40f, 0.20f),
            sample(0.15f, 0.12f, 0.10f),
            sample(0.50f, 0.45f, 0.40f)
        )

        for (features in samples) {
            val params = LutParamPredictor.predict(features).params
            val values = with(params) {
                listOf(
                    temperature, tint, saturation, brightness, contrast, highlight, shadow
                )
            }
            for (v in values) {
                assertTrue(
                    "参数 $v 越界（${LutParams.MIN}..${LutParams.MAX}），写进 LutParams 会直接抛异常",
                    v in LutParams.MIN..LutParams.MAX
                )
            }
        }
    }

    @Test
    fun forcedModeOverridesAutomaticSelection() {
        val features = sample(0.62f, 0.45f, 0.36f)

        val auto = LutParamPredictor.predict(features)
        val forced = LutParamPredictor.predict(features, LutAnalysisMode.ExtremeDark)

        assertEquals(LutAnalysisMode.Standard, auto.mode)
        assertEquals(LutAnalysisMode.ExtremeDark, forced.mode)
        // 极暗模板会大幅提亮，与标准模板必然不同。
        assertTrue(
            "强制极暗模板后亮度应高于标准模板：${forced.params.brightness} vs ${auto.params.brightness}",
            forced.params.brightness > auto.params.brightness
        )
    }

    // ───────────────────────── 预演：中性参数不动肤色 ─────────────────────────

    @Test
    fun neutralParamsLeaveSkinUnchanged() {
        val skin = floatArrayOf(
            0.62f, 0.45f, 0.36f,
            0.66f, 0.48f, 0.38f,
            0.58f, 0.42f, 0.34f
        )

        val out = LutParamPredictor.simulateOnSkin(skin, LutParams())

        assertEquals(skin.size, out.size)
        for (i in skin.indices) {
            assertTrue(
                "中性参数不该改变肤色像素[$i]：${skin[i]} → ${out[i]}",
                abs(skin[i] - out[i]) <= 1e-6f
            )
        }
    }

    // ───────────────────────── 肤色提取 ─────────────────────────

    @Test
    fun uniformSkinFrameYieldsFeatures() {
        val features = LutSkinAnalyzer.analyze(uniformFrame(400, 400, 0xC8, 0xA5, 0x78), 400, 400)

        assertNotNull("纯肤色画面应当提取到肤色", features)
        val f = features!!
        // 整帧同色 → R/B 就是通道比本身（逐像素是 float32 除法，故留 1e-6 余量）。
        assertEquals(200.0 / 120.0, f.skinRb, 1e-6)
        assertEquals(200.0 / 165.0, f.skinRg, 1e-6)
        assertTrue("应当达到最小肤色像素数", f.skinPixelCount >= LutSkinAnalyzer.MIN_SKIN_PIXELS)
    }

    @Test
    fun grayFrameYieldsNoSkin() {
        // 灰度像素 R/B = 1.0，落在 SKIN_MIN_RB(1.05) 之外——判据写松这里立刻会红。
        assertNull(LutSkinAnalyzer.analyze(uniformFrame(400, 400, 128, 128, 128), 400, 400))
    }

    @Test(expected = IllegalArgumentException::class)
    fun mismatchedPixelCountIsRejected() {
        // 少一个像素：这种错在调用方（Bitmap.getPixels 参数写错）最容易发生，
        // 必须当场炸掉而不是越界读到脏数据。
        LutSkinAnalyzer.analyze(IntArray(400 * 400 - 1), 400, 400)
    }

    // ───────────────────────── 串起来 ─────────────────────────

    @Test
    fun analyzeFeaturesBuildsPreviewFromPredictedParams() {
        val features = sample(0.62f, 0.45f, 0.36f, 0.66f, 0.48f, 0.38f)

        val analysis = LutAutoAnalyzer.analyzeFeatures(features)

        assertTrue(analysis.hasSkin)
        // 结果要等于「直接调 predictor」——中间那层不能自己改参数。
        assertEquals(LutParamPredictor.predict(features).params, analysis.prediction.params)
        val expected = LutSkinStats.sample(
            LutParamPredictor.simulateOnSkin(features.skinRgb, analysis.prediction.params)
        )
        assertNotNull(analysis.preview)
        assertEquals(expected.skinRb, analysis.preview!!.skinRb, 1e-9)
        assertEquals(expected.skinLum, analysis.preview!!.skinLum, 1e-9)
        assertTrue("结论里应当带上模板名", analysis.message.contains(analysis.prediction.mode.label))
    }

    @Test
    fun analyzeWithoutSkinExplainsWhyNothingHappened() {
        val analysis = LutAutoAnalyzer.analyze(uniformFrame(64, 64, 128, 128, 128), 64, 64)

        assertNull(analysis.features)
        assertNull("没肤色就没有可预演的对象", analysis.preview)
        assertEquals(LutParams(-20, 6, 0, 0, 0, -2, 2), analysis.prediction.params)
        assertTrue(
            "没肤色时要说清原因，而不是静默失败：${analysis.message}",
            analysis.message.contains("肤色")
        )
    }

    // ───────────────────────── 工具 ─────────────────────────

    /** 用肤色数组构造特征：走与运行时同一个统计函数，避免两套算法各自漂移。 */
    private fun sample(vararg rgb: Float): LutSkinFeatures = LutSkinStats.sample(rgb)

    /** 参考实现里 is_extreme_dark 命中的那组样本（skin_lum ≈ 0.177）。 */
    private fun darkSkin(): LutSkinFeatures = sample(
        0.22f, 0.16f, 0.13f,
        0.24f, 0.17f, 0.14f,
        0.20f, 0.15f, 0.12f,
        0.26f, 0.18f, 0.15f
    )

    private fun uniformFrame(w: Int, h: Int, r: Int, g: Int, b: Int): IntArray =
        IntArray(w * h) { (0xFF shl 24) or (r shl 16) or (g shl 8) or b }
}
