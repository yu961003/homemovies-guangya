package com.example.localmovielibrarz.ui.home
import com.example.localmovielibrarz.ui.AppRadius

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.example.localmovielibrarz.data.local.MovieEntity
import com.example.localmovielibrarz.data.local.ScrapeTaskStatus
import com.example.localmovielibrarz.data.repository.MovieMetadataSummary
import com.example.localmovielibrarz.scraper.ActorAvatarStore
import com.example.localmovielibrarz.ui.shared.ListEndHint
import com.example.localmovielibrarz.ui.shared.LoadingState
import com.example.localmovielibrarz.ui.shared.MovieArtwork
import com.example.localmovielibrarz.ui.shared.Top250ArtworkBadge
import com.example.localmovielibrarz.ui.shared.UriImage
import com.example.localmovielibrarz.util.metadataKey
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.foundation.layout.sizeIn
import com.example.localmovielibrarz.ui.MinTouchTarget
import com.example.localmovielibrarz.ui.ContentAlphaMin

private val MoviesBackground: Color
    @Composable get() = MaterialTheme.colorScheme.background
private val MoviesSurface: Color
    @Composable get() = MaterialTheme.colorScheme.surface
private val MoviesPanel: Color
    @Composable get() = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.075f)
private val MoviesAccent = Color(0xFF36C5F0)
private const val ALL_MOVIES_INITIAL_COUNT = 90
private const val ALL_MOVIES_PAGE_SIZE = 60
private const val ALL_MOVIES_PREFETCH_THRESHOLD = 18

@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onMovieClick: (Long) -> Unit,
    onPlay: (MovieEntity) -> Unit,
    onOpenLibrary: () -> Unit
) {
    MoviesScreen(viewModel = viewModel, onMovieClick = onMovieClick, onPlay = onPlay, onOpenLibrary = onOpenLibrary)
}

