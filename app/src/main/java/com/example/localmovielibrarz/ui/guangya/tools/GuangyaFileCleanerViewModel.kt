package com.example.localmovielibrarz.ui.guangya.tools

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.localmovielibrarz.cloudguangya.GuangyaBrowserRepository
import com.example.localmovielibrarz.cloudguangya.GuangyaCleanExecutor
import com.example.localmovielibrarz.cloudguangya.GuangyaCleanProgress
import com.example.localmovielibrarz.data.repository.AppSettingsRepository
import com.example.localmovielibrarz.ui.cloudclean.CleanRuleLogic
import com.example.localmovielibrarz.ui.cloudclean.MAX_CLEAN_RULE_ITEMS
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class GuangyaFileCleanMatch(
    val fileId: String,
    val name: String,
    val size: Long?,
    val path: String,
    val reason: String
)

data class GuangyaFileCleanUiState(
    val scanning: Boolean = false,
    val scannedDirs: Int = 0, // 新增：已扫目录数
    val scannedFiles: Int = 0, // 新增：已扫文件数
    val matches: List<GuangyaFileCleanMatch> = emptyList(),
    val error: String? = null,
    val message: String? = null,
    val selectionMode: Boolean = false,
    val selectedIds: Set<String> = emptySet(),
    val cleaning: Boolean = false
)

