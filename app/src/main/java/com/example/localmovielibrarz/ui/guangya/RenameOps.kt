package com.example.localmovielibrarz.ui.guangya

import com.example.localmovielibrarz.cloudguangya.GuangyaFileItem
import org.json.JSONObject
import java.util.Locale

/**
 * 批量重命名 v3：四类操作（删除/替换、插入、全新命名、自定义正则）+ 正则增强 + 模板。
 * 纯函数实现，便于单测；所有「位置」语义均为 1 基（第 1 位 = 首字符）。
 * 语义示例统一用主名 "ABCDEF-123"（共 10 位）。
 */

// ==================== 配置模型 ====================

/** 批量重命名配置（四类操作统一入口）。 */
sealed interface BatchRenameConfig {
    /** 删除/替换字符：replacement 为空 = 纯删除；xxAsRegex=true 时 XX 按正则解释、替换为支持 $ 分组。 */
    data class DeleteReplace(
        val range: RenameDeleteRange,
        val replacement: String = "",
        val xxAsRegex: Boolean = false
    ) : BatchRenameConfig

    /** 新增/插入字符。 */
    data class Insert(
        val position: RenameInsertPosition,
        val text: String,
        val markerAsRegex: Boolean = false
    ) : BatchRenameConfig

    /** 全新命名：{name}=原主名，{n}=序号（startNumber 起、digits 位补零）。 */
    data class NewNaming(
        val template: String,
        val startNumber: Int = 1,
        val digits: Int = 2
    ) : BatchRenameConfig

    /** 自定义正则查找替换（完全自由 pattern，replacement 支持 $1/$2 分组）。 */
    data class CustomRegex(
        val pattern: String,
        val replacement: String,
        val ignoreCase: Boolean = false,
        val firstOnly: Boolean = false,
        val applyToExtension: Boolean = false
    ) : BatchRenameConfig
}

// ============ 删除/替换范围（示例主名 ABCDEF-123） ============

sealed interface RenameDeleteRange {
    /** 指定字符串 XX（字面模式：所有出现处；正则模式：XX 即 pattern）。XX="DEF" → ABC-123 */
    data class SpecifiedString(val text: String) : RenameDeleteRange

    /** 前 N 个字符。N=6 → -123 */
    data class FirstN(val n: Int) : RenameDeleteRange

    /** 后 N 个字符。N=3 → ABCDEF- */
    data class LastN(val n: Int) : RenameDeleteRange

    /** 第 M 位之后的 N 个字符。M=6,N=4 → 删第 7~10 位("-123") → ABCDEF */
    data class AfterPositionMN(val m: Int, val n: Int) : RenameDeleteRange

    /** 倒数第 M 位之前的 N 个字符（不含倒数第 M 位本身）。M=4,N=2 → 倒数第4位是"-"，删其前"EF" → ABCD-123 */
    data class BeforeEndPositionMN(val m: Int, val n: Int) : RenameDeleteRange

    /** XX 之后的全部字符。XX="-" → ABCDEF-（includeMarker=true 连 XX 一起删 → ABCDEF） */
    data class AfterStringAll(val text: String, val includeMarker: Boolean = false) : RenameDeleteRange

    /** XX 之前的全部字符。XX="-" → -123（includeMarker=true → 123） */
    data class BeforeStringAll(val text: String, val includeMarker: Boolean = false) : RenameDeleteRange

    /** XX 之后的 N 个字符。XX="-",N=2 → ABCDEF-3 */
    data class AfterStringN(val text: String, val n: Int, val includeMarker: Boolean = false) : RenameDeleteRange
}

// ==================== 插入位置（示例插入文本 "[HD]"） ====================

sealed interface RenameInsertPosition {
    /** 开头 → [HD]ABCDEF-123 */
    data object Start : RenameInsertPosition
    /** 末尾 → ABCDEF-123[HD] */
    data object End : RenameInsertPosition
    /** 第 N 个字符之后。N=6 → ABCDEF[HD]-123 */
    data class AfterNth(val n: Int) : RenameInsertPosition
    /** 倒数第 N 个字符之前。N=3 → ABCDEF-[HD]123 */
    data class BeforeLastNth(val n: Int) : RenameInsertPosition
    /** XX 字符串之后（字面模式：第一处；正则模式：每一处命中）。XX="-" → ABCDEF-[HD]123 */
    data class AfterString(val text: String) : RenameInsertPosition
    /** XX 字符串之前。XX="-" → ABCDEF[HD]-123 */
    data class BeforeString(val text: String) : RenameInsertPosition
}

// ==================== 纯函数实现 ====================

object RenameOps {

    val ILLEGAL_NAME_CHARS = charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')

