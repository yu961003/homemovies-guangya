package com.example.localmovielibrarz.playback.lut

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 实时调色（LUT）纯算法层测试。
 *
 * 这一层的价值在于**不看画面也能证伪**：
 * - 参数为 0 时生成的必须是与单位 LUT 完全一致的恒等表（否则"关掉调色"其实没关掉）；
 * - 强度 0 必须等于恒等、强度 100 必须等于原参数结果（否则滑杆两端语义错位）；
 * - `.cube` 文本的行数必须正好 size³+1（多一行少一行 mpv 会整张拒绝加载）。
 *
 * 这些都是"改一个数字就静默坏掉"的地方：它们不会抛异常，只会在真机上表现为
 * 偏色、或 mpv 日志里一句 target-lut 加载失败。
 */
class LutColorMathTest {

    @Test
    fun neutralParamsProduceIdentityLut() {
        val size = 5
        val lut = LutColorMath.generateLut(size, LutParams.NEUTRAL)
        val last = (size - 1).toFloat()
        for (b in 0 until size) {
            for (g in 0 until size) {
                for (r in 0 until size) {
                    val (or, og, ob) = lut.at(r, g, b)
                    assertClose(r / last, or, "neutral R @($r,$g,$b)")
                    assertClose(g / last, og, "neutral G @($r,$g,$b)")
                    assertClose(b / last, ob, "neutral B @($r,$g,$b)")
                }
            }
        }
    }

    @Test
    fun strengthZeroEqualsIdentityEvenWithStrongParams() {
        val size = 5
        val strong = LutPreset.ExtremeDark.params
        val scaled = LutColorMath.generateScaledLut(size, strong, strengthPercent = 0)
        val identity = identityLut(size)
        for (b in 0 until size) {
            for (g in 0 until size) {
                for (r in 0 until size) {
                    val a = scaled.at(r, g, b)
                    val e = identity.at(r, g, b)
                    assertClose(e.first, a.first, "strength0 R @($r,$g,$b)")
                    assertClose(e.second, a.second, "strength0 G @($r,$g,$b)")
                    assertClose(e.third, a.third, "strength0 B @($r,$g,$b)")
                }
            }
        }
    }

    @Test
    fun strengthHundredEqualsRawParams() {
        val size = 5
        val params = LutPreset.Standard.params
        val scaled = LutColorMath.generateScaledLut(size, params, strengthPercent = 100)
        val raw = LutColorMath.generateLut(size, params)
        for (b in 0 until size) {
            for (g in 0 until size) {
                for (r in 0 until size) {
                    val a = scaled.at(r, g, b)
                    val e = raw.at(r, g, b)
                    assertClose(e.first, a.first, "strength100 R @($r,$g,$b)")
                    assertClose(e.second, a.second, "strength100 G @($r,$g,$b)")
                    assertClose(e.third, a.third, "strength100 B @($r,$g,$b)")
                }
            }
        }
    }

    @Test
    fun halfStrengthLandsBetweenIdentityAndRaw() {
        val size = 5
        val params = LutPreset.ExtremeDark.params
        val identity = identityLut(size)
        val raw = LutColorMath.generateLut(size, params)
        val half = LutColorMath.generateScaledLut(size, params, strengthPercent = 50)
        // 取一个亮度中段的格点：两端都在同一侧，才能验证"落在中间"。
        val mid = size / 2
        val idv = identity.at(mid, mid, mid)
        val rawv = raw.at(mid, mid, mid)
        val hv = half.at(mid, mid, mid)
        listOf(
            Triple(idv.first, rawv.first, hv.first),
            Triple(idv.second, rawv.second, hv.second),
            Triple(idv.third, rawv.third, hv.third)
        ).forEach { (a, b, h) ->
            val lo = minOf(a, b) - 1e-4f
            val hi = maxOf(a, b) + 1e-4f
            assertTrue("half-strength 值 $h 应落在 [$lo, $hi] 之间", h in lo..hi)
        }
    }

