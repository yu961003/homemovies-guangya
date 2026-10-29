package com.example.localmovielibrarz.ui.cloud
import com.example.localmovielibrarz.ui.AppRadius

import android.widget.Toast
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.VideoFile
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import com.example.localmovielibrarz.util.VideoFormats
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.localmovielibrarz.cloud115.Cloud115FileItem
import com.example.localmovielibrarz.data.local.CloudFolderBatchTaskEntity
import com.example.localmovielibrarz.data.local.CloudFolderBatchTaskStatus
import com.example.localmovielibrarz.ui.cloud.components.CloudBottomSheet
import com.example.localmovielibrarz.ui.cloud.components.CloudOperationBar
import com.example.localmovielibrarz.ui.cloud.components.CloudPrimaryAction
import com.example.localmovielibrarz.ui.cloud.components.CloudSearchBar
import com.example.localmovielibrarz.ui.cloud.components.CloudSearchScope
import com.example.localmovielibrarz.ui.cloud.components.CloudToolEntryCard
import com.example.localmovielibrarz.ui.SEARCH_DEBOUNCE_MS

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.foundation.layout.sizeIn
import com.example.localmovielibrarz.ui.MinTouchTarget
import com.example.localmovielibrarz.ui.shared.formatFileSizeOrUnknown
import com.example.localmovielibrarz.ui.shared.folderBatchAggregateProgress
import com.example.localmovielibrarz.ui.shared.folderBatchProgress
import com.example.localmovielibrarz.ui.shared.FolderBatchProgress

private val CloudBackground: Color
    @Composable get() = MaterialTheme.colorScheme.background
private val CloudPanel: Color
    @Composable get() = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.075f)

