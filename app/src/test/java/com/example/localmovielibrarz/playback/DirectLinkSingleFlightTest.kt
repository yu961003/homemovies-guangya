package com.example.localmovielibrarz.playback

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 单飞合并器的并发测试。
 *
 * 项目里只有 junit 依赖（没有 kotlinx-coroutines-test），因此全部用 [runBlocking] 驱动；
 * 并发顺序通过 `CoroutineStart.UNDISPATCHED` + [CompletableDeferred] 闸门精确控制：
 * UNDISPATCHED 让每个协程在启动线程上同步执行到第一个挂起点，于是「leader 已经进入 block、
 * follower 已经在 await」这一状态是确定性的，断言不依赖 delay 的长短。
 *
 * 所有用例跑在 [supervisorScope] 里：失败用例中 leader 协程会抛异常，普通作用域会把父 Job
 * 一起取消，导致「等待者是否收到异常」根本来不及观察；supervisor 让每个子协程的失败只留在
 * 自己的 Deferred 上。
 */
class DirectLinkSingleFlightTest {

    /** 统一入口：runBlocking + supervisorScope，且把作用域交给用例体，便于 `async` 启动子协程。 */
    private fun flightTest(block: suspend CoroutineScope.() -> Unit) = runBlocking {
        supervisorScope { block(this) }
    }

    @Test
    fun `concurrent calls for one key execute the block exactly once`() = flightTest {
        val flight = DirectLinkSingleFlight()
        val executions = AtomicInteger(0)
        val gate = CompletableDeferred<Unit>()

        val leader = async(Dispatchers.Default, CoroutineStart.UNDISPATCHED) {
            flight.run("115:P1") {
                executions.incrementAndGet()
                gate.await()
                "link-1"
            }
        }
        // UNDISPATCHED 保证 leader 已经注册 entry 并卡在 gate 上，后面的调用必然是 follower。
        assertEquals(1, executions.get())

        val followers = (1..7).map {
            async(Dispatchers.Default, CoroutineStart.UNDISPATCHED) {
                flight.run("115:P1") {
                    executions.incrementAndGet()
                    "fallback-$it"
                }
            }
        }

        gate.complete(Unit)

        val results = (listOf(leader) + followers).awaitAll()

        assertEquals(1, executions.get())
        assertEquals(List(8) { "link-1" }, results)
    }

    @Test
    fun `failure is delivered to every waiter and the key stays reusable`() = flightTest {
        val flight = DirectLinkSingleFlight()
        val executions = AtomicInteger(0)
        val gate = CompletableDeferred<Unit>()

        val leader = async(Dispatchers.Default, CoroutineStart.UNDISPATCHED) {
            flight.run<Int>("guangya_res:R1") {
                executions.incrementAndGet()
                gate.await()
                throw IllegalStateException("boom")
            }
        }
        val followers = (1..3).map {
            async(Dispatchers.Default, CoroutineStart.UNDISPATCHED) {
                flight.run<Int>("guangya_res:R1") {
                    executions.incrementAndGet()
                    -1
                }
            }
        }

        gate.complete(Unit)

        val outcomes = (listOf(leader) + followers).map { runCatching { it.await() } }

        assertEquals(4, outcomes.size)
        outcomes.forEach { outcome ->
            assertTrue("expected a failure but got $outcome", outcome.isFailure)
            val error = outcome.exceptionOrNull()
            assertTrue("expected IllegalStateException but was $error", error is IllegalStateException)
            assertEquals("boom", error?.message)
        }
        // 只有 leader 执行过 block：失败没有让等待者各自重试一次。
        assertEquals(1, executions.get())

        // 失败结束后 entry 必须被清理，下一次调用重新执行 block。
        // 这里必须让恢复调用也记一次 executions，否则第 109 行的 assertEquals(2, ...) 断言的是
        // 「上一个 block 执行了两次与否」，与「entry 是否被清理」无关——测不出它想测的东西。
        val recovered = flight.run("guangya_res:R1") {
            executions.incrementAndGet()
            "link-after-failure"
        }
        assertEquals("link-after-failure", recovered)
        assertEquals(2, executions.get())
    }

    @Test
    fun `different keys run independently`() = flightTest {
        val flight = DirectLinkSingleFlight()
        val gate = CompletableDeferred<Unit>()
        val aStarted = CompletableDeferred<Unit>()

        val keyA = async(Dispatchers.Default, CoroutineStart.UNDISPATCHED) {
            flight.run("A") {
                aStarted.complete(Unit)
                gate.await()
                "link-a"
            }
        }
        aStarted.await()

        val keyB = withTimeout(5_000) { flight.run("B") { "link-b" } }
        assertEquals("link-b", keyB)

        gate.complete(Unit)
        assertEquals("link-a", keyA.await())
    }

    @Test
    fun `a successful block does not leave a stale entry behind`() = flightTest {
        val flight = DirectLinkSingleFlight()
        val executions = AtomicInteger(0)

        val results = (1..3).map {
            flight.run("K") { "link-${executions.incrementAndGet()}" }
        }

        assertEquals(listOf("link-1", "link-2", "link-3"), results)
        assertEquals(3, executions.get())
    }

    @Test
    fun `a cancelled leader releases the key for the next caller`() = flightTest {
        val flight = DirectLinkSingleFlight()
        val executions = AtomicInteger(0)
        val gate = CompletableDeferred<Unit>()

        val leader = async(Dispatchers.Default, CoroutineStart.UNDISPATCHED) {
            flight.run("C") {
                executions.incrementAndGet()
                gate.await()
                "cancelled"
            }
        }

        leader.cancelAndJoin()

        val value = flight.run("C") {
            executions.incrementAndGet()
            "recovered"
        }

        assertEquals("recovered", value)
        assertEquals(2, executions.get())
    }
}
