package com.example.localmovielibrarz.data.repository

import com.example.localmovielibrarz.data.local.MovieEntity
import com.example.localmovielibrarz.metadata.Action
import com.example.localmovielibrarz.metadata.MetadataAuthority
import com.example.localmovielibrarz.metadata.MetadataFieldKey
import com.example.localmovielibrarz.metadata.MetadataGapPatch
import com.example.localmovielibrarz.metadata.MetadataMergeEngine
import com.example.localmovielibrarz.scraper.ScrapeSource
import com.example.localmovielibrarz.tasks.TaskKind
import com.example.localmovielibrarz.tasks.TaskState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope

/**
 * 「元数据补全」任务的执行者：给**已刮削过但缺文本字段**的影片补上空位。
 *
 * 与既有三套任务的分工（这是本类存在的理由，也是它刻意不复用 `MovieScrapeTaskRunner` 的原因）：
 * - `MovieScrapeTaskRunner`：处理**没刮过 / 刮失败**的影片，产出 NFO、海报、剧集信息，是"从无到有"；
 * - `CloudFolderBatchTaskRunner`：网盘文件夹 → 入库 → 触发刮削，是"入库流水线"；
 * - 本类：只补 `actors / studios / genres / plot` 这类**纯文本空位**，不写文件、不下载图片、
 *   不重写 NFO、不建新影片行。这是"锦上添花"。
 *
 * 为什么可以纯文本、不碰文件：缺这些字段通常不代表刮削失败（很多站点本来就不给某些字段），
 * 用户看到的是详情页空着。用完整的重新刮削去补，代价是重写 NFO + 重下头像 + 可能改名，
 * 风险和副作用远大于收益；所以这里只做「抓一次元数据 → 逐字段决策 → 填空」。
 */