@Composable
fun CloudBrowserScreen(
    viewModel: CloudBrowserViewModel,
    onBack: () -> Unit,
    onPlayVideo: (Cloud115FileItem) -> Unit,
    onMovieAdded: (Long) -> Unit,
    topInsetHandled: Boolean = false,
    backEnabled: Boolean = true, // ★ E-1：pager 双页保活时仅当前页接管返回
    onOpenCloudClean: (() -> Unit)? = null,
    onOpenEmptyCleaner: ((rootCid: Long, rootName: String) -> Unit)? = null
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var pendingBatchFolder by remember { mutableStateOf<Cloud115FileItem?>(null) }
    // 需求 13.4：工具箱底部面板
    var showToolSheet by remember { mutableStateOf(false) }
    // 需求 2：搜索关键字本地状态（受控输入，清空时与 ViewModel 同步）
    var queryText by remember { mutableStateOf(uiState.searchQuery) }
    // 问题7：搜索条收起，点操作栏搜索图标呼出
    var showSearch by remember { mutableStateOf(false) }
    val currentFolderCid = uiState.path.lastOrNull()?.cid ?: 0L
    val initialScrollPosition = remember(currentFolderCid) {
        viewModel.scrollPositionFor(currentFolderCid)
    }
    val listState = rememberSaveable(currentFolderCid, saver = LazyListState.Saver) {
        LazyListState(
            firstVisibleItemIndex = initialScrollPosition.firstVisibleItemIndex,
            firstVisibleItemScrollOffset = initialScrollPosition.firstVisibleItemScrollOffset
        )
    }

    fun handleBack() {
        if (showSearch) { showSearch = false; queryText = ""; viewModel.clearSearchQuery(); return }
        if (!viewModel.goBackFolder()) {
            onBack()
        }
    }

    BackHandler(enabled = backEnabled, onBack = ::handleBack)

    LaunchedEffect(uiState.message) {
        uiState.message?.let {
            val snackbarJob = launch { snackbarHostState.showSnackbar(it) }
            delay(1_600)
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarJob.cancel()
            viewModel.clearMessage()
        }
    }

    LaunchedEffect(uiState.openMovieId) {
        val movieId = uiState.openMovieId ?: return@LaunchedEffect
        viewModel.consumeOpenMovie()
        onMovieAdded(movieId)
    }

    LaunchedEffect(uiState.scrollResetVersion, currentFolderCid) {
        if (uiState.scrollResetVersion > 0) {
            yield()
            listState.scrollToItem(0)
            viewModel.saveScrollPosition(currentFolderCid, 0, 0)
        }
    }

    LaunchedEffect(currentFolderCid, listState) {
        snapshotFlow {
            listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        }.collect { (index, offset) ->
            viewModel.saveScrollPosition(currentFolderCid, index, offset)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(CloudBackground)
    ) {
        uiState.pendingReplaceConflict?.let { conflict ->
            AlertDialog(
                onDismissRequest = viewModel::dismissReplaceConflict,
                title = { Text("发现同番号影片") },
                text = {
                    Text(
                        "已存在：${conflict.oldFileName}\n\n新视频：${conflict.item.name}\n\n是否用新视频替换原来的播放源？选择“作为新片”会重新刮削并作为两部不同影片入库。"
                    )
                },
                confirmButton = {
                    Button(onClick = viewModel::confirmReplacePickcode) {
                        Text("替换")
                    }
                },
                dismissButton = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = viewModel::addConflictAsNewMovie) {
                            Text("作为新片")
                        }
                        Button(onClick = viewModel::dismissReplaceConflict) {
                            Text("取消")
                        }
                    }
                }
            )
        }
        pendingBatchFolder?.let { folder ->
            AlertDialog(
                onDismissRequest = { pendingBatchFolder = null },
                title = { Text("添加整个文件夹？") },
                text = {
                    Text(
                        "确认后会递归读取“${folder.name}”内的视频，并按当前默认刮削方式逐个加入队列。无法提取番号、命中排除名单或小于设置大小阈值的视频会直接跳过。"
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            pendingBatchFolder = null
                            viewModel.addFolderVideosToLibrary(folder)
                        }
                    ) {
                        Text("开始添加")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingBatchFolder = null }) {
                        Text("取消")
                    }
                }
            )
        }
        Column(modifier = Modifier.fillMaxSize()) {
            CloudTopBar(
                path = uiState.path,
                canGoBackFolder = viewModel.canGoBackFolder(),
                onBack = ::handleBack,
                sortOption = uiState.sortOption,
                onSortOptionSelected = viewModel::setSortOption,
                sortAscending = uiState.sortAscending,
                onToggleSortDirection = viewModel::toggleSortDirection,
                topInsetHandled = topInsetHandled
            )

            // UI 重构：统一操作栏（返回上级 + 根目录 + 工具箱 + 搜索入口）
            CloudOperationBar(
                canGoBack = viewModel.canGoBackFolder(),
                onGoBack = { viewModel.goBackFolder() },
                onGoRoot = viewModel::resetToRoot,
                primaryAction = if (uiState.isLoading) {
                    CloudPrimaryAction.Loading
                } else {
                    CloudPrimaryAction.Toolbox(onClick = { showToolSheet = true })
                },
                onSearch = { showSearch = true }
            )

            // 整夹添加的进度面板：主动作在这个页面发起，进度与暂停/继续也必须留在这里，
            // 否则用户只能被告知「去设置页看」，等于没有进度。
            if (uiState.folderBatchTasks.isNotEmpty()) {
                CloudFolderBatchPanel(
                    tasks = uiState.folderBatchTasks,
                    onPause = viewModel::pauseFolderBatchTask,
                    onResume = viewModel::resumeFolderBatchTask,
                    onCancel = viewModel::cancelFolderBatchTask
                )
            }

            // H+1 轮：搜索防抖 300ms（原实现每输入一个字符即请求一次网盘 API）
            LaunchedEffect(queryText) {
                if (queryText.isBlank()) return@LaunchedEffect
                delay(SEARCH_DEBOUNCE_MS)
                viewModel.searchFiles(queryText)
            }

            // UI 重构：收起式搜索条（问题7）——点操作栏搜索图标呼出（带展开/收起动画）
            AnimatedVisibility(
                visible = showSearch,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                CloudSearchBar(
                    query = queryText,
                    onQueryChange = { newQuery ->
                        queryText = newQuery
                        if (newQuery.isBlank()) {
                            viewModel.clearSearchQuery()
                        }
                    },
                    onCollapse = { showSearch = false; queryText = ""; viewModel.clearSearchQuery() },
                    isSearching = uiState.searching,
                    searchScope = uiState.searchScope,
                    onScopeChange = viewModel::setSearchScope,
                    showScopeSwitcher = true
                )
            }

            when {
                uiState.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.onSurface)
                }
                uiState.errorMessage != null -> CloudMessage(text = uiState.errorMessage.orEmpty())
                uiState.items.isEmpty() -> CloudMessage(text = "这个目录是空的")
                else -> CloudFileList(
                    items = uiState.items,
                    timeSortOption = uiState.sortOption,
                    listState = listState,
                    addingPickcodes = uiState.addingPickcodes,
                    addedPickcodes = uiState.addedPickcodes,
                    queuedPickcodes = uiState.queuedPickcodes,
                    failedPickcodes = uiState.failedPickcodes,
                    excludedVideoNames = uiState.excludedVideoNames,
                    onOpenFolder = viewModel::openFolder,
                    onPlayVideo = onPlayVideo,
                    onAddVideo = viewModel::addVideoToLibrary,
                    onRetryScrape = viewModel::retryFailedScrape,
                    onAddFolder = { pendingBatchFolder = it }
                )
            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 76.dp, start = 16.dp, end = 16.dp)
        )
    }

    // UI 重构：统一工具箱底部面板
    if (showToolSheet) {
        val currentDir = uiState.path.lastOrNull()
        CloudBottomSheet(
            title = "工具箱",
            onDismiss = { showToolSheet = false }
        ) {
            CloudToolEntryCard(
                icon = Icons.Rounded.DeleteSweep,
                title = "文件清理",
                subtitle = "按关键词 / 后缀 + 大小阈值扫描，批量移入回收站",
                onClick = {
                    showToolSheet = false
                    onOpenCloudClean?.invoke()
                }
            )
            CloudToolEntryCard(
                icon = Icons.Rounded.FolderOpen,
                title = "空文件夹清理",
                subtitle = "逐级识别真空文件夹，确认后批量移入回收站",
                onClick = {
                    showToolSheet = false
                    onOpenEmptyCleaner?.invoke(currentDir?.cid ?: 0L, currentDir?.name ?: "根目录")
                }
            )
        }
    }
}

