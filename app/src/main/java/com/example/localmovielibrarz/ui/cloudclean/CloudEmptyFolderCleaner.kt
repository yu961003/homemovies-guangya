package com.example.localmovielibrarz.ui.cloudclean
import com.example.localmovielibrarz.ui.AppRadius

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.localmovielibrarz.cloud115.Cloud115Client
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 空文件夹候选。 */
data class CloudEmptyFolder(
    val cid: Long,
    val name: String,
    val path: String
)

data class CloudEmptyFolderUiState(
    val scanning: Boolean = false,
    val verifying: Boolean = false,
    val scannedDirs: Int = 0,
    val emptyFolders: List<CloudEmptyFolder> = emptyList(),
    val error: String? = null,
    val message: String? = null,
    val selectedCids: Set<Long> = emptySet(),
    val deleting: Boolean = false,
    val deletedCount: Int = 0
)

/**
 * 115 空文件夹清理工具（需求 13.2）：
 * DFS 递归扫描指定目录树，子项为空 → 空候选；对候选二次 listFiles 确认无子项；
 * 勾选后批量 deleteFoldersByCids 移入回收站（≤50/批，绝不永久删除）。
 */
class CloudEmptyFolderViewModel(
    private val cloud115Client: Cloud115Client,
    private val rootCid: Long,
    private val rootName: String
) : ViewModel() {

    private val _uiState = MutableStateFlow(CloudEmptyFolderUiState())
    val uiState: StateFlow<CloudEmptyFolderUiState> = _uiState.asStateFlow()

    private var scanJob: Job? = null
    private var deleteJob: Job? = null

    fun startScan() {
        scanJob?.cancel()
        _uiState.update {
            it.copy(
                scanning = true,
                verifying = false,
                scannedDirs = 0,
                emptyFolders = emptyList(),
                selectedCids = emptySet(),
                deletedCount = 0,
                error = null
            )
        }
        scanJob = viewModelScope.launch {
            val result = runCatching { scanEmptyFolders() }
            if (result.isFailure) onError(result.exceptionOrNull())
            _uiState.update {
                it.copy(
                    scanning = false,
                    verifying = false,
                    message = if (result.isSuccess) {
                        "扫描完成：共找到 ${it.emptyFolders.size} 个空文件夹"
                    } else null
                )
            }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        _uiState.update { it.copy(scanning = false, verifying = false, message = "扫描已中止") }
    }

    private fun onError(error: Throwable?) {
        _uiState.update {
            it.copy(
                message = null,
                error = when (error) {
                    is java.io.IOException -> "扫描失败（网络）：${error.message ?: "请检查网络"}"
                    else -> "扫描失败：${error?.message ?: "未知错误"}"
                }
            )
        }
    }

    /** DFS 找空目录：先收集一次 listFiles 为空的目录，再对候选二次确认，防止扫描期间被占用的误报。 */
    private suspend fun scanEmptyFolders() {
        val candidates = mutableListOf<CloudEmptyFolder>()
        val visited = mutableSetOf<Long>()
        val stack = ArrayDeque<Pair<Long, String>>()
        stack.add(rootCid to (if (rootName.isBlank()) "根目录" else rootName))

        var dirCount = 0
        val maxDirs = 5_000

        fun subPath(parent: String, dirName: String) =
            if (parent == rootName || parent == "根目录") dirName else "$parent/$dirName"

        while (stack.isNotEmpty()) {
            val (cid, path) = stack.removeLast()
            if (cid in visited) continue
            visited.add(cid)
            if (++dirCount > maxDirs) {
                _uiState.update { it.copy(message = "目录数量达到上限 $maxDirs，已停止展开") }
                break
            }
            val children = cloud115Client.listFiles(cid)
            if (children.isEmpty()) {
                candidates += CloudEmptyFolder(cid = cid, name = cidName(path), path = path)
            } else {
                children.filter { it.isDirectory }.forEach { child ->
                    child.cid?.let { stack.add(it to subPath(path, child.name)) }
                }
            }
            if (dirCount % 25 == 0) {
                _uiState.update {
                    it.copy(scannedDirs = dirCount, emptyFolders = candidates.toList())
                }
                delay(16)
            }
        }
        _uiState.update { it.copy(scannedDirs = dirCount, emptyFolders = candidates.toList()) }

        // 二次确认：重新 listFiles 每个候选，仍有子项或读取失败则剔除
        val confirmed = mutableListOf<CloudEmptyFolder>()
        _uiState.update { it.copy(verifying = true) }
        candidates.forEachIndexed { index, folder ->
            val stillEmpty = runCatching { cloud115Client.listFiles(folder.cid).isEmpty() }.getOrDefault(false)
            if (stillEmpty) confirmed += folder
            if ((index + 1) % 25 == 0) {
                _uiState.update { it.copy(emptyFolders = confirmed.toList(), scannedDirs = dirCount) }
                delay(16)
            }
        }
        _uiState.update { it.copy(emptyFolders = confirmed.toList(), verifying = false) }
    }

    private fun cidName(path: String): String = path.substringAfterLast('/')

    // ---------------- 选择与删除 ----------------

    fun toggleSelect(cid: Long) {
        _uiState.update { s ->
            val set = if (cid in s.selectedCids) s.selectedCids - cid else s.selectedCids + cid
            s.copy(selectedCids = set)
        }
    }

    fun selectAll() {
        _uiState.update { s -> s.copy(selectedCids = s.emptyFolders.map { it.cid }.toSet()) }
    }

    fun clearSelection() {
        _uiState.update { it.copy(selectedCids = emptySet()) }
    }

    fun deleteSelected() {
        val ids = _uiState.value.selectedCids.toList()
        if (ids.isEmpty()) {
            _uiState.update { it.copy(message = "请先勾选要移入回收站的空文件夹") }
            return
        }
        deleteJob?.cancel()
        _uiState.update { it.copy(deleting = true, message = "正在移入回收站…") }
        deleteJob = viewModelScope.launch {
            var ok = 0
            var fail = 0
            ids.chunked(BATCH_LIMIT).forEach { batch ->
                val success = runCatching { cloud115Client.deleteFoldersByCids(batch) }.getOrDefault(false)
                if (success) ok += batch.size else fail += batch.size
                _uiState.update {
                    it.copy(
                        emptyFolders = it.emptyFolders.filterNot { f -> f.cid in batch && success },
                        deletedCount = it.deletedCount + if (success) batch.size else 0,
                        selectedCids = it.selectedCids - batch.toSet()
                    )
                }
                delay(80)
            }
            _uiState.update {
                it.copy(
                    deleting = false,
                    message = "清理完成：成功移入回收站 $ok 个${if (fail > 0) "，失败 $fail 个" else ""}"
                )
            }
        }
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = null, error = null) }
    }

    companion object {
        private const val BATCH_LIMIT = 50

        fun factory(
            cloud115Client: Cloud115Client,
            rootCid: Long,
            rootName: String
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return CloudEmptyFolderViewModel(cloud115Client, rootCid, rootName) as T
            }
        }
    }
}

