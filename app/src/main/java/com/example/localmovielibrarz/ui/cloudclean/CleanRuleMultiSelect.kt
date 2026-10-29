package com.example.localmovielibrarz.ui.cloudclean

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.localmovielibrarz.ui.AppRadius
import com.example.localmovielibrarz.ui.MinTouchTarget
import com.example.localmovielibrarz.ui.settings.settingsTextFieldColors

/**
 * 清理名单「多选下拉菜单」组件。
 *
 * 需求 3 背景：原来的「输入框 + 添加按钮 + chip 流」有四个体验硬伤：
 *  1. chip 流默认只展开 5 条，超过后要再点「展开全部」，长名单（几十上百条）几乎不可用；
 *  2. 已选项与可选推荐项混在一起，用户看不出「哪些还能加」；
 *  3. 没有搜索，找一条关键词要肉眼扫完全部 chip；
 *  4. 删除只能点 chip 上的小 ×，误触率高，且没有批量清理入口。
 *
 * 新设计：下拉面板 = 搜索框 + 已选区 + 候选项列表（多选 + 全选/清空），
 * 关闭面板后用等价 chip 流回显已选项（保留一眼可读的概览）。
 */
@Composable
fun CleanRuleMultiSelect(
    title: String,
    /** 全部候选项（含已选与未选），由 [CleanRuleLogic.buildOptions] 生成。 */
    options: List<CleanRuleOption>,
    /** 已选值（决定 Checkbox 勾选态）。 */
    selected: Set<String>,
    /** 勾选/取消勾选。 */
    onToggle: (value: String, checked: Boolean) -> Unit,
    /** 全选 / 清空。 */
    onSelectAll: () -> Unit,
    onClearAll: () -> Unit,
    /** 新增自定义项（回车或点「添加」）。 */
    onAddCustom: (String) -> Unit,
    /** 移除单项（chip 上的 ×）。 */
    onRemove: (String) -> Unit,
    /** 批量导入入口。 */
    onImport: (() -> Unit)? = null,
    /** 是否按后缀规则处理（影响占位文案与归一化提示）。 */
    isSuffix: Boolean = false,
    enabled: Boolean = true,
    /** 上限（达到后禁止新增，但仍可删改）。 */
    maxItems: Int = 500
) {
    var expanded by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var customInput by remember { mutableStateOf("") }

    val selectedList = options.filter { it.selected }.map { it.value }
    val visible = remember(options, query) { CleanRuleLogic.filterOptions(options, query) }
    val unselectedVisible = visible.filter { !it.selected }
    val selectedVisible = visible.filter { it.selected }
    val validationError = remember(customInput, isSuffix) {
        if (customInput.isBlank()) null else CleanRuleLogic.validateEntry(customInput, isSuffix)
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // ── 主按钮：点开下拉 ──
        Box(Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { expanded = true },
                enabled = enabled,
                shape = AppRadius.MD,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    Text(
                        if (selectedList.isEmpty()) "未选择（点击配置）"
                        else "已选 ${selectedList.size} 项：${selectedList.take(3).joinToString("、")}" +
                            if (selectedList.size > 3) " 等" else "",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Icon(Icons.Rounded.ArrowDropDown, contentDescription = "展开$title")
            }

            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false; query = "" },
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .width(320.dp)
            ) {
                // 搜索框
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("搜索${if (isSuffix) "后缀" else "关键词"}", style = MaterialTheme.typography.bodySmall) },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }, modifier = Modifier.size(MinTouchTarget)) {
                                Icon(Icons.Rounded.Close, contentDescription = "清空搜索", modifier = Modifier.size(16.dp))
                            }
                        }
                    },
                    singleLine = true,
                    shape = AppRadius.MD,
                    colors = settingsTextFieldColors(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                )

                // 操作行：全选 / 清空 / 计数
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "选中 ${selectedList.size}/${options.size}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        modifier = Modifier.weight(1f).padding(start = 6.dp)
                    )
                    TextButton(
                        onClick = onSelectAll,
                        enabled = options.isNotEmpty() && selectedList.size < options.size
                    ) { Text("全选", style = MaterialTheme.typography.labelSmall) }
                    TextButton(
                        onClick = onClearAll,
                        enabled = selectedList.isNotEmpty()
                    ) { Text("清空", style = MaterialTheme.typography.labelSmall) }
                }
                HorizontalDivider()

                // 候选项列表
                if (visible.isEmpty()) {
                    Text(
                        "没有匹配「$query」的项，可在下方输入后添加",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                    )
                } else {
                    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp)) {
                        if (selectedVisible.isNotEmpty()) {
                            item { GroupHeader("已选 ${selectedVisible.size}") }
                            items(selectedVisible, key = { "s-${it.key}" }) { opt ->
                                OptionRow(opt, true, isSuffix, onToggle)
                            }
                            if (unselectedVisible.isNotEmpty()) {
                                item { GroupHeader("可选 ${unselectedVisible.size}") }
                            }
                        }
                        items(unselectedVisible, key = { "u-${it.key}" }) { opt ->
                            OptionRow(opt, false, isSuffix, onToggle)
                        }
                    }
                }

                HorizontalDivider()

                // 自定义新增
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OutlinedTextField(
                        value = customInput,
                        onValueChange = { customInput = it },
                        placeholder = { Text(if (isSuffix) "自定义后缀，如 txt" else "自定义关键词", style = MaterialTheme.typography.bodySmall) },
                        isError = validationError != null,
                        singleLine = true,
                        shape = AppRadius.MD,
                        colors = settingsTextFieldColors(),
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = {
                            onAddCustom(customInput)
                            customInput = ""
                        },
                        enabled = customInput.isNotBlank() && validationError == null && options.size < maxItems
                    ) { Text("添加") }
                }
                validationError?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                    )
                }
                if (options.size >= maxItems) {
                    Text(
                        "已达上限 $maxItems 条，请先清理后再添加",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                    )
                }
                if (onImport != null) {
                    TextButton(
                        onClick = { onImport(); expanded = false },
                        modifier = Modifier.padding(start = 6.dp, bottom = 4.dp)
                    ) { Text("从 TXT 批量导入…", style = MaterialTheme.typography.labelSmall) }
                }
            }
        }

        // ── 已选回显（chip 流，超过 12 条折叠）──
        if (selectedList.isNotEmpty()) {
            SelectedChips(
                values = selectedList,
                isSuffix = isSuffix,
                onRemove = onRemove,
                enabled = enabled
            )
        } else {
            Text(
                if (isSuffix) "未选择后缀（此时不会按后缀匹配）" else "未选择关键词（此时不会按关键词匹配）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
        }
    }
}