@Composable
private fun CloudTopBar(
    path: List<CloudPathItem>,
    canGoBackFolder: Boolean,
    onBack: () -> Unit,
    sortOption: CloudSortOption,
    onSortOptionSelected: (CloudSortOption) -> Unit,
    sortAscending: Boolean,
    onToggleSortDirection: () -> Unit,
    topInsetHandled: Boolean = false,
    onOpenTools: (() -> Unit)? = null
) {
    var sortMenuExpanded by remember { androidx.compose.runtime.mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (topInsetHandled) {
                    // 嵌入网盘 tab：背景由外层渐变容器统一提供
                    Modifier.background(Color.Transparent)
                } else {
                    Modifier.background(
                        Brush.verticalGradient(listOf(MaterialTheme.colorScheme.surfaceVariant, CloudBackground))
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
                    contentDescription = if (canGoBackFolder) "返回上一级目录" else "返回影片",
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
                text = "115 网盘",
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp)
            )
            Box {
                Text(
                    text = sortOption.label,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(AppRadius.Full)
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f))
                        .clickable { sortMenuExpanded = true }
                        .padding(horizontal = 8.dp, vertical = 5.dp)
                )
                DropdownMenu(
                    expanded = sortMenuExpanded,
                    onDismissRequest = { sortMenuExpanded = false }
                ) {
                    CloudSortOption.entries.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.label) },
                            onClick = {
                                sortMenuExpanded = false
                                onSortOptionSelected(option)
                            }
                        )
                    }
                }
            }
            IconButton(onClick = onToggleSortDirection, modifier = Modifier.sizeIn(minWidth = MinTouchTarget, minHeight = MinTouchTarget)) {
                Icon(
                    imageVector = if (sortAscending) Icons.Rounded.ArrowUpward else Icons.Rounded.ArrowDownward,
                    contentDescription = "${sortOption.label}${if (sortAscending) "正序" else "倒序"}",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(20.dp)
                )
            }
            // 工具箱入口：仅当 onOpenTools 不为 null 时显示（UI 重构后由 CloudOperationBar 承载）
            if (onOpenTools != null) {
                OutlinedButton(
                    onClick = onOpenTools,
                    shape = AppRadius.LG,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    modifier = Modifier.padding(start = 4.dp)
                ) {
                    Icon(
                        Icons.Rounded.Build,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
                    )
                    Text(
                        text = "工具箱",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f),
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
            }
        }
        Text(
            text = path.joinToString(" / ") { it.name },
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 12.dp)
        )
    }
}

