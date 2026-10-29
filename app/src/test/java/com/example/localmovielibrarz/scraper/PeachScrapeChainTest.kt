package com.example.localmovielibrarz.scraper

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 桃链的控制流（peach 的「按类型选链 → 链内按序 → 逐字段合并 → 必填齐则停 → 未齐继续」）。
 *
 * 用假执行器而不是真站点：这几条分支（全部冷却、负缓存全命中、预算耗尽、中途结算）
 * 在真机上要么要等几分钟冷却，要么要撞上站点抖动才能复现，而它们恰好是「链会不会自己走飞」
 * 的关键。假执行器把「哪家答了什么」「有没有真的发请求」变成可断言的输入。
 */
class PeachScrapeChainTest {

    /** 记录被问过的来源，并按脚本返回结果。 */
    private class FakeExecutor(
        private val results: Map<ScrapeSource, PeachAttempt> = emptyMap(),
        private val cooling: Map<ScrapeSource, Long> = emptyMap(),
        private val failures: Map<ScrapeSource, ScrapeFailure> = emptyMap()
    ) : PeachSourceExecutor {

        val asked = mutableListOf<ScrapeSource>()
        val logs = mutableListOf<String>()

        override fun cooldownRemaining(source: ScrapeSource): Long = cooling[source] ?: 0L

        override fun lastFailure(source: ScrapeSource): ScrapeFailure? = failures[source]

        override suspend fun fetch(source: ScrapeSource, number: String): PeachAttempt {
            asked += source
            return results[source] ?: PeachAttempt.Failure(
                failure = ScrapeFailure(ScrapeFailureKind.NotFound, "${source.label} 没有"),
                numberMatched = true
            )
        }

        override fun log(message: String) {
            logs += message
        }
    }

    private fun plan(vararg sources: ScrapeSource) =
        ScrapeRouteRules.RoutePlan(sources = sources.toList(), skipped = emptyList(), fallbackReason = null)

    private fun info(
        title: String = "",
        actors: List<String> = emptyList(),
        studio: String = "",
        premiered: String = ""
    ) = ScrapedMovieInfo(
        number = "SSIS-115",
        title = title,
        actors = actors,
        studio = studio,
        premiered = premiered
    )

    private fun fullInfo(source: ScrapeSource) = PeachAttempt.Success(
        info = info(title = "标题", actors = listOf("演员"), studio = "S1", premiered = "2024-01-02")
            .copy(source = source.label),
        responseBytes = 1_024L
    )

    @Test
    fun stopsAsSoonAsRequiredScalarsAreComplete() = runBlocking {
        val executor = FakeExecutor(
            results = mapOf(
                ScrapeSource.Javbus to PeachAttempt.Success(
                    info = info(title = "标题", actors = listOf("演员"), studio = "S1", premiered = "2024-01-02"),
                    responseBytes = 100L
                ),
                ScrapeSource.TheJavDB to fullInfo(ScrapeSource.TheJavDB)
            )
        )
        val chain = PeachScrapeChain(executor, ScrapeMissCache(), ScrapeBudget())
        val result = chain.run("SSIS-115", plan(ScrapeSource.Javbus, ScrapeSource.TheJavDB))

        assertTrue(result is PeachChainResult.Success)
        // 必填四项在第一家就齐了，第二家不该再被问——这正是 peach 的 settles。
        assertEquals(listOf(ScrapeSource.Javbus), executor.asked)
    }

    @Test
    fun keepsAskingWhileAnOfficialSourceIsStillBehind() = runBlocking {
        val executor = FakeExecutor(
            results = mapOf(
                ScrapeSource.Avbase to PeachAttempt.Success(
                    info = info(title = "社区给的标题"), responseBytes = 100L
                ),
                ScrapeSource.Dmm to PeachAttempt.Success(
                    info = info(
                        title = "官方标题", actors = listOf("演员"), studio = "S1", premiered = "2024-01-02"
                    ),
                    responseBytes = 100L
                )
            )
        )
        val chain = PeachScrapeChain(executor, ScrapeMissCache(), ScrapeBudget())
        val result = chain.run("SSIS-115", plan(ScrapeSource.Avbase, ScrapeSource.Dmm))

        assertTrue(result is PeachChainResult.Success)
        // 必填未齐且后面还排着官方档 → 继续问。这正是 peach `settles` 的第 ① 条。
        assertEquals(listOf(ScrapeSource.Avbase, ScrapeSource.Dmm), executor.asked)
        // 逐字段合并：标题听官方（层级高），不再听先到的那家。
        val merged = (result as PeachChainResult.Success).info
        assertEquals("官方标题", merged.title)
        assertEquals("S1", merged.studio)
    }

