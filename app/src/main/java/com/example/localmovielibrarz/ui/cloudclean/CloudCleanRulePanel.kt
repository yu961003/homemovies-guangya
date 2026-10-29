package com.example.localmovielibrarz.ui.cloudclean

import com.example.localmovielibrarz.ui.AppRadius

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.localmovielibrarz.ui.settings.settingsTextFieldColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.charset.Charset

/** 清理名单条目数上限：防止 SharedPreferences StringSet 无上限失控（500 条 × 万级文件的 contains 仍是毫秒级）。 */
internal const val MAX_RULE_ITEMS = MAX_CLEAN_RULE_ITEMS

/**
 * 宽松分隔解析：换行/顿号/中英文逗号/分号/Tab/连续空格；每条 trim + 去首尾引号；空条丢弃。
 * 自检："广告 推广,试看、免费；完整版 now" → [广告, 推广, 试看, 免费, 完整版, now]；'"广告"' → [广告]。
 */
internal fun parseCleanRuleText(text: String): List<String> =
    text.split('\n', '、', ',', '，', ';', '；', '\t')
        .flatMap { it.split(Regex("\\s+")) }      // 空格分隔（单个/连续）
        .map { it.trim().trim('"', '「', '」').trim() }
        .filter { it.isNotEmpty() }
        .distinct()

/**
 * 共享清理名单卡片（115 / 光鸭两端的清理工具页内嵌）。
 * 同一份 SharedPreferences 数据（关键词/后缀/大小阈值 + 开关），两端修改即时互相同步。
 * 面板内不放标题——修复三的页面外层卡已有「清理名单」标题；只放开关可折叠内容。
 *
 * ★ 需求 3 改版（下拉菜单）：关键词 / 后缀从「输入框 + chip 流」改为 [CleanRuleMultiSelect]：
 *  - 候选 = 内置推荐项 ∪ 用户自定义项，带搜索、已选/可选分组、全选/清空；
 *  - 支持「一次性提交整个集合」（[onApplyKeywords] / [onApplySuffixes]），避免逐条写盘；
 *  - 关闭面板后 chip 流回显已选项，保留一眼可读概览。
 *  旧回调（onAddKeyword/onRemoveKeyword/onImportKeywords/…）保留，供页面既有接线继续可用。
 */
