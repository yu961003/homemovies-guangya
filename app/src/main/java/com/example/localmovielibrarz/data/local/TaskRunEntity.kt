package com.example.localmovielibrarz.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 任务运行账本（task_runs）：所有后台任务共用的「这一次跑得怎么样」记录。
 *
 * 鸭影原有的三套任务模型（`cloud_folder_batch_tasks`、`cloud_video_tasks`、`movies.scrapeTaskStatus`）
 * 各自只描述「任务当前处于什么状态」，没有任何一张表记录「第几次尝试、被谁占用、
 * 上次为什么中断、走到第几步」。于是冷启动恢复只能各写各的启发式，设置页也拼不出
 * 一句完整的人话。这张表不替代它们——它是旁路的**运行历史**：业务表继续管自己的状态，
 * 账本负责跨任务的统一视图、去重与中断恢复。
 *
 * 为什么 dedupeKey 不建唯一索引：Room 不支持部分唯一索引（peach 用是 `WHERE state IN (...)`
 * 的部分索引），而「同一 dedupeKey 只允许一个活跃 run」这条约束必须同时容纳终态历史行。
 * 因此互斥由 `TaskRunRepository` 在事务内「查活跃行 → 落新行」+ 进程内 Mutex 保证，
 * 失败模式（两个进程同时抢）在单机 App 里不成立：Room 写入是单进程串行的。
 */
@Entity(
    tableName = "task_runs",
    indices = [
        Index(value = ["dedupeKey"]),
        Index(value = ["state"]),
        Index(value = ["kind"]),
        Index(value = ["updatedAtMillis"]),
        Index(value = ["lastHeartbeatAtMillis"])
    ]
)
data class TaskRunEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** `tasks.TaskKind.name`，例如 "FolderBatch"、"MovieScrape"。 */
    val kind: String,
    /** `tasks.TaskRunPolicy.dedupeKeyOf(kind, scope)` 的产物：同一种任务、同一个对象只应有一个活跃 run。 */
    val dedupeKey: String,
    /** 展示用标题，例如文件夹名、"ABC-123"。 */
    val title: String,
    /** `tasks.TaskState.name`：Pending/Running/Paused/Succeeded/Failed/Cancelled。 */
    val state: String,
    /** 当前步骤的人类可读描述，例如 "枚举候选"、"刮削第 3/12 部"。 */
    val step: String? = null,
    val completedSteps: Int = 0,
    val totalSteps: Int = 0,
    /** 第几次尝试（同一 dedupeKey 的 run 计数，从 1 开始）。 */
    val attempts: Int = 1,
    val failureMessage: String? = null,
    /**
     * 占用者令牌。
     *
     * 用随机串而不是进程 id：Android 进程重启后 pid 可能复用，靠 pid 判断「是不是同一个
     * 执行者」会把新进程误认成旧进程，从而把刚恢复的任务又判成「别人在跑」。令牌在每次
     * `claim` 时重新生成，只有持有者能续心跳与改状态。
     */
    val ownerToken: String? = null,
    val startedAtMillis: Long = 0L,
    val lastHeartbeatAtMillis: Long = 0L,
    val updatedAtMillis: Long = 0L
)

/** 进程内生成占用者令牌；不落盘、不跨进程复用。 */
object TaskRunOwnerTokens {
    fun newToken(): String = java.util.UUID.randomUUID().toString()
}
