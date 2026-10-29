package com.example.localmovielibrarz.ui.cloudclean

import java.util.Locale

/**
 * 清理名单（关键词 / 后缀）的**纯逻辑层**。
 *
 * 为什么单独抽出来：
 * 需求 3 要求把「关键词/后缀」从「输入框 + chip 流」改成下拉菜单式选择，
 * 而选择器的核心难点全在纯逻辑里（排序、归一化、候选项生成、搜索过滤、全选/反选），
 * 与 Compose 无关。抽成纯函数后可以脱离 Android 直接在 JVM 上做静态验证，
 * 避免「改 UI 顺带改坏匹配语义」。
 *
 * 术语：
 * - keyword（关键词）：文件名**包含**即命中，大小写不敏感；
 * - suffix（后缀）：文件**扩展名等于**该值才命中（不带点，如 "txt"、"mp4"）。
 */

/** 归一化后的候选条目：value 是真正参与匹配的值，label 是展示文本。 */
data class CleanRuleOption(
    val value: String,
    val label: String,
    val selected: Boolean = false,
    /** 内置推荐项（用于「推荐」分组，与用户自定义项区分展示）。 */
    val recommended: Boolean = false
) {
    /** 用于去重与搜索的键。 */
    val key: String get() = CleanRuleLogic.keywordKey(value)
}

/** 选择器的全量状态快照（纯数据，便于测试）。 */
data class CleanRuleSelection(
    val options: List<CleanRuleOption>,
    val query: String = ""
) {
    /** 应用搜索词后的可见项；搜索为空时返回全部（推荐项在前）。 */
    val visible: List<CleanRuleOption> get() = CleanRuleLogic.filterOptions(options, query)

    val selectedValues: List<String> get() = options.filter { it.selected }.map { it.value }
    val selectedCount: Int get() = options.count { it.selected }
    val allSelected: Boolean get() = options.isNotEmpty() && options.all { it.selected }
}

object CleanRuleLogic {

    /** 关键词归一化：trim + 去首尾引号 + 连续空白压成一个空格。大小写**保留**（便于展示）。 */
    fun normalizeKeyword(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        return raw.trim()
            .trim('"', '\'', '「', '」', '“', '”')
            .trim()
            .replace(Regex("""\s+"""), " ")
    }

    /**
     * 后缀归一化：去掉前导点、trim、**转小写**。
     * 扩展名匹配本身大小写不敏感，统一小写可避免 "TXT"/"txt" 在名单里重复出现。
     */
    fun normalizeSuffix(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        return raw.trim().trimStart('.').trim().lowercase(Locale.ROOT)
    }

    /** 关键词去重键（大小写不敏感：广告 / 廣告 是两回事，但 "AD"/"ad" 视为同一条）。 */
    fun keywordKey(raw: String?): String = normalizeKeyword(raw).lowercase(Locale.ROOT)

    /**
     * 构建选择器选项列表：内置推荐项 + 用户已选项，按「选中优先、推荐次之、字母序」排序。
     *
     * @param existing 已在名单中的值（来自 SharedPreferences）
     * @param recommended 内置推荐候选项
     * @param isSuffix true 表示按后缀规则归一化，false 按关键词
     */
    fun buildOptions(
        existing: Collection<String>,
        recommended: List<String>,
        isSuffix: Boolean
    ): List<CleanRuleOption> {
        val normalize = if (isSuffix) ::normalizeSuffix else ::normalizeKeyword
        val keyOf = { v: String -> if (isSuffix) normalizeSuffix(v) else keywordKey(v) }

        val existingNormalized = LinkedHashMap<String, String>()
        existing.forEach { raw ->
            val v = normalize(raw)
            if (v.isNotEmpty()) existingNormalized.putIfAbsent(keyOf(v), v)
        }
        val recommendedNormalized = LinkedHashMap<String, String>()
        recommended.forEach { raw ->
            val v = normalize(raw)
            if (v.isNotEmpty()) recommendedNormalized.putIfAbsent(keyOf(v), v)
        }

        val result = mutableListOf<CleanRuleOption>()
        // 1) 用户已选项（无论是否在推荐列表里都要出现，否则用户看不到/删不掉自定义项）
        existingNormalized.forEach { (key, value) ->
            result += CleanRuleOption(
                value = value,
                label = if (isSuffix) ".$value" else value,
                selected = true,
                recommended = key in recommendedNormalized
            )
        }
        // 2) 未选中的推荐项
        recommendedNormalized.forEach { (key, value) ->
            if (key !in existingNormalized) {
                result += CleanRuleOption(
                    value = value,
                    label = if (isSuffix) ".$value" else value,
                    selected = false,
                    recommended = true
                )
            }
        }
        return result.sortedWith(compareByDescending<CleanRuleOption> { it.selected }.thenBy { it.value.lowercase(Locale.ROOT) })
    }