    /** 统一分发：xxAsRegex=false 走字面实现，true 走自动编译的正则实现。 */
    fun deleteOrReplace(
        base: String,
        range: RenameDeleteRange,
        replacement: String,
        xxAsRegex: Boolean = false
    ): String? {
        if (replacement.any { it in ILLEGAL_NAME_CHARS }) return null
        return if (xxAsRegex) regexDeleteOrReplace(base, range, replacement)
        else literalDeleteOrReplace(base, range, replacement)
    }

    /** 统一分发：markerAsRegex 仅对 XX 定位类位置有意义，纯位置类两模式行为一致。 */
    fun insert(
        base: String,
        position: RenameInsertPosition,
        text: String,
        markerAsRegex: Boolean = false
    ): String? {
        if (text.isEmpty() || text.any { it in ILLEGAL_NAME_CHARS }) return null
        return if (markerAsRegex) regexInsert(base, position, text)
        else literalInsert(base, position, text)
    }

    // ---------------- 字面实现 ----------------

    private fun literalDeleteOrReplace(base: String, range: RenameDeleteRange, replacement: String): String? {
        /** 处理 base 的 [start,end) 区间：start 越界=参数错误返回 null；end 自动收敛。 */
        fun cut(start: Int, end: Int): String? {
            if (start < 0 || start > base.length) return null
            val e = end.coerceIn(start, base.length)
            return base.replaceRange(start, e, replacement)
        }
        return when (range) {
            is RenameDeleteRange.SpecifiedString ->
                if (range.text.isEmpty()) null else base.replace(range.text, replacement)

            is RenameDeleteRange.FirstN ->
                if (range.n <= 0) null else cut(0, range.n)

            is RenameDeleteRange.LastN ->
                if (range.n <= 0) null else cut(base.length - range.n, base.length)

            is RenameDeleteRange.AfterPositionMN ->
                if (range.m < 0 || range.n <= 0) null else cut(range.m, range.m + range.n)

            is RenameDeleteRange.BeforeEndPositionMN ->
                if (range.m <= 0 || range.m > base.length || range.n <= 0) null
                else cut(base.length - range.m - range.n, base.length - range.m)

            is RenameDeleteRange.AfterStringAll -> {
                val idx = base.indexOf(range.text)
                when {
                    range.text.isEmpty() -> null
                    idx < 0 -> base
                    else -> cut(if (range.includeMarker) idx else idx + range.text.length, base.length)
                }
            }

            is RenameDeleteRange.BeforeStringAll -> {
                val idx = base.indexOf(range.text)
                when {
                    range.text.isEmpty() -> null
                    idx < 0 -> base
                    else -> cut(0, if (range.includeMarker) idx + range.text.length else idx)
                }
            }

            is RenameDeleteRange.AfterStringN -> {
                val idx = base.indexOf(range.text)
                when {
                    range.text.isEmpty() || range.n <= 0 -> null
                    idx < 0 -> base
                    else -> {
                        val start = if (range.includeMarker) idx else idx + range.text.length
                        cut(start, start + range.n)
                    }
                }
            }
        }
    }

    private fun literalInsert(base: String, position: RenameInsertPosition, text: String): String? {
        val at: Int = when (position) {
            RenameInsertPosition.Start -> 0
            RenameInsertPosition.End -> base.length
            is RenameInsertPosition.AfterNth -> position.n.coerceIn(0, base.length)
            is RenameInsertPosition.BeforeLastNth -> (base.length - position.n).coerceIn(0, base.length)
            is RenameInsertPosition.AfterString ->
                if (position.text.isEmpty()) return null
                else base.indexOf(position.text).takeIf { it >= 0 }
                    ?.let { it + position.text.length } ?: return base
            is RenameInsertPosition.BeforeString ->
                if (position.text.isEmpty()) return null
                else base.indexOf(position.text).takeIf { it >= 0 } ?: return base
        }
        return StringBuilder(base).insert(at, text).toString()
    }

    // ---------------- 正则实现（下拉选项自动编译为 pattern） ----------------

