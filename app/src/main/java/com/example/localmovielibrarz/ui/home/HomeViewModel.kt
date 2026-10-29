package com.example.localmovielibrarz.ui.home

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import com.example.localmovielibrarz.data.local.MovieEntity
import com.example.localmovielibrarz.data.local.MoviePlaybackKeyItem
import com.example.localmovielibrarz.data.local.PlaybackProgressListItem
import com.example.localmovielibrarz.data.repository.AppSettingsRepository
import com.example.localmovielibrarz.data.repository.CloudStrmRecordRepository
import com.example.localmovielibrarz.data.repository.MovieLibrarySummaries
import com.example.localmovielibrarz.data.repository.MovieRepository
import com.example.localmovielibrarz.data.repository.MovieScrapeTaskRunner
import com.example.localmovielibrarz.data.repository.MovieScrapeTaskRunnerState
import com.example.localmovielibrarz.data.repository.PlaybackProgressRepository
import com.example.localmovielibrarz.data.repository.StrmScrapeRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlin.random.Random

@OptIn(FlowPreview::class)
class HomeViewModel(
    private val repository: MovieRepository,
    private val settingsRepository: AppSettingsRepository,
    private val scrapeRepository: StrmScrapeRepository,
    private val playbackProgressRepository: PlaybackProgressRepository,
    private val movieScrapeTaskRunner: MovieScrapeTaskRunner,
    private val cloudStrmRecordRepository: CloudStrmRecordRepository
) : ViewModel() {
    private val scanState = MutableStateFlow<ScanState>(ScanState.Idle)
    private val sortState = MutableStateFlow(loadSavedSortState(settingsRepository))
    private val imageModeState = MutableStateFlow(loadSavedImageMode(settingsRepository))
    private val displayedMovies = MutableStateFlow<List<MovieEntity>>(emptyList())
    private val pendingMovies = MutableStateFlow<List<MovieEntity>>(emptyList())
    private val pendingNewCount = MutableStateFlow(0)
    private var hasLoadedMovieList = false
    private val librarySummaries = MutableStateFlow(MovieLibrarySummaries())
    private val avatarUpdateState = scrapeRepository.actorAvatarUpdateState
    private val libraryUpdateState = combine(avatarUpdateState, pendingNewCount) { avatarUpdate, newCount ->
        avatarUpdate to newCount
    }
    private val movieBaseBuckets = displayedMovies.map { movieList ->
        HomeMovieBaseBuckets(
            sourceMovies = movieList,
            recentlyAdded = movieList.sortedByDescending { it.scannedAtMillis }.take(12),
            favoriteMovies = movieList.filter { it.isFavorite }.sortedByDescending { it.updatedAt }.take(12),
            stats = LibraryStats(
                total = movieList.size,
                watched = movieList.count { it.isWatched },
                favorites = movieList.count { it.isFavorite }
            )
        )
    }.flowOn(Dispatchers.Default)
    private val movieBuckets = combine(movieBaseBuckets, sortState) { base, sort ->
        HomeMovieBuckets(
            sourceMovies = base.sourceMovies,
            movies = if (sort.option == HomeSortOption.Random) {
                base.sourceMovies.shuffled(Random(sort.randomSeed))
            } else {
                base.sourceMovies.sortedWith(movieComparator(sort))
            },
            recentlyAdded = base.recentlyAdded,
            favoriteMovies = base.favoriteMovies,
            sortState = sort,
            stats = base.stats
        )
    }.flowOn(Dispatchers.Default)
    private val playbackProgress = playbackProgressRepository.observeRecent(HOME_RECENT_PROGRESS_LIMIT)
    private val playbackKeys = repository.observeMoviePlaybackKeys()
    private val moviesWithProgress = combine(movieBuckets, playbackProgress, playbackKeys) { buckets, progress, keys ->
        val playbackIndex = buckets.sourceMovies.toPlaybackMovieIndex(keys)
        buckets to progress.recentlyPlayedMovies(playbackIndex).take(12)
    }.flowOn(Dispatchers.Default)
    private val scrapeIssueData = combine(
        repository.observeScrapeIssueMovies(),
        movieScrapeTaskRunner.state
    ) { movies, runnerState -> movies to runnerState }
    /** 影片库首帧是否已到：用于区分「正在载入」与「库里真的没有影片」。 */
    private val libraryLoaded = MutableStateFlow(false)

    private val homeMovieData = combine(
        moviesWithProgress,
        librarySummaries,
        scrapeIssueData
    ) { movieData, summaries, scrapeIssues -> Triple(movieData, summaries, scrapeIssues) }
        .onEach { libraryLoaded.value = true }

    init {
        viewModelScope.launch {
            // 首次加载不防抖，直接取数据；后续变更走 450ms 防抖防止扫描期间频繁刷新
            var isFirst = true
            repository.observeMovies()
                .debounce {
                    if (isFirst) {
                        isFirst = false
                        0L  // 首次不延迟
                    } else {
                        450L
                    }
                }
                .collect { latestMovies ->
                    handleDatabaseMovies(latestMovies)
                }
        }
    }

    val uiState: StateFlow<HomeUiState> = combine(homeMovieData, scanState, imageModeState, libraryUpdateState) { combinedHomeData, scan, imageMode, libraryUpdate ->
        val (combinedMovieData, summaries, scrapeIssues) = combinedHomeData
        val (buckets, recentlyPlayed) = combinedMovieData
        val (scrapeIssueMovies, scrapeRunnerState) = scrapeIssues
        val (avatarUpdate, newCount) = libraryUpdate
        HomeUiState(
            movies = buckets.movies,
            recentlyAdded = buckets.recentlyAdded,
            recentlyPlayed = recentlyPlayed,
            favoriteMovies = buckets.favoriteMovies,
            scanState = scan,
            sortState = buckets.sortState,
            imageMode = imageMode,
            isUpdatingActorAvatars = avatarUpdate.isUpdating,
            actorAvatarUpdateMessage = avatarUpdate.message,
            actorAvatarRefreshVersion = avatarUpdate.refreshVersion,
            pendingNewCount = newCount,
            scrapeIssueMovies = scrapeIssueMovies,
            scrapeRunnerState = scrapeRunnerState,
            librarySummaries = summaries,
            stats = buckets.stats,
            isLoading = !libraryLoaded.value
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    fun applyPendingMovieUpdates() {
        val pending = pendingMovies.value
        if (pending.isNotEmpty()) {
            displayedMovies.value = pending
        }
        pendingMovies.value = emptyList()
        pendingNewCount.value = 0
    }

    private fun handleDatabaseMovies(latestMovies: List<MovieEntity>) {
        val update = reduceHomeMovieListUpdate(
            displayed = displayedMovies.value,
            latest = latestMovies,
            isInitialLoad = !hasLoadedMovieList
        )
        hasLoadedMovieList = true
        // 数据库流已经经过 450ms 防抖；将同一时间段内的新增合并为一次自动刷新，
        // 不再要求用户点击横幅才能看到新影片。
        displayedMovies.value = update.pending.ifEmpty { update.displayed }
        pendingMovies.value = emptyList()
        pendingNewCount.value = 0
    }

    fun toggleFavorite(movie: MovieEntity) {
        viewModelScope.launch {
            repository.setFavorite(movie.id, !movie.isFavorite)
        }
    }

    fun toggleWatched(movie: MovieEntity) {
        viewModelScope.launch {
            repository.setWatched(movie.id, !movie.isWatched)
        }
    }

    fun setSortOption(option: HomeSortOption) {
        val next = sortState.value.copy(
            option = option,
            randomSeed = if (option == HomeSortOption.Random) Random.nextLong() else sortState.value.randomSeed
        )
        sortState.value = next
        settingsRepository.saveHomeSortOptionName(next.option.name)
    }

    fun toggleSortDirection() {
        val current = sortState.value
        val next = current.copy(
            direction = current.direction.toggle(),
            randomSeed = if (current.option == HomeSortOption.Random) Random.nextLong() else current.randomSeed
        )
        sortState.value = next
        settingsRepository.saveHomeSortDirectionName(next.direction.name)
    }

    fun setSortDirection(direction: HomeSortDirection) {
        val current = sortState.value
        val next = current.copy(
            direction = direction,
            randomSeed = if (current.option == HomeSortOption.Random) Random.nextLong() else current.randomSeed
        )
        sortState.value = next
        settingsRepository.saveHomeSortDirectionName(next.direction.name)
    }

    fun setImageMode(mode: HomeImageMode) {
        imageModeState.value = mode
        settingsRepository.saveHomeImageModeName(mode.name)
    }

    fun refreshLibrarySummaries() {
        viewModelScope.launch {
            runCatching { repository.getLibrarySummaries() }
                .onSuccess { librarySummaries.value = it }
        }
    }

    fun startBatchScrape() = movieScrapeTaskRunner.startIssues()

    fun pauseBatchScrape() = movieScrapeTaskRunner.pause()

    // ---------------- 批量操作（影片 tab） ----------------

    private val batchMutex = Mutex()
    private val _batchState = MutableStateFlow(HomeBatchState())
    val batchState: StateFlow<HomeBatchState> = _batchState.asStateFlow()

    fun clearBatchMessage() {
        _batchState.update { it.copy(message = null) }
    }

    /** 批量删除影片：删除影片记录 + 本地 STRM 文件 + 网盘记录（不删网盘上的原文件）。 */
    fun batchDeleteMovies(ids: List<Long>) {
        runBatch(ids, "删除影片") { movie ->
            val result = repository.deleteMovieWithFiles(movie.id)
            cloudStrmRecordRepository.deleteForMovie(movie.id, result.pickcodes)
            scrapeRepository.appendLog("批量删除影片：${movie.title}（id=${movie.id}）")
        }
    }

    /** 批量删除刮削数据（保留 STRM）：清除 NFO / 海报 / 元数据，还原为原始 STRM。 */
    fun batchClearScrapeData(ids: List<Long>) {
        runBatch(ids, "清除刮削数据") { movie ->
            scrapeRepository.clearScrapeFiles(movie)
            scrapeRepository.appendLog("批量清除刮削数据：${movie.title}（id=${movie.id}）")
        }
    }

    /** 批量重新刮削：用默认来源重新刮削并整理文件。 */
    fun batchRescrapeMovies(ids: List<Long>) {
        val source = settingsRepository.getDefaultScrapeSource()
        runBatch(ids, "重新刮削") { movie ->
            val result = scrapeRepository.rescrapeMovieWithOutput(movie, source)
            runCatching { repository.refreshMovieAtStoredPath(movie.id) }
            scrapeRepository.finishMovieScrapeTask(result.taskId, success = true, message = "批量重新刮削完成")
            scrapeRepository.appendLog("批量重新刮削：${movie.title}（id=${movie.id}）")
        }
    }

    private fun runBatch(ids: List<Long>, label: String, action: suspend (MovieEntity) -> Unit) {
        // 需求 6 并发保护：批量刮削进行中禁止进入批量删除/其他批量操作，避免并发读写 Room/文件导致的闪退
        if (movieScrapeTaskRunner.state.value.isRunning) {
            _batchState.update { it.copy(message = "正在批量刮削，请先停止后再执行${label}操作") }
            return
        }
        // 需求 6 并发保护：已有批量操作进行中时拒绝新请求
        if (_batchState.value.busy) {
            _batchState.update { it.copy(message = "正在执行批量操作，请等待完成") }
            return
        }
        // 同时从已展示影片和未刮削影片中查找，确保所有子tab的批量操作都能找到目标
        val allCandidates = displayedMovies.value + uiState.value.scrapeIssueMovies
        val targets = ids.mapNotNull { id -> allCandidates.firstOrNull { it.id == id } }
        if (targets.isEmpty()) {
            _batchState.update { it.copy(message = "没有找到选中的影片") }
            return
        }
        viewModelScope.launch {
            batchMutex.withLock {
                _batchState.value = HomeBatchState(busy = true, totalCount = targets.size)
                var done = 0
                var failed = 0
                // 需求 6：按块处理（每 5 条切 IO 后 yield），减少连续占用主线程时长，同时降低 Room 单事务压力
                targets.chunked(5).forEach { chunk ->
                    withContext(Dispatchers.IO) {
                        chunk.forEach { movie ->
                            runCatching { action(movie) }
                                .onFailure { error ->
                                    failed++
                                    scrapeRepository.appendLog("批量${label}失败：${movie.title}，原因：${error.message ?: error::class.java.simpleName}")
                                }
                            done++
                        }
                    }
                    yield()
                    // 需求 6 UI 刷新节流：每 10 项刷新一次计数，降低重组频率
                    if (done % 10 == 0 || done == targets.size || failed > 0) {
                        _batchState.update { it.copy(doneCount = done, failedCount = failed) }
                    }
                }
                _batchState.update {
                    it.copy(
                        busy = false,
                        doneCount = targets.size,
                        message = "批量${label}完成：成功 ${done - failed} 个${if (failed > 0) "，失败 $failed 个" else ""}"
                    )
                }
            }
        }
    }

    companion object {
        private const val HOME_RECENT_PROGRESS_LIMIT = 100

        fun factory(
            repository: MovieRepository,
            settingsRepository: AppSettingsRepository,
            scrapeRepository: StrmScrapeRepository,
            playbackProgressRepository: PlaybackProgressRepository,
            movieScrapeTaskRunner: MovieScrapeTaskRunner,
            cloudStrmRecordRepository: CloudStrmRecordRepository
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    HomeViewModel(
                        repository,
                        settingsRepository,
                        scrapeRepository,
                        playbackProgressRepository,
                        movieScrapeTaskRunner,
                        cloudStrmRecordRepository
                    ) as T
            }
    }
}

/** 批量操作进度状态。 */
data class HomeBatchState(
    val busy: Boolean = false,
    val message: String? = null,
    val doneCount: Int = 0,
    val totalCount: Int = 0,
    val failedCount: Int = 0
)

internal data class HomeMovieListUpdate(
    val displayed: List<MovieEntity>,
    val pending: List<MovieEntity>,
    val pendingCount: Int
)

internal fun excludeScrapeIssueMovies(
    movies: List<MovieEntity>,
    scrapeIssueMovies: List<MovieEntity>
): List<MovieEntity> {
    if (scrapeIssueMovies.isEmpty()) return movies
    val issueIds = scrapeIssueMovies.mapTo(HashSet(scrapeIssueMovies.size), MovieEntity::id)
    return movies.filterNot { it.id in issueIds }
}

internal fun reduceHomeMovieListUpdate(
    displayed: List<MovieEntity>,
    latest: List<MovieEntity>,
    isInitialLoad: Boolean = false
): HomeMovieListUpdate {
    if (isInitialLoad) {
        return HomeMovieListUpdate(latest, emptyList(), 0)
    }

    // 删除不应等待用户手动刷新；保留已删除项会在网格中留下空白卡位。
    val latestIds = latest.mapTo(hashSetOf(), MovieEntity::id)
    if (displayed.any { it.id !in latestIds }) {
        return HomeMovieListUpdate(latest, emptyList(), 0)
    }

    val changedCount = countPresentationChanges(displayed, latest)
    if (changedCount == 0) {
        val latestById = latest.associateBy(MovieEntity::id)
        val hasInteractionChanges = displayed.any { current ->
            val next = latestById[current.id] ?: return@any false
            current.isFavorite != next.isFavorite || current.isWatched != next.isWatched
        }
        val nextDisplayed = if (hasInteractionChanges) latest else displayed
        return HomeMovieListUpdate(nextDisplayed, emptyList(), 0)
    }

    val latestById = latest.associateBy(MovieEntity::id)
    val stableDisplayed = displayed.map { current ->
        val next = latestById[current.id] ?: return@map current
        if (current.isFavorite == next.isFavorite && current.isWatched == next.isWatched) {
            current
        } else {
            current.copy(
                isFavorite = next.isFavorite,
                isWatched = next.isWatched,
                updatedAt = next.updatedAt
            )
        }
    }
    return HomeMovieListUpdate(stableDisplayed, latest, changedCount)
}

private fun countPresentationChanges(
    displayed: List<MovieEntity>,
    latest: List<MovieEntity>
): Int {
    val displayedById = displayed.associateBy(MovieEntity::id)
    val latestById = latest.associateBy(MovieEntity::id)
    return (displayedById.keys + latestById.keys).count { id ->
        val current = displayedById[id]
        val next = latestById[id]
        current == null || next == null || current.presentationSnapshot() != next.presentationSnapshot()
    }
}

private fun MovieEntity.presentationSnapshot(): MovieEntity = copy(
    isFavorite = false,
    isWatched = false,
    updatedAt = 0L,
    scrapeFailureReason = null,
    scrapeTaskStatus = com.example.localmovielibrarz.data.local.ScrapeTaskStatus.None.name
)

private fun loadSavedSortState(settingsRepository: AppSettingsRepository): HomeSortState {
    val option = settingsRepository.getHomeSortOptionName()
        ?.let { stored -> HomeSortOption.entries.firstOrNull { it.name == stored } }
        ?: HomeSortOption.ReleaseDate
    val direction = settingsRepository.getHomeSortDirectionName()
        ?.let { stored -> HomeSortDirection.entries.firstOrNull { it.name == stored } }
        ?: HomeSortDirection.Descending
    return HomeSortState(option = option, direction = direction)
}

private fun loadSavedImageMode(settingsRepository: AppSettingsRepository): HomeImageMode =
    settingsRepository.getHomeImageModeName()
        ?.let { stored -> HomeImageMode.entries.firstOrNull { it.name == stored } }
        ?: HomeImageMode.Poster

private fun movieComparator(sort: HomeSortState): Comparator<MovieEntity> =
    Comparator { left, right ->
        val result = compareBySortOption(left, right, sort.option)
            .takeIf { it != 0 }
            ?: compareStrings(left.sortTitle.ifBlank { left.title }, right.sortTitle.ifBlank { right.title })
        if (sort.direction == HomeSortDirection.Ascending) result else -result
    }

private fun compareBySortOption(left: MovieEntity, right: MovieEntity, option: HomeSortOption): Int =
    when (option) {
        HomeSortOption.ImdbRating -> compareNullable(left.rating, right.rating)
        HomeSortOption.DateAdded -> compareValues(left.scannedAtMillis, right.scannedAtMillis)
        HomeSortOption.ReleaseDate -> compareNullable(left.premiered ?: left.year?.toString(), right.premiered ?: right.year?.toString())
        HomeSortOption.Director -> compareStrings(left.directors.firstOrNull().orEmpty(), right.directors.firstOrNull().orEmpty())
        HomeSortOption.Year -> compareNullable(left.year, right.year)
        HomeSortOption.PlayDuration -> compareNullable(left.runtimeMinutes, right.runtimeMinutes)
        HomeSortOption.FileName -> compareStrings(left.videoName, right.videoName)
        HomeSortOption.Title -> compareStrings(left.sortTitle.ifBlank { left.title }, right.sortTitle.ifBlank { right.title })
        HomeSortOption.Random -> 0
    }

private fun compareStrings(left: String, right: String): Int =
    left.lowercase().compareTo(right.lowercase())

private fun <T : Comparable<T>> compareNullable(left: T?, right: T?): Int =
    when {
        left == null && right == null -> 0
        left == null -> 1
        right == null -> -1
        else -> left.compareTo(right)
    }

private fun List<PlaybackProgressListItem>.recentlyPlayedMovies(index: PlaybackMovieIndex): List<MovieEntity> {
    if (isEmpty() || index.isEmpty) return emptyList()
    return mapNotNull { progress ->
        index.byUri[progress.mediaKey]
            ?: progress.mediaKey.parentDocumentKey()?.let { index.byParentDocument[it] }
    }.distinctBy { it.id }
}

private fun List<MovieEntity>.toPlaybackMovieIndex(keys: List<MoviePlaybackKeyItem>): PlaybackMovieIndex {
    if (isEmpty() || keys.isEmpty()) return PlaybackMovieIndex()
    val moviesById = associateBy { it.id }
    val keyPairs = keys.mapNotNull { key ->
        val movie = moviesById[key.id] ?: return@mapNotNull null
        key.videoUri to movie
    }
    return PlaybackMovieIndex(
        byUri = keyPairs.toMap(),
        byParentDocument = keyPairs.mapNotNull { (videoUri, movie) ->
            videoUri.parentDocumentKey()?.let { parent -> parent to movie }
        }.toMap()
    )
}

private fun String.parentDocumentKey(): String? {
    val uri = runCatching { Uri.parse(this) }.getOrNull() ?: return null
    val documentId = uri.documentIdFromSafUri() ?: return null
    return documentId.substringBeforeLast('/', missingDelimiterValue = "")
        .takeIf { it.isNotBlank() }
}

private fun Uri.documentIdFromSafUri(): String? {
    val index = pathSegments.indexOf("document")
    return index.takeIf { it >= 0 && it + 1 < pathSegments.size }
        ?.let { Uri.decode(pathSegments[it + 1]) }
}

data class HomeUiState(
    val movies: List<MovieEntity> = emptyList(),
    val recentlyAdded: List<MovieEntity> = emptyList(),
    val recentlyPlayed: List<MovieEntity> = emptyList(),
    val favoriteMovies: List<MovieEntity> = emptyList(),
    val scanState: ScanState = ScanState.Idle,
    val sortState: HomeSortState = HomeSortState(),
    val imageMode: HomeImageMode = HomeImageMode.Poster,
    val isUpdatingActorAvatars: Boolean = false,
    val actorAvatarUpdateMessage: String? = null,
    val actorAvatarRefreshVersion: Int = 0,
    val pendingNewCount: Int = 0,
    val scrapeIssueMovies: List<MovieEntity> = emptyList(),
    val scrapeRunnerState: MovieScrapeTaskRunnerState = MovieScrapeTaskRunnerState(),
    val librarySummaries: MovieLibrarySummaries = MovieLibrarySummaries(),
    val stats: LibraryStats = LibraryStats(),
    /** 首帧未到时为 true：UI 用它展示加载态而不是空态。 */
    val isLoading: Boolean = false
) {
    val hasPendingMovieUpdates: Boolean get() = pendingNewCount > 0
}

data class HomeSortState(
    val option: HomeSortOption = HomeSortOption.ReleaseDate,
    val direction: HomeSortDirection = HomeSortDirection.Descending,
    val randomSeed: Long = Random.nextLong()
)

data class LibraryStats(
    val total: Int = 0,
    val watched: Int = 0,
    val favorites: Int = 0
)

private data class HomeMovieBuckets(
    val sourceMovies: List<MovieEntity> = emptyList(),
    val movies: List<MovieEntity> = emptyList(),
    val recentlyAdded: List<MovieEntity> = emptyList(),
    val favoriteMovies: List<MovieEntity> = emptyList(),
    val sortState: HomeSortState = HomeSortState(),
    val stats: LibraryStats = LibraryStats()
)

private data class HomeMovieBaseBuckets(
    val sourceMovies: List<MovieEntity> = emptyList(),
    val recentlyAdded: List<MovieEntity> = emptyList(),
    val favoriteMovies: List<MovieEntity> = emptyList(),
    val stats: LibraryStats = LibraryStats()
)

private data class PlaybackMovieIndex(
    val byUri: Map<String, MovieEntity> = emptyMap(),
    val byParentDocument: Map<String, MovieEntity> = emptyMap()
) {
    val isEmpty: Boolean get() = byUri.isEmpty() && byParentDocument.isEmpty()
}

sealed interface ScanState {
    data object Idle : ScanState
    data object Scanning : ScanState
    data class Done(val count: Int) : ScanState
    data class Error(val message: String) : ScanState
}

enum class HomeSortOption {
    ImdbRating,
    DateAdded,
    ReleaseDate,
    Director,
    Year,
    PlayDuration,
    FileName,
    Title,
    Random
}

enum class HomeSortDirection {
    Ascending,
    Descending;

    fun toggle(): HomeSortDirection =
        if (this == Ascending) Descending else Ascending
}

enum class HomeImageMode {
    Poster,
    Thumb
}