@Composable
fun MoviesScreen(
    viewModel: HomeViewModel,
    onMovieClick: (Long) -> Unit,
    onPlay: (MovieEntity) -> Unit,
    onOpenLibrary: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val visibleRecentlyAdded = remember(uiState.recentlyAdded, uiState.scrapeIssueMovies) {
        excludeScrapeIssueMovies(uiState.recentlyAdded, uiState.scrapeIssueMovies)
    }
    val gridState = rememberSaveable(saver = LazyGridState.Saver) { LazyGridState() }
    var lastScrollResetKey by rememberSaveable {
        mutableStateOf("${uiState.sortState.option.name}:${uiState.sortState.direction.name}:${uiState.imageMode.name}")
    }

    LaunchedEffect(uiState.sortState, uiState.imageMode) {
        val nextKey = "${uiState.sortState.option.name}:${uiState.sortState.direction.name}:${uiState.imageMode.name}"
        if (lastScrollResetKey != nextKey) {
            lastScrollResetKey = nextKey
            gridState.scrollToItem(0)
        }
    }

    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Adaptive(minSize = if (uiState.imageMode == HomeImageMode.Thumb) 170.dp else 112.dp),
        modifier = Modifier
            .fillMaxSize()
            .background(MoviesBackground),
        contentPadding = PaddingValues(bottom = 30.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        horizontalArrangement = Arrangement.spacedBy(13.dp)
    ) {
        item(
            key = "movies-top-bar",
            contentType = "movies-top-bar",
            span = { GridItemSpan(maxLineSpan) }
        ) {
            MoviesTopBar(
                stats = uiState.stats,
                scanState = uiState.scanState
            )
        }

        if (uiState.isLoading) {
            // 审查 P1：首帧未到时此前直接显示「空库」，用户会以为影片丢了
            item(
                key = "library-loading",
                contentType = "library-loading",
                span = { GridItemSpan(maxLineSpan) }
            ) {
                LoadingState()
            }
        } else if (uiState.movies.isEmpty()) {
            item(
                key = "empty-library",
                contentType = "empty-library",
                span = { GridItemSpan(maxLineSpan) }
            ) {
                EmptyLibraryState(
                    isScanning = uiState.scanState is ScanState.Scanning,
                    onOpenLibrary = onOpenLibrary
                )
            }
        } else {
            item(
                key = "movies-top-spacer",
                contentType = "section-spacer",
                span = { GridItemSpan(maxLineSpan) }
            ) {
                Spacer(Modifier.height(8.dp))
            }
            if (uiState.hasPendingMovieUpdates) {
                item(
                    key = "pending-movies-banner",
                    contentType = "pending-movies-banner",
                    span = { GridItemSpan(maxLineSpan) }
                ) {
                    PendingMoviesBanner(
                        count = uiState.pendingNewCount,
                        onClick = viewModel::applyPendingMovieUpdates
                    )
                }
            }
            if (visibleRecentlyAdded.isNotEmpty()) {
                item(
                    key = "recently-added-section",
                    contentType = "movie-rail-section",
                    span = { GridItemSpan(maxLineSpan) }
                ) {
                    HomeMovieSection(title = "\u6700\u8FD1\u6DFB\u52A0") {
                    HorizontalMovieRail(
                        movies = visibleRecentlyAdded,
                        imageMode = uiState.imageMode,
                        onMovieClick = onMovieClick,
                        onPlay = onPlay,
                        onToggleFavorite = viewModel::toggleFavorite,
                        onToggleWatched = viewModel::toggleWatched
                        )
                    }
                }
            }

            if (uiState.recentlyPlayed.isNotEmpty()) {
                item(
                    key = "recently-played-section",
                    contentType = "movie-rail-section",
                    span = { GridItemSpan(maxLineSpan) }
                ) {
                    HomeMovieSection(title = "最近播放") {
                        HorizontalMovieRail(
                            movies = uiState.recentlyPlayed,
                            imageMode = uiState.imageMode,
                            onMovieClick = onMovieClick,
                            onPlay = onPlay,
                            onToggleFavorite = viewModel::toggleFavorite,
                            onToggleWatched = viewModel::toggleWatched
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun MoviesLibraryScreen(
    viewModel: HomeViewModel,
    onMovieClick: (Long) -> Unit,
    onPlay: (MovieEntity) -> Unit,
    onFilterClick: (String, String) -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val batchState by viewModel.batchState.collectAsStateWithLifecycle()
    var selectedTabName by rememberSaveable { mutableStateOf(LibraryTab.All.name) }
    val availableTabs = LibraryTab.entries
    val selectedTab = availableTabs.firstOrNull { it.name == selectedTabName } ?: LibraryTab.All
    val regularMovies = remember(uiState.movies, uiState.scrapeIssueMovies) {
        excludeScrapeIssueMovies(uiState.movies, uiState.scrapeIssueMovies)
    }

    // 批量操作状态
    var batchMode by rememberSaveable { mutableStateOf(false) }
    val selectedIds = remember { mutableStateListOf<Long>() }
    var pendingBatchAction by remember { mutableStateOf<BatchAction?>(null) }

    val currentBatchMovies: List<MovieEntity> = when (selectedTab) {
        LibraryTab.All -> regularMovies.filterNot { it.isVrMovie() }
        LibraryTab.Scraped -> regularMovies.filterNot { it.isVrMovie() }
            .filter { it.isEffectivelyScraped() }
        LibraryTab.Vr -> regularMovies.filter { it.isVrMovie() }
        LibraryTab.Unscraped -> uiState.scrapeIssueMovies
        else -> emptyList()
    }

    fun exitBatchMode() {
        batchMode = false
        selectedIds.clear()
        pendingBatchAction = null
    }

    fun toggleSelect(movieId: Long) {
        if (movieId in selectedIds) selectedIds.remove(movieId) else selectedIds.add(movieId)
    }

    fun toggleSelectAll() {
        val target = if (selectedIds.size == currentBatchMovies.size) emptyList() else currentBatchMovies.map { it.id }
        selectedIds.clear()
        selectedIds.addAll(target)
    }

    // 批量操作完成提示自动清除
    LaunchedEffect(batchState.message) {
        batchState.message?.let {
            delay(3_000)
            viewModel.clearBatchMessage()
        }
    }

    LaunchedEffect(selectedTabName) {
        if (selectedTabName != selectedTab.name) {
            selectedTabName = selectedTab.name
        }
    }

    LaunchedEffect(selectedTab, uiState.movies.size) {
        if (
            selectedTab != LibraryTab.All &&
            selectedTab != LibraryTab.Unscraped &&
            selectedTab != LibraryTab.Vr &&
            selectedTab != LibraryTab.Years
        ) {
            viewModel.refreshLibrarySummaries()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MoviesBackground)
    ) {
        // 内容区（顶栏 + 影片网格）：占满，底部留白给悬浮操作栏
        Column(modifier = Modifier.fillMaxSize()) {
        LibraryTopBar(
            selectedTab = selectedTab,
            tabs = availableTabs,
            stats = uiState.stats,
            sortState = uiState.sortState,
            imageMode = uiState.imageMode,
            batchMode = batchMode,
            batchCount = selectedIds.size,
            onToggleBatchMode = {
                if (batchMode) exitBatchMode() else batchMode = true
            },
            onTabSelected = {
                selectedTabName = it.name
                if (batchMode) exitBatchMode()
            },
            onSortSelected = viewModel::setSortOption,
            onToggleSortDirection = viewModel::toggleSortDirection,
            onImageModeSelected = viewModel::setImageMode
        )
        if (uiState.hasPendingMovieUpdates) {
            PendingMoviesBanner(
                count = uiState.pendingNewCount,
                onClick = viewModel::applyPendingMovieUpdates
            )
        }
        when (selectedTab) {
            LibraryTab.All -> LibraryAllMoviesGrid(
                movies = regularMovies.filterNot { it.isVrMovie() },
                imageMode = uiState.imageMode,
                sortState = uiState.sortState,
                batchMode = batchMode,
                selectedIds = selectedIds,
                onToggleSelect = ::toggleSelect,
                onMovieClick = onMovieClick,
                onPlay = onPlay,
                onToggleFavorite = viewModel::toggleFavorite,
                onToggleWatched = viewModel::toggleWatched
            )
            LibraryTab.Scraped -> LibraryAllMoviesGrid(
                movies = regularMovies.filterNot { it.isVrMovie() }
                    .filter { it.isEffectivelyScraped() },
                imageMode = uiState.imageMode,
                sortState = uiState.sortState,
                batchMode = batchMode,
                selectedIds = selectedIds,
                onToggleSelect = ::toggleSelect,
                onMovieClick = onMovieClick,
                onPlay = onPlay,
                onToggleFavorite = viewModel::toggleFavorite,
                onToggleWatched = viewModel::toggleWatched
            )
            LibraryTab.Unscraped -> UnscrapedMoviesTab(
                movies = uiState.scrapeIssueMovies,
                imageMode = uiState.imageMode,
                sortState = uiState.sortState,
                runnerState = uiState.scrapeRunnerState,
                onStart = viewModel::startBatchScrape,
                onPause = viewModel::pauseBatchScrape,
                onMovieClick = onMovieClick,
                onPlay = onPlay,
                onToggleFavorite = viewModel::toggleFavorite,
                onToggleWatched = viewModel::toggleWatched,
                batchMode = batchMode,
                selectedIds = selectedIds,
                onToggleSelect = ::toggleSelect
            )
            LibraryTab.Vr -> LibraryAllMoviesGrid(
                movies = regularMovies.filter { it.isVrMovie() },
                imageMode = uiState.imageMode,
                sortState = uiState.sortState,
                batchMode = batchMode,
                selectedIds = selectedIds,
                onToggleSelect = ::toggleSelect,
                onMovieClick = onMovieClick,
                onPlay = onPlay,
                onToggleFavorite = viewModel::toggleFavorite,
                onToggleWatched = viewModel::toggleWatched
            )
            LibraryTab.Collections -> LibrarySummaryGrid(
                title = "合集",
                summaries = uiState.librarySummaries.collections,
                emptyText = "还没有可归类的合集",
                onClick = { onFilterClick("collection", it.value) }
            )
            LibraryTab.Actors -> ActorSummaryGrid(
                summaries = uiState.librarySummaries.actors,
                refreshVersion = uiState.actorAvatarRefreshVersion,
                onClick = { onFilterClick("actor", it.value) }
            )
            LibraryTab.Tags -> LibrarySummaryGrid(
                title = "标签",
                summaries = uiState.librarySummaries.tags,
                emptyText = "还没有标签",
                onClick = { onFilterClick("tag", it.value) }
            )
            LibraryTab.Genres -> LibrarySummaryGrid(
                title = "类型",
                summaries = uiState.librarySummaries.genres,
                emptyText = "还没有类型",
                onClick = { onFilterClick("genre", it.value) }
            )
            LibraryTab.Years -> LibrarySummaryGrid(
                title = "年份",
                summaries = regularMovies.mapNotNull { it.year?.toString() }.summaryValues().sortedByDescending { it.value },
                emptyText = "还没有年份信息",
                onClick = { onFilterClick("year", it.value) }
            )
            LibraryTab.Studios -> LibrarySummaryGrid(
                title = "工作室",
                summaries = uiState.librarySummaries.studios,
                emptyText = "还没有工作室信息",
                onClick = { onFilterClick("studio", it.value) }
            )
        }
        } // 闭合内容区内层 Column

        // 底部悬浮栏：批量进度提示 / 消息 / 操作栏（避免被 fillMaxSize 的网格挤出视口）
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
        ) {
        // 批量操作进度提示
        if (batchState.busy) {
            Text(
                text = "正在${batchActionLabel(pendingBatchAction)} ${batchState.doneCount}/${batchState.totalCount} ...",
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
        batchState.message?.let { msg ->
            Text(
                text = msg,
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f))
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }

        // 批量操作栏（批量模式且非执行中）
        if (batchMode && !batchState.busy) {
            BatchActionBar(
                selectedCount = selectedIds.size,
                totalCount = currentBatchMovies.size,
                onSelectAll = ::toggleSelectAll,
                onDelete = { pendingBatchAction = BatchAction.DeleteMovies },
                onClearScrape = { pendingBatchAction = BatchAction.ClearScrapeData },
                onRescrape = { pendingBatchAction = BatchAction.Rescrape },
                onExit = ::exitBatchMode
            )
        }
        }
    }

    // 批量操作确认对话框
    pendingBatchAction?.let { action ->
        AlertDialog(
            onDismissRequest = { pendingBatchAction = null },
            title = { Text(batchActionTitle(action)) },
            text = {
                Text(
                    when (action) {
                        BatchAction.DeleteMovies ->
                            "确认删除选中的 ${selectedIds.size} 部影片？将同时删除本地 STRM 文件和影片记录（不会删除网盘上的原文件），此操作不可恢复。"
                        BatchAction.ClearScrapeData ->
                            "确认清除选中的 ${selectedIds.size} 部影片的刮削数据？NFO、海报等元数据将被清除，STRM 文件保留，影片恢复为未刮削状态。"
                        BatchAction.Rescrape ->
                            "确认重新刮削选中的 ${selectedIds.size} 部影片？将使用默认刮削来源重新刮削并整理文件。"
                    }
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val ids = selectedIds.toList()
                        pendingBatchAction = null
                        when (action) {
                            BatchAction.DeleteMovies -> viewModel.batchDeleteMovies(ids)
                            BatchAction.ClearScrapeData -> viewModel.batchClearScrapeData(ids)
                            BatchAction.Rescrape -> viewModel.batchRescrapeMovies(ids)
                        }
                        exitBatchMode()
                    },
                    colors = ButtonDefaults.buttonColors(
                        // 删除与清除刮削数据是破坏性操作，用 error 色与「重新刮削」区分
                        containerColor = if (action == BatchAction.Rescrape) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                        contentColor = if (action == BatchAction.Rescrape) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onError
                        }
                    )
                ) {
                    Text("确认")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingBatchAction = null }) {
                    Text("取消")
                }
            }
        )
    }
}

private enum class BatchAction {
    DeleteMovies,
    ClearScrapeData,
    Rescrape
}

/**
 * 宽松判定「已刮削」：除了显式 Completed，还包含 NFO 未落盘但已写入刮削元数据
 * （海报/片名）的影片。原始 scrapeTaskStatus==Completed 仅在 nfoUri!=null 时赋值，
 * 部分网盘直链影片刮削成功却无本地 NFO，导致「已刮削」tab 空白，这里兜底。
 */
private fun MovieEntity.isEffectivelyScraped(): Boolean {
    if (scrapeTaskStatus == ScrapeTaskStatus.Completed.name) return true
    // 未完成/失败态不参与兜底，避免把正在刮削或刮削失败的影片算作已刮削
    if (scrapeTaskStatus in setOf(
            ScrapeTaskStatus.Pending.name,
            ScrapeTaskStatus.Running.name,
            ScrapeTaskStatus.Failed.name
        )
    ) return false
    return posterUri != null || nfoUri != null
}

private fun batchActionTitle(action: BatchAction): String = when (action) {
    BatchAction.DeleteMovies -> "批量删除影片"
    BatchAction.ClearScrapeData -> "批量清除刮削数据"
    BatchAction.Rescrape -> "批量重新刮削"
}

private fun batchActionLabel(action: BatchAction?): String = when (action) {
    BatchAction.DeleteMovies -> "删除影片"
    BatchAction.ClearScrapeData -> "清除刮削数据"
    BatchAction.Rescrape -> "重新刮削"
    null -> "批量操作"
}

@Composable
private fun BatchActionBar(
    selectedCount: Int,
    totalCount: Int,
    onSelectAll: () -> Unit,
    onDelete: () -> Unit,
    onClearScrape: () -> Unit,
    onRescrape: () -> Unit,
    onExit: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "已选 $selectedCount / $totalCount",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onSelectAll) {
                Text(if (selectedCount == totalCount) "取消全选" else "全选")
            }
            TextButton(onClick = onExit) {
                Text("退出批量")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BatchActionButton(text = "删除影片", onClick = onDelete, danger = true, modifier = Modifier.weight(1f))
            BatchActionButton(text = "清除刮削数据", onClick = onClearScrape, modifier = Modifier.weight(1f))
            BatchActionButton(text = "重新刮削", onClick = onRescrape, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun BatchActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    danger: Boolean = false
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        shape = AppRadius.MD,
        colors = if (danger) {
            ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError
            )
        } else {
            ButtonDefaults.buttonColors()
        }
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun UnscrapedMoviesTab(
    movies: List<MovieEntity>,
    imageMode: HomeImageMode,
    sortState: HomeSortState,
    runnerState: com.example.localmovielibrarz.data.repository.MovieScrapeTaskRunnerState,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onMovieClick: (Long) -> Unit,
    onPlay: (MovieEntity) -> Unit,
    onToggleFavorite: (MovieEntity) -> Unit,
    onToggleWatched: (MovieEntity) -> Unit,
    batchMode: Boolean = false,
    selectedIds: List<Long> = emptyList(),
    onToggleSelect: (Long) -> Unit = {}
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MoviesPanel)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "未刮削影片 ${movies.size} 部",
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = runnerState.message.ifBlank {
                            "显示刮削失败、从未刮削或缺少 NFO 的影片"
                        },
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Button(
                    onClick = if (runnerState.isRunning) onPause else onStart,
                    enabled = runnerState.isRunning || movies.isNotEmpty()
                ) {
                    Icon(
                        imageVector = if (runnerState.isRunning) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = null
                    )
                    Text(
                        text = if (runnerState.isRunning) "暂停" else "批量刮削",
                        modifier = Modifier.padding(start = 6.dp)
                    )
                }
            }
            if (runnerState.isRunning) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
        if (movies.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "没有刮削失败或未刮削的影片",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f)
                )
            }
        } else {
            Box(modifier = Modifier.weight(1f)) {
                LibraryAllMoviesGrid(
                    movies = movies,
                    imageMode = imageMode,
                    sortState = sortState,
                    showScrapeStatus = true,
                    batchMode = batchMode,
                    selectedIds = selectedIds,
                    onToggleSelect = onToggleSelect,
                    onMovieClick = onMovieClick,
                    onPlay = onPlay,
                    onToggleFavorite = onToggleFavorite,
                    onToggleWatched = onToggleWatched
                )
            }
        }
    }
}