    /**
     * 分组约定（正则模式）：
     * - SpecifiedString：$1.. = 用户在 XX 中自己写的分组；
     * - FirstN/LastN/AfterPositionMN/BeforeEndPositionMN：$1 = 被替换的目标字符；
     * - AfterStringAll：pattern=(XX)[\s\S]*，$1 = XX 匹配内容（替换为 $1 = 保留 XX 删其后，留空 = 连 XX 一起删）；
     * - BeforeStringAll：pattern=[\s\S]*?(XX)，$1 = XX 匹配内容（替换为 $1 = 删其前保留 XX，留空 = 连 XX 一起删）；
     * - AfterStringN：pattern=(XX)([\s\S]{1,N})，$1 = XX，$2 = 其后 N 个字符。
     * XX 定位类对每一处命中生效（字面模式只作用于第一处）。
     */
    private fun regexDeleteOrReplace(base: String, range: RenameDeleteRange, replacement: String): String? {
        // runCatching 同时兜底「pattern 非法」与「replacement 引用了不存在的分组」
        fun apply(pattern: String): String? = runCatching {
            Regex(pattern).replace(base, replacement)
        }.getOrNull()

        return when (range) {
            is RenameDeleteRange.SpecifiedString ->
                if (range.text.isEmpty()) null else apply(range.text)

            is RenameDeleteRange.FirstN ->
                if (range.n <= 0) null else apply("^([\\s\\S]{1,${range.n}})")

            is RenameDeleteRange.LastN ->
                if (range.n <= 0) null else apply("([\\s\\S]{1,${range.n}})\$")

            is RenameDeleteRange.AfterPositionMN ->
                if (range.m < 0 || range.n <= 0) null
                else apply("(?<=^[\\s\\S]{${range.m}})([\\s\\S]{1,${range.n}})")

            is RenameDeleteRange.BeforeEndPositionMN ->
                if (range.m <= 0 || range.n <= 0) null
                else apply("([\\s\\S]{1,${range.n}})(?=[\\s\\S]{${range.m}}\$)")

            is RenameDeleteRange.AfterStringAll ->
                if (range.text.isEmpty()) null else apply("(${range.text})[\\s\\S]*")

            is RenameDeleteRange.BeforeStringAll ->
                if (range.text.isEmpty()) null else apply("[\\s\\S]*?(${range.text})")

            is RenameDeleteRange.AfterStringN ->
                if (range.text.isEmpty() || range.n <= 0) null
                else apply("(${range.text})([\\s\\S]{1,${range.n}})")
        }
    }

    private fun regexInsert(base: String, position: RenameInsertPosition, text: String): String? {
        // 插入文本中的 $ 需转义为 $$，避免被当作分组引用
        val safeText = text.replace("$", "$$")
        return when (position) {
            // 纯位置类：正则无增益，直接字面处理
            RenameInsertPosition.Start, RenameInsertPosition.End,
            is RenameInsertPosition.AfterNth, is RenameInsertPosition.BeforeLastNth ->
                literalInsert(base, position, text)

            is RenameInsertPosition.AfterString ->
                if (position.text.isEmpty()) null
                else runCatching {
                    Regex("(${position.text})").replace(base, "$1" + safeText)
                }.getOrNull()

            is RenameInsertPosition.BeforeString ->
                if (position.text.isEmpty()) null
                else runCatching {
                    Regex("(${position.text})").replace(base, safeText + "$1")
                }.getOrNull()
        }
    }

    // ---------------- 全新命名 / 自定义正则 ----------------

    /** 全新命名模板。{name}=原主名，{n}=序号。 */
    fun fromTemplate(template: String, base: String, index: Int, startNumber: Int, digits: Int): String? {
        val t = template.trim()
        if (t.isEmpty() || t.any { it in ILLEGAL_NAME_CHARS }) return null
        if (startNumber < 0 || digits !in 1..6) return null
        val seq = String.format(Locale.ROOT, "%0${digits}d", startNumber + index)
        return t.replace("{n}", seq).replace("{name}", base).trim().ifEmpty { null }
    }

    /** 自定义正则替换。pattern 为空或非法、replacement 引用不存在分组 → null。 */
    fun regexReplace(
        base: String,
        pattern: String,
        replacement: String,
        ignoreCase: Boolean,
        firstOnly: Boolean
    ): String? {
        if (pattern.isEmpty()) return null
        if (replacement.any { it in ILLEGAL_NAME_CHARS }) return null   // ★ 新增：堵住 P1-C 漏洞
        val regex = runCatching {
            Regex(pattern, if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet())
        }.getOrNull() ?: return null
        return runCatching {
            if (firstOnly) regex.replaceFirst(base, replacement) else regex.replace(base, replacement)
        }.getOrNull()
    }
}

// ==================== 批量结果计算 ====================

/**
 * names：与 files 一一对应的新名列表（元素可空，跳过项为 null）。
 * 全部失败时 names = null。
 * ⚠️ names 元素类型由 String 变为 String?，属破坏性变更，见指南兼容性说明。
 *
 * ★ 需求 4 修复：批内重名不再 fail-fast 返回 null，而改为**与执行层同源**的自动消解
 *   （见 [BatchNameResolver]）。理由：删除同一字符串导致「多个文件收敛成同名」是**正常且常见**
 *   的重命名结果，旧行为直接报「生成结果存在重名」并拒绝执行，用户完全无法理解
 *   （「去掉的都是一样的字符串，去之前没重名，去掉就重名了？」）。现在预览即给出最终名，
 *   与执行结果一字不差。
 */
