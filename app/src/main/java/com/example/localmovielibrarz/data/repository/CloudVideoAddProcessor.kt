package com.example.localmovielibrarz.data.repository

import android.net.Uri
import com.example.localmovielibrarz.cloud115.Cloud115FileItem
import com.example.localmovielibrarz.data.local.CloudVideoTaskEntity
import com.example.localmovielibrarz.scraper.MovieNumberExtractor
import com.example.localmovielibrarz.tasks.TaskKind
import com.example.localmovielibrarz.tasks.TaskState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class CloudVideoAddProcessor(
    private val strmRepository: Cloud115StrmRepository,
    private val recordRepository: CloudStrmRecordRepository,
    private val settingsRepository: AppSettingsRepository,
    private val movieRepository: MovieRepository,
    private val scrapeRepository: StrmScrapeRepository,
    /** 统一任务账本；默认 null 让既有构造点与单测无需立刻跟进，装配点在 `AppContainer`。 */
    private val taskRunRepository: TaskRunRepository? = null
) {
    private val addLock = Mutex()

    suspend fun process(task: CloudVideoTaskEntity) {
        process(
            item = task.toCloudFileItem(),
            pickcode = task.pickcode,
            forceDistinct = task.forceDistinct
        )
    }

    /** Single-video and folder imports share this complete import-and-scrape pipeline. */
    suspend fun process(
        item: Cloud115FileItem,
        pickcode: String,
        forceDistinct: Boolean
    ) {
        val lockKey = extractMovieNumberWithRules(item.name)?.uppercase()
            ?: item.name.trim().lowercase()
        val lock = addLock
        if (lock.isLocked) {
            scrapeRepository.appendLog("同番号持久化任务等待整理完成：$lockKey")
        }
        lock.withLock {
            processLocked(item, pickcode, forceDistinct)
        }
    }

    private suspend fun processLocked(
        item: Cloud115FileItem,
        pickcode: String,
        forceDistinct: Boolean
    ) {
        // 单视频入库在账本里按 pickcode 记一行：同一个 pickcode 只应有一条活跃 run，
        // 而不同 pickcode（用户连续拖入十部）应当各记各的，设置页才能看到「十个单视频入库」。
        // 占不到不阻断——真正的互斥由 `addLock` 与队列的 `claimNext` 保证，账本只负责记录。
        val ledgerRunId = claimLedgerRun(item = item, pickcode = pickcode)
        var ledgerSettled = false
        try {
            runLockedImport(item, pickcode, forceDistinct)
            finishLedgerRun(ledgerRunId, TaskState.Succeeded, null)
            ledgerSettled = true
        } catch (error: CancellationException) {
            finishLedgerRun(ledgerRunId, TaskState.Paused, "已暂停，下次继续处理")
            ledgerSettled = true
            throw error
        } catch (error: Throwable) {
            finishLedgerRun(
                ledgerRunId,
                TaskState.Failed,
                error.message ?: error::class.java.simpleName
            )
            ledgerSettled = true
            throw error
        } finally {
            // 兜底：任何没走到上面三个分支的退出（例如 Error）都不能让 run 永远停在 Running，
            // 否则冷启动的 recoverInterrupted 会把它当成「被中断的任务」反复提示用户。
            if (!ledgerSettled) {
                withContext(NonCancellable) {
                    finishLedgerRun(ledgerRunId, TaskState.Paused, "处理被中断，下次继续")
                }
            }
        }
    }

    /**
     * 在 `task_runs` 里为单视频入库占位。
     *
     * 占不到（同 pickcode 已有活跃 run）或账本抛错时返回 [NO_LEDGER_RUN] 并**继续导入**：
     * 单视频入库是用户刚点完「加入」的即时动作，为了记账把它丢掉没有任何收益。
     * scope 用 pickcode 而不是文件名：文件名会随整理策略改写，pickcode 是 115 侧的稳定主键。
     */
    private suspend fun claimLedgerRun(item: Cloud115FileItem, pickcode: String): Long {
        val ledger = taskRunRepository ?: return NO_LEDGER_RUN
        return runCatching {
            ledger.claim(
                kind = TaskKind.VideoAdd,
                scope = pickcode,
                title = item.name,
                totalSteps = 1
            )?.id ?: run {
                scrapeRepository.appendLog("单视频入库已在处理中，本次不记账：${item.name} / $pickcode")
                NO_LEDGER_RUN
            }
        }.getOrElse { error ->
            scrapeRepository.appendLog(
                "任务账本不可用，本次单视频入库不记账：${error.message ?: error::class.java.simpleName}"
            )
            NO_LEDGER_RUN
        }
    }

    private suspend fun finishLedgerRun(runId: Long, state: TaskState, message: String?) {
        if (runId == NO_LEDGER_RUN) return
        val ledger = taskRunRepository ?: return
        runCatching {
            if (state == TaskState.Paused) ledger.notifyPaused(runId, message)
            else {
                ledger.updateProgress(
                    id = runId,
                    state = TaskState.Running,
                    step = message ?: "处理完成",
                    completedSteps = 1,
                    totalSteps = 1
                )
                ledger.finish(runId, state, message)
            }
        }
    }

    private suspend fun runLockedImport(
        item: Cloud115FileItem,
        pickcode: String,
        forceDistinct: Boolean
    ) {
        scrapeRepository.appendLog("开始处理持久化网盘任务：${item.name} / $pickcode")
        val generated = strmRepository.generateStrmForVideo(item, forceDistinct = forceDistinct)
        val libraryRoot = settingsRepository.getLibraryRootUri()
            ?: error("请先到设置页选择影片库目录")
        val rootUri = Uri.parse(libraryRoot)

        if (!generated.shouldScrape) {
            scrapeRepository.appendLog("附加播放源 STRM 写入完成，不单独入库：${generated.fileName}")
            return
        }

        scrapeRepository.appendLog("STRM 写入完成，开始扫描单个 STRM：${generated.fileName}")
        val addedMovie = movieRepository.scanSingleMovie(
            rootUri,
            Uri.parse(generated.strmUri),
            mergeByMovieNumber = !generated.forceDistinct
        )
        addedMovie?.let { recordRepository.attachMovie(pickcode, it.id) }

        val number = extractMovieNumberWithRules(generated.fileName, item.name)
        if (number == null) {
            val reason = "STRM 已添加，但无法从文件名提取番号，无法自动刮削"
            addedMovie?.let { movieRepository.markScrapeTaskFailed(it.id, reason) }
            error(reason)
        }
        val movieForScrape = addedMovie
            ?: movieRepository.findMovieByNumberAndVariant(libraryRoot, number, generated.fileName)
            ?: error("STRM 已添加，但扫描后没有在影片库中找到 $number")
        recordRepository.attachMovie(pickcode, movieForScrape.id)
        movieRepository.markScrapeTaskPending(movieForScrape.id)

        val source = settingsRepository.getDefaultScrapeSource()
        movieRepository.markScrapeTaskRunning(movieForScrape.id)
        scrapeRepository.appendLog("开始刮削持久化网盘任务：$number，来源：$source")
        val scrapeResult = try {
            scrapeRepository.scrapeStrmUriWithOutput(
                libraryRootUri = libraryRoot,
                strmUri = generated.strmUri,
                source = source,
                forceDistinct = generated.forceDistinct
            )
        } catch (error: CancellationException) {
            withContext(NonCancellable) {
                movieRepository.markScrapeTaskPending(movieForScrape.id)
            }
            throw error
        } catch (error: Throwable) {
            val reason = "刮削来源 ${source.name} 失败：${error.message ?: error::class.java.simpleName}"
            movieRepository.markScrapeTaskFailed(movieForScrape.id, reason)
            throw IllegalStateException(reason, error)
        }

        scrapeRepository.appendLog("文件整理完成，开始同步影片数据库：$number")
        val refreshedMovie = finalizeOrganizedScrape(
            refreshMovie = {
                movieRepository.refreshMovieAfterScrape(
                    original = movieForScrape,
                    scrapedStrmUri = scrapeResult.strmUri,
                    mergeByMovieNumber = !generated.forceDistinct
                )
            },
            markCompleted = { movieRepository.markScrapeTaskCompleted(it.id) },
            markFailed = { reason -> movieRepository.markScrapeTaskFailed(movieForScrape.id, reason) },
            missingMovieMessage = "整理后的 STRM 已写入，但影片路径或元数据未同步到数据库：$number"
        )
        recordRepository.updateStrmLocation(
            pickcode = pickcode,
            strmUri = refreshedMovie.videoUri,
            libraryRootUri = refreshedMovie.libraryRootUri,
            movieId = refreshedMovie.id
        )
        scrapeRepository.appendLog(
            "[媒体路径] 状态=成功，操作=网盘影片刮削整理并更新数据库，movieId=${refreshedMovie.id}，文件=${refreshedMovie.videoName}，详情=影片表和 STRM 索引表已同步"
        )
        scrapeRepository.appendLog("持久化网盘任务完成：$number / $pickcode")
    }

    private suspend fun extractMovieNumberWithRules(vararg values: String?): String? {
        scrapeRepository.loadCachedNumberRecognitionRules()
        values.mapNotNull { it?.takeIf(String::isNotBlank) }.forEach { value ->
            MovieNumberExtractor.extract(value)?.let { return it }
        }
        return null
    }
}

private fun CloudVideoTaskEntity.toCloudFileItem(): Cloud115FileItem =
    Cloud115FileItem(
        name = fileName,
        cid = cid,
        fid = fid,
        pickcode = pickcode,
        size = size,
        addedAt = addedAt,
        modifiedAt = modifiedAt,
        isDirectory = false
    )

internal suspend fun <T : Any> finalizeOrganizedScrape(    refreshMovie: suspend () -> T?,
    markCompleted: suspend (T) -> Unit,
    markFailed: suspend (String) -> Unit,
    missingMovieMessage: String
): T {
    return try {
        val refreshedMovie = refreshMovie() ?: error(missingMovieMessage)
        markCompleted(refreshedMovie)
        refreshedMovie
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        markFailed(error.message?.takeIf(String::isNotBlank) ?: missingMovieMessage)
        throw error
    }
}

/**
 * 账本占位失败的哨兵值。与 `CloudFolderBatchTaskRunner` / `MovieScrapeTaskRunner` 里的同名常量
 * 各自 private：值相同但语义各自演进，共享常量会让一边改语义时静默污染另一边。
 */
private const val NO_LEDGER_RUN = -1L
