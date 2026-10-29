package com.example.localmovielibrarz.data.repository

import com.example.localmovielibrarz.data.local.SourceHealthDao
import com.example.localmovielibrarz.data.local.SourceHealthEntity
import com.example.localmovielibrarz.scraper.ScrapeFailure
import com.example.localmovielibrarz.scraper.ScrapeFailureKind
import com.example.localmovielibrarz.scraper.ScrapeSource
import com.example.localmovielibrarz.scraper.ScrapeSourceHealthTracker
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * 来源健康度的持久化层。
 *
 * 与 `scraper.ScrapeSourceHealthTracker` 的关系：tracker 是**单次运行内的快路径**（纯内存、
 * 同步、无 IO），本类是**跨进程的真相源**。App 启动时把库里仍在冷却的来源回灌进 tracker
 * （[primeTracker]），运行中每成功/失败一次同步写库（[recordFailure]/[recordSuccess]）。
 *
 * 为什么不让 tracker 直接依赖 DAO：刮削链的核心判断（要不要跳过某个源）必须是同步且可单测的，
 * 一旦掺进 suspend 调用，`scrapeWithFallback` 的每个分支都要改成挂起点，既有调用方全得动。
 */
class SourceHealthRepository(
    private val dao: SourceHealthDao,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    /**
     * 冷启动回灌：把仍在冷却中的来源塞回内存 tracker。
     *
     * 只回灌 `coolingUntilMillis > now` 的行——已经过冷却的来源不需要占用内存条目，
     * 它们下次失败时会自然重新记账。
     */
    suspend fun primeTracker(tracker: ScrapeSourceHealthTracker): Int = withContext(ioDispatcher) {
        val now = clock()
        val cooling = dao.getCoolingDown(now)
        cooling.forEach { row ->
            val source = ScrapeSource.fromStoredName(row.source) ?: return@forEach
            val kind = row.lastFailureKind?.let { stored ->
                ScrapeFailureKind.entries.firstOrNull { it.name == stored }
            } ?: ScrapeFailureKind.Unknown
            tracker.restore(
                source = source,
                failure = ScrapeFailure(kind = kind, message = row.lastFailureMessage.orEmpty()),
                cooldownRemainingMillis = (row.coolingUntilMillis - now).coerceAtLeast(0L)
            )
        }
        cooling.size
    }

    /**
     * 记录一次失败：连续失败次数 +1，冷却取「已有截止时间」与「本次算出」的较大值。
     *
     * [cooldownMillisOverride] 由内存 tracker 传入：被拦类失败（403 / 验证页）的冷却要按
     * 「连续被拦第几次」做阶梯翻倍（见 `ScrapeCooldownPolicy`），那个计数只在 tracker 里。
     * 不传时退回 [ScrapeFailureKind.cooldownMillis] 的固定值——两层各算一遍必然分叉，
     * 所以正常路径都应把 tracker 的返回值带进来。
     */
    suspend fun recordFailure(
        source: ScrapeSource,
        failure: ScrapeFailure,
        cooldownMillisOverride: Long? = null
    ): Int =
        withContext(ioDispatcher) {
            val now = clock()
            val current = dao.get(source.name)
            val consecutive = (current?.consecutiveFailures ?: 0) + 1
            val cooldownMillis = cooldownMillisOverride ?: failure.cooldownMillis
            val computedUntil = if (cooldownMillis > 0L) now + cooldownMillis else 0L
            val previousUntil = current?.coolingUntilMillis ?: 0L
            val until = maxOf(previousUntil, computedUntil)
            dao.upsert(
                SourceHealthEntity(
                    source = source.name,
                    consecutiveFailures = consecutive,
                    coolingUntilMillis = until,
                    lastFailureKind = failure.kind.name,
                    lastFailureMessage = failure.message.take(LAST_MESSAGE_LIMIT),
                    lastSuccessAtMillis = current?.lastSuccessAtMillis ?: 0L,
                    updatedAtMillis = now
                )
            )
            consecutive
        }

    /** 成功一次：冷却与连续失败一起清零，但保留 lastSuccessAtMillis 供设置页展示。 */
    suspend fun recordSuccess(source: ScrapeSource) = withContext(ioDispatcher) {
        val now = clock()
        val current = dao.get(source.name)
        dao.upsert(
            SourceHealthEntity(
                source = source.name,
                consecutiveFailures = 0,
                coolingUntilMillis = 0L,
                lastFailureKind = null,
                lastFailureMessage = null,
                lastSuccessAtMillis = now,
                updatedAtMillis = now
            )
        )
        if (current == null) {
            // 没有历史行时也要落一行：否则「一直成功的来源」在设置页里查不到，
            // 用户会以为这个源根本没被用过。
            Unit
        }
    }

    suspend fun consecutiveFailures(source: ScrapeSource): Int = withContext(ioDispatcher) {
        dao.get(source.name)?.consecutiveFailures ?: 0
    }

    suspend fun coolingRemaining(source: ScrapeSource): Long = withContext(ioDispatcher) {
        val until = dao.get(source.name)?.coolingUntilMillis ?: 0L
        (until - clock()).coerceAtLeast(0L)
    }

    /**
     * 给设置页用的可读快照：`Dmm=Forbidden（剩余 28 分钟）`。
     * 与内存 tracker 的 `snapshot()` 同格式，但覆盖「上次运行留下的冷却」。
     */
    suspend fun readableSnapshot(): Map<ScrapeSource, String> = withContext(ioDispatcher) {
        val now = clock()
        dao.getAll().mapNotNull { row ->
            val source = ScrapeSource.fromStoredName(row.source) ?: return@mapNotNull null
            val remaining = (row.coolingUntilMillis - now).coerceAtLeast(0L)
            val kindText = row.lastFailureKind ?: "正常"
            val text = if (remaining > 0L) {
                "$kindText（剩余 ${(remaining / 60_000L).coerceAtLeast(1L)} 分钟）"
            } else {
                kindText
            }
            source to text
        }.toMap()
    }

    suspend fun getAll(): List<SourceHealthEntity> = withContext(ioDispatcher) { dao.getAll() }

    fun observeAll(): Flow<List<SourceHealthEntity>> = dao.observeAll()

    suspend fun clear(source: ScrapeSource) = withContext(ioDispatcher) { dao.delete(source.name) }

    /** 清理早已过冷却且没有失败历史的行；设置页的「重置来源状态」也会调它。 */
    suspend fun pruneHealthy(now: Long = clock()): Int = withContext(ioDispatcher) {
        dao.deleteHealthyBefore(now)
    }

    private companion object {
        const val LAST_MESSAGE_LIMIT = 300
    }
}
