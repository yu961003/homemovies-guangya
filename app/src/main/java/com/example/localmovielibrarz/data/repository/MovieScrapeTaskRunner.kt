package com.example.localmovielibrarz.data.repository

import com.example.localmovielibrarz.data.local.MovieEntity
import com.example.localmovielibrarz.scraper.ScrapeSource
import com.example.localmovielibrarz.tasks.TaskKind
import com.example.localmovielibrarz.tasks.TaskState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger

class MovieScrapeTaskRunner(
    private val settingsRepository: AppSettingsRepository,
    private val movieRepository: MovieRepository,
    private val scrapeRepository: StrmScrapeRepository,
    private val cloudVideoTaskRepository: CloudVideoTaskRepository,
    /**
     * 统一任务账本。默认 null 是为了让既有的单测与预览构造点不必立刻跟着改；
     * 真正的装配在 `AppContainer`，那里一定会传入实例。
     */
    private val taskRunRepository: TaskRunRepository? = null
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(MovieScrapeTaskRunnerState())
    private val lock = Any()
    private var job: Job? = null
    private var restartRequested = false

    val state: StateFlow<MovieScrapeTaskRunnerState> = _state

    fun startIssues() {
        synchronized(lock) {
            val currentJob = job
            if (currentJob?.isCompleted == false) {
                if (currentJob.isCancelled) restartRequested = true
                return
            }
            val nextJob = scope.launch(start = CoroutineStart.LAZY) { runIssues() }
            nextJob.invokeOnCompletion {
                val shouldRestart = synchronized(lock) {
                    if (job === nextJob) job = null
                    restartRequested.also { restartRequested = false }
                }
                if (shouldRestart) startIssues()
            }
            job = nextJob
            nextJob.start()
        }
    }

    private suspend fun runIssues() {
            val cloudOwnedMovieIds = cloudVideoTaskRepository.unfinishedOwnedMovieIds()
            movieRepository.resetRunningScrapeTasks(excludedMovieIds = cloudOwnedMovieIds)
            val source = settingsRepository.getDefaultScrapeSource()
            val issueMovies = movieRepository.getScrapeIssueMovies()
            val invalidPathMovies = issueMovies.filter(MovieEntity::hasInvalidStoredMediaPath)
            val tasks = excludeOwnedScrapeTasks(
                tasks = issueMovies.filterNot(MovieEntity::hasInvalidStoredMediaPath),
                ownedMovieIds = cloudOwnedMovieIds,
                idOf = { it.id }
            )
            if (invalidPathMovies.isNotEmpty()) {
                scrapeRepository.appendLog(
                    "[媒体路径] 状态=跳过，操作=影片批量刮削，数量=${invalidPathMovies.size}，详情=这些影片此前已确认数据库路径失效，本次不重复尝试；请在设置页手动扫描"
                )
            }
            if (tasks.isEmpty()) {
                _state.value = MovieScrapeTaskRunnerState(message = "没有待刮削的影片")
                return
            }
            val success = AtomicInteger(0)
            val failed = AtomicInteger(0)
            val processed = AtomicInteger(0)
            val concurrency = settingsRepository.getScrapeConcurrencyLimit()
            // 账本占位放在「确认真的有待刮削影片」之后：空批次不该在 task_runs 里留一行成功记录，
            // 否则设置页的最近任务列表会被一排「0 个影片」刷屏。
            val ledgerRunId = claimLedgerRun(total = tasks.size, source = source)
            _state.value = MovieScrapeTaskRunnerState(
                isRunning = true,
                total = tasks.size,
                message = "正在准备影片批量刮削..."
            )
            scrapeRepository.appendLog("影片批量刮削开始：共 ${tasks.size} 个，并发=$concurrency，来源：${source.name}")
            try {
                runMovieBatchWorkers(tasks, concurrency) { index, movie ->
                    val label = movie.videoName.ifBlank { movie.title }
                    _state.update { state -> state.copy(
                        success = success.get(),
                        failed = failed.get(),
                        message = "正在刮削：$label"
                    ) }
                    val outcome = processMovieScrapeTask(
                        markRunning = { movieRepository.markScrapeTaskRunning(movie.id) },
                        runScrape = {
                            val result = scrapeRepository.scrapeMovieWithOutput(movie, source)
                            val refreshed = movieRepository.refreshMovieAfterScrape(movie, result.strmUri)
                                ?: error("刮削已返回结果，但没有定位到整理后的 STRM")
                            movieRepository.markScrapeTaskCompleted(refreshed.id)
                        },
                        markPending = { movieRepository.markScrapeTaskPending(movie.id) },
                        onRunningStatusTimeout = {
                            scrapeRepository.appendLog("影片状态更新为刮削中超时，继续实际刮削：$label")
                        },
                        markFailed = { error ->
                            val reason = "刮削来源 ${source.name} 失败：${error.message ?: error::class.java.simpleName}"
                            movieRepository.markScrapeTaskFailed(movie.id, reason)
                            scrapeRepository.appendLog("影片批量刮削失败：$label，原因：$reason")
                        },
                        onScrapeTimeout = {
                            scrapeRepository.appendLog("单个影片刮削超过时限，已释放 worker：$label")
                        }
                    )
                    when (outcome) {
                        MovieScrapeTaskOutcome.Completed -> {
                            success.incrementAndGet()
                            scrapeRepository.appendLog("影片批量刮削完成：$label")
                        }
                        MovieScrapeTaskOutcome.Failed -> failed.incrementAndGet()
                    }
                    val completed = processed.incrementAndGet()
                    _state.update { state -> state.copy(
                        current = completed,
                        success = success.get(),
                        failed = failed.get(),
                        message = "影片批量刮削进度：$completed/${tasks.size}"
                    ) }
                    recordLedgerProgress(
                        runId = ledgerRunId,
                        completed = completed,
                        total = tasks.size,
                        step = label
                    )
                }
                val message = "影片批量刮削完成：成功 ${success.get()} 个，失败 ${failed.get()} 个"
                scrapeRepository.appendLog(message)
                _state.value = MovieScrapeTaskRunnerState(
                    total = tasks.size,
                    current = tasks.size,
                    success = success.get(),
                    failed = failed.get(),
                    message = message
                )
                // 部分失败仍记 Succeeded：批量刮削的语义是「跑完了」，单部失败的原因已经
                // 逐条写进 `movies.scrape_failure_reason`，账本再记一次 Failed 只会让设置页
                // 把「3 个没刮到」误报成「整批失败」。
                finishLedgerRun(ledgerRunId, TaskState.Succeeded, null)
            } catch (error: CancellationException) {
                scrapeRepository.appendLog("影片批量刮削已暂停：${error.message ?: "任务被取消"}")
                _state.value = _state.value.copy(
                    isRunning = false,
                    success = success.get(),
                    failed = failed.get(),
                    message = "已暂停影片批量刮削，点击开始可继续"
                )
                finishLedgerRun(ledgerRunId, TaskState.Paused, "已暂停，再次开始会从剩余影片继续")
            } catch (error: Throwable) {
                val message = "影片批量刮削执行器异常退出：${error.message ?: error::class.java.simpleName}"
                runCatching { scrapeRepository.appendLog(message) }
                _state.value = _state.value.copy(
                    isRunning = false,
                    success = success.get(),
                    failed = failed.get(),
                    message = message
                )
                finishLedgerRun(ledgerRunId, TaskState.Failed, message)
            } finally {
                if (_state.value.isRunning) {
                    _state.value = _state.value.copy(isRunning = false)
                }
            }
    }

    fun pause() {
        synchronized(lock) {
            restartRequested = false
            job?.cancel(CancellationException("已手动暂停影片批量刮削"))
        }
    }

    /**
     * 在 `task_runs` 里为「影片批量刮削」占位，返回 run id；占不到或账本不可用时返回 [NO_LEDGER_RUN]。
     *
     * scope 固定为 `"batch"` 而不是逐片一条：用户点是「开始批量刮削」这一个动作，
     * 设置页要看到的是「一次批量在跑、跑到第几部」，逐片建行会让最近任务列表被几百条
     * 「影片刮削」淹没，且失去「本次批量成功几个、失败几个」的汇总口径。
     *
     * **占不到不阻断**：批量刮削是用户主动发起的动作，账本把「同键已有活跃行」当作
     * 「别的地方已经在跑」的判据只在真正的互斥场景（文件夹批量按 folderCid）成立；
     * 这里若因一条历史 Paused 行整批被拒，用户会看到「点了没反应」而日志一片空白，
     * 比不记账糟得多。因此降级为「不记账继续跑」，并把原因写进刮削日志。
     */
    private suspend fun claimLedgerRun(total: Int, source: ScrapeSource): Long {
        val ledger = taskRunRepository ?: return NO_LEDGER_RUN
        return runCatching {
            ledger.claim(
                kind = TaskKind.MovieScrape,
                scope = LEDGER_SCOPE_BATCH,
                title = "影片批量刮削（${source.label}）",
                totalSteps = total
            )?.id ?: run {
                scrapeRepository.appendLog(
                    "影片批量刮削已在处理中，本次不记账；来源：${source.label}"
                )
                NO_LEDGER_RUN
            }
        }.getOrElse { error ->
            scrapeRepository.appendLog(
                "任务账本不可用，本次批量刮削不记账：${error.message ?: error::class.java.simpleName}"
            )
            NO_LEDGER_RUN
        }
    }

    /** 每完成一部就刷新一次进度；写失败只吞掉，绝不能因为记账异常中断真正的刮削。 */
    private suspend fun recordLedgerProgress(runId: Long, completed: Int, total: Int, step: String) {
        if (runId == NO_LEDGER_RUN) return
        val ledger = taskRunRepository ?: return
        runCatching {
            ledger.updateProgress(
                id = runId,
                state = TaskState.Running,
                step = step,
                completedSteps = completed,
                totalSteps = total
            )
        }
    }

    /** 落终态。`Paused` 不是终态，必须走 `notifyPaused`（见 `CloudFolderBatchTaskRunner` 的同名说明）。 */
    private suspend fun finishLedgerRun(runId: Long, state: TaskState, message: String?) {
        if (runId == NO_LEDGER_RUN) return
        val ledger = taskRunRepository ?: return
        runCatching {
            if (state == TaskState.Paused) ledger.notifyPaused(runId, message)
            else ledger.finish(runId, state, message)
        }
    }
}

