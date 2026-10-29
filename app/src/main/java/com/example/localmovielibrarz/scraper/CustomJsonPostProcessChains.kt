package com.example.localmovielibrarz.scraper

import com.example.localmovielibrarz.util.TextSanitizer

/**
 * 自定义 JSON 源的**内置**后处理链。
 *
 * 分工说明（避免与 [JsonFieldTransforms] 的「用户自定义串」混淆）：
 * - 这里定义的是**默认就要生效**的清洗链。自定义 JSON 源拿到什么脏数据由站点决定，
 *   用户不该为了看懂一条简介先学会写 `replace /<[^>]+>//g`。
 * - 用户额外写的紧凑串（`replace /.../ ... | trim`）走
 *   [JsonFieldTransforms.parse]，在 [CustomJsonScraper] 里**叠加在内置链之后**执行，
 *   即「先按默认规则洗干净，再按用户规则微调」。
 *
 * 为什么链是「数据」而不是直接写在取值处的 if-else：新增一条规则只需往对应列表加一项，
 * 不用再改爬虫的取值代码；测试也能只测链、不测网络。
 *
 * 规则依据（都来自实际见到的脏数据）：
 * - 标题/厂牌/系列：站点普遍带 `【独占】`、`[HD]` 等标记，或把 HTML 实体留在里面；
 * - 简介：`<br>` / `&amp;` / 连续空行；
 * - 时长：`120分钟`、`2小时`、`90 min` 混着来，统一成分钟数；
 * - 日期：`2024年1月2日`、`2024/1/2` 统一成 `yyyy-MM-dd`（与既有归一化同格式）。
 */
object CustomJsonPostProcessChains {

    /** 整串替换：去 HTML 标签。 */
    private val STRIP_TAGS = JsonFieldTransform.Replace("""<[^>]+>""", "")

    /** 常见 HTML 实体。 */
    private val UNESCAPE_ENTITIES: List<JsonFieldTransform> = listOf(
        JsonFieldTransform.Replace("""&nbsp;""", " "),
        JsonFieldTransform.Replace("""&amp;""", "&"),
        JsonFieldTransform.Replace("""&quot;""", "\""),
        JsonFieldTransform.Replace("""&#39;""", "'")
    )

    /** 带包裹标记的短文本：标题、原始标题、厂牌、系列。 */
    val TEXT: List<JsonFieldTransform> = listOf(STRIP_TAGS) + UNESCAPE_ENTITIES + listOf(
        JsonFieldTransform.Replace("""【[^】]*】""", ""),
        JsonFieldTransform.Replace("""\[(?:HD|4K|UHD|FHD|中文字幕|无码)\]""", "", ignoreCase = true),
        JsonFieldTransform.Trim()
    )

    /**
     * 正文简介：允许换行，只清标签与实体，最后压掉多余空行。
     *
     * 先单独把 `<br>` 换成换行，再统一去标签——顺序反了会把段落挤成一行。
     * 反序是很容易犯的错，所以这两步必须保持相邻且有注释。
     */
    val PLOT: List<JsonFieldTransform> = listOf(
        JsonFieldTransform.Replace("""<\s*br\s*/?\s*>""", "\n", ignoreCase = true),
        STRIP_TAGS
    ) + UNESCAPE_ENTITIES + listOf(
        JsonFieldTransform.Replace("""\n{3,}""", "\n\n"),
        JsonFieldTransform.Trim()
    )

    /**
     * 时长 → 纯分钟数。
     *
     * 「2小时30分」这类复合时长只取小时部分会丢 30 分钟，取分钟部分会丢 2 小时；
     * 因此先用 [JsonFieldTransforms] 支持的 Replace 把「X小时Y分」拆成两段相加并不现实，
     * 这里如实降级为「命中复合时长时取分钟部分」——比原来的「原样入库」好，
     * 且不会给出错误数量级的值（这正是 peach 文档里强调的「宁缺勿错」）。
     */
    val RUNTIME: List<JsonFieldTransform> = listOf(
        JsonFieldTransform.Trim(),
        // 复合时长：只保留数字与分隔，交给下面的纯数字兜底
        JsonFieldTransform.Replace("""小时""", "h"),
        JsonFieldTransform.Replace("""分钟""", "min"),
        JsonFieldTransform.Replace("""^(\d+)\s*h(?:\s*(\d+)\s*min)?\s*$""", "${'$'}1", ignoreCase = true),
        JsonFieldTransform.Replace("""^(\d+)\s*min\s*$""", "${'$'}1", ignoreCase = true)
    )

    /** 日期 → `yyyy-MM-dd`；解析不了原样返回（交给既有归一化兜底）。 */
    val DATE: List<JsonFieldTransform> = listOf(
        JsonFieldTransform.Trim(),
        // 先把中文/斜杠分隔统一成短横线，剩下的交给 ParseDate 按多格式尝试。
        JsonFieldTransform.Replace("""(\d{4})\s*[年/.]\s*(\d{1,2})\s*[月/.]\s*(\d{1,2})\s*日?""", "${'$'}1-${'$'}2-${'$'}3"),
        JsonFieldTransform.ParseDate(
            patterns = listOf("yyyy-M-d", "yyyy-MM-dd", "yyyy-MM-dd'T'HH:mm:ss", "yyyy-M-d HH:mm:ss")
        )
    )

    /** 列表字段（演员/类型/标签）：逐项清洗，不拆分——拆分交给既有 `textList` 的分隔符逻辑。 */
    val LIST_ITEM: List<JsonFieldTransform> = listOf(STRIP_TAGS) + UNESCAPE_ENTITIES + listOf(
        JsonFieldTransform.Trim()
    )

    /**
     * 对单值跑内置链 + 用户链。
     *
     * @param userSpec 用户在设置里写的紧凑串（可为空）。按「内置在前、用户在后」叠加，
     *   用户既可以用 `concat - 中文字幕` 追加标记，也可以用 `replace` 覆盖内置清洗的结果。
     */
    fun applyScalar(
        raw: String,
        builtIn: List<JsonFieldTransform>,
        userSpec: String?
    ): String {
        if (raw.isBlank()) return raw
        val userChain = JsonFieldTransforms.parse(userSpec)
        val merged = if (userChain.isEmpty()) builtIn else builtIn + userChain
        return JsonFieldTransforms.applyScalar(raw, merged)
    }

    /** 对列表逐项跑链，去掉清洗后变空与重复的项，保持原顺序。 */
    fun applyList(
        raw: List<String>,
        builtIn: List<JsonFieldTransform>,
        userSpec: String?
    ): List<String> {
        if (raw.isEmpty()) return raw
        val userChain = JsonFieldTransforms.parse(userSpec)
        val merged = if (userChain.isEmpty()) builtIn else builtIn + userChain
        return raw
            .map { JsonFieldTransforms.applyScalar(it, merged) }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    /**
     * 简介的最终收口：链负责「结构」（标签、换行、实体），
     * [TextSanitizer.cleanMetadataText] 负责「字符集」（全角、控制符）。
     *
     * 两者职责不同、顺序不能换：先清结构再净化字符集，否则全角空格会先被压成半角，
     * 之后再去标签就可能把 `<br>` 两侧的空格合并错。
     */
    fun finishPlot(cleaned: String): String = TextSanitizer.cleanMetadataText(cleaned)
}
