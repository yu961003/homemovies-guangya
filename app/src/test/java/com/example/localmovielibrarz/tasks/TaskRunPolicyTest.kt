package com.example.localmovielibrarz.tasks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 账本策略的边界测试。
 *
 * 固定 [NOW] 与显式阈值，避免任何用例依赖真实时钟；每条断言都对应一个线上会真实发生的场景：
 * 重复入队、卡死接管、冷启动恢复、横幅计数、断点进度。
 */
class TaskRunPolicyTest {

    @Test
    fun dedupeKeyNormalizesScopeAndKeepsKindPrefix() {
        assertEquals(
            "FolderBatch:pickcode:abc123",
            TaskRunPolicy.dedupeKeyOf(TaskKind.FolderBatch, "  Pickcode:ABC123  ")
        )
        // 大小写不同的同一作用域必须落到同一个键，否则同一任务会被开两条 run。
        assertEquals(
            TaskRunPolicy.dedupeKeyOf(TaskKind.VideoAdd, "PICKCODE:xyz"),
            TaskRunPolicy.dedupeKeyOf(TaskKind.VideoAdd, " pickcode:XYZ ")
        )
    }

    @Test
    fun dedupeKeyUsesDefaultForBlankScopeAndKeepsInternalSpacing() {
        assertEquals("MovieScrape:default", TaskRunPolicy.dedupeKeyOf(TaskKind.MovieScrape, ""))
        assertEquals("LinkRefresh:default", TaskRunPolicy.dedupeKeyOf(TaskKind.LinkRefresh, "   "))
        // 内部空格属于业务内容（文件名/路径），不能被规范化吃掉。
        assertEquals(
            "MetadataRefresh:star wars",
            TaskRunPolicy.dedupeKeyOf(TaskKind.MetadataRefresh, " Star Wars ")
        )
        // 不同种类即使作用域相同也必须是不同键。
        assertTrue(
            TaskRunPolicy.dedupeKeyOf(TaskKind.FolderBatch, "same") !=
                TaskRunPolicy.dedupeKeyOf(TaskKind.VideoAdd, "same")
        )
    }

    @Test
    fun startsWhenNoActiveRunExists() {
        val decision = TaskRunPolicy.shouldStartNewRun(null, TaskState.Pending, NOW, THRESHOLD)
        assertEquals(TaskRunPolicy.Decision(true, "no_active_run"), decision)
    }

    @Test
    fun blocksNewRunWhileRunningWithFreshHeartbeat() {
        val running = snapshot(state = TaskState.Running, lastHeartbeatAtMillis = NOW - 1_000L)
        val decision = TaskRunPolicy.shouldStartNewRun(running, TaskState.Pending, NOW, THRESHOLD)
        assertEquals(TaskRunPolicy.Decision(false, "active_run_in_progress"), decision)
    }

    @Test
    fun blocksNewRunWhilePaused() {
        // Paused 持有断点，新 run 顶掉它会让续跑进度被覆盖。
        val paused = snapshot(state = TaskState.Paused)
        val decision = TaskRunPolicy.shouldStartNewRun(paused, TaskState.Running, NOW, THRESHOLD)
        assertEquals(TaskRunPolicy.Decision(false, "active_run_in_progress"), decision)
    }

    @Test
    fun blocksDuplicateWhilePending() {
        // 已有一条排队记录时应复用它，而不是再插一条。
        val pending = snapshot(state = TaskState.Pending)
        val decision = TaskRunPolicy.shouldStartNewRun(pending, TaskState.Pending, NOW, THRESHOLD)
        assertEquals(TaskRunPolicy.Decision(false, "active_run_in_progress"), decision)
    }

    @Test
    fun allowsTakeOverOfStalledRunningRun() {
        val stalled = snapshot(
            state = TaskState.Running,
            lastHeartbeatAtMillis = NOW - THRESHOLD - 1L
        )
        val decision = TaskRunPolicy.shouldStartNewRun(stalled, TaskState.Running, NOW, THRESHOLD)
        assertEquals(TaskRunPolicy.Decision(true, "take_over_stalled"), decision)
    }

    @Test
    fun stalledBoundaryIsStrictlyGreaterThanThreshold() {
        val exactlyAtThreshold = snapshot(
            state = TaskState.Running,
            lastHeartbeatAtMillis = NOW - THRESHOLD
        )
        // 恰好等于阈值仍算活着，不做接管。
        assertEquals(
            TaskRunPolicy.Decision(false, "active_run_in_progress"),
            TaskRunPolicy.shouldStartNewRun(exactlyAtThreshold, TaskState.Running, NOW, THRESHOLD)
        )
    }

