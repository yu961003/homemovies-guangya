package com.example.localmovielibrarz.cloudguangya

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

/** 离线下载批次进度（供 UI 展示）。 */
data class GuangyaOfflineProgress(
    val running: Boolean = false,
    val paused: Boolean = false,
    val total: Int = 0,        // 本次待提交总数（已过滤记忆）
    val done: Int = 0,         // 已处理数
    val ok: Int = 0,           // 成功数
    val fail: Int = 0,         // 失败数
    val skippedDone: Int = 0,  // 已提交记忆跳过
    val skippedFailed: Int = 0 // 黑名单跳过
)

/**
 * 光鸭离线下载并发提交引擎（移植 guangya-txt-cloud-add 脚本 v1.1 runBatch）。
 *
 * - 3 路并发 worker（默认），429/限频/临时失败自动降并发（最低 1），成功恢复
 * - 临时失败自动重试 2 次，指数退避 500ms → 1s → 2s
 * - 记忆过滤：已成功提交跳过、永久失败黑名单跳过、本批内去重
 * - 每日限额：成功计数达到限额自动停；服务端配额超限（QUOTA）立即停
 * - 暂停 / 继续：paused 标志 + 轮询挂起
 * - 日志缓冲（保留最近 [LOG_KEEP] 条），UI 批量渲染防卡死
 *
 * 线程安全：队列 / 去重集 / 计数器 / 失败原因统计全部由 [lock] 保护（worker 多协程并发）。
 */
