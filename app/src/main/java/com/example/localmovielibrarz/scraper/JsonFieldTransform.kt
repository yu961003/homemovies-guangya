package com.example.localmovielibrarz.scraper

import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 自定义 JSON 源的单字段后处理动作（对标 Stash 的 YAML `postProcess`）。
 *
 * 为什么要有这一层：站点返回的元数据经常需要「原地修一修」才能落库——
 * 封面 URL 要补域名前缀、简介里混进站点横幅要按正则删掉、日期是 `yyyy/MM/dd`
 * 而库里只认 `yyyy-MM-dd`、演员是逗号串却要存成列表。把这些规则写死在刮削器里，
 * 每加一条规则都得改代码重新发版；写成一小段紧凑串后，用户在设置页里就能自己
 * 组合（`replace /.../ ... | trim`），刮削器只管把原始值交给 [JsonFieldTransforms]。
 *
 * 设计上刻意「不抛异常」：后处理是锦上添花的环节，正则写错、日期对不上、下标
 * 越界都不该让整次刮削失败。任何一步出错都保留上一步的值——这也是 Stash
 * 「postProcess 失败回退原值」的语义。
 */
sealed interface JsonFieldTransform {

    /** 正则替换（替换全部命中）。`with` 里可用 `$1` 引用分组。 */
    data class Replace(
        val regex: String,
        val with: String,
        val ignoreCase: Boolean = false
    ) : JsonFieldTransform

    /** 整串映射：只有正则完整匹配整个当前值时才替换，否则原样返回。 */
    data class MapValue(
        val regex: String,
        val to: String,
        val ignoreCase: Boolean = false
    ) : JsonFieldTransform

    /** 去掉首尾属于 [trimChars] 的字符。 */
    data class Trim(val trimChars: String = " \t\r\n") : JsonFieldTransform

    /** 在末尾追加。 */
    data class Concat(val suffix: String) : JsonFieldTransform

    /** 在开头插入。 */
    data class Prepend(val prefix: String) : JsonFieldTransform

    /** 按字符下标截取；越界自动夹紧。 */
    data class Substring(val start: Int, val end: Int?) : JsonFieldTransform

    /** 多值分隔符。标量链遇到它即停止，列表链用它拆项。 */
    data class Split(val separator: String) : JsonFieldTransform

    /** 按多个源格式依次尝试解析，成功则输出 [targetPattern]；全部失败原样返回。 */
    data class ParseDate(
        val patterns: List<String>,
        val targetPattern: String = "yyyy-MM-dd"
    ) : JsonFieldTransform
}

/**
 * 紧凑转换串的解析与执行。
 *
 * 语法速查（`|` 分隔动作，动作内参数可用 `>>` 分隔）：
 * - `replace /正则/ 替换文本`，正则后紧跟 `i` 表示忽略大小写
 * - `map /正则/ 替换文本`，语义同 replace，但只替换整串完全匹配的值
 * - `trim` 或 `trim 自定义字符集`
 * - `concat 后缀` / `prepend 前缀`
 * - `substring 起点 [终点]`
 * - `split 分隔符`
 * - `date 源格式1;源格式2 >> 目标格式`
 *
 * 已知取舍：替换文本/参数会去掉首尾空白，因此无法用这种紧凑写法表达
 * 「追加一个纯空格」；那是设置页语法的边界，不是后处理链的职责。
 */
object JsonFieldTransforms {

    /** `trim` 不带参数时的默认字符集，覆盖抓取文本里最常见的首尾脏字符。 */
    private const val DEFAULT_TRIM_CHARS = " \t\r\n"

    /** `date` 不带目标格式时的默认输出格式。 */
    private const val DEFAULT_DATE_PATTERN = "yyyy-MM-dd"

    private const val MAP_VERB = "map"