data class BatchRenameResult(
    val names: List<String?>?,
    val error: String?,
    val skippedIndices: Set<Int> = emptySet(),
    val skippedReasons: Map<Int, String> = emptyMap(),
    /** ★ 需求 4：因撞名被自动加序号的项数（预览与执行同一口径）。 */
    val autoSuffixedIndices: Set<Int> = emptySet()
)

/**
 * 对选中文件统一应用配置，自动保留扩展名（自定义正则的 applyToExtension=true 除外）。
 * 校验链：入口参数合法性 → 结果非空 →（非法字符已在 RenameOps 入口拦截，继承字符放行）→ 批内消解。
 * startIndex：P2 多规则场景下 NewNaming {n} 序号的全局偏移，单规则路径保持默认 0（行为不变）。
 *
 * @param dirExistingNames ★ 需求 4：目录**既有**文件名（已剔除本批将改走的旧名）。
 *   预览层应传当前目录的文件名集合，使预览与执行得出完全一致的结果。
 *   缺省空集 = 不感知目录冲突（纯批内消解），保持旧调用点可编译且行为可预期。
 */
fun buildBatchRenameNames(
    files: List<GuangyaFileItem>,
    config: BatchRenameConfig,
    startIndex: Int = 0,
    dirExistingNames: Collection<String> = emptyList()
): BatchRenameResult {
    val names = mutableListOf<String?>()
    val skippedReasons = mutableMapOf<Int, String>()

    files.forEachIndexed { index, item ->
        val dot = item.name.lastIndexOf('.')
        val hasExt = dot > 0
        val base = if (hasExt) item.name.substring(0, dot) else item.name
        val ext = if (hasExt) item.name.substring(dot) else ""

        /** ★ 第十轮（C-1）：先 trim 空格，再清首尾 '-'/'.' 分隔符残留（服务端拒绝 "-." 开头等形态），
         *  再 trim（清理 "-. " 混合残留的余空格）；清理后为空走「生成结果为空」跳过分支 */
        fun composeName(stem: String?): String? {
            if (stem == null) return null
            val cleaned = stem.trim().trim('-', '.').trim()
            return if (cleaned.isEmpty()) "" else cleaned + ext
        }

        val newFullName: String? = when (config) {
            is BatchRenameConfig.DeleteReplace ->
                composeName(RenameOps.deleteOrReplace(base, config.range, config.replacement, config.xxAsRegex))

            is BatchRenameConfig.Insert ->
                composeName(RenameOps.insert(base, config.position, config.text, config.markerAsRegex))

            is BatchRenameConfig.NewNaming ->
                composeName(RenameOps.fromTemplate(config.template, base, startIndex + index, config.startNumber, config.digits))

            is BatchRenameConfig.CustomRegex ->
                if (config.applyToExtension) {
                    // 整名替换：同样净化（C-1 同步）；净化后为空命中 isBlank 跳过，null（正则非法）保持 null
                    RenameOps.regexReplace(
                        item.name, config.pattern, config.replacement, config.ignoreCase, config.firstOnly
                    )?.trim()?.trim('-', '.')?.trim()
                } else {
                    composeName(RenameOps.regexReplace(
                        base, config.pattern, config.replacement, config.ignoreCase, config.firstOnly
                    ))
                }
        }

        when {
            newFullName == null ->
                { skippedReasons[index] = "规则参数不合法（N/M/XX/正则输入有误）"; names += null }
            newFullName.isBlank() ->
                { skippedReasons[index] = "生成结果为空"; names += null }
            else -> names += newFullName
            // 非法字符不再整名校验：用户输入已在 RenameOps 入口拦截，
            // 剩余非法字符继承自原文件名（服务端存量数据已含这些字符），原样放行。
        }
    }

    // 全部失败才返回 null
    if (skippedReasons.size == files.size) {
        return BatchRenameResult(
            null, "所有文件均无法重命名，请检查规则参数", skippedReasons.keys, skippedReasons
        )
    }

    // ★ 需求 4 修复（B2）：预览层做与执行层完全同源的批内消解，杜绝「所见非所得」。
    // 只对「有效新名」消解，跳过项保持 null；消解后回填到原索引。
    // ⚠️ 顺序必须与执行层一致：统一经 [sortedForResolve]（按旧名 + fileId），
    //    否则「收敛同名」的多个文件会在两侧拿到互换的序号。
    val validIndices = names.indices.filter { names[it] != null }
    val orderedEntries = sortedForResolve(
        validIndices.map { idx -> Triple(files[idx].fileId, files[idx].name, names[idx]!!) }
    )
    val resolved = BatchNameResolver.resolveWithFlags(orderedEntries, dirExistingNames)
        .associateBy { it.fileId }

    val finalNames = names.toMutableList()
    val autoSuffixed = mutableSetOf<Int>()
    validIndices.forEach { idx ->
        val r = resolved[files[idx].fileId] ?: return@forEach
        finalNames[idx] = r.final
        if (r.autoSuffixed) autoSuffixed += idx
    }

    val baseError = if (skippedReasons.isEmpty()) null
        else "已跳过 ${skippedReasons.size} 个文件（${skippedReasons.values.take(3).joinToString("、")}）"
    val dupNote = if (autoSuffixed.isNotEmpty())
        "；其中 ${autoSuffixed.size} 项与已有文件重名，将自动加序号" else null

    return BatchRenameResult(
        finalNames.toList(),
        baseError?.plus(dupNote.orEmpty()) ?: dupNote,
        skippedReasons.keys,
        skippedReasons,
        autoSuffixed
    )
}

