package com.example.localmovielibrarz.tasks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 状态机与状态集合的边界测试。
 *
 * 重点覆盖三类容易回归的错误：
 * 1. 终态被「复活」（Failed → Running、Succeeded → Pending），会让账本丢掉「第几次尝试」的历史；
 * 2. `unfinishedNames` / `activeNames` 与 isTerminal / isActive 口径漂移，导致 DAO 查询与 UI 统计对不上；
 * 3. 迁移表被悄悄放宽（例如给 Paused 开一条通往 Succeeded 的捷径）。
 */
class TaskStateTest {

    @Test
    fun parseTrimsAndIgnoresCase() {
        assertEquals(TaskState.Running, TaskState.parse("  running  "))
        assertEquals(TaskState.Succeeded, TaskState.parse("SUCCEEDED"))
        assertEquals(TaskState.Paused, TaskState.parse("paused"))
        assertEquals(TaskState.Cancelled, TaskState.parse("Cancelled"))
    }

    @Test
    fun parseReturnsNullForBlankUnknownOrNull() {
        assertNull(TaskState.parse(null))
        assertNull(TaskState.parse(""))
        assertNull(TaskState.parse("   "))
        assertNull(TaskState.parse("Completed")) // 旧任务模型的 Completed 不应被静默映射到 Succeeded
        assertNull(TaskState.parse("None"))
    }

    @Test
    fun terminalFlagsCoverExactlySucceededFailedCancelled() {
        assertTrue(TaskState.Succeeded.isTerminal)
        assertTrue(TaskState.Failed.isTerminal)
        assertTrue(TaskState.Cancelled.isTerminal)
        assertFalse(TaskState.Pending.isTerminal)
        assertFalse(TaskState.Running.isTerminal)
        assertFalse(TaskState.Paused.isTerminal)
    }

    @Test
    fun isActiveOnlyForRunning() {
        assertTrue(TaskState.Running.isActive)
        listOf(TaskState.Pending, TaskState.Paused, TaskState.Succeeded, TaskState.Failed, TaskState.Cancelled)
            .forEach { assertFalse("$it 不应被视为 active", it.isActive) }
    }

    @Test
    fun blocksNewRunOnlyForRunningAndPaused() {
        assertTrue(TaskState.Running.blocksNewRun)
        assertTrue(TaskState.Paused.blocksNewRun)
        listOf(TaskState.Pending, TaskState.Succeeded, TaskState.Failed, TaskState.Cancelled)
            .forEach { assertFalse("$it 不应阻塞新 run", it.blocksNewRun) }
    }

    @Test
    fun pendingCanStartPauseOrCancel() {
        assertTrue(TaskState.Pending.canTransitionTo(TaskState.Running))
        assertTrue(TaskState.Pending.canTransitionTo(TaskState.Paused))
        assertTrue(TaskState.Pending.canTransitionTo(TaskState.Cancelled))
    }

    @Test
    fun runningCanPauseFinishFailOrCancel() {
        assertTrue(TaskState.Running.canTransitionTo(TaskState.Paused))
        assertTrue(TaskState.Running.canTransitionTo(TaskState.Succeeded))
        assertTrue(TaskState.Running.canTransitionTo(TaskState.Failed))
        assertTrue(TaskState.Running.canTransitionTo(TaskState.Cancelled))
    }

    @Test
    fun pausedCanResumeFailOrCancelButNotSucceed() {
        assertTrue(TaskState.Paused.canTransitionTo(TaskState.Running))
        assertTrue(TaskState.Paused.canTransitionTo(TaskState.Failed))
        assertTrue(TaskState.Paused.canTransitionTo(TaskState.Cancelled))
        // 没跑完不能直接宣告成功。
        assertFalse(TaskState.Paused.canTransitionTo(TaskState.Succeeded))
    }

    @Test
    fun runningAndPausedCannotFallBackToPending() {
        assertFalse(TaskState.Running.canTransitionTo(TaskState.Pending))
        assertFalse(TaskState.Paused.canTransitionTo(TaskState.Pending))
        // 排队中也不能跳过执行直接成功/失败。
        assertFalse(TaskState.Pending.canTransitionTo(TaskState.Succeeded))
        assertFalse(TaskState.Pending.canTransitionTo(TaskState.Failed))
    }

    @Test
    fun terminalStatesAreMutuallyExclusiveAndCannotRestart() {
        val terminals = listOf(TaskState.Succeeded, TaskState.Failed, TaskState.Cancelled)
        terminals.forEach { terminal ->
            assertTrue("$terminal 应允许幂等写回自身", terminal.canTransitionTo(terminal))
            TaskState.entries
                .filter { it != terminal }
                .forEach { other ->
                    assertFalse(
                        "$terminal 是终态，不能迁移到 $other（重跑必须新建 run 记录）",
                        terminal.canTransitionTo(other)
                    )
                }
        }
    }

    @Test
    fun sameStateWritesAreIdempotentNoOps() {
        // 心跳/进度落库会重复写当前状态，不能因此报「非法迁移」。
        TaskState.entries.forEach { state ->
            assertTrue("$state -> $state 应为幂等空操作", state.canTransitionTo(state))
        }
    }

    @Test
    fun fullTransitionMatrixMatchesDocumentedTable() {
        val allowed = setOf(
            TaskState.Pending to TaskState.Pending,
            TaskState.Pending to TaskState.Running,
            TaskState.Pending to TaskState.Paused,
            TaskState.Pending to TaskState.Cancelled,
            TaskState.Running to TaskState.Running,
            TaskState.Running to TaskState.Paused,
            TaskState.Running to TaskState.Succeeded,
            TaskState.Running to TaskState.Failed,
            TaskState.Running to TaskState.Cancelled,
            TaskState.Paused to TaskState.Paused,
            TaskState.Paused to TaskState.Running,
            TaskState.Paused to TaskState.Failed,
            TaskState.Paused to TaskState.Cancelled,
            TaskState.Succeeded to TaskState.Succeeded,
            TaskState.Failed to TaskState.Failed,
            TaskState.Cancelled to TaskState.Cancelled
        )
        TaskState.entries.forEach { from ->
            TaskState.entries.forEach { to ->
                val expected = (from to to) in allowed
                val message = "迁移表不一致：$from -> $to 期望 $expected"
                if (expected) {
                    assertTrue(message, from.canTransitionTo(to))
                } else {
                    assertFalse(message, from.canTransitionTo(to))
                }
            }
        }
    }

    @Test
    fun nameListsStayInSyncWithFlags() {
        assertEquals(listOf("Running"), TaskState.activeNames)
        assertEquals(
            listOf("Pending", "Running", "Paused", "Failed"),
            TaskState.unfinishedNames
        )
        assertEquals(
            TaskState.entries.filter { it.blocksNewRun }.map { it.name },
            listOf("Running", "Paused")
        )
        // 列表必须能从字符串还原成真实状态，供 DAO 的 IN 查询使用。
        TaskState.unfinishedNames.forEach { name ->
            assertEquals(TaskState.entries.first { it.name == name }, TaskState.parse(name))
        }
        // 与既有 ScrapeTaskStatus.unfinishedNames 同口径：失败也算「还没收尾」，
        // 因为设置页要把它暴露给用户重试；真正不需要再管的只有成功与用户取消。
        assertTrue(TaskState.Failed.name in TaskState.unfinishedNames)
        assertFalse(TaskState.Succeeded.name in TaskState.unfinishedNames)
        assertFalse(TaskState.Cancelled.name in TaskState.unfinishedNames)
    }
}
