package com.example.localmovielibrarz.scraper

/**
 * 一个只读的 JSON 值。
 *
 * 为什么不直接用 `org.json`：peach 链的解析器必须能被离线单测覆盖（本机没有 Android SDK，
 * `work/compile-pure-jvm.sh` 的 classpath 里只有 JUnit 与协程），而 `org.json` 是 Android 平台类，
 * 在纯 JVM 编译集里不可见。把解析逻辑塞进 `import okhttp3` 的文件里可以让编译器闭嘴，
 * 代价是这一段——链上最容易改版、最需要回归的三家站点解析——从此没有任何测试。
 *
 * 为什么不引第三方（Gson/Moshi/kotlinx-serialization）：本项目的约束是不加依赖，
 * 而这里需要的只是「把一页里嵌的那段 JSON 读成树」，不需要序列化、注解、泛型绑定。
 *
 * [Num] 保留原始字面量而不是直接转成 `Double`：FC2 的商品号是 7 位整数，
 * `Double` 走一遍 `4.137487E6` 再拼回去就会变成 `4137487` 之外的形状；番号比对是
 * 逐字符的，这类精度损失在链上表现为「查到了却被判成别的片」。
 */
sealed interface PeachJsonValue {

    /** JSON 的 `null`。与「键不存在」区分开：前者是站点明确写了 null。 */
    data object Null : PeachJsonValue

    data class Bool(val value: Boolean) : PeachJsonValue

    /** 数字，`raw` 是原始字面量（`4137487`、`1.5`、`-3`）。 */
    data class Num(val raw: String) : PeachJsonValue {

        fun toLongOrNull(): Long? = raw.toLongOrNull()

        fun toDoubleOrNull(): Double? = raw.toDoubleOrNull()
    }

    data class Str(val value: String) : PeachJsonValue

    data class Arr(val items: List<PeachJsonValue>) : PeachJsonValue

    /** 对象。重复键取最后一次——与 Python `json` 的默认行为一致。 */
    data class Obj(val fields: Map<String, PeachJsonValue>) : PeachJsonValue {
        operator fun get(key: String): PeachJsonValue? = fields[key]
    }
}

/** 取值辅助：站点数据里九成是「键在、值是 null、值是空串」这三种情况的混合。 */
object PeachJson {

    /** 解析入口。输入不是合法 JSON（含尾部多余字符）时返回 null，不抛异常。 */
    fun parse(text: String?): PeachJsonValue? {
        if (text.isNullOrBlank()) return null
        return runCatching { Reader(text).readDocument() }.getOrNull()
    }

    /** 解析成对象；顶层不是对象时返回 null。 */
    fun parseObject(text: String?): PeachJsonValue.Obj? = parse(text) as? PeachJsonValue.Obj

    /**
     * 递归下降读取器。
     *
     * 只承担「读」：本工程写 JSON 的地方（NFO、自定义源的取值变换）另有实现，
     * 把两边混在一个类里会让「读的容错」与「写的稳定」互相牵制。
     */
    private class Reader(private val text: String) {

        private var index = 0

        fun readDocument(): PeachJsonValue {
            val value = readValue(depth = 0)
            skipWhitespace()
            // 尾部还有内容说明这一页不是我们以为的那段 JSON（例如脚本后面又拼了一段 HTML），
            // 宁可判失败也不要交出一份「看起来能解析」的半截数据。
            require(index == text.length) { "JSON 尾部有多余内容，位置 $index" }
            return value
        }

        private fun readValue(depth: Int): PeachJsonValue {
            require(depth <= MAX_DEPTH) { "JSON 嵌套超过 $MAX_DEPTH 层" }
            skipWhitespace()
            require(index < text.length) { "JSON 在位置 $index 处意外结束" }
            return when (val ch = text[index]) {
                '{' -> readObject(depth)
                '[' -> readArray(depth)
                '"' -> PeachJsonValue.Str(readString())
                't' -> readLiteral("true", PeachJsonValue.Bool(true))
                'f' -> readLiteral("false", PeachJsonValue.Bool(false))
                'n' -> readLiteral("null", PeachJsonValue.Null)
                else -> if (ch == '-' || ch.isDigit()) readNumber() else error("JSON 位置 $index 出现非法字符 $ch")
            }
        }

        private fun readObject(depth: Int): PeachJsonValue.Obj {
            expect('{')
            val fields = LinkedHashMap<String, PeachJsonValue>()
            skipWhitespace()
            if (peek() == '}') {
                index += 1
                return PeachJsonValue.Obj(fields)
            }
            while (true) {
                skipWhitespace()
                val key = readString()
                skipWhitespace()
                expect(':')
                fields[key] = readValue(depth + 1)
                skipWhitespace()
                when (peek()) {
                    ',' -> index += 1
                    '}' -> {
                        index += 1
                        return PeachJsonValue.Obj(fields)
                    }
                    else -> error("JSON 对象在位置 $index 处缺少 , 或 }")
                }
            }
        }