// ==================== 生成名完整校验（批次 6b，对齐参考实现 validateGuangyaName） ====================

/** Windows 保留名（带或不带扩展名均不可用）。 */
private val WINDOWS_RESERVED_NAMES: Set<String> =
    setOf("CON", "PRN", "AUX", "NUL") +
        (1..9).map { "COM$it" } +
        (1..9).map { "LPT$it" }

/**
 * 校验「新生成的问题名」：返回 null 表示合法，否则返回拒绝原因。
 * 保持第六轮「继承自原名的非法字符放行」决策——本函数只拦新生成名，
 * 服务端存量数据里已有的非法字符（原样保留的文件）不在此列。
 *
 * ★ 需求 4 补强（E-1）：空格校验从「仅首末字符」升级为「首末 + 扩展名分隔点前后」。
 *   旧实现 `startsWith(' ') || endsWith(' ')` 会漏掉 `"Video .mp4"` 这类
 *   「空格紧贴扩展名点」的形态——该形态服务端一律拒绝（报「文件名不能…」），
 *   但本地放行后会在执行阶段整批失败，属于真实的防御缺口。
 */
fun validateGeneratedName(name: String): String? {
    if (name.isBlank()) return "名称为空"
    if (name.length > 255) return "名称超过 255 字符"
    if (name.startsWith(' ') || name.endsWith(' ')) return "名称不能以空格开头或结尾"
    if (name.startsWith('-')) return "名称不能以 - 开头" // ★ C-2：纵深防御（composeName 已净化，此为兜底）
    if (name.startsWith('.') || name.endsWith('.')) return "名称不能以点开头或结尾"
    if (name.contains("..")) return "名称不能包含连续点"
    // ★ E-1：点前后紧邻空格（"Video .mp4" / "Video. mp4"）同样非法。
    //   放在「连续点/首末点」之后，保证既有用例的错误文案优先级不变；
    //   仅在确实存在扩展名分隔点的形态下判罚。
    if (name.contains(" .") || name.contains(". ")) return "扩展名分隔点前后不能有空格"
    if (name.any { it.isISOControl() }) return "名称包含控制字符"
    if (name.any { it in RenameOps.ILLEGAL_NAME_CHARS }) return "名称包含非法字符"
    val base = name.substringBeforeLast('.', name).uppercase(Locale.ROOT)
    if (base in WINDOWS_RESERVED_NAMES) return "名称是系统保留名"
    return null
}

/** ★ A-2：撞名判定键——NFC 规范化 + 小写 + trim（对齐参考实现 renameNameKey）。 */
fun String.renameNameKey(): String =
    java.text.Normalizer.normalize(trim(), java.text.Normalizer.Form.NFC).lowercase(Locale.ROOT)

/**
 * G-3②：不可见空格显形——把名字里的空格渲染为可见符号 ␠，用于失败项可视化诊断。
 * B 类失败（展示名看似无空格，首尾空格在 UI 上不可见）经此即可一眼定位。
 */
fun visualizeInvisibleChars(name: String): String = name.replace(' ', '␠')

/** 重名 auto-suffix：与目录内既有文件撞名时补 " (n)"，返回最终不冲突名（防御上限 1000，极端兜底加用户可辨后缀）。 */
fun dedupeNameAgainst(candidate: String, existingNames: Set<String>): String {
    val existingKeys = existingNames.mapTo(HashSet()) { it.renameNameKey() }
    if (candidate.renameNameKey() !in existingKeys) return candidate
    val ext = candidate.substringAfterLast('.', "")
    val stem = if (ext.isNotEmpty()) candidate.substringBeforeLast('.') else candidate
    var n = 1
    while (n <= 1000) {
        val next = if (ext.isNotEmpty()) "${stem} ($n).$ext" else "$stem ($n)"
        if (next.renameNameKey() !in existingKeys) return next
        n++
    }
    return "${stem}-dup-$n$ext" // 理论上不可达（1000 个冲突名），纯防御
}

// ==================== 批内重名消解（需求 4 修复核心） ====================

