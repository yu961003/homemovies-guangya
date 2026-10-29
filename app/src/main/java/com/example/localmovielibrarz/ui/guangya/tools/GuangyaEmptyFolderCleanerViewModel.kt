package com.example.localmovielibrarz.ui.guangya.tools

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.localmovielibrarz.cloudguangya.GuangyaBrowserRepository
import com.example.localmovielibrarz.cloudguangya.GuangyaCleanExecutor
import com.example.localmovielibrarz.cloudguangya.GuangyaCleanProgress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class GuangyaEmptyFolderMatch(val folderId: String, val name: String, val path: String)

data class GuangyaEmptyFolderUiState(
    val scanning: Boolean = false,
    val scannedDirs: Int = 0, // 新增：已扫目录数
    val candidates: List<GuangyaEmptyFolderMatch> = emptyList(),
    val error: String? = null,
    val message: String? = null,
    val selectionMode: Boolean = false,
    val selectedIds: Set<String> = emptySet(),
    val cleaning: Boolean = false
)

/** 光鸭空文件夹清理：迭代式 DFS，子项为空 → 空候选 → 二次 listFiles 确认 → 大批量移入回收站（应用级执行器）。 */
class GuangyaEmptyFolderCleanerViewModel(
    private val repository: GuangyaBrowserRepository,
    private val cleanExecutor: GuangyaCleanExecutor
) : ViewModel() {

    private val _uiState = MutableStateFlow(GuangyaEmptyFolderUiState())
    val uiState: StateFlow<GuangyaEmptyFolderUiState> = _uiState.asStateFlow()

    /** 清理进度（来自应用级执行器，页面退出不中断）。 */
    val cleanProgress: StateFlow<GuangyaCleanProgress> = cleanExecutor.progress
    fun stopClean() = cleanExecutor.stop()

    private var scanJob: kotlinx.coroutines.Job? = null

    fun startScan(rootParentId: String, rootName: String) {
        // 5c 防御性日志：记录实际扫描根，便于排查「扫描范围不对」类反馈（只记不拦）
        android.util.Log.i("GuangyaEmptyFolderCleaner", "startScan: rootParentId='$rootParentId', rootName='$rootName'")
        scanJob?.cancel()
        _uiState.update { it.copy(scanning = true, candidates = emptyList(), error = null, scannedDirs = 0) }
        scanJob = viewModelScope.launch {
            val result = runCatching { scanAll(rootParentId, rootName.ifBlank { "根目录" }) }
            val e = result.exceptionOrNull()
            if (e is kotlinx.coroutines.CancellationException) return@launch   // 被新扫描取代，静默退出
            e?.let { _uiState.update { it.copy(error = it.message ?: "扫描失败") } }
            _uiState.update {
                it.copy(scanning = false,
                    message = if (result.isSuccess)
                        "扫描完成：已扫目录 ${it.scannedDirs}，空文件夹 ${it.candidates.size} 个" else null)
            }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        _uiState.update { it.copy(scanning = false, message = "扫描已中止") }
    }

    private suspend fun scanAll(rootParentId: String, rootName: String) {
        val candidates = mutableListOf<GuangyaEmptyFolderMatch>()
        val visited = mutableSetOf<String>()
        val stack = ArrayDeque<Pair<String, String>>()
        stack.add(rootParentId to rootName)
        var dirCount = 0; var failures = 0
        val maxDirs = 5000
        while (stack.isNotEmpty()) {
            val (pid, path) = stack.removeLast()
            if (pid in visited) continue
            visited.add(pid)
            if (++dirCount > maxDirs) {
                _uiState.update { it.copy(message = "目录数达上限 $maxDirs，已停止（可进入子目录分批扫描）") }
                break
            }
            // 取消异常必须透传（第四轮 1C），不许 runCatching 吞
            val children = try {
                repository.listFiles(pid)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                failures++
                if (failures >= 10) throw IllegalStateException("连续 $failures 个目录读取失败：${e.message}")
                emptyList()
            }
            if (children.isEmpty()) {
                candidates += GuangyaEmptyFolderMatch(pid, path.substringAfterLast('/'), path)
            } else {
                children.filter { it.isDir && it.fileId.isNotBlank() }.forEach {
                    stack.add(it.fileId to "$path/${it.name}")
                }
            }
            if (dirCount % 25 == 0) {
                _uiState.update { it.copy(scannedDirs = dirCount, candidates = candidates.toList()) }
                kotlinx.coroutines.delay(16)
            }
        }
        // 二次确认：单目录读取失败视为「非空」剔除，不再让一个失败毁掉整个扫描
        val confirmed = candidates.filter {
            runCatching { repository.listFiles(it.folderId).isEmpty() }.getOrDefault(false)
        }
        _uiState.update { it.copy(candidates = confirmed, scannedDirs = dirCount) }
    }

    fun toggleSelect(id: String) { _uiState.update { s -> s.copy(selectedIds = if (id in s.selectedIds) s.selectedIds - id else s.selectedIds + id) } }
    fun selectAll() { _uiState.update { s -> s.copy(selectedIds = s.candidates.map { it.folderId }.toSet()) } }
    fun clearSelection() { _uiState.update { it.copy(selectionMode = false, selectedIds = emptySet()) } }

    // 第四轮：委托应用级执行器（跨页面存活）；本地先清掉选中态与列表项，进度由 progress 流驱动展示
    fun cleanSelected() {
        val ids = _uiState.value.selectedIds.toList()
        if (ids.isEmpty()) { _uiState.update { it.copy(message = "请先勾选要移入回收站的空文件夹") }; return }
        if (cleanExecutor.isRunning) { _uiState.update { it.copy(message = "已有清理任务进行中，请等待完成") }; return }
        _uiState.update {
            it.copy(selectionMode = false, selectedIds = emptySet(),
                candidates = it.candidates.filterNot { c -> c.folderId in ids.toSet() })
        }
        val started = cleanExecutor.start(ids)
        if (!started) _uiState.update { it.copy(message = "清理任务启动失败") }
    }

    fun clearMessage() { _uiState.update { it.copy(message = null, error = null) } }

    // 3（方案 A）：代理执行器的线程安全清除方法，消费 finishedMessage 后调用，防止跨页面残留。
    fun clearFinishedMessage() = cleanExecutor.clearFinishedMessage()

    companion object {
        fun factory(repository: GuangyaBrowserRepository, cleanExecutor: GuangyaCleanExecutor): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    GuangyaEmptyFolderCleanerViewModel(repository, cleanExecutor) as T
            }
    }
}