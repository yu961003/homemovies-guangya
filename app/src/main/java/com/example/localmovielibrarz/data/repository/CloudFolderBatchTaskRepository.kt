package com.example.localmovielibrarz.data.repository

import com.example.localmovielibrarz.cloud115.Cloud115FileItem
import com.example.localmovielibrarz.data.local.CloudFolderBatchTaskDao
import com.example.localmovielibrarz.data.local.CloudFolderBatchTaskEntity
import com.example.localmovielibrarz.data.local.CloudFolderBatchTaskStatus
import com.example.localmovielibrarz.data.local.CloudPickcodeOutcome
import com.example.localmovielibrarz.data.local.CloudPickcodeProgressCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

class CloudFolderBatchTaskRepository(
    private val dao: CloudFolderBatchTaskDao
) {
    fun observeTasks(): Flow<List<CloudFolderBatchTaskEntity>> =
        dao.observeAll().distinctUntilChanged()

    fun observeUnfinishedTaskCount(): Flow<Int> =
        dao.observeTaskCount(UNFINISHED_STATUSES).distinctUntilChanged()

    suspend fun enqueueFolder(folder: Cloud115FileItem): CloudFolderBatchTaskEntity = withContext(Dispatchers.IO) {
        val cid = folder.cid ?: error("这个文件夹没有 CID，无法批量添加")
        val now = System.currentTimeMillis()
        val existing = dao.getUnfinishedByFolderCid(cid)
        if (existing != null) {
            // Failed / Cancelled 重新添加时回到 Pending 继续跑；
            // 断点（processedPickcodesCsv）保留——重跑不会重复入库，也不会丢进度。
            val nextStatus = when (existing.status) {
                CloudFolderBatchTaskStatus.Failed.name,
                CloudFolderBatchTaskStatus.Cancelled.name -> CloudFolderBatchTaskStatus.Pending.name

                else -> existing.status
            }
            val updated = existing.copy(
                folderName = folder.name,
                status = nextStatus,
                updatedAt = now
            )
            dao.upsert(updated)
            return@withContext updated
        }
        val id = dao.insert(
            CloudFolderBatchTaskEntity(
                folderCid = cid,
                folderName = folder.name,
                createdAt = now,
                updatedAt = now
            )
        )
        dao.get(id) ?: error("文件夹任务创建失败")
    }

    suspend fun nextRunnableTask(): CloudFolderBatchTaskEntity? = withContext(Dispatchers.IO) {
        dao.nextRunnable(RUNNABLE_STATUSES)
    }

    suspend fun prepareForStart(): Int = withContext(Dispatchers.IO) {
        dao.prepareForStart(System.currentTimeMillis())
    }

    suspend fun getTask(taskId: Long): CloudFolderBatchTaskEntity? = withContext(Dispatchers.IO) {
        dao.get(taskId)
    }

    /** 一次性读取当前任务列表（F-3 手动刷新用；flow 版见 [observeTasks]）。 */
    suspend fun getTasks(): List<CloudFolderBatchTaskEntity> =
        observeTasks().first()

    /** 被异常中断（进程被杀）的任务数——冷启动自动续跑的前置条件。 */
    suspend fun countInterrupted(): Int = withContext(Dispatchers.IO) {
        dao.countInterrupted()
    }

    suspend fun countUnfinished(): Int = withContext(Dispatchers.IO) {
        dao.countByStatuses(UNFINISHED_STATUSES)
    }

    /**
     * 开始（或继续）一条任务。
     *
     * 与旧 `resetForRun` 的区别：**不再清零计数**。计数改由 [progress] 给出，
     * 而 [progress] 是从 [processedPickcodesCsv] 推导出来的，所以「暂停 → 继续」后
     * 进度条接着走，而不是从 0 开始把同一批影片再刮一遍。
     */
    suspend fun resumeForRun(
        taskId: Long,
        progress: CloudFolderBatchTaskProgress
    ): CloudFolderBatchTaskEntity? = withContext(Dispatchers.IO) {
        val current = dao.get(taskId) ?: return@withContext null
        val now = System.currentTimeMillis()
        val updated = current.copy(
            status = CloudFolderBatchTaskStatus.Running.name,
            currentPath = progress.currentPath ?: current.folderName,
            currentFileName = progress.currentFileName,
            queuedVideos = progress.queuedVideos,
            processedVideos = progress.processedVideos,
            addedVideos = progress.addedVideos,
            scrapeFailedVideos = progress.scrapeFailedVideos,
            skippedVideos = progress.skippedVideos,
            failedVideos = progress.failedVideos,
            failedFolders = progress.failedFolders,
            failureMessage = null,
            lastProgressAt = now,
            updatedAt = now
        )
        dao.upsert(updated)
        updated
    }

    /**
     * 记录一部影片的结局并落库进度。
     *
     * 先合并 pickcode 结局写入 CSV，再整行 upsert 计数——两者在同一次写里完成，
     * 避免「计数走了但断点没记」（进程中途被杀会重复处理）或反之。
     */
    suspend fun recordOutcome(
        taskId: Long,
        pickcode: String,
        outcome: CloudPickcodeOutcome,
        progress: CloudFolderBatchTaskProgress
    ) = withContext(Dispatchers.IO) {
        val current = dao.get(taskId) ?: return@withContext
        val outcomes = CloudPickcodeProgressCodec.decode(current.processedPickcodesCsv)
        outcomes[pickcode] = outcome
        val now = System.currentTimeMillis()
        dao.upsert(
            current.copy(
                currentPath = progress.currentPath,
                currentFileName = progress.currentFileName,
                queuedVideos = progress.queuedVideos,
                processedVideos = progress.processedVideos,
                addedVideos = progress.addedVideos,
                scrapeFailedVideos = progress.scrapeFailedVideos,
                skippedVideos = progress.skippedVideos,
                failedVideos = progress.failedVideos,
                failedFolders = progress.failedFolders,
                failureMessage = progress.failureMessage,
                processedPickcodesCsv = CloudPickcodeProgressCodec.encode(outcomes),
                lastProgressAt = now,
                updatedAt = now
            )
        )
    }

    /**
     * 批量落断点。
     *
     * 刮削队列跑完后是一次性拿到 N 个 pickcode 的终态，逐个写会多出 N 次事务；
     * 这里一次合并、一次 upsert，同时保持「计数与断点同一条记录」的一致性。
     */
    suspend fun recordOutcomes(
        taskId: Long,
        resolved: Map<String, CloudPickcodeOutcome>,
        progress: CloudFolderBatchTaskProgress
    ) = withContext(Dispatchers.IO) {
        if (resolved.isEmpty()) return@withContext
        val current = dao.get(taskId) ?: return@withContext
        val outcomes = CloudPickcodeProgressCodec.decode(current.processedPickcodesCsv)
        outcomes.putAll(resolved)
        val now = System.currentTimeMillis()
        dao.upsert(
            current.copy(
                currentPath = progress.currentPath,
                currentFileName = progress.currentFileName,
                queuedVideos = progress.queuedVideos,
                processedVideos = progress.processedVideos,
                addedVideos = progress.addedVideos,
                scrapeFailedVideos = progress.scrapeFailedVideos,
                skippedVideos = progress.skippedVideos,
                failedVideos = progress.failedVideos,
                failedFolders = progress.failedFolders,
                failureMessage = progress.failureMessage,
                processedPickcodesCsv = CloudPickcodeProgressCodec.encode(outcomes),
                lastProgressAt = now,
                updatedAt = now
            )
        )
    }

    suspend fun updateProgress(taskId: Long, progress: CloudFolderBatchTaskProgress) = withContext(Dispatchers.IO) {
        val current = dao.get(taskId) ?: return@withContext
        val now = System.currentTimeMillis()
        dao.upsert(
            current.copy(
                currentPath = progress.currentPath,
                currentFileName = progress.currentFileName,
                queuedVideos = progress.queuedVideos,
                processedVideos = progress.processedVideos,
                addedVideos = progress.addedVideos,
                scrapeFailedVideos = progress.scrapeFailedVideos,
                skippedVideos = progress.skippedVideos,
                failedVideos = progress.failedVideos,
                failedFolders = progress.failedFolders,
                failureMessage = progress.failureMessage,
                lastProgressAt = now,
                updatedAt = now
            )
        )
    }

    suspend fun markCompleted(taskId: Long, progress: CloudFolderBatchTaskProgress, message: String) = withContext(Dispatchers.IO) {
        val current = dao.get(taskId) ?: return@withContext
        val now = System.currentTimeMillis()
        dao.upsert(
            current.copy(
                status = CloudFolderBatchTaskStatus.Completed.name,
                currentPath = progress.currentPath,
                currentFileName = null,
                queuedVideos = progress.queuedVideos,
                processedVideos = progress.processedVideos,
                addedVideos = progress.addedVideos,
                scrapeFailedVideos = progress.scrapeFailedVideos,
                skippedVideos = progress.skippedVideos,
                failedVideos = progress.failedVideos,
                failedFolders = progress.failedFolders,
                failureMessage = message,
                lastProgressAt = now,
                updatedAt = now
            )
        )
    }

    /**
     * 单条任务「继续」：把这一行放回 `Pending`，让它重新被 [nextRunnableTask] 拾起。
     *
     * 为什么不直接调 runner 的 `start()`：`start()` 会先跑 [prepareForStart]，那条 SQL 会把
     * **所有** Paused / Failed / Cancelled 一起推回 Pending。用户在某一行的「继续」按钮上
     * 点一下，却把其它几个手动暂停的任务一起唤醒，属于典型的越权副作用。
     *
     * 不动 `processedPickcodesCsv` 与各计数：断点是续跑的唯一真相源，清零等于把已完成的影片
     * 再刮一遍。
     */
    suspend fun markPending(taskId: Long, message: String? = null) = withContext(Dispatchers.IO) {
        val current = dao.get(taskId) ?: return@withContext
        dao.upsert(
            current.copy(
                status = CloudFolderBatchTaskStatus.Pending.name,
                failureMessage = message,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun markPaused(taskId: Long, message: String) = withContext(Dispatchers.IO) {
        val current = dao.get(taskId) ?: return@withContext
        dao.upsert(
            current.copy(
                status = CloudFolderBatchTaskStatus.Paused.name,
                failureMessage = message,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    /**
     * 用户主动取消（F-4）。
     *
     * 与 [markPaused] 分成两条路径：暂停表示「待会继续」，取消表示「这次不做了」。
     * 状态不同，设置页文案与「继续」按钮的显隐也不同，混用会让用户分不清任务到底还跑不跑。
     */
    suspend fun markCancelled(taskId: Long, message: String) = withContext(Dispatchers.IO) {
        val current = dao.get(taskId) ?: return@withContext
        dao.upsert(
            current.copy(
                status = CloudFolderBatchTaskStatus.Cancelled.name,
                failureMessage = message,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun markFailed(taskId: Long, message: String) = withContext(Dispatchers.IO) {
        val current = dao.get(taskId) ?: return@withContext
        dao.upsert(
            current.copy(
                status = CloudFolderBatchTaskStatus.Failed.name,
                failureMessage = message,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun clearCompletedTasks(): Int = withContext(Dispatchers.IO) {
        dao.deleteCompleted()
    }

    /**
     * 僵尸 Running 降级（F-3，配合设置页「刷新」）。
     *
     * 调用前必须由调用方确认 runner 已经不在跑，否则会把正在执行的任务误标成暂停。
     */
    suspend fun markZombieRunningAsPaused(message: String): Int = withContext(Dispatchers.IO) {
        dao.markRunningAsPaused(System.currentTimeMillis(), message)
    }

    suspend fun clearUnfinishedTasks(): Int = withContext(Dispatchers.IO) {
        dao.deleteByStatuses(UNFINISHED_STATUSES)
    }

    companion object {
        val UNFINISHED_STATUSES = listOf(
            CloudFolderBatchTaskStatus.Pending.name,
            CloudFolderBatchTaskStatus.Running.name,
            CloudFolderBatchTaskStatus.Paused.name,
            CloudFolderBatchTaskStatus.Failed.name,
            CloudFolderBatchTaskStatus.Cancelled.name
        )

        val RUNNABLE_STATUSES = listOf(CloudFolderBatchTaskStatus.Pending.name)
    }
}

data class CloudFolderBatchTaskProgress(
    val currentPath: String?,
    val currentFileName: String?,
    val queuedVideos: Int,
    val processedVideos: Int,
    val addedVideos: Int,
    val scrapeFailedVideos: Int,
    val skippedVideos: Int,
    val failedVideos: Int,
    val failedFolders: Int,
    val failureMessage: String?
)