@Composable
private fun CloudFileList(
    items: List<Cloud115FileItem>,
    timeSortOption: CloudSortOption,
    listState: LazyListState,
    addingPickcodes: Set<String>,
    addedPickcodes: Set<String>,
    queuedPickcodes: Set<String>,
    failedPickcodes: Set<String>,
    excludedVideoNames: Set<String>,
    onOpenFolder: (Cloud115FileItem) -> Unit,
    onPlayVideo: (Cloud115FileItem) -> Unit,
    onAddVideo: (Cloud115FileItem) -> Unit,
    onRetryScrape: (String) -> Unit,
    onAddFolder: (Cloud115FileItem) -> Unit
) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(14.dp)
    ) {
        items(
            items = items,
            key = { it.cloudStableKey() },
            contentType = {
                when {
                    it.isDirectory -> "cloud-folder"
                    it.isVideoFile() -> "cloud-video"
                    else -> "cloud-file"
                }
            }
        ) { item ->
            CloudFileRow(
                item = item,
                timeSortOption = timeSortOption,
                isAdding = item.pickcode != null && item.pickcode in addingPickcodes,
                isAdded = item.pickcode != null && item.pickcode in addedPickcodes,
                isQueued = item.pickcode != null && item.pickcode in queuedPickcodes,
                isFailed = item.pickcode != null && item.pickcode in failedPickcodes,
                isExcludedVideo = item.isVideoFile() && item.name.trim() in excludedVideoNames,
                onOpenFolder = { onOpenFolder(item) },
                onPlayVideo = { onPlayVideo(item) },
                onAddVideo = { onAddVideo(item) },
                onRetryScrape = { item.pickcode?.let(onRetryScrape) },
                onAddFolder = { onAddFolder(item) }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CloudFileRow(
    item: Cloud115FileItem,
    timeSortOption: CloudSortOption,
    isAdding: Boolean,
    isAdded: Boolean,
    isQueued: Boolean,
    isFailed: Boolean,
    isExcludedVideo: Boolean,
    onOpenFolder: () -> Unit,
    onPlayVideo: () -> Unit,
    onAddVideo: () -> Unit,
    onRetryScrape: () -> Unit,
    onAddFolder: () -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val subtitle = remember(item.size, item.addedAt, item.modifiedAt, item.isDirectory, timeSortOption) {
        item.cloudSubtitle(timeSortOption)
    }
    var folderMenuExpanded by remember { mutableStateOf(false) }
    val folderCid = item.cid?.toString().orEmpty()

    Box(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(AppRadius.LG)
                .background(CloudPanel)
                .then(
                    when {
                        item.isDirectory -> Modifier.combinedClickable(
                            onClick = onOpenFolder,
                            onLongClick = {
                                if (folderCid.isNotBlank()) folderMenuExpanded = true
                            }
                        )
                        item.isVideoFile() -> Modifier.clickable(onClick = onPlayVideo)
                        else -> Modifier
                    }
                )
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
                    imageVector = if (item.isDirectory) Icons.Rounded.Folder else Icons.Rounded.VideoFile,
                    contentDescription = null,
                    tint = if (item.isDirectory) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.76f)
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 10.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = item.name,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.56f),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            when {
                item.isDirectory && item.cid != null -> AddVideoButton(
                    isAdding = false,
                    isAdded = false,
                    isQueued = false,
                    isFailed = false,
                    onAddVideo = onAddFolder,
                    onRetryScrape = onRetryScrape
                )
                item.isVideoFile() && !isExcludedVideo -> AddVideoButton(
                    isAdding = isAdding,
                    isAdded = isAdded,
                    isQueued = isQueued,
                    isFailed = isFailed,
                    onAddVideo = onAddVideo,
                    onRetryScrape = onRetryScrape
                )
            }
        }

        DropdownMenu(
            expanded = folderMenuExpanded,
            onDismissRequest = { folderMenuExpanded = false }
        ) {
            DropdownMenuItem(
                text = { Text("复制 CID") },
                onClick = {
                    folderMenuExpanded = false
                    if (folderCid.isNotBlank()) {
                        clipboardManager.setText(AnnotatedString(folderCid))
                        Toast.makeText(context, "已复制 CID：$folderCid", Toast.LENGTH_SHORT).show()
                    }
                }
            )
        }
    }
}

