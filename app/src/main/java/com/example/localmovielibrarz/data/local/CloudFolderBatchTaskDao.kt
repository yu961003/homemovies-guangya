package com.example.localmovielibrarz.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CloudFolderBatchTaskDao {
    @Query(
        """
        SELECT * FROM cloud_folder_batch_tasks
        ORDER BY
            CASE status
                WHEN 'Running' THEN 0
                WHEN 'Pending' THEN 1
                WHEN 'Paused' THEN 2
                WHEN 'Failed' THEN 3
                WHEN 'Cancelled' THEN 4
                ELSE 5
            END,
            updatedAt DESC
        """
    )
    fun observeAll(): Flow<List<CloudFolderBatchTaskEntity>>

    @Query("SELECT COUNT(*) FROM cloud_folder_batch_tasks WHERE status IN (:statuses)")
    fun observeTaskCount(statuses: List<String>): Flow<Int>

    @Query("SELECT * FROM cloud_folder_batch_tasks WHERE id = :id LIMIT 1")
    suspend fun get(id: Long): CloudFolderBatchTaskEntity?

    @Query(
        """
        SELECT * FROM cloud_folder_batch_tasks
        WHERE folderCid = :folderCid AND status != 'Completed'
        ORDER BY createdAt DESC
        LIMIT 1
        """
    )
    suspend fun getUnfinishedByFolderCid(folderCid: Long): CloudFolderBatchTaskEntity?

    @Query(
        """
        SELECT * FROM cloud_folder_batch_tasks
        WHERE status IN (:statuses)
        ORDER BY createdAt ASC
        LIMIT 1
        """
    )
    suspend fun nextRunnable(statuses: List<String>): CloudFolderBatchTaskEntity?

    /**
     * 进程启动或手动开始时的僵尸恢复。
     *
     * 覆盖 Running（进程被杀）/ Paused（用户暂停）/ Failed（跑挂）/ Cancelled（取消后重新开始）
     * 四种终止态，统一回到 Pending。
     *
     * ★ 刻意**不清空** failureMessage 与 processedPickcodesCsv：
     * 前者让用户还能看到上次中断的原因，后者是断点续跑的唯一真相源。
     * 早期实现里的 `failureMessage = NULL` 会把「上次为什么停」直接抹掉，属于可观测性回退。
     */
    @Query(
        """
        UPDATE cloud_folder_batch_tasks
        SET status = 'Pending', updatedAt = :updatedAt
        WHERE status IN ('Running', 'Paused', 'Failed', 'Cancelled')
        """
    )
    suspend fun prepareForStart(updatedAt: Long): Int

    /** 被异常中断（进程被杀，残留 Running）的任务数：只有这类任务才值得启动时自动续跑。 */
    @Query("SELECT COUNT(*) FROM cloud_folder_batch_tasks WHERE status = 'Running'")
    suspend fun countInterrupted(): Int

    /**
     * 把僵尸 Running 行降级成 Paused（F-3）。
     *
     * 场景：进程被杀或 runner 崩溃后，库里还留着 status='Running' 却没有任何协程在跑。
     * 此时 UI 会一直显示「运行中」，用户既等不到完成、也不敢点继续。
     * 手动刷新（确认 runner 确实没在跑）就把它们改成 Paused，并写明原因与可续跑提示。
     *
     * ★ 只改状态与文案，**不动** processedPickcodesCsv —— 断点必须留着。
     */
    @Query(
        """
        UPDATE cloud_folder_batch_tasks
        SET status = 'Paused', failureMessage = :message, updatedAt = :updatedAt
        WHERE status = 'Running'
        """
    )
    suspend fun markRunningAsPaused(updatedAt: Long, message: String): Int

    @Query("SELECT COUNT(*) FROM cloud_folder_batch_tasks WHERE status IN (:statuses)")
    suspend fun countByStatuses(statuses: List<String>): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(task: CloudFolderBatchTaskEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(task: CloudFolderBatchTaskEntity): Long

    @Query("DELETE FROM cloud_folder_batch_tasks WHERE status = 'Completed'")
    suspend fun deleteCompleted(): Int

    @Query("DELETE FROM cloud_folder_batch_tasks WHERE status IN (:statuses)")
    suspend fun deleteByStatuses(statuses: List<String>): Int
}
