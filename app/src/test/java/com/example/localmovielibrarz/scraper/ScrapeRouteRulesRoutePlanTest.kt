package com.example.localmovielibrarz.scraper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrapeRouteRulesRoutePlanTest {

    private val allSources = ScrapeSource.values().toList() - ScrapeSource.Priority

    @Test
    fun censoredKeepsEveryConfiguredSource() {
        val plan = ScrapeRouteRules.routeFor(ScrapeContentType.Censored, allSources)
        assertEquals(allSources.toSet(), plan.sources.toSet())
        assertTrue("有码不裁剪任何来源", plan.skipped.isEmpty())
        assertNull(plan.fallbackReason)
    }

    @Test
    fun censoredOrdersByAuthorityNotByConfiguredOrder() {
        // 用户把兜底的 Javbus 配在最前，路由后仍应排在官方与首选社区之后。
        val configured = listOf(
            ScrapeSource.Javbus,
            ScrapeSource.TheJavDB,
            ScrapeSource.Dmm2,
            ScrapeSource.Official,
        )
        val plan = ScrapeRouteRules.routeFor(ScrapeContentType.Censored, configured)
        assertEquals(
            listOf(ScrapeSource.Dmm2, ScrapeSource.Official, ScrapeSource.TheJavDB, ScrapeSource.Javbus),
            plan.sources
        )
    }

    @Test
    fun amateurDropsDmmSources() {
        val plan = ScrapeRouteRules.routeFor(ScrapeContentType.Amateur, allSources)
        assertTrue(ScrapeSource.Dmm !in plan.sources)
        assertTrue(ScrapeSource.Dmm2 !in plan.sources)
        // r18.dev 是 DMM 目录的镜像，素人号在它上面同样是 404（实测 `dvd_id=MIUM-1239`），
        // 所以它跟着 DMM 一起被裁掉，而不是留在链上白打一次。
        assertTrue(ScrapeSource.R18Dev !in plan.sources)
        // Official 的前缀白名单里没有素人厂牌，留着它只是多一条必定失败的尝试。
        assertEquals(
            setOf(ScrapeSource.Dmm, ScrapeSource.Dmm2, ScrapeSource.Official, ScrapeSource.R18Dev),
            plan.skipped.toSet()
        )
        assertEquals(ScrapeSource.Mgstage, plan.sources.first())
    }

    @Test
    fun r18DevSurvivesCensoredChainButNotAmateurOrUncensored() {
        // 这个源存在的唯一理由是「DMM 连不上时还有一条路取回同一份目录数据」，
        // 所以它在有码链上必须留着，在镜像本身就不收录的类型上必须被裁掉。
        val censored = ScrapeRouteRules.routeFor(ScrapeContentType.Censored, allSources)
        assertTrue(ScrapeSource.R18Dev in censored.sources)

        for (type in listOf(ScrapeContentType.Amateur, ScrapeContentType.Uncensored, ScrapeContentType.Fc2)) {
            val plan = ScrapeRouteRules.routeFor(type, allSources)
            assertTrue("$type 不该问 r18.dev", ScrapeSource.R18Dev !in plan.sources)
        }
    }

    @Test
    fun uncensoredAndFc2DropOfficialAndDirectorSources() {
        for (type in listOf(ScrapeContentType.Uncensored, ScrapeContentType.Fc2)) {
            val plan = ScrapeRouteRules.routeFor(type, allSources)
            assertTrue("$type 不应问 Official", ScrapeSource.Official !in plan.sources)
            assertTrue("$type 不应问 DMM", ScrapeSource.Dmm !in plan.sources)
            assertTrue("$type 不应问 DMM2", ScrapeSource.Dmm2 !in plan.sources)
            assertTrue("$type 不应问 MGStage", ScrapeSource.Mgstage !in plan.sources)
        }
    }

    @Test
    fun kmibAndOtherKeepOnlyCustomJson() {
        for (type in listOf(ScrapeContentType.Kmib, ScrapeContentType.Other)) {
            val plan = ScrapeRouteRules.routeFor(type, allSources)
            assertEquals("$type 只该问用户自配的来源", listOf(ScrapeSource.CustomJson), plan.sources)
        }
    }

    @Test
    fun emptyChainFallsBackToConfiguredOrderInsteadOfFailing() {
        // 用户只开了 Javbus 一家，韩国 MIB 按类型裁完一个源都不剩。
        val configured = listOf(ScrapeSource.Javbus)
        val plan = ScrapeRouteRules.routeFor(ScrapeContentType.Kmib, configured)
        assertEquals(configured, plan.sources)
        assertNotNull("回退必须留下原因，调用方要写进日志", plan.fallbackReason)
        assertTrue(plan.fallbackReason!!.contains("JavBus"))
    }

    @Test
    fun unknownKeepsConfiguredOrderUntouched() {
        val configured = listOf(ScrapeSource.Javbus, ScrapeSource.Dmm, ScrapeSource.Mgstage)
        val plan = ScrapeRouteRules.routeFor(ScrapeContentType.Unknown, configured)
        assertEquals(configured, plan.sources)
        assertTrue(plan.skipped.isEmpty())
        assertNull(plan.fallbackReason)
    }

    @Test
    fun duplicateSourcesAreDedupedBeforeRouting() {
        val configured = listOf(ScrapeSource.Dmm, ScrapeSource.Dmm, ScrapeSource.Javbus)
        val plan = ScrapeRouteRules.routeFor(ScrapeContentType.Censored, configured)
        assertEquals(plan.sources.size, plan.sources.distinct().size)
        assertEquals(2, plan.sources.size)
    }
}