private val CloudSortOption.label: String
    get() = when (this) {
        CloudSortOption.AddedTime -> "添加时间"
        CloudSortOption.ModifiedTime -> "修改时间"
        CloudSortOption.Size -> "文件大小"
    }

@Composable
private fun AddVideoButton(
    isAdding: Boolean,
    isAdded: Boolean,
    isQueued: Boolean,
    isFailed: Boolean,
    onAddVideo: () -> Unit,
    onRetryScrape: () -> Unit
) {
    // ★ F-2 三态：刮削失败 > 排队中 > 已添加 > 添加中 > 可添加。
    // 顺序很关键：失败条目同时满足「有记录（已添加）」，必须优先给重试入口，
    // 否则用户看到「已添加」却既点不动、也不知道该去哪重试。
    if (isFailed) {
        Button(
            onClick = onRetryScrape,
            shape = AppRadius.MD,
            contentPadding = PaddingValues(horizontal = 11.dp, vertical = 6.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer
            ),
            modifier = Modifier.height(34.dp)
        ) {
            Text("重试刮削", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
        }
        return
    }
    if (isQueued) {
        Text(
            text = "排队中",
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.52f),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp)
        )
        return
    }
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
    Button(
        onClick = onAddVideo,
        shape = AppRadius.MD,
        contentPadding = PaddingValues(horizontal = 11.dp, vertical = 6.dp),
        modifier = Modifier.height(34.dp)
    ) {
        Text("添加", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
    }
}

private fun Cloud115FileItem.cloudStableKey(): String =
    when {
        pickcode != null -> "pc:$pickcode"
        fid != null -> "fid:$fid"
        cid != null -> "cid:$cid"
        else -> "name:$name"
    }