@Composable
fun MoviesTopBar(
    stats: LibraryStats,
    scanState: ScanState
) {
    var sortExpanded by remember { mutableStateOf(false) }
    var imageModeExpanded by remember { mutableStateOf(false) }
    val imageMode = HomeImageMode.Poster
    val sortState = HomeSortState()
    val onImageModeSelected: (HomeImageMode) -> Unit = {}
    val onSortSelected: (HomeSortOption) -> Unit = {}
    val onToggleSortDirection: () -> Unit = {}

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(MaterialTheme.colorScheme.surfaceVariant, MoviesBackground)))
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(start = 18.dp, end = 12.dp, top = 8.dp, bottom = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "\u9996\u9875",
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.ExtraBold
                )
                Text(
                    text = scanMessage(scanState),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.56f),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (false) {
            Box {
                IconButton(onClick = { imageModeExpanded = true }) {
                    Text(
                        text = if (imageMode == HomeImageMode.Poster) "海报" else "缩略",
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                DropdownMenu(expanded = imageModeExpanded, onDismissRequest = { imageModeExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text("使用 poster 显示") },
                        onClick = {
                            imageModeExpanded = false
                            onImageModeSelected(HomeImageMode.Poster)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("使用 thumb 显示") },
                        onClick = {
                            imageModeExpanded = false
                            onImageModeSelected(HomeImageMode.Thumb)
                        }
                    )
                }
            }

            Box {
                IconButton(onClick = { sortExpanded = true }) {
                    Icon(Icons.AutoMirrored.Rounded.Sort, contentDescription = "排序", tint = MaterialTheme.colorScheme.onSurface)
                }
                if (sortExpanded) {
                    SortDialog(
                        sortState = sortState,
                        onDismiss = { sortExpanded = false },
                        onSortSelected = onSortSelected,
                        onToggleSortDirection = onToggleSortDirection
                    )
                }
            }
            }
        }
    }
}