    @Test
    fun keepsAskingCommunitySourceWhenScalarsStillMissing() = runBlocking {
        val executor = FakeExecutor(
            results = mapOf(
                ScrapeSource.Avbase to PeachAttempt.Success(
                    info = info(title = "只有标题"), responseBytes = 100L
                ),
                ScrapeSource.TheJavDB to PeachAttempt.Success(
                    info = info(actors = listOf("演员"), studio = "S1", premiered = "2024-01-02"),
                    responseBytes = 100L
                )
            )
        )
        val chain = PeachScrapeChain(executor, ScrapeMissCache(), ScrapeBudget())
        val result = chain.run("SSIS-115", plan(ScrapeSource.Avbase, ScrapeSource.TheJavDB))

        assertTrue(result is PeachChainResult.Success)
        // 后面虽只剩社区档，但必填还缺三项——peach 此时仍会问完（缺的是事实，不是可信度）。
        assertEquals(listOf(ScrapeSource.Avbase, ScrapeSource.TheJavDB), executor.asked)
        val merged = (result as PeachChainResult.Success).info
        assertEquals("只有标题", merged.title)
        assertEquals("S1", merged.studio)
    }

    @Test
    fun notFoundFailureIsRememberedAsMiss() = runBlocking {
        val cache = ScrapeMissCache()
        val executor = FakeExecutor(
            results = mapOf(
                ScrapeSource.Javbus to PeachAttempt.Failure(
                    failure = ScrapeFailure(ScrapeFailureKind.NotFound, "没有"),
                    numberMatched = true
                )
            )
        )
        val chain = PeachScrapeChain(executor, cache, ScrapeBudget())
        chain.run("SSIS-115", plan(ScrapeSource.Javbus))

        assertTrue(cache.isMiss(ScrapeSource.Javbus, "SSIS-115"))
    }

    @Test
    fun mismatchedNumberIsNotRememberedAsMiss() = runBlocking {
        val cache = ScrapeMissCache()
        val executor = FakeExecutor(
            results = mapOf(
                ScrapeSource.Javbus to PeachAttempt.Failure(
                    failure = ScrapeFailure(ScrapeFailureKind.NotFound, "返回的是别的番号"),
                    numberMatched = false
                )
            )
        )
        val chain = PeachScrapeChain(executor, cache, ScrapeBudget())
        chain.run("SSIS-115", plan(ScrapeSource.Javbus))

        // 「这家会答错片」不等于「这家没有这部」，记进负缓存等于 7 天内静默跳过一条真实缺口。
        assertTrue(!cache.isMiss(ScrapeSource.Javbus, "SSIS-115"))
    }

    @Test
    fun missCacheHitSkipsTheSourceWithoutAnyRequest() = runBlocking {
        val cache = ScrapeMissCache()
        cache.rememberMiss(ScrapeSource.Javbus, "SSIS-115")
        val executor = FakeExecutor()
        val chain = PeachScrapeChain(executor, cache, ScrapeBudget())
        chain.run("SSIS-115", plan(ScrapeSource.Javbus))

        assertTrue(executor.asked.isEmpty())
        assertTrue(executor.logs.any { it.contains("负缓存") })
    }