/** 光鸭文件清理：按共享清理名单递归扫描目录树 → 勾选 → 大批量移入回收站（应用级执行器，跨页面存活）。 */
class GuangyaFileCleanerViewModel(
    private val repository: GuangyaBrowserRepository,
    private val settingsRepository: AppSettingsRepository,
    private val cleanExecutor: GuangyaCleanExecutor
) : ViewModel() {

    private val _uiState = MutableStateFlow(GuangyaFileCleanUiState())
    val uiState: StateFlow<GuangyaFileCleanUiState> = _uiState.asStateFlow()

    /** 清理进度（来自应用级执行器，页面退出不中断）。 */
    val cleanProgress: StateFlow<GuangyaCleanProgress> = cleanExecutor.progress
    fun stopClean() = cleanExecutor.stop()

    // ★ 第九轮（问题 3 修复 A）：关键词/后缀 StateFlow 化——删除/导入后回灌触发 Compose 重组，
    // 修复「删除后 UI 不刷新」（旧实现 UI 直读 SharedPreferences，无状态变化不重组）
    private val _keywords = MutableStateFlow<Set<String>>(emptySet())
    val keywordsFlow: StateFlow<Set<String>> = _keywords.asStateFlow()
    private val _suffixes = MutableStateFlow<Set<String>>(emptySet())
    val suffixesFlow: StateFlow<Set<String>> = _suffixes.asStateFlow()

    init {
        _keywords.value = settingsRepository.getCloudCleanKeywords()
        _suffixes.value = settingsRepository.getCloudCleanSuffixes()
    }

    private var scanJob: kotlinx.coroutines.Job? = null // 新增：可取消

    fun keywords() = settingsRepository.getCloudCleanKeywords() // 同步读保留：startScan/scanAll 内部仍在使用
    fun suffixes() = settingsRepository.getCloudCleanSuffixes()
    fun maxSizeMb() = settingsRepository.getCloudCleanMaxSizeMb()
    fun addKeyword(v: String) {
        settingsRepository.addCloudCleanKeyword(v)
        _keywords.value = settingsRepository.getCloudCleanKeywords() // 回灌触发重组
    }
    fun removeKeyword(v: String) {
        settingsRepository.removeCloudCleanKeyword(v)
        _keywords.value = settingsRepository.getCloudCleanKeywords()
    }
    fun addSuffix(v: String) {
        settingsRepository.addCloudCleanSuffix(v)
        _suffixes.value = settingsRepository.getCloudCleanSuffixes()
    }
    fun removeSuffix(v: String) {
        settingsRepository.removeCloudCleanSuffix(v)
        _suffixes.value = settingsRepository.getCloudCleanSuffixes()
    }

    // ★ 需求 3：下拉菜单多选需要「一次性提交整个集合」（全选 / 清空 / 勾选），
    // 逐条 add/remove 会产生 N 次 SharedPreferences 写盘 + N 次重组，故补批量入口。
    fun setKeywords(values: Collection<String>) {
        val cleaned = CleanRuleLogic.sanitizeSelection(values, isSuffix = false, max = MAX_CLEAN_RULE_ITEMS)
        settingsRepository.saveCloudCleanKeywords(cleaned.toSet())
        _keywords.value = settingsRepository.getCloudCleanKeywords()
    }

    fun setSuffixes(values: Collection<String>) {
        val cleaned = CleanRuleLogic.sanitizeSelection(values, isSuffix = true, max = MAX_CLEAN_RULE_ITEMS)
        settingsRepository.saveCloudCleanSuffixes(cleaned.toSet())
        _suffixes.value = settingsRepository.getCloudCleanSuffixes()
    }

    fun saveMaxSizeMb(v: Int) = settingsRepository.saveCloudCleanMaxSizeMb(v)

    // 清理条件开关（关键词 / 后缀，双亮 = 同时命中）
    fun keywordEnabled() = settingsRepository.isCloudCleanKeywordEnabled()
    fun suffixEnabled() = settingsRepository.isCloudCleanSuffixEnabled()
    fun setKeywordEnabled(v: Boolean) = settingsRepository.saveCloudCleanKeywordEnabled(v)
    fun setSuffixEnabled(v: Boolean) = settingsRepository.saveCloudCleanSuffixEnabled(v)

    fun push(msg: String) { _uiState.update { it.copy(message = msg) } }

    fun startScan(rootParentId: String, rootName: String) {
        // 5c 防御性日志：记录实际扫描根，便于排查「扫描范围不对」类反馈（只记不拦）
        android.util.Log.i("GuangyaFileCleaner", "startScan: rootParentId='$rootParentId', rootName='$rootName'")
        val kwOn = keywordEnabled(); val sfxOn = suffixEnabled()
        val kwEmpty = keywords().isEmpty(); val sfxEmpty = suffixes().isEmpty()
        val hint = when {
            !kwOn && !sfxOn -> "请至少点亮一组规则（关键词或后缀）"
            kwOn && kwEmpty -> "关键词组为空：请添加关键词，或熄灭关键词开关"
            sfxOn && sfxEmpty -> "后缀组为空：请添加后缀，或熄灭后缀开关"
            else -> null
        }
        if (hint != null) { _uiState.update { it.copy(message = hint) }; return }
        scanJob?.cancel() // 重复点击先取消旧任务
        _uiState.update {
            it.copy(scanning = true, matches = emptyList(), selectionMode = false,
                selectedIds = emptySet(), error = null, scannedDirs = 0, scannedFiles = 0)
        }
        scanJob = viewModelScope.launch {
            val result = runCatching { scanAll(rootParentId, rootName.ifBlank { "根目录" }) }
            val e = result.exceptionOrNull()
            if (e is kotlinx.coroutines.CancellationException) return@launch   // 被新扫描取代，静默退出
            e?.let { _uiState.update { it.copy(error = it.message ?: "扫描失败") } }
            _uiState.update {
                it.copy(
                    scanning = false,
                    message = if (result.isSuccess)
                        "扫描完成：目录 ${it.scannedDirs}，文件 ${it.scannedFiles}，命中 ${it.matches.size}" else null
                )
            }
        }
    }

    fun stopScan() { // 新增
        scanJob?.cancel()
        _uiState.update { it.copy(scanning = false, message = "扫描已中止") }
    }

    private suspend fun scanAll(rootParentId: String, rootName: String) {
        val kw = keywords(); val sfx = suffixes()
        val kwOn = keywordEnabled(); val sfxOn = suffixEnabled()
        val maxBytes = maxSizeMb().takeIf { it > 0 }?.times(1024L * 1024L)
        // 第四轮 1B：LinkedHashMap 按 fileId 全局去重（listFiles 已去重，这里再兜一道）
        val found = LinkedHashMap<String, GuangyaFileCleanMatch>()
        val visited = mutableSetOf<String>()
        val stack = ArrayDeque<Pair<String, String>>()
        stack.add(rootParentId to rootName)
        var dirs = 0; var files = 0; var failures = 0
        val maxDirs = 5000
        while (stack.isNotEmpty()) {
            val (pid, path) = stack.removeLast()
            if (pid in visited) continue
            visited.add(pid)
            if (++dirs > maxDirs) {
                _uiState.update { it.copy(message = "目录数达上限 $maxDirs，已停止展开（可进入子目录分批扫描）") }
                break
            }
            // 单目录失败跳过而非全盘中止；连续失败过多才判定为整体故障
            // 取消异常必须透传，不许 runCatching 吞（否则取消后协程不干净退出、污染新扫描状态）
            val children = try {
                repository.listFiles(pid)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                failures++
                if (failures >= 10) throw IllegalStateException("连续 $failures 个目录读取失败：${e.message}")
                emptyList()
            }
            for (c in children) {
                files++
                if (c.isDir) { stack.add(c.fileId to "$path/${c.name}"); continue }
                matchReason(c.name, c.size, kw, kwOn, sfx, sfxOn, maxBytes)?.let {
                    found.putIfAbsent(c.fileId, GuangyaFileCleanMatch(c.fileId, c.name, c.size, path, it))
                }
            }
            if (dirs % 25 == 0) { // 进度心跳
                _uiState.update { it.copy(matches = found.values.toList(), scannedDirs = dirs, scannedFiles = files) }
                delay(16)
            }
        }
        _uiState.update { it.copy(matches = found.values.toList(), scannedDirs = dirs, scannedFiles = files) }
    }

    /** AND 感知匹配：双亮=两组同时命中；单亮=仅该组；大小阈值仅约束后缀侧。 */
    private fun matchReason(
        name: String,
        size: Long?,
        kw: Set<String>, kwEnabled: Boolean,
        sfx: Set<String>, sfxEnabled: Boolean,
        maxBytes: Long?
    ): String? {
        val kwHit = if (kwEnabled) kw.firstOrNull { name.contains(it, ignoreCase = true) } else null
        val ext = name.substringAfterLast('.', "").lowercase()
        val sfxHit = if (sfxEnabled && ext.isNotEmpty() && ext in sfx) ext else null
        val sfxOk = when {
            sfxHit == null -> false
            maxBytes == null -> true
            else -> (size ?: 0L).let { s -> s <= 0L || s <= maxBytes }   // 未知大小放行（沿原语义）
        }
        val sizeNote = if (maxBytes != null) "（≤${maxSizeMb()}MB）" else ""
        return when {
            kwEnabled && sfxEnabled ->
                if (kwHit != null && sfxHit != null && sfxOk)
                    "关键词「$kwHit」＋后缀 .$sfxHit$sizeNote" else null
            kwEnabled -> kwHit?.let { "包含关键词「$it」" }
            sfxEnabled -> if (sfxHit != null && sfxOk) "后缀 .$sfxHit$sizeNote" else null
            else -> null
        }
    }

    fun toggleSelect(id: String) {
        _uiState.update { s -> s.copy(selectedIds = if (id in s.selectedIds) s.selectedIds - id else s.selectedIds + id) }
    }

    fun selectAll() { _uiState.update { s -> s.copy(selectedIds = s.matches.map { it.fileId }.toSet()) } }

    fun clearSelection() { _uiState.update { it.copy(selectionMode = false, selectedIds = emptySet()) } }

    // 第四轮：导入由 ViewModel 委托执行器（跨页面存活）；本地先清掉选中态与列表项，进度由 progress 流驱动展示
    fun cleanSelected() {
        val ids = _uiState.value.selectedIds.toList()
        if (ids.isEmpty()) { push("请先勾选要移入回收站的文件"); return }
        if (cleanExecutor.isRunning) { push("已有清理任务进行中，请等待完成"); return }
        _uiState.update {
            it.copy(selectionMode = false, selectedIds = emptySet(),
                matches = it.matches.filterNot { m -> m.fileId in ids.toSet() })
        }
        val started = cleanExecutor.start(ids)
        if (!started) push("清理任务启动失败")
    }

    // ---------------- 清理名单 TXT 批量导入 ----------------

    fun importKeywords(items: List<String>) {
        val added = settingsRepository.addCloudCleanKeywords(items)
        _keywords.value = settingsRepository.getCloudCleanKeywords() // 批量导入后回灌
        push("关键词导入完成：新增 $added 条 / 已存在 ${items.distinct().size - added} 条，请重新扫描")
        if (added > 0 && !keywordEnabled()) setKeywordEnabled(true)   // 导入成功自动点亮该组
    }

    fun importSuffixes(items: List<String>) {
        val added = settingsRepository.addCloudCleanSuffixes(items)
        _suffixes.value = settingsRepository.getCloudCleanSuffixes() // 批量导入后回灌
        push("后缀导入完成：新增 $added 条 / 已存在 ${items.distinct().size - added} 条，请重新扫描")
        if (added > 0 && !suffixEnabled()) setSuffixEnabled(true)
    }

    fun clearMessage() { _uiState.update { it.copy(message = null, error = null) } }

    // 3（方案 A）：代理执行器的线程安全清除方法，消费 finishedMessage 后调用，防止跨页面残留。
    fun clearFinishedMessage() = cleanExecutor.clearFinishedMessage()

    companion object {
        fun factory(
            repository: GuangyaBrowserRepository,
            settingsRepository: AppSettingsRepository,
            cleanExecutor: GuangyaCleanExecutor
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    GuangyaFileCleanerViewModel(repository, settingsRepository, cleanExecutor) as T
            }
    }
}