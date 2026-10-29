package com.example.localmovielibrarz.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface MovieFieldProvenanceDao {
    @Query("SELECT * FROM movie_field_provenance WHERE movieId = :movieId")
    suspend fun getForMovie(movieId: Long): List<MovieFieldProvenanceEntity>

    @Query(
        "SELECT * FROM movie_field_provenance WHERE movieId = :movieId AND fieldKey = :fieldKey LIMIT 1"
    )
    suspend fun get(movieId: Long, fieldKey: String): MovieFieldProvenanceEntity?

    @Query("SELECT * FROM movie_field_provenance WHERE movieId IN (:movieIds)")
    suspend fun getForMovies(movieIds: List<Long>): List<MovieFieldProvenanceEntity>

    @Upsert
    suspend fun upsert(rows: List<MovieFieldProvenanceEntity>)

    @Query("DELETE FROM movie_field_provenance WHERE movieId = :movieId")
    suspend fun deleteForMovie(movieId: Long)

    /** 影片被删除时清账，避免账本随库无限增长。 */
    @Query(
        "DELETE FROM movie_field_provenance WHERE movieId NOT IN (SELECT id FROM movies)"
    )
    suspend fun deleteOrphans(): Int

    @Query("SELECT COUNT(*) FROM movie_field_provenance")
    fun observeCount(): Flow<Int>
}

@Dao
interface EntityAliasDao {
    @Query("SELECT * FROM entity_aliases WHERE kind = :kind ORDER BY displayName ASC")
    suspend fun getByKind(kind: String): List<EntityAliasEntity>

    @Query(
        "SELECT * FROM entity_aliases WHERE kind = :kind AND normalizedKey = :normalizedKey LIMIT 1"
    )
    suspend fun get(kind: String, normalizedKey: String): EntityAliasEntity?

    @Query("SELECT * FROM entity_aliases WHERE canonicalKey = :canonicalKey")
    suspend fun getByCanonicalKey(canonicalKey: String): List<EntityAliasEntity>

    @Upsert
    suspend fun upsert(rows: List<EntityAliasEntity>)

    @Query("SELECT COUNT(*) FROM entity_aliases")
    fun observeCount(): Flow<Int>

    @Query("DELETE FROM entity_aliases")
    suspend fun clear()
}

@Dao
interface SourceHealthDao {
    @Query("SELECT * FROM source_health WHERE source = :source LIMIT 1")
    suspend fun get(source: String): SourceHealthEntity?

    @Query("SELECT * FROM source_health")
    suspend fun getAll(): List<SourceHealthEntity>

    @Query("SELECT * FROM source_health")
    fun observeAll(): Flow<List<SourceHealthEntity>>

    @Query(
        "SELECT * FROM source_health WHERE coolingUntilMillis > :now ORDER BY coolingUntilMillis ASC"
    )
    suspend fun getCoolingDown(now: Long): List<SourceHealthEntity>

    @Upsert
    suspend fun upsert(row: SourceHealthEntity)

    @Query("DELETE FROM source_health WHERE source = :source")
    suspend fun delete(source: String)

    /** 清理早已过冷却、且没有失败历史的行，避免设置页展示陈年脏数据。 */
    @Query("DELETE FROM source_health WHERE coolingUntilMillis <= :now AND consecutiveFailures = 0")
    suspend fun deleteHealthyBefore(now: Long): Int
}

@Dao
interface TaskRunDao {
    @Query("SELECT * FROM task_runs ORDER BY updatedAtMillis DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<TaskRunEntity>>

    @Query("SELECT * FROM task_runs WHERE id = :id LIMIT 1")
    suspend fun get(id: Long): TaskRunEntity?

    /** 同一去重键下处于这些状态的行——「是否已有活跃 run」的唯一判定依据。 */
    @Query(
        "SELECT * FROM task_runs WHERE dedupeKey = :dedupeKey AND state IN (:states) " +
            "ORDER BY updatedAtMillis DESC LIMIT 1"
    )
    suspend fun getByDedupeKeyInStates(dedupeKey: String, states: List<String>): TaskRunEntity?

    @Query("SELECT * FROM task_runs WHERE dedupeKey = :dedupeKey ORDER BY id DESC LIMIT 1")
    suspend fun getLatestByDedupeKey(dedupeKey: String): TaskRunEntity?

    @Query("SELECT COUNT(*) FROM task_runs WHERE dedupeKey = :dedupeKey")
    suspend fun countByDedupeKey(dedupeKey: String): Int

    @Query("SELECT * FROM task_runs WHERE state IN (:states)")
    suspend fun getByStates(states: List<String>): List<TaskRunEntity>

    @Query("SELECT * FROM task_runs WHERE state IN (:states) ORDER BY updatedAtMillis DESC")
    fun observeByStates(states: List<String>): Flow<List<TaskRunEntity>>

    @Query("SELECT COUNT(*) FROM task_runs WHERE state IN (:states)")
    suspend fun countByStates(states: List<String>): Int

    @Query("SELECT COUNT(*) FROM task_runs WHERE state IN (:states)")
    fun observeCountByStates(states: List<String>): Flow<Int>

    @Insert
    suspend fun insert(row: TaskRunEntity): Long

    @Update
    suspend fun update(row: TaskRunEntity)

    @Query(
        "UPDATE task_runs SET state = :state, step = :step, completedSteps = :completedSteps, " +
            "totalSteps = :totalSteps, failureMessage = :failureMessage, " +
            "lastHeartbeatAtMillis = :now, updatedAtMillis = :now WHERE id = :id"
    )
    suspend fun updateProgress(
        id: Long,
        state: String,
        step: String?,
        completedSteps: Int,
        totalSteps: Int,
        failureMessage: String?,
        now: Long
    )

    @Query(
        "UPDATE task_runs SET lastHeartbeatAtMillis = :now, updatedAtMillis = :now WHERE id = :id"
    )
    suspend fun touch(id: Long, now: Long)

    @Query("UPDATE task_runs SET state = :state, failureMessage = :message, updatedAtMillis = :now WHERE id = :id")
    suspend fun updateState(id: Long, state: String, message: String?, now: Long)

    /** 只清历史：保留最近 [keep] 条，其余终态行删除。活跃行永不删除。 */
    @Query(
        "DELETE FROM task_runs WHERE state IN (:terminalStates) AND id NOT IN " +
            "(SELECT id FROM task_runs ORDER BY updatedAtMillis DESC LIMIT :keep)"
    )
    suspend fun deleteOldTerminalRuns(terminalStates: List<String>, keep: Int): Int
}
