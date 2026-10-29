package com.example.localmovielibrarz.ui.guangya
import com.example.localmovielibrarz.ui.AppRadius

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOff
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.VideoFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import com.example.localmovielibrarz.util.VideoFormats
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.localmovielibrarz.cloudguangya.GuangyaFileItem
import com.example.localmovielibrarz.cloudguangya.GuangyaFolderRef
import com.example.localmovielibrarz.cloudguangya.GuangyaOfflineProgress
import com.example.localmovielibrarz.ui.cloud.components.CloudBottomSheet
import com.example.localmovielibrarz.ui.cloud.components.CloudOperationBar
import com.example.localmovielibrarz.ui.cloud.components.CloudSearchBar
import com.example.localmovielibrarz.ui.cloud.components.CloudSearchScope
import com.example.localmovielibrarz.ui.cloud.components.CloudPrimaryAction
import com.example.localmovielibrarz.ui.cloud.components.CloudToolEntryCard
import com.example.localmovielibrarz.ui.cloud.components.SelectionBar
import com.example.localmovielibrarz.ui.settings.settingsTextFieldColors
import com.example.localmovielibrarz.ui.SEARCH_DEBOUNCE_MS

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.sizeIn
import com.example.localmovielibrarz.ui.MinTouchTarget
import com.example.localmovielibrarz.ui.ContentAlphaMin
import com.example.localmovielibrarz.ui.shared.formatFileSize