class MovieMetadataRefreshRunner(
    private val movieRepository: MovieRepository,
    private val scrapeRepository: StrmScrapeRepository,
    private val settingsRepository: AppSettingsRepository,
    private val metadataLedger: MovieMetadataLedgerRepository? = null,
    private val taskRunRepository: TaskRunRepository? = null,
    private val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO
) {
    private val _state = MutableStateFlow(MetadataRefreshState())
    val state: StateFlow<MetadataRefreshState> = _state

    private var job: kotlinx.coroutines.Job? = null
    private var cancelRequested = false

    /** 是否正在跑。UI 用它决定按钮的可点状态，避免用户连点发起两轮。 */
    val isRunning: Boolean get() = _state.value.isRunning

    fun start(scope: CoroutineScope) {
        if (_state.value.isRunning) return
        cancelRequested = false
        job = scope.launch {
            runJob()
        }
    }

    /**
     * 请求停止。
     *
     * 语义与其它 runner 一致：取消 = 「已经补的算数，剩下的不补了」，因此落 `Cancelled`
     * 而不是 `Failed`——用户亲手停掉的任务显示成"失败"会让人以为出了错。
     */
    fun cancel() {
        cancelRequested = true
        job?.cancel(CancellationException("已手动停止元数据补全"))
    }

    private suspend fun runJob() {
        val ledgerRunId = claimLedgerRun()
        var settled = false
        try {
            runInner(ledgerRunId)
            finishLedgerRun(ledgerRunId, TaskState.Succeeded, null)
            settled = true
        } catch (error: CancellationException) {
            finishLedgerRun(
                ledgerRunId,
                if (cancelRequested) TaskState.Cancelled else TaskState.Paused,
                if (cancelRequested) "已手动停止" else "被中断，下次补全时会重新扫描缺口"
            )
            settled = true
            // ★ 必须把内存状态也落成「不在跑」。
            //
            // 取消是协程级的硬中断：`runInner` 里那条「全部完成 → isRunning=false」的赋值
            // 永远不会被执行到。少了这一步，界面会永久停在「正在补全 3/40」，用户点「停止」
            // 得不到任何反馈，而且 `start()` 开头的 `if (isRunning) return` 会把之后每一次
            // 点击都吃掉——表现就是「停止后一直卡在那个状态，没法再开始」。
            //
            // 进度保留（current/total 不清零）：那一批里已经写进去的字段是真的落库了，
            // 清零反而让用户以为白跑一趟。文案要区分「用户停的」与「被系统中断的」，
            // 因为后者下次会自动重新扫描缺口，而前者需要用户自己再按一次开始。
            val interruptedTotal = _state.value.total
            val interruptedCurrent = _state.value.current
            _state.update {
                it.copy(
                    isRunning = false,
                    message = if (cancelRequested) {
                        "已停止补全：已处理 $interruptedCurrent/$interruptedTotal 部，已写入的字段保留"
                    } else {
                        "补全被中断：已处理 $interruptedCurrent/$interruptedTotal 部，下次开始会重新扫描缺口"
                    }
                )
            }
            throw error
        } catch (error: Throwable) {
            val message = error.message ?: error::class.java.simpleName
            finishLedgerRun(ledgerRunId, TaskState.Failed, message)
            settled = true
            _state.update { it.copy(isRunning = false, message = "补全失败：$message") }
        } finally {
            // 兜底：正常/异常分支都没落终态时（例如 finish 自己抛了），至少别把 run 留在 Running——
            // 留着的 Running 会被冷启动恢复当成"被中断"，在任务列表里显示成一条永不结束的记录。
            if (!settled) {
                withContext(NonCancellable) {
                    finishLedgerRun(ledgerRunId, TaskState.Paused, "处理被中断，下次继续")
                }
            }
        }
    }

    private suspend fun runInner(ledgerRunId: Long) = coroutineScope {
        val movies = movieRepository.getMetadataGapMovies()
        if (movies.isEmpty()) {
            _state.value = MetadataRefreshState(message = "没有需要补全的影片")
            return@coroutineScope
        }
        val source = settingsRepository.getDefaultScrapeSource()
        val total = movies.size
        _state.value = MetadataRefreshState(isRunning = true, total = total, current = 0)
        scrapeRepository.appendLog("[元数据补全] 开始：共 $total 部待补全，来源=${source.label}")

        // 并发 2：与批量刮削保持一致。补全走的是同一批刮削源，
        // 并发开大只会更快撞上站点的限流/验证页，反而把整体成功率拉低。
        val semaphore = Semaphore(METADATA_REFRESH_CONCURRENCY)
        var completed = 0
        var succeeded = 0
        var failed = 0
        var patchedFields = 0
        var protectedFields = 0

        val workerJobs = movies.map { movie ->
            launch {
                semaphore.withPermit {
                    val label = movie.title.ifBlank { movie.videoName }
                    _state.update { it.copy(current = completed, message = "正在补全：$label") }
                    val result = runCatching { refreshOne(movie, source) }
                    val outcome = result.getOrNull()
                    if (outcome == null) {
                        failed += 1
                        val reason = result.exceptionOrNull()?.message ?: "未知原因"
                        scrapeRepository.appendLog("[元数据补全] 失败：$label，原因：$reason")
                    } else {
                        if (outcome.appliedCount == 0 && outcome.protectedCount == 0) {
                            // 抓到了但没有任何字段可写：不是失败，但也不该算成"补全成功"，
                            // 否则用户会以为补了东西却看不到变化。单独计数并在结尾提示。
                            scrapeRepository.appendLog("[元数据补全] 无新字段：$label（站点未返回缺失字段）")
                            failed += 1
                        } else {
                            succeeded += 1
                            patchedFields += outcome.appliedCount
                            protectedFields += outcome.protectedCount
                        }
                    }
                    completed += 1
                    _state.update { it.copy(current = completed) }
                    recordLedgerProgress(runId = ledgerRunId, completed = completed, total = total, step = label)
                }
            }
        }
        workerJobs.forEach { it.join() }

        val summary = "补全完成：成功 $succeeded，失败 $failed，共写入 $patchedFields 个字段" +
            if (protectedFields > 0) "，$protectedFields 个字段因手改保护未覆盖" else ""
        scrapeRepository.appendLog("[元数据补全] $summary")
        _state.value = MetadataRefreshState(
            isRunning = false,
            total = total,
            current = completed,
            success = succeeded,
            failed = failed,
            patchedFields = patchedFields,
            protectedFields = protectedFields,
            message = summary
        )
    }

    /**
     * 补一部影片：抓取 → 逐字段决策 → 只写放行字段。
     *
     * 逐字段走 [MovieMetadataLedgerRepository.decideIncoming] 而不是整块覆盖，是为了尊重手改：
     * 用户在详情页把片名改成自己的叫法后，补全任务不该把它换回站点原名。
     * 账本不可用时（迁移未完成 / 异常）退化为"只填空位"，也就是最保守的策略。
     */
    private suspend fun refreshOne(movie: MovieEntity, source: ScrapeSource) =
        withContext(ioDispatcher) {
            val info = scrapeRepository.refreshMetadataOnly(movie, source)
            val patch = MetadataGapPatch(
                title = info.title,
                originalTitle = info.originalTitle,
                plot = info.plot,
                year = info.year.toIntOrNull(),
                premiered = info.premiered,
                runtimeMinutes = info.runtime.toIntOrNull(),
                studios = listOfNotNull(info.studio.takeIf { it.isNotBlank() }),
                series = info.series,
                directors = info.directors,
                actors = info.actors,
                genres = info.genres,
                tags = info.tags,
                rating = info.rating.toDoubleOrNull()
            )
            if (patch.isEmpty()) {
                return@withContext MetadataGapFillSummary(appliedCount = 0, protectedCount = 0)
            }

            val applied = mutableListOf<MetadataFieldKey>()
            val protected = mutableListOf<MetadataFieldKey>()
            val candidates = patch.candidates()
            val ledger = metadataLedger
            if (ledger == null) {
                // 账本不可用：只填空位。currentValue 传当前值，引擎在"无当前值"时才放行。
                candidates.forEach { (field, value) ->
                    val isBlank = movie.currentValueOf(field).isNullOrBlank()
                    if (isBlank) applied += field
                }
            } else {
                candidates.forEach { (field, value) ->
                    val decision = runCatching {
                        ledger.decideIncoming(
                            movieId = movie.id,
                            field = field,
                            currentValue = movie.currentValueOf(field),
                            incomingValue = value,
                            incomingAuthority = MetadataAuthority.Provider,
                            sourceLabel = info.source.ifBlank { source.label }
                        )
                    }.getOrNull()
                    // 决策缺失（账本抛错）按"空位可写"处理：宁可多补一个空字段，也不要因为一次
                    // 查询失败把整批影片的补全静默跳过去。非 Apply 的决策一律视为"尊重现值"。
                    when (decision?.action) {
                        null, Action.Apply -> applied += field
                        else -> protected += field
                    }
                }
            }

            val written = movieRepository.applyMetadataGapPatch(movie.id, patch, applied.toSet())
            MetadataGapFillSummary(appliedCount = written, protectedCount = protected.size)
        }

    private suspend fun claimLedgerRun(): Long {
        val ledger = taskRunRepository ?: return NO_LEDGER_RUN
        return runCatching {
            ledger.claim(
                kind = TaskKind.MetadataRefresh,
                scope = LEDGER_SCOPE_BATCH,
                title = "元数据补全",
                totalSteps = 0
            )?.id ?: run {
                scrapeRepository.appendLog("元数据补全已在处理中，本次不重复发起")
                NO_LEDGER_RUN
            }
        }.getOrElse { error ->
            scrapeRepository.appendLog("任务账本不可用，本次不记账：${error.message ?: error::class.java.simpleName}")
            NO_LEDGER_RUN
        }
    }

    private suspend fun recordLedgerProgress(runId: Long, completed: Int, total: Int, step: String) {
        // 进度写入失败不影响补全本身：账本是观测层，不是数据通路。
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

    private suspend fun finishLedgerRun(runId: Long, state: TaskState, message: String?) {
        if (runId == NO_LEDGER_RUN) return
        val ledger = taskRunRepository ?: return
        runCatching {
            if (state == TaskState.Paused) ledger.notifyPaused(runId, message)
            else ledger.finish(runId, state, message)
        }
    }

    private companion object {
        const val METADATA_REFRESH_CONCURRENCY = 2
        const val LEDGER_SCOPE_BATCH = "batch"
        const val NO_LEDGER_RUN = -1L
    }
}

/** 一部影片的补全结果：写了几个字段、几个字段被手改保护挡下。 */
data class MetadataGapFillSummary(val appliedCount: Int, val protectedCount: Int)

/** 补全任务的对外状态，供设置页按钮与进度文案使用。 */
data class MetadataRefreshState(
    val isRunning: Boolean = false,
    val total: Int = 0,
    val current: Int = 0,
    val success: Int = 0,
    val failed: Int = 0,
    val patchedFields: Int = 0,
    val protectedFields: Int = 0,
    val message: String = ""
)

/** 按字段枚举读出实体里的当前文本值（多值字段用 U+001F 串接，与候选值同一表示）。 */
private fun MovieEntity.currentValueOf(field: MetadataFieldKey): String? = when (field) {
    MetadataFieldKey.Title -> title
    MetadataFieldKey.OriginalTitle -> originalTitle
    MetadataFieldKey.Plot -> plot
    MetadataFieldKey.Outline -> outline
    MetadataFieldKey.Year -> year?.toString()
    MetadataFieldKey.Premiered -> premiered
    MetadataFieldKey.Runtime -> runtimeMinutes?.toString()
    MetadataFieldKey.Mpaa -> mpaa
    MetadataFieldKey.Studios -> studios.joinToString("\u001F")
    MetadataFieldKey.Series -> series
    MetadataFieldKey.Directors -> directors.joinToString("\u001F")
    MetadataFieldKey.Actors -> actors.joinToString("\u001F")
    MetadataFieldKey.Genres -> genres.joinToString("\u001F")
    MetadataFieldKey.Tags -> tags.joinToString("\u001F")
    MetadataFieldKey.Rating -> rating?.toString()
    // 图片列存的是本地文件 Uri，补全任务不碰图片，因此对它们恒返回 null（引擎会认为是空槽，
    // 但 patch 里本来也不会带这三个字段，所以不会走到写库）。
    MetadataFieldKey.Poster, MetadataFieldKey.Fanart, MetadataFieldKey.Thumb -> null
}