/**
 * 批量重命名的**唯一名消解器**——预览层与执行层共用，保证「所见即所得」。
 *
 * 为什么需要它（需求 4 的根因）：
 * 旧实现把两条语义完全不同的重名来源塞进同一个 Set：
 *   (a) 目录里**已存在**的文件名 —— 撞上才真该加 (1)；
 *   (b) 本批**同伴刚生成的新名** —— 撞上只说明「两个文件收敛成同名」，属批内问题，
 *       绝不该拿同伴的新名去给彼此加序号（旧代码正是这么做的，于是 N 个文件删同一字符串
 *       会得到 `A.mp4`、`A (1).mp4`、`A (2).mp4`… 用户看到的就是「怎么可能重名？」）。
 *
 * 正确模型：先用「目录既有名」算一次基准冲突，再对批内**收敛同名**按稳定顺序（文件旧名 →
 * fileId）分配序号。全部逻辑集中在此，避免预览/执行两处各写一套导致漂移。
 */
object BatchNameResolver {

    /** 加序号后的名字形态：stem (n).ext / stem (n)。 */
    private fun suffixed(candidate: String, n: Int): String {
        val ext = candidate.substringAfterLast('.', "")
        val stem = if (ext.isNotEmpty()) candidate.substringBeforeLast('.') else candidate
        return if (ext.isNotEmpty()) "$stem ($n).$ext" else "$stem ($n)"
    }

    /**
     * 计算一批重命名的最终名，并区分「目录冲突」与「批内冲突」两类自动加序号。
     *
     * ⚠️ **确定性契约（务必遵守，否则预览与执行会给出不同序号分配）**：
     * 本函数的加序号优先级 = `entries` 的传入顺序。因此预览层与执行层**必须传入相同顺序的 entries**。
     * 约定：统一按 `(旧文件名 renameNameKey, fileId)` 升序排列后再传入——旧名是批次的天然稳定键，
     * 且预览/执行两侧都拿得到，不会随 UI 排序策略漂移。
     * 推荐直接用 [sortedForResolve] 完成排序，避免各处手写排序导致不一致。
     *
     * @param entries     (fileId, 期望新名)；顺序即加序号优先级
     * @param dirExisting 目录**既有**文件名（调用方须先剔除所有将被本批改走的旧名）
     * @return 按 entries 顺序一一对应的最终名列表
     */
    fun resolve(
        entries: List<Pair<String, String>>,
        dirExisting: Collection<String>
    ): List<String> {
        val dirKeys = dirExisting.mapTo(HashSet()) { it.renameNameKey() }
        // 批内已占用的 key → 首次占用者（用于判定是否需要消解）
        val batchClaimed = HashSet<String>()
        val result = ArrayList<String>(entries.size)

        // 加序号优先级完全由 entries 的传入顺序决定（首个占用者保持干净名）。
        // 调用方须先经 [sortedForResolve] 统一排序，见上方「确定性契约」。
        entries.forEach { (_, want) ->
            var final = want
            val wantedKey = want.renameNameKey()
            val hitDir = wantedKey in dirKeys
            val hitBatch = wantedKey in batchClaimed

            if (hitDir || hitBatch) {
                // 已冲突：从 n=1 起找第一个既不在目录也不在批内已占用的名字
                var n = 1
                while (n <= 1000) {
                    val next = suffixed(want, n)
                    val nextKey = next.renameNameKey()
                    if (nextKey !in dirKeys && nextKey !in batchClaimed) { final = next; break }
                    n++
                }
                if (n > 1000) final = "${want.substringBeforeLast('.', want)}-dup-$n" +
                    want.substringAfterLast('.', "").let { if (it.isNotEmpty()) ".$it" else "" }
            }
            batchClaimed.add(final.renameNameKey())
            result.add(final)
        }
        return result
    }

    /**
     * 把一批 (fileId, 期望新名) 归一为「无批内重名」的最终名，并回报每条是否被自动加了序号。
     * 单文件或本就无冲突时返回原样，零开销。
     */
    fun resolveWithFlags(
        entries: List<Pair<String, String>>,
        dirExisting: Collection<String>
    ): List<ResolvedName> {
        val resolved = resolve(entries, dirExisting)
        return entries.mapIndexed { i, (fileId, want) ->
            ResolvedName(fileId, want, resolved[i], resolved[i] != want)
        }
    }
}

/** 单条消解结果：want 是期望名，final 是实际生效名，autoSuffixed 表示是否因冲突被加序号。 */
data class ResolvedName(
    val fileId: String,
    val want: String,
    val final: String,
    val autoSuffixed: Boolean
)

/**
 * 消解顺序的唯一约定：按 (旧名 renameNameKey, fileId) 升序。
 * 预览层与执行层都必须经由此函数排序，否则同一批「收敛同名」的文件
 * 会因两侧初始顺序不同而拿到互换的序号（预览说 A 是 A.mp4、执行却把 A 变成 A (1).mp4）。
 */