/**
 * 光鸭云盘浏览 / 添加界面（操作逻辑对齐 115 CloudBrowserScreen）。
 * 已接入导航图（Route.GuangyaBrowser），也可嵌入网盘 tab（topInsetHandled=true 时由外层切换栏处理状态栏）。
 *
 * - 点视频 = 播放（onPlayVideo 回调，AppRoot 构造 guangya://guangya_res/$fileId 交给播放器）。
 * - 行右侧「添加」= 入库并自动刮削；目录行「添加」= 递归添加整个文件夹（带确认弹窗）。
 * - 「离线下载」= 完整功能版：txt 批量导入 + 记忆去重/黑名单 + 每日限额 + 3 路并发 + 暂停/继续 + 文件夹选择 + 记忆导出/导入。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GuangyaBrowserScreen(
    viewModel: GuangyaBrowserViewModel,
    onBack: () -> Unit,
    onPlayVideo: (GuangyaFileItem) -> Unit,
    topInsetHandled: Boolean = false,
    backEnabled: Boolean = true, // ★ E-1：pager 双页保活时仅当前页接管返回
    onOpenFileCleaner: ((rootParentId: String, rootName: String) -> Unit)? = null,
    onOpenEmptyFolderCleaner: ((rootParentId: String, rootName: String) -> Unit)? = null
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val offlineProgress by viewModel.offlineProgress.collectAsStateWithLifecycle()
    val offlineLogs by viewModel.offlineLogs.collectAsStateWithLifecycle()
    val statusBarPadding = if (topInsetHandled) Modifier else Modifier.windowInsetsPadding(WindowInsets.statusBars)
    var pendingFolder by remember { mutableStateOf<GuangyaFileItem?>(null) }
    var showOfflineDialog by remember { mutableStateOf(false) }
    // 需求 4：工具箱弹层状态（收叠 UI）
    var showToolbox by remember { mutableStateOf(false) }
    // 需求 3：文件管理弹窗状态（回收站确认 / 移动目录选择）
    var showRecycleConfirm by remember { mutableStateOf(false) }
    var showMoveDialog by remember { mutableStateOf(false) }
    // 需求 3 补充：批量重命名（模板化）/ 解散子目录
    var showBatchRenameDialog by remember { mutableStateOf(false) }
    var showDissolveConfirm by remember { mutableStateOf(false) }
    // ★ 第九轮（问题 1）：批量重命名完成检测——batchRenameRunning（ViewModel 专用字段）
    // 从 true 变 false（成功/失败均已复位）时自动关闭弹窗，停留当前目录；操作中禁止手动关闭
    var batchRenameWasRunning by remember { mutableStateOf(false) }

    // 需求 2：搜索关键字本地状态（受控输入，清空时与 ViewModel 同步）
    var queryText by remember { mutableStateOf(state.searchQuery) }
    var showSearch by remember { mutableStateOf(false) } // 问题7：搜索条收起，点图标呼出

    // 系统返回（含手势左滑）：先收起搜索条 → 再清搜索 → 再返回上级目录，栈空才退出页面
    fun handleBack() {
        if (showSearch) { showSearch = false; queryText = ""; viewModel.clearSearchQuery(); return }
        if (state.searchQuery.isNotBlank()) { viewModel.clearSearchQuery(); return }
        if (viewModel.canGoBackFolder()) {
            viewModel.goUp()
        } else {
            onBack()
        }
    }
    BackHandler(enabled = backEnabled, onBack = ::handleBack)

    // 提示信息自动清除
    LaunchedEffect(state.message) {
        state.message?.let {
            delay(2_500)
            viewModel.clearMessage()
        }
    }

    // ★ 第九轮（问题 1）：批量重命名完成 → 弹窗自动关闭（仍停留在当前目录，多选态保留）
    LaunchedEffect(state.batchRenameRunning) {
        if (state.batchRenameRunning && showBatchRenameDialog) {
            batchRenameWasRunning = true
        } else if (!state.batchRenameRunning && batchRenameWasRunning) {
            batchRenameWasRunning = false
            showBatchRenameDialog = false // 完成（成功或失败）后关闭，仍停留在当前目录
        }
    }

    if (!viewModel.isLoggedIn()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .then(statusBarPadding)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "尚未登录光鸭云盘",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "请先到「设置 → 网盘设置 → 光鸭云盘」完成手机号登录，再来浏览网盘并添加影片。",
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f),
                style = MaterialTheme.typography.bodyMedium
            )
            Button(onClick = onBack) { Text("返回") }
        }
        return
    }

    LaunchedEffect(Unit) {
        if (state.files.isEmpty() && state.error == null && !state.loading) {
            viewModel.restoreOrRoot()
        }
    }

    pendingFolder?.let { folder ->
        AlertDialog(
            onDismissRequest = { pendingFolder = null },
            title = { Text("添加整个文件夹？") },
            text = {
                Text(
                    "确认后会递归读取“${folder.name}”内的视频，逐个入库并自动刮削。"
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        pendingFolder = null
                        viewModel.addFolderToLibrary(folder)
                    }
                ) {
                    Text("开始添加")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingFolder = null }) {
                    Text("取消")
                }
            }
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        GuangyaTopBar(
            path = state.path,
            onBack = ::handleBack,
            topInsetHandled = topInsetHandled
        )

        // 统一操作栏：返回上级 + 根目录（左） / 搜索 + 工具箱 + 离线下载（右）
        CloudOperationBar(
            canGoBack = viewModel.canGoBackFolder(),
            onGoBack = viewModel::goUp,
            onGoRoot = viewModel::enterRoot,
            primaryAction = if (state.loading) CloudPrimaryAction.Loading
                            else CloudPrimaryAction.OfflineDownload { showOfflineDialog = true },
            onToolbox = { showToolbox = true },
            onSearch = { showSearch = true }
        )

        // H+1 轮：搜索防抖 300ms。此前每输入一个字符就打一次网盘 API，
        // 输入 8 个字 = 8 次并发请求，结果乱序覆盖且容易被限流。
        LaunchedEffect(queryText) {
            if (queryText.isBlank()) return@LaunchedEffect
            delay(SEARCH_DEBOUNCE_MS)
            viewModel.searchFiles(queryText)
        }

        // 收起式搜索条（问题7）：默认隐藏，点操作栏搜索图标呼出（带展开/收起动画）
        AnimatedVisibility(
            visible = showSearch,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            CloudSearchBar(
                query = queryText,
                onQueryChange = { queryText = it },
                onCollapse = { showSearch = false; queryText = ""; viewModel.clearSearchQuery() },
                isSearching = state.searching,
                searchScope = CloudSearchScope.Global,
                onScopeChange = { },
                showScopeSwitcher = false
            )
        }

        // 需求 4：原整宽「工具箱：解散子目录」按钮与整宽搜索框已收叠到顶部图标 / 工具箱弹层

        // 完整版离线下载面板
        if (showOfflineDialog) {
            OfflineDownloadDialog(
                viewModel = viewModel,
                progress = offlineProgress,
                logs = offlineLogs,
                defaultParentId = state.currentParentId,
                onDismiss = { showOfflineDialog = false }
            )
        }

        // 统一工具箱弹层（四件套：文件清理 / 空文件夹清理 / 解散子目录 / 整理文件）
        if (showToolbox) {
            CloudBottomSheet(title = "工具箱", onDismiss = { showToolbox = false }) {
                CloudToolEntryCard(Icons.Rounded.DeleteSweep, "文件清理", "按关键词/后缀/大小预扫描，勾选后分批移入回收站") {
                    if (!state.initialized) {
                        viewModel.notifyNotReady()
                        return@CloudToolEntryCard
                    }
                    showToolbox = false; onOpenFileCleaner?.invoke(state.currentParentId, state.path.lastOrNull() ?: "根目录")
                }
                CloudToolEntryCard(Icons.Rounded.CreateNewFolder, "空文件夹清理", "递归扫描当前目录，列出空文件夹，勾选后移入回收站") {
                    if (!state.initialized) {
                        viewModel.notifyNotReady()
                        return@CloudToolEntryCard
                    }
                    showToolbox = false
                    onOpenEmptyFolderCleaner?.invoke(state.currentParentId, state.path.lastOrNull() ?: "当前目录")
                }
                CloudToolEntryCard(Icons.Rounded.FolderOff, "解散子目录", "把子目录中的文件移动到当前目录") {
                    showToolbox = false; showDissolveConfirm = true
                }
                CloudToolEntryCard(Icons.Rounded.SelectAll, "整理文件", "多选 → 移动 / 回收站 / 批量重命名") {
                    showToolbox = false; viewModel.enterSelectionMode()
                }
                CloudToolEntryCard(Icons.Rounded.DriveFileRenameOutline, "批量重命名", "删除/替换 · 插入 · 全新命名 · 正则，支持模板") {
                    showToolbox = false
                    viewModel.enterSelectionMode()
                }
            }
        }

        state.message?.let { msg ->
            Text(
                text = msg,
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
            )
        }
        state.error?.let { err ->
            Text(
                text = "出错：$err",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
            )
        }

        when {
            state.searching -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.onSurface)
                    Text("正在搜索…", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                }
            }
            state.searchQuery.isNotBlank() && state.error != null && state.files.isEmpty() -> Box(
                Modifier.fillMaxSize().padding(18.dp), contentAlignment = Alignment.Center
            ) {
                Text("搜索出错：${state.error}", color = MaterialTheme.colorScheme.error)
            }
            state.searchQuery.isNotBlank() && state.files.isEmpty() -> Box(
                Modifier.fillMaxSize().padding(18.dp), contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("未搜到「${state.searchQuery}」", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.74f))
                    TextButton(onClick = { viewModel.clearSearchQuery() }) { Text("退出搜索") }
                }
            }
            state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.onSurface)
            }
            state.error != null && state.files.isEmpty() -> Unit // 常规错误已在顶部展示
            state.files.isEmpty() -> Box(
                Modifier.fillMaxSize().padding(18.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "这个目录是空的",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.74f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(14.dp)
            ) {
                items(state.files, key = { it.fileId }) { file ->
                    GuangyaFileRow(
                        file = file,
                        isAdding = file.fileId in state.addingFileIds,
                        isAdded = file.fileId in state.addedFileIds,
                        isExcluded = !file.isDir && viewModel.isVideoNameExcluded(file.name, file.size),
                        selectionMode = state.selectionMode,
                        selected = file.fileId in state.selectedIds,
                        onToggleSelect = { viewModel.toggleSelect(file.fileId) },
                        onLongPressSelect = {
                            if (!state.selectionMode) viewModel.enterSelectionMode()
                            viewModel.toggleSelect(file.fileId)
                        },
                        onOpen = {
                            if (state.selectionMode) {
                                viewModel.toggleSelect(file.fileId)
                            } else if (file.isDir) {
                                viewModel.openFolder(file)
                            } else {
                                onPlayVideo(file)
                            }
                        },
                        onAdd = {
                            if (file.isDir) pendingFolder = file else viewModel.addVideoToLibrary(file)
                        }
                    )
                }
            }
        }

        // 需求 3：多选操作栏（移入回收站 / 移动 / 批量重命名 / 退出）——公共组件（修复二轮第三轮）
        if (state.selectionMode) {
            SelectionBar(
                selectedCount = state.selectedIds.size,
                totalCount = state.files.size,
                showMove = true,
                showRename = false,                                  // ★ 旧简版入口关闭（修复二轮问题 B）
                showBatchRename = state.selectedIds.size >= 1,       // ★ ≥1 即显示（原 >=2）：单个也走 v3 弹窗
                showDelete = true,
                onSelectAll = viewModel::selectAll,
                onRecycle = { showRecycleConfirm = true },
                onMove = { showMoveDialog = true },
                onRename = { },                                      // 占位（按钮已隐藏）
                onBatchRename = { showBatchRenameDialog = true },
                onExit = viewModel::exitSelectionMode
            )
        }
    }

    // 需求 3：移入回收站确认
    if (showRecycleConfirm) {
        AlertDialog(
            onDismissRequest = { showRecycleConfirm = false },
            title = { Text("移入回收站？") },
            text = {
                Text("确定将选中的 ${state.selectedIds.size} 项移入回收站吗？回收站内可恢复，不会彻底删除。")
            },
            confirmButton = {
                Button(onClick = {
                    showRecycleConfirm = false
                    viewModel.recycleSelected()
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showRecycleConfirm = false }) { Text("取消") }
            }
        )
    }

    // 需求 3：移动目标目录选择
    if (showMoveDialog) {
        MoveTargetDialog(
            initialFolders = { parentId -> viewModel.listOfflineFolders(parentId) },
            searchFolders = { keyword -> if (keyword.isBlank()) emptyList() else viewModel.searchOfflineFolders(keyword) },
            onMove = { targetId ->
                showMoveDialog = false
                viewModel.moveSelectedTo(targetId)
            },
            onDismiss = { showMoveDialog = false }
        )
    }

    // 需求 3 补充：批量重命名 v3（四类操作 + 正则增强 + 模板；单项/多项通用，修复二轮问题 B）
    if (showBatchRenameDialog) {
        BatchRenameDialog(
            viewModel = viewModel,
            selectedFiles = state.files
                .filter { it.fileId.isNotBlank() && it.fileId in state.selectedIds }
                .sortedBy { it.name },
            onConfirm = { config ->
                // ★ 第九轮（问题 1）：不再立即关闭弹窗——停留展示进度，完成后由
                // LaunchedEffect(state.batchRenameRunning) 自动关闭
                viewModel.batchRenameSelected(config)
            },
            onMultiConfirm = { rules ->
                // ★ 同上：不立即关闭
                viewModel.batchRenameMultiRule(rules)
            },
            onDismiss = { if (!state.batchRenameRunning) showBatchRenameDialog = false } // ★ 操作中禁止点外部/返回关闭
        )
    }

    // 需求 3 补充：解散子目录确认
    if (showDissolveConfirm) {
        AlertDialog(
            onDismissRequest = { showDissolveConfirm = false },
            title = { Text("解散子目录？") },
            text = {
                Text(
                    "会把当前目录下所有子目录（含更深层）中的文件移动到当前目录并刷新；" +
                        "与当前目录重名的文件会被跳过，文件夹本身不会被删除。此操作不可撤销。"
                )
            },
            confirmButton = {
                Button(onClick = {
                    showDissolveConfirm = false
                    viewModel.dissolveSubdirectories()
                }) { Text("开始解散") }
            },
            dismissButton = {
                TextButton(onClick = { showDissolveConfirm = false }) { Text("取消") }
            }
        )
    }
}

// ==================== 离线下载完整面板 ====================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OfflineDownloadDialog(
    viewModel: GuangyaBrowserViewModel,
    progress: GuangyaOfflineProgress,
    logs: List<String>,
    defaultParentId: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var offlineText by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<OfflineParsePreview?>(null) }
    var limitInput by remember { mutableStateOf(viewModel.offlineDailyLimit().toString()) }
    var showFolderPicker by remember { mutableStateOf(false) }
    var showMemoryDialog by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    var importText by remember { mutableStateOf("") }
    var pendingClearMemory by remember { mutableStateOf(false) }

    val targetFolder by viewModel.offlineTargetFolderFlow.collectAsStateWithLifecycle()

    // 按钮统一样式
    val dialogButtonShape = AppRadius.MD

    // 选本地 txt（SAF），自动填充并解析
    val txtLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val text = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                }.getOrNull()
            } ?: ""
            offlineText = text
            preview = viewModel.parseOfflineText(text)
        }
    }

    // 内容较多，改用 ModalBottomSheet 提供更好的滚动体验（避免 AlertDialog 内容超出屏幕）
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "离线下载",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 4.dp)
            )
                // 1. 目标文件夹
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "目标文件夹：${targetFolder?.name ?: "（当前目录）"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedButton(
                        onClick = { showFolderPicker = true },
                        shape = dialogButtonShape
                    ) {
                        Text("选择文件夹")
                    }
                    if (targetFolder != null) {
                        OutlinedButton(
                            onClick = { viewModel.setOfflineTargetFolder(null) },
                            shape = dialogButtonShape
                        ) {
                            Text("清除")
                        }
                    }
                }

                // 2. 输入区
                OutlinedTextField(
                    value = offlineText,
                    onValueChange = {
                        offlineText = it
                        preview = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("磁力链接 / .torrent URL / ed2k（每行一个）") },
                    minLines = 4,
                    maxLines = 6,
                    shape = dialogButtonShape,
                    colors = settingsTextFieldColors(),
                    trailingIcon = {
                        if (offlineText.isNotBlank()) {
                            IconButton(onClick = { offlineText = ""; preview = null }) {
                                Icon(Icons.Rounded.Close, contentDescription = "清空")
                            }
                        }
                    },
                    placeholder = { Text("magnet:?xt=urn:btih:...\n或 40 位 hex / 32 位 base32\n或直接选本地 txt 文件") }
                )
                OutlinedButton(
                    onClick = { txtLauncher.launch(arrayOf("text/plain", "text/*")) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = dialogButtonShape
                ) {
                    Text("选本地 txt 导入")
                }

                // 3. 解析统计
                preview?.let { p ->
                    val pendingCount = p.magnets.size
                    Text(
                        text = buildString {
                            append("识别 $pendingCount 个 · 待提交 ${(pendingCount - p.submittedSkip - p.failedSkip).coerceAtLeast(0)} 个")
                            if (p.submittedSkip > 0) append(" · 已提交跳过 ${p.submittedSkip}")
                            if (p.failedSkip > 0) append(" · 黑名单跳过 ${p.failedSkip}")
                        },
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                // 4. 每日限额 — label 固定 64dp，输入框 weight(1f)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "每日限额",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f),
                        modifier = Modifier.width(64.dp)
                    )
                    OutlinedTextField(
                        value = limitInput,
                        onValueChange = { limitInput = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall,
                        shape = dialogButtonShape,
                        colors = settingsTextFieldColors(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    TextButton(
                        onClick = {
                            val n = limitInput.toIntOrNull()
                            if (n != null && n in 1..100000) {
                                viewModel.setOfflineDailyLimit(n)
                            } else {
                                limitInput = viewModel.offlineDailyLimit().toString()
                            }
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) {
                        Text("保存", style = MaterialTheme.typography.labelMedium, maxLines = 1)
                    }
                    Text(
                        text = "${viewModel.offlineTodayCount()}/${viewModel.offlineDailyLimit()}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
                        maxLines = 1
                    )
                }

                // 5. 操作按钮 — 统一 44dp 高度，maxLines=1 防换行
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = {
                            if (preview == null) {
                                preview = viewModel.parseOfflineText(offlineText)
                            } else if (preview!!.magnets.isNotEmpty()) {
                                // 开始上传前自动保存当前输入的限额值，避免用户忘了点保存
                                val n = limitInput.toIntOrNull()
                                if (n != null && n in 1..100000) viewModel.setOfflineDailyLimit(n)
                                val parentId = targetFolder?.fileId ?: defaultParentId
                                viewModel.startOffline(preview!!.magnets, parentId)
                            }
                        },
                        enabled = !progress.running,
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp),
                        shape = AppRadius.MD,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) {
                        Text(
                            if (preview == null) "解析" else "开始上传",
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (progress.running) {
                        Button(
                            onClick = {
                                if (progress.paused) viewModel.resumeOffline() else viewModel.pauseOffline()
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp),
                            shape = AppRadius.MD,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                        ) {
                            Text(
                                if (progress.paused) "继续" else "暂停",
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1
                            )
                        }
                        OutlinedButton(
                            onClick = viewModel::cancelOffline,
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp),
                            shape = AppRadius.MD,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                        ) {
                            Text("停止", style = MaterialTheme.typography.labelMedium, maxLines = 1)
                        }
                    }
                }

                // 6. 进度
                if (progress.running) {
                    LinearProgressIndicator(
                        progress = {
                            if (progress.total <= 0) 0f else (progress.done.toFloat() / progress.total).coerceIn(0f, 1f)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(AppRadius.XS)
                    )
                    Text(
                        text = "提交 ${progress.done}/${progress.total} · 成功 ${progress.ok} · 失败 ${progress.fail}" +
                            if (progress.paused) "（已暂停）" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f)
                    )
                }

                // 7. 日志区（最近 30 条，批量渲染防卡死）
                if (logs.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 100.dp, max = 200.dp)
                            .clip(AppRadius.SM)
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                            .verticalScroll(rememberScrollState())
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        logs.takeLast(30).forEach { line ->
                            Text(
                                text = line,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
                            )
                        }
                    }
                }

                // 8. 记忆管理 — 统一 labelMedium + 40dp 高度 + 单行
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { showMemoryDialog = true },
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) {
                        Text("记忆状态", style = MaterialTheme.typography.labelMedium, maxLines = 1)
                    }
                    OutlinedButton(
                        onClick = {
                            val json = viewModel.exportOfflineMemory()
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("光鸭离线记忆备份", json))
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) {
                        Text("导出记忆", style = MaterialTheme.typography.labelMedium, maxLines = 1)
                    }
                    OutlinedButton(
                        onClick = { importText = ""; showImportDialog = true },
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) {
                        Text("导入记忆", style = MaterialTheme.typography.labelMedium, maxLines = 1)
                    }
                }

                // 关闭
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    shape = dialogButtonShape
                ) {
                    Text("关闭")
                }
            }
        }

    // 文件夹选择器
    if (showFolderPicker) {
        FolderPickerDialog(
            viewModel = viewModel,
            initialParentId = targetFolder?.fileId ?: "",
            initialName = targetFolder?.name ?: "",
            initialParentPath = targetFolder?.parentPath ?: emptyList(),
            onSelect = { folder ->
                viewModel.setOfflineTargetFolder(folder)
                showFolderPicker = false
            },
            onDismiss = { showFolderPicker = false }
        )
    }

    // 记忆状态 + 清空确认
    if (showMemoryDialog) {
        AlertDialog(
            onDismissRequest = { showMemoryDialog = false },
            title = { Text("记忆状态") },
            text = { Text(viewModel.offlineMemoryStats()) },
            confirmButton = {
                TextButton(onClick = { showMemoryDialog = false }) { Text("知道了") }
            },
            dismissButton = {
                TextButton(onClick = { pendingClearMemory = true }) { Text("清空记忆") }
            }
        )
    }
    if (pendingClearMemory) {
        AlertDialog(
            onDismissRequest = { pendingClearMemory = false },
            title = { Text("清空全部记忆？") },
            text = { Text("清空后已提交的磁力链会被重新提交！仅调试用，不建议。") },
            confirmButton = {
                Button(onClick = {
                    viewModel.clearOfflineMemory()
                    pendingClearMemory = false
                    showMemoryDialog = false
                }) { Text("清空") }
            },
            dismissButton = {
                TextButton(onClick = { pendingClearMemory = false }) { Text("取消") }
            }
        )
    }

    // 导入记忆
    if (showImportDialog) {
        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            title = { Text("导入记忆") },
            text = {
                OutlinedTextField(
                    value = importText,
                    onValueChange = { importText = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("粘贴导出时的 JSON 备份内容") },
                    minLines = 5,
                    maxLines = 8
                )
            },
            confirmButton = {
                Button(onClick = {
                    val result = viewModel.importOfflineMemory(importText.trim())
                    showImportDialog = false
                }) { Text("导入") }
            },
            dismissButton = {
                TextButton(onClick = { showImportDialog = false }) { Text("取消") }
            }
        )
    }
}

// ==================== 文件夹选择器 ====================

@Composable
private fun FolderPickerDialog(
    viewModel: GuangyaBrowserViewModel,
    initialParentId: String,
    initialName: String = "",
    initialParentPath: List<GuangyaFolderRef> = emptyList(),
    onSelect: (GuangyaFolderRef) -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var currentParentId by remember { mutableStateOf(initialParentId) }
    // parentStack 仅记录用户在弹窗内的真实导航历史（每进入一层，将当前 parentId 入栈）。
    // 审查 P3：再次打开时若已有目标文件夹，用 initialParentPath 重建栈，使「返回上级」可见且能逐级回到真实上级目录。
    // pathNames 预填初始目录名（及初始父链名）仅用于路径展示和「选择此文件夹」的名称显示。
    val parentStack = remember {
        mutableStateListOf<String>().apply { addAll(initialParentPath.map { it.fileId }) }
    }
    val pathNames = remember {
        mutableStateListOf<String>().apply {
            initialParentPath.forEach { add(it.name) }
            if (initialParentId.isNotBlank() && initialName.isNotBlank()) add(initialName)
        }
    }
    var folders by remember { mutableStateOf<List<GuangyaFileItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var searchKeyword by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }

    fun load(parentId: String) {
        loading = true
        error = null
        scope.launch {
            runCatching { viewModel.listOfflineFolders(parentId) }
                .onSuccess { folders = it }
                .onFailure { error = it.message }
            loading = false
        }
    }

    fun search(keyword: String) {
        if (keyword.isBlank()) return
        searching = true
        loading = true
        error = null
        scope.launch {
            runCatching { viewModel.searchOfflineFolders(keyword) }
                .onSuccess {
                    folders = it
                    searching = false
                }
                .onFailure {
                    error = it.message
                    searching = false
                }
            loading = false
        }
    }

    LaunchedEffect(Unit) {
        load(initialParentId)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择目标文件夹") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // 搜索行
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = searchKeyword,
                        onValueChange = { searchKeyword = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        label = { Text("搜索文件夹") },
                        textStyle = MaterialTheme.typography.bodySmall
                    )
                    Button(onClick = { search(searchKeyword.trim()) }, shape = AppRadius.MD) {
                        Text("搜索")
                    }
                }
                // 当前路径 / 搜索提示
                Text(
                    text = when {
                        searching -> "搜索结果（点选即选中）"
                        pathNames.isNotEmpty() -> "当前：${pathNames.joinToString(" / ")}"
                        else -> "当前：根目录"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                error?.let {
                    Text(
                        text = "出错：$it",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                // 目录列表
                if (loading) {
                    Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else if (folders.isEmpty()) {
                    Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                        Text(
                            text = if (searching) "未搜到文件夹" else "此目录没有子文件夹",
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                } else {
                    LazyColumn(modifier = Modifier.height(220.dp)) {
                        items(folders, key = { it.fileId }) { folder ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(AppRadius.SM)
                                .clickable {
                                    if (searching) {
                                        onSelect(GuangyaFolderRef(folder.fileId, folder.name, parentPath = emptyList()))
                                    } else {
                                        parentStack.add(currentParentId)
                                        pathNames.add(folder.name)
                                        currentParentId = folder.fileId
                                        load(folder.fileId)
                                    }
                                }
                                    .padding(horizontal = 8.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Rounded.Folder,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.tertiary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(
                                    text = folder.name,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(start = 8.dp)
                                )
                                if (searching) {
                                    Text(
                                        "选中",
                                        color = MaterialTheme.colorScheme.primary,
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (!searching) {
                Button(
                    onClick = {
                        // 审查 P3：当前文件夹的父链 = parentStack[i] 是 depth-(i+1) 文件夹的父 id，
                        // pathNames[i] 是该文件夹名，故 ancestor(d) 的 id=parentStack[d]、name=pathNames[d-1]
                        val currentPathChain = buildList {
                            for (d in 1 until parentStack.size) {
                                val id = parentStack[d]
                                val name = pathNames.getOrNull(d - 1) ?: continue
                                if (id.isNotBlank()) add(GuangyaFolderRef(id, name))
                            }
                        }
                        onSelect(
                            GuangyaFolderRef(
                                fileId = currentParentId,
                                name = pathNames.lastOrNull() ?: "(根目录)",
                                parentPath = currentPathChain
                            )
                        )
                    },
                    enabled = !loading
                ) { Text("选择此文件夹") }
            } else {
                TextButton(onClick = { searching = false; load(currentParentId) }) {
                    Text("退出搜索")
                }
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (parentStack.isNotEmpty() && !searching) {
                    TextButton(onClick = {
                        // parentStack 记录真实的导航历史，「返回上级」弹出栈顶即为父目录。
                        // 初始进入时（已有目标文件夹）parentStack 为空，不显示此按钮，避免误跳根目录。
                        currentParentId = parentStack.removeAt(parentStack.size - 1)
                        pathNames.removeAt(pathNames.size - 1)
                        load(currentParentId)
                    }) { Text("返回上级") }
                }
                if (!searching) {
                    TextButton(onClick = {
                        parentStack.clear()
                        pathNames.clear()
                        currentParentId = ""
                        load("")
                    }) { Text("根目录") }
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        }
    )
}

// ==================== 既有组件 ====================

@Composable
private fun GuangyaTopBar(
    path: List<String>,
    onBack: () -> Unit,
    topInsetHandled: Boolean
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (topInsetHandled) {
                    // 嵌入网盘 tab：背景由外层渐变容器统一提供
                    Modifier.background(Color.Transparent)
                } else {
                    Modifier.background(
                        Brush.verticalGradient(
                            listOf(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.background)
                        )
                    )
                }
            )
            .then(
                if (topInsetHandled) Modifier else Modifier.windowInsetsPadding(WindowInsets.statusBars)
            )
            .padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.sizeIn(minWidth = MinTouchTarget, minHeight = MinTouchTarget)) {
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "返回",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(22.dp)
                )
            }
            Icon(
                Icons.Rounded.Cloud,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f),
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = "光鸭云盘",
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp)
            )
        }
        Text(
            text = path.joinToString(" / ").ifBlank { "根目录" },
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 12.dp)
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GuangyaFileRow(
    file: GuangyaFileItem,
    isAdding: Boolean,
    isAdded: Boolean,
    isExcluded: Boolean,
    selectionMode: Boolean,
    selected: Boolean,
    onToggleSelect: () -> Unit,
    onLongPressSelect: () -> Unit,
    onOpen: () -> Unit,
    onAdd: () -> Unit
) {
    val bgColor = when {
        selected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
        isExcluded -> MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlphaMin)
        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.075f)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(AppRadius.LG)
            .background(bgColor)
            .combinedClickable(onClick = onOpen, onLongClick = onLongPressSelect)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (file.isDir) Icons.Rounded.Folder else Icons.Rounded.VideoFile,
                contentDescription = null,
                tint = if (file.isDir) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.76f)
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 10.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                text = file.name,
                color = if (isExcluded) MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlphaMin) else MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = if (isExcluded) "${file.subtitle()} · 命中排除名单" else file.subtitle(),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.56f),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (selectionMode) {
            Checkbox(
                checked = selected,
                onCheckedChange = { onToggleSelect() }
            )
        } else {
            GuangyaAddButton(isAdding = isAdding, isAdded = isAdded, isExcluded = isExcluded, onAdd = onAdd)
        }
    }
}

@Composable
private fun GuangyaAddButton(isAdding: Boolean, isAdded: Boolean, isExcluded: Boolean = false, onAdd: () -> Unit) {
    if (isAdded) {
        Text(
            text = "已添加",
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.52f),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp)
        )
        return
    }
    if (isAdding) {
        Text(
            text = "添加中",
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.52f),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp)
        )
        return
    }
    if (isExcluded) {
        Text(
            text = "已排除",
            color = MaterialTheme.colorScheme.error.copy(alpha = 0.72f),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 10.dp)
        )
        return
    }
    Button(
        onClick = onAdd,
        shape = AppRadius.MD,
        contentPadding = PaddingValues(horizontal = 11.dp, vertical = 6.dp),
        modifier = Modifier.height(34.dp)
    ) {
        Text("添加", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
    }
}

private fun GuangyaFileItem.subtitle(): String = when {
    isDir -> "目录 · 点按进入"
    isVideoFile() -> "视频 · ${formatSize(size)}"
    else -> "文件 · ${formatSize(size)}"
}

private fun GuangyaFileItem.isVideoFile(): Boolean =
    !isDir && VideoFormats.isVideoFileName(name)

// H+1 轮：改为复用统一实现（此前 3 个文件各有一份逐字相同的副本）
private fun formatSize(bytes: Long): String = formatFileSize(bytes)

// ==================== 需求 3：文件管理组件（移动目录选择 / 重命名） ====================

/** 移动目标目录选择：可逐级浏览目录树 + 全局搜索目录。目标仅支持移动到目录（父目录 fileId）。 */
@Composable
private fun MoveTargetDialog(
    initialFolders: suspend (String) -> List<GuangyaFileItem>,
    searchFolders: suspend (String) -> List<GuangyaFileItem>,
    onMove: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var parentId by remember { mutableStateOf("") }
    var idStack by remember { mutableStateOf<List<String>>(emptyList()) }
    var nameStack by remember { mutableStateOf<List<String>>(emptyList()) }
    var folders by remember { mutableStateOf<List<GuangyaFileItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<GuangyaFileItem>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }

    fun load(parent: String) {
        scope.launch {
            loading = true
            results = emptyList()
            query = ""
            runCatching { initialFolders(parent) }
                .onSuccess { folders = it }
                .onFailure { folders = emptyList() }
            loading = false
        }
    }

    fun search(kw: String) {
        val q = kw.trim()
        if (q.isBlank()) {
            results = emptyList()
            searching = false
            return
        }
        scope.launch {
            searching = true
            runCatching { searchFolders(q) }
                .onSuccess { results = it }
                .onFailure { results = emptyList() }
            searching = false
        }
    }

    LaunchedEffect(Unit) { load("") }

    // H+1 轮：文件夹搜索同样防抖（原实现每输入一个字符即发起一次请求）
    LaunchedEffect(query) {
        if (query.isBlank()) return@LaunchedEffect
        delay(SEARCH_DEBOUNCE_MS)
        search(query)
    }

    val breadcrumb = if (nameStack.isEmpty()) "根目录" else nameStack.joinToString(" / ")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("移动到：$breadcrumb") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (parentId.isNotBlank()) {
                        TextButton(
                            onClick = {
                                if (idStack.isNotEmpty()) {
                                    parentId = idStack.last()
                                    idStack = idStack.dropLast(1)
                                    nameStack = nameStack.dropLast(1)
                                    load(parentId)
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 6.dp)
                        ) {
                            Text("上级", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("搜索目标文件夹", style = MaterialTheme.typography.bodySmall) },
                        singleLine = true,
                        shape = AppRadius.MD,
                        trailingIcon = if (query.isNotBlank()) {
                            {
                                IconButton(onClick = { query = ""; search("") }) {
                                    Icon(Icons.Rounded.Close, contentDescription = "清除")
                                }
                            }
                        } else null,
                        modifier = Modifier.weight(1f)
                    )
                }

                when {
                    searching -> Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.onSurface)
                    }
                    results.isNotEmpty() -> LazyColumn(Modifier.height(220.dp)) {
                        items(results, key = { it.fileId }) { dir ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(AppRadius.MD)
                                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                                    .clickable {
                                        idStack = idStack + parentId
                                        nameStack = nameStack + dir.name
                                        parentId = dir.fileId
                                        load(dir.fileId)
                                    }
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Rounded.Folder,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.tertiary,
                                    modifier = Modifier.padding(end = 8.dp)
                                )
                                Text(
                                    text = dir.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                    loading -> Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.onSurface)
                    }
                    folders.isEmpty() -> Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                        Text("目录为空", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    }
                    else -> LazyColumn(Modifier.height(240.dp)) {
                        items(folders, key = { it.fileId }) { dir ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(AppRadius.MD)
                                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                                    .clickable {
                                        idStack = idStack + parentId
                                        nameStack = nameStack + dir.name
                                        parentId = dir.fileId
                                        load(dir.fileId)
                                    }
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Rounded.Folder,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.tertiary,
                                    modifier = Modifier.padding(end = 8.dp)
                                )
                                Text(
                                    text = dir.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onMove(parentId) }) { Text("移动到当前目录") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