    @Test
    fun fallsBackToUpdatedAtWhenHeartbeatIsMissing() {
        val legacyRun = snapshot(
            state = TaskState.Running,
            lastHeartbeatAtMillis = 0L,
            updatedAtMillis = NOW - THRESHOLD - 5_000L
        )
        assertEquals(
            TaskRunPolicy.Decision(true, "take_over_stalled"),
            TaskRunPolicy.shouldStartNewRun(legacyRun, TaskState.Pending, NOW, THRESHOLD)
        )
    }

    @Test
    fun heartbeatWinsOverStaleUpdatedAt() {
        val run = snapshot(
            state = TaskState.Running,
            lastHeartbeatAtMillis = NOW - 1_000L,
            updatedAtMillis = NOW - THRESHOLD * 5
        )
        assertEquals(
            TaskRunPolicy.Decision(false, "active_run_in_progress"),
            TaskRunPolicy.shouldStartNewRun(run, TaskState.Pending, NOW, THRESHOLD)
        )
    }

    @Test
    fun allowsNewRunAfterPreviousRunFinished() {
        listOf(TaskState.Succeeded, TaskState.Failed, TaskState.Cancelled).forEach { terminal ->
            val run = snapshot(state = terminal)
            val decision = TaskRunPolicy.shouldStartNewRun(run, TaskState.Pending, NOW, THRESHOLD)
            assertEquals(
                "终态 $terminal 之后应允许开新 run",
                TaskRunPolicy.Decision(true, "previous_run_finished"),
                decision
            )
        }
    }

    @Test
    fun rejectsInvalidRequestedState() {
        // Paused / 终态都不是「开跑」入口，传错必须显式拒绝而不是静默纠正。
        assertEquals(
            TaskRunPolicy.Decision(false, "invalid_requested_state"),
            TaskRunPolicy.shouldStartNewRun(null, TaskState.Paused, NOW, THRESHOLD)
        )
        assertEquals(
            TaskRunPolicy.Decision(false, "invalid_requested_state"),
            TaskRunPolicy.shouldStartNewRun(null, TaskState.Succeeded, NOW, THRESHOLD)
        )
    }

    @Test
    fun recoverDowngradesFreshRunningRunToPaused() {
        // 进程重启后不可能还有同进程协程：即使心跳很新，也必须降级为 Paused。
        val fresh = snapshot(id = 7L, state = TaskState.Running, lastHeartbeatAtMillis = NOW)
        val actions = TaskRunPolicy.recoverInterrupted(listOf(fresh), NOW)
        assertEquals(
            listOf(TaskRunPolicy.RecoveryAction(7L, TaskState.Running, TaskState.Paused, "interrupted_restart")),
            actions
        )
        // 中断不是失败：绝不能出现 Failed。
        assertTrue(actions.none { it.to == TaskState.Failed })
    }

    @Test
    fun recoverDowngradesStalledRunningRunWithStalledReason() {
        val stalled = snapshot(
            id = 8L,
            state = TaskState.Running,
            lastHeartbeatAtMillis = NOW - THRESHOLD - 1L
        )
        val actions = TaskRunPolicy.recoverInterrupted(listOf(stalled), NOW)
        assertEquals(
            listOf(TaskRunPolicy.RecoveryAction(8L, TaskState.Running, TaskState.Paused, "interrupted_stalled")),
            actions
        )
    }

    @Test
    fun recoverSkipsPendingPausedAndTerminalRuns() {
        val runs = listOf(
            snapshot(id = 1L, state = TaskState.Pending, lastHeartbeatAtMillis = 0L),
            snapshot(id = 2L, state = TaskState.Paused, lastHeartbeatAtMillis = 0L),
            snapshot(id = 3L, state = TaskState.Succeeded),
            snapshot(id = 4L, state = TaskState.Failed),
            snapshot(id = 5L, state = TaskState.Cancelled)
        )
        assertTrue(TaskRunPolicy.recoverInterrupted(runs, NOW).isEmpty())
    }

    @Test
    fun recoverIsIdempotentAndPreservesInputOrder() {
        val first = snapshot(id = 10L, state = TaskState.Running, lastHeartbeatAtMillis = NOW - THRESHOLD - 1L)
        val second = snapshot(id = 11L, state = TaskState.Running, lastHeartbeatAtMillis = NOW)
        val third = snapshot(id = 12L, state = TaskState.Pending)

        val actions = TaskRunPolicy.recoverInterrupted(listOf(first, second, third), NOW)
        assertEquals(listOf(10L, 11L), actions.map { it.runId })

        // 落库后（都变成 Paused）再跑一次恢复不应产生第二批动作。
        val recovered = listOf(
            first.copy(state = TaskState.Paused),
            second.copy(state = TaskState.Paused),
            third
        )
        assertTrue(TaskRunPolicy.recoverInterrupted(recovered, NOW).isEmpty())
    }