@Composable
fun LibraryStatsRow(stats: LibraryStats) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatPill("\u5F71\u7247", stats.total.toString())
        StatPill("\u6536\u85CF", stats.favorites.toString())
        StatPill("\u5DF2\u89C2\u770B", stats.watched.toString())
    }
}

@Composable
private fun SortDialog(
    sortState: HomeSortState,
    onDismiss: () -> Unit,
    onSortSelected: (HomeSortOption) -> Unit,
    onToggleSortDirection: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 360.dp)
                    .heightIn(max = 620.dp)
                    .clip(AppRadius.LG)
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(top = 18.dp, bottom = 10.dp)
            ) {
                Text(
                    text = "\u6392\u5E8F:",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
                Spacer(Modifier.height(10.dp))
                LazyColumn {
                    items(
                        count = HomeSortOption.values().size,
                        contentType = { "sort-option" }
                    ) { index ->
                        val option = HomeSortOption.values()[index]
                        SortDialogRow(
                            option = option,
                            selected = sortState.option == option,
                            direction = sortState.direction,
                            onClick = {
                                if (sortState.option == option) {
                                    onToggleSortDirection()
                                } else {
                                    onSortSelected(option)
                                }
                                onDismiss()
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SortDialogRow(
    option: HomeSortOption,
    selected: Boolean,
    direction: HomeSortDirection,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(AppRadius.SM)
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.58f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.width(34.dp), contentAlignment = Alignment.CenterStart) {
            if (selected) {
                Icon(Icons.Rounded.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(21.dp))
            }
        }
        Text(
            text = sortLabel(option),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.92f),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )
        if (selected) {
            Icon(
                imageVector = if (direction == HomeSortDirection.Descending) Icons.Rounded.ArrowDownward else Icons.Rounded.ArrowUpward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

@Composable
private fun StatPill(label: String, value: String) {
    Column(
        modifier = Modifier
            .clip(AppRadius.MD)
            .background(MoviesPanel)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Text(
            text = value,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
            style = MaterialTheme.typography.labelSmall
        )
    }
}

@Composable
private fun LibraryEntryCard(
    total: Int,
    actorCount: Int,
    tagCount: Int,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .padding(horizontal = 18.dp)
            .fillMaxWidth()
            .clip(AppRadius.XL)
            .background(
                Brush.horizontalGradient(
                    listOf(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.surface)
                )
            )
            .clickable(onClick = onClick)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "全部影片",
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.ExtraBold
                )
                Text(
                    text = "按全部、合集、演员、标签、类型继续浏览",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Text(
                text = "进入",
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatPill("影片", total.toString())
            StatPill("演员", actorCount.toString())
            StatPill("标签", tagCount.toString())
        }
    }
}

@Composable
private fun PendingMoviesBanner(
    count: Int,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .padding(horizontal = 18.dp)
            .fillMaxWidth()
            .clip(AppRadius.LG)
            .background(MaterialTheme.colorScheme.primaryContainer)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "有 ${count} 部影片待更新",
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "点击后一次性刷新影片页面",
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f),
                style = MaterialTheme.typography.bodySmall
            )
        }
        Text(
            text = "\u5237\u65B0",
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.ExtraBold
        )
    }
}

@Composable
fun HomeMovieSection(
    title: String,
    subtitle: String? = null,
    content: @Composable () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HomeSectionHeader(title = title, subtitle = subtitle)
        content()
    }
}

@Composable
private fun LibraryTopBar(
    selectedTab: LibraryTab,
    tabs: List<LibraryTab>,
    stats: LibraryStats,
    sortState: HomeSortState,
    imageMode: HomeImageMode,
    batchMode: Boolean,
    batchCount: Int,
    onToggleBatchMode: () -> Unit,
    onTabSelected: (LibraryTab) -> Unit,
    onSortSelected: (HomeSortOption) -> Unit,
    onToggleSortDirection: () -> Unit,
    onImageModeSelected: (HomeImageMode) -> Unit
) {
    var sortExpanded by remember { mutableStateOf(false) }
    var imageModeExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(MaterialTheme.colorScheme.surfaceVariant, MoviesBackground)))
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(top = 6.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.padding(start = 18.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (batchMode) "批量选择" else "影片库",
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.ExtraBold
                )
                Text(
                    text = if (batchMode) "已选 $batchCount 部" else "${stats.total} 部影片",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.56f),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (!batchMode) {
                Box {
                    IconButton(onClick = { imageModeExpanded = true }) {
                        Text(
                            text = if (imageMode == HomeImageMode.Poster) "海报" else "缩略",
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    DropdownMenu(expanded = imageModeExpanded, onDismissRequest = { imageModeExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text("使用 poster 显示") },
                            onClick = {
                                imageModeExpanded = false
                                onImageModeSelected(HomeImageMode.Poster)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("使用 thumb 显示") },
                            onClick = {
                                imageModeExpanded = false
                                onImageModeSelected(HomeImageMode.Thumb)
                            }
                        )
                    }
                }
            }
            Box {
                IconButton(onClick = onToggleBatchMode, modifier = Modifier.sizeIn(minWidth = MinTouchTarget, minHeight = MinTouchTarget)) {
                    Icon(
                        imageVector = if (batchMode) Icons.Rounded.Close else Icons.Rounded.Checklist,
                        contentDescription = if (batchMode) "退出批量" else "批量操作",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
            if (!batchMode) {
                Box {
                    IconButton(onClick = { sortExpanded = true }) {
                        Icon(Icons.AutoMirrored.Rounded.Sort, contentDescription = "排序", tint = MaterialTheme.colorScheme.onSurface)
                    }
                    if (sortExpanded) {
                        SortDialog(
                            sortState = sortState,
                            onDismiss = { sortExpanded = false },
                            onSortSelected = onSortSelected,
                            onToggleSortDirection = onToggleSortDirection
                        )
                    }
                }
            }
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            items(
                items = tabs,
                key = { it.name },
                contentType = { "library-tab" }
            ) { tab ->
                val selected = selectedTab == tab
                Text(
                    text = tab.label,
                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.82f),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(AppRadius.LG)
                        .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f))
                        .clickable { onTabSelected(tab) }
                        .padding(horizontal = 15.dp, vertical = 9.dp)
                )
            }
        }
    }
}

