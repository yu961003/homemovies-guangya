package com.example.localmovielibrarz.cloudguangya

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 光鸭批量清理进度（应用级单例，跨页面存活）。 */
data class GuangyaCleanProgress(
    val running: Boolean = false,
    val total: Int = 0,
    val done: Int = 0,
    val failed: Int = 0,
    val phase: String = "",          // 如 "第 3/40 批"
    val finishedMessage: String? = null
)

/**
 * 大批量清理执行器：50/批顺序提交 + 逐批等待终态。
 * 单例注入 AppContainer（对齐 GuangyaOfflineSubmitter 模式），
 * 页面退出不中断；同一时刻仅允许一个清理任务。
 */
class GuangyaCleanExecutor(private val repository: GuangyaBrowserRepository) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()

    private val _progress = MutableStateFlow(GuangyaCleanProgress())
    val progress: StateFlow<GuangyaCleanProgress> = _progress.asStateFlow()

    private var job: Job? = null

    val isRunning: Boolean get() = _progress.value.running

    /**
     * 启动批量回收。返回 false = 已有任务在跑（或列表为空）。
     * [onFinished] 在任务结束后回调（成功/失败/取消均回调，供页面弹提示）。
     *
     * 并发安全说明：
     * - start() 是普通函数，不能用挂起的 Mutex.withLock（编译错误）——
     *   改用非挂起的 tryLock()/unlock() 完成「检查 + 赋初值」的原子性；
     * - 协程体内再以 withLock 保护整批推进期间的状态写入（挂起上下文合法）；
     * - stop() 只置 running=false，让协程在下一批检查时自然退出，
     *   保证 finishedMessage 能正常设置（cancel 会跳过汇总）；
     * - onFinished 移到锁外调用，避免回调在持锁期间执行。
     */
    fun start(fileIds: List<String>, onFinished: (GuangyaCleanProgress) -> Unit = {}): Boolean {
        if (fileIds.isEmpty()) return false
        if (!mutex.tryLock()) return false                       // 已有任务在启动/运行中
        try {
            if (_progress.value.running) return false
            _progress.value = GuangyaCleanProgress(running = true, total = fileIds.size)
        } finally {
            mutex.unlock()
        }
        job = scope.launch {
            var finishedProgress: GuangyaCleanProgress
            mutex.withLock {
                var done = 0; var failed = 0; var lastError: String? = null
                val batches = fileIds.chunked(50)
                batches.forEachIndexed { i, batch ->
                    if (!_progress.value.running) return@forEachIndexed   // 已停止：跳过后续批次
                    _progress.value = _progress.value.copy(
                        done = done, failed = failed, phase = "第 ${i + 1}/${batches.size} 批")
                    try {
                        repository.trashItemsBatch(batch)   // 内部已提交 + 等终态
                        done += batch.size
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failed += batch.size; lastError = e.message      // 单批失败不中止后续
                    }
                    _progress.value = _progress.value.copy(done = done, failed = failed)
                }
                val cancelled = !_progress.value.running
                finishedProgress = _progress.value.copy(
                    running = false, phase = "",
                    finishedMessage = when {
                        cancelled -> "清理已停止：完成 $done / 总 ${fileIds.size}，失败 $failed"
                        failed > 0 -> "清理完成：成功 $done，失败 $failed" +
                            (lastError?.let { "（$it）" } ?: "") + "，失败项可重试"
                        else -> "清理完成：成功 $done 项（已进入回收站）"
                    }
                )
                _progress.value = finishedProgress
            }
            onFinished(finishedProgress)
        }
        return true
    }

    /** 停止：只阻止后续批次（已提交批次等服务端终态，对齐脚本取消语义）。 */
    fun stop() {
        _progress.value = _progress.value.copy(running = false)
    }

    /**
     * 清除完成消息（页面消费后调用，防止跨页面残留）。
     * update 为 CAS 原子操作，与协程体内 mutex.withLock 保护的写入不存在丢失更新。
     */
    fun clearFinishedMessage() {
        _progress.update { it.copy(finishedMessage = null) }
    }
}