    @Test
    fun cubeTextHasHeaderAndExactlyOneLinePerGridPoint() {
        val size = 17
        val text = identityLut(size).toCubeText()
        val lines = text.trimEnd('\n').split('\n')
        assertEquals("LUT_3D_SIZE $size", lines.first())
        assertEquals("数据行数应等于 size³", 1 + size * size * size, lines.size)
        // 每个数据行必须正好三个分量，且不含科学计数法（mpv 解析器不认 1.0E-5）。
        lines.drop(1).forEach { line ->
            val parts = line.split(' ')
            assertEquals("数据行分量数：$line", 3, parts.size)
            parts.forEach { p ->
                assertTrue("分量不该是科学计数法：$p", !p.contains('E') && !p.contains('e'))
            }
        }
    }

    @Test
    fun settingsSignatureChangesWithEveryInputThatAffectsTheImage() {
        val base = LutSettings(enabled = true, lutSize = 33, strengthPercent = 80, params = LutPreset.Standard.params)
        val sig = base.signature()
        assertNotEquals("改色温应改签名", sig, base.withParams(base.params.set(LutParamKey.Temperature, -21)).signature())
        assertNotEquals("改强度应改签名", sig, base.withStrength(79).signature())
        assertNotEquals("改尺寸应改签名", sig, base.withLutSize(65).signature())
        assertEquals("只改开关不该改签名（开关走空路径而非换文件）", sig, base.copy(enabled = false).signature())
    }

    @Test
    fun effectiveFileNameIsNullWhenNothingWouldBeApplied() {
        val neutral = LutSettings(enabled = true, params = LutParams.NEUTRAL, strengthPercent = 80)
        assertNull("参数全 0 时不该下发 LUT", neutral.effectiveFileName())

        val disabled = LutSettings(enabled = false, params = LutPreset.Standard.params, strengthPercent = 80)
        assertNull("关着时不该下发 LUT", disabled.effectiveFileName())

        val zeroStrength = LutSettings(enabled = true, params = LutPreset.Standard.params, strengthPercent = 0)
        assertNull("强度 0 时不该下发 LUT", zeroStrength.effectiveFileName())

        val active = LutSettings(enabled = true, params = LutPreset.Standard.params, strengthPercent = 80)
        val name = active.effectiveFileName()
        assertTrue("生效时应给出 a 前缀的文件名，实际 $name", name != null && name.startsWith(LutSettings.FILE_PREFIX))
    }

    @Test
    fun presetValuesMatchReferenceImplementation() {
        // 数值来自 mpv_realtime_lut.py 的 JY_TEMPLATE / JY_TEMPLATE_HIGH_MATCH / JY_TEMPLATE_EXTREME_DARK。
        val standard = LutPreset.Standard.params
        assertEquals(-20, standard.temperature)
        assertEquals(6, standard.tint)
        assertEquals(-2, standard.highlight)
        assertEquals(2, standard.shadow)

        val highMatch = LutPreset.HighMatch.params
        assertEquals(-3, highMatch.temperature)
        assertEquals(5, highMatch.brightness)

        val dark = LutPreset.ExtremeDark.params
        assertEquals(25, dark.brightness)
        assertEquals(20, dark.shadow)

        assertTrue("「关闭调色」预设必须等于恒等参数", LutPreset.Neutral.params.isNeutral())
    }

    @Test
    fun paramSetClampsInsteadOfThrowing() {
        val out = LutParams.NEUTRAL.set(LutParamKey.Saturation, 9999)
        assertEquals(LutParams.MAX, out.saturation)
        val low = out.set(LutParamKey.Saturation, -9999)
        assertEquals(LutParams.MIN, low.saturation)
    }

    @Test
    fun paramsWillChangeImageRejectsNoOpCombinations() {
        assertTrue(!LutColorMath.paramsWillChangeImage(LutParams.NEUTRAL, 80))
        assertTrue(!LutColorMath.paramsWillChangeImage(LutPreset.Standard.params, 0))
        assertTrue(LutColorMath.paramsWillChangeImage(LutPreset.Standard.params, 80))
    }

    /** `identity()` 是实例方法（它依赖自身 size），测试里需要一个构造入口。 */
    private fun identityLut(size: Int): CubeLut =
        CubeLut(size, FloatArray(size * size * size * 3)).identity()

    private fun assertClose(expected: Float, actual: Float, label: String) {
        assertTrue(
            "$label：期望 $expected，实际 $actual（差 ${abs(expected - actual)}）",
            abs(expected - actual) <= 1e-5f
        )
    }
}