    @Test
    fun summarizeCountsStatesAndTreatsStalledAsActiveSubset() {
        val runs = listOf(
            snapshot(id = 1L, state = TaskState.Running, lastHeartbeatAtMillis = NOW),
            snapshot(id = 2L, state = TaskState.Running, lastHeartbeatAtMillis = NOW - THRESHOLD - 1L),
            snapshot(id = 3L, state = TaskState.Paused, lastHeartbeatAtMillis = 0L),
            snapshot(id = 4L, state = TaskState.Failed),
            snapshot(id = 5L, state = TaskState.Succeeded),
            snapshot(id = 6L, state = TaskState.Pending, lastHeartbeatAtMillis = 0L),
            snapshot(id = 7L, state = TaskState.Cancelled)
        )
        val summary = TaskRunPolicy.summarize(runs, NOW)
        assertEquals(
            TaskRunPolicy.TaskRunSummary(active = 2, stalled = 1, paused = 1, failed = 1, succeeded = 1),
            summary
        )
        assertTrue("stalled 必须是 active 的子集", summary.stalled <= summary.active)
    }

    @Test
    fun summarizeIsEmptyForEmptyLedger() {
        assertEquals(
            TaskRunPolicy.TaskRunSummary(active = 0, stalled = 0, paused = 0, failed = 0, succeeded = 0),
            TaskRunPolicy.summarize(emptyList(), NOW)
        )
    }

    @Test
    fun progressFractionHandlesUnknownAndOutOfRangeTotals() {
        // 总量未知：不猜，直接 0.0。
        assertEquals(0.0, snapshot(completedSteps = 120, totalSteps = 0).progressFraction, 0.0)
        assertEquals(0.0, snapshot(completedSteps = 5, totalSteps = -3).progressFraction, 0.0)
        // 正常区间。
        assertEquals(0.5, snapshot(completedSteps = 5, totalSteps = 10).progressFraction, 0.0)
        // 脏数据钳制，避免把进度条画崩。
        assertEquals(1.0, snapshot(completedSteps = 12, totalSteps = 10).progressFraction, 0.0)
        assertEquals(0.0, snapshot(completedSteps = -4, totalSteps = 10).progressFraction, 0.0)
    }

    @Test
    fun stalledCheckRejectsNonRunningAndInvalidThresholds() {
        val oldHeartbeat = NOW - THRESHOLD - 1L
        assertFalse(
            "Paused 没有活协程，不应算失联",
            snapshot(state = TaskState.Paused, lastHeartbeatAtMillis = oldHeartbeat).isStalledAt(NOW, THRESHOLD)
        )
        assertFalse(
            "阈值非正视为配置异常，不能误判接管",
            snapshot(state = TaskState.Running, lastHeartbeatAtMillis = oldHeartbeat).isStalledAt(NOW, 0L)
        )
        assertFalse(
            "心跳在未来（时钟回拨）不算失联",
            snapshot(state = TaskState.Running, lastHeartbeatAtMillis = NOW + 60_000L).isStalledAt(NOW, THRESHOLD)
        )
        assertFalse(
            "两个时间戳都缺失时不做判定",
            snapshot(
                state = TaskState.Running,
                lastHeartbeatAtMillis = 0L,
                updatedAtMillis = 0L
            ).isStalledAt(NOW, THRESHOLD)
        )
        assertTrue(
            snapshot(state = TaskState.Running, lastHeartbeatAtMillis = oldHeartbeat).isStalledAt(NOW, THRESHOLD)
        )
    }

    private companion object {
        /** 固定基准时间，保证所有用例与真实时钟无关。 */
        const val NOW: Long = 1_700_000_000_000L
        const val THRESHOLD: Long = TaskRunPolicy.DEFAULT_STALL_THRESHOLD_MILLIS

        fun snapshot(
            id: Long = 1L,
            kind: TaskKind = TaskKind.FolderBatch,
            dedupeKey: String = "FolderBatch:default",
            title: String = "测试任务",
            state: TaskState = TaskState.Running,
            step: String? = null,
            completedSteps: Int = 0,
            totalSteps: Int = 0,
            attempts: Int = 1,
            failureMessage: String? = null,
            lastHeartbeatAtMillis: Long = NOW,
            updatedAtMillis: Long = NOW
        ): TaskRunSnapshot = TaskRunSnapshot(
            id = id,
            kind = kind,
            dedupeKey = dedupeKey,
            title = title,
            state = state,
            step = step,
            completedSteps = completedSteps,
            totalSteps = totalSteps,
            attempts = attempts,
            failureMessage = failureMessage,
            lastHeartbeatAtMillis = lastHeartbeatAtMillis,
            updatedAtMillis = updatedAtMillis
        )
    }
}
