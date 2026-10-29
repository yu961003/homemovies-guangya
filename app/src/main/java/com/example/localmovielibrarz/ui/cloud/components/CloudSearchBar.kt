package com.example.localmovielibrarz.ui.cloud.components
import com.example.localmovielibrarz.ui.AppRadius

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.localmovielibrarz.ui.ContentAlphaMin

/**
 * 展开态搜索条：52dp 药丸输入框（不裁字）+ 取消按钮；115 的范围切换下移为分段按钮独占一行。
 * 收起态由调用方控制（配合 CloudOperationBar 的 onSearch 图标入口）。
 */
@Composable
fun CloudSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onCollapse: () -> Unit,
    isSearching: Boolean,
    searchScope: CloudSearchScope = CloudSearchScope.Global,
    onScopeChange: (CloudSearchScope) -> Unit = {},
    showScopeSwitcher: Boolean = false,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.weight(1f).heightIn(min = 52.dp),   // 不再强制 40dp，字不被裁
                placeholder = { Text("搜索文件…") },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                trailingIcon = {
                    when {
                        isSearching -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        query.isNotEmpty() -> IconButton(onClick = { onQueryChange("") }) {
                            Icon(Icons.Rounded.Close, contentDescription = "清除")
                        }
                    }
                },
                shape = AppRadius.XL,                        // 药丸造型
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent,
                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                    focusedPlaceholderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlphaMin),
                    unfocusedPlaceholderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlphaMin)
                )
            )
            TextButton(onClick = onCollapse) { Text("取消") }
        }
        if (showScopeSwitcher) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = searchScope == CloudSearchScope.CurrentDirectory,
                    onClick = { onScopeChange(CloudSearchScope.CurrentDirectory) },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                ) { Text("当前目录") }
                SegmentedButton(
                    selected = searchScope == CloudSearchScope.Global,
                    onClick = { onScopeChange(CloudSearchScope.Global) },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                ) { Text("全局") }
            }
        }
    }
}

/** 网盘搜索范围（115 / 光鸭共用）。 */
enum class CloudSearchScope(val label: String) {
    Global("全局"),
    CurrentDirectory("当前目录")
}