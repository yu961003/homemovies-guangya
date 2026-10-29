package com.example.localmovielibrarz.ui.guangya
import com.example.localmovielibrarz.util.userMessage

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.localmovielibrarz.cloudguangya.GuangyaBrowserRepository
import com.example.localmovielibrarz.cloudguangya.GuangyaFileItem
import com.example.localmovielibrarz.cloudguangya.GuangyaFolderRef
import com.example.localmovielibrarz.cloudguangya.GuangyaMagnetExtractor
import com.example.localmovielibrarz.cloudguangya.GuangyaOfflineMemory
import com.example.localmovielibrarz.cloudguangya.GuangyaOfflineProgress
import com.example.localmovielibrarz.cloudguangya.GuangyaOfflineSubmitter
import com.example.localmovielibrarz.data.repository.AppSettingsRepository
import com.example.localmovielibrarz.data.repository.CloudStrmRecordRepository
import com.example.localmovielibrarz.data.repository.GuangyaLocationSnapshot
import com.example.localmovielibrarz.data.repository.GuangyaStrmRepository
import com.example.localmovielibrarz.data.repository.MovieRepository
import com.example.localmovielibrarz.data.repository.StrmScrapeRepository
import com.example.localmovielibrarz.data.repository.finalizeOrganizedScrape
import com.example.localmovielibrarz.scraper.MovieNumberExtractor
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class GuangyaBrowserUiState(
    val files: List<GuangyaFileItem> = emptyList(),
    val currentParentId: String = "",
    val path: List<String> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    val addingFileIds: Set<String> = emptySet(),
    val addedFileIds: Set<String> = emptySet(),
    // 需求 2：搜索态
    val searchQuery: String = "",
    val searching: Boolean = false,
    // 需求 3：文件管理（多选 / 回收站 / 移动）
    val selectionMode: Boolean = false,
    val selectedIds: Set<String> = emptySet(),
    val managing: Boolean = false,
    // ★ 第九轮：批量重命名专用运行标记（managing 被回收站/移动/解散等操作复用，不能直接用于弹窗滞留判定）
    val batchRenameRunning: Boolean = false,
    // 5a：首次 loadFiles 已结束（成功或失败均置 true）。用于工具箱入口在加载未完成时拦截。
    val initialized: Boolean = false
)

/** 离线下载文本解析预览（含记忆过滤统计）。 */
data class OfflineParsePreview(
    val magnets: List<String> = emptyList(),
    val submittedSkip: Int = 0,
    val failedSkip: Int = 0
)

/**
 * 光鸭云盘浏览 + 添加 ViewModel（对齐 115 CloudBrowserViewModel 的操作逻辑）。
 * 根目录为 parentId=""（115 用 Long 类型的 ROOT_CID，光鸭用空串）。
 *
 * - 点视频 = 播放（由 UI 回调导航到播放器，播放时 PlaybackResolver 按 guangya_res 分流）。
 * - 点「添加」= 生成 STRM → 扫描入库 → 提取番号 → 自动刮削 → 整理同步（复用 115 链路，fileId 作为记录主键）。
 * - 目录「添加」= 递归遍历子目录，逐个添加视频（简化批量，不建持久化任务表）。
 */
