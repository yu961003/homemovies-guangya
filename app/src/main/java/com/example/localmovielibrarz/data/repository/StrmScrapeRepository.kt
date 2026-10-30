package com.example.localmovielibrarz.data.repository

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.example.localmovielibrarz.data.local.MovieEntity
import com.example.localmovielibrarz.scraper.ActorAvatarStore
import com.example.localmovielibrarz.scraper.AndroidSourceFetch
import com.example.localmovielibrarz.scraper.AvbaseScraper
import com.example.localmovielibrarz.scraper.SourcePageFetcher
import com.example.localmovielibrarz.scraper.Fc2CmadbScraper
import com.example.localmovielibrarz.scraper.Fc2PpvDbScraper
import com.example.localmovielibrarz.scraper.Fc2Scraper
import com.example.localmovielibrarz.scraper.JavtenScraper
import com.example.localmovielibrarz.scraper.OfficialMakerScraper
import com.example.localmovielibrarz.scraper.OnePondoScraper
import com.example.localmovielibrarz.scraper.browser.WebViewBrowserChannel
import com.example.localmovielibrarz.scraper.CustomJsonScraper
import com.example.localmovielibrarz.scraper.Dmm2Scraper
import com.example.localmovielibrarz.scraper.DmmScraper
import com.example.localmovielibrarz.scraper.JavdbScraper
import com.example.localmovielibrarz.scraper.JavbusScraper
import com.example.localmovielibrarz.scraper.MgstageScraper
import com.example.localmovielibrarz.scraper.MovieNumberExtractor
import com.example.localmovielibrarz.scraper.MovieScraperRegistry
import com.example.localmovielibrarz.scraper.NetworkProbe
import com.example.localmovielibrarz.scraper.NfoWriter
import com.example.localmovielibrarz.scraper.OfficialScraper
import com.example.localmovielibrarz.metadata.MetadataAuthority
import com.example.localmovielibrarz.metadata.MetadataFieldKey
import com.example.localmovielibrarz.scraper.ScrapeSourceChain
import com.example.localmovielibrarz.scraper.ScrapeFailure
import com.example.localmovielibrarz.scraper.ScrapeFailureKind
import com.example.localmovielibrarz.scraper.ChainDecision
import com.example.localmovielibrarz.scraper.SourceAttempt
import com.example.localmovielibrarz.scraper.ScrapeLogStore
import com.example.localmovielibrarz.scraper.ScrapeNumberGuard
import com.example.localmovielibrarz.scraper.ScrapeFailureClassifier
import com.example.localmovielibrarz.scraper.ScrapeSourceHealthTracker
import com.example.localmovielibrarz.scraper.ScrapeLogContext
import com.example.localmovielibrarz.scraper.ScrapeTaskJournal
import com.example.localmovielibrarz.scraper.ScrapeTaskReport
import com.example.localmovielibrarz.scraper.ScrapeEventLevel
import com.example.localmovielibrarz.scraper.ScrapeSource
import com.example.localmovielibrarz.scraper.PollutionSafeDns
import com.example.localmovielibrarz.scraper.ScrapeProxySelector
import com.example.localmovielibrarz.scraper.ScrapeRouteRules
import com.example.localmovielibrarz.scraper.ScrapeSourceThrottle
import com.example.localmovielibrarz.scraper.ScrapeAuthority
import com.example.localmovielibrarz.scraper.R18DevScraper
import com.example.localmovielibrarz.scraper.ScrapedMovieInfo
import com.example.localmovielibrarz.scraper.NumberPrefixRewriteRule
import com.example.localmovielibrarz.scraper.PeachAttempt
import com.example.localmovielibrarz.scraper.PeachFailureReason
import com.example.localmovielibrarz.scraper.PeachReasonClassifier
import com.example.localmovielibrarz.scraper.PeachChainResult
import com.example.localmovielibrarz.scraper.PeachScrapeChain
import com.example.localmovielibrarz.scraper.PeachSourceExecutor
import com.example.localmovielibrarz.scraper.ScrapeBudget
import com.example.localmovielibrarz.scraper.ScrapeByteMeter
import com.example.localmovielibrarz.scraper.ScrapeChainMode
import com.example.localmovielibrarz.scraper.ScrapeContentType
import com.example.localmovielibrarz.scraper.ScrapeMissCache
import com.example.localmovielibrarz.scraper.normalizeMgstageSearchNumber
import com.example.localmovielibrarz.scraper.rewriteNumberPrefix
import com.example.localmovielibrarz.util.MovieVariant
import com.example.localmovielibrarz.util.detectMovieVariant
import com.example.localmovielibrarz.util.displayNumberWithVariant
import com.example.localmovielibrarz.util.extractMovieNumberInfo
import com.example.localmovielibrarz.util.movieKeyFromText
import com.example.localmovielibrarz.util.playbackSourceSuffix
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import kotlin.system.measureTimeMillis
import com.example.localmovielibrarz.util.HttpClients

data class ClearScrapeResult(
    val number: String,
    val strmUri: String
)

