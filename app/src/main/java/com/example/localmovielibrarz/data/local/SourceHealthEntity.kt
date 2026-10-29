package com.example.localmovielibrarz.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 来源健康度（跨进程持久化版）。
 *
 * 之前 `scraper.ScrapeSourceHealthTracker` 只活在内存里：App 一重启，刚被 403 的站点
 * 立刻又被整条优先级链打一遍，等于每次冷启动都重新撞一次风控。这里把「连续失败次数 +
 * 冷却截止时间」落库，进程重启后仍然生效；内存版继续保留作单次运行内的高频快路径，
 * 二者共用同一套 `ScrapeFailureKind` 的冷却时长。
 *
 * 主键用 source 字符串（`ScrapeSource.name`）而不是自增 id：一个来源只有一行状态，
 * 自增 id 只会带来「同一来源出现两行」的风险。
 */
@Entity(
    tableName = "source_health",
    indices = [Index(value = ["coolingUntilMillis"]), Index(value = ["updatedAtMillis"])]
)
data class SourceHealthEntity(
    @PrimaryKey
    val source: String,
    /** 连续失败次数；成功一次即清零。用来区分「偶发一次」与「持续被封」。 */
    val consecutiveFailures: Int = 0,
    /** 冷却截止时间戳；0 表示当前没有冷却。 */
    val coolingUntilMillis: Long = 0L,
    /** 最近一次失败的分类名（`ScrapeFailureKind.name`），冷却中展示给用户看。 */
    val lastFailureKind: String? = null,
    val lastFailureMessage: String? = null,
    val lastSuccessAtMillis: Long = 0L,
    val updatedAtMillis: Long = 0L
)
