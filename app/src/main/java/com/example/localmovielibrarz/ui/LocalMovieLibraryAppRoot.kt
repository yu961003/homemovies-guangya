package com.example.localmovielibrarz.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import com.example.localmovielibrarz.data.AppContainer
import com.example.localmovielibrarz.data.repository.AppUpdateInfo
import com.example.localmovielibrarz.ui.cloud.CloudBrowserScreen
import com.example.localmovielibrarz.ui.cloud.CloudBrowserViewModel
import com.example.localmovielibrarz.ui.detail.DetailScreen
import com.example.localmovielibrarz.ui.detail.DetailViewModel
import com.example.localmovielibrarz.ui.favorites.FavoritesScreen
import com.example.localmovielibrarz.ui.favorites.FavoritesViewModel
import com.example.localmovielibrarz.ui.filter.FilterResultScreen
import com.example.localmovielibrarz.ui.filter.FilterResultViewModel
import com.example.localmovielibrarz.ui.guangya.GuangyaBrowserScreen
import com.example.localmovielibrarz.ui.guangya.GuangyaBrowserViewModel
import com.example.localmovielibrarz.ui.home.HomeScreen
import com.example.localmovielibrarz.ui.home.HomeImageMode
import com.example.localmovielibrarz.ui.home.HomeViewModel
import com.example.localmovielibrarz.ui.home.MoviesLibraryScreen
import com.example.localmovielibrarz.ui.logs.PlayerLogScreen
import com.example.localmovielibrarz.ui.logs.ScrapeLogScreen
import com.example.localmovielibrarz.ui.logs.ScrapeLogViewModel
import com.example.localmovielibrarz.ui.player.PlayerScreen
import com.example.localmovielibrarz.ui.player.PlayerViewModel
import com.example.localmovielibrarz.ui.search.SearchScreen
import com.example.localmovielibrarz.ui.search.SearchViewModel
import com.example.localmovielibrarz.ui.settings.SettingsScreen
import com.example.localmovielibrarz.ui.settings.SettingsViewModel
import com.example.localmovielibrarz.ui.shared.Top250BadgeProvider
import kotlinx.coroutines.delay