class StrmScrapeRepository(
    private val context: Context,
    private val settingsRepository: AppSettingsRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val logStore: ScrapeLogStore = ScrapeLogStore(context),
    private val taskJournal: ScrapeTaskJournal = ScrapeTaskJournal(context),
    private val storedDocumentLocator: StoredDocumentLocator = StoredDocumentLocator(context),
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        // 超时对齐 peach 的 REQUEST_TIMEOUT = 30s（httpx 的 timeout 同时作用在 connect/read/write）。
        // OkHttp 默认是 10s：跨境线路经代理转发时，一次 TCP + TLS 就要几秒，站点首字节慢一点
        // 就变成 SocketTimeoutException，而它会被归类成 Network——用户看到的就是「网络错误」。
        .proxySelector(ScrapeProxySelector(settingsRepository))
        // 国内 DNS 会为部分站点投出 2001::/32（Teredo）这种不可路由地址，连接只能卡到
        // connectTimeout 才失败。IPv4 优先 + 剔除 Teredo 是这一层唯一能做的补救。
        .dns(PollutionSafeDns())
        .connectTimeout(SCRAPE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(SCRAPE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(SCRAPE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        // 整体上限：connect/read/write 三个 30 秒可以叠加成 90 秒，而调用方按「一个来源
        // 最多花多久」做预算（桃链 90 秒墙钟）。没有这一行，单个源就能吃掉整条链的预算。
        .callTimeout(SCRAPE_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .connectionPool(HttpClients.sharedConnectionPool)
        .dispatcher(HttpClients.sharedDispatcher)
        .build(),
    private val networkProbe: NetworkProbe = NetworkProbe(client = httpClient, ioDispatcher = ioDispatcher),
    private val remoteScrapeConfigRepository: RemoteScrapeConfigRepository = RemoteScrapeConfigRepository(
        settingsRepository = settingsRepository
    ),
    private val dmmScraper: DmmScraper = DmmScraper(client = httpClient, ioDispatcher = ioDispatcher),
    private val dmm2Scraper: Dmm2Scraper = Dmm2Scraper(
        client = httpClient,
        ioDispatcher = ioDispatcher,
        logger = logStore::append,
        // The repository retries the complete source request using the user setting.
        // Keep the scraper's transport retry at one attempt to avoid multiplying retries.
        graphqlRetryCountProvider = { 1 }
    ),
    private val officialScraper: OfficialScraper = OfficialScraper(client = httpClient, ioDispatcher = ioDispatcher),
    private val mgstageScraper: MgstageScraper = MgstageScraper(client = httpClient, ioDispatcher = ioDispatcher),
    private val javbusScraper: JavbusScraper = JavbusScraper(client = httpClient, ioDispatcher = ioDispatcher),
    private val javdbScraper: JavdbScraper = JavdbScraper(client = httpClient, ioDispatcher = ioDispatcher),
    private val r18DevScraper: R18DevScraper = R18DevScraper(client = httpClient, ioDispatcher = ioDispatcher),
    // peach 主链的三家新源。放在这里而不是等到用到时才 new：它们与既有八家共用同一个
    // httpClient（同一套代理与超时）和 ioDispatcher，另建一份只会多出一份连接池与线程池。
    private val avbaseScraper: AvbaseScraper = AvbaseScraper(client = httpClient, ioDispatcher = ioDispatcher),
    private val onePondoScraper: OnePondoScraper = OnePondoScraper(client = httpClient, ioDispatcher = ioDispatcher),
    private val fc2Scraper: Fc2Scraper = Fc2Scraper(client = httpClient, ioDispatcher = ioDispatcher),
    private val customJsonScraper: CustomJsonScraper = CustomJsonScraper(settingsRepository = settingsRepository, client = httpClient, ioDispatcher = ioDispatcher),
    /**
     * 按来源的最小请求间隔（peach 的 `SOURCE_INTERVALS`）。可为 null 关闭限速：
     * 单测与被用户点名的单次刮削都不需要它。
     */
    private val sourceThrottle: ScrapeSourceThrottle? = ScrapeSourceThrottle(),
    /**
     * 取页层：OkHttp 主通道 + WebView 回落。
     *
     * 为什么它必须**先于**依赖它的 scraper 声明：Kotlin 的默认参数按声明顺序求值，
     * 写在 [fc2CmadbScraper] 之后就拿不到它了。
     *
     * 浏览器通道的可用性不在这里判：`enabled = { true }` 只表示「策略上允许」，
     * 真正能不能用由 [WebViewBrowserChannel.state] 按「有没有前台 Activity / WebView 是否
     * 可用」在调用那一刻决定。后台批刮时它会自动退化成 [BrowserChannelState.NoForeground]，
     * 于是 HTTP 被拦就如实报失败，而不是去起一个没人看得到的 WebView。
     */
    private val sourcePageFetcher: SourcePageFetcher = AndroidSourceFetch(
        httpClient = httpClient,
        browser = WebViewBrowserChannel(appContext = context.applicationContext),
        throttle = sourceThrottle,
        ioDispatcher = ioDispatcher,
        log = logStore::append
    ),
    /**
     * FC2 系社区索引三家（peach `sources/fc2cmadb.py` / `fc2ppvdb.py` / `javten.py`）。
     *
     * 与既有八家的唯一结构差别：它们不自己 `newCall`，而是把取页交给 [sourcePageFetcher]，
     * 于是「HTTP 被拦 → 上浏览器」这段逻辑只存在一份。
     */
    private val fc2CmadbScraper: Fc2CmadbScraper = Fc2CmadbScraper(
        fetcher = sourcePageFetcher,
        client = httpClient,
        ioDispatcher = ioDispatcher
    ),
    private val fc2PpvDbScraper: Fc2PpvDbScraper = Fc2PpvDbScraper(
        fetcher = sourcePageFetcher,
        client = httpClient,
        ioDispatcher = ioDispatcher
    ),
    private val javtenScraper: JavtenScraper = JavtenScraper(
        fetcher = sourcePageFetcher,
        client = httpClient,
        ioDispatcher = ioDispatcher
    ),
    /**
     * 片商官网三家（peach 的 `amane` 桥在进程内的等价实现）。
     *
     * peach 那边是一个 Python 子进程按前缀选中官网再跑该站手写解析器；Android 没有
     * Python，所以拆成「声明式目录 + 通用抽取器 + 取页层」三段，这里只是把这三段
     * 各按一个来源记一次账。三家共用同一条编排，`source` 决定这次算谁的。
     */
    private val falenoScraper: OfficialMakerScraper = OfficialMakerScraper(
        fetcher = sourcePageFetcher,
        source = ScrapeSource.Faleno,
        client = httpClient,
        log = logStore::append,
        ioDispatcher = ioDispatcher
    ),
    private val dahliaScraper: OfficialMakerScraper = OfficialMakerScraper(
        fetcher = sourcePageFetcher,
        source = ScrapeSource.Dahlia,
        client = httpClient,
        log = logStore::append,
        ioDispatcher = ioDispatcher
    ),
    private val makerSiteScraper: OfficialMakerScraper = OfficialMakerScraper(
        fetcher = sourcePageFetcher,
        source = ScrapeSource.MakerSite,
        client = httpClient,
        log = logStore::append,
        ioDispatcher = ioDispatcher
    ),
    private val scraperRegistry: MovieScraperRegistry = MovieScraperRegistry(
        listOf(
            dmmScraper, dmm2Scraper, officialScraper, mgstageScraper,
            javbusScraper, javdbScraper, r18DevScraper,
            avbaseScraper, onePondoScraper, fc2Scraper,
            fc2CmadbScraper, fc2PpvDbScraper, javtenScraper,
            falenoScraper, dahliaScraper, makerSiteScraper,
            customJsonScraper
        )
    ),
    private val imageDownloadService: ImageDownloadService = ImageDownloadService(
        httpClient = httpClient,
        retryCountProvider = settingsRepository::getImageDownloadRetryCount,
        logger = logStore::append,
        ioDispatcher = ioDispatcher
    ),
    private val sourceHealthTracker: ScrapeSourceHealthTracker = ScrapeSourceHealthTracker(),
    /**
     * 来源健康度的持久层。可为 null：单测与无 DB 的场景不需要它，
     * 且 tracker 本身仍是运行期唯一判断依据（内存快路径不能被 IO 阻塞）。
     */
    private val sourceHealthRepository: SourceHealthRepository? = null,
    /**
     * 元数据归属账本。可为 null（单测/无 DB 场景），记账失败绝不回滚刮削结果本身。
     */
    private val metadataLedger: MovieMetadataLedgerRepository? = null
) {
    private val actorAvatarStore = ActorAvatarStore(context)
    private val backgroundScope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private var actorAvatarJob: Job? = null
    private val scrapeAdmissionMutex = Mutex()
    /**
     * 桃链的「站里没有这个番号」负缓存（peach 的 miss cache）。
     *
     * 进程内共享而不是每次刮削新建：批量刮削里同一部影片常被重试两三次，
     * 每次重试都把 8 家站重问一遍，问的都是同一个必然搜不到的号。
     * 不落库：这是一份「省请求」的缓存，跨进程复用会让站点侧的真实变化（补档）看不见。
     */
    private val scrapeMissCache = ScrapeMissCache()
    private val _scrapeQueueState = MutableStateFlow(ScrapeQueueState())
    private val _actorAvatarUpdateState = MutableStateFlow(ActorAvatarUpdateState())
    val scrapeQueueState: StateFlow<ScrapeQueueState> = _scrapeQueueState
    val actorAvatarUpdateState: StateFlow<ActorAvatarUpdateState> = _actorAvatarUpdateState

    fun logDates(): List<String> = logStore.dates()

    fun readLogs(date: String = logDates().firstOrNull().orEmpty()): String = logStore.read(date)

    fun logUpdates(): StateFlow<Long> = logStore.updates

    fun clearLogs(date: String? = null) {
        if (date == null) logStore.clearAll() else logStore.clear(date)
    }

    fun removeLegacyLogs(): Int = logStore.removeLegacyLines()

    fun appendLog(message: String) {
        logStore.append(message)
    }

    fun latestScrapeReport(movieId: Long): ScrapeTaskReport? = taskJournal.latestForMovie(movieId)
    val scrapeTaskReports: StateFlow<List<ScrapeTaskReport>> get() = taskJournal.reports

    private suspend fun <T> runQueuedScrapeTask(
        label: String,
        serialMutex: Mutex? = null,
        block: suspend () -> T
    ): T = withContext(ioDispatcher) {
        _scrapeQueueState.update { state ->
            state.copy(waitingCount = state.waitingCount + 1)
        }
        if (serialMutex != null) {
            return@withContext serialMutex.withLock {
                runAdmittedScrapeTask(label, block)
            }
        }
        runAdmittedScrapeTask(label, block)
    }

    private suspend fun <T> runAdmittedScrapeTask(label: String, block: suspend () -> T): T {
        var admitted = false
        var waitingLogged = false
        try {
            while (!admitted) {
                val limit = settingsRepository.getScrapeConcurrencyLimit()
                scrapeAdmissionMutex.withLock {
                    val state = _scrapeQueueState.value
                    if (state.runningCount < limit) {
                        val nextRunningCount = state.runningCount + 1
                        _scrapeQueueState.value = state.copy(
                            isRunning = true,
                            runningLabel = label,
                            runningCount = nextRunningCount,
                            waitingCount = (state.waitingCount - 1).coerceAtLeast(0),
                            startedAtMillis = state.startedAtMillis.takeIf { it > 0L } ?: System.currentTimeMillis()
                        )
                        admitted = true
                    }
                }
                if (!admitted) {
                    if (!waitingLogged) {
                        logStore.append("刮削任务等待队列：$label，当前并发=${_scrapeQueueState.value.runningCount}/$limit")
                        waitingLogged = true
                    }
                    delay(SCRAPE_QUEUE_POLL_INTERVAL_MS)
                }
            }
            try {
                return block()
            } finally {
                scrapeAdmissionMutex.withLock {
                    _scrapeQueueState.update { state ->
                        val nextRunningCount = (state.runningCount - 1).coerceAtLeast(0)
                        state.copy(
                            isRunning = nextRunningCount > 0,
                            runningLabel = if (nextRunningCount > 0) state.runningLabel else null,
                            runningCount = nextRunningCount,
                            startedAtMillis = if (nextRunningCount > 0) state.startedAtMillis else 0L
                        )
                    }
                }
            }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            if (!admitted) {
                withContext(NonCancellable) {
                    _scrapeQueueState.update { state ->
                        state.copy(waitingCount = (state.waitingCount - 1).coerceAtLeast(0))
                    }
                }
            }
            throw error
        }
    }

    private fun appendMovieDivider(title: String, number: String, fileName: String, source: ScrapeSource? = null) {
        val sourceText = source?.let { "，来源=${it.label}" }.orEmpty()
        logStore.append("【番号分隔】$number")
        logStore.append("[影片刮削] 状态=开始，类型=$title，番号=$number，文件=$fileName$sourceText")
    }

    fun getDefaultScrapeSource(): ScrapeSource = settingsRepository.getDefaultScrapeSource()

    suspend fun testCustomJsonScrape(number: String): ScrapedMovieInfo =
        customJsonScraper.scrape(number)

    suspend fun previewCustomJsonPathCandidates(number: String) =
        customJsonScraper.previewPathCandidates(number)

    fun loadCachedNumberRecognitionRules(): Set<String> =
        remoteScrapeConfigRepository.loadCachedNumberRecognitionRules()

    suspend fun canReachGoogle(): Boolean = withContext(ioDispatcher) {
        var reachable = false
        logStore.append("Start Google connectivity check, timeout 5s")
        val elapsedMs = measureTimeMillis {
            reachable = networkProbe.canReachGoogle()
        }
        logStore.append("Google connectivity ${if (reachable) "passed" else "failed"}, elapsed=${elapsedMs}ms")
        reachable
    }

    suspend fun scrapeMovieWithOutput(movie: MovieEntity, source: ScrapeSource, forceDistinct: Boolean = false): ScrapedMovieWriteResult = runQueuedScrapeTask(
        label = "scrape:${movie.videoName}:${source.label}",
        serialMutex = source.serialScrapeMutex()
    ) {
        val target = findTargetForMovie(movie)
        val number = extractMovieNumberWithRules(target.file.name.orEmpty())
            ?: error("无法从文件名提取番号：${target.file.name}")
        appendMovieDivider("影片刮削", number, target.file.name.orEmpty(), source)
        val taskId = taskJournal.start(movie.id, number, target.file.name.orEmpty(), "刮削", source.label)
        logStore.append("[刮削任务:$taskId] 开始：$number，来源=${source.label}")
        runCatching {
            scrapeTargetWithOutput(target, source, forceDistinct, taskId, dividerAlreadyLogged = true)
        }.onFailure { error ->
            if (error !is CancellationException) {
                val message = error.message ?: error::class.java.simpleName
                taskJournal.finish(taskId, success = false, message = message)
                logStore.append("[刮削任务:$taskId] 失败：$message")
            }
        }.getOrThrow()
    }

    suspend fun scrapeStrmUriWithOutput(
        libraryRootUri: String,
        strmUri: String,
        source: ScrapeSource,
        forceDistinct: Boolean = false
    ): ScrapedMovieWriteResult = runQueuedScrapeTask(
        label = "scrape-uri:${Uri.parse(strmUri).lastPathSegment.orEmpty()}:${source.label}",
        serialMutex = source.serialScrapeMutex()
    ) {
        val target = requireStoredDocument(
            movieId = null,
            fileName = Uri.parse(strmUri).lastPathSegment.orEmpty(),
            databaseUri = strmUri,
            operation = "新增 STRM 刮削定位"
        ) {
            storedDocumentLocator.find(libraryRootUri, strmUri)?.toStrmTarget()
        }
        scrapeTargetWithOutput(target, source, forceDistinct, taskId = null)
    }

    private suspend fun scrapeTargetWithOutput(
        target: StrmTarget,
        source: ScrapeSource,
        forceDistinct: Boolean,
        taskId: String?,
        dividerAlreadyLogged: Boolean = false
    ): ScrapedMovieWriteResult {
        val number = extractMovieNumberWithRules(target.file.name.orEmpty())
            ?: error("无法从文件名提取番号：${target.file.name}")

        if (!dividerAlreadyLogged) {
            appendMovieDivider("影片刮削", number, target.file.name.orEmpty(), source)
        }
        return ScrapeLogContext.withMovieNumber(number) {
            taskId?.let { taskJournal.record(it, "识别", "已识别番号 $number；来源 ${source.label}") }
            val info = scrapeWithFallback(source, number, target.file.name.orEmpty())
            logStore.append("[影片刮削] 状态=元数据获取成功，番号=$number，标题=${info.title.ifBlank { number }}")
            taskId?.let { taskJournal.record(it, "请求", "元数据获取成功：${info.title.ifBlank { number }}", ScrapeEventLevel.Success) }
            val strmUri = writeOrganizedScrapeFiles(target, info, number, forceDistinct)
            downloadActorAvatars(info)
            logStore.append("[影片刮削] 状态=文件整理完成，番号=$number；说明=等待影片数据库同步")
            taskId?.let { taskJournal.record(it, "写入", "NFO 与图片已写入；等待数据库同步") }
            ScrapedMovieWriteResult(info = info, strmUri = strmUri, taskId = taskId)
        }
    }

    fun finishMovieScrapeTask(taskId: String?, success: Boolean, message: String) {
        taskId ?: return
        taskJournal.finish(taskId, success, message)
        logStore.append("[刮削任务:$taskId] ${if (success) "完成" else "失败"}：$message")
    }

    suspend fun rescrapeMovie(movie: MovieEntity, source: ScrapeSource): ScrapedMovieInfo =
        rescrapeMovieWithOutput(movie, source).info

    /**
     * 只补元数据，**不碰存储上的任何文件**（元数据补全任务用）。
     *
     * 为什么不直接复用 [rescrapeMovieWithOutput]：那条路径会 `rewriteScrapeFilesInPlace`
     * 重写已整理的 NFO/海报，并 `downloadActorAvatars` 下载头像。对「补全」任务来说这是两处越界：
     * 用户要的是「把空着的演员/厂牌补上」，不是「把整理好的文件重新写一遍」；
     * 而且后台批量补全时对每个影片都重写文件，会让网盘/SD 卡上的文件时间戳成片变化，
     * 既慢又可能触发写权限弹窗。
     *
     * 番号从数据库字段里取（videoName/title），**不去遍历存储**：批量补全要能在
     * 存储不可读（权限被收回、网盘离线）时也尽量跑通，能补多少补多少。
     *
     * @return 刮削结果；调用方负责按字段合并（见 [MovieMetadataRefreshRunner]）。
     */
    suspend fun refreshMetadataOnly(movie: MovieEntity, source: ScrapeSource): ScrapedMovieInfo =
        runQueuedScrapeTask(
            label = "metadataRefresh:${movie.videoName}:${source.label}",
            serialMutex = source.serialScrapeMutex()
        ) {
            val number = extractMovieNumberWithRules(movie.videoName, movie.title)
                ?: error("无法从片名提取番号：${movie.videoName}")
            ScrapeLogContext.withMovieNumber(number) {
                val info = scrapeWithFallback(source, number, movie.videoName)
                logStore.append("[元数据补全] 状态=元数据获取成功，番号=$number，标题=${info.title.ifBlank { number }}")
                recordScrapedProvenance(movie.id, info)
                info
            }
        }

    suspend fun rescrapeMovieWithOutput(movie: MovieEntity, source: ScrapeSource): RescrapedMovieWriteResult = runQueuedScrapeTask(
        label = "rescrape:${movie.videoName}:${source.label}",
        serialMutex = source.serialScrapeMutex()
    ) {
        val target = findTargetForMovie(movie)
        val number = extractMovieNumberWithRules(target.file.name.orEmpty(), movie.title)
            ?: error("无法从文件名提取番号：${target.file.name}")
        appendMovieDivider("影片重新刮削", number, target.file.name.orEmpty(), source)
        val taskId = taskJournal.start(movie.id, number, target.file.name.orEmpty(), "重新刮削", source.label)
        logStore.append("[刮削任务:$taskId] 开始重新刮削：$number，来源=${source.label}")
        runCatching {
            ScrapeLogContext.withMovieNumber(number) {
                taskJournal.record(taskId, "识别", "已识别番号 $number；来源 ${source.label}")
                val info = scrapeWithFallback(source, number, target.file.name.orEmpty())
                logStore.append("[影片重新刮削] 状态=元数据获取成功，番号=$number，标题=${info.title.ifBlank { number }}")
                taskJournal.record(taskId, "请求", "元数据获取成功：${info.title.ifBlank { number }}", ScrapeEventLevel.Success)
                recordScrapedProvenance(movie.id, info)
                rewriteScrapeFilesInPlace(target, info)
                downloadActorAvatars(info)
                logStore.append("[影片重新刮削] 状态=文件写入完成，番号=$number；说明=等待影片数据库刷新")
                taskJournal.record(taskId, "写入", "NFO 与图片已更新；等待数据库刷新")
                RescrapedMovieWriteResult(info, taskId)
            }
        }.onFailure { error ->
            if (error !is CancellationException) {
                val message = error.message ?: error::class.java.simpleName
                taskJournal.finish(taskId, success = false, message = message)
                logStore.append("[刮削任务:$taskId] 失败：$message")
            }
        }.getOrThrow()
    }

    /**
     * 刮削入口：**桃链优先，legacy 只做最终兜底**。
     *
     * 调度规则（用户点名来源的场景必须保持原样，否则等于替他改主意）：
     * - 用户点名某个来源：直接走 legacy。他点的就是那一家，桃链的分层重排对单源无意义。
     * - 自动链（`requestedSource == Priority`）：
     *   - 设置里选了 [ScrapeChainMode.LegacyOnly] → 直接走 legacy，桃链一行都不执行。
     *   - 否则先跑 [scrapePeachChain]：
     *     - 拿到元数据 → 直接返回。
     *     - 桃链给不出可用结果（[PeachChainResult.NoResult] / [PeachChainResult.Unavailable] /
     *       自身抛异常）→ 降级到 legacy 链，这就是「最终兜底」的触发点。
     *
     * 为什么 NoResult 也降级，而不像最初那样直接失败：用户要的语义是「peach 为主线，
     * 现有链路仅作最终兜底」——桃链一行元数据都没拿到，正是兜底该出场的时机。
     * 桃链这一趟的失败已经在 `sourceHealthTracker` 里记过账，legacy 的冷却判断会接手，
     * 冷却中的站不会被重打一遍；真正可能被重问的只有「明确答复没有」的那批，
     * 它们本来也是要问的（legacy 不知道桃链的负缓存，但重复一次 404 的代价远小于刮不出东西）。
     */
    private suspend fun scrapeWithFallback(
        requestedSource: ScrapeSource,
        number: String,
        fileName: String
    ): ScrapedMovieInfo {
        if (requestedSource != ScrapeSource.Priority) {
            return scrapeLegacyChain(requestedSource, number, fileName)
        }
        val chainMode = settingsRepository.getScrapeChainMode()
        if (chainMode == ScrapeChainMode.LegacyOnly) {
            logStore.append("刮削链模式为「${chainMode.label}」，跳过桃链直接走原有刮削链")
            return scrapeLegacyChain(requestedSource, number, fileName)
        }
        // 桃链真的发出过请求的来源。降级到 legacy 链时要把这批剔掉：两条链的
        // routeFor/routeForPeach 会产出几乎相同的来源顺序，不剔的话同一家在一次刮削里
        // 会被打两轮（每轮还有 3 次重试），单源单号最多 6 个请求。用户看到的
        // 「失败了的又重复获取」就是这里。
        val visitedSources = mutableSetOf<ScrapeSource>()
        val peachOutcome = runCatching { scrapePeachChain(number, fileName, visitedSources) }
        return when (val result = peachOutcome.getOrNull()) {
            is PeachChainResult.Success -> result.info.copy(
                number = rewriteNumberPrefix(number, settingsRepository.getNumberPrefixRewriteRules())
            )
            is PeachChainResult.NoResult -> {
                logStore.append("桃链未拿到可用元数据（${result.reason}），降级到原有刮削链兜底")
                scrapeLegacyChain(requestedSource, number, fileName, visitedSources)
            }
            is PeachChainResult.Unavailable -> {
                logStore.append("桃链不可用（${result.reason}），降级到原有刮削链兜底")
                scrapeLegacyChain(requestedSource, number, fileName, visitedSources)
            }
            null -> {
                // runCatching 会连 CancellationException 一起吞掉，必须原样抛回去：
                // 否则「用户取消刮削」会被当成「桃链出错」而多跑一遍 legacy 链。
                val thrown = peachOutcome.exceptionOrNull()
                if (thrown is CancellationException) throw thrown
                logStore.append(
                    "桃链异常（${thrown?.message ?: thrown?.let { it::class.java.simpleName } ?: "未知"}），降级到原有刮削链兜底"
                )
                scrapeLegacyChain(requestedSource, number, fileName, visitedSources)
            }
        }
    }

    /**
     * 跑一次桃链。
     *
     * 路由用 [ScrapeRouteRules.routeForPeach]（与 legacy 的 `routeFor` 有两处有意差别：
     * 裁空时不回退完整配置、形状认不出时社区档优先；见其 KDoc）。
     * 预算与负缓存的作用域**不一样**，别按同一套理解：
     * - 预算是每次调用新建——批量刮削是一部影片一次调用，跨调用共享会变成
     *   「前 12 部把预算用完，后面全部不走网络」。
     * - 负缓存是类字段（[scrapeMissCache]）共享的——它的全部价值就是「同一部片在一次批量里
     *   被重试时别再问一遍」，每次新建等于把它关掉了。
     */
    private suspend fun scrapePeachChain(
        number: String,
        fileName: String,
        visitedSources: MutableSet<ScrapeSource> = mutableSetOf()
    ): PeachChainResult {
        val routeType = ScrapeRouteRules.classify(number, fileName)
        val plan = ScrapeRouteRules.routeForPeach(routeType, scrapeSourcesFor(ScrapeSource.Priority, number))
        if (routeType != ScrapeContentType.Unknown) {
            logStore.append(
                "桃链：番号形态识别为 ${routeType.label}，来源顺序 " +
                    plan.sources.joinToString("→") { "${it.label}(${ScrapeAuthority.tierOf(it).label})" }
            )
        }
        if (plan.skipped.isNotEmpty()) {
            logStore.append(
                "桃链：按「${routeType.label}」跳过 ${plan.skipped.size} 个来源：" +
                    plan.skipped.joinToString("、") { it.label }
            )
        }
        val chain = PeachScrapeChain(
            executor = RepositoryPeachSourceExecutor(visitedSources = visitedSources),
            missCache = scrapeMissCache,
            budget = ScrapeBudget()
        )
        return chain.run(number = number, plan = plan, wantedScalars = true)
    }

    /**
     * 桃链执行器：把桃链的「单家取数」接到本类既有的节流 / 重试 / 校验 / 记账上。
     *
     * 写成内部类而不是独立文件，因为它需要 [scrapeSource]、[sourceThrottle]、
     * [sourceHealthTracker] 这些私有成员；如果把这些搬出去，legacy 链就得跟着改，
     * 而 legacy 链的历史行为正是这次要保住的东西。
     */
    private inner class RepositoryPeachSourceExecutor(
        private val visitedSources: MutableSet<ScrapeSource> = mutableSetOf()
    ) : PeachSourceExecutor {

        override fun cooldownRemaining(source: ScrapeSource): Long =
            sourceHealthTracker.cooldownRemaining(source)

        override fun lastFailure(source: ScrapeSource): ScrapeFailure? =
            sourceHealthTracker.lastFailure(source)

        override suspend fun fetch(source: ScrapeSource, number: String): PeachAttempt {
            // DMM2 的番号前缀黑名单：配置性跳过，不发请求、不计预算。
            dmm2SkipMessage(source, number)?.let { message ->
                logStore.append("桃链跳过 ${source.label}：$message")
                return PeachAttempt.Failure(
                    failure = ScrapeFailure(ScrapeFailureKind.Config, message),
                    requestAttempted = false
                )
            }
            // 到这一步才是「真的会发请求」，配置性跳过（上面那条 return）不算。
            visitedSources += source
            val attempt = fetchSingleSource(
                source = source,
                number = number,
                prefixRewriteRules = settingsRepository.getNumberPrefixRewriteRules(),
                isAutoChain = true
            )
            val info = attempt.info
            if (info != null) {
                return PeachAttempt.Success(info = info, responseBytes = attempt.responseBytes)
            }
            val failure = attempt.failure ?: ScrapeFailure(ScrapeFailureKind.Unknown, "未取得数据")
            return PeachAttempt.Failure(
                failure = failure,
                numberMatched = attempt.numberMatched,
                responseBytes = attempt.responseBytes,
                reason = attempt.peachReason
            )
        }

        override fun log(message: String) {
            logStore.append(message)
        }
    }

    private suspend fun scrapeLegacyChain(
        requestedSource: ScrapeSource,
        number: String,
        fileName: String,
        skipSources: Set<ScrapeSource> = emptySet()
    ): ScrapedMovieInfo {
        val prefixRewriteRules = settingsRepository.getNumberPrefixRewriteRules()
        val outputNumber = rewriteNumberPrefix(number, prefixRewriteRules)
        // 自动回退链才看冷却；用户点名某个来源时照打，失败也照常记账
        // （否则「我明明选了 Javbus 却什么也没发生」比失败更让人困惑）。
        val isAutoChain = requestedSource == ScrapeSource.Priority
        val cooling: (ScrapeSource) -> Long = { source ->
            if (isAutoChain) sourceHealthTracker.cooldownRemaining(source) else 0L
        }
        // 按番号形态重排（peach 的内容类型路由，按鸭影只有 8 个来源的实际情况改写）：
        // FC2/业余/无码在有码站上大概率查不到，固定链会让它们先吃一次「搜不到」——
        // 用户看到的失败其实只是「问错了站」。只在自动链里生效：用户点名来源时不改他的顺序。
        val routeType = if (isAutoChain) {
            ScrapeRouteRules.classify(number, fileName)
        } else {
            ScrapeContentType.Unknown
        }
        // 自动链走「按类型裁剪 + 按取信层级重排」；用户点名某个来源时两套都不走，
        // 直接把那一家交下去——他点的就是那一家，替他排序等于改他的意思。
        var plan = if (isAutoChain) {
            ScrapeRouteRules.routeFor(routeType, scrapeSourcesFor(requestedSource, number))
        } else {
            ScrapeRouteRules.RoutePlan(
                sources = scrapeSourcesFor(requestedSource, number),
                skipped = emptyList(),
                fallbackReason = null
            )
        }
        if (isAutoChain && routeType != ScrapeContentType.Unknown) {
            logStore.append(
                "番号形态识别为 ${routeType.label}，来源按该类型裁剪并分层：" +
                    plan.sources.joinToString("→") { "${it.label}(${ScrapeAuthority.tierOf(it).label})" }
            )
        }
        // 桃链已经问过、并且明确没问出结果的来源，这里不再重复请求。
        // 只剔「桃链真的发过请求」的：它已经按单源重试打过一轮，重复只会拉长耗时、
        // 并且把同一个失败再记一次账（冷却/负缓存都会更乱）。
        if (skipSources.isNotEmpty()) {
            val before = plan.sources
            val kept = before.filterNot { it in skipSources }
            if (kept.isNotEmpty() && kept.size != before.size) {
                logStore.append(
                    "已跳过桃链问过的 ${before.size - kept.size} 个来源，不再重复请求：" +
                        before.filter { it in skipSources }.joinToString("、") { it.label }
                )
                plan = plan.copy(sources = kept)
            } else if (kept.isEmpty()) {
                logStore.append("桃链已问遍全部 ${before.size} 个来源，legacy 链没有可重复尝试的站，按原顺序再兜一次")
            }
        }
        if (plan.skipped.isNotEmpty()) {
            logStore.append(
                "按「${routeType.label}」跳过 ${plan.skipped.size} 个来源：" +
                    plan.skipped.joinToString("、") { it.label } +
                    "（这些站按该类型的番号会返回同号别的作品或必然搜不到）"
            )
        }
        plan.fallbackReason?.let { logStore.append(it) }
        val sources = ScrapeSourceChain.order(
            configured = plan.sources,
            pinned = null,
            coolingRemaining = cooling
        )
        val failures = mutableListOf<Pair<ScrapeSource, ScrapeFailure>>()
        // 底稿按「取信层级」拼装（见 ScrapeAuthority.mergeByAuthority）：同一字段几份非空值时
        // 听层级高的那家，而不是听先打到的那家——用户把 Javbus 配在 TheJavDB 前面时，
        // 先到优先会让一份来自别的片的值顶掉更准的值。
        var draft: Pair<ScrapeSource, ScrapedMovieInfo>? = null
        var abortReason: String? = null

        for ((index, source) in sources.withIndex()) {
            dmm2SkipMessage(source, number)?.let { message ->
                failures += source to ScrapeFailure(ScrapeFailureKind.Config, message)
                logStore.append("Priority scrape skipped ${source.label}: $message")
                continue
            }
            // 冷却中的来源在 order() 里已被排到末尾，这里必须显式跳过：
            // 排到末尾只保证「最后才打」，不保证「不打」。
            val coolingRemaining = cooling(source)
            if (coolingRemaining > 0L) {
                val minutes = (coolingRemaining / 60_000L).coerceAtLeast(1L)
                val kind = sourceHealthTracker.lastFailure(source)?.kind ?: ScrapeFailureKind.Unknown
                failures += source to ScrapeFailure(kind, "正在冷却，剩余 $minutes 分钟")
                logStore.append("优先级刮削跳过 ${source.label}：来源冷却中，剩余 $minutes 分钟（${kind.name}）")
                continue
            }
            if (isAutoChain) {
                logStore.append("优先级刮削尝试 ${index + 1}/${sources.size}：${source.label}，file=$fileName，number=$number")
            }
            val attempt = fetchSingleSource(
                source = source,
                number = number,
                prefixRewriteRules = prefixRewriteRules,
                isAutoChain = isAutoChain
            )

            attempt.failure?.let { failures += source to it }
            draft = attempt.info?.let { info ->
                ScrapeAuthority.mergeByAuthority(draft, source to info)
            } ?: draft

            // 结算判定（peach 的 settles）：必填标量齐了就收工，不再打后面的来源。
            // 列表字段（类型/标签/封面）刻意不参与短路——它们在多数源上本来就缺，
            // 要求它们等于逼着刮削把每一家都打一遍，多打一家就多一次风控机会。
            val settled = draft?.second
            if (isAutoChain && settled != null && ScrapeRouteRules.settles(settled, sources.drop(index + 1))) {
                val missing = ScrapeRouteRules.missingRequiredFields(settled)
                val reason = if (missing.isEmpty()) "必填字段已齐" else "必填字段仍未齐（缺 ${missing.joinToString("、")}），但后面已没有能补的官方来源"
                logStore.append("优先级刮削结算：$reason，不再追问后续来源")
                abortReason = null
                break
            }

            val remaining = sources.drop(index + 1)
            when (val decision = ScrapeSourceChain.decide(attempt, remaining, cooling)) {
                is ChainDecision.Succeeded -> {
                    // 已经打完所有来源（或结果够完整）：收工。
                    abortReason = null
                    break
                }
                is ChainDecision.ContinueWith -> {
                    if (isAutoChain) logStore.append("优先级刮削继续：${decision.reason}")
                }
                is ChainDecision.Abort -> {
                    abortReason = decision.reason
                    if (isAutoChain) logStore.append("优先级刮削中止：${decision.reason}")
                    break
                }
            }
        }

        val accepted = draft
        if (accepted != null) {
            return accepted.second.copy(
                number = outputNumber,
                source = accepted.second.source.ifBlank { accepted.first.label }
            )
        }
        if (isAutoChain) {
            error("优先级刮削全部失败：${ScrapeSourceChain.aggregateFailureReason(failures)}")
        }
        error(
            abortReason
                ?: failures.lastOrNull()?.second?.message
                ?: "${requestedSource.label} 刮削失败"
        )
    }

    /**
     * 对**一家**来源做一次完整尝试：节流 → 传输层重试 → 番号一致性校验 → 健康度记账。
     *
     * 抽出来是为了让 legacy 链与桃链共用同一段单源逻辑。之前这段代码内联在 legacy 循环里，
     * 桃链若要自己实现一遍，两条链就会在「重试几次 / 认不认答错片的回答 / 冷却怎么记」
     * 这三个最容易被风控抓到的地方慢慢走散——而这类偏差只会在线上表现为
     * 「换了默认链以后某家站开始报 403」，很难回溯到具体某行。
     *
     * 冷却检查**留在调用方**：legacy 用 `order()` 把冷却源排到末尾后仍显式跳过，
     * 桃链在建 plan 后自行判冷却，两边的跳过日志措辞也不同，合进来反而要加参数区分。
     */
    private suspend fun fetchSingleSource(
        source: ScrapeSource,
        number: String,
        prefixRewriteRules: List<NumberPrefixRewriteRule>,
        isAutoChain: Boolean
    ): SourceAttempt {
        val sourceNumber = rewriteNumberPrefix(number, prefixRewriteRules, source)
        if (!sourceNumber.equals(number, ignoreCase = true)) {
            logStore.append("数字前缀规则命中：${source.label} 使用 $sourceNumber 查询")
        }
        // 按来源的最小请求间隔（peach 的 SOURCE_INTERVALS）。批量刮削会在一小时内
        // 把同一家站敲上几百下，间隔不够时对方返 403，冷却反而把这家锁半小时——
        // 等两秒比锁半小时划算。点名来源时不等待：用户点这一家就是想立刻看结果。
        if (isAutoChain) {
            val waitMillis = sourceThrottle?.waitMillisBefore(source) ?: 0L
            if (waitMillis > 0L) {
                logStore.append("${source.label} 距上次请求不足最小间隔，等待 ${waitMillis}ms")
                delay(waitMillis)
            }
            sourceThrottle?.markRequested(source)
        }
        val retryCount = settingsRepository.getScrapeRetryCount()
        // 字节闸门取快照：取数前后各取一次，差值就是这一次（含重试）读进来的总量。
        // 记在重试之外而不是每个 attempt 里，是因为重试次数是用户配的，逐次记账要多加一个累计位。
        val bytesMark = ScrapeByteMeter.mark(source)
        return runCatching {
            retryScrapeRequest(
                attemptCount = retryCount,
                retryDelayScheduleMillis = SCRAPE_RETRY_DELAYS_MILLIS,
                // 只有传输层失败才重试同一来源；403/404/429/5xx 是站点的确定性回答，
                // 立刻重试等于把风控与配额再烧一遍（peach 也只重试 transport 错误）。
                shouldRetry = ScrapeFailureClassifier::isRetryableTransportFailure,
                onAttemptFailure = { attemptNumber, error ->
                    val kind = ScrapeFailureClassifier.fromThrowable(error).kind
                    logStore.append(
                        "刮削来源 ${source.label} 第 $attemptNumber/$retryCount 次失败（$kind）：" +
                            (error.message ?: error::class.java.simpleName) +
                            if (kind == ScrapeFailureKind.Network) "" else "，非传输层失败，不再重试"
                    )
                }
            ) {
                scrapeSource(source, sourceNumber, useSerialMutex = isAutoChain)
            }
        }.fold(
            onSuccess = { info ->
                // 番号一致性校验：社区站按番号搜索的常见失败形态不是「搜不到」，而是
                // 「搜到了同号异片」（`259LUXU-891` 被答成 `259LUXU-1891`）。收尾时会把
                // 结果番号改写成文件名番号，若不校验，错片的标题/演员/封面就会被静默
                // 写到这部影片上。认不出来的形态不拦（见 ScrapeNumberGuard 的取舍）。
                if (ScrapeNumberGuard.conflicts(sourceNumber, info.number)) {
                    val failure = ScrapeFailure(
                        kind = ScrapeFailureKind.NotFound,
                        message = "${source.label} 返回的番号是 ${info.number.ifBlank { "空" }}，" +
                            "与本次查询的 $sourceNumber 不一致，已丢弃这份结果",
                    )
                    val cooldownMillis = sourceHealthTracker.recordFailure(source, failure)
                    sourceHealthRepository?.let { repo ->
                        backgroundScope.launch {
                            runCatching { repo.recordFailure(source, failure, cooldownMillis) }
                        }
                    }
                    if (isAutoChain) {
                        logStore.append("优先级刮削丢弃：${source.label}，${failure.message}")
                    }
                    // numberMatched = false：这家答的是别的片，**不是**「站里没有这个番号」。
                    // 桃链靠这个标志决定写不写负缓存，写错等于 7 天内静默跳过一条真实缺口。
                    return@fold SourceAttempt(
                        source = source,
                        failure = failure,
                        numberMatched = false,
                        responseBytes = ScrapeByteMeter.since(source, bytesMark),
                        // 档位是「这家答不出这个番号」，与 NotFound 同档；负缓存仍靠
                        // numberMatched=false 拦住，所以这里标档只影响日志与聚合措辞。
                        peachReason = PeachFailureReason.NotFound
                    )
                }
                sourceHealthTracker.recordSuccess(source)
                sourceHealthRepository?.let { repo ->
                    backgroundScope.launch { runCatching { repo.recordSuccess(source) } }
                }
                if (isAutoChain) {
                    logStore.append("优先级刮削成功：${source.label} -> ${info.title.ifBlank { number }}")
                }
                SourceAttempt(
                    source = source,
                    info = info,
                    responseBytes = ScrapeByteMeter.since(source, bytesMark)
                )
            },
            onFailure = { error ->
                if (error is CancellationException) throw error
                val failure = ScrapeFailureClassifier.fromThrowable(error)
                // 顺带按 peach 十二档归一次类：桃链判「这家是不是永久坏了 / 要不要停整源」
                // 用的是这一档，而它只在拿到原始异常的这一层才归得出来（下面只剩
                // ScrapeFailure 的九档口径，认不出「站点改版」与「被 Cloudflare 挡」）。
                // legacy 链不读这个字段，因此对既有行为零影响。
                val peachReason = PeachReasonClassifier.classify(error)
                val cooldownMillis = sourceHealthTracker.recordFailure(source, failure)
                sourceHealthRepository?.let { repo ->
                    backgroundScope.launch {
                        runCatching { repo.recordFailure(source, failure, cooldownMillis) }
                    }
                }
                if (isAutoChain) {
                    logStore.append(
                        "优先级刮削失败：${source.label}，类型=${failure.kind.name}" +
                            "，peach档位=${peachReason.name}" +
                            "，可重试=${failure.retryable}，冷却=${cooldownMillis / 1000}s，原因：${failure.message}"
                    )
                }
                SourceAttempt(
                    source = source,
                    failure = failure,
                    responseBytes = ScrapeByteMeter.since(source, bytesMark),
                    peachReason = peachReason
                )
            }
        )
    }

    /**
     * 把「这次刮削给哪些字段写了值」记进归属账本。
     *
     * 为什么只在重新刮削路径记账、不在首次刮削记账：首次刮削时影片行还没入库，拿不到 movieId；
     * 而重新刮削是唯一一个「既有影片 + 明确来源 + 用户主动要求覆盖」的场景，正好是账本最该管的地方
     * ——下次批量刮削想把站点值盖上来时，引擎就能凭这里的等级判断该不该覆盖。
     *
     * 只记非空字段：空值不构成「写过」，记了反而会让空槽在后续合并里被误判成有主。
     */
    private suspend fun recordScrapedProvenance(movieId: Long, info: ScrapedMovieInfo) {
        val ledger = metadataLedger ?: return
        if (movieId <= 0L) return
        val sourceLabel = info.source.ifBlank { "刮削" }
        val entries = buildList {
            fun put(field: MetadataFieldKey, value: String?) {
                val trimmed = value?.trim().orEmpty()
                if (trimmed.isNotEmpty()) {
                    add(MovieMetadataLedgerRepository.ProvenanceWrite(field, trimmed, MetadataAuthority.Provider, sourceLabel))
                }
            }
            put(MetadataFieldKey.Title, info.title)
            put(MetadataFieldKey.OriginalTitle, info.originalTitle)
            put(MetadataFieldKey.Plot, info.plot)
            put(MetadataFieldKey.Outline, info.outline)
            put(MetadataFieldKey.Year, info.year)
            put(MetadataFieldKey.Premiered, info.premiered)
            put(MetadataFieldKey.Runtime, info.runtime)
            put(MetadataFieldKey.Series, info.series)
            put(MetadataFieldKey.Rating, info.rating)
            put(MetadataFieldKey.Studios, info.studio)
            put(MetadataFieldKey.Poster, info.posterUrl)
            put(MetadataFieldKey.Fanart, info.fanartUrl)
            put(MetadataFieldKey.Thumb, info.thumbUrl)
            if (info.directors.isNotEmpty()) {
                put(MetadataFieldKey.Directors, info.directors.joinToString(""))
            }
            if (info.actors.isNotEmpty()) {
                put(MetadataFieldKey.Actors, info.actors.joinToString(""))
            }
            if (info.genres.isNotEmpty()) {
                put(MetadataFieldKey.Genres, info.genres.joinToString(""))
            }
            if (info.tags.isNotEmpty()) {
                put(MetadataFieldKey.Tags, info.tags.joinToString(""))
            }
        }
        // 记账失败不该让「已经成功的刮削」变成失败：数据已经写进 NFO 与库里了。
        runCatching { ledger.recordWrites(movieId, entries) }
            .onFailure { logStore.append("元数据归属记账失败（不影响刮削结果）：${it.message ?: it::class.java.simpleName}") }
    }

    private suspend fun scrapeSource(
        source: ScrapeSource,
        number: String,
        useSerialMutex: Boolean
    ): ScrapedMovieInfo {
        val scrapeNumber = if (source == ScrapeSource.Mgstage) {
            remoteScrapeConfigRepository.getMgstageNumberPrefixes()
            normalizeMgstageSearchNumber(
                number,
                remoteScrapeConfigRepository.getLocalMgstageSearchPrefixAliases()
            )
        } else {
            number
        }
        val mutex = source.serialScrapeMutex().takeIf { useSerialMutex }
        return if (mutex == null) {
            scraperRegistry.scrape(source, scrapeNumber)
        } else {
            mutex.withLock { scraperRegistry.scrape(source, scrapeNumber) }
        }
    }

    private suspend fun extractMovieNumberWithRules(vararg values: String?): String? {
        return extractNumberWithCachedRules(
            values = values.toList(),
            extractor = MovieNumberExtractor::extract,
            loadCachedRules = ::loadCachedNumberRecognitionRules
        )
    }

    private suspend fun extractOutputNumberWithRules(vararg values: String?): String? {
        val number = extractNumberWithCachedRules(
            values = values.toList(),
            extractor = MovieNumberExtractor::extractDisplayNumber,
            loadCachedRules = ::loadCachedNumberRecognitionRules
        )
        return number?.let { rewriteNumberPrefix(it, settingsRepository.getNumberPrefixRewriteRules()) }
    }

    private suspend fun scrapeSourcesFor(source: ScrapeSource, number: String): List<ScrapeSource> {
        if (source != ScrapeSource.Priority) return listOf(source)
        val configuredSources = settingsRepository.getPriorityScrapeSources()
        if (!settingsRepository.isMgstageAmateurPriorityEnabled()) return configuredSources
        val prefix = numberPrefix(number) ?: return configuredSources
        val mgstagePrefixes = remoteScrapeConfigRepository.getMgstageNumberPrefixes()
        if (prefix !in mgstagePrefixes) return configuredSources
        logStore.append("MGStage 前缀规则命中：$prefix，优先使用 MGStage")
        return (listOf(ScrapeSource.Mgstage) + configuredSources).distinct()
    }

    private fun dmm2SkipMessage(source: ScrapeSource, number: String): String? {
        if (source != ScrapeSource.Dmm2) return null
        val prefix = numberPrefix(number) ?: return null
        if (prefix.isBlank()) return null
        return if (prefix in settingsRepository.getDmm2SkippedNumberPrefixes()) {
            "DMM2不支持${prefix}番号刮削"
        } else {
            null
        }
    }

    suspend fun clearScrapeFiles(movie: MovieEntity): ClearScrapeResult = withContext(ioDispatcher) {
        val target = findTargetForMovie(movie)
        val root = DocumentFile.fromTreeUri(context, Uri.parse(movie.libraryRootUri))
            ?: error("影片库目录不可用")
        val number = extractMovieNumberWithRules(target.file.name.orEmpty(), movie.title)
            ?: error("无法安全提取影片番号")

        logStore.append("Start clearing scrape files: $number")
        val restoredFileName = restoredStrmFileName(target.file, number)
        val directoryName = target.directory.name.orEmpty()
        val isOrganizedFolder = directoryName.hasSameMovieNumber(number) &&
            (directoryName.startsWith("\u3010") || directoryName.startsWith("[")) &&
            target.parentDirectory != null

        val restoredStrmUri = if (isOrganizedFolder) {
            val parent = target.parentDirectory ?: error("无法定位父目录")
            val rootFileName = root.uniqueChildFileName(restoredFileName)
            val restoredFile = copyStrmFile(target.file, root, rootFileName)
            logStore.append("STRM restored to library root: $rootFileName")
            deleteRecursively(target.directory)
            logStore.append("Deleted generated movie directory: ${target.directory.name}")
            deleteEmptyDirectory(parent, root)
            restoredFile.uri.toString()
        } else {
            deleteMetadataFiles(target.directory, target.baseName)
            logStore.append("Deleted metadata files for baseName=${target.baseName}")
            target.file.uri.toString()
        }

        logStore.append("Clear scrape files finished: $number")
        ClearScrapeResult(number = number, strmUri = restoredStrmUri)
    }

    fun startUpdateMissingActorAvatars(movies: List<MovieEntity>) {
        if (actorAvatarJob?.isActive == true) {
            logStore.append("Actor avatar update is already running")
            return
        }
        actorAvatarJob = backgroundScope.launch {
            _actorAvatarUpdateState.value = ActorAvatarUpdateState(isUpdating = true, message = "Updating missing actor avatars...")
            runCatching { updateMissingActorAvatarsInternal(movies) }
                .onSuccess { result ->
                    _actorAvatarUpdateState.value = ActorAvatarUpdateState(
                        isUpdating = false,
                        message = if (result.totalMissing == 0) {
                            "Actor avatars are already up to date"
                        } else {
                            "Actor avatars updated: ${result.downloaded}/${result.totalMissing}"
                        },
                        refreshVersion = _actorAvatarUpdateState.value.refreshVersion + 1
                    )
                }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    logStore.append("Actor avatar background update failed: ${error.message ?: error::class.java.simpleName}")
                    _actorAvatarUpdateState.value = ActorAvatarUpdateState(
                        isUpdating = false,
                        message = error.message ?: "演员头像更新失败",
                        refreshVersion = _actorAvatarUpdateState.value.refreshVersion
                    )
                }
        }
    }

    private suspend fun updateMissingActorAvatarsInternal(movies: List<MovieEntity>): ActorAvatarUpdateResult {
        val missingActors = movies
            .flatMap { it.actors }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.normalizedActorName() }
            .filterNot { actorAvatarStore.hasAvatar(it) }

        if (missingActors.isEmpty()) {
            logStore.append("Actor avatar update: no missing avatars")
            return ActorAvatarUpdateResult(totalMissing = 0, downloaded = 0, scrapedMovies = 0)
        }

        logStore.append("Start actor avatar update, missing=${missingActors.size}")
        val pending = missingActors.toMutableSet()
        val visitedNumbers = mutableSetOf<String>()
        var scrapedMovies = 0

        movies
            .filter { movie -> movie.actors.any { actor -> pending.any { it.sameActor(actor) } } }
            .forEach { movie ->
                if (pending.isEmpty()) return@forEach
                val number = MovieNumberExtractor.extract(movie.videoName)
                    ?: MovieNumberExtractor.extract(movie.title)
                    ?: MovieNumberExtractor.extract(movie.originalTitle.orEmpty())
                    ?: return@forEach
                if (!visitedNumbers.add(number.uppercase())) return@forEach

                runCatching {
                    logStore.append("Query DMM2 for actor avatars: $number")
                    val info = dmm2Scraper.scrape(number)
                    scrapedMovies += 1
                    downloadActorAvatars(info)
                    val resolved = pending.filter { actorAvatarStore.hasAvatar(it) }
                    pending.removeAll(resolved.toSet())
                    if (resolved.isNotEmpty()) {
                        logStore.append("Actor avatars resolved: ${resolved.joinToString(", ")}")
                    }
                }.onFailure { error ->
                    if (error is CancellationException) throw error
                    logStore.append("DMM2 actor avatar query failed: $number, ${error.message ?: error::class.java.simpleName}")
                }
            }

        val downloaded = missingActors.count { actorAvatarStore.hasAvatar(it) }
        logStore.append("Actor avatar update finished: downloaded=$downloaded/${missingActors.size}, scrapedMovies=$scrapedMovies")
        return ActorAvatarUpdateResult(
            totalMissing = missingActors.size,
            downloaded = downloaded,
            scrapedMovies = scrapedMovies
        )
    }

    private fun findTargetForMovie(movie: MovieEntity): StrmTarget {
        if (!movie.videoName.endsWith(".strm", ignoreCase = true)) {
            error("当前影片不是 STRM 文件")
        }
        return requireStoredDocument(
            movieId = movie.id,
            fileName = movie.videoName,
            databaseUri = movie.videoUri,
            operation = "刮削定位"
        ) {
            storedDocumentLocator.find(movie.libraryRootUri, movie.videoUri)?.toStrmTarget()
        }
    }

    private fun LocatedDocument.toStrmTarget(): StrmTarget {
        val name = file.name.orEmpty()
        return StrmTarget(
            directory = directory,
            file = file,
            baseName = name.substringBeforeLast('.', name),
            parentDirectory = parentDirectory
        )
    }

    private suspend fun writeOrganizedScrapeFiles(target: StrmTarget, info: ScrapedMovieInfo, fallbackNumber: String, forceDistinct: Boolean = false): String {
        val sourceName = target.file.name.orEmpty()
        val outputNumber = extractOutputNumberWithRules(sourceName)
        val baseNumber = (outputNumber ?: fallbackNumber.ifBlank { info.number }).uppercase()
        val variant = detectMovieVariant(sourceName)
        val writeInfo = info.copy(number = baseNumber)
        if (info.number.isNotBlank() && !info.number.equals(baseNumber, ignoreCase = true)) {
            logStore.append("Use source filename number for output: $baseNumber (scraped=${info.number})")
        }
        val distinctSuffix = if (forceDistinct) target.file.name.orEmpty().distinctPickcodeSuffix() else null
        val baseName = buildMovieBaseName(writeInfo, baseNumber) + distinctSuffix.orEmpty()
        val movieDirectory = if (target.directory.name == baseName) {
            target.directory
        } else {
            val actorDirectory = createOrReuseActorDirectory(target.directory, writeInfo)
            createOrReuseMovieDirectory(actorDirectory, baseName)
        }

        logStore.append("Movie directory: ${movieDirectory.name}")

        val partLabel = extractMovieNumberInfo(target.file.name.orEmpty())?.partLabel
        val strmName = "$baseName${playbackSourceSuffix(partLabel, variant)}.strm"
        val newStrm = copyStrmFile(target.file, movieDirectory, strmName)
        logStore.append("[媒体路径] 状态=新文件已写入，操作=刮削整理，文件=$strmName，说明=等待数据库更新")

        try {
            val nfoName = "$baseName.nfo"
            logStore.append("Write NFO: $nfoName")
            writeTextFile(movieDirectory, nfoName, NfoWriter.build(writeInfo))
            logStore.append("NFO written: $nfoName")

            val imageReferer = info.imageReferer()
            val posterUrls = info.posterDownloadUrls()
            if (posterUrls.isNotEmpty()) {
                val posterName = "$baseName-poster.jpg"
                logStore.append("Download poster: ${posterUrls.first()}${posterUrls.candidateCountSuffix()}")
                tryDownloadImageToFile(movieDirectory, posterName, posterUrls, imageReferer, "Poster")
            } else {
                logStore.append("Poster URL is blank; skipped")
            }

            val thumbUrls = info.thumbDownloadUrls()
            val fanartUrls = info.fanartDownloadUrls()
            if (thumbUrls.isNotEmpty()) {
                val thumbName = "$baseName-thumb.jpg"
                logStore.append("Download thumb: ${thumbUrls.first()}${thumbUrls.candidateCountSuffix()}")
                tryDownloadImageToFile(movieDirectory, thumbName, thumbUrls, imageReferer, "Thumb")

                val fanartName = "$baseName-fanart.jpg"
                logStore.append("Copy thumb as fanart: $fanartName")
                tryDownloadImageToFile(movieDirectory, fanartName, fanartUrls, imageReferer, "Fanart")
            } else {
                logStore.append("Thumb URL is blank; skipped")
            }
            deleteLegacyNfoXml(target)
            deleteOldStrmIfMoved(target, newStrm)
            return newStrm.uri.toString()
        } catch (error: Throwable) {
            rollbackCopiedStrm(target, newStrm)
            throw error
        }
    }

    private suspend fun rewriteScrapeFilesInPlace(target: StrmTarget, info: ScrapedMovieInfo) {
        val baseName = target.baseName
        val directory = target.directory
        val nfoName = "$baseName.nfo"
        val sourceNumber = extractOutputNumberWithRules(target.file.name.orEmpty()) ?: info.number
        val displayNumber = displayNumberWithVariant(sourceNumber, target.file.name.orEmpty())
        val writeInfo = info.copy(number = displayNumber)
        logStore.append("Rewrite NFO: $nfoName")
        writeTextFile(directory, nfoName, NfoWriter.build(writeInfo))
        logStore.append("NFO rewritten: $nfoName")

        val posterName = "$baseName-poster.jpg"
        val thumbName = "$baseName-thumb.jpg"
        val fanartName = "$baseName-fanart.jpg"
        val hasStandardPoster = directory.findFile(posterName) != null
        val hasPoster = hasStandardPoster || directory.hasAnyFile("poster.jpg", "movie-poster.jpg")
        val hasThumb = directory.hasAnyFile(thumbName, "thumb.jpg")
        val hasFanart = directory.hasAnyFile(fanartName, "fanart.jpg", "movie-fanart.jpg")
        val shouldRefreshPoster = info.shouldRefreshDistinctPoster()
        if (hasPoster && hasThumb && hasFanart && !shouldRefreshPoster) {
            logStore.append("poster/thumb/fanart already exist; skipped image downloads")
            deleteLegacyNfoXml(target)
            return
        }

        val imageReferer = info.imageReferer()
        val posterUrls = info.posterDownloadUrls()
        val thumbUrls = info.thumbDownloadUrls()
        val fanartUrls = info.fanartDownloadUrls()
        if (((!hasStandardPoster && posterUrls.isNotEmpty()) || (shouldRefreshPoster && posterUrls.isNotEmpty()))) {
            logStore.append(
                if (hasStandardPoster) {
                    "Poster refreshed: ${posterUrls.first()}${posterUrls.candidateCountSuffix()}"
                } else {
                    "Poster missing; downloading: ${posterUrls.first()}${posterUrls.candidateCountSuffix()}"
                }
            )
            tryDownloadImageToFile(directory, posterName, posterUrls, imageReferer, "Poster")
        }
        if (!hasThumb && thumbUrls.isNotEmpty()) {
            logStore.append("Thumb missing; downloading: ${thumbUrls.first()}${thumbUrls.candidateCountSuffix()}")
            tryDownloadImageToFile(directory, thumbName, thumbUrls, imageReferer, "Thumb")
        }
        if (!hasFanart && fanartUrls.isNotEmpty()) {
            logStore.append("Fanart missing; using thumb: $fanartName")
            tryDownloadImageToFile(directory, fanartName, fanartUrls, imageReferer, "Fanart")
        }
        deleteLegacyNfoXml(target)
    }

    private suspend fun downloadActorAvatars(info: ScrapedMovieInfo) {
        if (info.actorImageUrls.isEmpty()) return
        val imageReferer = info.imageReferer()
        info.actorImageUrls.forEach { (actorName, imageUrl) ->
            if (actorName.isBlank() || imageUrl.isBlank()) return@forEach
            if (actorAvatarStore.hasAvatar(actorName)) {
                logStore.append("Actor avatar already exists; skipped: $actorName")
                return@forEach
            }
            runCatching {
                logStore.append("Download actor avatar: $actorName -> $imageUrl")
                actorAvatarStore.saveAvatar(actorName, imageDownloadService.downloadImageBytes(imageUrl, imageReferer))
                logStore.append("Actor avatar downloaded: $actorName")
            }.onFailure { error ->
                logStore.append("Actor avatar download failed: $actorName, ${error.message ?: error::class.java.simpleName}")
            }
        }
    }

    private fun buildMovieBaseName(info: ScrapedMovieInfo, fallbackNumber: String): String {
        val actor = info.actors.firstOrNull { it.isNotBlank() }
            ?.sanitizeFileName()
            ?.ifBlank { null }
            ?: "\u672A\u77E5\u6F14\u5458"
        val number = info.number.ifBlank { fallbackNumber }
            .uppercase()
            .sanitizeFileName()
        return "\u3010$actor\u3011$number"
    }

    private fun createOrReuseActorDirectory(parent: DocumentFile, info: ScrapedMovieInfo): DocumentFile {
        val desiredName = actorGroupFolderName(info)
        if (parent.name == desiredName) return parent
        parent.findFile(desiredName)?.let { existing ->
            if (existing.isDirectory) return existing
        }
        logStore.append("Create actor directory: $desiredName")
        return parent.createDirectory(desiredName)
            ?: error("无法创建演员目录：$desiredName")
    }

    private fun actorGroupFolderName(info: ScrapedMovieInfo): String {
        val actors = info.actors
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
        return when (actors.size) {
            0 -> "\u672A\u77E5\u6F14\u5458"
            1 -> actors.first().sanitizeFileName().ifBlank { "\u672A\u77E5\u6F14\u5458" }
            else -> "\u591A\u4EBA\u4F5C\u54C1"
        }
    }

    private fun createOrReuseMovieDirectory(parent: DocumentFile, desiredName: String): DocumentFile {
        parent.findFile(desiredName)?.let { existing ->
            if (existing.isDirectory) return existing
        }
        var candidate = desiredName
        var index = 1
        while (parent.findFile(candidate) != null) {
            candidate = "$desiredName-$index"
            index += 1
        }
        logStore.append("Create movie directory: $candidate")
        return parent.createDirectory(candidate) ?: error("无法创建影片目录：$candidate")
    }

    private fun copyStrmFile(source: DocumentFile, directory: DocumentFile, fileName: String): DocumentFile {
        val content = context.contentResolver.openInputStream(source.uri)?.use { stream ->
            stream.bufferedReader(Charsets.UTF_8).readText()
        } ?: error("无法读取源 STRM 文件：${source.name}")
        return writeTextFile(directory, fileName, content)
    }

    private fun restoredStrmFileName(source: DocumentFile, fallbackNumber: String): String {
        val content = context.contentResolver.openInputStream(source.uri)
            ?.use { stream -> stream.bufferedReader(Charsets.UTF_8).readText() }
            .orEmpty()
        val originalName = content.extractOriginalVideoNameFromStrm()
            ?: source.name
                ?.takeIf { !it.startsWith("\u3010") && !it.startsWith("[") }
        val baseName = originalName
            ?.substringBeforeLast('.', originalName)
            ?.sanitizeFileName()
            ?.takeIf { it.isNotBlank() }
            ?: fallbackNumber.sanitizeFileName()
        return "$baseName.strm"
    }

    private fun String.extractOriginalVideoNameFromStrm(): String? {
        val uri = runCatching { Uri.parse(this.trim()) }.getOrNull() ?: return null
        val segments = uri.pathSegments
        val routeIndex = segments.indexOfFirst { it == "download_m3u" || it == "play" || it == "video_proxy" }
        return routeIndex
            .takeIf { it >= 0 && it + 2 < segments.size }
            ?.let { segments[it + 2] }
            ?.let(Uri::decode)
            ?.takeIf { it.isNotBlank() }
    }

    private fun deleteOldStrmIfMoved(target: StrmTarget, newStrm: DocumentFile) {
        if (target.file.uri == newStrm.uri) return
        if (target.file.delete()) {
            logStore.append(
                "[媒体路径] 状态=文件移动完成，操作=刮削整理，文件=${newStrm.name}，详情=旧 STRM 已删除；等待调用方同步数据库"
            )
        } else {
            logStore.append(
                "[媒体路径] 状态=警告，操作=刮削整理，文件=${target.file.name}，详情=新 STRM 已写入，但旧 STRM 删除失败；数据库将指向新文件"
            )
        }
    }

    private fun rollbackCopiedStrm(target: StrmTarget, newStrm: DocumentFile) {
        if (target.file.uri == newStrm.uri) return
        if (newStrm.delete()) {
            logStore.append("Rollback copied STRM after scrape file write failure: ${newStrm.name}")
        } else {
            logStore.append("Warning: failed to rollback copied STRM: ${newStrm.name}")
        }
    }

    private fun deleteLegacyNfoXml(target: StrmTarget) {
        val legacyName = "${target.baseName}.nfo.xml"
        target.directory.findFile(legacyName)?.let { legacy ->
            if (legacy.delete()) {
                logStore.append("Deleted legacy NFO file: $legacyName")
            }
        }
    }

    private fun writeTextFile(directory: DocumentFile, fileName: String, content: String): DocumentFile {
        directory.findFile(fileName)?.delete()
        val file = directory.createFile(GENERIC_FILE_MIME_TYPE, fileName)
            ?: error("无法创建文件：$fileName")
        context.contentResolver.openOutputStream(file.uri, "wt")?.use { output ->
            output.write(content.toByteArray(Charsets.UTF_8))
        } ?: error("无法写入文件：$fileName")
        return file
    }

    private fun DocumentFile.uniqueChildFileName(desiredName: String): String {
        if (findFile(desiredName) == null) return desiredName
        val baseName = desiredName.substringBeforeLast('.', desiredName)
        val extension = desiredName.substringAfterLast('.', "").takeIf { it.isNotBlank() }
        var index = 1
        while (true) {
            val candidate = buildString {
                append(baseName)
                append("-")
                append(index)
                extension?.let { append(".").append(it) }
            }
            if (findFile(candidate) == null) return candidate
            index += 1
        }
    }

    private suspend fun downloadImageToFile(directory: DocumentFile, fileName: String, url: String, referer: String? = null) {
        val bytes = imageDownloadService.downloadImageBytes(url, referer)
        directory.findFile(fileName)?.delete()
        val file = directory.createFile("image/jpeg", fileName)
            ?: error("无法创建图片：$fileName")
        context.contentResolver.openOutputStream(file.uri, "wt")?.use { output ->
            output.write(bytes)
        } ?: error("无法写入图片：$fileName")
    }

    private suspend fun tryDownloadImageToFile(
        directory: DocumentFile,
        fileName: String,
        url: String,
        referer: String?,
        label: String
    ): Boolean = tryDownloadImageToFile(directory, fileName, listOf(url), referer, label)

    private suspend fun tryDownloadImageToFile(
        directory: DocumentFile,
        fileName: String,
        urls: List<String>,
        referer: String?,
        label: String
    ): Boolean {
        val candidates = urls.normalizedUrlList()
        if (candidates.isEmpty()) return false
        candidates.forEachIndexed { index, url ->
            runCatching {
                downloadImageToFile(directory, fileName, url, referer)
            }.onSuccess {
                if (index > 0) {
                    logStore.append("$label fallback succeeded (${index + 1}/${candidates.size}): $url")
                }
                logStore.append("$label written: $fileName")
                return true
            }.onFailure { error ->
                val message = error.message ?: error::class.java.simpleName
                if (index < candidates.lastIndex) {
                    logStore.append("$label download failed (${index + 1}/${candidates.size}), trying next: $message")
                } else {
                    logStore.append("$label download failed; skipped image: $message")
                }
            }
        }
        return false
    }

    private fun deleteMetadataFiles(directory: DocumentFile, baseName: String) {
        val imageExtensions = listOf("jpg", "jpeg", "png", "webp")
        val names = buildList {
            add("$baseName.nfo")
            add("$baseName.nfo.xml")
            imageExtensions.forEach { ext ->
                add("$baseName-poster.$ext")
                add("$baseName-thumb.$ext")
                add("$baseName-fanart.$ext")
                add("poster.$ext")
                add("thumb.$ext")
                add("fanart.$ext")
                add("movie-poster.$ext")
                add("movie-fanart.$ext")
            }
        }
        names.forEach { name ->
            directory.findFile(name)?.delete()
        }
    }

    private fun DocumentFile.hasAnyFile(vararg names: String): Boolean {
        return names.any { name -> findFile(name) != null }
    }

    private fun deleteRecursively(file: DocumentFile) {
        if (file.isDirectory) {
            file.listFiles().forEach { deleteRecursively(it) }
        }
        file.delete()
    }

    private fun deleteEmptyDirectory(directory: DocumentFile, root: DocumentFile) {
        if (directory.uri == root.uri) return
        if (!directory.isDirectory) return
        if (directory.listFiles().isEmpty() && directory.delete()) {
            logStore.append("Deleted empty parent directory: ${directory.name}")
        }
    }

    private fun String.sanitizeFileName(): String {
        return replace(Regex("""[\\/:*?"<>|]"""), "_").trim()
    }

    private fun String.hasSameMovieNumber(number: String): Boolean {
        val expected = movieKeyFromText(number) ?: number.uppercase()
        return movieKeyFromText(this) == expected || contains(number, ignoreCase = true)
    }

    private fun String.distinctPickcodeSuffix(): String? {
        val token = substringBeforeLast('.', this)
            .substringAfterLast('_', "")
            .takeIf { it.length >= 8 && it.all { char -> char.isLetterOrDigit() } }
            ?: return null
        return "_${token.take(8)}"
    }

    private fun String.normalizedActorName(): String = trim().lowercase()

    private fun String.sameActor(other: String): Boolean =
        normalizedActorName() == other.normalizedActorName()

    private fun ScrapedMovieInfo.imageReferer(): String? {
        if (source.equals("mgstage", ignoreCase = true)) return MGSTAGE_BASE_URL
        return website
            .takeIf { source.equals("javbus", ignoreCase = true) }
            ?.takeIf { it.startsWith(JAVBUS_BASE_URL, ignoreCase = true) }
    }

    private fun ScrapedMovieInfo.posterDownloadUrls(): List<String> =
        (posterImageUrls + posterUrl.ifBlank { thumbUrl }).normalizedUrlList()

    private fun ScrapedMovieInfo.thumbDownloadUrls(): List<String> =
        (thumbImageUrls + thumbUrl).normalizedUrlList()

    private fun ScrapedMovieInfo.fanartDownloadUrls(): List<String> =
        (listOf(fanartUrl) + thumbImageUrls + thumbUrl).normalizedUrlList()

    private fun ScrapedMovieInfo.shouldRefreshDistinctPoster(): Boolean {
        if (!source.equals("dmm", ignoreCase = true) && !source.equals("dmm2", ignoreCase = true)) return false
        val poster = posterDownloadUrls().firstOrNull().orEmpty()
        val thumb = thumbDownloadUrls().firstOrNull().orEmpty()
        return poster.isNotBlank() && thumb.isNotBlank() && !poster.equals(thumb, ignoreCase = true)
    }

    private fun List<String>.normalizedUrlList(): List<String> =
        map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }

    private fun List<String>.candidateCountSuffix(): String =
        if (size > 1) " (+${size - 1} fallback)" else ""

    private fun DocumentFile.isExcludedAssetDirectory(): Boolean {
        val normalized = name.orEmpty().trim().lowercase().replace('_', ' ').replace('-', ' ')
        return normalized == "extrafanart" || normalized == "behind the scenes"
    }

    private data class StrmTarget(
        val directory: DocumentFile,
        val file: DocumentFile,
        val baseName: String,
        val parentDirectory: DocumentFile?
    )

    private val serialScrapeMutexes = ConcurrentHashMap<ScrapeSource, Mutex>()

    // 审查 I11：此前恒返回 null，导致「串行刮削」从未生效。现按源持有独立 Mutex，
    // 仅在 useSerialMutex 为真时（serialMutex?.takeIf { useSerialMutex }）真正串行化同源刮削。
    private fun ScrapeSource.serialScrapeMutex(): Mutex = serialScrapeMutexes.getOrPut(this) { Mutex() }

    private fun numberPrefix(number: String): String? =
        number.substringBefore('-', missingDelimiterValue = number)
            .uppercase()
            .filter { it.isLetterOrDigit() }
            .takeIf { it.isNotBlank() && it.any { char -> char.isLetter() } }

    private companion object {
        const val GENERIC_FILE_MIME_TYPE = "application/octet-stream"
        const val JAVBUS_BASE_URL = "https://www.javbus.com/"
        const val MGSTAGE_BASE_URL = "https://www.mgstage.com/"
        const val SCRAPE_QUEUE_POLL_INTERVAL_MS = 250L

        /** peach 的 REQUEST_TIMEOUT = 30.0（httpx 同一超时作用于 connect/read/write）。 */
        const val SCRAPE_TIMEOUT_SECONDS = 30L

        /**
         * 单次 HTTP 调用的整体上限（connect + TLS + 首字节 + body 读完）。
         *
         * 为什么不等于 [SCRAPE_TIMEOUT_SECONDS]：那个是**每一跳**的超时，三次跳可以叠成 90 秒；
         * 而链路的预算口径是「一个来源最多占多久」。45 秒 = 一次跨境 TLS 握手（数秒）
         * + 一页 1~4 MiB HTML 的传输（数秒~十几秒）+ 余量，正常页面远用不到。
         */
        const val SCRAPE_CALL_TIMEOUT_SECONDS = 45L

        /**
         * peach 的 NETWORK_RETRY_DELAYS = (2, 4, 6, 8)：只对**传输层失败**退避重试。
         *
         * 为什么不照抄成「固定 1 秒重试 3 次」：连接被重置/超时的恢复时间不是常量，
         * 立刻重试往往打在同一个失败窗口里；而 403/404 这类确定性结果重试只是多打一遍。
         * 次数仍由设置页的「每个来源尝试次数」控制，这里只提供每一跳的等待表。
         */
        val SCRAPE_RETRY_DELAYS_MILLIS = listOf(2_000L, 4_000L, 6_000L, 8_000L)
    }
}

internal suspend fun <T> retryScrapeRequest(
    attemptCount: Int,
    retryDelayMillis: Long = 1_000L,
    retryDelayScheduleMillis: List<Long>? = null,
    shouldRetry: (Throwable) -> Boolean = { true },
    onAttemptFailure: (attempt: Int, error: Throwable) -> Unit = { _, _ -> },
    block: suspend (attempt: Int) -> T
): T {
    require(attemptCount > 0) { "attemptCount must be positive" }
    var lastError: Throwable? = null
    repeat(attemptCount) { index ->
        val attempt = index + 1
        try {
            return block(attempt)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            lastError = error
            onAttemptFailure(attempt, error)
            // 不可重试的失败立刻收手：继续打同一家不但不会成功，还会把风控与配额额度再烧一遍。
            if (!shouldRetry(error)) throw error
            if (attempt < attemptCount) {
                // 阶梯表优先；越界或未配置时退回固定间隔（老行为，测试里用 0 表示不等）。
                val delayMillis = retryDelayScheduleMillis?.getOrNull(index) ?: retryDelayMillis
                if (delayMillis > 0L) delay(delayMillis)
            }
        }
    }
    throw lastError ?: IllegalStateException("刮削失败")
}

internal fun extractNumberWithCachedRules(
    values: List<String?>,
    extractor: (String) -> String?,
    loadCachedRules: () -> Unit
): String? {
    val candidates = values.mapNotNull { it?.takeIf(String::isNotBlank) }
    loadCachedRules()
    candidates.forEach { value -> extractor(value)?.let { return it } }
    return null
}

data class ActorAvatarUpdateResult(
    val totalMissing: Int,
    val downloaded: Int,
    val scrapedMovies: Int
)

data class ScrapedMovieWriteResult(
    val info: ScrapedMovieInfo,
    val strmUri: String,
    val taskId: String? = null
)

data class RescrapedMovieWriteResult(
    val info: ScrapedMovieInfo,
    val taskId: String
)

data class ActorAvatarUpdateState(
    val isUpdating: Boolean = false,
    val message: String? = null,
    val refreshVersion: Int = 0
)

data class ScrapeQueueState(
    val isRunning: Boolean = false,
    val runningLabel: String? = null,
    val runningCount: Int = 0,
    val waitingCount: Int = 0,
    val startedAtMillis: Long = 0L
)
