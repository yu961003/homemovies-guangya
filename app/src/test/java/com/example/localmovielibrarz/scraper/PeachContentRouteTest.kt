package com.example.localmovielibrarz.scraper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * peach `ROUTES` 的字面顺序是否被原样保住。
 *
 * 这些断言盯的不是「排序算法」，而是**链上的先手是谁**：无码先问一本道、FC2 先问 FC2 自己、
 * 有码把 r18.dev 排在社区站之前。层级排序在这三处都会给出不同答案，所以每一条都写成
 * 具体顺序断言，改表时先坏一条，而不是静静地换一个先手。
 */
class PeachContentRouteTest {

    private val allWired = listOf(
        ScrapeSource.Avbase,
        ScrapeSource.Javbus,
        ScrapeSource.TheJavDB,
        ScrapeSource.R18Dev,
        ScrapeSource.Dmm,
        ScrapeSource.Dmm2,
        ScrapeSource.Official,
        ScrapeSource.Mgstage,
        ScrapeSource.OnePondo,
        ScrapeSource.Fc2
    )

    @Test
    fun censoredShadowPutsOfficialMirrorBeforeCommunity() {
        val ordered = PeachContentRoute.order(ScrapeContentType.Censored, allWired)
        // peach：prestige/faleno/dahlia/makers/r18dev/dmm/avbase/javbus/javdb
        // 本工程能落的：Official(prestige) → R18Dev → Dmm/Dmm2 → Avbase → Javbus → JavDB
        val positions = ordered.withIndex().associate { it.value to it.index }
        assertTrue(positions.getValue(ScrapeSource.Official) < positions.getValue(ScrapeSource.R18Dev))
        assertTrue(positions.getValue(ScrapeSource.R18Dev) < positions.getValue(ScrapeSource.Dmm))
        assertTrue(positions.getValue(ScrapeSource.Dmm) < positions.getValue(ScrapeSource.Avbase))
        assertTrue(positions.getValue(ScrapeSource.Avbase) < positions.getValue(ScrapeSource.Javbus))
        assertTrue(positions.getValue(ScrapeSource.Javbus) < positions.getValue(ScrapeSource.TheJavDB))
    }

    @Test
    fun uncensoredAsksOnePondoFirst() {
        val ordered = PeachContentRoute.order(ScrapeContentType.Uncensored, allWired)
        assertEquals(ScrapeSource.OnePondo, ordered.first())
        // avsox 未接，所以一本道之后直接是 AVBase/JavBus/JavDB 这一段社区档。
        assertTrue(ordered.indexOf(ScrapeSource.Avbase) < ordered.indexOf(ScrapeSource.Javbus))
    }

    @Test
    fun fc2RouteStartsWithFc2Itself() {
        val ordered = PeachContentRoute.order(ScrapeContentType.Fc2, allWired)
        assertEquals(ScrapeSource.Fc2, ordered.first())
        // peach 的 fc2 链里没有 avbase 与 1pondo——排序层不删它们，删在路由层（见下一条）。
        assertTrue(ordered.contains(ScrapeSource.Avbase))
    }

    @Test
    fun amateurPutsMgstageFirst() {
        val ordered = PeachContentRoute.order(ScrapeContentType.Amateur, allWired)
        assertEquals(ScrapeSource.Mgstage, ordered.first())
    }

    @Test
    fun offRouteSourcesKeepTheirRelativeOrderAtTheEnd() {
        val configured = listOf(ScrapeSource.CustomJson, ScrapeSource.TheJavDB, ScrapeSource.Javbus)
        val ordered = PeachContentRoute.order(ScrapeContentType.Censored, configured)
        assertEquals(
            listOf(ScrapeSource.Javbus, ScrapeSource.TheJavDB, ScrapeSource.CustomJson),
            ordered
        )
    }

    @Test
    fun kmibAndOtherAreEmptyRoutes() {
        assertTrue(PeachContentRoute.routeNamesOf(ScrapeContentType.Kmib).isEmpty())
        assertTrue(PeachContentRoute.routeNamesOf(ScrapeContentType.Other).isEmpty())
        // 空链不改变传入顺序：是否「真正不问」由 routeForPeach 的裁空逻辑决定。
        assertEquals(allWired, PeachContentRoute.order(ScrapeContentType.Kmib, allWired))
    }

    @Test
    fun stepsForKeepsUnwiredPeachSourcesAsNonExecutableSteps() {
        val steps = PeachContentRoute.stepsFor(ScrapeContentType.Fc2)
        val names = steps.map { it.profile.name }
        // peach 的 fc2 链在名字层面必须完整（含本工程没接的 javarchive）。
        assertEquals(
            listOf("fc2", "fc2cmadb", "fc2ppvdb", "javten", "javarchive", "javdb"),
            names
        )
        val fc2Step = steps.first { it.profile.name == "fc2" }
        assertTrue(fc2Step.executable)
        // 三家 FC2 索引已经从「名字在表里、没有实现」翻成真接线：
        // 链上是可执行步骤，且带得出对应的 ScrapeSource。
        for (name in listOf("fc2cmadb", "fc2ppvdb", "javten")) {
            val step = steps.first { it.profile.name == name }
            assertTrue("$name 现已接线，应当可执行", step.executable)
            assertTrue(step.source != null)
        }
        // javarchive 仍没有实现（peach 侧走的是已下线的 avsox 数据），保留不可执行。
        val javarchive = steps.first { it.profile.name == "javarchive" }
        assertTrue(javarchive.profile.unwiredReason.isNotBlank())
        assertEquals(null, javarchive.source)
        assertTrue(!javarchive.executable)
    }

    @Test
    fun dmmStepCarriesBothProjectEntryPoints() {
        // `dmm` 这一个 peach 来源名在本工程有两个入口（目录走 REST，Dmm2 走 GraphQL），
        // 展开成两步而不是一步，链上真正执行的是这两个来源。
        val steps = PeachContentRoute.stepsFor(ScrapeContentType.Censored).filter { it.profile.name == "dmm" }
        assertEquals(listOf(ScrapeSource.Dmm, ScrapeSource.Dmm2), steps.map { it.source })
        assertEquals("", steps.first().note)
        assertTrue(steps[1].note.contains("工程扩展入口"))
    }
}