@Composable
private fun LibraryAllMoviesGrid(
    movies: List<MovieEntity>,
    imageMode: HomeImageMode,
    sortState: HomeSortState,
    showScrapeStatus: Boolean = false,
    batchMode: Boolean = false,
    selectedIds: List<Long> = emptyList(),
    onToggleSelect: (Long) -> Unit = {},
    onMovieClick: (Long) -> Unit,
    onPlay: (MovieEntity) -> Unit,
    onToggleFavorite: (MovieEntity) -> Unit,
    onToggleWatched: (MovieEntity) -> Unit
) {
    val gridState = rememberSaveable(saver = LazyGridState.Saver) { LazyGridState() }
    var visibleCount by rememberSaveable { mutableStateOf(ALL_MOVIES_INITIAL_COUNT) }
    var lastScrollResetKey by rememberSaveable {
        mutableStateOf("${sortState.option.name}:${sortState.direction.name}:${imageMode.name}")
    }
    val visibleMovies = remember(movies, visibleCount) {
        movies.take(visibleCount.coerceIn(0, movies.size))
    }

    LaunchedEffect(sortState, imageMode) {
        val nextKey = "${sortState.option.name}:${sortState.direction.name}:${imageMode.name}"
        if (lastScrollResetKey != nextKey) {
            lastScrollResetKey = nextKey
            visibleCount = ALL_MOVIES_INITIAL_COUNT.coerceAtMost(movies.size)
            gridState.scrollToItem(0)
        }
    }

    LaunchedEffect(gridState, movies.size) {
        if (visibleCount == 0 && movies.isNotEmpty()) {
            visibleCount = ALL_MOVIES_INITIAL_COUNT.coerceAtMost(movies.size)
        }
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collectLatest { lastVisibleIndex ->
                if (
                    movies.size > visibleCount &&
                    lastVisibleIndex >= visibleCount - ALL_MOVIES_PREFETCH_THRESHOLD
                ) {
                    visibleCount = (visibleCount + ALL_MOVIES_PAGE_SIZE).coerceAtMost(movies.size)
                }
            }
    }

    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Adaptive(minSize = if (imageMode == HomeImageMode.Thumb) 170.dp else 112.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        horizontalArrangement = Arrangement.spacedBy(13.dp)
    ) {
        items(
            items = visibleMovies,
            key = { it.id },
            contentType = { if (imageMode == HomeImageMode.Thumb) "movie-thumb-card" else "movie-poster-card" }
        ) { movie ->
            MoviePosterCard(
                movie = movie,
                width = Dp.Unspecified,
                imageMode = imageMode,
                showScrapeStatus = showScrapeStatus,
                batchMode = batchMode,
                selected = movie.id in selectedIds,
                onToggleSelect = { onToggleSelect(movie.id) },
                onClick = { onMovieClick(movie.id) },
                onPlay = { onPlay(movie) },
                onToggleFavorite = { onToggleFavorite(movie) },
                onToggleWatched = { onToggleWatched(movie) }
            )
        }
        if (visibleMovies.size < movies.size) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    text = "已显示 ${visibleMovies.size} / ${movies.size}",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlphaMin),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 10.dp),
                )
            }
        } else if (movies.isNotEmpty()) {
            // 审查 P1：此前滚到底静默截断，用户分不清「卡住」还是「真没有了」
            item(
                key = "all-movies-end",
                contentType = "list-end",
                span = { GridItemSpan(maxLineSpan) }
            ) {
                ListEndHint(count = movies.size)
            }
        }
    }
}

