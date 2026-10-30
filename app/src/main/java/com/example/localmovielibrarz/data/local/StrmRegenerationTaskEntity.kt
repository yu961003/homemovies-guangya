package com.example.localmovielibrarz.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * STRM 再生成任务（独立表 `strm_regeneration_tasks`）。
 *
 * 为什么独立成表，而不是继续复用 [CloudVideoTaskEntity]（`cloud_video_tasks`）：
 * 那张表的主键是 `pickcode`，语义是「这部片**有没有入过库**」——一旦 Completed 就再也不会
 * 重跑（见 `CloudVideoTaskRepository.enqueue` 对 Completed 的保留）。而「再生成」是另一件事：
 * 同一部片可以因为换了网盘、换了 STRM 根目录、改了下载前缀而**反复重写**同一个 .strm，
 * 需要独立记 attempts / failureReason / status，才能按「再生成」这个维度统计成功率与重试次数。
 * 两者混用会让「入库失败」与「再生成失败」在统计里串味，也会让入库断点被再生成重置。
 *
 * 字段与 `cloud_video_tasks` 的对应关系：pickcode/fileName/cid/fid/size 原样保留（都是 115 侧
 * 的稳定标识），去掉了 `addedAt`/`modifiedAt`（再生成不看网盘时间戳），新增了 `strmPath`
 * （再生成的目标落点）与 `attempts`（重试计数，失败时 +1）。
 *
 * DDL 必须与 `AppContainer.MIGRATION_21_22` 逐字一致：列名、INTEGER/TEXT、NOT NULL、主键、
 * 索引名。索引三件套：
 * - `pickcode` 唯一：一个 pickcode 同时只该有一条再生成任务，重复入队走 REPLACE 覆盖；
 * - `status`：worker 取 nextPending / 统计未完成数；
 * - `updatedAt`：设置页按最近活动排序。
 */
@Entity(
    tableName = "strm_regeneration_tasks",
    indices = [
        Index(value = ["pickcode"], unique = true),
        Index(value = ["status"]),
        Index(value = ["updatedAt"])
    ]
)
data class StrmRegenerationTaskEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    /** 115 侧的视频 pickcode；再生成就是拿它重新拼 `download_m3u` 下载地址。 */
    val pickcode: String,
    /** 网盘里的原始文件名（决定 STRM 的文件名与番号解析）。 */
    val fileName: String,
    /** 所在目录 cid；再生成失败重试时用于回查目录上下文。 */
    val cid: Long? = null,
    /** 文件 fid。 */
    val fid: Long? = null,
    /** 文件字节数；只用于展示与跳过策略，可空。 */
    val size: Long? = null,
    /**
     * 生成的 .strm 的 content:// URI。
     *
     * 与 `cloud_strm_records.strmUri` 的区别：那张表记「这部片当前有效的 STRM 是哪份」，
     * 本列记「本次再生成尝试**产出**了哪份」。两者在失败/覆盖时可能不一致，所以分开存。
     */
    val strmPath: String? = null,
    /**
     * 是否强制独立命名（`<文件名>_<pickcode>.strm`）。
     *
     * 再生成的入参之一，必须持久化：否则重试时无法还原当初是按「合并到番号目录」还是
     * 「独立成文件」生成的，重试可能产出与首次不同的落点。
     */
    val forceDistinct: Boolean = false,
    /** 任务状态，取值见 [StrmRegenerationTaskStatus]；以字符串落库，便于 SQL 里直接过滤。 */
    val status: String = StrmRegenerationTaskStatus.Pending.name,
    /** 最近一次失败原因；成功时清空。 */
    val failureReason: String? = null,
    /** 已尝试次数；失败时 +1，成功不清零（保留历史重试成本）。 */
    val attempts: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * 再生成任务状态机。
 *
 * 与 [CloudVideoTaskStatus] 刻意不共用：那张表有 Paused（入库队列可暂停续跑），
 * 再生成没有暂停语义——它要么还没跑（Pending）、正在跑（Running）、跑成了（Completed）、
 * 跑砸了（Failed）。共用枚举会让 DAO 里出现永远不会写入的 Paused 分支。
 */
enum class StrmRegenerationTaskStatus {
    Pending,
    Running,
    Completed,
    Failed
}