internal fun MovieEntity.hasInvalidStoredMediaPath(): Boolean =
    scrapeFailureReason.orEmpty().contains("媒体路径失效")

internal fun <T> excludeOwnedScrapeTasks(
    tasks: List<T>,
    ownedMovieIds: Set<Long>,
    idOf: (T) -> Long
): List<T> = if (ownedMovieIds.isEmpty()) {
    tasks
} else {
    tasks.filterNot { idOf(it) in ownedMovieIds }
}

internal suspend fun processMovieScrapeTask(
    markRunning: suspend () -> Unit,
    runScrape: suspend () -> Unit,
    markPending: suspend () -> Unit,
    markFailed: suspend (Throwable) -> Unit,
    runningStatusTimeoutMillis: Long = RUNNING_STATUS_TIMEOUT_MS,
    onRunningStatusTimeout: suspend () -> Unit = {},
    scrapeTimeoutMillis: Long = MOVIE_SCRAPE_TIMEOUT_MS,
    onScrapeTimeout: suspend () -> Unit = {}
): MovieScrapeTaskOutcome {
    try {
        withTimeout(runningStatusTimeoutMillis) { markRunning() }
    } catch (_: TimeoutCancellationException) {
        onRunningStatusTimeout()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        markFailed(error)
        return MovieScrapeTaskOutcome.Failed
    }
    return try {
        withTimeout(scrapeTimeoutMillis) { runScrape() }
        MovieScrapeTaskOutcome.Completed
    } catch (_: TimeoutCancellationException) {
        val error = IllegalStateException("单个影片刮削超过 ${scrapeTimeoutMillis / 1_000} 秒")
        markFailed(error)
        onScrapeTimeout()
        MovieScrapeTaskOutcome.Failed
    } catch (error: CancellationException) {
        withContext(NonCancellable) { markPending() }
        throw error
    } catch (error: Throwable) {
        markFailed(error)
        MovieScrapeTaskOutcome.Failed
    }
}