@Composable
private fun GroupHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
            .padding(horizontal = 16.dp, vertical = 5.dp)
    )
}

@Composable
private fun OptionRow(
    option: CleanRuleOption,
    checked: Boolean,
    isSuffix: Boolean,
    onToggle: (String, Boolean) -> Unit
) {
    DropdownMenuItem(
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (isSuffix) ".${option.value}" else option.value,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (option.recommended) {
                    Text(
                        "推荐",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                        modifier = Modifier.padding(start = 6.dp)
                    )
                }
            }
        },
        leadingIcon = {
            Checkbox(checked = checked, onCheckedChange = { onToggle(option.value, it) })
        },
        // 整行可点，避免只能点中 Checkbox 的小方块
        onClick = { onToggle(option.value, !checked) }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SelectedChips(
    values: List<String>,
    isSuffix: Boolean,
    onRemove: (String) -> Unit,
    enabled: Boolean
) {
    var showAll by remember { mutableStateOf(false) }
    val previewCount = 12
    val shown = if (showAll) values else values.take(previewCount)

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (showAll) Modifier.heightIn(max = 180.dp) else Modifier),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            shown.forEach { value ->
                Row(
                    modifier = Modifier
                        .clip(AppRadius.MD)
                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f))
                        .padding(start = 10.dp, top = 2.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (isSuffix) ".$value" else value,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1
                    )
                    IconButton(
                        onClick = { onRemove(value) },
                        enabled = enabled,
                        modifier = Modifier.size(MinTouchTarget)
                    ) {
                        Icon(
                            Icons.Rounded.Close,
                            contentDescription = "移除 ${if (isSuffix) ".$value" else value}",
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        }
        if (values.size > previewCount) {
            TextButton(
                onClick = { showAll = !showAll },
                contentPadding = PaddingValues(horizontal = 4.dp)
            ) {
                Text(
                    if (showAll) "收起" else "还有 ${values.size - previewCount} 项，展开全部",
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}
