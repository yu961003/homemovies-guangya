package com.example.localmovielibrarz.ui.cloudclean

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.localmovielibrarz.cloud115.Cloud115Client
import com.example.localmovielibrarz.cloud115.Cloud115FileItem
import com.example.localmovielibrarz.data.repository.AppSettingsRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 清理名单条目数上限：与 UI 侧 `MAX_RULE_ITEMS` 保持一致，防 SharedPreferences StringSet 失控。 */
const val MAX_CLEAN_RULE_ITEMS = 500

/** 命中清理规则的网盘文件。 */
data class CloudCleanMatch(
    val name: String,
    val pickcode: String,
    val size: Long?,
    val path: String,
    val reason: String
)

data class CloudCleanUiState(
    val scanning: Boolean = false,
    val scannedDirs: Int = 0,
    val scannedFiles: Int = 0,
    val matches: List<CloudCleanMatch> = emptyList(),
    val error: String? = null,
    val message: String? = null,
    val selectionMode: Boolean = false,
    val selectedPickcodes: Set<String> = emptySet(),
    val cleaning: Boolean = false,
    val cleanedCount: Int = 0
)

/**
 * 115 清理工具（需求 13）：
 * - 按「共享清理名单」（关键词 / 后缀 + 大小阈值，与光鸭共用 AppSettingsRepository）扫描 115 目录树；
 * - 命中项展示原因，可多选 / 全选，批量移入回收站（deleteFileByPickcode，绝不永久删除）。
 */
