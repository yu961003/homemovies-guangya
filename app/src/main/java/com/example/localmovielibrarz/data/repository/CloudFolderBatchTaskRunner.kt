package com.example.localmovielibrarz.data.repository

import android.net.Uri
import com.example.localmovielibrarz.cloud115.Cloud115FileItem
import com.example.localmovielibrarz.data.local.CloudFolderBatchTaskEntity
import com.example.localmovielibrarz.data.local.CloudPickcodeOutcome
import com.example.localmovielibrarz.data.local.CloudPickcodeProgressCodec
import com.example.localmovielibrarz.scraper.MovieNumberExtractor
import com.example.localmovielibrarz.tasks.TaskKind
import com.example.localmovielibrarz.tasks.TaskState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random
import com.example.localmovielibrarz.util.RetryBackoff
import com.example.localmovielibrarz.util.VideoFormats

class CloudFolderBatchTaskRunner(
    private val taskRepository: CloudFolderBatchTaskRepository,
    private val strmRepository: Cloud115StrmRepository,
    private val recordRepository: CloudStrmRecordRepository,
    private val settingsRepository: AppSettingsRepository,
    private val movieRepository: MovieRepository,
    private val scrapeRepository: StrmScrapeRepository,
    private val videoAddProcessor: CloudVideoAddProcessor,
    private val cloudVideoTaskRunner: CloudVideoTaskRunner,
    /**
     * 统一任务账本（`task_runs`）。
     *
     * 与 `CloudFolderBatchTaskRepository` 是「业务进度」与「任务生命周期」两层记录：
     * 前者存每个任务待处理的 pickcode 集合，后者只回答「这类任务现在有没有在跑、卡在哪、为什么停」。
     * 设成可空，是为了让不接 DB 的单元测试仍能构造 runner（记账本身不是业务前置条件）。
     */
    private val taskRunRepository: TaskRunRepository? = null
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val runMutex = Mutex()
    private val addLock = Mutex()
    /** ★ W4：子文件夹列举失败后的退避节奏（2s → 4s → ... 上限 30s，带 ±30% 抖动）。 */
    private val childFolderRetryBackoff = RetryBackoff(
        baseMillis = CHILD_FOLDER_RETRY_DELAY_MIN_MS,
        maxMillis = CHILD_FOLDER_RETRY_BACKOFF_MAX_MS,
        multiplier = 2.0,
        jitterRatio = 0.3
    )
    private val _isRunning = MutableStateFlow(false)
    private var job: Job? = null
    private var currentTaskId: Long? = null
    private var cancelRequested = false

    val isRunning: StateFlow<Boolean> = _isRunning

    fun start() = launchRun(poolPausedRows = true)

    /**
     * 单条任务「继续」：只把这一行放回 `Pending`，不动其它 Paused / Failed / Cancelled。
     *
     * 对照组是 [start]：它先跑 `prepareForStart()`，那条 SQL 会把**所有**暂停/失败/取消的行
     * 一起推回 Pending。用户在某一行的「继续」按钮上点一下却把旁边几个手动暂停的任务一起
     * 唤醒，是明显的越权副作用，所以这里必须走另一条入口。
     */
    suspend fun resumeTask(taskId: Long) {
        taskRepository.markPending(taskId)
        launchRun(poolPausedRows = false)
    }

    /**
     * 暂停某一条任务。
     *
     * 当前正在跑的这一条没有「只停它、别的继续」的轻量实现——runner 是单协程顺序处理，
     * 取消就是取消这个协程。所以做法是：停掉这一轮（终态由 `runPendingTasks` 的取消分支
     * 落成 `Paused`），再重新起一轮让**剩下的 Pending** 继续，等价于「暂停这一条，队列不塌」。
     * 非当前任务（排在后面还没轮到）直接改库状态即可，它本来就不会被 `nextRunnableTask` 捡走。
     */
    suspend fun pauseTask(taskId: Long) {
        if (currentTaskId == taskId) {
            val running = job
            stop()
            runCatching { running?.join() }
            launchRun(poolPausedRows = false)
            return
        }
        taskRepository.markPaused(taskId, PAUSE_MESSAGE)
        scrapeRepository.appendLog("网盘文件夹任务已暂停（排队中）：$taskId")
    }

    /**
     * 取消某一条任务。
     *
     * 与 [pauseTask] 的差别只在终态：`Cancelled` 是「这条不用再跑了」，不会再被
     * `prepareForStart()` 之外的任何路径唤醒；`Paused` 是「先停一下，等下续」。
     * 当前正在跑的那条同样要重启队列，避免一条任务被取消后把后面排队的全饿死。
     */
    suspend fun cancelTask(taskId: Long) {
        if (currentTaskId == taskId) {
            val running = job
            cancel()
            runCatching { running?.join() }
            launchRun(poolPausedRows = false)
            return
        }
        taskRepository.markCancelled(taskId, CANCEL_MESSAGE)
        scrapeRepository.appendLog("网盘文件夹任务已取消（排队中）：$taskId")
    }

    /**
     * @param poolPausedRows true = 先把所有暂停/失败/取消的行放回 Pending（「开始全部」语义）；
     *   false = 只跑当前库里已经是 Pending 的行（单条继续 / 暂停后维持队列）。
     */
    private fun launchRun(poolPausedRows: Boolean) {
        if (job?.isActive == true) return
        cancelRequested = false
        job = scope.launch {
            runMutex.withLock {
                if (poolPausedRows) taskRepository.prepareForStart()
                _isRunning.value = true
                try {
                    runPendingTasks()
                } finally {
                    currentTaskId = null
                    _isRunning.value = false
                }
            }
        }
    }

    /**
     * 冷启动自动续跑（F-5）。
     *
     * 只有当库里存在 `Running` 残留（进程被杀，任务没来得及写终态）时才自动开跑；
     * 用户主动 Paused / Failed / Cancelled 的任务不会被悄悄唤醒——那种「打开 App 就自己跑起来」
     * 的行为比丢进度更让人意外。
     */
    fun resumeInterruptedIfNeeded() {
        if (job?.isActive == true) return
        scope.launch {
            val interrupted = runCatching { taskRepository.countInterrupted() }.getOrDefault(0)
            if (interrupted <= 0) return@launch
            scrapeRepository.appendLog("检测到 $interrupted 个被中断的网盘文件夹任务，自动续跑")
            start()
        }
    }

    fun stop() {
        cancelRequested = false
        job?.cancel(CancellationException("已手动停止文件夹刮削任务"))
    }

    fun cancel() {
        cancelRequested = true
        job?.cancel(CancellationException("已手动取消文件夹刮削任务"))
    }

    /** 账本落账结果：用局部变量区分「跑完了」与「中途失败」，避免在 finally 里重复写终态。 */
    private enum class LedgerOutcome { Pending, Succeeded, Failed }

    /**
     * 在 `task_runs` 里为某个文件夹任务占位，返回 run id；占不到返回 null。
     *
     * dedupe scope 用 folderCid 而不是 task.id：用户可能对同一个网盘文件夹反复建任务，
     * 真正要互斥的是「这个文件夹正在被处理」，而不是「这条任务行正在被处理」。
     */
    private suspend fun claimLedgerRun(task: CloudFolderBatchTaskEntity): Long? {
        val ledger = taskRunRepository ?: return NO_LEDGER_RUN
        return runCatching {
            ledger.claim(
                kind = TaskKind.FolderBatch,
                scope = task.folderCid.toString(),
                title = task.folderName,
                totalSteps = 0
            )?.id ?: run {
                scrapeRepository.appendLog("文件夹任务已在处理中，跳过：${task.folderName} / ${task.folderCid}")
                null
            }
        }.getOrElse { error ->
            // 记账不可用（迁移未完成 / DB 异常）不该阻断真正的刮削：降级为「不记账继续跑」。
            scrapeRepository.appendLog("任务账本不可用，本次不记账：${error.message ?: error::class.java.simpleName}")
            NO_LEDGER_RUN
        }
    }

    /**
     * 落终态。
     *
     * 这里必须分两路：`finish()` 内部 `require(state.isTerminal)`，而 `Paused` 不是终态
     * （暂停后还能续跑，语义上「没出错，只是没在跑」），传进去会被直接拒掉——
     * 记账失败被 runCatching 吞掉虽然不会崩，但设置页会一直显示这条 run 处于 Running，
     * 冷启动的「中断恢复」就会把它当成失联任务去接管，等于把用户的暂停给悄悄改写了。
     */
    private suspend fun finishLedgerRun(runId: Long, state: TaskState, message: String?) {
        if (runId == NO_LEDGER_RUN) return
        val ledger = taskRunRepository ?: return
        runCatching {
            if (state == TaskState.Paused) ledger.notifyPaused(runId, message)
            else ledger.finish(runId, state, message)
        }
    }

    private suspend fun runPendingTasks() {
        while (true) {
            val task = taskRepository.nextRunnableTask() ?: return
            currentTaskId = task.id
            // 账本占位：同一文件夹同一时刻只允许一条活跃 run。占不到说明已有协程在跑这个文件夹
            // （例如上一轮进程没退干净），此时继续跑会让两路同时写同一批 pickcode，直接跳过。
            val ledgerRunId = claimLedgerRun(task) ?: continue
            var ledgerState: LedgerOutcome = LedgerOutcome.Pending
            runCatching {
                runTask(task)
                ledgerState = LedgerOutcome.Succeeded
            }.onFailure { error ->
                if (error is CancellationException) {
                    // F-4：取消与暂停是两种语义，分别落库，设置页才能给出不同文案与操作。
                    if (cancelRequested) {
                        taskRepository.markCancelled(task.id, "已手动取消")
                        scrapeRepository.appendLog("网盘文件夹任务已取消：${task.folderName}")
                    } else {
                        taskRepository.markPaused(task.id, "已手动停止，再次开始会从中断处继续")
                        scrapeRepository.appendLog("网盘文件夹任务已暂停：${task.folderName}")
                    }
                    // 取消/暂停不是失败：账本要能看到「为什么停」，否则设置页只能显示一条莫名字段。
                    // 取消是终态（不会再自动继续），停止是占位态（下次开始从中断处续），
                    // 两者在账本里必须落成不同状态，否则设置页无法区分「该重新发起」与「点继续就行」。
                    finishLedgerRun(
                        ledgerRunId,
                        if (cancelRequested) TaskState.Cancelled else TaskState.Paused,
                        if (cancelRequested) "已手动取消" else "已手动停止"
                    )
                    ledgerState = LedgerOutcome.Failed
                    throw error
                }
                val message = error.message ?: error::class.java.simpleName
                taskRepository.markFailed(task.id, message)
                scrapeRepository.appendLog("网盘文件夹任务失败：${task.folderName} / ${task.folderCid}，原因：$message")
                finishLedgerRun(ledgerRunId, TaskState.Failed, message)
                ledgerState = LedgerOutcome.Failed
            }
            if (ledgerState == LedgerOutcome.Succeeded) {
                finishLedgerRun(ledgerRunId, TaskState.Succeeded, null)
            }
            currentTaskId = null
        }
    }

    private suspend fun runTask(task: CloudFolderBatchTaskEntity) {
        // ★ 断点来源：已处理 pickcode → 结局。命中即跳过，不再重复入库/刮削。
        val outcomes = CloudPickcodeProgressCodec.decode(task.processedPickcodesCsv)
        val summary = CloudFolderBatchRunSummary(folderName = task.folderName, outcomes = outcomes)
        val submittedPickcodes = linkedSetOf<String>()
        val candidates = mutableListOf<CloudFolderVideoCandidate>()
        val numberChecker = CloudAddNumberChecker()
        val folder = Cloud115FileItem(
            name = task.folderName,
            cid = task.folderCid,
            fid = null,
            pickcode = null,
            size = null,
            addedAt = null,
            modifiedAt = null,
            isDirectory = true
        )

        taskRepository.resumeForRun(task.id, summary.toProgress(task.folderName, null))
        if (outcomes.isNotEmpty()) {
            scrapeRepository.appendLog(
                "网盘文件夹任务续跑：${task.folderName}，已有断点 ${outcomes.size} 个 pickcode 不再重复处理"
            )
        } else {
            scrapeRepository.appendLog("网盘文件夹任务开始：${task.folderName} / ${task.folderCid}")
        }

        collectFolderVideoCandidates(
            taskId = task.id,
            folder = folder,
            path = task.folderName,
            excludedVideoNames = settingsRepository.getCloudExcludedVideoNames(),
            skipBelowSizeBytes = settingsRepository.getCloudScrapeSkipBelowSizeBytes(),
            candidates = candidates,
            summary = summary,
            numberChecker = numberChecker,
            delayBeforeRequest = false
        )
        summary.queuedCandidates = candidates.size
        persistProgress(task.id, summary, currentPath = task.folderName, currentFileName = null)
        scrapeRepository.appendLog(
            "网盘文件夹任务收集完成：${task.folderName}，候选=${summary.queuedCandidates}，跳过=${summary.skippedVideos}，" +
                "断点=${outcomes.size}，子文件夹失败=${summary.failedFolders}"
        )

        val existingPickcodes = recordRepository.existingPickcodesForVisibleItems(
            candidates.mapNotNull { it.item.pickcode?.takeIf(String::isNotBlank) }.toSet()
        )

        candidates.forEach { candidate ->
            val item = candidate.item
            val pickcode = item.pickcode?.takeIf(String::isNotBlank)
            persistProgress(task.id, summary, currentPath = candidate.path, currentFileName = item.name)
            if (pickcode == null) {
                summary.collectionSkipped += 1
                scrapeRepository.appendLog("网盘文件夹任务跳过：${candidate.fullPath}，原因：缺少 pickcode")
                persistProgress(task.id, summary, currentPath = candidate.path, currentFileName = item.name)
                return@forEach
            }
            if (outcomes.containsKey(pickcode)) {
                scrapeRepository.appendLog(
                    "网盘文件夹任务断点跳过：${candidate.fullPath}，上次结局：${outcomes[pickcode]}"
                )
                return@forEach
            }
            if (pickcode in existingPickcodes) {
                // 已在库里：记成 Skipped 落断点，下次续跑连查询都省了。
                recordOutcome(task.id, summary, pickcode, CloudPickcodeOutcome.Skipped, candidate)
                scrapeRepository.appendLog("网盘文件夹任务跳过：${candidate.fullPath}，原因：视频已添加")
                return@forEach
            }
            runCatching {
                withAddLock(item.name) {
                    processCloudVideoAddAllowScrapeFailure(
                        item = item,
                        pickcode = pickcode,
                        forceDistinct = false,
                        submittedPickcodes = submittedPickcodes
                    )
                }
            }.onSuccess {
                // 结局要等刮削队列跑到终态才知道（Added / ScrapeFailed），
                // 这里**不**提前写断点，否则中途被杀会把「未刮削」误记成「已完成」。
                persistProgress(task.id, summary, currentPath = candidate.path, currentFileName = item.name)
            }.onFailure { error ->
                if (error is CancellationException) throw error
                val message = error.message ?: error::class.java.simpleName
                summary.failedVideos += 1
                summary.rememberFailure("${item.name}: $message")
                scrapeRepository.appendLog("网盘文件夹任务失败：${item.name} / $pickcode，原因：$message")
                persistProgress(task.id, summary, currentPath = candidate.path, currentFileName = item.name)
            }
        }

        if (submittedPickcodes.isNotEmpty()) {
            cloudVideoTaskRunner.start()
            awaitSubmittedSettled(
                taskId = task.id,
                summary = summary,
                submittedPickcodes = submittedPickcodes,
                candidatesByPickcode = candidates
                    .mapNotNull { candidate -> candidate.item.pickcode?.let { it to candidate } }
                    .toMap()
            )
        }

        val message = summary.toUserMessage()
        if (summary.scrapeFailedVideos > 0 || summary.failedVideos > 0 || summary.failedFolders > 0) {
            taskRepository.markFailed(task.id, message)
            scrapeRepository.appendLog("网盘文件夹任务结束但存在失败：${task.folderName}，$message")
        } else {
            taskRepository.markCompleted(task.id, summary.toProgress(task.folderName, null), message)
            scrapeRepository.appendLog("网盘文件夹任务完成：${task.folderName}，$message")
        }
    }

    /**
     * 等这批已提交的影片跑到终态，**每落定一部就立刻写一次断点**。
     *
     * 为什么不用 `awaitTerminal` 一把等完：那一版要等最后一个 pickcode 也进终态才返回，
     * 于是「界面上那条进度条」在整段刮削期间一直停在 `0/N`，直到全部结束才突然跳到 100%。
     * 对用户来说这和没有进度没有区别——尤其是一百多个视频的文件夹，中途完全看不出是在跑
     * 还是已经卡死。改成轮询快照 + 逐条落库，进度就随刮削推进而增长，而且进程被杀时已
     * 落定的部分不会丢（这部分本来就已经入库，重跑只会重新扫一遍待处理的）。
     *
     * 超时是必要的兜底：任务行被别的入口清掉时 `snapshotSettled` 永远捞不到它，
     * 没有上限就会把整个文件夹批量任务吊死在这里。
     */
    private suspend fun awaitSubmittedSettled(
        taskId: Long,
        summary: CloudFolderBatchRunSummary,
        submittedPickcodes: Set<String>,
        candidatesByPickcode: Map<String, CloudFolderVideoCandidate>
    ) {
        val waiting = submittedPickcodes.toMutableSet()
        val deadline = System.currentTimeMillis() + SUBMIT_SETTLE_TIMEOUT_MS
        while (waiting.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val settled = runCatching { cloudVideoTaskRunner.snapshotSettled(waiting) }
                .getOrElse { error ->
                    if (error is CancellationException) throw error
                    scrapeRepository.appendLog(
                        "网盘文件夹任务读取刮削状态失败：${error.message ?: error::class.java.simpleName}"
                    )
                    emptyMap()
                }
            var recorded = 0
            settled.forEach { (pickcode, terminal) ->
                if (!waiting.remove(pickcode)) return@forEach
                val outcome = if (terminal.status == "Failed") {
                    summary.rememberFailure("${terminal.fileName}: ${terminal.failureReason.orEmpty()}")
                    CloudPickcodeOutcome.ScrapeFailed
                } else {
                    CloudPickcodeOutcome.Added
                }
                val candidate = candidatesByPickcode[pickcode]
                if (candidate == null) {
                    // 拿不到候选（理论上不该发生）也要落结局，否则这一部会被无限重试。
                    summary.outcomes[pickcode] = outcome
                    taskRepository.recordOutcome(
                        taskId = taskId,
                        pickcode = pickcode,
                        outcome = outcome,
                        progress = summary.toProgress(null, terminal.fileName)
                    )
                } else {
                    recordOutcome(taskId, summary, pickcode, outcome, candidate)
                }
                recorded += 1
            }
            if (recorded == 0) {
                if (System.currentTimeMillis() > deadline) {
                    scrapeRepository.appendLog(
                        "网盘文件夹任务等待刮削队列超时：${summary.folderName}，剩余 ${waiting.size} 部未落定"
                    )
                    waiting.forEach { pickcode ->
                        summary.rememberFailure("$pickcode: 等待刮削队列超时")
                    }
                    summary.failedVideos += waiting.size
                    waiting.clear()
                    break
                }
                delay(SUBMIT_SETTLE_POLL_MS)
            }
        }
    }

    private suspend fun collectFolderVideoCandidates(
        taskId: Long,
        folder: Cloud115FileItem,
        path: String,
        excludedVideoNames: Set<String>,
        skipBelowSizeBytes: Long,
        candidates: MutableList<CloudFolderVideoCandidate>,
        summary: CloudFolderBatchRunSummary,
        numberChecker: CloudAddNumberChecker,
        delayBeforeRequest: Boolean
    ) {
        val cid = folder.cid ?: run {
            summary.failedFolders += 1
            summary.rememberFailure("$path: 文件夹缺少 CID")
            persistProgress(taskId, summary, currentPath = path, currentFileName = null)
            return
        }
        persistProgress(taskId, summary, currentPath = path, currentFileName = null)
        val children = listFolderChildrenWithRetry(
            cid = cid,
            path = path,
            summary = summary,
            taskId = taskId,
            delayBeforeRequest = delayBeforeRequest
        ) ?: return

        children
            .asSequence()
            .filter { !it.isDirectory && it.isVideoFile() }
            .sortedBy { it.name.lowercase() }
            .forEach { item ->
                val reason = item.batchSkipReason(excludedVideoNames, skipBelowSizeBytes)
                    ?: if (numberChecker.extract(item.name) == null) "无法提取番号" else null
                if (reason == null) {
                    candidates += CloudFolderVideoCandidate(item = item, path = path)
                } else {
                    // 收集阶段的跳过是**每轮确定性重算**的（结果只取决于文件名/大小/策略），
                    // 因此不写断点、只记本轮计数，避免续跑时把同一批跳过重复累加进断点。
                    summary.collectionSkipped += 1
                    scrapeRepository.appendLog("网盘文件夹任务跳过：$path/${item.name}，原因：$reason")
                    persistProgress(taskId, summary, currentPath = path, currentFileName = item.name)
                }
            }

        children
            .asSequence()
            .filter { it.isDirectory && it.cid != null }
            .sortedBy { it.name.lowercase() }
            .forEach { child ->
                collectFolderVideoCandidates(
                    taskId = taskId,
                    folder = child,
                    path = "$path/${child.name}",
                    excludedVideoNames = excludedVideoNames,
                    skipBelowSizeBytes = skipBelowSizeBytes,
                    candidates = candidates,
                    summary = summary,
                    numberChecker = numberChecker,
                    delayBeforeRequest = true
                )
            }
    }

    private suspend fun listFolderChildrenWithRetry(
        cid: Long,
        path: String,
        summary: CloudFolderBatchRunSummary,
        taskId: Long,
        delayBeforeRequest: Boolean
    ): List<Cloud115FileItem>? {
        var attempt = 1
        while (true) {
            if (delayBeforeRequest) {
                delay(Random.nextLong(CHILD_FOLDER_LIST_DELAY_MIN_MS, CHILD_FOLDER_LIST_DELAY_MAX_MS + 1))
            }
            runCatching {
                return strmRepository.listFiles(cid)
            }.onFailure { error ->
                if (!delayBeforeRequest) throw error
                val message = error.message ?: error::class.java.simpleName
                if (attempt >= CHILD_FOLDER_LIST_MAX_ATTEMPTS) {
                    summary.failedFolders += 1
                    summary.rememberFailure("$path: $message")
                    scrapeRepository.appendLog("读取子文件夹失败：$path / $cid，已跳过该子文件夹，原因：$message")
                    persistProgress(taskId, summary, currentPath = path, currentFileName = null)
                    return null
                }
                // ★ W4：指数退避替代固定随机区间。固定 2~5 秒在「限流」场景下等于没退避：
                // 三次尝试挤在同一个窗口里打完，只会把限流再撞满一轮。
                val retryDelay = childFolderRetryBackoff.delayForAttempt(attempt)
                scrapeRepository.appendLog("读取子文件夹失败：$path / $cid，第 $attempt 次，${retryDelay / 1000.0} 秒后重试，原因：$message")
                delay(retryDelay)
                attempt += 1
            }
        }
    }

    private suspend fun recordOutcome(
        taskId: Long,
        summary: CloudFolderBatchRunSummary,
        pickcode: String,
        outcome: CloudPickcodeOutcome,
        candidate: CloudFolderVideoCandidate
    ) {
        summary.outcomes[pickcode] = outcome
        taskRepository.recordOutcome(
            taskId = taskId,
            pickcode = pickcode,
            outcome = outcome,
            progress = summary.toProgress(candidate.path, candidate.item.name)
        )
    }

    private suspend fun processCloudVideoAddAllowScrapeFailure(
        item: Cloud115FileItem,
        pickcode: String,
        forceDistinct: Boolean,
        submittedPickcodes: MutableSet<String>
    ): CloudFolderVideoAddResult {
        val task = cloudVideoTaskRunner.enqueue(item, forceDistinct)
        submittedPickcodes += task.pickcode
        return CloudFolderVideoAddResult(
            pickcode = pickcode,
            message = "已提交统一刮削队列：${item.name}"
        )
    }

    /**
     * Kept temporarily for source-history comparison only. Folder execution now uses
     * [CloudVideoAddProcessor], the same implementation as single-video imports.
     */
    @Suppress("unused")
    private suspend fun legacyProcessCloudVideoAddAllowScrapeFailure(
        item: Cloud115FileItem,
        pickcode: String,
        forceDistinct: Boolean
    ): CloudFolderVideoAddResult {
        scrapeRepository.appendLog("开始处理网盘文件夹任务：${item.name} / $pickcode")
        val generated = strmRepository.generateStrmForVideo(item, forceDistinct = forceDistinct)
        val libraryRoot = settingsRepository.getLibraryRootUri()
            ?: error("请先到设置页选择影片库目录")
        val rootUri = Uri.parse(libraryRoot)

        if (!generated.shouldScrape) {
            scrapeRepository.appendLog("附加播放源 STRM 写入完成，不单独刮削：${generated.fileName}")
            return CloudFolderVideoAddResult(
                pickcode = pickcode,
                message = "已加入播放源：${generated.movieNumberHint ?: item.name}"
            )
        }

        scrapeRepository.appendLog("网盘文件夹任务 STRM 写入完成，开始扫描：${generated.fileName}")
        val addedMovie = movieRepository.scanSingleMovie(rootUri, Uri.parse(generated.strmUri), mergeByMovieNumber = !generated.forceDistinct)
        addedMovie?.let { recordRepository.attachMovie(pickcode, it.id) }

        val number = extractMovieNumberWithRules(generated.fileName, item.name)
        if (number == null) {
            val reason = "无法从文件名提取番号，无法自动刮削"
            addedMovie?.let { movieRepository.markScrapeTaskFailed(it.id, reason) }
            scrapeRepository.appendLog("网盘文件夹任务未刮削：${item.name}，原因：$reason")
            return CloudFolderVideoAddResult(
                pickcode = pickcode,
                message = "已添加但未刮削：${item.name}",
                scrapeFailed = true
            )
        }

        val movieForScrape = addedMovie
            ?: movieRepository.findMovieByNumberAndVariant(libraryRoot, number, generated.fileName)
            ?: error("STRM 已添加，但扫描后没有在影片库中找到 $number")
        recordRepository.attachMovie(pickcode, movieForScrape.id)
        movieRepository.markScrapeTaskPending(movieForScrape.id)
        val source = settingsRepository.getDefaultScrapeSource()
        scrapeRepository.appendLog("开始网盘文件夹任务刮削影片：$number，来源：$source")
        movieRepository.markScrapeTaskRunning(movieForScrape.id)
        val scrapeResult = runCatching {
            scrapeRepository.scrapeStrmUriWithOutput(
                libraryRootUri = libraryRoot,
                strmUri = generated.strmUri,
                source = source,
                forceDistinct = generated.forceDistinct
            )
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            val reason = "刮削来源 ${source.name} 失败：${error.message ?: error::class.java.simpleName}"
            movieRepository.markScrapeTaskFailed(movieForScrape.id, reason)
            scrapeRepository.appendLog("网盘文件夹任务已入库但刮削失败：$number，原因：$reason")
            return CloudFolderVideoAddResult(
                pickcode = pickcode,
                message = "已添加但刮削失败：$number",
                scrapeFailed = true
            )
        }

        scrapeRepository.appendLog("网盘文件夹任务文件整理完成，开始同步影片数据库：$number")
        val refreshedMovie = runCatching {
            finalizeOrganizedScrape(
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
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            val reason = error.message ?: "整理后的 STRM 未同步到数据库：$number"
            scrapeRepository.appendLog("网盘文件夹任务刮削失败：$reason")
            return CloudFolderVideoAddResult(
                pickcode = pickcode,
                message = reason,
                scrapeFailed = true
            )
        }
        recordRepository.updateStrmLocation(
            pickcode = pickcode,
            strmUri = refreshedMovie.videoUri,
            libraryRootUri = refreshedMovie.libraryRootUri,
            movieId = refreshedMovie.id
        )
        scrapeRepository.appendLog(
            "[媒体路径] 状态=成功，操作=网盘文件夹刮削整理并更新数据库，movieId=${refreshedMovie.id}，文件=${refreshedMovie.videoName}，详情=影片表和 STRM 索引表已同步"
        )
        return CloudFolderVideoAddResult(
            pickcode = pickcode,
            message = if (generated.created) {
                "已添加并刮削：$number"
            } else {
                "STRM 已存在，已刷新并刮削：$number"
            }
        )
    }

    private suspend fun <T> withAddLock(fileName: String, block: suspend () -> T): T {
        val key = extractMovieNumberWithRules(fileName)?.uppercase() ?: fileName.trim().lowercase()
        val lock = addLock
        if (lock.isLocked) {
            scrapeRepository.appendLog("同番号文件夹任务等待整理完成：$key")
        }
        return lock.withLock {
            block()
        }
    }

    private suspend fun persistProgress(
        taskId: Long,
        summary: CloudFolderBatchRunSummary,
        currentPath: String?,
        currentFileName: String?
    ) {
        taskRepository.updateProgress(taskId, summary.toProgress(currentPath, currentFileName))
    }

    private inner class CloudAddNumberChecker {
        private var loadedCachedRules = false

        suspend fun extract(fileName: String): String? {
            if (!loadedCachedRules) {
                scrapeRepository.loadCachedNumberRecognitionRules()
                loadedCachedRules = true
            }
            return MovieNumberExtractor.extract(fileName)
        }
    }

    private suspend fun extractMovieNumberWithRules(vararg values: String?): String? {
        scrapeRepository.loadCachedNumberRecognitionRules()
        values.mapNotNull { it?.takeIf(String::isNotBlank) }.forEach { value ->
            MovieNumberExtractor.extract(value)?.let { return it }
        }
        return null
    }

    companion object {
        private const val CHILD_FOLDER_LIST_DELAY_MIN_MS = 1_500L
        private const val CHILD_FOLDER_LIST_DELAY_MAX_MS = 3_000L
        /** 退避的起点（第 1 次失败后的等待）。 */
        private const val CHILD_FOLDER_RETRY_DELAY_MIN_MS = 2_000L
        /** 退避上限：再久就不如让这一轮先失败、留到下轮整体重试。 */
        private const val CHILD_FOLDER_RETRY_BACKOFF_MAX_MS = 30_000L
        private const val CHILD_FOLDER_LIST_MAX_ATTEMPTS = 3
        /** 等已提交影片落定的轮询间隔：短到肉眼能看到进度在动，长到不会把 DB 读穿。 */
        private const val SUBMIT_SETTLE_POLL_MS = 800L
        /**
         * 等已提交影片落定的总上限。
         *
         * 一部影片在这个项目里最坏要走完「转存 → 刮削 → 落 STRM」，两个队列叠起来通常几分钟；
         * 给 30 分钟是为了让整个文件夹（可能上百部、并发受限）有一份完整预算，同时保证任务行
         * 被外部清掉时不会永远吊在这里。
         */
        private const val SUBMIT_SETTLE_TIMEOUT_MS = 30L * 60_000L
        /** 单条暂停/取消时写进 `failureMessage` 的文案（设置页直接展示这句话）。 */
        private const val PAUSE_MESSAGE = "已手动暂停，点「继续」从中断处续刮"
        private const val CANCEL_MESSAGE = "已手动取消，不会再自动继续"
    }
}

private data class CloudFolderVideoCandidate(
    val item: Cloud115FileItem,
    val path: String
) {
    val fullPath: String = "$path/${item.name}"
}

private data class CloudFolderVideoAddResult(
    val pickcode: String,
    val message: String,
    val scrapeFailed: Boolean = false
)

/**
 * 一次运行的汇总。
 *
 * 所有「已决定」的计数都从 [outcomes]（= 库里的断点）推导，只有
 * [collectionSkipped] / [failedVideos] / [failedFolders] 是本轮临时量：
 * 前者每轮确定性重算，后两者表示「还没成功、下轮要重试」，**不**写进断点。
 */
private class CloudFolderBatchRunSummary(
    val folderName: String,
    val outcomes: LinkedHashMap<String, CloudPickcodeOutcome>,
    var collectionSkipped: Int = 0,
    var queuedCandidates: Int = 0,
    var failedVideos: Int = 0,
    var failedFolders: Int = 0,
    val failureSamples: MutableList<String> = mutableListOf()
) {
    val addedVideos: Int get() = outcomes.values.count { it == CloudPickcodeOutcome.Added }
    val scrapeFailedVideos: Int get() = outcomes.values.count { it == CloudPickcodeOutcome.ScrapeFailed }
    val skippedVideos: Int
        get() = collectionSkipped + outcomes.values.count { it == CloudPickcodeOutcome.Skipped }

    fun rememberFailure(message: String) {
        if (failureSamples.size < 3) {
            failureSamples += message
        }
    }

    fun toProgress(currentPath: String?, currentFileName: String?): CloudFolderBatchTaskProgress =
        CloudFolderBatchTaskProgress(
            currentPath = currentPath,
            currentFileName = currentFileName,
            queuedVideos = queuedCandidates + collectionSkipped,
            processedVideos = outcomes.size + collectionSkipped,
            addedVideos = addedVideos,
            scrapeFailedVideos = scrapeFailedVideos,
            skippedVideos = skippedVideos,
            failedVideos = failedVideos,
            failedFolders = failedFolders,
            failureMessage = failureSamples.firstOrNull()
        )

    fun toUserMessage(): String {
        if (queuedCandidates == 0 && addedVideos == 0 && outcomes.isEmpty()) {
            return buildString {
                append("文件夹没有可添加的视频，已跳过 $skippedVideos 个")
                if (failedFolders > 0) append("，$failedFolders 个子文件夹读取失败")
                if (failedVideos > 0) append("，$failedVideos 个视频失败")
            }
        }
        return buildString {
            append("入库 $addedVideos 个")
            if (scrapeFailedVideos > 0) append("，$scrapeFailedVideos 个未刮削成功")
            if (skippedVideos > 0) append("，跳过 $skippedVideos 个")
            if (failedVideos > 0 || failedFolders > 0) append("，失败 ${failedVideos + failedFolders} 项")
        }
    }
}

private fun Cloud115FileItem.batchSkipReason(
    excludedVideoNames: Set<String>,
    skipBelowSizeBytes: Long
): String? {
    if (name.trim() in excludedVideoNames) return "命中网盘排除名单"
    if (pickcode.isNullOrBlank()) return "缺少 pickcode"
    val sizeBytes = size
    if (skipBelowSizeBytes > 0L && sizeBytes != null && sizeBytes <= skipBelowSizeBytes) {
        return "文件大小 ${formatCloudSize(sizeBytes)} 小于或等于阈值 ${formatCloudSize(skipBelowSizeBytes)}"
    }
    return null
}

private fun Cloud115FileItem.isVideoFile(): Boolean =
    CLOUD_VIDEO_EXTENSIONS.any { name.endsWith(it, ignoreCase = true) }

private fun formatCloudSize(size: Long): String {
    if (size <= 0L) return "0 B"
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var amount = size.toDouble()
    var index = 0
    while (amount >= 1024.0 && index < units.lastIndex) {
        amount /= 1024.0
        index += 1
    }
    return "%.1f %s".format(amount, units[index])
}

/** ★ 统一取自 [VideoFormats]。 */
private val CLOUD_VIDEO_EXTENSIONS: List<String> = VideoFormats.dotExtensions()

/** 「本次没有账本行」的哨兵 id：与 Room 自增主键（>=1）不冲突。 */
private const val NO_LEDGER_RUN: Long = -1L