@Composable
fun CloudCleanRulePanel(
    keywords: Set<String>,
    suffixes: Set<String>,
    maxSizeMb: Int,
    keywordEnabled: Boolean,
    suffixEnabled: Boolean,
    onToggleKeyword: (Boolean) -> Unit,
    onToggleSuffix: (Boolean) -> Unit,
    onAddKeyword: (String) -> Unit,
    onRemoveKeyword: (String) -> Unit,
    onAddSuffix: (String) -> Unit,
    onRemoveSuffix: (String) -> Unit,
    onSaveMaxSizeMb: (Int) -> Unit,
    onImportKeywords: (List<String>) -> Unit,
    onImportSuffixes: (List<String>) -> Unit,
    /** ★ 需求 3：批量提交已选关键词（全选 / 清空 / 勾选后即时保存）。缺省回退到逐条 add/remove。 */
    onApplyKeywords: ((Collection<String>) -> Unit)? = null,
    /** ★ 需求 3：批量提交已选后缀。缺省回退到逐条 add/remove。 */
    onApplySuffixes: ((Collection<String>) -> Unit)? = null
) {
    var sizeInput by remember { mutableStateOf(maxSizeMb.toString()) }
    // TXT 批量导入：预览状态（目标, 解析条目）
    var importPreview by remember { mutableStateOf<Pair<String, List<String>>?>(null) }
    // UTF-8 优先 + GBK 回退的文件读取 launcher（Windows 记事本默认 ANSI）
    val kwTxtLauncher = rememberTxtLauncher { text ->
        val items = parseCleanRuleText(text)
        if (items.isNotEmpty()) importPreview = "关键词" to items
    }
    val sxTxtLauncher = rememberTxtLauncher { text ->
        val items = parseCleanRuleText(text)
        if (items.isNotEmpty()) importPreview = "后缀" to items
    }

    // 候选选项：内置推荐 + 用户已选/自定义，按「选中优先」排序（纯逻辑见 CleanRuleLogic）
    val keywordOptions = remember(keywords) {
        CleanRuleLogic.buildOptions(keywords, CleanRuleLogic.RECOMMENDED_KEYWORDS, isSuffix = false)
    }
    val suffixOptions = remember(suffixes) {
        CleanRuleLogic.buildOptions(suffixes, CleanRuleLogic.RECOMMENDED_SUFFIXES, isSuffix = true)
    }

    /**
     * 统一提交入口：优先走批量回调；未接线时回退为「差集 add / remove」，
     * 保证只替换 UI 组件、不强制改调用方签名。
     */
    fun applyKeywords(next: Collection<String>) {
        val cleaned = CleanRuleLogic.sanitizeSelection(next, isSuffix = false, max = MAX_RULE_ITEMS)
        if (onApplyKeywords != null) {
            onApplyKeywords(cleaned)
        } else {
            val current = keywords.map { CleanRuleLogic.normalizeKeyword(it) }.toSet()
            (current - cleaned.toSet()).forEach(onRemoveKeyword)
            (cleaned.toSet() - current).forEach(onAddKeyword)
        }
    }

    fun applySuffixes(next: Collection<String>) {
        val cleaned = CleanRuleLogic.sanitizeSelection(next, isSuffix = true, max = MAX_RULE_ITEMS)
        if (onApplySuffixes != null) {
            onApplySuffixes(cleaned)
        } else {
            val current = suffixes.map { CleanRuleLogic.normalizeSuffix(it) }.toSet()
            (current - cleaned.toSet()).forEach(onRemoveSuffix)
            (cleaned.toSet() - current).forEach(onAddSuffix)
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 开关行：两个可点亮 FilterChip（关键词 / 后缀）
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilterChip(
                selected = keywordEnabled,
                onClick = { onToggleKeyword(!keywordEnabled) },
                label = { Text("关键词", style = MaterialTheme.typography.labelSmall) },
                leadingIcon = if (keywordEnabled) {
                    { Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(14.dp)) }
                } else null
            )
            FilterChip(
                selected = suffixEnabled,
                onClick = { onToggleSuffix(!suffixEnabled) },
                label = { Text("后缀", style = MaterialTheme.typography.labelSmall) },
                leadingIcon = if (suffixEnabled) {
                    { Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(14.dp)) }
                } else null
            )
        }

        // 匹配语义描述直接由纯逻辑给出，避免文案与 ViewModel 的 AND/OR 行为脱节
        Text(
            CleanRuleLogic.matchModeDescription(keywordEnabled, suffixEnabled),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
        )

        // 关键词（下拉多选）
        Column(Modifier.alpha(if (keywordEnabled) 1f else 0.45f)) {
            CleanRuleMultiSelect(
                title = "关键词（文件名包含即命中）",
                options = keywordOptions,
                selected = keywordOptions.filter { it.selected }.map { it.value }.toSet(),
                onToggle = { value, checked ->
                    val cur = keywordOptions.filter { it.selected }.map { it.value }.toMutableList()
                    if (checked) cur.add(value) else cur.removeIf { CleanRuleLogic.keywordKey(it) == CleanRuleLogic.keywordKey(value) }
                    applyKeywords(cur)
                },
                onSelectAll = { applyKeywords(keywordOptions.map { it.value }) },
                onClearAll = { applyKeywords(emptyList()) },
                onAddCustom = { onAddKeyword(it) },
                onRemove = onRemoveKeyword,
                onImport = if (keywords.size < MAX_RULE_ITEMS) {
                    { kwTxtLauncher.launch(arrayOf("text/plain", "text/*")) }
                } else null,
                isSuffix = false,
                enabled = keywordEnabled,
                maxItems = MAX_RULE_ITEMS
            )
        }

        // 后缀（下拉多选）
        Column(Modifier.alpha(if (suffixEnabled) 1f else 0.45f)) {
            CleanRuleMultiSelect(
                title = "后缀（扩展名命中即清理）",
                options = suffixOptions,
                selected = suffixOptions.filter { it.selected }.map { it.value }.toSet(),
                onToggle = { value, checked ->
                    val cur = suffixOptions.filter { it.selected }.map { it.value }.toMutableList()
                    if (checked) cur.add(value) else cur.removeIf { CleanRuleLogic.normalizeSuffix(it) == CleanRuleLogic.normalizeSuffix(value) }
                    applySuffixes(cur)
                },
                onSelectAll = { applySuffixes(suffixOptions.map { it.value }) },
                onClearAll = { applySuffixes(emptyList()) },
                onAddCustom = { onAddSuffix(it) },
                onRemove = onRemoveSuffix,
                onImport = if (suffixes.size < MAX_RULE_ITEMS) {
                    { sxTxtLauncher.launch(arrayOf("text/plain", "text/*")) }
                } else null,
                isSuffix = true,
                enabled = suffixEnabled,
                maxItems = MAX_RULE_ITEMS
            )
        }

        // 大小阈值
        Row(
            modifier = Modifier.alpha(if (suffixEnabled) 1f else 0.45f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("小文件上限(MB)", style = MaterialTheme.typography.labelMedium)
            OutlinedTextField(
                value = sizeInput,
                onValueChange = { sizeInput = it },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
                textStyle = MaterialTheme.typography.bodySmall,
                shape = AppRadius.MD,
                colors = settingsTextFieldColors()
            )
            TextButton(
                onClick = {
                    val n = sizeInput.toIntOrNull()
                    if (n != null) onSaveMaxSizeMb(n) else sizeInput = maxSizeMb.toString()
                }
            ) { Text("保存") }
        }
        Text(
            "0 = 不限制大小；大小阈值仅对「后缀」侧生效",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
        )
    }

    // 导入预览弹窗
    importPreview?.let { (target, items) ->
        // 导入前先按纯逻辑清洗，弹窗里显示「实际会入库的条数」，避免用户以为重复项也被加进去了
        val isSuffixTarget = target == "后缀"
        val cleaned = CleanRuleLogic.sanitizeSelection(items, isSuffix = isSuffixTarget, max = MAX_RULE_ITEMS)
        val existingKeys = if (isSuffixTarget) {
            suffixes.map { CleanRuleLogic.normalizeSuffix(it) }.toSet()
        } else {
            keywords.map { CleanRuleLogic.keywordKey(it) }.toSet()
        }
        val newOnes = cleaned.filter {
            val k = if (isSuffixTarget) CleanRuleLogic.normalizeSuffix(it) else CleanRuleLogic.keywordKey(it)
            k !in existingKeys
        }
        AlertDialog(
            onDismissRequest = { importPreview = null },
            title = { Text("导入${target}（新增 ${newOnes.size} 条）") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        if (cleaned.isEmpty()) "解析后无可导入内容"
                        else cleaned.take(10).joinToString("、") +
                            if (cleaned.size > 10) " 等 ${cleaned.size} 条" else "",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 3, overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "共解析 ${items.size} 条，去重后 ${cleaned.size} 条，其中 ${newOnes.size} 条为新增" +
                            if (cleaned.size - newOnes.size > 0) "，${cleaned.size - newOnes.size} 条已存在将跳过" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                    Text(
                        "支持空格/逗号/顿号/分号/Tab/换行分隔；大小写不敏感去重",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (target == "关键词") onImportKeywords(cleaned) else onImportSuffixes(cleaned)
                        importPreview = null
                    },
                    enabled = cleaned.isNotEmpty()
                ) { Text("导入") }
            },
            dismissButton = { TextButton(onClick = { importPreview = null }) { Text("取消") } }
        )
    }
}

/**
 * TXT 文件读取 launcher：UTF-8 优先，出现替换符回退 GBK（Windows 记事本默认 ANSI）。
 * 文件读取放 IO 线程，回调切回主线程（修改 Compose 状态必须在主线程）。
 */
@Composable
private fun rememberTxtLauncher(onText: (String) -> Unit): ActivityResultLauncher<Array<String>> {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    return rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            val bytes = runCatching {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }.getOrNull() ?: return@launch
            val text = bytes.toString(Charsets.UTF_8)
                .takeIf { !it.contains('\uFFFD') }
                ?: String(bytes, Charset.forName("GBK"))
            withContext(Dispatchers.Main) { onText(text) }
        }
    }
}
