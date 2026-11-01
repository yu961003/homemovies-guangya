package com.example.localmovielibrarz.playback.lut

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import kotlin.math.floor

/**
 * `.cube` 3D LUT 的内存表示与读写。
 *
 * 为什么自己写而不用现成库：mpv 只认 `.cube` 文本格式，而 App 侧要做的只有
 * 「按参数生成一张」和「读用户导入的那张」两件事，格式本身（`LUT_3D_SIZE` + 若干行
 * 三元组）简单到没有引入依赖的必要；多一个依赖就多一个 ABI 风险。
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
 * ## v2 性能改动
 *
 * | 路径 | v1 | v2 |
 * |---|---|---|
 * | 序列化 | `StringBuilder` → `String` → `writeText` 再编码一遍 | 直接按 ASCII 写进字节缓冲，零中间字符串 |
 * | 去重写盘 | `writeTo` 先把旧文件整份读回来再比较字符串 | 按签名命名，文件存在即复用（见 [LutFileWriter]） |
 * | 解析 | `split` + `ArrayList<Float>` 装箱，65³ 要 80 万次装箱 | 逐字节扫描 + 预分配 `FloatArray` |
 * | 采样 | 无 | [sample] 四面体插值，供「导入风格 LUT 叠加」使用 |
 *
 * @property size 每边采样数（`.cube` 的 `LUT_3D_SIZE`）。
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
     * 四面体插值采样（零分配，结果写入 `out[0..2]`）。
     *
     * 为什么不用三线性：三线性在每个立方体里用 8 个角点，会沿灰轴产生轻微的色偏
     * （灰色输入出来带一点颜色）；四面体只用包含该点的那个四面体的 4 个角点，
     * 灰轴恰好是所有四面体的公共边，因此**中性灰保持中性**——这也是调色软件
     * （Resolve / OCIO）的默认插值方式。
     */
    fun sample(r: Float, g: Float, b: Float, out: FloatArray) {
        val n1 = size - 1
        val fr = r.coerceIn(0f, 1f) * n1
        val fg = g.coerceIn(0f, 1f) * n1
        val fb = b.coerceIn(0f, 1f) * n1
        val r0 = floor(fr).toInt().coerceAtMost(n1 - 1).coerceAtLeast(0)
        val g0 = floor(fg).toInt().coerceAtMost(n1 - 1).coerceAtLeast(0)
        val b0 = floor(fb).toInt().coerceAtMost(n1 - 1).coerceAtLeast(0)
        val dr = fr - r0
        val dg = fg - g0
        val db = fb - b0

        val sr = 3
        val sg = size * 3
        val sb = size * size * 3
        val c000 = ((b0 * size + g0) * size + r0) * 3
        val c111 = c000 + sr + sg + sb

        for (ch in 0 until 3) {
            val v000 = values[c000 + ch]
            val v111 = values[c111 + ch]
            out[ch] = if (dr >= dg) {
                if (dg >= db) {
                    // r >= g >= b
                    val v100 = values[c000 + sr + ch]
                    val v110 = values[c000 + sr + sg + ch]
                    v000 + (v100 - v000) * dr + (v110 - v100) * dg + (v111 - v110) * db
                } else if (dr >= db) {
                    // r >= b > g
                    val v100 = values[c000 + sr + ch]
                    val v101 = values[c000 + sr + sb + ch]
                    v000 + (v100 - v000) * dr + (v101 - v100) * db + (v111 - v101) * dg
                } else {
                    // b > r >= g
                    val v001 = values[c000 + sb + ch]
                    val v101 = values[c000 + sr + sb + ch]
                    v000 + (v001 - v000) * db + (v101 - v001) * dr + (v111 - v101) * dg
                }
            } else {
                if (db >= dg) {
                    // b >= g > r
                    val v001 = values[c000 + sb + ch]
                    val v011 = values[c000 + sg + sb + ch]
                    v000 + (v001 - v000) * db + (v011 - v001) * dg + (v111 - v011) * dr
                } else if (db >= dr) {
                    // g > b >= r
                    val v010 = values[c000 + sg + ch]
                    val v011 = values[c000 + sg + sb + ch]
                    v000 + (v010 - v000) * dg + (v011 - v010) * db + (v111 - v011) * dr
                } else {
                    // g > r > b
                    val v010 = values[c000 + sg + ch]
                    val v110 = values[c000 + sr + sg + ch]
                    v000 + (v010 - v000) * dg + (v110 - v010) * dr + (v111 - v110) * db
                }
            }
        }
    }

    /**
     * 与另一张同尺寸 LUT 做线性插值，`t=0` 得 [this]，`t=1` 得 [other]。
     *
     * mpv 的 `--target-lut` **只接受一个文件**，没有强度参数，所以「强度」只能在生成阶段
     * 混合。v2 的生成路径已经在 [LutColorMath] 里就地混合，本方法保留给外部调用与单测。
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

    /** 身份 LUT：输出等于输入（格点顺序与 [CubeLut] 一致）。 */
    fun identity(): CubeLut = identity(size)

    // ─────────────────────────────────────────────────────────────────────────
    // 写
    // ─────────────────────────────────────────────────────────────────────────

    /** 序列化成 `.cube` 文本（诊断与单测用；落盘请用 [writeTo] / [writeCube]，不经过 String）。 */
    fun toCubeText(): String = String(encode(), Charsets.US_ASCII)

    /**
     * 写文件。返回 `true` 表示实际写了。
     *
     * v1 会先把旧文件整份读回来做字符串比较（65³ 约 4.7 MB），这一步本身就比生成还慢；
     * v2 由调用方按签名命名文件，这里只负责高效写入。
     */
    fun writeTo(file: File): Boolean {
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { writeCube(it) }
        return true
        }

    /** 把 `.cube` 字节流写入 [stream]（调用方负责关闭）。 */
    fun writeCube(stream: OutputStream) {
        val buffered = if (stream is BufferedOutputStream) stream else BufferedOutputStream(stream, 1 shl 16)
        buffered.write(encode())
        buffered.flush()
    }

    /**
     * 按 ASCII 直接编码。数值格式与 v1 `appendTrimmed` 完全一致：
     * 6 位小数四舍五入、去尾零、整数不带小数点、永不出现科学计数法（mpv 解析器不认）。
     */
    private fun encode(): ByteArray {
        val header = "LUT_3D_SIZE $size\n".toByteArray(Charsets.US_ASCII)
        // 每个分量最多 "0.123456" 8 字节，三分量 + 2 空格 + 换行 = 27 字节/行。
        val out = ByteArray(header.size + (values.size / 3) * 27)
        System.arraycopy(header, 0, out, 0, header.size)
        var p = header.size
        var i = 0
        while (i < values.size) {
            p = putValue(out, p, values[i])
            out[p++] = SPACE
            p = putValue(out, p, values[i + 1])
            out[p++] = SPACE
            p = putValue(out, p, values[i + 2])
            out[p++] = NEWLINE
            i += 3
        }
        return if (p == out.size) out else out.copyOf(p)
    }

    private fun putValue(buf: ByteArray, start: Int, v: Float): Int {
        val scaled = Math.round(v.coerceIn(0f, 1f) * 1_000_000f)
        if (scaled >= 1_000_000) {
            buf[start] = ONE
            return start + 1
        }
        if (scaled <= 0) {
            buf[start] = ZERO
            return start + 1
        }
        // 0.dddddd：一次性写满 6 位，再回退去掉尾零（比逐位判断少一半分支）。
        buf[start] = ZERO
        buf[start + 1] = DOT
        var f = scaled
        var p = start + 7
        while (p > start + 1) {
            buf[p] = (DIGIT0 + f % 10).toByte()
            f /= 10
            p--
        }
        var end = start + 8
        while (buf[end - 1] == ZERO) end--
        return end
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
        private const val KEYWORD_INPUT_RANGE = "LUT_3D_INPUT_RANGE"

        private const val SPACE = ' '.code.toByte()
        private const val NEWLINE = '\n'.code.toByte()
        private const val DOT = '.'.code.toByte()
        private const val DIGIT0 = '0'.code
        private const val ZERO = '0'.code.toByte()
        private const val ONE = '1'.code.toByte()

        /** 生成一张单位 LUT。 */
        fun identity(size: Int): CubeLut {
            val out = FloatArray(size * size * size * 3)
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

        /**
         * 解析 `.cube` 文本。用于「用户导入自己收藏的 LUT」这条路径。
         *
         * 只支持 3D LUT；`DOMAIN_MIN` / `DOMAIN_MAX` 非默认值时报错（强度混合假定 0..1 定义域）。
         * 数据值会被夹到 0..1：部分调色软件导出的 LUT 带极小的负数或 1.0000001。
         *
         * @throws IllegalArgumentException 格式非法、尺寸越界、数据行数不符。
         */
        fun parse(text: String): CubeLut {
            var size = 0
            var out: FloatArray? = null
            var count = 0
            var sawDomainOverride = false
            var lineNo = 0
            var pos = 0
            val len = text.length
            val triple = FloatArray(3)
            while (pos < len) {
                var end = text.indexOf('\n', pos)
                if (end < 0) end = len
                lineNo++
                var s = pos
                var e = end
                while (s < e && text[s].isWhitespace()) s++
                while (e > s && text[e - 1].isWhitespace()) e--
                pos = end + 1
                if (s >= e || text[s] == '#') continue

                val c = text[s]
                if (c.isLetter()) {
                    val line = text.substring(s, e)
                when {
                    line.startsWith(KEYWORD_SIZE) -> {
                        size = line.removePrefix(KEYWORD_SIZE).trim().toIntOrNull()
                                ?: throw IllegalArgumentException("第 $lineNo 行 LUT_3D_SIZE 不是整数：$line")
                        require(size in MIN_SIZE..MAX_SIZE) {
                                "第 $lineNo 行 LUT_3D_SIZE 超出 $MIN_SIZE..$MAX_SIZE：$size"
                        }
                            out = FloatArray(size * size * size * 3)
                    }

                    line.startsWith(KEYWORD_1D_SIZE) ->
                        throw IllegalArgumentException(
                                "不支持 1D LUT（第 $lineNo 行）。本功能只接受 3D LUT（含 LUT_3D_SIZE 的 .cube）"
                        )

                        line.startsWith(KEYWORD_DOMAIN_MIN) ->
                            if (!isDefaultDomain(line.removePrefix(KEYWORD_DOMAIN_MIN), 0f)) sawDomainOverride = true

                        line.startsWith(KEYWORD_DOMAIN_MAX) ->
                            if (!isDefaultDomain(line.removePrefix(KEYWORD_DOMAIN_MAX), 1f)) sawDomainOverride = true

                        line.startsWith(KEYWORD_INPUT_RANGE) -> {
                            val parts = line.removePrefix(KEYWORD_INPUT_RANGE).trim().split(' ', '\t').filter { it.isNotEmpty() }
                            if (parts.size != 2 || parts[0].toFloatOrNull() != 0f || parts[1].toFloatOrNull() != 1f) {
                                sawDomainOverride = true
                            }
                    }

                        line.startsWith(KEYWORD_TITLE) -> Unit
                        else -> Unit // 其它厂商私有关键字：忽略
                    }
                    continue
                    }

                if (!parseTriple(text, s, e, triple)) continue
                val buffer = out ?: throw IllegalArgumentException("第 $lineNo 行出现数据，但前面没有 LUT_3D_SIZE")
                if (count + 3 > buffer.size) {
                    throw IllegalArgumentException("数据行数超过 LUT_3D_SIZE=$size 的 ${buffer.size / 3} 行")
                    }
                buffer[count] = triple[0].coerceIn(0f, 1f)
                buffer[count + 1] = triple[1].coerceIn(0f, 1f)
                buffer[count + 2] = triple[2].coerceIn(0f, 1f)
                count += 3
            }
            require(size > 0) { "缺少 LUT_3D_SIZE 行，不是有效的 3D .cube 文件" }
            require(!sawDomainOverride) { "不支持自定义 DOMAIN_MIN/DOMAIN_MAX 的 .cube（只接受 0..1 定义域）" }
            val data = out!!
            require(count == data.size) {
                "数据行数与 LUT_3D_SIZE 不符：期望 ${data.size / 3} 行，实际 ${count / 3} 行"
            }
            return CubeLut(size, data)
        }

        /** 解析一行三元组；不是三个数字时返回 false（与 v1 一样静默跳过）。 */
        private fun parseTriple(text: String, start: Int, end: Int, out: FloatArray): Boolean {
            var p = start
            for (k in 0 until 3) {
                while (p < end && (text[p] == ' ' || text[p] == '\t')) p++
                if (p >= end) return false
                val tokenStart = p
                while (p < end && text[p] != ' ' && text[p] != '\t') p++
                out[k] = fastParseFloat(text, tokenStart, p) ?: return false
            }
            while (p < end && (text[p] == ' ' || text[p] == '\t')) p++
            return p == end
        }

        /**
         * 定点快速解析：覆盖 `.cube` 实际出现的 `[-]d+[.d+]` 形态；遇到指数等少见写法
         * 退回 `toFloatOrNull`，结果仍然正确，只是慢一点。
         */
        private fun fastParseFloat(text: String, start: Int, end: Int): Float? {
            var p = start
            var negative = false
            if (p < end && (text[p] == '-' || text[p] == '+')) {
                negative = text[p] == '-'
                p++
            }
            var intPart = 0L
            var digits = 0
            while (p < end && text[p] in '0'..'9') {
                intPart = intPart * 10 + (text[p] - '0')
                p++
                digits++
            }
            var fracPart = 0L
            var scale = 1L
            if (p < end && text[p] == '.') {
                p++
                while (p < end && text[p] in '0'..'9') {
                    if (scale < 1_000_000_000_000L) {
                        fracPart = fracPart * 10 + (text[p] - '0')
                        scale *= 10
                    }
                    p++
                    digits++
                }
            }
            if (p != end || digits == 0) return text.substring(start, end).toFloatOrNull()
            val v = intPart + fracPart.toDouble() / scale
            return (if (negative) -v else v).toFloat()
        }

        private fun isDefaultDomain(rest: String, expected: Float): Boolean {
            val parts = rest.trim().split(' ', '\t').filter { it.isNotEmpty() }
            if (parts.size != 3) return false
            return parts.all { (it.toFloatOrNull() ?: return false) == expected }
        }
    }
}