    /**
     * 把一段紧凑转换串解析成有序动作。
     *
     * 解析失败的动作直接丢弃、绝不抛异常：设置页里的串是用户手写的，
     * 一个笔误不能让整个刮削源瘫痪。空串/null 返回空列表（等价于不处理）。
     */
    fun parse(spec: String?): List<JsonFieldTransform> {
        val text = spec?.trim().orEmpty()
        if (text.isEmpty()) return emptyList()

        val result = mutableListOf<JsonFieldTransform>()
        var cursor = 0
        while (cursor < text.length) {
            // `|` 与空白都只是动作边界；`\|` 会由下面的参数扫描器整体吃掉。
            cursor = skipSeparators(text, cursor)
            if (cursor >= text.length) break

            val verbStart = cursor
            cursor = readVerbEnd(text, cursor)
            val verb = text.substring(verbStart, cursor).lowercase(Locale.US)

            cursor = try {
                when (verb) {
                    "replace", MAP_VERB -> consumeRegexAction(text, cursor, verb, result)
                    "trim", "concat", "prepend", "substring", "split", "date" ->
                        consumePlainAction(text, cursor, verb, result)
                    // 未知动作（含用户写错的拼写）整段跳过，继续解析后面的动作。
                    else -> skipAction(text, cursor)
                }
            } catch (e: Exception) {
                // 解析器自身也遵守「绝不抛异常」：坏动作之后继续找下一个 `|`。
                skipAction(text, cursor)
            }
            // 防御性推进：任何一条分支都不允许把游标留在原地，否则外层会死循环。
            if (cursor <= verbStart) cursor = verbStart + 1
        }
        return result
    }

    /**
     * 标量链：依次套用，遇到 [JsonFieldTransform.Split] 立即停止。
     *
     * Split 的语义属于列表（见 [applyList]），标量字段没有「多项」可拆，
     * 继续执行只会得到一个被分隔符污染一半的值，所以只能在链尾出现。
     */
    fun applyScalar(raw: String, transforms: List<JsonFieldTransform>): String {
        var value = raw
        for (transform in transforms) {
            if (transform is JsonFieldTransform.Split) break
            value = try {
                applyOne(value, transform)
            } catch (e: Exception) {
                // 非法正则、分组引用越界、日期格式非法……统一回退到这一步之前的值。
                value
            }
        }
        return value
    }