        private fun readArray(depth: Int): PeachJsonValue.Arr {
            expect('[')
            val items = ArrayList<PeachJsonValue>()
            skipWhitespace()
            if (peek() == ']') {
                index += 1
                return PeachJsonValue.Arr(items)
            }
            while (true) {
                items += readValue(depth + 1)
                skipWhitespace()
                when (peek()) {
                    ',' -> index += 1
                    ']' -> {
                        index += 1
                        return PeachJsonValue.Arr(items)
                    }
                    else -> error("JSON 数组在位置 $index 处缺少 , 或 ]")
                }
            }
        }

        private fun readString(): String {
            expect('"')
            val builder = StringBuilder()
            while (true) {
                require(index < text.length) { "JSON 字符串未闭合" }
                val ch = text[index]
                index += 1
                when (ch) {
                    '"' -> return builder.toString()
                    '\\' -> {
                        require(index < text.length) { "JSON 转义序列未完成" }
                        when (val escape = text[index]) {
                            '"' -> { builder.append('"'); index += 1 }
                            '\\' -> { builder.append('\\'); index += 1 }
                            '/' -> { builder.append('/'); index += 1 }
                            'b' -> { builder.append('\b'); index += 1 }
                            'f' -> { builder.append('\u000C'); index += 1 }
                            'n' -> { builder.append('\n'); index += 1 }
                            'r' -> { builder.append('\r'); index += 1 }
                            't' -> { builder.append('\t'); index += 1 }
                            'u' -> {
                                index += 1
                                require(index + 4 <= text.length) { "JSON \\u 转义长度不足" }
                                val code = text.substring(index, index + 4).toIntOrNull(16)
                                    ?: error("JSON \\u 转义不是十六进制")
                                builder.append(code.toChar())
                                index += 4
                            }
                            else -> error("JSON 不认识的转义 \\$escape")
                        }
                    }
                    else -> builder.append(ch)
                }
            }
        }

        private fun readNumber(): PeachJsonValue.Num {
            val start = index
            if (peek() == '-') index += 1
            while (index < text.length && (text[index].isDigit() || text[index] == '.' ||
                        text[index] == 'e' || text[index] == 'E' || text[index] == '+' || text[index] == '-')
            ) {
                index += 1
            }
            require(index > start) { "JSON 数字为空" }
            return PeachJsonValue.Num(text.substring(start, index))
        }

        private fun readLiteral(literal: String, value: PeachJsonValue): PeachJsonValue {
            require(text.startsWith(literal, index)) { "JSON 位置 $index 处不是 $literal" }
            index += literal.length
            return value
        }

        private fun expect(ch: Char) {
            require(peek() == ch) { "JSON 位置 $index 处期望 $ch" }
            index += 1
        }

        private fun peek(): Char? = if (index < text.length) text[index] else null

        private fun skipWhitespace() {
            while (index < text.length && text[index].isWhitespace()) index += 1
        }
    }

    /**
     * 嵌套深度上限。
     *
     * 站点返回的是自己的页面，正常情况下五六层就到顶了；给 64 层是留足余量，同时保证
     * 一份被灌成「一千个 `[`」的响应不会把递归吃掉整个线程栈——那是崩溃，不是解析失败。
     */
    private const val MAX_DEPTH = 64
}

/*
 * 下面是读取侧的取值辅助。放在文件顶层而不是 [PeachJson] 的成员：成员扩展函数只有
 * 在对象作用域内才可见，三家站点的解析器分别在不同的 object 里，逐个 `with(PeachJson)`
 * 包一层只会让调用点更难读。
 */

/** 字符串值；数字与布尔也接受（站点偶尔把 id 写成数字）。其余返回空串。 */
fun PeachJsonValue?.stringOrEmpty(): String = when (this) {
    null, PeachJsonValue.Null -> ""
    is PeachJsonValue.Str -> value
    is PeachJsonValue.Num -> raw
    is PeachJsonValue.Bool -> if (value) "true" else "false"
    else -> ""
}

/** 对象字段；不存在或不是对象时返回 null。 */
fun PeachJsonValue?.objOrNull(): PeachJsonValue.Obj? = this as? PeachJsonValue.Obj

/** 对象字段；不存在或不是数组时返回空列表。 */
fun PeachJsonValue?.arrayOrEmpty(): List<PeachJsonValue> =
    (this as? PeachJsonValue.Arr)?.items.orEmpty()

/** 数组里所有对象，顺序不变。 */
fun PeachJsonValue?.objectsOrEmpty(): List<PeachJsonValue.Obj> =
    arrayOrEmpty().mapNotNull { it as? PeachJsonValue.Obj }

/** 数组里所有字符串值（数字也按字面量收），空串被丢弃。 */
fun PeachJsonValue?.stringsOrEmpty(): List<String> =
    arrayOrEmpty().map { it.stringOrEmpty().trim() }.filter { it.isNotEmpty() }
