package com.example.localmovielibrarz.data.repository

import com.example.localmovielibrarz.data.local.TaskRunDao
import com.example.localmovielibrarz.data.local.TaskRunEntity
import com.example.localmovielibrarz.data.local.TaskRunOwnerTokens
import com.example.localmovielibrarz.tasks.TaskKind
import com.example.localmovielibrarz.tasks.TaskRunPolicy
import com.example.localmovielibrarz.tasks.TaskRunSnapshot
import com.example.localmovielibrarz.tasks.TaskState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 任务运行账本（task_runs）的读写门面 + 互斥占位。
 *
 * 互斥为什么不用 SQL 唯一索引：Room 不支持部分唯一索引（`WHERE state IN (...)`），
 * 而「同一 dedupeKey 只允许一个活跃 run」必须容忍同键的历史终态行。因此在
 * [claim] 里用「进程内 Mutex + 读活跃行 + 插入」三步实现，并接受一个已知失败模式：
 * 若未来出现多进程（WorkManager 独立进程、widget 进程），两进程可能各插一行。
 * 鸭影当前是单进程 App，Room 写入本身也是串行的，这个取舍已被明确记录而非忽略。
 */
class TaskRunRepository(
    private val dao: TaskRunDao,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val claimMutex = Mutex()

    fun observeRecent(limit: Int = 20): Flow<List<TaskRunSnapshot>> =
        dao.observeRecent(limit).map { rows -> rows.map { it.toSnapshot() } }

    fun observeActiveCount(): Flow<Int> = dao.observeCountByStates(TaskState.activeNames)

    fun observeUnfinishedCount(): Flow<Int> = dao.observeCountByStates(TaskState.unfinishedNames)

    /** 「占位中」（Running + Paused）的状态名，供 [claim] 查询使用。 */
    private val blockingStates: List<String> =
        TaskState.entries.filter { it.blocksNewRun }.map { it.name }

    suspend fun get(id: Long): TaskRunSnapshot? = withContext(ioDispatcher) {
        dao.get(id)?.toSnapshot()
    }

    suspend fun latestFor(kind: TaskKind, scope: String): TaskRunSnapshot? = withContext(ioDispatcher) {
        dao.getLatestByDedupeKey(TaskRunPolicy.dedupeKeyOf(kind, scope))?.toSnapshot()
    }

    suspend fun activeRuns(): List<TaskRunSnapshot> = withContext(ioDispatcher) {
        dao.getByStates(TaskState.activeNames).map { it.toSnapshot() }
    }

    /**
     * 尝试为某任务占位。返回 null 表示「已有活跃 run 在跑」，调用方应放弃本次启动。
     *
     * attempts 自动 +1：同一 dedupeKey 的历史行数 + 1，这样「第几次重试」不需要额外字段维护。
     */
    suspend fun claim(
        kind: TaskKind,
        scope: String,
        title: String,
        totalSteps: Int = 0
    ): TaskRunSnapshot? = claimMutex.withLock {
        withContext(ioDispatcher) {
            val dedupeKey = TaskRunPolicy.dedupeKeyOf(kind, scope)
            val now = clock()
            // 判定交给策略层，而不是在这里手写「有没有活跃行」：策略层的顺序（失联接管先于占位拦截）
            // 是经过讨论的语义，仓储只负责执行它给出的结论，避免两处判断慢慢跑偏。
            val blocking = dao.getByDedupeKeyInStates(dedupeKey, blockingStates)?.toSnapshot()
            if (!TaskRunPolicy.shouldStartNewRun(blocking, TaskState.Running, now).allowed) {
                return@withContext null
            }
            // 走到这里有两种可能：没有占位行，或占位行已失联被判为可接管。
            // 后者必须先把旧行降级，否则同一 dedupeKey 会同时存在两条 Running，
            // UI 的「活跃任务数」会凭空多一，且旧协程若侥幸活着会与新协程并行写同一批影片。
            if (blocking != null && blocking.state == TaskState.Running) {
                dao.updateState(blocking.id, TaskState.Paused.name, TAKEN_OVER_MESSAGE, now)
            }
            val attempts = dao.countByDedupeKey(dedupeKey) + 1
            val id = dao.insert(
                TaskRunEntity(
                    kind = kind.name,
                    dedupeKey = dedupeKey,
                    title = title,
                    state = TaskState.Running.name,
                    step = null,
                    completedSteps = 0,
                    totalSteps = totalSteps.coerceAtLeast(0),
                    attempts = attempts,
                    failureMessage = null,
                    ownerToken = TaskRunOwnerTokens.newToken(),
                    startedAtMillis = now,
                    lastHeartbeatAtMillis = now,
                    updatedAtMillis = now
                )
            )
            dao.get(id)?.toSnapshot()
        }
    }

    suspend fun heartbeat(id: Long) = withContext(ioDispatcher) {
        dao.touch(id, clock())
    }

    suspend fun updateProgress(
        id: Long,
        state: TaskState,
        step: String?,
        completedSteps: Int,
        totalSteps: Int,
        failureMessage: String? = null
    ) = withContext(ioDispatcher) {
        dao.updateProgress(
            id = id,
            state = state.name,
            step = step,
            completedSteps = completedSteps.coerceAtLeast(0),
            totalSteps = totalSteps.coerceAtLeast(0),
            failureMessage = failureMessage,
            now = clock()
        )
    }

    suspend fun finish(id: Long, state: TaskState, failureMessage: String? = null) =
        withContext(ioDispatcher) {
            require(state.isTerminal) { "finish() 只接受终态，收到 $state" }
            dao.updateState(id, state.name, failureMessage, clock())
        }

    suspend fun notifyPaused(id: Long, reason: String?) = withContext(ioDispatcher) {
        dao.updateState(id, TaskState.Paused.name, reason, clock())
    }

    /**
     * 冷启动恢复：所有 Running 行一律降级为 Paused。
     *
     * 为什么不是只在超过心跳阈值时才降级：进程被杀时协程也随之消失，**任何** Running
     * 行在新进程里都不可能有执行者。把它留在 Running 只会让「活跃任务」统计虚高，
     * 并让新的 claim 被自己的幽灵行挡掉。中断不是失败，因此不写 Failed。
     *
     * @return 被恢复的行数，供启动横幅提示「已恢复 N 个被中断的任务」。
     */
    suspend fun recoverInterrupted(): Int = withContext(ioDispatcher) {
        val now = clock()
        val running = dao.getByStates(listOf(TaskState.Running.name))
        running.forEach { row ->
            dao.updateState(row.id, TaskState.Paused.name, RECOVERED_MESSAGE, now)
        }
        running.size
    }

    /** 清理历史终态行，只保留最近 [keep] 条；活跃行永不删除。 */
    suspend fun pruneHistory(keep: Int = 200): Int = withContext(ioDispatcher) {
        dao.deleteOldTerminalRuns(
            terminalStates = listOf(
                TaskState.Succeeded.name,
                TaskState.Failed.name,
                TaskState.Cancelled.name
            ),
            keep = keep
        )
    }

    suspend fun summary(): TaskRunPolicy.TaskRunSummary = withContext(ioDispatcher) {
        // 必须先按 dedupeKey 去重再汇总：同一业务任务重跑会产生多条历史 run，
        // 直接喂给 summarize 会让「已失败 3 次」被数成「3 个任务失败」。
        val latestPerKey = dao.getByStates(TaskState.entries.map { it.name })
            .groupBy { it.dedupeKey }
            .values
            .mapNotNull { rows -> rows.maxWithOrNull(RUN_RECENCY) }
        TaskRunPolicy.summarize(runs = latestPerKey.map { it.toSnapshot() }, now = clock())
    }

    private fun TaskRunEntity.toSnapshot(): TaskRunSnapshot = TaskRunSnapshot(
        id = id,
        kind = TaskKind.parse(kind) ?: TaskKind.MovieScrape,
        dedupeKey = dedupeKey,
        title = title,
        state = TaskState.parse(state) ?: TaskState.Pending,
        step = step,
        completedSteps = completedSteps,
        totalSteps = totalSteps,
        attempts = attempts,
        failureMessage = failureMessage,
        lastHeartbeatAtMillis = lastHeartbeatAtMillis,
        updatedAtMillis = updatedAtMillis
    )

    companion object {
        const val RECOVERED_MESSAGE: String = "上次运行被中断（进程重启），已暂停并保留进度"
        const val TAKEN_OVER_MESSAGE: String = "心跳超时，已由新的运行接管（旧进度保留在断点）"

        /** 「谁更新」的排序：先看 updatedAtMillis，同毫秒时用 id 兜底，避免取到未定义的那一条。 */
        private val RUN_RECENCY: Comparator<TaskRunEntity> =
            compareBy<TaskRunEntity> { it.updatedAtMillis }.thenBy { it.id }
    }
}
