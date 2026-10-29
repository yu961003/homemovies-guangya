package com.example.localmovielibrarz.data.repository

import com.example.localmovielibrarz.cloud115.Cloud115Client
import com.example.localmovielibrarz.data.local.DirectLinkDao
import com.example.localmovielibrarz.data.local.DirectLinkEntity
import com.example.localmovielibrarz.playback.DirectLinkExpiryParser
import com.example.localmovielibrarz.tasks.TaskKind
import com.example.localmovielibrarz.tasks.TaskState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DirectLinkRepository(
    private val directLinkDao: DirectLinkDao,
    private val cloud115Client: Cloud115Client,
    /**
     * 统一任务账本；默认 null 让既有构造点（含单测）不必立刻跟进。
     *
     * 直链刷新没有独立的「开始刷新」按钮——它是播放/整理路径上命中缓存过期时**被动**发生的，
     * 所以账本只能记在真正发请求的那一处，用一条 LinkRefresh run 表达「这个 pickcode 刚刚续签过」。
     */
    private val taskRunRepository: TaskRunRepository? = null
) {
    suspend fun resolve(pickcode: String, forceRefresh: Boolean = false): String = withContext(Dispatchers.IO) {
        val now = nowSeconds()
        val cached = if (forceRefresh) null else directLinkDao.get(pickcode)
        if (cached != null && cached.url.isNotBlank() && cached.expiresAt > now) {
            return@withContext cached.url
        }

        // 只有真的要走网络时才记一行：缓存命中不算「刷新」。否则每次播放都会刷出一条
        // 「直链刷新成功」，把真正的续签淹没，最近任务列表也就失去了筛查价值。
        val ledgerRunId = claimLedgerRun(pickcode)
        val directUrl = try {
            cloud115Client.fetchDirectUrl(pickcode)
        } catch (error: CancellationException) {
            finishLedgerRun(ledgerRunId, TaskState.Paused, "续签被中断")
            throw error
        } catch (error: Throwable) {
            finishLedgerRun(ledgerRunId, TaskState.Failed, error.message ?: error::class.java.simpleName)
            throw error
        }
        val expiresAt = DirectLinkExpiryParser.parseExpiresAt(directUrl, now)
        directLinkDao.upsert(
            DirectLinkEntity(
                pickcode = pickcode,
                url = directUrl,
                expiresAt = expiresAt,
                updatedAt = now
            )
        )
        finishLedgerRun(ledgerRunId, TaskState.Succeeded, "直链已续签，有效期至 $expiresAt")
        directUrl
    }

    suspend fun invalidate(pickcode: String) = withContext(Dispatchers.IO) {
        directLinkDao.delete(pickcode)
    }

    private suspend fun claimLedgerRun(pickcode: String): Long {
        val ledger = taskRunRepository ?: return NO_LEDGER_RUN
        return runCatching {
            ledger.claim(
                kind = TaskKind.LinkRefresh,
                scope = pickcode,
                title = "直链续签 $pickcode",
                totalSteps = 1
            )?.id ?: NO_LEDGER_RUN
        }.getOrElse { NO_LEDGER_RUN }
    }

    private suspend fun finishLedgerRun(runId: Long, state: TaskState, message: String?) {
        if (runId == NO_LEDGER_RUN) return
        val ledger = taskRunRepository ?: return
        runCatching {
            if (state == TaskState.Paused) {
                ledger.notifyPaused(runId, message)
            } else {
                ledger.updateProgress(
                    id = runId,
                    state = TaskState.Running,
                    step = message,
                    completedSteps = 1,
                    totalSteps = 1
                )
                ledger.finish(runId, state, message)
            }
        }
    }

    private fun nowSeconds(): Long = System.currentTimeMillis() / 1000L
}

/** 见 `CloudVideoAddProcessor` 末尾同名常量上的说明：各 runner 各自 private，不共享。 */
private const val NO_LEDGER_RUN = -1L