fun sortedForResolve(entries: List<Triple<String, String, String>>): List<Pair<String, String>> =
    entries.sortedWith(compareBy({ it.second.renameNameKey() }, { it.first }))
        .map { it.first to it.third }

// ==================== 多规则批量重命名（P2） ====================

/**
 * 一条重命名规则：绑定一个 BatchRenameConfig + 目标文件匹配条件。
 * matchMode 决定哪些文件会被这条规则处理。
 */
data class RenameRuleEntry(
    val id: Long = nextRuleId(),   // v6：原子自增，避免同毫秒重复
    val config: BatchRenameConfig,
    val matchMode: RuleMatchMode,
    val enabled: Boolean = true
) {
    companion object {
        private val ID = java.util.concurrent.atomic.AtomicLong(0)
        fun nextRuleId(): Long = ID.incrementAndGet()
    }
}

/**
 * 规则匹配模式：
 * - All: 匹配所有选中文件（默认，兼容现有行为）
 * - Contains: 文件名包含指定关键词时匹配
 * - RegexPattern: 文件名匹配正则时匹配
 * - Manual: 用户手动勾选的文件 ID 集合（首版 UI 不暴露，待补文件选择器后开放）
 */
sealed interface RuleMatchMode {
    data object All : RuleMatchMode
    data class Contains(val keyword: String) : RuleMatchMode
    data class RegexPattern(val pattern: String) : RuleMatchMode
    data class Manual(val fileIds: Set<String>) : RuleMatchMode
}

/**
 * 多规则批量重命名结果。
 * skippedFiles 与 unassignedFiles 互斥（v6：attempted 标记保证一个文件最多归入其一）。
 */
data class MultiRuleBatchResult(
    val renamePairs: List<Pair<GuangyaFileItem, String>>,  // (文件, 新名)
    val skippedFiles: List<Pair<GuangyaFileItem, String>>,  // (文件, 跳过原因)
    val unassignedFiles: List<GuangyaFileItem>,             // 未被任何规则尝试匹配的文件
    val errors: List<String>
)

/**
 * 多规则批量重命名：按规则顺序逐条匹配文件并计算新名。
 * 已被某条规则尝试过（无论成败）的文件不会被后续规则重复处理。
 * 合并后做跨规则重名校验，重名则整体失败（重名多为规则设计问题，静默跳过会造成意外覆盖）。
 */
fun buildMultiRuleRenameNames(
    files: List<GuangyaFileItem>,
    rules: List<RenameRuleEntry>
): MultiRuleBatchResult {
    val renamePairs = mutableListOf<Pair<GuangyaFileItem, String>>()
    val skippedFiles = mutableListOf<Pair<GuangyaFileItem, String>>()
    val attempted = mutableSetOf<String>()   // v6：含失败项，保证 skipped/unassigned 互斥
    val errors = mutableListOf<String>()
    var namingOffset = 0                     // v6：NewNaming {n} 序号跨规则接续

    for (rule in rules.filter { it.enabled }) {
        val targets = files.filter { item ->
            item.fileId !in attempted && matchRule(item, rule.matchMode)
        }
        if (targets.isEmpty()) continue

        // NewNaming 序号全局接续：规则 1 编到 05，规则 2 从 06 开始
        val result = buildBatchRenameNames(targets, rule.config, startIndex = namingOffset)
        if (rule.config is BatchRenameConfig.NewNaming) {
            namingOffset += targets.size
        }

        val names = result.names
        if (names == null) {
            errors.add("规则「${describeRule(rule)}」失败：${result.error}")
            // 规则整体失败：这些文件标记为已尝试并计入跳过（v6：同时加入 attempted，
            // 修复 v5 中它们又被重复计入 unassignedFiles 的双重计数问题）
            targets.forEach { target ->
                attempted.add(target.fileId)
                skippedFiles.add(target to "规则失败：${result.error}")
            }
            continue
        }

        targets.zip(names).forEach { (item, name) ->
            attempted.add(item.fileId)       // v6：失败项也标记，防止后续规则重复处理
            if (name != null) {
                renamePairs.add(item to name)
            } else {
                skippedFiles.add(item to "生成失败")
            }
        }
    }

    // v6：跨规则重名校验（各规则内部重名已在 buildBatchRenameNames 中 fail-fast）
    val allNames = renamePairs.map { it.second }
    if (allNames.toSet().size != allNames.size) {
        return MultiRuleBatchResult(
            emptyList(),
            skippedFiles,
            files.filter { it.fileId !in attempted },
            errors + "不同规则生成了相同的新文件名：请调整匹配条件，或为各规则的 {n} 检查起始序号/接续设置"
        )
    }

    val unassigned = files.filter { it.fileId !in attempted }
    return MultiRuleBatchResult(renamePairs, skippedFiles, unassigned, errors)
}