@Composable
private fun CloudMessage(text: String) {
    Box(Modifier.fillMaxSize().padding(18.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = text,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.74f),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

// H+1 轮：复用统一实现；未知大小此前返回「文件」（语义错误），改为「大小未知」
private fun formatSize(size: Long?): String = formatFileSizeOrUnknown(size)

private fun formatCloudTime(timestampMillis: Long): String {
    val safeMillis = if (timestampMillis < 10_000_000_000L) timestampMillis * 1000L else timestampMillis
    return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(safeMillis))
}

private fun Cloud115FileItem.cloudSubtitle(sortOption: CloudSortOption): String {
    val timeLabel = if (sortOption == CloudSortOption.AddedTime) "添加时间" else "修改时间"
    val timestamp = if (sortOption == CloudSortOption.AddedTime) addedAt else modifiedAt
    val time = timestamp?.let { formatCloudTime(it) } ?: "时间未知"
    return if (isDirectory) {
        "$timeLabel：$time"
    } else {
        "${formatSize(size)} · $timeLabel：$time"
    }
}

// 注意：Cloud115FileItem 的目录字段是 isDirectory（115 侧命名），
// 与 GuangyaFileItem.isDir（光鸭侧命名）不同，勿混用。
private fun Cloud115FileItem.isVideoFile(): Boolean =
    !isDirectory && VideoFormats.isVideoFileName(name)

/**
 * 整夹添加的进度与操作面板。
 *
 * 数据直接来自 `cloud_folder_batch_tasks`（与设置页、runner 同一张表），所以这里看到的进度
 * 就是真实落库的进度，不存在「UI 自己算一套、实际跑另一套」的分叉。
 *
 * 整体百分比只统计 `queuedVideos > 0` 的行：还在递归收集候选的那条任务分母未知，
 * 把它算进来会把总进度算错（尤其是刚点完的那几秒）。
 */
@Composable
private fun CloudFolderBatchPanel(
    tasks: List<CloudFolderBatchTaskEntity>,
    onPause: (Long) -> Unit,
    onResume: (Long) -> Unit,
    onCancel: (Long) -> Unit
) {
    val countable = tasks.filter { it.status != CloudFolderBatchTaskStatus.Cancelled.name }
    val aggregate = countable.folderBatchAggregateProgress()
    // 行数不设上限会把这个面板撑成整屏，遮住文件列表；保留最近的几条 + 一条汇总即可。
    val visibleTasks = tasks.take(FOLDER_BATCH_PANEL_MAX_ROWS)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clip(AppRadius.MD)
            .background(CloudPanel)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = when (aggregate) {
                is FolderBatchProgress.Determinate ->
                    "整夹添加进度 ${aggregate.completed}/${aggregate.total}（${(aggregate.ratio * 100).toInt()}%）"
                is FolderBatchProgress.Indeterminate -> "整夹添加：${aggregate.label}"
            },
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold
        )
        when (aggregate) {
            is FolderBatchProgress.Determinate -> LinearProgressIndicator(
                progress = { aggregate.ratio },
                modifier = Modifier.fillMaxWidth()
            )
            is FolderBatchProgress.Indeterminate -> LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth()
            )
        }
        visibleTasks.forEach { task ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = buildString {
                        append(task.folderName)
                        append(" · ")
                        append(folderBatchStatusLabel(task))
                        when (val rowProgress = task.folderBatchProgress()) {
                            is FolderBatchProgress.Determinate -> {
                                append(" ")
                                append(rowProgress.completed)
                                append("/")
                                append(rowProgress.total)
                            }
                            is FolderBatchProgress.Indeterminate -> {
                                append(" · ")
                                append(rowProgress.label)
                            }
                        }
                    },
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (task.status == CloudFolderBatchTaskStatus.Running.name ||
                    task.status == CloudFolderBatchTaskStatus.Pending.name
                ) {
                    TextButton(
                        onClick = { onPause(task.id) },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text("暂停", style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    TextButton(
                        onClick = { onResume(task.id) },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text("继续", style = MaterialTheme.typography.bodySmall)
                    }
                }
                TextButton(
                    onClick = { onCancel(task.id) },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text("取消", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (tasks.size > visibleTasks.size) {
            Text(
                text = "另有 ${tasks.size - visibleTasks.size} 个文件夹任务，可在设置-刮削任务中查看",
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

/** 面板里最多平铺几行任务；超出的折叠成一行提示。 */
private const val FOLDER_BATCH_PANEL_MAX_ROWS = 5

private fun folderBatchStatusLabel(task: CloudFolderBatchTaskEntity): String = when (task.status) {
    CloudFolderBatchTaskStatus.Pending.name -> "排队中"
    CloudFolderBatchTaskStatus.Running.name -> "进行中"
    CloudFolderBatchTaskStatus.Paused.name -> "已暂停"
    CloudFolderBatchTaskStatus.Cancelled.name -> "已取消"
    CloudFolderBatchTaskStatus.Completed.name -> "已完成"
    CloudFolderBatchTaskStatus.Failed.name -> "失败"
    else -> task.status
}