@Composable
fun LocalMovieLibraryAppRoot(appContainer: AppContainer) {
    val context = LocalContext.current
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val unfinishedMovieScrapeTaskCount by appContainer.movieRepository
        .observeUnfinishedScrapeTaskCount()
        .collectAsState(initial = 0)
    val unfinishedFolderBatchTaskCount by appContainer.cloudFolderBatchTaskRepository
        .observeUnfinishedTaskCount()
        .collectAsState(initial = 0)
    val unfinishedCloudVideoTaskCount by appContainer.cloudVideoTaskRepository
        .observeUnfinishedTaskCount()
        .collectAsState(initial = 0)
    val unfinishedScrapeTaskCount = unfinishedMovieScrapeTaskCount +
        unfinishedFolderBatchTaskCount + unfinishedCloudVideoTaskCount
    val latestUnfinishedScrapeTaskCount by rememberUpdatedState(unfinishedScrapeTaskCount)
    var scrapeTaskPromptDismissed by remember { mutableStateOf(false) }
    var showStartupScrapeTaskPrompt by remember { mutableStateOf(false) }
    var startupUpdateInfo by remember { mutableStateOf<AppUpdateInfo?>(null) }
    var showStartupAnimation by remember {
        mutableStateOf(appContainer.settingsRepository.isStartupAnimationEnabled())
    }
    val useLightTheme by appContainer.settingsRepository.observeLightThemeEnabled()
        .collectAsState(initial = appContainer.settingsRepository.isLightThemeEnabled())
    val showBottomBar = currentRoute == Route.Home ||
        currentRoute == Route.Movies ||
        currentRoute == Route.MovieLibrary ||
        currentRoute == Route.FilterResult ||
        currentRoute == Route.Favorites ||
        currentRoute == Route.Search ||
        currentRoute == Route.CloudBrowser ||
        currentRoute == Route.Settings ||
        currentRoute == Route.SettingsUpdate ||
        currentRoute == Route.SettingsScrapeTasks

    /** 统一播放入口：回归纯 ExoPlayer（PlayerScreen）。 */
    fun playNav(videoUri: String, title: String, fileName: String, movieId: Long = -1L) {
        navController.navigate(Route.player(videoUri, title, fileName, movieId))
    }

    LaunchedEffect(Unit) {
        appContainer.appUpdateRepository.cleanupInstalledUpdateApksIfNeeded()
        if (!appContainer.settingsRepository.isUpdateAutoCheckOnStartupEnabled()) return@LaunchedEffect
        delay(8_000)
        if (!appContainer.settingsRepository.isUpdateAutoCheckOnStartupEnabled()) return@LaunchedEffect
        runCatching { appContainer.appUpdateRepository.checkForUpdate() }
            .onSuccess { result ->
                if (result.hasUpdate) {
                    startupUpdateInfo = result.latest
                }
            }
    }

    // ★ 第九轮：v18 迁移后首次启动触发播放来源回填（后台 IO，不阻塞 UI；
    // 完成标志由 AppContainer.maybeRunSourceBackfillAsync 内部置位，失败下次启动重试）
    LaunchedEffect(Unit) {
        appContainer.maybeRunSourceBackfillAsync()
    }

    // ★ F-5：网盘文件夹批量任务冷启动续跑。
    // 之前进程被杀后 runner 就彻底停了，库里留着 Running，用户得手动进设置页点「开始」，
    // 而且中途的断点也没人接着用。这里延后一点（避开启动首帧的 IO 高峰）再判断：
    // 只有存在被中断的 Running 才自动开跑，用户主动暂停/取消的任务不会被偷偷唤醒。
    LaunchedEffect(Unit) {
        delay(1_200)
        appContainer.cloudFolderBatchTaskRunner.resumeInterruptedIfNeeded()
    }

    // ★ 冷启动数据侧恢复：来源冷却回灌 + 任务账本里失联的 Running 归位。
    // 与上面的 folder runner 恢复是两件事：这里只管「内存状态与账本」，不主动拉起任何业务 runner。
    LaunchedEffect(Unit) {
        appContainer.resumeInterruptedTasksAsync()
    }

    LaunchedEffect(Unit) {
        delay(800)
        if (latestUnfinishedScrapeTaskCount > 0) {
            showStartupScrapeTaskPrompt = true
        }
    }

    HomeMovieTheme(useLightTheme = useLightTheme) {
    // 榜单角标：在主题内、Scaffold 外下发一次，所有影片卡片共享同一份名次表。
    Top250BadgeProvider {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (showBottomBar) {
                AppBottomNavigation(
                    currentRoute = currentRoute,
                    onNavigate = { route ->
                        navController.navigate(route) {
                            popUpTo(Route.Home) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.background)
                .padding(innerPadding)
        ) {
            NavHost(navController = navController, startDestination = Route.Home) {
                composable(Route.Home) {
                    val viewModel: HomeViewModel = viewModel(
                        factory = HomeViewModel.factory(
                            repository = appContainer.movieRepository,
                            settingsRepository = appContainer.settingsRepository,
                            scrapeRepository = appContainer.strmScrapeRepository,
                            playbackProgressRepository = appContainer.playbackProgressRepository,
                            movieScrapeTaskRunner = appContainer.movieScrapeTaskRunner,
                            cloudStrmRecordRepository = appContainer.cloudStrmRecordRepository
                        )
                    )
                    HomeScreen(
                        viewModel = viewModel,
                        onMovieClick = { movieId -> navController.navigate(Route.detail(movieId)) },
                        onPlay = { movie -> playNav(movie.videoUri, movie.title, movie.videoName, movie.id) },
                        onOpenLibrary = { navController.navigate(Route.Movies) }
                    )
                }
                composable(Route.Movies) {
                    val viewModel: HomeViewModel = viewModel(
                        factory = HomeViewModel.factory(
                            repository = appContainer.movieRepository,
                            settingsRepository = appContainer.settingsRepository,
                            scrapeRepository = appContainer.strmScrapeRepository,
                            playbackProgressRepository = appContainer.playbackProgressRepository,
                            movieScrapeTaskRunner = appContainer.movieScrapeTaskRunner,
                            cloudStrmRecordRepository = appContainer.cloudStrmRecordRepository
                        )
                    )
                    MoviesLibraryScreen(
                        viewModel = viewModel,
                        onMovieClick = { movieId -> navController.navigate(Route.detail(movieId)) },
                        onPlay = { movie -> playNav(movie.videoUri, movie.title, movie.videoName, movie.id) },
                        onFilterClick = { filterType, filterValue ->
                            navController.navigate(Route.filterResult(filterType, filterValue))
                        }
                    )
                }
                composable(Route.Search) {
                    val viewModel: SearchViewModel = viewModel(
                        factory = SearchViewModel.factory(appContainer.movieRepository)
                    )
                    SearchScreen(
                        viewModel = viewModel,
                        onMovieClick = { movieId -> navController.navigate(Route.detail(movieId)) },
                        imageMode = appContainer.homeImageMode()
                    )
                }
                composable(Route.CloudBrowser) {
                    // 网盘 tab：切换栏与页面顶栏共用同一渐变容器（状态栏由外层统一处理），连成一体
                    // 需求 7：左右滑动联动。用 HorizontalPager 承载 115 / 光鸭 两页，currentPage 持久化。
                    val scope = rememberCoroutineScope()
                    val savedProvider =
                        appContainer.settingsRepository.getCloudProviderIndex().coerceIn(0, 1)
                    val pagerState = rememberPagerState(
                        initialPage = savedProvider,
                        pageCount = { 2 }
                    )
                    Column(modifier = Modifier.fillMaxSize()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    Brush.verticalGradient(
                                        listOf(
                                            MaterialTheme.colorScheme.surfaceVariant,
                                            MaterialTheme.colorScheme.background
                                        )
                                    )
                                )
                                .windowInsetsPadding(WindowInsets.statusBars)
                                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 2.dp)
                        ) {
                            // 顶栏高亮始终跟随当前页；点击切换动画滚动到目标页并持久化
                            CloudProviderSwitchBar(
                                selected = pagerState.currentPage,
                                onSelect = { index ->
                                    appContainer.settingsRepository.saveCloudProviderIndex(index)
                                    scope.launch { pagerState.animateScrollToPage(index) }
                                }
                            )
                        }
                        // 左右滑动：横向分页承载两供应商。beyondViewportPageCount=1 预加载相邻页降低切页卡顿；
                        // 两页各自持有独立 ViewModel（状态由 NavBackStackEntry 的 ViewModelStore 保留，滑动不丢列表位置）。
                        Box(modifier = Modifier.weight(1f)) {
                            HorizontalPager(
                                state = pagerState,
                                beyondViewportPageCount = 1,
                                flingBehavior = PagerDefaults.flingBehavior(state = pagerState)
                            ) { page ->
                                when (page) {
                                    0 -> {
                                        val viewModel: CloudBrowserViewModel = viewModel(
                                            factory = CloudBrowserViewModel.factory(
                                                strmRepository = appContainer.cloud115StrmRepository,
                                                recordRepository = appContainer.cloudStrmRecordRepository,
                                                settingsRepository = appContainer.settingsRepository,
                                                movieRepository = appContainer.movieRepository,
                                                scrapeRepository = appContainer.strmScrapeRepository,
                                                folderBatchTaskRepository = appContainer.cloudFolderBatchTaskRepository,
                                                folderBatchTaskRunner = appContainer.cloudFolderBatchTaskRunner,
                                                videoTaskRepository = appContainer.cloudVideoTaskRepository,
                                                videoTaskRunner = appContainer.cloudVideoTaskRunner
                                            )
                                        )
                                        CloudBrowserScreen(
                                            viewModel = viewModel,
                                            onBack = { navController.popBackStack() },
                                            onPlayVideo = { item ->
                                                val pickcode = item.pickcode ?: return@CloudBrowserScreen
                                                playNav("cloud115://play/$pickcode", item.name, item.name)
                                            },
                                            onMovieAdded = { },
                                            topInsetHandled = true,
                                            backEnabled = pagerState.currentPage == 0 // ★ E-1：仅当前页接管返回
                                        )
                                    }

                                    else -> {
                                        val viewModel: GuangyaBrowserViewModel = viewModel(
                                            factory = GuangyaBrowserViewModel.factory(
                                                repository = appContainer.guangyaBrowserRepository,
                                                strmRepository = appContainer.guangyaStrmRepository,
                                                recordRepository = appContainer.cloudStrmRecordRepository,
                                                movieRepository = appContainer.movieRepository,
                                                scrapeRepository = appContainer.strmScrapeRepository,
                                                settingsRepository = appContainer.settingsRepository,
                                                offlineMemory = appContainer.guangyaOfflineMemory,
                                                offlineSubmitter = appContainer.guangyaOfflineSubmitter
                                            )
                                        )
                                        GuangyaBrowserScreen(
                                            viewModel = viewModel,
                                            onBack = { navController.popBackStack() },
                                            onPlayVideo = { item ->
                                                playNav("guangya://guangya_res/${item.fileId}", item.name, item.name)
                                            },
                                            topInsetHandled = true,
                                            backEnabled = pagerState.currentPage == 1 // ★ E-1：仅当前页接管返回
                                        )
                                    }
                                }
                            }
                        }
                    }
                    // 滑动后同步顶栏 selected 并持久化 providerIndex（重启记住上次 tab）
                    LaunchedEffect(pagerState) {
                        snapshotFlow { pagerState.currentPage }
                            .distinctUntilChanged()
                            .collect { index ->
                                appContainer.settingsRepository.saveCloudProviderIndex(index)
                            }
                    }
                }
                composable(Route.GuangyaBrowser) {
                    val viewModel: GuangyaBrowserViewModel = viewModel(
                        factory = GuangyaBrowserViewModel.factory(
                            repository = appContainer.guangyaBrowserRepository,
                            strmRepository = appContainer.guangyaStrmRepository,
                            recordRepository = appContainer.cloudStrmRecordRepository,
                            movieRepository = appContainer.movieRepository,
                            scrapeRepository = appContainer.strmScrapeRepository,
                            settingsRepository = appContainer.settingsRepository,
                            offlineMemory = appContainer.guangyaOfflineMemory,
                                            offlineSubmitter = appContainer.guangyaOfflineSubmitter
                        )
                    )
                    GuangyaBrowserScreen(
                        viewModel = viewModel,
                        onBack = { navController.popBackStack() },
                        onPlayVideo = { item ->
                            playNav("guangya://guangya_res/${item.fileId}", item.name, item.name)
                        }
                    )
                }
                composable(Route.Settings) {
                    val viewModel: SettingsViewModel = viewModel(
                        factory = SettingsViewModel.factory(
                            repository = appContainer.settingsRepository,
                            movieRepository = appContainer.movieRepository,
                            cloudStrmRecordRepository = appContainer.cloudStrmRecordRepository,
                            scrapeRepository = appContainer.strmScrapeRepository,
                            appUpdateRepository = appContainer.appUpdateRepository,
                            cloud115QrLoginClient = appContainer.cloud115QrLoginClient,
                            cloudFolderBatchTaskRepository = appContainer.cloudFolderBatchTaskRepository,
                            cloudFolderBatchTaskRunner = appContainer.cloudFolderBatchTaskRunner,
                            cloudVideoTaskRepository = appContainer.cloudVideoTaskRepository,
                            cloudVideoTaskRunner = appContainer.cloudVideoTaskRunner,
                            movieScrapeTaskRunner = appContainer.movieScrapeTaskRunner,
                            movieMetadataRefreshRunner = appContainer.movieMetadataRefreshRunner,
                            localSubtitleLibraryRepository = appContainer.localSubtitleLibraryRepository
                        )
                    )
                    SettingsScreen(
                        viewModel = viewModel,
                        onOpenScrapeLogs = { navController.navigate(Route.ScrapeLogs) },
                        onOpenPlayerLogs = { navController.navigate(Route.PlayerLogs) },
                        onOpenGuangyaBrowser = { navController.navigate(Route.GuangyaBrowser) }
                    )
                }
                composable(Route.SettingsUpdate) {
                    val viewModel: SettingsViewModel = viewModel(
                        factory = SettingsViewModel.factory(
                            repository = appContainer.settingsRepository,
                            movieRepository = appContainer.movieRepository,
                            cloudStrmRecordRepository = appContainer.cloudStrmRecordRepository,
                            scrapeRepository = appContainer.strmScrapeRepository,
                            appUpdateRepository = appContainer.appUpdateRepository,
                            cloud115QrLoginClient = appContainer.cloud115QrLoginClient,
                            cloudFolderBatchTaskRepository = appContainer.cloudFolderBatchTaskRepository,
                            cloudFolderBatchTaskRunner = appContainer.cloudFolderBatchTaskRunner,
                            cloudVideoTaskRepository = appContainer.cloudVideoTaskRepository,
                            cloudVideoTaskRunner = appContainer.cloudVideoTaskRunner,
                            movieScrapeTaskRunner = appContainer.movieScrapeTaskRunner,
                            movieMetadataRefreshRunner = appContainer.movieMetadataRefreshRunner,
                            localSubtitleLibraryRepository = appContainer.localSubtitleLibraryRepository
                        )
                    )
                    SettingsScreen(
                        viewModel = viewModel,
                        onOpenScrapeLogs = { navController.navigate(Route.ScrapeLogs) },
                        onOpenPlayerLogs = { navController.navigate(Route.PlayerLogs) },
                        openUpdatePage = true,
                        onBack = { navController.popBackStack() }
                    )
                }
                composable(Route.SettingsScrapeTasks) {
                    val viewModel: SettingsViewModel = viewModel(
                        factory = SettingsViewModel.factory(
                            repository = appContainer.settingsRepository,
                            movieRepository = appContainer.movieRepository,
                            cloudStrmRecordRepository = appContainer.cloudStrmRecordRepository,
                            scrapeRepository = appContainer.strmScrapeRepository,
                            appUpdateRepository = appContainer.appUpdateRepository,
                            cloud115QrLoginClient = appContainer.cloud115QrLoginClient,
                            cloudFolderBatchTaskRepository = appContainer.cloudFolderBatchTaskRepository,
                            cloudFolderBatchTaskRunner = appContainer.cloudFolderBatchTaskRunner,
                            cloudVideoTaskRepository = appContainer.cloudVideoTaskRepository,
                            cloudVideoTaskRunner = appContainer.cloudVideoTaskRunner,
                            movieScrapeTaskRunner = appContainer.movieScrapeTaskRunner,
                            movieMetadataRefreshRunner = appContainer.movieMetadataRefreshRunner,
                            localSubtitleLibraryRepository = appContainer.localSubtitleLibraryRepository
                        )
                    )
                    SettingsScreen(
                        viewModel = viewModel,
                        onOpenScrapeLogs = { navController.navigate(Route.ScrapeLogs) },
                        onOpenPlayerLogs = { navController.navigate(Route.PlayerLogs) },
                        openScrapeTasksPage = true,
                        onBack = { navController.popBackStack() }
                    )
                }
                composable(Route.ScrapeLogs) {
                    val viewModel: ScrapeLogViewModel = viewModel(
                        factory = ScrapeLogViewModel.factory(appContainer.strmScrapeRepository)
                    )
                    ScrapeLogScreen(
                        viewModel = viewModel,
                        onBack = { navController.popBackStack() }
                    )
                }
                composable(Route.PlayerLogs) {
                    PlayerLogScreen(
                        errorLog = appContainer.runtimeErrorLog,
                        onBack = { navController.popBackStack() }
                    )
                }
                composable(Route.Favorites) {
                    val viewModel: FavoritesViewModel = viewModel(
                        factory = FavoritesViewModel.factory(appContainer.movieRepository)
                    )
                    FavoritesScreen(
                        viewModel = viewModel,
                        onMovieClick = { movieId -> navController.navigate(Route.detail(movieId)) },
                        imageMode = appContainer.homeImageMode()
                    )
                }
                composable(
                    route = Route.Detail,
                    arguments = listOf(navArgument("movieId") { type = NavType.LongType })
                ) { entry ->
                    val movieId = entry.arguments?.getLong("movieId") ?: return@composable
                    val viewModel: DetailViewModel = viewModel(
                        key = "detail-$movieId",
                        factory = DetailViewModel.factory(
                            context = context,
                            movieId = movieId,
                            repository = appContainer.movieRepository,
                            cloudStrmRecordRepository = appContainer.cloudStrmRecordRepository,
                            scrapeRepository = appContainer.strmScrapeRepository,
                            settingsRepository = appContainer.settingsRepository,
                            cloud115Client = appContainer.cloud115Client
                        )
                    )
                    DetailScreen(
                        viewModel = viewModel,
                        onBack = { navController.popBackStack() },
                        onPlay = { videoUri, title, fileName -> playNav(videoUri, title, fileName, movieId) },
                        onFilterClick = { filterType, filterValue ->
                            navController.navigate(Route.filterResult(filterType, filterValue))
                        },
                        onMovieClick = { similarMovieId -> navController.navigate(Route.detail(similarMovieId)) },
                        onOpenMovie = { newMovieId ->
                            navController.popBackStack()
                            navController.navigate(Route.detail(newMovieId))
                        }
                    )
                }
                composable(
                    route = Route.FilterResult,
                    arguments = listOf(
                        navArgument("filterType") { type = NavType.StringType },
                        navArgument("filterValue") { type = NavType.StringType }
                    )
                ) { entry ->
                    val filterType = entry.arguments?.getString("filterType").orEmpty()
                    val filterValue = Uri.decode(entry.arguments?.getString("filterValue").orEmpty())
                    val viewModel: FilterResultViewModel = viewModel(
                        key = "filter-$filterType-$filterValue",
                        factory = FilterResultViewModel.factory(filterType, filterValue, appContainer.movieRepository)
                    )
                    FilterResultScreen(
                        viewModel = viewModel,
                        onBack = { navController.popBackStack() },
                        onMovieClick = { movieId -> navController.navigate(Route.detail(movieId)) },
                        imageMode = appContainer.homeImageMode()
                    )
                }
                composable(
                    route = Route.Player,
                    arguments = listOf(
                        navArgument("videoUri") { type = NavType.StringType },
                        navArgument("title") {
                            type = NavType.StringType
                            defaultValue = "Movie"
                        },
                        navArgument("fileName") {
                            type = NavType.StringType
                            defaultValue = ""
                        },
                        navArgument("movieId") { type = NavType.LongType; defaultValue = -1L }
                    )
                ) { entry ->
                    val videoUri = entry.arguments?.getString("videoUri") ?: return@composable
                    val title = entry.arguments?.getString("title") ?: "Movie"
                    val fileName = entry.arguments?.getString("fileName").orEmpty()
                    val movieId = entry.arguments?.getLong("movieId") ?: -1L
                    val parsedUri = Uri.parse(videoUri)
                    val application = LocalContext.current.applicationContext as android.app.Application
                    val viewModel: PlayerViewModel = viewModel(
                        key = "player-$videoUri",
                        factory = PlayerViewModel.factory(
                            application = application,
                            videoUri = parsedUri,
                            title = title,
                            fileName = fileName,
                            directLinkRepository = appContainer.directLinkRepository,
                            guangyaDirectLinkResolver = appContainer.guangyaDirectLinkResolver,
                            cloud115Client = appContainer.cloud115Client,
                            cloudStrmRecordRepository = appContainer.cloudStrmRecordRepository,
                            settingsRepository = appContainer.settingsRepository,
                            playbackProgressRepository = appContainer.playbackProgressRepository,
                            movieRepository = appContainer.movieRepository,
                            movieId = movieId,
                            usageStatsRepository = appContainer.usageStatsRepository,
                            localSubtitleLibraryRepository = appContainer.localSubtitleLibraryRepository
                        )
                    )
                    PlayerScreen(
                        viewModel = viewModel,
                        onBack = { navController.popBackStack() }
                    )
                }
            }
        }
    }
    }

    if (showStartupAnimation) {
        StartupAnimationOverlay(
            imageUri = appContainer.settingsRepository.getStartupAnimationImageUri(),
            onFinished = { showStartupAnimation = false }
        )
    }

    if (showStartupScrapeTaskPrompt && unfinishedScrapeTaskCount > 0 && !scrapeTaskPromptDismissed) {
        AlertDialog(
            onDismissRequest = { scrapeTaskPromptDismissed = true },
            title = { Text("有未完成任务") },
            text = {
                Text(
                    buildString {
                        append("检测到您有未完成任务")
                        if (
                            unfinishedMovieScrapeTaskCount > 0 ||
                            unfinishedFolderBatchTaskCount > 0 ||
                            unfinishedCloudVideoTaskCount > 0
                        ) {
                            append("：")
                            val parts = mutableListOf<String>()
                            if (unfinishedMovieScrapeTaskCount > 0) {
                                parts += "影片刮削 $unfinishedMovieScrapeTaskCount 个"
                            }
                            if (unfinishedFolderBatchTaskCount > 0) {
                                parts += "网盘文件夹任务 $unfinishedFolderBatchTaskCount 个"
                            }
                            if (unfinishedCloudVideoTaskCount > 0) {
                                parts += "单视频持久化任务 $unfinishedCloudVideoTaskCount 个"
                            }
                            append(parts.joinToString("，"))
                        }
                        append("。请挂节点后到刮削任务中手动启动，App 不会自动继续。")
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scrapeTaskPromptDismissed = true
                        navController.navigate(Route.SettingsScrapeTasks) {
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                ) {
                    Text("去刮削任务")
                }
            },
            dismissButton = {
                TextButton(onClick = { scrapeTaskPromptDismissed = true }) {
                    Text("稍后处理")
                }
            }
        )
    }

    startupUpdateInfo?.let { update ->
        AlertDialog(
            onDismissRequest = { startupUpdateInfo = null },
            title = { Text("发现新版本 ${update.versionName}") },
            text = {
                Text(
                    buildString {
                        append("当前版本可以更新到 ${update.versionName}。")
                        update.sizeBytes?.let { size ->
                            append("\nAPK 大小：${formatUpdateSize(size)}")
                        }
                        if (update.notes.isNotEmpty()) {
                            append("\n\n")
                            append(update.notes.take(5).joinToString("\n") { "- $it" })
                        }
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        startupUpdateInfo = null
                        navController.navigate(Route.SettingsUpdate) {
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                ) {
                    Text("去更新")
                }
            },
            dismissButton = {
                TextButton(onClick = { startupUpdateInfo = null }) {
                    Text("稍后")
                }
            }
        )
    }
    }
}

private fun AppContainer.homeImageMode(): HomeImageMode =
    settingsRepository.getHomeImageModeName()
        ?.let { stored -> HomeImageMode.entries.firstOrNull { it.name == stored } }
        ?: HomeImageMode.Poster

@Composable
private fun CloudProviderSwitchBar(
    selected: Int,
    onSelect: (Int) -> Unit
) {
    // 紧凑切换行：背景/状态栏由外层渐变容器统一提供，与页面顶栏连成一体
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(AppRadius.Full)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        listOf("115 网盘", "光鸭云盘").forEachIndexed { index, label ->
            val isSelected = selected == index
            Text(
                text = label,
                color = if (isSelected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f)
                },
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier
                    .weight(1f)
                    .clip(AppRadius.Full)
                    .background(
                        if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
                    )
                    .clickable { onSelect(index) }
                    .padding(vertical = 8.dp)
            )
        }
    }
}