class GuangyaBrowserViewModel(
    private val repository: GuangyaBrowserRepository,
    private val strmRepository: GuangyaStrmRepository,
    private val recordRepository: CloudStrmRecordRepository,
    private val movieRepository: MovieRepository,
    private val scrapeRepository: StrmScrapeRepository,
    private val settingsRepository: AppSettingsRepository,
    private val offlineMemory: GuangyaOfflineMemory,
    private val offlineSubmitter: GuangyaOfflineSubmitter
) : ViewModel() {
    private val _uiState = MutableStateFlow(GuangyaBrowserUiState())
    val uiState: StateFlow<GuangyaBrowserUiState> = _uiState.asStateFlow()

    // ---------------- 离线下载（完整版：记忆去重/黑名单/限额/并发/暂停） ----------------
    // offlineSubmitter 由 AppContainer 单例注入（审查 P2：跨页面保留进度，不随 ViewModel 销毁）
    val offlineProgress: StateFlow<GuangyaOfflineProgress> = offlineSubmitter.progress
    val offlineLogs: StateFlow<List<String>> = offlineSubmitter.logs

    /** 离线下载目标文件夹（响应式，驱动 UI 实时更新）。 */
    private val _offlineTargetFolder = MutableStateFlow<GuangyaFolderRef?>(offlineMemory.targetFolder())
    val offlineTargetFolderFlow: StateFlow<GuangyaFolderRef?> = _offlineTargetFolder.asStateFlow()

    /**
     * 当前是否已登录光鸭云盘。每次调用实时读取持久化登录态，
     * 避免 ViewModel 构造后用户在设置页登录/登出导致的缓存过期。
     */
    fun isLoggedIn(): Boolean = settingsRepository.isGuangyaLoggedIn()

    /** 已进入目录的 parentId 栈（栈底为根的空串，栈顶=当前目录的父），用于「返回上级」。 */
    private val pathStack = mutableListOf<String>()

    /** 已进入目录的名称栈（根目录为空），用于顶栏路径展示。 */
    private val nameStack = mutableListOf<String>()

    /** 恢复的当前位置（当前目录的 fileId），restoreOrRoot 消费后置空。 */
    private var restoredParentId: String? = null

    init {
        // 恢复上次浏览位置（跨进程/重启保留）
        if (isLoggedIn()) {
            val snapshot = settingsRepository.getGuangyaLocation()
            if (snapshot.currentParentId.isNotBlank() && snapshot.stack.isNotEmpty()) {
                pathStack.addAll(snapshot.stack)
                nameStack.addAll(snapshot.names)
                restoredParentId = snapshot.currentParentId
            } else {
                // 快照不完整（旧版本 bug 产生的数据）：清掉避免卡在错误状态
                settingsRepository.clearGuangyaLocation()
            }
        }
    }

    fun canGoBackFolder(): Boolean = pathStack.isNotEmpty()

    /** 进入页面时调用：有恢复位置则回到该目录，否则进入根目录。 */
    fun restoreOrRoot() {
        val target = restoredParentId ?: ""
        restoredParentId = null
        loadFiles(target)
    }

    fun enterRoot() {
        pathStack.clear()
        nameStack.clear()
        settingsRepository.clearGuangyaLocation()
        clearSearchQuery()
        loadFiles("")
    }

    fun openFolder(item: GuangyaFileItem) {
        if (item.isDir) {
            pathStack.add(_uiState.value.currentParentId)
            nameStack.add(item.name)
            // 持久化「新目录」作为当前位置（loadFiles 为异步，不能依赖其后的状态）
            persistLocation(item.fileId)
            loadFiles(item.fileId)
        }
    }

    fun goUp() {
        if (pathStack.isEmpty()) {
            settingsRepository.clearGuangyaLocation()
            clearSearchQuery()
            loadFiles("")
        } else {
            nameStack.removeLastOrNull()
            val parent = pathStack.removeAt(pathStack.size - 1)
            // 持久化「上级目录」作为当前位置
            persistLocation(parent)
            loadFiles(parent)
        }
    }

    private fun persistLocation(currentParentId: String) {
        settingsRepository.saveGuangyaLocation(
            GuangyaLocationSnapshot(
                stack = pathStack.toList(),
                currentParentId = currentParentId,
                names = nameStack.toList()
            )
        )
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }

    /** 5a：目录尚未完成首次加载时，工具箱入口点击后的提示。 */
    fun notifyNotReady() {
        _uiState.update { it.copy(message = "目录仍在加载，请稍后再试") }
    }

    // ---------------- 离线下载（cloudcollection/v1，完整功能版） ----------------

    /** 解析文本中的磁力链接，返回解析结果 + 记忆过滤统计（移植油猴脚本提取 + 去重逻辑）。 */
    fun parseOfflineText(text: String): OfflineParsePreview {
        val magnets = GuangyaMagnetExtractor.extractMagnets(text)
        val unique = LinkedHashSet(magnets)
        var submitted = 0
        var failed = 0
        unique.forEach { m ->
            // H 轮：与提交引擎同口径 —— 成功记忆回读旧键，失败黑名单只看新键
            when {
                GuangyaMagnetExtractor.submittedKeys(m).any { offlineMemory.isSubmitted(it) } -> submitted++
                GuangyaMagnetExtractor.failedKeys(m).any { offlineMemory.isFailed(it) } -> failed++
            }
        }
        return OfflineParsePreview(
            magnets = unique.toList(),
            submittedSkip = submitted,
            failedSkip = failed
        )
    }

    /** 启动离线下载批次（提交引擎处理：并发/重试/限额/记忆）。 */
    fun startOffline(urls: List<String>, parentId: String) {
        offlineSubmitter.start(urls, parentId)
    }

    fun pauseOffline() = offlineSubmitter.pause()

    fun resumeOffline() = offlineSubmitter.resume()

    fun cancelOffline() = offlineSubmitter.cancel()

    // ---- 记忆 / 限额 / 目标文件夹 ----

    fun offlineDailyLimit(): Int = offlineMemory.getDailyLimit()

    fun setOfflineDailyLimit(n: Int) {
        offlineMemory.setDailyLimit(n)
        _uiState.update { it.copy(message = "每日限额已设为 $n（今日已用 ${offlineMemory.getTodayCount()}）") }
    }

    fun offlineTodayCount(): Int = offlineMemory.getTodayCount()

    fun offlineTodayRemaining(): Int = offlineMemory.todayRemaining()

    fun offlineTargetFolder(): GuangyaFolderRef? = offlineMemory.targetFolder()

    fun setOfflineTargetFolder(folder: GuangyaFolderRef?) {
        offlineMemory.setTargetFolder(folder)
        _offlineTargetFolder.value = folder
        _uiState.update { it.copy(message = if (folder != null) "目标文件夹：${folder.name}" else "目标文件夹：(根目录)") }
    }

    fun offlineMemoryStats(): String {
        val submitted = offlineMemory.submittedCount()
        val failed = offlineMemory.failedCount()
        val today = offlineMemory.getTodayCount()
        val limit = offlineMemory.getDailyLimit()
        val total = offlineMemory.getTotalSubmitted()
        val target = offlineMemory.targetFolder()
        return buildString {
            append("今日已用 $today/$limit（剩余 ${(limit - today).coerceAtLeast(0)}）\n")
            append("已成功记忆 $submitted 个\n")
            append("永久失败黑名单 $failed 个\n")
            append("历史累计提交 $total 次\n")
            append("目标文件夹：${target?.name ?: "(根目录)"}")
        }
    }

    /** 导出记忆为 JSON 备份文本。 */
    fun exportOfflineMemory(): String = offlineMemory.exportJson()

    /** 导入记忆备份，返回提示文本。 */
    fun importOfflineMemory(json: String): String = runCatching {
        val n = offlineMemory.importJson(json)
        "已导入记忆：$n 个已提交磁力链（含黑名单/每日计数/目标文件夹）"
    }.getOrElse { "导入失败：${it.message}" }

    fun clearOfflineMemory() {
        offlineMemory.clearAll()
        _uiState.update { it.copy(message = "记忆已清空（已提交的磁力链会重新提交）") }
    }

    /** 列出目标文件夹候选（只列目录，供选择器浏览）。 */
    suspend fun listOfflineFolders(parentId: String): List<GuangyaFileItem> = repository.listFolders(parentId)

    /** 全局搜索目标文件夹候选。 */
    suspend fun searchOfflineFolders(keyword: String): List<GuangyaFileItem> = repository.searchFolders(keyword)

    /** 需求 5：判断文件名/大小是否命中网盘排除名单（与 115 语义一致），供 UI 置灰展示。 */
    fun isVideoNameExcluded(name: String, size: Long): Boolean {
        val excludedNames = settingsRepository.getCloudExcludedVideoNames()
        if (name.trim() in excludedNames) return true
        val skipBelowSizeBytes = settingsRepository.getCloudScrapeSkipBelowSizeBytes()
        return skipBelowSizeBytes > 0L && size > 0L && size <= skipBelowSizeBytes
    }

    /** 字节数格式化为可读大小（B/KB/MB），用于跳过提示显示实际文件大小。 */
    private fun formatBytes(bytes: Long): String = when {
        bytes < 1024 -> "${bytes}B"
        bytes < 1024 * 1024 -> String.format(java.util.Locale.ROOT, "%.1fKB", bytes / 1024.0)
        else -> String.format(java.util.Locale.ROOT, "%.1fMB", bytes / (1024.0 * 1024.0))
    }

    /** 添加单个视频：生成 STRM → 入库 → 自动刮削（镜像 115 的 processCloudVideoAddAllowScrapeFailure）。 */
    fun addVideoToLibrary(item: GuangyaFileItem) {
        val fileId = item.fileId
        if (fileId.isBlank()) {
            _uiState.update { it.copy(message = "这个视频没有 fileId，无法添加") }
            return
        }
        if (fileId in _uiState.value.addedFileIds || fileId in _uiState.value.addingFileIds) {
            _uiState.update { it.copy(message = "这个视频已经添加或正在添加") }
            return
        }
        // 需求 5：命中网盘排除名单（与 115 对齐）则跳过
        val excludedNames = settingsRepository.getCloudExcludedVideoNames()
        val skipBelowSizeBytes = settingsRepository.getCloudScrapeSkipBelowSizeBytes()
        val skipReason = when {
            item.name.trim() in excludedNames -> "命中网盘排除名单"
            skipBelowSizeBytes > 0L && item.size > 0L && item.size <= skipBelowSizeBytes ->
                "文件大小低于排除阈值（${formatBytes(item.size)} ≤ ${formatBytes(skipBelowSizeBytes)}）"
            else -> null
        }
        if (skipReason != null) {
            _uiState.update { it.copy(message = "${item.name}：$skipReason（未添加）") }
            scrapeRepository.appendLog("光鸭小文件跳过：${item.name}，size=${item.size}B，阈值=${skipBelowSizeBytes}B，原因：$skipReason")
            return
        }
        _uiState.update {
            it.copy(addingFileIds = it.addingFileIds + fileId, message = "正在检查 ${item.name}")
        }
        viewModelScope.launch {
            runCatching { processGuangyaVideoAdd(item, fileId) }
                .onSuccess { result ->
                    _uiState.update {
                        it.copy(
                            addingFileIds = it.addingFileIds - fileId,
                            addedFileIds = it.addedFileIds + fileId,
                            message = result
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            addingFileIds = it.addingFileIds - fileId,
                            message = error.message ?: "添加失败"
                        )
                    }
                }
        }
    }

    /** 添加整个文件夹：递归遍历子目录，逐个添加视频（简化批量，无持久化队列）。 */
    fun addFolderToLibrary(folder: GuangyaFileItem) {
        val fileId = folder.fileId
        if (fileId.isBlank()) {
            _uiState.update { it.copy(message = "这个文件夹没有 fileId，无法批量添加") }
            return
        }
        if (fileId in _uiState.value.addingFileIds) {
            _uiState.update { it.copy(message = "这个文件夹正在添加中") }
            return
        }
        _uiState.update {
            it.copy(addingFileIds = it.addingFileIds + fileId, message = "正在读取文件夹：${folder.name}")
        }
        viewModelScope.launch {
            runCatching { processFolderAdd(folder) }
                .onSuccess { summary ->
                    _uiState.update {
                        it.copy(
                            addingFileIds = it.addingFileIds - fileId,
                            message = summary
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            addingFileIds = it.addingFileIds - fileId,
                            message = error.message ?: "文件夹添加失败"
                        )
                    }
                }
        }
    }

    private suspend fun processFolderAdd(folder: GuangyaFileItem): String {
        var added = 0
        var failed = 0
        var skipped = 0
        // 需求 5：递归时同步应用排除名单与大小阈值（与 115 一致）
        val excludedNames = settingsRepository.getCloudExcludedVideoNames()
        val skipBelowSizeBytes = settingsRepository.getCloudScrapeSkipBelowSizeBytes()
        val stack = ArrayDeque<GuangyaFileItem>()
        stack.add(folder)
        while (stack.isNotEmpty()) {
            val dir = stack.removeLast()
            val children = repository.listFiles(dir.fileId)
            children.filter { it.isDir }.forEach { stack.add(it) }
            for (video in children.filter { !it.isDir && it.isVideoFile() }) {
                val skipReason = when {
                    video.name.trim() in excludedNames -> "命中网盘排除名单"
                    skipBelowSizeBytes > 0L && video.size > 0L && video.size <= skipBelowSizeBytes ->
                        "文件大小低于排除阈值（${formatBytes(video.size)} ≤ ${formatBytes(skipBelowSizeBytes)}）"
                    else -> null
                }
                if (skipReason != null) {
                    skipped++
                    scrapeRepository.appendLog("光鸭文件夹添加跳过：${video.name}，原因：$skipReason")
                    continue
                }
                runCatching { processGuangyaVideoAdd(video, video.fileId) }
                    .onSuccess {
                        added++
                        _uiState.update { s -> s.copy(addedFileIds = s.addedFileIds + video.fileId) }
                    }
                    .onFailure {
                        failed++
                        scrapeRepository.appendLog("光鸭文件夹添加失败：${video.name} / ${video.fileId}，原因：${it.message}")
                    }
            }
        }
        val skipNote = if (skipped > 0) "，跳过（排除名单/大小）$skipped 个" else ""
        return "文件夹添加完成：入库 $added 个${if (failed > 0) "，失败 $failed 个" else ""}$skipNote"
    }

    /** 单个光鸭视频完整链路：STRM → 扫描入库 → 番号 → 刮削 → 整理同步（镜像 115，fileId 作为记录主键）。 */
    private suspend fun processGuangyaVideoAdd(item: GuangyaFileItem, fileId: String): String {
        if (recordRepository.get(fileId) != null) {
            scrapeRepository.appendLog("光鸭添加跳过：${item.name} / $fileId，原因：影片已经添加")
            return "影片已经添加"
        }
        scrapeRepository.appendLog("开始处理光鸭添加：${item.name} / $fileId")
        val generated = strmRepository.generateStrmForVideo(item)
        val libraryRoot = settingsRepository.getLibraryRootUri()
            ?: error("请先到设置页选择影片库目录")
        val rootUri = Uri.parse(libraryRoot)

        if (!generated.shouldScrape) {
            scrapeRepository.appendLog("附加播放源 STRM 写入完成，不单独刮削：${generated.fileName}")
            return "已加入播放源：${generated.movieNumberHint ?: item.name}"
        }

        scrapeRepository.appendLog("光鸭 STRM 写入完成，开始扫描：${generated.fileName}")
        val addedMovie = movieRepository.scanSingleMovie(rootUri, Uri.parse(generated.strmUri), mergeByMovieNumber = true)
        addedMovie?.let { recordRepository.attachMovie(fileId, it.id) }

        // ★ 第九轮（问题 6 改动 4）：附加源判定——扫描合并后影片主 videoUri 不是本次光鸭 STRM，
        // 说明本片已有其它来源（scanSingleMovie mergedAsAdditionalSource 分支保留旧 videoUri），
        // 光鸭作为附加播放源挂靠即可，跳过整条刮削+整理链（镜像 115 附加源 shouldScrape=false 语义），
        // 同时消解问题 4「刮削整理把光鸭 STRM 移入整理目录造成分段源失联」的干扰面
        if (addedMovie != null && addedMovie.videoUri != generated.strmUri) {
            scrapeRepository.appendLog("光鸭附加源挂靠完成，跳过刮削：${item.name} → movieId=${addedMovie.id}")
            return "已加入播放源：${addedMovie.videoName}"
        }

        val number = extractMovieNumberWithRules(generated.fileName, item.name)
        if (number == null) {
            val reason = "无法从文件名提取番号，无法自动刮削"
            addedMovie?.let { movieRepository.markScrapeTaskFailed(it.id, reason) }
            scrapeRepository.appendLog("光鸭添加未刮削：${item.name}，原因：$reason")
            return "已添加但未刮削：${item.name}"
        }

        val movieForScrape = addedMovie
            ?: movieRepository.findMovieByNumberAndVariant(libraryRoot, number, generated.fileName)
            ?: error("STRM 已添加，但扫描后没有在影片库中找到 $number")
        recordRepository.attachMovie(fileId, movieForScrape.id)
        movieRepository.markScrapeTaskPending(movieForScrape.id)
        val source = settingsRepository.getDefaultScrapeSource()
        scrapeRepository.appendLog("开始刮削光鸭影片：$number，来源：$source")
        movieRepository.markScrapeTaskRunning(movieForScrape.id)
        val scrapeResult = runCatching {
            scrapeRepository.scrapeStrmUriWithOutput(
                libraryRootUri = libraryRoot,
                strmUri = generated.strmUri,
                source = source,
                forceDistinct = false
            )
        }.getOrElse { error ->
            val reason = "刮削来源 ${source.name} 失败：${error.userMessage()}"
            movieRepository.markScrapeTaskFailed(movieForScrape.id, reason)
            scrapeRepository.appendLog("光鸭已入库但刮削失败：$number，原因：$reason")
            return "已添加但刮削失败：$number"
        }

        scrapeRepository.appendLog("光鸭文件整理完成，开始同步影片数据库：$number")
        val refreshedMovie = runCatching {
            finalizeOrganizedScrape(
                refreshMovie = {
                    movieRepository.refreshMovieAfterScrape(
                        original = movieForScrape,
                        scrapedStrmUri = scrapeResult.strmUri,
                        mergeByMovieNumber = true
                    )
                },
                markCompleted = { movieRepository.markScrapeTaskCompleted(it.id) },
                markFailed = { reason -> movieRepository.markScrapeTaskFailed(movieForScrape.id, reason) },
                missingMovieMessage = "整理后的 STRM 已写入，但影片路径或元数据未同步到数据库：$number"
            )
        }.getOrElse { error ->
            val reason = error.message ?: "整理后的 STRM 未同步到数据库：$number"
            scrapeRepository.appendLog("光鸭刮削整理失败：$reason")
            return reason
        }
        recordRepository.updateStrmLocation(
            pickcode = fileId,
            strmUri = refreshedMovie.videoUri,
            libraryRootUri = refreshedMovie.libraryRootUri,
            movieId = refreshedMovie.id
        )
        scrapeRepository.appendLog(
            "[媒体路径] 状态=成功，操作=光鸭单片刮削整理并更新数据库，movieId=${refreshedMovie.id}，文件=${refreshedMovie.videoName}"
        )
        return if (generated.created) {
            "已添加并刮削：$number"
        } else {
            "STRM 已存在，已刷新并刮削：$number"
        }
    }

    private suspend fun extractMovieNumberWithRules(vararg values: String?): String? {
        scrapeRepository.loadCachedNumberRecognitionRules()
        values.mapNotNull { it?.takeIf(String::isNotBlank) }.forEach { value ->
            MovieNumberExtractor.extract(value)?.let { return it }
        }
        return null
    }

    // ---------------- 需求 2：列表页全局搜索 ----------------

    private var searchJob: kotlinx.coroutines.Job? = null

    fun clearSearchQuery() {
        searchJob?.cancel()
        _uiState.update { it.copy(searchQuery = "", searching = false) }
    }

    /** 全局搜索文件+目录（去抖 350ms）。命中目录直接进入并将 parentId 置空便于回根。 */
    fun searchFiles(keyword: String) {
        val q = keyword.trim()
        searchJob?.cancel()
        if (q.isBlank()) {
            _uiState.update { it.copy(searchQuery = "", searching = false) }
            loadFiles(_uiState.value.currentParentId.takeIf { it.isNotBlank() } ?: "")
            return
        }
        // 进入搜索态：立即转圈，保证「有反应」
        _uiState.update { it.copy(searchQuery = q, searching = true, loading = true, error = null) }
        searchJob = viewModelScope.launch {
            kotlinx.coroutines.delay(350)
            runCatching { repository.searchFiles(q) }
                .onSuccess { items ->
                    val added = recordRepository.existingPickcodesForVisibleItems(
                        items.map { it.fileId }.filter { it.isNotBlank() }.toSet()
                    )
                    _uiState.update {
                        it.copy(files = items, addedFileIds = added, searching = false, loading = false, error = null)
                    }
                }
                .onFailure { error ->
                    val msg = when (error) {
                        is com.example.localmovielibrarz.cloudguangya.GuangyaTokenExpiredException -> "光鸭登录已失效，请重新登录"
                        is java.io.IOException -> "网络连接失败：${error.message ?: "请检查网络"}"
                        else -> error.message ?: "搜索失败"
                    }
                    // 关键：失败也清空列表，让错误态可见（否则被非空列表掩盖，表现为「没反应」）
                    _uiState.update { it.copy(error = msg, searching = false, loading = false, files = emptyList()) }
                }
        }
    }

    // ---------------- 需求 3：文件管理（多选 / 移入回收站 / 移动 / 重命名） ----------------

    fun enterSelectionMode() {
        _uiState.update { it.copy(selectionMode = true, selectedIds = emptySet()) }
    }

    fun exitSelectionMode() {
        _uiState.update { it.copy(selectionMode = false, selectedIds = emptySet()) }
    }

    fun toggleSelect(fileId: String) {
        _uiState.update { s ->
            val set = if (fileId in s.selectedIds) s.selectedIds - fileId else s.selectedIds + fileId
            s.copy(selectedIds = set)
        }
    }

    fun selectAll() {
        _uiState.update { s ->
            s.copy(selectedIds = s.files.map { it.fileId }.filter { it.isNotBlank() }.toSet())
        }
    }

    /** 批量移入回收站（绝不永久删除）。完成后核对服务端是否真的移除，退出生效。 */
    fun recycleSelected() {
        val ids = _uiState.value.selectedIds.toList()
        if (ids.isEmpty()) {
            _uiState.update { it.copy(message = "请先勾选要移入回收站的文件") }
            return
        }
        val parentId = _uiState.value.currentParentId // 先存，防状态被改
        _uiState.update { it.copy(managing = true, message = "正在移入回收站…") }
        viewModelScope.launch {
            runCatching { repository.recycleFiles(ids) }
                .onSuccess {
                    // 结果校验（最后防线，修复二轮问题 D）：re-list 核对选中 id 是否真的消失——防异步执行/静默失败
                    // （参考项目 recycle 也是「调用后立即 loadFiles」，无任务轮询；此处延时一次并核对即可兜住）
                    delay(600) // 给服务端异步生效留窗口
                    val stillThere = try {
                        val current = repository.listFiles(parentId)
                        ids.filter { id -> current.any { it.fileId == id } }
                    } catch (e: Exception) {
                        ids // 校验失败时保守处理：视为未消失，交界面提示
                    }
                    if (stillThere.isNotEmpty()) {
                        scrapeRepository.appendLog("光鸭移入回收站未生效（服务端未移除）：${stillThere.joinToString("、")}")
                    }
                    _uiState.update {
                        it.copy(
                            message = if (stillThere.isEmpty())
                                "已移入回收站 ${ids.size} 项"
                            else
                                "移入回收站未生效（服务端未移除 ${stillThere.size} 项，请重试或查看刮削日志）",
                            managing = false,
                            selectionMode = false,
                            selectedIds = emptySet()
                        )
                    }
                    loadFiles(parentId) // 刷新真实状态
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            message = "移入回收站失败：${error.userMessage()}",
                            managing = false
                        )
                    }
                    loadFiles(parentId) // 部分成功也要刷新出真实状态
                }
        }
    }

    /** 批量移动到目标目录。目标目录由调用方传入（父目录 fileId）。 */
    fun moveSelectedTo(targetParentId: String) {
        val ids = _uiState.value.selectedIds.toList()
        if (ids.isEmpty()) {
            _uiState.update { it.copy(message = "请先勾选要移动的文件") }
            return
        }
        val parentId = _uiState.value.currentParentId // 先存，防状态被改
        _uiState.update { it.copy(managing = true, message = "正在移动…") }
        viewModelScope.launch {
            runCatching { repository.moveFiles(ids, targetParentId) }
                .onSuccess {
                    _uiState.update {
                        it.copy(
                            message = "已移动 ${ids.size} 项",
                            managing = false,
                            selectionMode = false,
                            selectedIds = emptySet()
                        )
                    }
                    loadFiles(parentId) // 关键修复
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            message = "移动失败：${error.userMessage()}",
                            managing = false
                        )
                    }
                    loadFiles(parentId) // 部分成功也要刷新出真实状态
                }
        }
    }

    /** 重命名单个文件（保留扩展名由调用方保证）。成功后刷新当前目录。 */
    fun renameFile(fileId: String, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isBlank()) {
            _uiState.update { it.copy(message = "新名称不能为空") }
            return
        }
        _uiState.update { it.copy(managing = true, message = "正在重命名…") }
        viewModelScope.launch {
            runCatching { repository.renameFile(fileId, trimmed) }
                .onSuccess {
                    _uiState.update { it.copy(message = "重命名成功", managing = false, selectionMode = false) }
                    loadFiles(_uiState.value.currentParentId)
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(message = "重命名失败：${error.userMessage()}", managing = false)
                    }
                }
        }
    }

    // ---------------- 需求 3 补充：批量重命名 + 解散子目录 ----------------

    /** 读取已保存的重命名模板。 */
    fun renameTemplates(): List<String> = settingsRepository.getGuangyaRenameTemplates()

    /** 保存/删除重命名模板（持久化，跨会话有效）。 */
    fun saveRenameTemplate(template: String) = settingsRepository.saveGuangyaRenameTemplate(template)

    fun deleteRenameTemplate(template: String) = settingsRepository.deleteGuangyaRenameTemplate(template)

    /** 批量重命名：按配置（四类操作）统一处理，成功后刷新当前目录（P1-B：容错逐条，跳过失败项）。 */
    fun batchRenameSelected(config: BatchRenameConfig) {
        val selected = _uiState.value.files
            .filter { it.fileId.isNotBlank() && it.fileId in _uiState.value.selectedIds }
            .sortedBy { it.name }
        if (selected.isEmpty()) {
            _uiState.update { it.copy(message = "请先勾选要重命名的文件") }
            return
        }
        val result = buildBatchRenameNames(selected, config)
        val finalNames = result.names
        if (finalNames == null) {
            _uiState.update { it.copy(message = result.error ?: "规则不合法") }
            return
        }

        // 构建重命名对列表，跳过 null 项（无法处理的文件）
        val renamePairs = selected.zip(finalNames).mapNotNull { (item, name) ->
            if (name != null) item.fileId to name else null
        }

        if (renamePairs.isEmpty()) {
            _uiState.update { it.copy(message = "没有可重命名的文件") }
            return
        }

        val skipNote = if (result.skippedIndices.isNotEmpty())
            "（跳过 ${result.skippedIndices.size} 个）" else ""

        val parentId = _uiState.value.currentParentId
        _uiState.update { it.copy(managing = true, batchRenameRunning = true, message = "正在批量重命名…$skipNote") }
        viewModelScope.launch {
            runCatching {
                repository.batchRenameFiles(renamePairs, parentId) // parentId：6b 重名 auto-suffix 依据
            }
                .onSuccess { outcome -> // 返回值不再是 Unit：逐条结果（succeeded/failed）
                    _uiState.update {
                        it.copy(
                            message = buildString {
                                append("已重命名 ${outcome.succeeded.size} 项$skipNote")
                                if (outcome.retried > 0) append("（含自动重试生效 ${outcome.retried} 项）")
                                if (outcome.autoSuffixed > 0) {
                                    append("，其中 ${outcome.autoSuffixed} 项因重名自动加序号")
                                }
                                if (outcome.failed.isNotEmpty()) {
                                    append("，失败 ${outcome.failed.size} 项：")
                                    // ★ G-3②：不可见空格显形（␠），便于一眼定位 B 类失败
                                    append(outcome.failed.joinToString("；") { "${visualizeInvisibleChars(it.newName)}（${it.reason}）" })
                                }
                            },
                            managing = false,
                            batchRenameRunning = false
                            // ★ 问题 1 改动 4：不再强制退出多选——完成后弹窗自动关闭，
                            // 用户停留在当前目录的多选态（fileId 不因改名变化），可继续批量操作
                        )
                    }
                    loadFiles(parentId) // 有任何成功项即刷新；全失败也刷新以反映真实状态
                }
                .onFailure { error -> // 仅剩「网络层整体异常」等罕见路径
                    _uiState.update {
                        it.copy(
                            message = "批量重命名失败：${error.userMessage()}",
                            managing = false,
                            batchRenameRunning = false
                        )
                    }
                    loadFiles(parentId) // ★ 失败也刷新，杜绝「部分已生效但列表停留在旧名」
                }
        }
    }

    /**
     * 多规则批量重命名：按规则队列依次匹配文件并重命名（P2）。
     */
    fun batchRenameMultiRule(rules: List<RenameRuleEntry>) {
        val selected = _uiState.value.files
            .filter { it.fileId.isNotBlank() && it.fileId in _uiState.value.selectedIds }
            .sortedBy { it.name }
        if (selected.isEmpty()) {
            _uiState.update { it.copy(message = "请先勾选要重命名的文件") }
            return
        }

        val result = buildMultiRuleRenameNames(selected, rules)

        if (result.renamePairs.isEmpty()) {
            _uiState.update {
                it.copy(message = "没有可重命名的文件" +
                    if (result.errors.isNotEmpty()) "（${result.errors.first()}）"
                    else if (result.unassignedFiles.isNotEmpty()) "（${result.unassignedFiles.size} 个未匹配任何规则）"
                    else "")
            }
            return
        }

        val parentId = _uiState.value.currentParentId
        val skipMsg = buildString {
            if (result.skippedFiles.isNotEmpty()) append("，跳过 ${result.skippedFiles.size} 个")
            if (result.unassignedFiles.isNotEmpty()) append("，${result.unassignedFiles.size} 个未匹配规则")
        }

        _uiState.update { it.copy(managing = true, batchRenameRunning = true, message = "正在批量重命名…$skipMsg") }
        viewModelScope.launch {
            runCatching {
                repository.batchRenameFiles(
                    result.renamePairs.map { (item, name) -> item.fileId to name },
                    parentId // 6b：重名 auto-suffix 依据
                )
            }
                .onSuccess { outcome -> // 返回值不再是 Unit：逐条结果（succeeded/failed）
                    _uiState.update {
                        it.copy(
                            message = buildString {
                                append("已重命名 ${outcome.succeeded.size} 项$skipMsg")
                                if (outcome.retried > 0) append("（含自动重试生效 ${outcome.retried} 项）")
                                if (outcome.autoSuffixed > 0) {
                                    append("，其中 ${outcome.autoSuffixed} 项因重名自动加序号")
                                }
                                if (outcome.failed.isNotEmpty()) {
                                    append("，失败 ${outcome.failed.size} 项：")
                                    // ★ G-3②：不可见空格显形（␠），便于一眼定位 B 类失败
                                    append(outcome.failed.joinToString("；") { "${visualizeInvisibleChars(it.newName)}（${it.reason}）" })
                                }
                            },
                            managing = false,
                            batchRenameRunning = false
                            // ★ 问题 1 改动 4：不再强制退出多选（同 batchRenameSelected）
                        )
                    }
                    loadFiles(parentId)
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            message = "批量重命名失败：${error.userMessage()}",
                            managing = false,
                            batchRenameRunning = false
                        )
                    }
                    loadFiles(parentId) // ★ 失败也刷新，杜绝「部分已生效但列表停留在旧名」
                }
        }
    }

    // ---------------- 模板（全操作类型） ----------------

    /** 读取 V2 模板；V2 为空时把旧版纯文本模板一次性迁移为「全新命名」模板。 */
    fun batchRenameTemplates(): List<BatchRenameTemplate> {
        val v2 = settingsRepository.getGuangyaBatchRenameTemplates()
            .mapNotNull { BatchRenameTemplate.fromJson(it) }
        if (v2.isNotEmpty()) return v2.sortedByDescending { it.id }
        val legacy = renameTemplates().map {
            BatchRenameTemplate(id = it.hashCode().toLong(), name = it, opType = 2, namingTemplate = it)
        }
        legacy.forEach { t -> settingsRepository.saveGuangyaBatchRenameTemplate(t.toJson()) }
        return legacy
    }

    /** 保存模板（相同参数不重复保存）。 */
    fun saveBatchRenameTemplate(t: BatchRenameTemplate) {
        val existing = batchRenameTemplates()
        if (existing.any { it.copy(id = 0, name = "") == t.copy(id = 0, name = "") }) {
            _uiState.update { it.copy(message = "相同模板已存在") }
            return
        }
        settingsRepository.saveGuangyaBatchRenameTemplate(t.toJson())
    }

    fun deleteBatchRenameTemplate(id: Long) {
        settingsRepository.getGuangyaBatchRenameTemplates()
            .mapNotNull { BatchRenameTemplate.fromJson(it) }
            .filter { it.id == id }
            .forEach { settingsRepository.deleteGuangyaBatchRenameTemplate(it.toJson()) }
    }

    /**
     * 解散子目录：递归收集当前目录下所有子目录中的文件，批量移动到当前目录。
     * 重写版（第四轮修复）：分批移动 + 进度反馈 + 空列表提前返回（不再误报「已完成」）。
     */
    fun dissolveSubdirectories() {
        val parentId = _uiState.value.currentParentId
        if (_uiState.value.managing) return
        _uiState.update { it.copy(managing = true, message = "正在扫描子目录…") }
        viewModelScope.launch {
            runCatching {
                // 1. 先收集（带安全限制）
                val existingNames = _uiState.value.files.map { it.name }.toSet()
                val allFiles = collectFilesRecursively(parentId)
                    .filter { it.fileId.isNotBlank() && it.name !in existingNames }
                    .distinctBy { it.fileId }

                if (allFiles.isEmpty()) {
                    // ★ 空列表提前返回，不再走「已把 0 个文件…」——这是 4b 确认的误报根因
                    _uiState.update {
                        it.copy(managing = false, message = "当前目录下没有需要解散的子目录文件")
                    }
                    return@launch
                }

                // 2. 分批移动 + 进度反馈（每批 50 = moveFiles 内部 chunked(50)，一次调用恰好一个服务端批次）
                val total = allFiles.size
                var moved = 0
                var failed = 0
                val batches = allFiles.chunked(DISSOLVE_BATCH_SIZE)
                _uiState.update { it.copy(message = "正在解散子目录：0/$total（共 ${batches.size} 批）") }

                for ((batchIndex, batch) in batches.withIndex()) {
                    coroutineContext.ensureActive()   // ★ 协程取消检查（页面销毁 / ViewModel 清理时退出）
                    try {
                        repository.moveFiles(batch.map { it.fileId }, parentId)
                        moved += batch.size
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failed += batch.size   // 单批失败不中止，最后汇总
                    }
                    _uiState.update {
                        it.copy(
                            message = "正在解散子目录：${moved + failed}/$total" +
                                "（第 ${batchIndex + 1}/${batches.size} 批）" +
                                if (failed > 0) " · 失败 $failed" else ""
                        )
                    }
                }

                // 3. 全部批次结束后才提示完成（含失败计数）
                _uiState.update {
                    it.copy(
                        message = "解散完成：成功移动 $moved 个文件" +
                            if (failed > 0) "，失败 $failed 个（可重试）" else "",
                        managing = false,
                        selectionMode = false,
                        selectedIds = emptySet()
                    )
                }
                loadFiles(parentId)
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        message = "解散子目录失败：${error.userMessage()}",
                        managing = false
                    )
                }
            }
        }
    }

    /**
     * 栈式 DFS 收集子目录中的文件（非递归，防栈溢出）。
     * 语义对齐原递归实现：只收集子目录（depth >= 1）内的文件，
     * 当前目录（depth=0）的直属文件不收集、不移动。
     * @return 收集到的文件列表；超过数量上限时抛 IllegalStateException
     */
    private suspend fun collectFilesRecursively(parentId: String): List<GuangyaFileItem> {
        val result = ArrayList<GuangyaFileItem>()
        val visited = mutableSetOf<String>()
        val stack = ArrayDeque<Pair<String, Int>>()   // (目录 ID, 深度)
        stack.add(parentId to 0)

        while (stack.isNotEmpty()) {
            val (dirId, depth) = stack.removeLast()
            if (dirId in visited) continue
            visited.add(dirId)
            if (depth > DISSOLVE_MAX_DEPTH) continue   // 深度保护

            val children = try {
                repository.listFiles(dirId)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                continue   // 单目录读取失败跳过
            }

            for (child in children) {
                if (child.isDir && child.fileId.isNotBlank()) {
                    stack.add(child.fileId to depth + 1)
                } else if (depth > 0 && child.fileId.isNotBlank()) {
                    // ★ depth > 0：跳过当前目录直属文件。
                    //   第四轮方案漏了此判断，会把直属文件提交 moveItems(fileId, parentId)
                    //   （移动到自身所在目录），仅靠同名过滤兜不住缓存不一致的情况。
                    if (result.size >= DISSOLVE_MAX_FILES) {
                        throw IllegalStateException(
                            "子目录文件数超过上限 $DISSOLVE_MAX_FILES，请先手动整理部分子目录后再解散"
                        )
                    }
                    result.add(child)
                }
            }
        }
        return result
    }

    private fun loadFiles(parentId: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                loading = true,
                error = null,
                currentParentId = parentId,
                path = nameStack.toList()
            )
            runCatching {
                val items = repository.listFiles(parentId)
                val added = recordRepository.existingPickcodesForVisibleItems(
                    items.map { it.fileId }.filter { it.isNotBlank() }.toSet()
                )
                items to added
            }            .onSuccess { (items, added) ->
                _uiState.update {
                    it.copy(files = items, addedFileIds = added, loading = false, initialized = true)
                }
            }.onFailure { error ->
                val msg = when (error) {
                    is com.example.localmovielibrarz.cloudguangya.GuangyaTokenExpiredException -> "光鸭登录已失效，请重新登录"
                    is java.io.IOException -> "网络连接失败：${error.message ?: "请检查网络"}"
                    else -> error.message ?: "加载失败"
                }
                _uiState.update {
                    it.copy(error = msg, loading = false, initialized = true)
                }
            }
        }
    }

    companion object {
        // 解散子目录安全限制
        private const val DISSOLVE_MAX_FILES = 2000     // 文件数上限
        private const val DISSOLVE_MAX_DEPTH = 20       // 递归深度上限
        private const val DISSOLVE_BATCH_SIZE = 50      // 必须与 moveFiles 内部 chunked(50) 一致，
                                                        // 保证一次调用恰好对应一个服务端批次
        fun factory(
            repository: GuangyaBrowserRepository,
            strmRepository: GuangyaStrmRepository,
            recordRepository: CloudStrmRecordRepository,
            movieRepository: MovieRepository,
            scrapeRepository: StrmScrapeRepository,
            settingsRepository: AppSettingsRepository,
            offlineMemory: GuangyaOfflineMemory,
            offlineSubmitter: GuangyaOfflineSubmitter
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                return GuangyaBrowserViewModel(
                    repository,
                    strmRepository,
                    recordRepository,
                    movieRepository,
                    scrapeRepository,
                    settingsRepository,
                    offlineMemory,
                    offlineSubmitter
                ) as T
            }
        }
    }
}
