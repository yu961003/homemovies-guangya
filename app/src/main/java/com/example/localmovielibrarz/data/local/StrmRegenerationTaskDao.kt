package com.example.localmovielibrarz.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * `strm_regeneration_tasks` 的读写入口。
 *
 * 设计约束（与 [CloudVideoTaskDao] 对照着看）：
 * - 本表主键是自增 `id`，不是 `pickcode`；但 `pickcode` 上有唯一索引，所以「同一部片只留一条
 *   再生成任务」由索引保证，`upsert` 用 `OnConflictStrategy.REPLACE` 覆盖整行。
 *   ⚠️ REPLACE 会**删旧行插新行**，自增 id 与 createdAt 会被重置；需要保留历史时，
 *   由调用方（[com.example.localmovielibrarz.data.repository.StrmRegenerationTaskRepository]）
 *   先 `get` 再带上原 id/createdAt/attempts 回写，DAO 这里只做最朴素的落库。
 * - 状态全部用字符串比较（'Pending' / 'Running' / 'Completed' / 'Failed'）而不是枚举，
 *   因为 Room 不认识我们手写的 enum，除非加 TypeConverter；字符串列让迁移与手写 SQL 都简单。
 */
@Dao
interface StrmRegenerationTaskDao {
    /** 设置页列表：Running 最先、其次 Pending、Failed；同状态按最近更新倒序。 */
    @Query(
        """
        SELECT * FROM strm_regeneration_tasks
        ORDER BY
            CASE status
                WHEN 'Running' THEN 0
                WHEN 'Pending' THEN 1
                WHEN 'Failed' THEN 2
                ELSE 3
            END,
            updatedAt DESC
        """
    )
    fun observeAll(): Flow<List<StrmRegenerationTaskEntity>>

    /** 未完成数量（Pending + Running）；用于设置页角标，不订阅全表。 */
    @Query("SELECT COUNT(*) FROM strm_regeneration_tasks WHERE status IN ('Pending', 'Running')")
    fun observeUnfinishedCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM strm_regeneration_tasks WHERE status IN ('Pending', 'Running')")
    suspend fun countUnfinished(): Int

    @Query("SELECT COUNT(*) FROM strm_regeneration_tasks")
    suspend fun countAll(): Int

    @Query("SELECT COUNT(*) FROM strm_regeneration_tasks WHERE status = :status")
    suspend fun countByStatus(status: String): Int

    @Query("SELECT * FROM strm_regeneration_tasks WHERE status = :status ORDER BY updatedAt DESC")
    suspend fun listByStatus(status: String): List<StrmRegenerationTaskEntity>

    @Query("SELECT * FROM strm_regeneration_tasks WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): StrmRegenerationTaskEntity?

    @Query("SELECT * FROM strm_regeneration_tasks WHERE pickcode = :pickcode LIMIT 1")
    suspend fun getByPickcode(pickcode: String): StrmRegenerationTaskEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(task: StrmRegenerationTaskEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(tasks: List<StrmRegenerationTaskEntity>): List<Long>

    @Query(
        "SELECT * FROM strm_regeneration_tasks WHERE status = 'Pending' " +
            "ORDER BY createdAt ASC LIMIT 1"
    )
    suspend fun nextPending(): StrmRegenerationTaskEntity?

    /**
     * 抢占下一条 Pending：先查再 CAS 式改 Running。
     *
     * 与 [CloudVideoTaskDao.claimNext] 同一个套路——两个 worker 同时看到同一条 Pending 时，
     * 只有 UPDATE 影响行数为 1 的那个算抢到，另一个返回 null 继续抢下一条。
     */
    @Query(
        """
        UPDATE strm_regeneration_tasks
        SET status = 'Running', failureReason = NULL, updatedAt = :updatedAt
        WHERE id = :id AND status = 'Pending'
        """
    )
    suspend fun markRunningIfPending(id: Long, updatedAt: Long): Int

    @Transaction
    suspend fun claimNext(): StrmRegenerationTaskEntity? {
        val task = nextPending() ?: return null
        return if (markRunningIfPending(task.id, System.currentTimeMillis()) == 1) {
            task.copy(status = StrmRegenerationTaskStatus.Running.name, failureReason = null)
        } else {
            null
        }
    }

    @Query(
        """
        UPDATE strm_regeneration_tasks
        SET status = 'Completed', strmPath = :strmPath, failureReason = NULL, updatedAt = :updatedAt
        WHERE pickcode = :pickcode
        """
    )
    suspend fun markCompletedIfExists(
        pickcode: String,
        strmPath: String?,
        updatedAt: Long
    ): Int

    /**
     * 记一次失败：`attempts + 1` 与状态切换在同一条 UPDATE 里完成。
     *
     * 为什么在 SQL 里自增而不是「读出来 +1 再写回」：worker 可能中断后重跑，
     * 读改写会丢掉并发期间别人的那次 +1；`attempts = attempts + 1` 是原子的。
     */
    @Query(
        """
        UPDATE strm_regeneration_tasks
        SET status = 'Failed', failureReason = :failureReason,
            attempts = attempts + 1, updatedAt = :updatedAt
        WHERE pickcode = :pickcode
        """
    )
    suspend fun markFailedAndIncrement(pickcode: String, failureReason: String?, updatedAt: Long): Int

    @Query("DELETE FROM strm_regeneration_tasks WHERE pickcode = :pickcode")
    suspend fun deleteByPickcode(pickcode: String): Int

    @Query("DELETE FROM strm_regeneration_tasks WHERE status = 'Completed'")
    suspend fun deleteCompleted(): Int

    @Query("DELETE FROM strm_regeneration_tasks WHERE status != 'Completed'")
    suspend fun deleteUnfinished(): Int

    @Query("DELETE FROM strm_regeneration_tasks")
    suspend fun deleteAll(): Int
}