    /** 搜索过滤：value 或 label 命中即保留；搜索词大小写不敏感。 */
    fun filterOptions(options: List<CleanRuleOption>, query: String): List<CleanRuleOption> {
        val q = query.trim().lowercase(Locale.ROOT)
        if (q.isEmpty()) return options
        return options.filter {
            it.value.lowercase(Locale.ROOT).contains(q) || it.label.lowercase(Locale.ROOT).contains(q)
        }
    }

    /**
     * 把「选择器提交的选中集合」落库前清洗：
     * 归一化 → 去空 → 去重（保序）→ 截断到上限。
     *
     * ⚠️ 去重必须按**大小写不敏感的 key**，不能按归一化后的字面值：
     * 关键词匹配本身 ignoreCase=true，若同时存了 "AD" 和 "ad"，界面上会出现两条
     * 看起来不同、实际行为完全一样的规则，用户无法理解为什么「删不掉重复项」。
     */
    fun sanitizeSelection(values: Collection<String>, isSuffix: Boolean, max: Int): List<String> {
        val normalize = if (isSuffix) ::normalizeSuffix else ::normalizeKeyword
        val keyOf = { v: String -> if (isSuffix) normalizeSuffix(v) else keywordKey(v) }
        val seen = LinkedHashMap<String, String>()   // key -> 首个出现的展示值
        values.forEach { raw ->
            val v = normalize(raw)
            if (v.isNotEmpty()) seen.putIfAbsent(keyOf(v), v)
        }
        return seen.values.take(max)
    }

    /** 校验单条输入；返回 null 表示合法，否则返回拒绝原因（供 UI 即时提示）。 */
    fun validateEntry(raw: String?, isSuffix: Boolean): String? {
        val normalized = if (isSuffix) normalizeSuffix(raw) else normalizeKeyword(raw)
        if (normalized.isEmpty()) return "不能为空"
        if (normalized.length > MAX_ENTRY_LENGTH) return "超过 $MAX_ENTRY_LENGTH 个字符"
        if (normalized.any { it.isISOControl() }) return "包含控制字符"
        if (isSuffix) {
            if (normalized.contains('/') || normalized.contains('\\')) return "后缀不能包含路径分隔符"
            if (normalized.contains(' ')) return "后缀不能包含空格"
            if (normalized.length > MAX_SUFFIX_LENGTH) return "后缀超过 $MAX_SUFFIX_LENGTH 个字符"
        }
        return null
    }

    /** 依据开关状态计算匹配语义的可读描述（直接对齐 ViewModel 的 AND/OR 行为）。 */
    fun matchModeDescription(keywordEnabled: Boolean, suffixEnabled: Boolean): String = when {
        keywordEnabled && suffixEnabled -> "同时点亮：需「包含关键词」且「扩展名命中后缀」（大小阈值仅约束后缀侧）"
        keywordEnabled -> "仅关键词：文件名包含任一关键词即命中"
        suffixEnabled -> "仅后缀：扩展名命中任一后缀即命中（可叠加大小阈值）"
        else -> "两组均熄灭：无法匹配，请至少点亮一组"
    }

    const val MAX_ENTRY_LENGTH = 100
    const val MAX_SUFFIX_LENGTH = 20

    /** 内置推荐后缀（覆盖「小文件/垃圾文件」清理的常见目标）。 */
    val RECOMMENDED_SUFFIXES: List<String> = listOf(
        "txt", "jpg", "jpeg", "png", "gif", "bmp", "webp",
        "nfo", "sfv", "url", "lnk", "db", "ini", "log",
        "html", "htm", "xml", "json", "md"
    )

    /** 内置推荐关键词（广告/水印/推广等常见的可清理片段）。 */
    val RECOMMENDED_KEYWORDS: List<String> = listOf(
        "广告", "推广", "宣传", "加群", "微信", "QQ群", "扫码",
        "试看", "免费", "最新地址", "发布页", "收藏地址",
        "hhd800", "2048", "sehuatang", "btbtt", "javbus"
    )
}
