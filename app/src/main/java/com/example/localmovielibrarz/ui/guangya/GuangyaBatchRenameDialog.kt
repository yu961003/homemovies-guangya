package com.example.localmovielibrarz.ui.guangya
import com.example.localmovielibrarz.ui.AppRadius

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.localmovielibrarz.cloudguangya.GuangyaFileItem
import com.example.localmovielibrarz.ui.settings.settingsTextFieldColors

private val DELETE_RANGE_OPTIONS = listOf(
    "指定字符串 XX", "前 N 个字符", "后 N 个字符",
    "第 M 位之后的 N 个字符", "倒数第 M 位之前的 N 个字符",
    "XX 之后的全部字符", "XX 之前的全部字符", "XX 之后的 N 个字符"
)

private val INSERT_POSITION_OPTIONS = listOf(
    "开头", "末尾", "第 N 个字符之后", "倒数第 N 个字符之前",
    "XX 字符串之后", "XX 字符串之前"
)

private val OP_TYPE_OPTIONS = listOf("删除/替换字符", "新增/插入字符", "全新命名", "自定义正则")

/**
 * 批量重命名弹窗 v3：四类操作统一入口；XX 定位类操作支持「按正则解释」+ $ 分组替换；
 * 全操作类型可存为模板并一键套用；实时预览 + 错误提示，预览合法才可确认。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatchRenameDialog(
    viewModel: GuangyaBrowserViewModel,
    selectedFiles: List<GuangyaFileItem>,
    onConfirm: (BatchRenameConfig) -> Unit,
    onMultiConfirm: (List<RenameRuleEntry>) -> Unit,
    onDismiss: () -> Unit
) {
    // ★ 第九轮（问题 1）：收集运行态——batchRenameRunning 期间禁用确认/取消并显示进度
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var opType by remember { mutableStateOf(0) }                 // 0 删除/替换 1 插入 2 全新命名 3 自定义正则
    // —— 删除/替换表单 ——
    var rangeIdx by remember { mutableStateOf(0) }
    var strXX by remember { mutableStateOf("") }
    var intM by remember { mutableStateOf("") }
    var intN by remember { mutableStateOf("") }
    var includeMarker by remember { mutableStateOf(false) }
    var replacement by remember { mutableStateOf("") }
    var xxAsRegex by remember { mutableStateOf(false) }
    // —— 插入表单 ——
    var insIdx by remember { mutableStateOf(0) }
    var insText by remember { mutableStateOf("") }
    var insN by remember { mutableStateOf("") }
    var insMarker by remember { mutableStateOf("") }
    var insMarkerAsRegex by remember { mutableStateOf(false) }
    // —— 全新命名 ——
    var template by remember { mutableStateOf("{name}-{n}") }
    var startNo by remember { mutableStateOf("1") }
    var digits by remember { mutableStateOf("2") }
    // —— 自定义正则 ——
    var pattern by remember { mutableStateOf("") }
    var regexReplacement by remember { mutableStateOf("") }
    var ignoreCase by remember { mutableStateOf(false) }
    var firstOnly by remember { mutableStateOf(false) }
    var applyToExt by remember { mutableStateOf(false) }
    // —— 模板 ——
    var templates by remember { mutableStateOf(viewModel.batchRenameTemplates()) }

    // —— 多规则队列（P2）——
    var ruleQueue by remember { mutableStateOf(listOf<RenameRuleEntry>()) }
    var matchModeIdx by remember { mutableStateOf(0) }  // 0=全部 1=包含 2=正则（v6：移除"手动"，首版不开放）
    var matchKeyword by remember { mutableStateOf("") }
    val MATCH_MODE_OPTIONS = listOf("全部选中文件", "文件名包含关键词", "文件名匹配正则")

    fun intOr(v: String): Int = v.trim().toIntOrNull() ?: Int.MIN_VALUE

    fun applyTemplate(t: BatchRenameTemplate) {
        opType = t.opType
        rangeIdx = t.rangeIndex; strXX = t.xxText
        intM = t.m.toString(); intN = t.n.toString()
        includeMarker = t.includeMarker; replacement = t.replacement; xxAsRegex = t.xxAsRegex
        insIdx = t.insertIndex; insText = t.insertText; insN = t.insertN.toString()
        insMarker = t.insertMarker; insMarkerAsRegex = t.insertMarkerAsRegex
        template = t.namingTemplate; startNo = t.startNumber.toString(); digits = t.digits.toString()
        pattern = t.pattern; regexReplacement = t.regexReplacement
        ignoreCase = t.ignoreCase; firstOnly = t.firstOnly; applyToExt = t.applyToExtension
    }

    fun currentTemplateName(): String = when (opType) {
        0 -> "删/替·" + when (rangeIdx) {
            0 -> "「${strXX.ifBlank { "?" }}」${if (xxAsRegex) "(正则)" else ""}"
            1 -> "前${intN.ifBlank { "?" }}字符"
            2 -> "后${intN.ifBlank { "?" }}字符"
            3 -> "第${intM.ifBlank { "?" }}位后${intN.ifBlank { "?" }}字符"
            4 -> "倒数${intM.ifBlank { "?" }}位前${intN.ifBlank { "?" }}字符"
            5 -> "「${strXX.ifBlank { "?" }}」后全部${if (includeMarker) "(含XX)" else ""}"
            6 -> "「${strXX.ifBlank { "?" }}」前全部${if (includeMarker) "(含XX)" else ""}"
            else -> "「${strXX.ifBlank { "?" }}」后${intN.ifBlank { "?" }}字符"
        } + if (replacement.isNotBlank()) "→$replacement" else ""
        1 -> "插入·${INSERT_POSITION_OPTIONS.getOrElse(insIdx) { "?" }}「${insText}」"
        2 -> "命名·$template"
        else -> "正则·${pattern.ifBlank { "?" }}→${regexReplacement.ifBlank { "(空)" }}"
    }

    fun currentTemplate(): BatchRenameTemplate = BatchRenameTemplate(
        id = System.currentTimeMillis(),
        name = currentTemplateName(),
        opType = opType,
        rangeIndex = rangeIdx, xxText = strXX, m = intOr(intM), n = intOr(intN),
        includeMarker = includeMarker, replacement = replacement, xxAsRegex = xxAsRegex,
        insertIndex = insIdx, insertText = insText, insertN = intOr(insN),
        insertMarker = insMarker, insertMarkerAsRegex = insMarkerAsRegex,
        namingTemplate = template, startNumber = intOr(startNo), digits = intOr(digits),
        pattern = pattern, regexReplacement = regexReplacement,
        ignoreCase = ignoreCase, firstOnly = firstOnly, applyToExtension = applyToExt
    )

    val config: BatchRenameConfig = when (opType) {
        0 -> {
            val range = when (rangeIdx) {
                0 -> RenameDeleteRange.SpecifiedString(strXX)
                1 -> RenameDeleteRange.FirstN(intOr(intN))
                2 -> RenameDeleteRange.LastN(intOr(intN))
                3 -> RenameDeleteRange.AfterPositionMN(intOr(intM), intOr(intN))
                4 -> RenameDeleteRange.BeforeEndPositionMN(intOr(intM), intOr(intN))
                5 -> RenameDeleteRange.AfterStringAll(strXX, includeMarker)
                6 -> RenameDeleteRange.BeforeStringAll(strXX, includeMarker)
                else -> RenameDeleteRange.AfterStringN(strXX, intOr(intN), includeMarker)
            }
            BatchRenameConfig.DeleteReplace(range, replacement, xxAsRegex)
        }
        1 -> {
            val pos = when (insIdx) {
                0 -> RenameInsertPosition.Start
                1 -> RenameInsertPosition.End
                2 -> RenameInsertPosition.AfterNth(intOr(insN))
                3 -> RenameInsertPosition.BeforeLastNth(intOr(insN))
                4 -> RenameInsertPosition.AfterString(insMarker)
                else -> RenameInsertPosition.BeforeString(insMarker)
            }
            BatchRenameConfig.Insert(pos, insText, insMarkerAsRegex)
        }
        2 -> BatchRenameConfig.NewNaming(template, intOr(startNo), intOr(digits))
        else -> BatchRenameConfig.CustomRegex(pattern, regexReplacement, ignoreCase, firstOnly, applyToExt)
    }

    // ★ 需求 4：预览必须与执行同源——传入「目录既有名（剔除本批将改走的旧名）」，
    // 使批内收敛同名的加序号行为在预览中即时可见，杜绝「所见非所得」。
    // 语义对齐 GuangyaBrowserRepository：dirExisting = 目录现有名 − 本批将改走的旧名。
    val dirExistingNames = remember(selectedFiles, state.files) {
        val selectedIds = selectedFiles.mapTo(HashSet()) { it.fileId }
        val renamingOldKeys = selectedFiles.mapTo(HashSet()) { it.name.renameNameKey() }
        state.files
            .filter { it.fileId !in selectedIds }
            .map { it.name }
            .filter { it.renameNameKey() !in renamingOldKeys }   // 与执行层同一套 renameNameKey 口径
    }
    val preview = remember(selectedFiles, config, dirExistingNames) {
        buildBatchRenameNames(selectedFiles, config, dirExistingNames = dirExistingNames)
    }
    // 只要至少有 1 个文件可重命名，就允许操作
    val hasValidNames = preview.names?.any { it != null } == true

    // 多规则合并预览（v6：remember 包裹，避免 200 文件 × 多正则时输入卡顿）
    val multiPreview = remember(selectedFiles, ruleQueue) {
        if (ruleQueue.isNotEmpty()) buildMultiRuleRenameNames(selectedFiles, ruleQueue)
        else null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("批量重命名（${selectedFiles.size} 项）") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // ===== 四类操作切换（可横滚） =====
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OP_TYPE_OPTIONS.forEachIndexed { i, label ->
                        FilterChip(
                            selected = opType == i,
                            onClick = { opType = i },
                            label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }

                when (opType) {
                    0 -> { // —— 删除/替换 ——
                        RenameOptionSelector("删除/替换范围", DELETE_RANGE_OPTIONS, rangeIdx) { rangeIdx = it }
                        when (rangeIdx) {
                            0 -> RenameTextField("字符串 XX", strXX) { strXX = it }
                            1, 2 -> RenameNumberField("N（字符个数）", intN) { intN = it }
                            3, 4 -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                RenameNumberField("M（位置）", intM, Modifier.weight(1f)) { intM = it }
                                RenameNumberField("N（个数）", intN, Modifier.weight(1f)) { intN = it }
                            }
                            5, 6 -> RenameTextField("字符串 XX", strXX) { strXX = it }
                            else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                RenameTextField("字符串 XX", strXX) { strXX = it }
                                RenameNumberField("N（字符个数）", intN) { intN = it }
                            }
                        }
                        if (rangeIdx in listOf(0, 5, 6, 7)) {
                            RenameSwitch("XX 按正则解释（替换为支持 \$1 分组）", xxAsRegex) { xxAsRegex = it }
                        }
                        if (rangeIdx in listOf(5, 6, 7) && !xxAsRegex) {
                            RenameSwitch("删除范围包含 XX 本身", includeMarker) { includeMarker = it }
                        }
                        RenameTextField("替换为（留空 = 纯删除）", replacement) { replacement = it }
                        if (xxAsRegex) {
                            Text(
                                text = when (rangeIdx) {
                                    0 -> "分组约定：\$1.. = 你在 XX 中写的分组。例：XX 填 \\[.*?\\]、替换为留空 → 删除所有中括号标记"
                                    5 -> "分组约定：\$1 = XX 匹配内容。替换为 \$1 = 保留 XX 删其后；留空 = 连 XX 一起删（对所有命中处生效）"
                                    6 -> "分组约定：\$1 = XX 匹配内容。替换为 \$1 = 删其前保留 XX；留空 = 连 XX 一起删"
                                    else -> "分组约定：\$1 = XX，\$2 = 其后 N 个字符。替换为 \$1新文本 = 保留 XX"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            )
                        }
                    }

                    1 -> { // —— 插入 ——
                        RenameOptionSelector("插入位置", INSERT_POSITION_OPTIONS, insIdx) { insIdx = it }
                        RenameTextField("插入文本", insText) { insText = it }
                        when (insIdx) {
                            2, 3 -> RenameNumberField("N（第/倒数第 N 个字符）", insN) { insN = it }
                            4, 5 -> RenameTextField("字符串 XX", insMarker) { insMarker = it }
                        }
                        if (insIdx in listOf(4, 5)) {
                            RenameSwitch("XX 按正则解释", insMarkerAsRegex) { insMarkerAsRegex = it }
                            Text(
                                "字面模式：只对第一处 XX 插入；正则模式：对每一处命中的 XX 插入。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            )
                        } else {
                            Text(
                                "按 XX 定位时，若文件名中未找到 XX 则保持原名不变。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            )
                        }
                    }

                    2 -> { // —— 全新命名 ——
                        RenameTextField("命名模板", template) { template = it }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            RenameNumberField("序号起始值", startNo, Modifier.weight(1f)) { startNo = it }
                            RenameNumberField("序号位数", digits, Modifier.weight(1f)) { digits = it }
                        }
                        Text(
                            "{name}=原主名（不含扩展名）　{n}=序号，默认 01、02…",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }

                    else -> { // —— 自定义正则 ——
                        RenameTextField("查找（正则表达式）", pattern) { pattern = it }
                        RenameTextField("替换为（支持 \$1、\$2 分组）", regexReplacement) { regexReplacement = it }
                        RenameSwitch("忽略大小写", ignoreCase) { ignoreCase = it }
                        RenameSwitch("仅替换第一处命中", firstOnly) { firstOnly = it }
                        RenameSwitch("扩展名一并纳入替换（默认只作用于主名）", applyToExt) { applyToExt = it }
                        Text(
                            "示例：查找 \\[(.*?)\\] 替换为（空）→ 删除所有中括号标记；查找 (\\w+)-(\\d+) 替换为 \$2-\$1 → 交换前后段。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }
                }

                // ===== 模板（全操作类型） =====
                if (templates.isNotEmpty()) {
                    Text(
                        "已保存模板（点选套用，点 ⨯ 删除）：",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.56f)
                    )
                    templates.forEach { t ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(
                                onClick = { applyTemplate(t) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    t.name,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                            TextButton(onClick = {
                                viewModel.deleteBatchRenameTemplate(t.id)
                                templates = viewModel.batchRenameTemplates()
                            }) { Text("⨯") }
                        }
                    }
                }
                OutlinedButton(
                    onClick = {
                        viewModel.saveBatchRenameTemplate(currentTemplate())
                        templates = viewModel.batchRenameTemplates()
                    },
                    shape = AppRadius.MD
                ) { Text("存为模板（当前设置）", style = MaterialTheme.typography.labelMedium) }

                // ===== 多规则队列（P2，可选） =====
                Text(
                    "多规则队列（可选）：配置当前规则后「添加到队列」，可对不同文件子集套用不同规则",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.56f)
                )
                // 匹配模式选择
                RenameOptionSelector("匹配模式", MATCH_MODE_OPTIONS, matchModeIdx) { matchModeIdx = it }
                if (matchModeIdx != 0) {
                    RenameTextField(if (matchModeIdx == 1) "关键词" else "正则表达式", matchKeyword) { matchKeyword = it }
                }
                OutlinedButton(
                    onClick = {
                        val mode = when (matchModeIdx) {
                            1 -> RuleMatchMode.Contains(matchKeyword)
                            2 -> RuleMatchMode.RegexPattern(matchKeyword)
                            else -> RuleMatchMode.All
                        }
                        ruleQueue = ruleQueue + RenameRuleEntry(config = config, matchMode = mode)
                    },
                    shape = AppRadius.MD
                ) { Text("+ 添加当前规则到队列", style = MaterialTheme.typography.labelMedium) }

                // 队列展示（key 用 rule.id，v6：原子自增保证唯一）
                ruleQueue.forEachIndexed { i, rule ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "规则${i + 1}: ${describeRule(rule)} · ${describeMatchMode(rule.matchMode)}",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        TextButton(onClick = { ruleQueue = ruleQueue.filterIndexed { j, _ -> j != i } }) {
                            Text("⨯")
                        }
                    }
                }

                // ===== 实时预览（单规则：部分成功 + 跳过提示） =====
                if (hasValidNames) {
                    val validNames = preview.names!!.mapIndexedNotNull { i, n -> n?.let { i to it } }
                    Column {
                        Text(
                            text = buildString {
                                append("预览（${validNames.size}/${selectedFiles.size}）：")
                                append(validNames.take(3).joinToString("、") { it.second })
                                if (validNames.size > 3) append(" 等 ${validNames.size} 项")
                            },
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (preview.skippedIndices.isNotEmpty()) {
                            Text(
                                text = "将跳过 ${preview.skippedIndices.size} 个无法处理的文件",
                                color = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                        // ★ 需求 4：把「因重名自动加序号」提前告知，避免用户执行后才发现名字被改
                        if (preview.autoSuffixedIndices.isNotEmpty()) {
                            Text(
                                text = "其中 ${preview.autoSuffixedIndices.size} 项与已有文件重名，将自动加 (序号)",
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                } else {
                    Text(
                        text = preview.error ?: "规则不合法",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                // 多规则合并预览的错误展示（v6 新增：跨规则重名等 errors 需对用户可见）
                if (multiPreview != null && multiPreview.errors.isNotEmpty()) {
                    Text(
                        text = multiPreview.errors.joinToString("；"),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // ★ 第九轮（问题 1 可选增强）：前置校验失败（如「没有可重命名的文件」）时
                // state.message 渲染在弹窗外会被模态遮挡，此处透传到弹窗内
                if (!state.batchRenameRunning && state.message != null) {
                    Text(
                        text = state.message.orEmpty(),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (ruleQueue.isNotEmpty()) {
                        onMultiConfirm(ruleQueue)  // 新回调
                    } else {
                        onConfirm(config)  // 单规则兼容
                    }
                },
                // ★ 第九轮（问题 1）：batchRenameRunning 期间禁用——防重复提交
                enabled = !state.batchRenameRunning &&
                    (multiPreview?.renamePairs?.isNotEmpty() == true ||
                        (ruleQueue.isEmpty() && hasValidNames))
            ) {
                if (state.batchRenameRunning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp), strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("重命名中…")
                } else Text("开始重命名")
            }
        },
        // ★ 第九轮（问题 1）：取消按钮同步禁用（操作中点外部已被 onDismiss 守卫拦下，这里补视觉反馈）
        dismissButton = { TextButton(onClick = onDismiss, enabled = !state.batchRenameRunning) { Text("取消") } }
    )
}

// ==================== 小控件 ====================

@Composable
private fun RenameOptionSelector(
    label: String,
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                shape = AppRadius.MD,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(options.getOrElse(selected) { options.first() },
                    style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEachIndexed { i, opt ->
                    DropdownMenuItem(
                        text = { Text(opt) },
                        onClick = { onSelect(i); expanded = false }
                    )
                }
            }
        }
    }
}

@Composable
private fun RenameTextField(label: String, value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        singleLine = true,
        shape = AppRadius.MD,
        colors = settingsTextFieldColors(),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun RenameNumberField(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    onValueChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        shape = AppRadius.MD,
        colors = settingsTextFieldColors(),
        modifier = modifier
    )
}

@Composable
private fun RenameSwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f)
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}