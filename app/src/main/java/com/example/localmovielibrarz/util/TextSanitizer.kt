package com.example.localmovielibrarz.util

/**
 * 展示层文本净化。
 *
 * 背景（需求 2）：影片详情页的「影片详情 / 简介」里会出现 `<br>` 字面量，
 * 甚至 `<br/>`、`&lt;br&gt;`、`<br />` 等变体，直接影响阅读体验。
 *
 * 成因是刮削源的描述字段本身就是一段 HTML：
 *   - DMM/Javbus/Mgstage 走 `cleanHtml`，用 `<[^>]+>` 正则整体剥标签 → 正常；
 *   - JavDb / Dmm2 / CustomJson 的 `cleanText` 只做实体反转义，**未剥离任何标签** → 漏网。
 * 更麻烦的是「二次转义」：源站把 `<br>` 写成 `&lt;br&gt;`，
 * 反转义之后才变成 `<br>`，此时若「先剥标签、后反转义」，标签就会残留到最终文本。
 *
 * 因此本净化器采用「先反转义 → 再剥标签 → 再反转义」的幂等流水线，
 * 既能清掉原始标签，也能清掉反转义后才浮现的标签，重复调用结果稳定。
 * 另外把 `<br>` 类换行标签替换为真实换行，保留原作者的分段意图（而不是糊成一行）。
 */
object TextSanitizer {

    /**
     * 会被解释为「换行」的标签。
     * `[^<>]*` 段用于吞掉属性（如 `<br clear=all>`、`<br style="...">`），
     * 否则带属性的 br 不会被识别为换行、而是被后面 ANY_TAG 直接删除 —— 结果两个词会粘在一起。
     */
    private val LINE_BREAK_TAGS = Regex(
        """<\s*(?i:br|/p|/div|/li|/tr|/h[1-6])\b[^<>]*?/?\s*>""",
        RegexOption.IGNORE_CASE
    )

    /** 需要整体丢弃（连内容一起）的标签：script/style 里的内容不是给人看的。 */
    private val DROP_WITH_CONTENT_TAGS = Regex(
        """<\s*(?i:script|style)\b[^>]*>[\s\S]*?<\s*/\s*(?i:script|style)\s*>""",
        RegexOption.IGNORE_CASE
    )

    /** 任意 HTML 标签（此阶段只剥壳、保内容）。 */
    private val ANY_TAG = Regex("""<[^<>]{0,300}?>""")

    /** 连续空白（不含换行时用于压缩行内空格）。 */
    private val INLINE_WS = Regex("""[ \t\u00A0\u3000]{2,}""")

    /** 3 个及以上连续换行 → 压缩为 2 个（保留段落感、去掉大片空白）。 */
    private val EXCESS_NEWLINES = Regex("""\n{3,}""")

    /**
     * 净化「带 HTML 的元数据文本」，用于影片简介等展示场景。
     *
     * 处理顺序（顺序本身很关键，见类注释）：
     * 1. 丢弃 script/style 及其内容；
     * 2. 反转义 HTML 实体（`&lt;br&gt;` → `<br>`）；
     * 3. 把换行类标签替换为 `\n`；
     * 4. 剥离剩余全部标签；
     * 5. 再反转义一次（剥标签可能又暴露出新的实体，如 `&amp;lt;`）；
     * 6. 去掉零宽字符、统一空白。
     *
     * @param keepLineBreaks true（默认）保留 `<br>` 产生的换行；false 时压成单空格（适合单行标题）。
     */
    fun cleanMetadataText(raw: String?, keepLineBreaks: Boolean = true): String {
        if (raw.isNullOrEmpty()) return ""
        var text = raw

        // 1. 先干掉脚本/样式块（其内容含尖括号，会干扰后续标签识别）。
        //    用空串替换：脚本块通常紧跟正文，补空格会把「前后」变成「前 后」。
        text = DROP_WITH_CONTENT_TAGS.replace(text, "")

        // 2. 反转义：让被转义的标签显形，后续才能被识别并剥离
        text = unescapeHtmlEntities(text)

        // 3. 换行标签 → 真实换行（保留原作者分段意图）
        text = LINE_BREAK_TAGS.replace(text, "\n")

        // 4. 剥离其余标签
        text = ANY_TAG.replace(text, "")

        // 5. 再反转义一次：`&amp;lt;` → 上一步后可能浮现新的 `&lt;`
        text = unescapeHtmlEntities(text)
        //   第二次可能又暴露出标签（极少数双重转义数据），再剥一遍并统一换行
        text = LINE_BREAK_TAGS.replace(text, "\n")
        text = ANY_TAG.replace(text, "")

        // 6. 清理零宽字符与多余空白
        text = stripZeroWidth(text)
        text = text.replace('\r', '\n')

        return if (keepLineBreaks) {
            text.split('\n')
                .map { INLINE_WS.replace(it, " ").trim() }
                .joinToString("\n")
                .let { EXCESS_NEWLINES.replace(it, "\n\n") }
                .trim()
        } else {
            INLINE_WS.replace(text.replace('\n', ' '), " ").trim()
        }
    }