    /**
     * 列表链：先拆项，再对每一项套用除 Split 以外的全部标量动作。
     *
     * 拆项规则刻意保守：只有链里出现 Split 才用它的分隔符，否则才按 `|` 拆；
     * 用逗号/斜杠做「顺带拆分」会误伤 `SSIS-115, 中文标题` 这类正常字段。
     * 不足两项时说明原值本来就不是列表，整串当作唯一一项处理。
     */
    fun applyList(raw: String, transforms: List<JsonFieldTransform>): List<String> {
        val declaredSeparator = transforms
            .filterIsInstance<JsonFieldTransform.Split>()
            .firstOrNull()
            ?.separator
            ?.takeIf { it.isNotEmpty() }
        val separator = declaredSeparator ?: "|"
        // Split 已经变成「拆」这个动作本身，不再参与单项的标量处理。
        val scalarChain = transforms.filterNot { it is JsonFieldTransform.Split }

        val parts = try {
            raw.split(separator)
        } catch (e: Exception) {
            listOf(raw)
        }
        val items = if (parts.size >= 2) parts else listOf(raw)

        return items
            .map { item -> applyScalar(item, scalarChain) }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    // ---------------------------------------------------------------------
    // 解析
    // ---------------------------------------------------------------------

    private fun skipSeparators(text: String, from: Int): Int {
        var index = from
        while (index < text.length && (text[index].isWhitespace() || text[index] == '|')) {
            index++
        }
        return index
    }

    /** 动作名读到空白 / `|` / `/` 为止；`/` 让 `replace/.../...` 这种紧凑写法也能解析。 */
    private fun readVerbEnd(text: String, from: Int): Int {
        var index = from
        while (index < text.length) {
            val char = text[index]
            if (char.isWhitespace() || char == '|' || char == '/') break
            index++
        }
        return index
    }

    /** 跳过当前动作剩余部分，停在下一个未转义的 `|`（或串尾）。 */
    private fun skipAction(text: String, from: Int): Int {
        var index = from
        while (index < text.length) {
            val char = text[index]
            if (char == '\\' && index + 1 < text.length) {
                index += 2
                continue
            }
            if (char == '|') return index
            index++
        }
        return index
    }

    /**
     * 解析 `replace` / `map`：正则用 `/.../` 定界，正则内部的 `\/` 原样交给
     * Java 正则（它允许转义非字母字符），这样站点 URL 里的斜杠不必先反解。
     */
    private fun consumeRegexAction(
        text: String,
        from: Int,
        verb: String,
        out: MutableList<JsonFieldTransform>
    ): Int {
        var index = from
        while (index < text.length && text[index].isWhitespace()) index++
        if (index >= text.length || text[index] != '/') return skipAction(text, index)

        index++ // 开头的 `/`
        val pattern = StringBuilder()
        var closed = false
        while (index < text.length) {
            val char = text[index]
            if (char == '\\' && index + 1 < text.length) {
                pattern.append(char).append(text[index + 1])
                index += 2
                continue
            }
            if (char == '/') {
                closed = true
                index++
                break
            }
            pattern.append(char)
            index++
        }
        if (!closed) return skipAction(text, index) // 定界符没闭合，丢弃整个动作

        // 正则后的连续字母视作标志位；目前只认 `i`，其余字母忽略。
        val flagsStart = index
        while (index < text.length && text[index].isLetter() && text[index].code < 128) index++
        val flags = text.substring(flagsStart, index)

        while (index < text.length && text[index].isWhitespace()) index++
        // 同时接受 `replace /x/>>替换` 这种「参数用 >> 分隔」的写法。
        if (index + 1 < text.length && text[index] == '>' && text[index + 1] == '>') {
            index += 2
            while (index < text.length && text[index].isWhitespace()) index++
        }

        val replacement = StringBuilder()
        while (index < text.length) {
            val char = text[index]
            if (char == '\\' && index + 1 < text.length) {
                replacement.append(char).append(text[index + 1])
                index += 2
                continue
            }
            if (char == '|') break
            replacement.append(char)
            index++
        }

        if (pattern.isNotEmpty()) {
            val ignoreCase = flags.contains('i')
            val with = unescapePipes(replacement.toString()).trim()
            out += if (verb == MAP_VERB) {
                JsonFieldTransform.MapValue(pattern.toString(), with, ignoreCase)
            } else {
                JsonFieldTransform.Replace(pattern.toString(), with, ignoreCase)
            }
        }
        return index
    }

    /** 解析其余「动词 + 一段文本参数」的动作；参数一直取到下一个未转义的 `|`。 */
    private fun consumePlainAction(
        text: String,
        from: Int,
        verb: String,
        out: MutableList<JsonFieldTransform>
    ): Int {
        var index = from
        while (index < text.length && text[index].isWhitespace()) index++

        val argument = StringBuilder()
        while (index < text.length) {
            val char = text[index]
            if (char == '\\' && index + 1 < text.length) {
                argument.append(char).append(text[index + 1])
                index += 2
                continue
            }
            if (char == '|') break
            argument.append(char)
            index++
        }

        val raw = unescapePipes(argument.toString()).trim()
        val transform: JsonFieldTransform? = when (verb) {
            "trim" -> JsonFieldTransform.Trim(raw.ifEmpty { DEFAULT_TRIM_CHARS })
            "concat" -> raw.takeIf { it.isNotEmpty() }?.let { JsonFieldTransform.Concat(it) }
            "prepend" -> raw.takeIf { it.isNotEmpty() }?.let { JsonFieldTransform.Prepend(it) }
            "substring" -> parseSubstring(raw)
            "split" -> raw.takeIf { it.isNotEmpty() }?.let { JsonFieldTransform.Split(it) }
            "date" -> parseDateSpec(raw)
            else -> null
        }
        if (transform != null) out += transform
        return index
    }

    private fun parseSubstring(raw: String): JsonFieldTransform? {
        // `substring 0>>4` 与 `substring 0 4` 等价，逗号也容忍。
        val parts = raw.replace(">>", " ")
            .split(' ', '\t', ',')
            .filter { it.isNotBlank() }
        if (parts.isEmpty()) return null
        val start = parts[0].trim().toIntOrNull() ?: return null
        val end = if (parts.size >= 2) {
            parts[1].trim().toIntOrNull() ?: return null
        } else {
            null
        }
        return JsonFieldTransform.Substring(start, end)
    }

    private fun parseDateSpec(raw: String): JsonFieldTransform? {
        if (raw.isBlank()) return null
        val separatorIndex = raw.indexOf(">>")
        val sourcePart: String
        var targetPattern = DEFAULT_DATE_PATTERN
        if (separatorIndex >= 0) {
            sourcePart = raw.substring(0, separatorIndex)
            val explicitTarget = raw.substring(separatorIndex + 2).trim()
            if (explicitTarget.isNotEmpty()) targetPattern = explicitTarget
        } else {
            sourcePart = raw
        }
        val patterns = sourcePart.split(';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (patterns.isEmpty()) return null
        return JsonFieldTransform.ParseDate(patterns, targetPattern)
    }

    /** 唯一支持的转义是 `\|`：分隔符本身要能出现在参数里，其余反斜杠保持原样。 */
    private fun unescapePipes(value: String): String = value.replace("\\|", "|")

    // ---------------------------------------------------------------------
    // 执行
    // ---------------------------------------------------------------------

    private fun applyOne(value: String, transform: JsonFieldTransform): String = when (transform) {
        is JsonFieldTransform.Replace ->
            Regex(transform.regex, regexOptions(transform.ignoreCase))
                .replace(value, transform.with)

        is JsonFieldTransform.MapValue -> {
            val regex = Regex(transform.regex, regexOptions(transform.ignoreCase))
            // matchEntire 保证「整串」而不是「任意子串」命中；不匹配就保持原值。
            if (regex.matchEntire(value) != null) regex.replace(value, transform.to) else value
        }

        is JsonFieldTransform.Trim -> value.trim { it in transform.trimChars }
        is JsonFieldTransform.Concat -> value + transform.suffix
        is JsonFieldTransform.Prepend -> transform.prefix + value
        is JsonFieldTransform.Substring -> value.takeRange(transform.start, transform.end)
        is JsonFieldTransform.Split -> value
        is JsonFieldTransform.ParseDate -> value.formatWith(transform)
    }

    private fun regexOptions(ignoreCase: Boolean): Set<RegexOption> =
        if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()

    private fun String.takeRange(start: Int, end: Int?): String {
        // 先夹紧到合法下标：用户写的 `substring 10 20` 打在 7 个字的值上必须得到空串，
        // 而不是 IndexOutOfBoundsException。终点早于起点时也按空串处理。
        val from = start.coerceIn(0, length)
        val to = (end ?: length).coerceIn(0, length)
        return if (to <= from) "" else substring(from, to)
    }

    private fun String.formatWith(transform: JsonFieldTransform.ParseDate): String {
        val source = trim()
        if (source.isEmpty() || transform.patterns.isEmpty()) return this
        // SimpleDateFormat 非线程安全，这里每次新建；Locale.US 固定数字/字母语义，
        // 避免在土耳其语等地区出现 `i` 大小写之类的意外。
        val target = SimpleDateFormat(transform.targetPattern, Locale.US)
        for (pattern in transform.patterns) {
            val parsed = try {
                SimpleDateFormat(pattern, Locale.US).apply { isLenient = false }.parse(source)
            } catch (e: Exception) {
                null
            } ?: continue
            return try {
                target.format(parsed)
            } catch (e: Exception) {
                this
            }
        }
        // 所有源格式都解析不了：保留刮削器拿到的原文，交给上层决定怎么兜底。
        return this
    }
}