@Composable
private fun AppBottomNavigation(
    currentRoute: String?,
    onNavigate: (String) -> Unit
) {
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp
    ) {
        NavigationBarItem(
            selected = currentRoute == Route.Home,
            onClick = { onNavigate(Route.Home) },
            icon = { Icon(Icons.Rounded.Home, contentDescription = "首页") },
            label = { Text("\u9996\u9875") },
            colors = bottomNavColors()
        )
        NavigationBarItem(
            selected = currentRoute == Route.Movies ||
                currentRoute == Route.MovieLibrary ||
                currentRoute == Route.FilterResult,
            onClick = { onNavigate(Route.Movies) },
            icon = { Icon(Icons.Rounded.Movie, contentDescription = "影片") },
            label = { Text("\u5F71\u7247") },
            colors = bottomNavColors()
        )
        NavigationBarItem(
            selected = currentRoute == Route.Favorites,
            onClick = { onNavigate(Route.Favorites) },
            icon = { Icon(Icons.Rounded.Favorite, contentDescription = "收藏") },
            label = { Text("\u6536\u85CF") },
            colors = bottomNavColors()
        )
        NavigationBarItem(
            selected = currentRoute == Route.Search,
            onClick = { onNavigate(Route.Search) },
            icon = { Icon(Icons.Rounded.Search, contentDescription = "搜索") },
            label = { Text("\u641C\u7D22") },
            colors = bottomNavColors()
        )
        NavigationBarItem(
            selected = currentRoute == Route.CloudBrowser,
            onClick = { onNavigate(Route.CloudBrowser) },
            icon = { Icon(Icons.Rounded.Cloud, contentDescription = "网盘") },
            label = { Text("\u7F51\u76D8") },
            colors = bottomNavColors()
        )
        NavigationBarItem(
            selected = currentRoute == Route.Settings ||
                currentRoute == Route.SettingsUpdate ||
                currentRoute == Route.SettingsScrapeTasks,
            onClick = { onNavigate(Route.Settings) },
            icon = { Icon(Icons.Rounded.Settings, contentDescription = "设置") },
            label = { Text("\u8BBE\u7F6E") },
            colors = bottomNavColors()
        )
    }
}