    /** 单行场景的便捷入口（标题、分类、演员名等）。 */
    fun cleanSingleLine(raw: String?): String = cleanMetadataText(raw, keepLineBreaks = false)

    /** 仅做 HTML 实体反转义（供刮削器内部复用）。 */
    fun unescapeHtmlEntities(value: String): String {
        if (value.indexOf('&') < 0) return value
        var s = value
        // 命名实体
        s = s.replace("&nbsp;", " ")
            .replace("&ensp;", " ")
            .replace("&emsp;", " ")
            .replace("&thinsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&#39;", "'")
            .replace("&#34;", "\"")
            .replace("&hellip;", "…")
            .replace("&mdash;", "—")
            .replace("&ndash;", "–")
            .replace("&middot;", "·")
            .replace("&times;", "×")
            .replace("&yen;", "¥")
            .replace("&copy;", "©")
            .replace("&reg;", "®")
            .replace("&trade;", "™")

        // 数字实体 &#123; / &#x1F600;（含十六进制）
        if (s.indexOf("&#") >= 0) {
            s = NUMERIC_ENTITY.replace(s) { m ->
                val body = m.groupValues[1]
                val code = runCatching {
                    if (body.startsWith("x", ignoreCase = true)) body.substring(1).toInt(16)
                    else body.toInt()
                }.getOrNull()
                if (code == null || code <= 0 || code > 0x10FFFF) m.value
                else String(Character.toChars(code))
            }
        }
        return s
    }

    /** 零宽与 BOM 等不可见字符（按字符集合逐个删除，见 [stripZeroWidth]）。 */
    private val ZERO_WIDTH_CHARS = charArrayOf(
        '\u200B', // ZERO WIDTH SPACE
        '\u200C', // ZERO WIDTH NON-JOINER
        '\u200D', // ZERO WIDTH JOINER
        '\uFEFF', // ZERO WIDTH NO-BREAK SPACE / BOM
        '\u2060'  // WORD JOINER
    )

    /**
     * ★ 需求 2 修复（F-1）：按字符集合删除零宽字符。
     *
     * 旧实现用 `text.replace(ZERO_WIDTH, "")`（ZERO_WIDTH 是 5 个字符拼成的 String），
     * 命中的是 `String.replace(CharSequence, CharSequence)` 重载——**按整段子串匹配**，
     * 只有这 5 个字符连续相邻出现时才会被删除；单独出现的（例如刮削页面里最常见的
     * 单个 U+200B 零宽空格、文件头的 U+FEFF BOM）会原样残留。
     */
    private fun stripZeroWidth(text: String): String {
        if (text.none { it in ZERO_WIDTH_CHARS }) return text
        return text.filterNot { it in ZERO_WIDTH_CHARS }
    }

    private val NUMERIC_ENTITY = Regex("""&#(x?[0-9A-Fa-f]{1,8});""")
}