class GuangyaOfflineSubmitter(
    private val repository: GuangyaBrowserRepository,
    private val memory: GuangyaOfflineMemory
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    private val _progress = MutableStateFlow(GuangyaOfflineProgress())
    val progress: StateFlow<GuangyaOfflineProgress> = _progress.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private var batchJob: Job? = null
    @Volatile private var pausedRequested = false
    @Volatile private var stopped = false
    @Volatile private var concurrency = MAX_CONCURRENCY

    private val logBuffer = ArrayDeque<String>()

    // 日志刷新节流：合并高频日志，按固定间隔批量刷新 StateFlow，减少 UI 重组
    private var logDirty = false
    private var logFlushJob: Job? = null

    /** 启动一个批次（幂等：已有批次运行时忽略）。 */
    fun start(urls: List<String>, parentId: String) {
        if (batchJob?.isActive == true) return
        stopped = false
        pausedRequested = false
        concurrency = MAX_CONCURRENCY
        logBuffer.clear()
        _logs.value = emptyList()
        batchJob = scope.launch { runBatch(urls, parentId) }
    }

    fun pause() {
        pausedRequested = true
        memory.flushNow() // 审查 P2：暂停即同步落盘断点（SharedPreferences.apply，无阻塞风险）
        _progress.update { it.copy(paused = true) }
    }

    fun resume() {
        pausedRequested = false
        _progress.update { it.copy(paused = false) }
    }

    /** 取消当前批次（已完成的记忆保留，下次断点续跑）。 */
    fun cancel() {
        batchJob?.cancel()
        logFlushJob?.cancel()    // 新增：取消日志刷新协程
        pausedRequested = false
        stopped = true
        _progress.value = GuangyaOfflineProgress()
    }

    private suspend fun runBatch(urls: List<String>, parentId: String) {
        // ---- 记忆过滤：跳过已提交 + 黑名单 + 本批去重 ----
        val seen = HashSet<String>()
        val alreadyDone = mutableListOf<String>()
        val alreadyFailed = mutableListOf<String>()
        val pending = mutableListOf<String>()
        for (m in urls) {
            val hash = GuangyaMagnetExtractor.magnetHash(m)
            if (!seen.add(hash)) continue
            // H 轮：成功记忆回读旧键（避免重复提交），失败黑名单只看新键（修复后允许重试历史失败项）
            when {
                GuangyaMagnetExtractor.submittedKeys(m).any { memory.isSubmitted(it) } -> alreadyDone.add(m)
                GuangyaMagnetExtractor.failedKeys(m).any { memory.isFailed(it) } -> alreadyFailed.add(m)
                else -> pending.add(m)
            }
        }
        log("本次识别 ${urls.size} 个，已提交跳过 ${alreadyDone.size}，黑名单跳过 ${alreadyFailed.size}，待提交 ${pending.size}")
        if (pending.isEmpty()) {
            log("全部已提交/失败过，无需操作")
            finish()
            return
        }
        val remaining = memory.todayRemaining()
        if (remaining <= 0) {
            log("今日额度已用完（${memory.getTodayCount()}/${memory.getDailyLimit()}），明天再跑")
            finish()
            return
        }

        val target = memory.targetFolder()
        log(if (target != null) "目标文件夹：${target.name}（ID ${target.fileId}）" else "目标文件夹：(根目录)")
        log("今日已用 ${memory.getTodayCount()}/${memory.getDailyLimit()}，剩余 $remaining")

        val total = pending.size
        _progress.value = GuangyaOfflineProgress(
            running = true,
            total = total,
            skippedDone = alreadyDone.size,
            skippedFailed = alreadyFailed.size
        )

        val queue = ArrayDeque(pending)
        val seenThisRun = HashSet<String>()
        val failReasons = linkedMapOf<String, Int>()
        var done = 0
        var ok = 0
        var fail = 0

        fun recordProgress() {
            val snapshot = synchronized(lock) { Triple(done, ok, fail) }
            _progress.update { it.copy(done = snapshot.first, ok = snapshot.second, fail = snapshot.third) }
        }

        suspend fun waitIfPaused() {
            while (pausedRequested && coroutineContext.isActive) delay(200)
        }

        suspend fun worker() {
            while (coroutineContext.isActive && !stopped) {
                val magnet: String
                val hash: String                                // 提前计算，复用
                synchronized(lock) {
                    if (queue.isEmpty()) return
                    val item = queue.removeFirst()
                    hash = GuangyaMagnetExtractor.magnetHash(item)
                    if (seenThisRun.contains(hash)) {
                        done++
                        return
                    }
                    seenThisRun.add(hash)
                    if (ok >= remaining) { stopped = true; return }
                    magnet = item
                }
                waitIfPaused()
                // hash 已在上面同步块内计算一次，无需重复调用 magnetHash(magnet)

                // 6.2 修复：提交前在锁内再次检查限额。
                // 多个 worker 并发取出 magnet 后，此处拦截超额部分，保证不超出 remaining。
                synchronized(lock) {
                    if (ok >= remaining) {
                        stopped = true
                        done++
                        recordProgress()
                        return
                    }
                }

                val result = try {
                    processOne(magnet, hash, parentId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val msg = e.message ?: e::class.java.simpleName
                    synchronized(lock) { failReasons.merge("异常: $msg", 1, Int::plus) }
                    if (!memory.isTempFailure(msg)) memory.markFailed(hash, "异常: $msg")
                    log("✗ 异常: ${magnet.take(50)} → $msg")
                    ResultState.FAIL
                }

                synchronized(lock) {
                    when (result) {
                        ResultState.OK -> ok++
                        ResultState.FAIL -> fail++
                        ResultState.STOP -> {
                            stopped = true
                            done++
                            recordProgress()
                            return
                        }
                    }
                    done++
                }
                recordProgress()
                delay(BASE_INTERVAL_MS) // 基础间隔，防洪峰
            }
        }

        log("开始提交 0/$total")
        // 用 coroutineScope 包裹：worker 是当前批次协程（batchJob）的子协程，
        // cancel() 取消批次时自动级联取消 worker（否则 scope 直管的子协程不会被取消）。
        try {
            coroutineScope {
                val workers = List(concurrency) { launch { worker() } }
                workers.joinAll()
            }
        } finally {
            memory.flushNow() // 立即落盘记忆，保证断点续跑
        }

        // 汇总失败原因
        val reasonLines = synchronized(lock) {
            failReasons.entries
                .sortedByDescending { it.value }
                .take(5)
                .joinToString("；") { "${it.value}×${it.key}" }
        }
        if (reasonLines.isNotBlank()) log("失败原因：$reasonLines")
        log("=== 完成：成功 $ok / 失败 $fail ===")
        log("今日已用 ${memory.getTodayCount()}/${memory.getDailyLimit()}，记忆已保存，下次自动跳过已提交/失败的")
        _progress.update { it.copy(running = false, paused = false) }
    }

    /**
     * 单个链接：解析 → 创建任务（各带临时失败重试），返回处理结果。
     *
     * H 轮：resolve_res 的返回值不再丢弃 —— create_task 提交**服务端规范化的 data.url**
     * （BT 资源另带 fileIndexes 分片提交）。旧实现直接提交原始链接，导致电驴链接被服务端
     * 按 BT 处理、批量返回「BT 文件不存在」。
     */
    private suspend fun processOne(
        magnet: String,
        hash: String,
        parentId: String
    ): ResultState {
        // 1. 解析（失败不直接判死：退回原始链接继续提交，行为不劣于旧版本）
        val resolved: GuangyaResolvedResource = try {
            withRetry(label = "解析") { repository.resolveOfflineResource(magnet) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("! 解析失败，退回原始链接提交: ${e.message ?: e::class.java.simpleName}")
            GuangyaOfflineResolve.parse(magnet, null)
        }

        // 2. 创建任务（BT 带 fileIndexes 分片提交；ed2k / http 单文件只提交规范化 url）
        //    fileIndexes 为空时用「空分片」表示不传该字段，保持与旧行为一致。
        val batches: List<List<Int>> = if (resolved.fileIndexes.isEmpty()) {
            listOf(emptyList())
        } else {
            resolved.fileIndexes.chunked(GuangyaResolvedResource.FILE_INDEX_BATCH)
        }
        try {
            // 用 for 而不是 forEach：withRetry 是 suspend，不能在非 suspend 的 lambda 里调用
            for (indexes in batches) {
                withRetry(label = "提交") { repository.createOfflineTask(resolved, parentId, indexes) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return handleFail(magnet, hash, e.message ?: "创建任务失败", submitHint(resolved))
        }

        // 成功：记忆 + 计数（限频降并发后恢复）
        memory.markSubmitted(hash)
        memory.bumpSuccessCounters()
        if (concurrency < MAX_CONCURRENCY) concurrency = MAX_CONCURRENCY
        log("✓ 已添加: ${magnet.take(60)}${successSuffix(resolved)}")
        return ResultState.OK
    }


    /** 成功日志补充：资源类型 / 子文件数 / 是否用了服务端规范链接。 */
    private fun successSuffix(resolved: GuangyaResolvedResource): String {
        val parts = mutableListOf<String>()
        parts += when (resolved.infoKind) {
            GuangyaResolvedResource.KIND_BT -> "BT"
            GuangyaResolvedResource.KIND_EMULE -> "电驴"
            GuangyaResolvedResource.KIND_URL -> "直链"
            else -> "resType=${resolved.resType}"
        }
        if (resolved.fileIndexes.isNotEmpty()) parts += "子文件 ${resolved.fileIndexes.size} 个"
        if (!resolved.usedResolvedUrl) parts += "未拿到规范链接·回退原始链接"
        return "（${parts.joinToString(" · ")}）"
    }

    /**
     * 失败诊断提示：电驴链接报「BT 文件不存在」时，说明服务端把它当 BT 处理了，
     * 日志里标明本次用的是规范链接还是原始链接，便于区分「链接本身失效」与「协议用错」。
     */
    private fun submitHint(resolved: GuangyaResolvedResource): String {
        if (!resolved.isEd2k) return ""
        return if (resolved.usedResolvedUrl) "电驴·已用服务端规范链接" else "电驴·服务端未返回规范链接，退回原始链接"
    }

    private fun handleFail(magnet: String, hash: String, reason: String, hint: String = ""): ResultState {
        if (memory.isQuotaExceeded(reason)) {
            log("⚠ 今日云添加次数已超限，立即停止。剩余磁力明天再跑（记忆断点已保存）")
            return ResultState.STOP
        }
        if (!memory.isTempFailure(reason)) memory.markFailed(hash, reason)
        log("✗ ${magnet.take(50)} → $reason${if (hint.isNotBlank()) "（$hint）" else ""}")
        if (memory.isTempFailure(reason) && concurrency > MIN_CONCURRENCY) concurrency--
        return ResultState.FAIL
    }

    /** 临时失败自动重试：500ms → 1s → 2s（最多 3 次），非临时失败直接抛出。 */
    private suspend fun <T> withRetry(label: String, block: suspend () -> T): T {
        var delayMs = RETRY_BASE_DELAY_MS
        var attempt = 1
        while (true) {
            try {
                return block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val msg = e.message ?: e::class.java.simpleName
                if (!memory.isTempFailure(msg) || attempt >= MAX_ATTEMPTS) throw e
                log("↻ $label 重试 $attempt/${MAX_ATTEMPTS - 1}（$msg）")
                delay(delayMs)
                delayMs *= 2
                attempt++
            }
        }
    }

    private fun finish() {
        _progress.value = GuangyaOfflineProgress()
    }

    /** 日志缓冲（保留最近 LOG_KEEP 条，防无限增长）。200ms 节流批量刷新 StateFlow，减少 UI 重组。 */
    private fun log(msg: String) {
        synchronized(lock) {
            logBuffer.addLast(msg)
            while (logBuffer.size > LOG_KEEP) logBuffer.removeFirst()
            logDirty = true
            if (logFlushJob?.isActive != true) {
                logFlushJob = scope.launch {
                    while (true) {
                        delay(LOG_FLUSH_INTERVAL_MS)
                        synchronized(lock) {
                            if (logDirty) {
                                _logs.value = logBuffer.toList()
                                logDirty = false
                            } else {
                                return@launch  // 无新日志，结束本轮 flush
                            }
                        }
                    }
                }
            }
        }
    }

    private enum class ResultState { OK, FAIL, STOP }

    private companion object {
        const val MAX_CONCURRENCY = 3
        const val MIN_CONCURRENCY = 1
        const val RETRY_BASE_DELAY_MS = 500L
        const val MAX_ATTEMPTS = 3
        const val BASE_INTERVAL_MS = 200L
        const val LOG_FLUSH_INTERVAL_MS = 200L
        const val LOG_KEEP = 200
    }
}
