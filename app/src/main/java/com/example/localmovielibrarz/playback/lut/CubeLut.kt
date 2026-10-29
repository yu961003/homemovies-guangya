package com.example.localmovielibrarz.playback.lut

import java.io.File

/**
 * `.cube` 3D LUT 的内存表示与读写。
 *
 * 为什么自己写而不用现成库：mpv 只认 `.cube` 文本格式，而 App 侧要做的只有
 * 「按参数生成一张」和「读用户导入的那张」两件事，格式本身（`LUT_3D_SIZE` + 若干行
 * 三元组）简单到没有引入依赖的必要；多一个依赖就多一个 ABI 风险（本工程已为
 * `libmpv.so` 的 ABI 吃过一次苦头）。
 *
 * ## 数据排列
 *
 * 规范规定：**红色索引变化最快**，即
 * ```
 * for b in 0 until size: for g in 0 until size: for r in 0 until size: 追加一行
 * ```
 * 这与参考实现 `mpv_realtime_lut.py:1452-1464` 完全一致。读写双方都遵守它，
 * 所以中间不需要重排——重排是这类代码最常见的错位来源（红蓝对调、画面发青）。
 *
 * @property size 每边采样数（`.cube` 的 `LUT_3D_SIZE`）。mpv 的 `--target-lut` 接受任意
 *   1..256 的值；App 侧只暴露 17 / 33 / 65 三档（见 [CubeLut.Sizes]）。
 * @property values 展平后的三元组，`values.size == size*size*size*3`，取值范围 0f..1f。
 */