/** 空文件夹清理界面（需求 13.2 / 13.4）。 */
@Composable
fun CloudEmptyFolderScreen(
    viewModel: CloudEmptyFolderViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showDeleteConfirm by remember { mutableStateOf(false) }

    BackHandler(onBack = onBack)

    LaunchedEffect(state.message) {
        state.message?.let {
            delay(2_500)
            viewModel.clearMessage()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
    ) {
        // 顶栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
            }
            Text(
                text = "空文件夹清理",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            OutlinedButton(
                onClick = { if (state.scanning || state.verifying) viewModel.stopScan() else viewModel.startScan() },
                enabled = !state.deleting
            ) {
                Text(if (state.scanning || state.verifying) "停止扫描" else "开始扫描")
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            state.message?.let { msg ->
                Text(
                    text = msg,
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            state.error?.let { err ->
                Text(
                    text = "出错：$err",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            if (state.scanning || state.verifying) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (state.verifying) {
                            "二次确认中：已扫目录 ${state.scannedDirs} · 确认空文件夹 ${state.emptyFolders.size}"
                        } else {
                            "扫描中：已扫目录 ${state.scannedDirs} · 发现空文件夹 ${state.emptyFolders.size}"
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            } else {
                Text(
                    text = "空文件夹 ${state.emptyFolders.size} 个（勾选后批量移入回收站）",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
            }

            if (state.emptyFolders.isEmpty()) {
                Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                    Text(
                        text = if (state.scanning || state.verifying) "正在扫描…" else "暂无空文件夹，点击「开始扫描」检查网盘",
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.emptyFolders, key = { it.cid }) { folder ->
                        val checked = folder.cid in state.selectedCids
                        val rowBg =
                            if (checked) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(rowBg, shape = AppRadius.MD)
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Rounded.Folder,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                modifier = Modifier.size(22.dp)
                            )
                            Column(
                                Modifier
                                    .weight(1f)
                                    .padding(start = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(
                                    text = folder.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "路径：${folder.path}",
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Checkbox(checked = checked, onCheckedChange = { viewModel.toggleSelect(folder.cid) })
                        }
                    }
                }
            }
        }

        // 底部操作栏
        if (state.emptyFolders.isNotEmpty()) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(10.dp),
                shape = AppRadius.LG,
                tonalElevation = 4.dp,
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = "已选 ${state.selectedCids.size}/${state.emptyFolders.size}",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                        maxLines = 1,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = viewModel::selectAll) { Text("全选", style = MaterialTheme.typography.labelMedium) }
                    TextButton(
                        onClick = { showDeleteConfirm = true },
                        enabled = state.selectedCids.isNotEmpty() && !state.deleting
                    ) {
                        Text("移入回收站", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(onClick = viewModel::clearSelection) { Text("清空选择", style = MaterialTheme.typography.labelMedium) }
                }
            }
        }
        if (state.deleting) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "删除中：已移入回收站 ${state.deletedCount} 个",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("移入回收站？") },
            text = {
                Text("确定将选中的 ${state.selectedCids.size} 个空文件夹移入回收站吗？回收站内可恢复，不会彻底删除。")
            },
            confirmButton = {
                Button(onClick = {
                    showDeleteConfirm = false
                    viewModel.deleteSelected()
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") }
            }
        )
    }
}