@Composable
private fun LibrarySummaryGrid(
    title: String,
    summaries: List<MovieMetadataSummary>,
    emptyText: String,
    onClick: (MovieMetadataSummary) -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 150.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Text(
                text = "$title ${summaries.size}",
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold
            )
        }
        if (summaries.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(emptyText, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f), style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            items(
                items = summaries,
                key = { it.value },
                contentType = { "metadata-summary-card" }
            ) { summary ->
                SummaryCard(summary = summary, onClick = { onClick(summary) })
            }
        }
    }
}

@Composable
private fun ActorSummaryGrid(
    summaries: List<MovieMetadataSummary>,
    refreshVersion: Int,
    onClick: (MovieMetadataSummary) -> Unit
) {
    val context = LocalContext.current
    val avatarStore = remember(context, refreshVersion) { ActorAvatarStore(context) }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 108.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "演员 ${summaries.size}",
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.ExtraBold
                )
            }
        }
        if (summaries.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text("还没有演员信息", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f), style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            items(
                items = summaries,
                key = { it.value },
                contentType = { "actor-summary-card" }
            ) { summary ->
                ActorSummaryCard(
                    summary = summary,
                    avatarUri = avatarStore.avatarUri(summary.value),
                    onClick = { onClick(summary) }
                )
            }
        }
    }
}

