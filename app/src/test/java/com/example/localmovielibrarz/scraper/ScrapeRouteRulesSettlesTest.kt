package com.example.localmovielibrarz.scraper

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 结算判据的单测：照 peach `metadata_routes.settles` 的三条规则改成鸭影版之后，
 * 「什么时候不再追问后续来源」必须仍是一条能说清、能测的规则。
 */
class ScrapeRouteRulesSettlesTest {

    @Test
    fun completeScalarsSettleEvenWithoutGenres() {
        val info = info(title = "标题", actors = listOf("演员"), studio = "厂牌", premiered = "2024-01-01")
        assertTrue(ScrapeRouteRules.settles(info, listOf(ScrapeSource.Javbus)))
    }

    @Test
    fun highCompletenessDoesNotSettleWhenActorsMissing() {
        // 标题、厂牌、发行日、封面、类型、年份都在，但演员为空——
        // 旧的 0.7 完成度阈值会在这里结算，用户翻库时按演员筛就永远是空的。
        val info = info(
            title = "标题",
            studio = "厂牌",
            premiered = "2024-01-01",
            genres = listOf("类别"),
            year = "2024",
            posterUrl = "https://example.com/p.jpg",
        )
        assertFalse(ScrapeRouteRules.settles(info, emptyList()))
    }

    @Test
    fun missingGenresKeepsGoingWhenAnotherOfficialSourceIsLeft() {
        val info = info(title = "标题", actors = listOf("演员"), studio = "厂牌", premiered = "2024-01-01")
        assertFalse(
            "后面还排着官方来源，值得多问一次类别",
            ScrapeRouteRules.settles(info, listOf(ScrapeSource.Dmm2, ScrapeSource.Javbus))
        )
        assertTrue(
            "后面只剩社区站，它们本来也常缺类别",
            ScrapeRouteRules.settles(info, listOf(ScrapeSource.Javbus, ScrapeSource.TheJavDB))
        )
    }

    @Test
    fun genresPresentSettlesRegardlessOfRemaining() {
        val info = info(
            title = "标题",
            actors = listOf("演员"),
            studio = "厂牌",
            premiered = "2024-01-01",
            genres = listOf("类别"),
        )
        assertTrue(ScrapeRouteRules.settles(info, listOf(ScrapeSource.Dmm2)))
    }

    @Test
    fun releaseDateAcceptsPremieredOrYear() {
        val byYear = info(title = "标题", actors = listOf("演员"), studio = "厂牌", year = "2024")
        assertTrue(ScrapeRouteRules.settles(byYear, emptyList()))
    }

    private fun info(
        title: String = "",
        actors: List<String> = emptyList(),
        studio: String = "",
        premiered: String = "",
        year: String = "",
        genres: List<String> = emptyList(),
        posterUrl: String = "",
    ): ScrapedMovieInfo = ScrapedMovieInfo(
        number = "SSIS-115",
        title = title,
        studio = studio,
        premiered = premiered,
        year = year,
        actors = actors,
        genres = genres,
        posterUrl = posterUrl,
    )
}