/** 判断文件是否匹配规则的目标条件。 */
private fun matchRule(item: GuangyaFileItem, mode: RuleMatchMode): Boolean = when (mode) {
    is RuleMatchMode.All -> true
    is RuleMatchMode.Contains -> item.name.contains(mode.keyword, ignoreCase = true)
    is RuleMatchMode.RegexPattern -> runCatching {
        Regex(mode.pattern).containsMatchIn(item.name)
    }.getOrDefault(false)
    is RuleMatchMode.Manual -> item.fileId in mode.fileIds
}

/** 规则类型的可读描述（供 UI 展示）。 */
fun describeRule(rule: RenameRuleEntry): String {
    return when (rule.config) {
        is BatchRenameConfig.DeleteReplace -> "删除/替换"
        is BatchRenameConfig.Insert -> "插入"
        is BatchRenameConfig.NewNaming -> "全新命名"
        is BatchRenameConfig.CustomRegex -> "自定义正则"
    }
}

/** 匹配模式的可读描述（供 UI 展示）。 */
fun describeMatchMode(mode: RuleMatchMode): String = when (mode) {
    RuleMatchMode.All -> "全部选中文件"
    is RuleMatchMode.Contains -> "文件名包含「${mode.keyword}」"
    is RuleMatchMode.RegexPattern -> "文件名匹配正则 /${mode.pattern}/"
    is RuleMatchMode.Manual -> "手动指定 ${mode.fileIds.size} 个文件"
}

// ==================== 模板（全操作类型） ====================

/**
 * 批量重命名模板：镜像对话框表单的扁平参数集，JSON 序列化存 SharedPreferences StringSet。
 * opType：0=删除/替换 1=插入 2=全新命名 3=自定义正则。
 */
data class BatchRenameTemplate(
    val id: Long,                    // System.currentTimeMillis()
    val name: String,                // 展示名（保存时自动生成）
    val opType: Int,
    // —— 删除/替换 ——
    val rangeIndex: Int = 0,
    val xxText: String = "",
    val m: Int = 0,
    val n: Int = 0,
    val includeMarker: Boolean = false,
    val replacement: String = "",
    val xxAsRegex: Boolean = false,
    // —— 插入 ——
    val insertIndex: Int = 0,
    val insertText: String = "",
    val insertN: Int = 0,
    val insertMarker: String = "",
    val insertMarkerAsRegex: Boolean = false,
    // —— 全新命名 ——
    val namingTemplate: String = "{name}-{n}",
    val startNumber: Int = 1,
    val digits: Int = 2,
    // —— 自定义正则 ——
    val pattern: String = "",
    val regexReplacement: String = "",
    val ignoreCase: Boolean = false,
    val firstOnly: Boolean = false,
    val applyToExtension: Boolean = false
) {
    fun toJson(): String = JSONObject().apply {
        put("id", id); put("name", name); put("opType", opType)
        put("rangeIndex", rangeIndex); put("xxText", xxText); put("m", m); put("n", n)
        put("includeMarker", includeMarker); put("replacement", replacement); put("xxAsRegex", xxAsRegex)
        put("insertIndex", insertIndex); put("insertText", insertText); put("insertN", insertN)
        put("insertMarker", insertMarker); put("insertMarkerAsRegex", insertMarkerAsRegex)
        put("namingTemplate", namingTemplate); put("startNumber", startNumber); put("digits", digits)
        put("pattern", pattern); put("regexReplacement", regexReplacement)
        put("ignoreCase", ignoreCase); put("firstOnly", firstOnly); put("applyToExtension", applyToExtension)
    }.toString()

    companion object {
        fun fromJson(s: String): BatchRenameTemplate? = runCatching {
            val o = JSONObject(s)
            BatchRenameTemplate(
                id = o.optLong("id"), name = o.optString("name"), opType = o.optInt("opType"),
                rangeIndex = o.optInt("rangeIndex"), xxText = o.optString("xxText"),
                m = o.optInt("m"), n = o.optInt("n"),
                includeMarker = o.optBoolean("includeMarker"), replacement = o.optString("replacement"),
                xxAsRegex = o.optBoolean("xxAsRegex"),
                insertIndex = o.optInt("insertIndex"), insertText = o.optString("insertText"),
                insertN = o.optInt("insertN"), insertMarker = o.optString("insertMarker"),
                insertMarkerAsRegex = o.optBoolean("insertMarkerAsRegex"),
                namingTemplate = o.optString("namingTemplate", "{name}-{n}"),
                startNumber = o.optInt("startNumber", 1), digits = o.optInt("digits", 2),
                pattern = o.optString("pattern"), regexReplacement = o.optString("regexReplacement"),
                ignoreCase = o.optBoolean("ignoreCase"), firstOnly = o.optBoolean("firstOnly"),
                applyToExtension = o.optBoolean("applyToExtension")
            )
        }.getOrNull()
    }
}