class ScrapeAuthorityTest {

    @Test
    fun officialSourcesShareTheTopTier() {
        val official = listOf(
            ScrapeSource.Official,
            ScrapeSource.Dmm,
            ScrapeSource.Dmm2,
            ScrapeSource.Mgstage,
        )
        for (source in official) {
            assertEquals(SourceTier.Official, ScrapeAuthority.tierOf(source))
        }
    }

    @Test
    fun preferredCommunityIsTrustedOverPlainCommunityAndFallback() {
        assertTrue(
            ScrapeAuthority.tierOf(ScrapeSource.TheJavDB)
                .trustedOver(ScrapeAuthority.tierOf(ScrapeSource.CustomJson))
        )
        assertTrue(
            ScrapeAuthority.tierOf(ScrapeSource.CustomJson)
                .trustedOver(ScrapeAuthority.tierOf(ScrapeSource.Javbus))
        )
    }

    @Test
    fun orderByTierGroupsWithoutDropping() {
        val configured = listOf(
            ScrapeSource.Javbus,
            ScrapeSource.CustomJson,
            ScrapeSource.TheJavDB,
            ScrapeSource.Dmm,
        )
        val ordered = ScrapeAuthority.orderByTier(configured)
        assertEquals(configured.toSet(), ordered.toSet())
        assertEquals(
            listOf(
                ScrapeSource.Dmm,
                ScrapeSource.TheJavDB,
                ScrapeSource.CustomJson,
                ScrapeSource.Javbus,
            ),
            ordered
        )
    }

    @Test
    fun mergeByAuthorityPreferredXOverFallback() {
        val fallback = ScrapeSource.Javbus to baseInfo(title = "错标题", studio = "错厂牌")
        val preferred = ScrapeSource.TheJavDB to baseInfo(title = "对标题", studio = "对厂牌")

        // 先到的是兜底来源，后到的是首选社区：后者应当赢。
        val merged = ScrapeAuthority.mergeByAuthority(fallback, preferred)
        assertEquals(ScrapeSource.TheJavDB, merged.first)
        assertEquals("对标题", merged.second.title)
        assertEquals("对厂牌", merged.second.studio)
    }

    @Test
    fun mergeByAuthorityKeepsNonBlankFromLowerTierWhenHigherIsBlank() {
        val official = ScrapeSource.Dmm to baseInfo(title = "", studio = "官方厂牌")
        val fallback = ScrapeSource.Javbus to baseInfo(title = "兜底标题", studio = "兜底厂牌")

        val merged = ScrapeAuthority.mergeByAuthority(official, fallback)
        // 标题只有兜底给了，非空即用；厂牌官方给了，兜底不许盖。
        assertEquals("兜底标题", merged.second.title)
        assertEquals("官方厂牌", merged.second.studio)
        // 合并结果的来源标层级最高的那家。
        assertEquals(ScrapeSource.Dmm, merged.first)
    }

    @Test
    fun mergeByAuthorityOnNullBaseReturnsIncoming() {
        val incoming = ScrapeSource.Javbus to baseInfo(title = "标题")
        assertEquals(incoming, ScrapeAuthority.mergeByAuthority(null, incoming))
    }

    @Test
    fun unknownTierNeverOverridesKnownTier() {
        val unknown = ScrapeSource.Priority to baseInfo(title = "链标记给的标题")
        val known = ScrapeSource.Javbus to baseInfo(title = "兜底标题")
        val merged = ScrapeAuthority.mergeByAuthority(unknown, known)
        assertEquals("兜底标题", merged.second.title)
    }

    private fun baseInfo(title: String = "", studio: String = ""): ScrapedMovieInfo =
        ScrapedMovieInfo(number = "SSIS-115", title = title, studio = studio)
}