@Composable
private fun SummaryCard(summary: MovieMetadataSummary, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(AppRadius.LG)
            .background(MoviesPanel)
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = summary.value,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = "${summary.count} 部影片",
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.56f),
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun ActorSummaryCard(summary: MovieMetadataSummary, avatarUri: String?, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.78f)
                .clip(AppRadius.SM)
                .background(MoviesSurface),
            contentAlignment = Alignment.Center
        ) {
            if (avatarUri != null) {
                UriImage(uri = avatarUri, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop, maxDecodeSize = 360)
            } else {
                Icon(Icons.Rounded.Person, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.82f), modifier = Modifier.size(38.dp))
            }
        }
        Text(
            text = summary.value,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = "${summary.count} 部",
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlphaMin),
            style = MaterialTheme.typography.labelSmall
        )
    }
}

@Composable
private fun HomeSectionHeader(title: String, subtitle: String? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        Text(
            text = title,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        subtitle?.let {
            Spacer(Modifier.width(8.dp))
            Text(
                text = it,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlphaMin),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
fun HorizontalMovieRail(
    movies: List<MovieEntity>,
    imageMode: HomeImageMode,
    onMovieClick: (Long) -> Unit,
    onPlay: (MovieEntity) -> Unit,
    onToggleFavorite: (MovieEntity) -> Unit,
    onToggleWatched: (MovieEntity) -> Unit
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(13.dp)
    ) {
        items(
            items = movies,
            key = { it.id },
            contentType = { if (imageMode == HomeImageMode.Thumb) "rail-thumb-card" else "rail-poster-card" }
        ) { movie ->
            MoviePosterCard(
                movie = movie,
                width = if (imageMode == HomeImageMode.Thumb) 190.dp else 118.dp,
                imageMode = imageMode,
                onClick = { onMovieClick(movie.id) },
                onPlay = { onPlay(movie) },
                onToggleFavorite = { onToggleFavorite(movie) },
                onToggleWatched = { onToggleWatched(movie) }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MoviePosterCard(
    movie: MovieEntity,
    width: Dp,
    imageMode: HomeImageMode = HomeImageMode.Poster,
    showScrapeStatus: Boolean = false,
    batchMode: Boolean = false,
    selected: Boolean = false,
    onToggleSelect: () -> Unit = {},
    onClick: () -> Unit,
    onPlay: () -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleWatched: () -> Unit
) {
    var menuExpanded by remember(movie.id) { mutableStateOf(false) }
    // 榜单角标是横切能力：由 AppRoot 通过 CompositionLocal 下发，卡片自身只负责画。
    // 榜单角标由 Top250ArtworkBadge 叠层自己读取上下文，这里无需再取。
    val cardModifier = if (width == Dp.Unspecified) Modifier else Modifier.width(width)
    // 批量模式下点击/长按都切换选择；普通模式保持原有行为（显式类型避免 if-else 推断为 Any）
    val cardClick: () -> Unit = if (batchMode) onToggleSelect else onClick
    val cardLongClick: () -> Unit = if (batchMode) onToggleSelect else ({ menuExpanded = true })

    Column(modifier = cardModifier) {
        Box {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(if (imageMode == HomeImageMode.Thumb) 16f / 9f else 2f / 3f)
                    .combinedClickable(
                        onClick = cardClick,
                        onLongClick = cardLongClick
                    )
                    .then(
                        if (batchMode && selected) {
                            Modifier.border(3.dp, MaterialTheme.colorScheme.primary, AppRadius.SM)
                        } else {
                            Modifier
                        }
                    ),
                shape = AppRadius.SM,
                colors = CardDefaults.cardColors(containerColor = MoviesSurface),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    MovieArtwork(
                        movie = movie,
                        preferThumb = imageMode == HomeImageMode.Thumb,
                        modifier = Modifier.fillMaxSize(),
                    )
                    if (!batchMode) {
                        Top250ArtworkBadge(movie)
                    }
                    if (movie.isFavorite && !batchMode) {
                        Icon(
                            imageVector = Icons.Rounded.Favorite,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(8.dp)
                                .size(18.dp)
                        )
                    }
                    if (batchMode) {
                        // 批量选择标记（右上角圆形选中圈）
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(6.dp)
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(
                                    if (selected) MaterialTheme.colorScheme.primary
                                    else Color.Black.copy(alpha = 0.55f)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            if (selected) {
                                Icon(
                                    imageVector = Icons.Rounded.Check,
                                    contentDescription = "已选择",
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                    if (showScrapeStatus && !batchMode) {
                        val statusText = when (movie.scrapeTaskStatus) {
                            ScrapeTaskStatus.Failed.name -> "刮削失败"
                            ScrapeTaskStatus.Running.name -> "刮削中"
                            ScrapeTaskStatus.Pending.name -> "待刮削"
                            else -> "未刮削"
                        }
                        Text(
                            text = statusText,
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .background(
                                    if (movie.scrapeTaskStatus == ScrapeTaskStatus.Failed.name) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        Color.Black.copy(alpha = 0.72f)
                                    },
                                    RoundedCornerShape(topEnd = AppRadius.SM_Dp)
                                )
                                .padding(horizontal = 8.dp, vertical = 5.dp)
                        )
                    }
                }
            }

            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                DropdownMenuItem(
                    text = { Text("\u64AD\u653E") },
                    leadingIcon = { Icon(Icons.Rounded.PlayArrow, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        onPlay()
                    }
                )
                DropdownMenuItem(
                    text = { Text(if (movie.isFavorite) "\u53D6\u6D88\u6536\u85CF" else "\u52A0\u5165\u6536\u85CF") },
                    leadingIcon = {
                        Icon(
                            if (movie.isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                            contentDescription = null
                        )
                    },
                    onClick = {
                        menuExpanded = false
                        onToggleFavorite()
                    }
                )
                DropdownMenuItem(
                    text = { Text(if (movie.isWatched) "\u6807\u8BB0\u672A\u89C2\u770B" else "\u6807\u8BB0\u5DF2\u89C2\u770B") },
                    leadingIcon = { Icon(Icons.Rounded.CheckCircle, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        onToggleWatched()
                    }
                )
                DropdownMenuItem(
                    text = { Text("\u67E5\u770B\u8BE6\u60C5") },
                    leadingIcon = { Icon(Icons.Rounded.MoreHoriz, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        onClick()
                    }
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            text = movie.title,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        movie.year?.let {
            Text(
                text = it.toString(),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlphaMin),
                style = MaterialTheme.typography.labelSmall
            )
        }
        if (showScrapeStatus) {
            movie.scrapeFailureReason?.takeIf(String::isNotBlank)?.let { reason ->
                Text(
                    text = reason,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun PosterPlaceholder(title: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.surface)
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = title.take(2).uppercase(),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.ExtraBold
        )
    }
}

@Composable
private fun EmptyLibraryState(
    isScanning: Boolean,
    onOpenLibrary: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp)
            .height(360.dp)
            .clip(AppRadius.XL)
            .background(MoviesPanel),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "\u5F71\u7247\u5E93\u8FD8\u662F\u7A7A\u7684",
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = if (isScanning) {
                    "\u6B63\u5728\u626B\u63CF\u5A92\u4F53\u6587\u4EF6\u5939..."
                } else {
                    "\u9009\u62E9\u4E00\u4E2A\u5A92\u4F53\u5E93\u76EE\u5F55\u5F00\u59CB\u6574\u7406\u5F71\u7247"
                },
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
                style = MaterialTheme.typography.bodyMedium
            )
            // H+1 轮：此前 onClick = { } 是空实现（死按钮），现接上跳转到媒体库目录选择
            AssistChip(
                onClick = onOpenLibrary,
                enabled = !isScanning,
                label = { Text(if (isScanning) "\u626B\u63CF\u4E2D..." else "\u9009\u62E9\u76EE\u5F55") }
            )
        }
    }
}

private fun scanMessage(scanState: ScanState): String = when (scanState) {
    ScanState.Idle -> "\u672C\u5730\u5F71\u7247\u3001\u6D77\u62A5\u548C NFO \u5143\u6570\u636E"
    ScanState.Scanning -> "\u6B63\u5728\u626B\u63CF\u6587\u4EF6\u5939..."
    is ScanState.Done -> "\u5DF2\u626B\u63CF ${scanState.count} \u90E8\u5F71\u7247"
    is ScanState.Error -> scanState.message
}

private enum class LibraryTab(val label: String) {
    All("全部"),
    Scraped("已刮削"),
    Unscraped("未刮削"),
    Vr("VR"),
    Collections("合集"),
    Actors("演员"),
    Tags("标签"),
    Genres("类型"),
    Years("年份"),
    Studios("工作室")
}

private fun MovieEntity.isVrMovie(): Boolean =
    tags.any { tag -> tag.contains("VR", ignoreCase = true) } ||
        title.contains("【VR】", ignoreCase = true) ||
        originalTitle.orEmpty().contains("【VR】", ignoreCase = true)

private fun List<String>.summaryValues(): List<MovieMetadataSummary> =
    map { it.trim() }
        .filter { it.isNotBlank() }
        .groupBy { it.metadataKey() }
        .map { (_, values) -> MovieMetadataSummary(values.first(), values.size) }
        .sortedWith(compareByDescending<MovieMetadataSummary> { it.count }.thenBy { it.value.lowercase() })

private fun sortLabel(option: HomeSortOption): String = when (option) {
    HomeSortOption.ImdbRating -> "IMDb \u8BC4\u5206"
    HomeSortOption.DateAdded -> "\u52A0\u5165\u65E5\u671F"
    HomeSortOption.ReleaseDate -> "\u53D1\u884C\u65E5\u671F"
    HomeSortOption.Director -> "\u5BFC\u6F14"
    HomeSortOption.Title -> "\u6807\u9898"
    HomeSortOption.Year -> "\u5E74\u4EFD"
    HomeSortOption.PlayDuration -> "\u64AD\u653E\u65F6\u957F"
    HomeSortOption.FileName -> "\u6587\u4EF6\u540D"
    HomeSortOption.Random -> "\u968F\u673A"
}