    @Test
    fun coolingSourceIsSkipped() = runBlocking {
        val executor = FakeExecutor(
            cooling = mapOf(ScrapeSource.TheJavDB to 30L * 60 * 1000),
            failures = mapOf(
                ScrapeSource.TheJavDB to ScrapeFailure(ScrapeFailureKind.Forbidden, "被挡")
            )
        )
        val chain = PeachScrapeChain(executor, ScrapeMissCache(), ScrapeBudget())
        val result = chain.run("SSIS-115", plan(ScrapeSource.TheJavDB))

        assertTrue(executor.asked.isEmpty())
        assertTrue(result is PeachChainResult.NoResult)
        // 冷却原因要进汇总：用户看到「没有结果」时唯一能解释的地方。
        assertTrue((result as PeachChainResult.NoResult).reason.contains("冷却"))
    }

    @Test
    fun budgetExhaustionStopsTheChain() = runBlocking {
        val executor = FakeExecutor(
            results = mapOf(
                ScrapeSource.Javbus to PeachAttempt.Success(info = info(title = "标题"), responseBytes = 100L)
            )
        )
        // 零预算：第一次查闸门就到底，一家都不该被打。
        val chain = PeachScrapeChain(executor, ScrapeMissCache(), ScrapeBudget(maxRequests = 0))
        val result = chain.run(
            "SSIS-115",
            plan(ScrapeSource.Javbus, ScrapeSource.TheJavDB)
        )

        assertTrue(executor.asked.isEmpty())
        assertTrue(result is PeachChainResult.NoResult)
        assertTrue((result as PeachChainResult.NoResult).reason.contains("请求预算"))
    }

    @Test
    fun emptyPlanIsUnavailableNotSilentSuccess() = runBlocking {
        val chain = PeachScrapeChain(FakeExecutor(), ScrapeMissCache(), ScrapeBudget())
        val result = chain.run(
            "SSIS-115",
            ScrapeRouteRules.RoutePlan(sources = emptyList(), skipped = emptyList(), fallbackReason = null)
        )
        assertTrue(result is PeachChainResult.Unavailable)
    }

    @Test
    fun routeTrimmedToEmptyIsFinalNotFallback() = runBlocking {
        val chain = PeachScrapeChain(FakeExecutor(), ScrapeMissCache(), ScrapeBudget())
        val result = chain.run(
            "SSIS-115",
            ScrapeRouteRules.RoutePlan(
                sources = emptyList(),
                skipped = listOf(ScrapeSource.Dmm),
                fallbackReason = "按「韩国 MIB」没有可问的来源"
            )
        )
        // skipped 非空说明是「按类型裁空」——这是终态（peach 对 kmib/other 就是空链），
        // 不是「链坏了」，所以报 NoResult 而不是 Unavailable。
        assertTrue(result is PeachChainResult.NoResult)
    }

    @Test
    fun successRewritesNumberToTheQueriedOne() = runBlocking {
        val executor = FakeExecutor(
            results = mapOf(
                ScrapeSource.Javbus to PeachAttempt.Success(
                    info = info(title = "标题", actors = listOf("演员"), studio = "S1", premiered = "2024-01-02")
                        .copy(number = "SSIS-999"),
                    responseBytes = 10L
                )
            )
        )
        val chain = PeachScrapeChain(executor, ScrapeMissCache(), ScrapeBudget())
        val result = chain.run("SSIS-115", plan(ScrapeSource.Javbus)) as PeachChainResult.Success
        assertEquals("SSIS-115", result.info.number)
    }

    @Test
    fun thinRequestSettlesOnAnyValue() = runBlocking {
        val executor = FakeExecutor(
            results = mapOf(
                ScrapeSource.Javbus to PeachAttempt.Success(
                    info = info(title = "只有标题"), responseBytes = 10L
                )
            )
        )
        val chain = PeachScrapeChain(executor, ScrapeMissCache(), ScrapeBudget())
        val result = chain.run(
            number = "SSIS-115",
            plan = plan(ScrapeSource.Javbus, ScrapeSource.TheJavDB),
            wantedScalars = false
        )
        assertTrue(result is PeachChainResult.Success)
        // 薄请求（只要封面/标签）：第一家给了东西就收工，不为了凑标题/演员再打一遍。
        assertEquals(listOf(ScrapeSource.Javbus), executor.asked)
    }
}