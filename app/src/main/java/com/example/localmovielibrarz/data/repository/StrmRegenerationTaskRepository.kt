package com.example.localmovielibrarz.data.repository

import com.example.localmovielibrarz.cloud115.Cloud115FileItem
import com.example.localmovielibrarz.data.local.StrmRegenerationTaskDao
import com.example.localmovielibrarz.data.local.StrmRegenerationTaskEntity
import com.example.localmovielibrarz.data.local.StrmRegenerationTaskStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext

/**
 * 「STRM 再生成」任务的持久层。
 *
 * 与 [CloudVideoTaskRepository] 是两条互不干扰的轨道：
 * - 那条轨道回答「这部片入过库没有」，Completed 之后不再重跑（入库断点）；
 * - 本轨道回答「这部片的 .strm 被重新生成过几次、上次为什么失败」，同一部片可以反复入队。
 *
 * 调用方（`CloudVideoAddProcessor.runLockedImport`）只把它当**旁路记账**用：写库失败
 * 绝不能反过来打断 STRM 生成/入库主流程，所以调用点都用 `runCatching` 包住。
 */
class StrmRegenerationTaskRepository(
    private val dao: StrmRegenerationTaskDao
) {
    fun observeTasks(): Flow<List<StrmRegenerationTaskEntity>> =
        dao.observeAll().distinctUntilChanged()

    fun observeUnfinishedCount(): Flow<Int> =
        dao.observeUnfinishedCount().distinctUntilChanged()

    /**
     * 入队（或刷新）一条再生成任务，落成 Pending。
     *
     * 与 `CloudVideoTaskRepository.enqueue` 的关键差异：那条轨道遇到 Completed 会**保留**
     * Completed（不再重跑），本轨道遇到 Completed 会**重置**为 Pending —— 再生成本来就是
     * 可以重复做的动作，保留旧终态会让第二次再生成永远跑不起来。
     *
     * 需要保留的历史：`id`（自增主键，REPLACE 会换 id）、`createdAt`（首次入队时间）、
     * `attempts`（累计重试成本）。不保留 `failureReason`：新一轮开始，上次的错因已经作废。
     */
    suspend fun enqueue(
        item: Cloud115FileItem,
        pickcode: String,
        forceDistinct: Boolean
    ): StrmRegenerationTaskEntity = withContext(Dispatchers.IO) {
        require(pickcode.isNotBlank()) { "这个视频没有 pickcode，无法记录 STRM 再生成任务" }
        val now = System.currentTimeMillis()
        val existing = dao.getByPickcode(pickcode)
        val task = StrmRegenerationTaskEntity(
            id = existing?.id ?: 0L,
            pickcode = pickcode,
            fileName = item.name,
            cid = item.cid,
            fid = item.fid,
            size = item.size,
            strmPath = existing?.strmPath,
            forceDistinct = forceDistinct,
            status = StrmRegenerationTaskStatus.Pending.name,
            failureReason = null,
            attempts = existing?.attempts ?: 0,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now
        )
        val rowId = dao.upsert(task)
        // REPLACE 在「pickcode 唯一索引冲突」时走的是删除+插入，自增 id 可能变。
        // 返回真实落库 id，调用方后续按 pickcode 操作，不依赖这个值。
        if (rowId > 0L) task.copy(id = rowId) else task
    }

    /** 读一条（不抢）。给设置页/测试用。 */
    suspend fun get(pickcode: String): StrmRegenerationTaskEntity? = withContext(Dispatchers.IO) {
        dao.getByPickcode(pickcode)
    }

    /** 按 createdAt 取最早的一条 Pending；只读，不改变状态。 */
    suspend fun nextPending(): StrmRegenerationTaskEntity? = withContext(Dispatchers.IO) {
        dao.nextPending()
    }

    /** 抢占下一条 Pending（CAS 改 Running）。多 worker 时只有影响行数为 1 的算抢到。 */
    suspend fun claimNext(): StrmRegenerationTaskEntity? = withContext(Dispatchers.IO) {
        dao.claimNext()
    }

    /** 标记成功：记下本次产出的 strm URI，清空失败原因，attempts 不清零（保留重试成本）。 */
    suspend fun markCompleted(pickcode: String, strmPath: String?) = withContext(Dispatchers.IO) {
        dao.markCompletedIfExists(pickcode, strmPath, System.currentTimeMillis())
        Unit
    }

    /**
     * 标记失败并让 `attempts + 1`。
     *
     * 自增放在 SQL 里做（`attempts = attempts + 1`）而不是先读再写：worker 中断后重跑时，
     * 读改写会吞掉并发期间别人的那一次 +1。
     */
    suspend fun markFailed(pickcode: String, reason: String?) = withContext(Dispatchers.IO) {
        dao.markFailedAndIncrement(pickcode, reason, System.currentTimeMillis())
        Unit
    }

    /** 按状态计数；设置页的「再生成」分组用它，不必订阅全表。 */
    suspend fun counts(): StrmRegenerationCounts = withContext(Dispatchers.IO) {
        StrmRegenerationCounts(
            pending = dao.countByStatus(StrmRegenerationTaskStatus.Pending.name),
            running = dao.countByStatus(StrmRegenerationTaskStatus.Running.name),
            completed = dao.countByStatus(StrmRegenerationTaskStatus.Completed.name),
            failed = dao.countByStatus(StrmRegenerationTaskStatus.Failed.name),
            total = dao.countAll()
        )
    }

    suspend fun hasUnfinishedTasks(): Boolean = withContext(Dispatchers.IO) {
        dao.countUnfinished() > 0
    }

    suspend fun clearCompleted(): Int = withContext(Dispatchers.IO) { dao.deleteCompleted() }

    suspend fun clearUnfinished(): Int = withContext(Dispatchers.IO) { dao.deleteUnfinished() }
}

/**
 * 「再生成」任务的分状态计数。
 *
 * [total] 是表里全部行数而不是四者之和的替代——四者之和等于 total（状态只有这四种），
 * 单独带出来是为了让 UI 不必再求和。
 */
data class StrmRegenerationCounts(
    val pending: Int,
    val running: Int,
    val completed: Int,
    val failed: Int,
    val total: Int
) {
    /** 未完成 = Pending + Running；失败单独算，不并入「未完成」，否则重试轮次里数字会忽大忽小。 */
    val unfinished: Int get() = pending + running
}