class CubeLut(
    val size: Int,
    val values: FloatArray
) {

    init {
        require(size in MIN_SIZE..MAX_SIZE) { "LUT_3D_SIZE 必须在 $MIN_SIZE..$MAX_SIZE 之间，实际 $size" }
        require(values.size == size * size * size * 3) {
            "LUT 数据长度不匹配：size=$size 需要 ${size * size * size * 3} 个浮点，实际 ${values.size}"
        }
    }

    /** 第 (r,g,b) 个格点的输出颜色，各分量 0f..1f。 */
    fun at(r: Int, g: Int, b: Int): Triple<Float, Float, Float> {
        val i = ((b * size + g) * size + r) * 3
        return Triple(values[i], values[i + 1], values[i + 2])
    }

    /**
     * 与另一张同尺寸 LUT 做线性插值，`t=0` 得 [this]，`t=1` 得 [other]。
     *
     * 用途：本项目的「强度」滑杆。mpv 的 `--target-lut` **只接受一个文件**，没有强度参数；
     * 而参考实现的 7 个参数是绝对值、没有强度概念。要在「开了 LUT 但只想微调」的场景里
     * 给出可调力度，唯一的做法是在**生成阶段**把结果与单位 LUT 混合后再写出文件——
     * 这样 mpv 侧仍然是「加载一张 .cube」，不需要任何 mpv 特性支持。
     *
     * @param t 0f..1f，超出范围会被夹取。
     */
    fun blendTowards(other: CubeLut, t: Float): CubeLut {
        require(other.size == size) { "混合要求两张 LUT 尺寸一致：$size vs ${other.size}" }
        val k = t.coerceIn(0f, 1f)
        if (k == 0f) return this
        if (k == 1f) return other
        val out = FloatArray(values.size)
        for (i in values.indices) {
            val a = values[i]
            out[i] = a + (other.values[i] - a) * k
        }
        return CubeLut(size, out)
    }

    /**
     * 身份 LUT：输出等于输入。
     *
     * 用来当「强度 0%」的基准。注意它的关键性质是**格点顺序必须与 [CubeLut] 一致**，
     * 否则混合出来的图会带着一种奇怪的颜色旋转——这类 bug 在截图里很难看出根因。
     */
    fun identity(): CubeLut {
        val out = FloatArray(values.size)
        val denom = (size - 1).toFloat()
        var i = 0
        for (b in 0 until size) {
            for (g in 0 until size) {
                for (r in 0 until size) {
                    out[i] = r / denom
                    out[i + 1] = g / denom
                    out[i + 2] = b / denom
                    i += 3
                }
            }
        }
        return CubeLut(size, out)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 写
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 序列化成 `.cube` 文本。
     *
     * 只写 `LUT_3D_SIZE` 与数据两段：`TITLE` / `DOMAIN_MIN` / `DOMAIN_MAX` 都是可选的，
     * 缺省值（0..1 定义域）正是我们要的。少写几行也少一处与 mpv 解析器对不上的机会。
     */
    fun toCubeText(): String {
        val sb = StringBuilder(64 + size * size * size * 26)
        sb.append("LUT_3D_SIZE ").append(size).append('\n')
        var i = 0
        while (i < values.size) {
            appendTrimmed(sb, values[i])
            sb.append(' ')
            appendTrimmed(sb, values[i + 1])
            sb.append(' ')
            appendTrimmed(sb, values[i + 2])
            sb.append('\n')
            i += 3
        }
        return sb.toString()
    }

    /**
     * 写文件。内容不变时不重写，返回 `false`——调用方据此跳过 `target-lut` 的重新下发，
     * 避免每次滑杆微动都让 mpv 重新加载 LUT（那会让画面闪一下）。
     */
    fun writeTo(file: File): Boolean {
        val text = toCubeText()
        if (file.isFile) {
            val existing = runCatching { file.readText() }.getOrNull()
            if (existing == text) return false
        }
        file.parentFile?.mkdirs()
        file.writeText(text)
        return true
    }

    private fun appendTrimmed(sb: StringBuilder, v: Float) {
        // 6 位小数与参考实现一致（`f"{r:.6f}"`）。用 String.format 而不是 toString：
        // 后者对 0f 会给 "0.0"，对极小值会给科学计数法（"1.0E-5"），mpv 的解析器不认。
        val scaled = Math.round(v.coerceIn(0f, 1f) * 1_000_000f)
        val intPart = scaled / 1_000_000L
        val fracPart = scaled % 1_000_000L
        sb.append(intPart)
        if (fracPart != 0L) {
            sb.append('.')
            val frac = fracPart.toString().padStart(6, '0').trimEnd('0')
            sb.append(frac)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 读
    // ─────────────────────────────────────────────────────────────────────────

    companion object {

        const val MIN_SIZE = 2
        const val MAX_SIZE = 256

        /** App 侧暴露的三档。33 是参考实现的默认值，也是画质/体积的常用折中。 */
        val Sizes = intArrayOf(17, 33, 65)

        const val DEFAULT_SIZE = 33

        private const val KEYWORD_SIZE = "LUT_3D_SIZE"
        private const val KEYWORD_1D_SIZE = "LUT_1D_SIZE"
        private const val KEYWORD_TITLE = "TITLE"
        private const val KEYWORD_DOMAIN_MIN = "DOMAIN_MIN"
        private const val KEYWORD_DOMAIN_MAX = "DOMAIN_MAX"

        /**
         * 解析 `.cube` 文本。用于「用户导入自己收藏的 LUT」这条路径。
         *
         * 只支持 3D LUT：1D LUT 在 mpv 侧走的是另一套语义（`--target-lut` 也能吃，但用户
         * 从调色软件导出的绝大多数是 3D），这里遇到 1D 会明确报错而不是猜。
         * `DOMAIN_MIN` / `DOMAIN_MAX` 非默认值时同样报错——我们的强度混合假定 0..1 定义域，
         * 静默接受会让混合结果错得没有线索。
         *
         * @throws IllegalArgumentException 格式非法、尺寸越界、数据行数不符。
         */
        fun parse(text: String): CubeLut {
            var size = 0
            val out = ArrayList<Float>(4096)
            var sawDomainOverride = false
            val lines = text.lineSequence()
            for ((index, rawLine) in lines.withIndex()) {
                val line = rawLine.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                when {
                    line.startsWith(KEYWORD_SIZE) -> {
                        size = line.removePrefix(KEYWORD_SIZE).trim().toIntOrNull()
                            ?: throw IllegalArgumentException("第 ${index + 1} 行 LUT_3D_SIZE 不是整数：$line")
                        require(size in MIN_SIZE..MAX_SIZE) {
                            "第 ${index + 1} 行 LUT_3D_SIZE 超出 $MIN_SIZE..$MAX_SIZE：$size"
                        }
                    }

                    line.startsWith(KEYWORD_1D_SIZE) ->
                        throw IllegalArgumentException(
                            "不支持 1D LUT（第 ${index + 1} 行）。本功能只接受 3D LUT（含 LUT_3D_SIZE 的 .cube）"
                        )

                    line.startsWith(KEYWORD_TITLE) -> Unit

                    line.startsWith(KEYWORD_DOMAIN_MIN) -> {
                        if (!isDefaultDomain(line.removePrefix(KEYWORD_DOMAIN_MIN), 0f)) sawDomainOverride = true
                    }

                    line.startsWith(KEYWORD_DOMAIN_MAX) -> {
                        if (!isDefaultDomain(line.removePrefix(KEYWORD_DOMAIN_MAX), 1f)) sawDomainOverride = true
                    }

                    else -> {
                        val parts = line.split(' ', '\t').filter { it.isNotEmpty() }
                        if (parts.size != 3) continue
                        val r = parts[0].toFloatOrNull() ?: continue
                        val g = parts[1].toFloatOrNull() ?: continue
                        val b = parts[2].toFloatOrNull() ?: continue
                        out.add(r)
                        out.add(g)
                        out.add(b)
                    }
                }
            }
            require(size > 0) { "缺少 LUT_3D_SIZE 行，不是有效的 3D .cube 文件" }
            require(!sawDomainOverride) { "不支持自定义 DOMAIN_MIN/DOMAIN_MAX 的 .cube（只接受 0..1 定义域）" }
            val expected = size * size * size * 3
            require(out.size == expected) {
                "数据行数与 LUT_3D_SIZE 不符：期望 ${expected / 3} 行，实际 ${out.size / 3} 行"
            }
            return CubeLut(size, out.toFloatArray())
        }

        private fun isDefaultDomain(rest: String, expected: Float): Boolean {
            val parts = rest.trim().split(' ', '\t').filter { it.isNotEmpty() }
            if (parts.size != 3) return false
            return parts.all { (it.toFloatOrNull() ?: return false) == expected }
        }
    }
}