internal enum class MovieScrapeTaskOutcome {
    Completed,
    Failed
}

private const val RUNNING_STATUS_TIMEOUT_MS = 5_000L
private const val MOVIE_SCRAPE_TIMEOUT_MS = 5L * 60L * 1_000L

/**
 * 账本占位失败的哨兵值。**故意与 `CloudFolderBatchTaskRunner` 里的同名常量各自 private**：
 * 两个值的含义完全一致（-1 表示「这条 run 不存在」），但让它们共享一个 internal 常量会
 * 把两个互不相干的 runner 绑在一起，将来一边改语义（例如允许 0）就会静默污染另一边。
 */
private const val NO_LEDGER_RUN = -1L

/** 批量刮削在整个账本里只占一行：同键互斥的是「一次批量」，不是「某一部影片」。 */
private const val LEDGER_SCOPE_BATCH = "batch"

internal suspend fun <T> runMovieBatchWorkers(
    tasks: List<T>,
    concurrency: Int,
    processor: suspend (index: Int, task: T) -> Unit
) {
    require(concurrency > 0) { "concurrency must be positive" }
    if (tasks.isEmpty()) return
    val nextIndex = AtomicInteger(0)
    val workerCount = minOf(concurrency, tasks.size)
    coroutineScope {
        repeat(workerCount) {
            launch {
                while (true) {
                    val index = nextIndex.getAndIncrement()
                    if (index >= tasks.size) return@launch
                    processor(index, tasks[index])
                }
            }
        }
    }
}

data class MovieScrapeTaskRunnerState(
    val isRunning: Boolean = false,
    val total: Int = 0,
    val current: Int = 0,
    val success: Int = 0,
    val failed: Int = 0,
    val message: String = ""
)