@Composable
private fun bottomNavColors() = NavigationBarItemDefaults.colors(
    selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
    selectedTextColor = MaterialTheme.colorScheme.onPrimaryContainer,
    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
    unselectedIconColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
    unselectedTextColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f)
)

private object Route {
    const val Home = "home"
    const val Movies = "movies"
    const val MovieLibrary = "movieLibrary"
    const val Favorites = "favorites"
    const val Search = "search"
    const val CloudBrowser = "cloudBrowser"
    const val Settings = "settings"
    const val SettingsUpdate = "settings/update"
    const val SettingsScrapeTasks = "settings/scrapeTasks"
    const val ScrapeLogs = "scrapeLogs"
    const val PlayerLogs = "playerLogs"
    const val GuangyaBrowser = "guangyaBrowser"
    const val Detail = "movieDetail/{movieId}"
    const val FilterResult = "filterResult/{filterType}/{filterValue}"
    const val Player = "player/{videoUri}?title={title}&fileName={fileName}&movieId={movieId}"

    fun detail(movieId: Long) = "movieDetail/$movieId"

    fun filterResult(filterType: String, filterValue: String) =
        "filterResult/${Uri.encode(filterType)}/${Uri.encode(filterValue)}"

    fun player(videoUri: String, title: String, fileName: String, movieId: Long = -1L) =
        "player/${Uri.encode(videoUri)}?title=${Uri.encode(title)}&fileName=${Uri.encode(fileName)}&movieId=$movieId"
}

private fun formatUpdateSize(bytes: Long): String {
    val mb = bytes / 1024.0 / 1024.0
    return if (mb >= 1.0) "%.1f MB".format(mb) else "${bytes / 1024} KB"
}