class CloudCleanViewModel(
    private val cloud115Client: Cloud115Client,
    private val settingsRepository: AppSettingsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(CloudCleanUiState())
    val uiState: StateFlow<CloudCleanUiState> = _uiState.asStateFlow()

    private var scanJob: Job? = null
    private var cleanJob: Job? = null

    // ---------------- 规则管理（透传共享清理名单） ----------------

    fun keywords(): Set<String> = settingsRepository.getCloudCleanKeywords() // 同步读保留：startScan 内部仍在使用
    fun suffixes(): Set<String> = settingsRepository.getCloudCleanSuffixes()
    fun maxSizeMb(): Int = settingsRepository.getCloudCleanMaxSizeMb()

    // ★ 第九轮（问题 3 修复 A，115 侧与光鸭同构）：关键词/后缀 StateFlow 化——删除/导入后回灌触发重组
    private val _keywords = MutableStateFlow<Set<String>>(emptySet())
    val keywordsFlow: StateFlow<Set<String>> = _keywords.asStateFlow()
    private val _suffixes = MutableStateFlow<Set<String>>(emptySet())
    val suffixesFlow: StateFlow<Set<String>> = _suffixes.asStateFlow()

    init {
        _keywords.value = settingsRepository.getCloudCleanKeywords()
        _suffixes.value = settingsRepository.getCloudCleanSuffixes()
    }

    // 清理条件开关（关键词 / 后缀，双亮 = 同时命中；与光鸭共用一份数据）
    fun keywordEnabled() = settingsRepository.isCloudCleanKeywordEnabled()
    fun suffixEnabled() = settingsRepository.isCloudCleanSuffixEnabled()
    fun setKeywordEnabled(v: Boolean) = settingsRepository.saveCloudCleanKeywordEnabled(v)
    fun setSuffixEnabled(v: Boolean) = settingsRepository.saveCloudCleanSuffixEnabled(v)

    fun addKeyword(value: String) {
        settingsRepository.addCloudCleanKeyword(value)
        _keywords.value = settingsRepository.getCloudCleanKeywords() // 回灌触发重组
        push("已添加关键词：${value.trim()}")
    }

    fun removeKeyword(value: String) {
        settingsRepository.removeCloudCleanKeyword(value)
        _keywords.value = settingsRepository.getCloudCleanKeywords()
    }

    fun addSuffix(value: String) {
        settingsRepository.addCloudCleanSuffix(value)
        _suffixes.value = settingsRepository.getCloudCleanSuffixes()
        push("已添加后缀：${value.trim().removePrefix(".")}")
    }

    fun removeSuffix(value: String) {
        settingsRepository.removeCloudCleanSuffix(value)
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

    // ---------------- 清理名单 TXT 批量导入（第四轮，与光鸭共用同一份数据） ----------------

    fun importKeywords(items: List<String>) {
        val added = settingsRepository.addCloudCleanKeywords(items)
        _keywords.value = settingsRepository.getCloudCleanKeywords() // 批量导入后回灌
        push("关键词导入完成：新增 $added 条，请重新扫描")
        if (added > 0 && !keywordEnabled()) setKeywordEnabled(true)
    }

    fun importSuffixes(items: List<String>) {
        val added = settingsRepository.addCloudCleanSuffixes(items)
        _suffixes.value = settingsRepository.getCloudCleanSuffixes() // 批量导入后回灌
        push("后缀导入完成：新增 $added 条，请重新扫描")
        if (added > 0 && !suffixEnabled()) setSuffixEnabled(true)
    }

    fun saveMaxSizeMb(value: Int) {
        settingsRepository.saveCloudCleanMaxSizeMb(value)
        push("大小阈值已设为 ${value}MB（0=不限制）")
    }

    // ---------------- 扫描 ----------------

    fun startScan() {
        scanJob?.cancel()
        _uiState.update {
            it.copy(
                scanning = true,
                matches = emptyList(),
                selectionMode = false,
                selectedPickcodes = emptySet(),
                cleanedCount = 0,
                scannedDirs = 0,
                scannedFiles = 0,
                error = null
            )
        }
        scanJob = viewModelScope.launch {
            val result = runCatching { scanAll() }
            if (result.isFailure) onScanError(result.exceptionOrNull())
            _uiState.update {
                it.copy(
                    scanning = false,
                    message = if (result.isSuccess) {
                        "扫描完成：目录 ${it.scannedDirs} 个，文件 ${it.scannedFiles} 个，命中清理 ${it.matches.size} 个"
                    } else {
                        null
                    }
                )
            }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        _uiState.update { it.copy(scanning = false, message = "扫描已中止") }
    }

    private fun onScanError(error: Throwable?) {
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

    /** 递归遍历 115 目录树（DFS），对文件应用共享清理规则。带目录环保护与规模上限防卡死。 */
    private suspend fun scanAll() {
        val keywordList = keywords()
        val suffixSet = suffixes()
        val kwOn = keywordEnabled(); val sfxOn = suffixEnabled()
        val maxSizeBytes = maxSizeMb().takeIf { it > 0 }?.times(1024L * 1024L)
        val hint = when {
            !kwOn && !sfxOn -> "请至少点亮一组规则（关键词或后缀）"
            kwOn && keywordList.isEmpty() -> "关键词组为空：请添加关键词，或熄灭关键词开关"
            sfxOn && suffixSet.isEmpty() -> "后缀组为空：请添加后缀，或熄灭后缀开关"
            else -> null
        }
        if (hint != null) throw IllegalStateException(hint)

        val matches = mutableListOf<CloudCleanMatch>()
        val visited = mutableSetOf<Long>()
        val stack = ArrayDeque<Pair<Long, String>>()
        stack.add(ROOT_CID to ROOT_NAME)

        var dirCount = 0
        var fileCount = 0
        val maxDirs = 5_000
        val maxFiles = 60_000

        fun subPath(parent: String, dirName: String) =
            if (parent == ROOT_NAME) dirName else "$parent/$dirName"

        while (stack.isNotEmpty()) {
            val (cid, path) = stack.removeLast()
            if (cid in visited) continue
            visited.add(cid)
            if (++dirCount > maxDirs) {
                _uiState.update { it.copy(message = "目录数量达到上限 $maxDirs，已停止展开") }
                break
            }

            val children = cloud115Client.listFiles(cid)
            for (child in children) {
                if (++fileCount > maxFiles) {
                    _uiState.update { it.copy(message = "文件数量达到上限 $maxFiles，已停止扫描") }
                    break
                }
                if (child.isDirectory) {
                    child.cid?.let { stack.add(it to subPath(path, child.name)) }
                    continue
                }
                val pickcode = child.pickcode
                if (pickcode.isNullOrBlank()) continue
                val reason = matchReason(child.name, child.size, keywordList, kwOn, suffixSet, sfxOn, maxSizeBytes)
                    ?: continue
                matches += CloudCleanMatch(
                    name = child.name,
                    pickcode = pickcode,
                    size = child.size,
                    path = path,
                    reason = reason
                )
                if (matches.size % 200 == 0) {
                    _uiState.update {
                        it.copy(matches = matches.toList(), scannedDirs = dirCount, scannedFiles = fileCount)
                    }
                    delay(16)
                }
            }
            _uiState.update {
                it.copy(matches = matches.toList(), scannedDirs = dirCount, scannedFiles = fileCount)
            }
        }
        _uiState.update { it.copy(matches = matches.toList(), scannedDirs = dirCount, scannedFiles = fileCount) }
    }

    /** AND 感知匹配：双亮=两组同时命中；单亮=仅该组；大小阈值仅约束后缀侧。与光鸭端同实现。 */
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

    // ---------------- 选中与清理 ----------------

    fun enterSelectionMode() {
        _uiState.update { it.copy(selectionMode = true, selectedPickcodes = emptySet()) }
    }

    fun toggleSelect(pickcode: String) {
        _uiState.update { s ->
            val set = if (pickcode in s.selectedPickcodes) s.selectedPickcodes - pickcode else s.selectedPickcodes + pickcode
            s.copy(selectedPickcodes = set)
        }
    }

    fun selectAll() {
        _uiState.update { s -> s.copy(selectedPickcodes = s.matches.map { it.pickcode }.toSet()) }
    }

    fun clearSelection() {
        _uiState.update { it.copy(selectionMode = false, selectedPickcodes = emptySet()) }
    }

    fun cleanSelected() {
        val ids = _uiState.value.selectedPickcodes.toList()
        if (ids.isEmpty()) {
            push("请先勾选要移入回收站的文件")
            return
        }
        cleanJob?.cancel()
        _uiState.update { it.copy(cleaning = true, message = "正在移入回收站…") }
        cleanJob = viewModelScope.launch {
            var ok = 0
            var fail = 0
            for (id in ids) {
                val match = _uiState.value.matches.firstOrNull { it.pickcode == id }
                val nameHint = match?.name
                val success = runCatching { cloud115Client.deleteFileByPickcode(id, nameHint) }.getOrDefault(false)
                if (success) ok++ else fail++
                _uiState.update {
                    it.copy(
                        matches = it.matches.filterNot { m -> m.pickcode == id && success },
                        cleanedCount = it.cleanedCount + if (success) 1 else 0,
                        selectedPickcodes = it.selectedPickcodes - id
                    )
                }
                delay(60)
            }
            _uiState.update {
                it.copy(
                    cleaning = false,
                    selectionMode = false,
                    selectedPickcodes = emptySet(),
                    message = "清理完成：成功移入回收站 $ok 个${if (fail > 0) "，失败 $fail 个" else ""}"
                )
            }
        }
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = null, error = null) }
    }

    /** 轻提示：设置 message 并自动带出（供 Screen 各回调调用，如开关切换提示）。 */
    fun push(msg: String) {
        _uiState.update { it.copy(message = msg) }
    }

    companion object {
        private const val ROOT_CID = 0L
        private const val ROOT_NAME = "根目录"

        fun factory(
            cloud115Client: Cloud115Client,
            settingsRepository: AppSettingsRepository
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return CloudCleanViewModel(cloud115Client, settingsRepository) as T
            }
        }
    }
}