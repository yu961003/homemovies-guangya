package com.example.localmovielibrarz.data.repository

import com.example.localmovielibrarz.cloud115.Cloud115FileItem
import com.example.localmovielibrarz.data.local.CloudVideoTaskDao
import com.example.localmovielibrarz.data.local.CloudVideoTaskEntity
import com.example.localmovielibrarz.data.local.CloudVideoTaskStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class CloudVideoTaskRepository(
    private val dao: CloudVideoTaskDao
) : PersistentWorkStore<CloudVideoTaskEntity> {
    fun observeTasks(): Flow<List<CloudVideoTaskEntity>> =
        dao.observeAll().distinctUntilChanged()

    fun observeUnfinishedTaskCount(): Flow<Int> =
        dao.observeUnfinishedCount().distinctUntilChanged()

    suspend fun hasUnfinishedTasks(): Boolean = withContext(Dispatchers.IO) {
        dao.countUnfinished() > 0
    }

    suspend fun awaitTerminal(pickcodes: Set<String>): Map<String, CloudVideoTaskEntity> {
        if (pickcodes.isEmpty()) return emptyMap()
        return observeTasks()
            .map { tasks -> tasks.filter { it.pickcode in pickcodes }.associateBy { it.pickcode } }
            .first { tasks ->
                pickcodes.all { pickcode -> tasks[pickcode]?.status in TERMINAL_STATUSES }
            }
    }

    /**
     * 取一次快照：这些 pickcode 里**已经跑到终态**的那些。
     *
     * 与 [awaitTerminal] 的区别是「不阻塞、不强求全到」。文件夹批量任务需要对每部影片
     * 逐个落断点（否则界面上那条进度条会一直停在 0/N 直到最后一个跑完），所以调用方是
     * 在轮询里用它捞「这一轮又多了哪几部」，而不是等一个全体集合。
     */
    suspend fun snapshotSettled(pickcodes: Set<String>): Map<String, CloudVideoTaskEntity> {
        if (pickcodes.isEmpty()) return emptyMap()
        return observeTasks().first()
            .asSequence()
            .filter { it.pickcode in pickcodes && it.status in TERMINAL_STATUSES }
            .associateBy { it.pickcode }
    }

    suspend fun unfinishedOwnedMovieIds(): Set<Long> = withContext(Dispatchers.IO) {
        dao.unfinishedOwnedMovieIds().toSet()
    }

    suspend fun enqueue(item: Cloud115FileItem, forceDistinct: Boolean): CloudVideoTaskEntity =
        withContext(Dispatchers.IO) {
            val pickcode = item.pickcode?.takeIf(String::isNotBlank)
                ?: error("这个视频没有 pickcode，无法加入任务")
            val now = System.currentTimeMillis()
            val existing = dao.get(pickcode)
            val task = CloudVideoTaskEntity(
                pickcode = pickcode,
                fileName = item.name,
                cid = item.cid,
                fid = item.fid,
                size = item.size,
                addedAt = item.addedAt,
                modifiedAt = item.modifiedAt,
                forceDistinct = forceDistinct,
                status = if (existing?.status == CloudVideoTaskStatus.Completed.name) {
                    CloudVideoTaskStatus.Completed.name
                } else {
                    CloudVideoTaskStatus.Pending.name
                },
                failureReason = null,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now
            )
            dao.upsert(task)
            task
        }

    override suspend fun prepareForStart() = withContext(Dispatchers.IO) {
        dao.prepareForStart(System.currentTimeMillis())
        Unit
    }

    override suspend fun claimNext(): CloudVideoTaskEntity? = withContext(Dispatchers.IO) {
        dao.claimNext()
    }

    override suspend fun markCompleted(work: CloudVideoTaskEntity) = withContext(Dispatchers.IO) {
        dao.updateStatus(work.pickcode, CloudVideoTaskStatus.Completed.name, null, System.currentTimeMillis())
    }

    override suspend fun markFailed(work: CloudVideoTaskEntity, reason: String) = withContext(Dispatchers.IO) {
        dao.updateStatus(work.pickcode, CloudVideoTaskStatus.Failed.name, reason, System.currentTimeMillis())
    }

    override suspend fun release(work: CloudVideoTaskEntity) = withContext(Dispatchers.IO) {
        dao.updateStatus(work.pickcode, CloudVideoTaskStatus.Paused.name, null, System.currentTimeMillis())
    }

    suspend fun clearCompletedTasks(): Int = withContext(Dispatchers.IO) { dao.deleteCompleted() }

    suspend fun clearUnfinishedTasks(): Int = withContext(Dispatchers.IO) { dao.deleteUnfinished() }

    private companion object {
        val TERMINAL_STATUSES = setOf(
            CloudVideoTaskStatus.Completed.name,
            CloudVideoTaskStatus.Failed.name
        )
    }
}
