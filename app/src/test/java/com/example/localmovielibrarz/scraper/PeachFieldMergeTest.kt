package com.example.localmovielibrarz.scraper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 逐字段判胜（peach `field_owners` + `metadata_policy`）。
 *
 * 这条改动的实际意义：改造前「先到非空优先」——先问到的那家把整份结果占了，
 * 后到的更准的字段进不来。peach 是**每个字段单独定胜负**：标题可能来自官网、
 * 演员来自 FC2 索引，互不干扰。这里的断言就是钉住这一点。
 */
class PeachFieldMergeTest {

    private fun info(
        title: String = "",
        actors: List<String> = emptyList(),
        premiered: String = "",
        studio: String = "",
        thumbUrl: String = "",
        tags: List<String> = emptyList()
    ) = ScrapedMovieInfo(
        number = "SSIS-115",
        title = title,
        actors = actors,
        premiered = premiered,
        studio = studio,
        thumbUrl = thumbUrl,
        tags = tags
    )

    private val nonBlank: (String) -> Boolean = { it.isNotBlank() }
    private val nonEmptyList: (List<String>) -> Boolean = { it.isNotEmpty() }

    @Test
    fun blankCandidateLosesToNonBlankOne() {
        val chosen = PeachFieldMerge.pick(
            field = ScrapeField.Title,
            candidates = listOf(
                PeachFieldMerge.Candidate(ScrapeSource.Javbus, ""),
                PeachFieldMerge.Candidate(ScrapeSource.TheJavDB, "正しい題名")
            ),
            isUsable = nonBlank
        )
        assertEquals("正しい題名", chosen?.value)
    }

    @Test
    fun higherTierWinsWhenBothUsable() {
        // r18.dev 是官方镜像档，JavBus 是社区档：同一字段都有值时听镜像的。
        val chosen = PeachFieldMerge.pick(
            field = ScrapeField.Title,
            candidates = listOf(
                PeachFieldMerge.Candidate(ScrapeSource.Javbus, "社区标题"),
                PeachFieldMerge.Candidate(ScrapeSource.R18Dev, "镜像标题")
            ),
            isUsable = nonBlank
        )
        assertEquals("镜像标题", chosen?.value)
    }

    @Test
    fun arrivalOrderBreaksTheTieWithinTheSameTier() {
        // 同为社区档（Avbase 与用户自配的 CustomJson 都在 Community）：先到的胜，
        // 让结果不随网络快慢漂移。
        val chosen = PeachFieldMerge.pick(
            field = ScrapeField.Title,
            candidates = listOf(
                PeachFieldMerge.Candidate(ScrapeSource.Avbase, "先到"),
                PeachFieldMerge.Candidate(ScrapeSource.CustomJson, "后到")
            ),
            isUsable = nonBlank
        )
        assertEquals("先到", chosen?.value)
    }

    @Test
    fun fc2WinsActorsEvenAgainstHigherTierSource() {
        // peach 的 FIELD_SOURCE_PRIORITY：演员表上 FC2 的「出演者」比目录站准，
        // 目录站常把卖家名当演员写。字段级优先表**先于**层级生效。
        val chosen = PeachFieldMerge.pick(
            field = ScrapeField.Actors,
            candidates = listOf(
                PeachFieldMerge.Candidate(ScrapeSource.R18Dev, listOf("导演写的名字")),
                PeachFieldMerge.Candidate(ScrapeSource.Fc2, listOf("出演者"))
            ),
            isUsable = nonEmptyList
        )
        assertEquals(listOf("出演者"), chosen?.value)
    }

    @Test
    fun noUsableCandidateReturnsNull() {
        val chosen = PeachFieldMerge.pick(
            field = ScrapeField.Tags,
            candidates = listOf(
                PeachFieldMerge.Candidate(ScrapeSource.Javbus, emptyList<String>()),
                PeachFieldMerge.Candidate(ScrapeSource.TheJavDB, emptyList())
            ),
            isUsable = nonEmptyList
        )
        assertNull(chosen)
    }

    @Test
    fun mergeFillsEachFieldFromItsOwnWinner() {
        val base = ScrapeSource.TheJavDB to info(title = "JavDB 标题", studio = "S1")
        val incoming = ScrapeSource.Fc2 to info(
            title = "",
            actors = listOf("出演者A"),
            premiered = "2024-01-02",
            thumbUrl = "https://example.test/cover.jpg"
        )
        val (_, merged) = PeachFieldMerge.merge(base, incoming)
        // 标题留 base 的（incoming 空），其余字段由 incoming 补齐——这是「逐字段」的直观含义。
        assertEquals("JavDB 标题", merged.title)
        assertEquals(listOf("出演者A"), merged.actors)
        assertEquals("2024-01-02", merged.premiered)
        assertEquals("https://example.test/cover.jpg", merged.thumbUrl)
        assertEquals("S1", merged.studio)
    }

    @Test
    fun mergeNeverRewritesNumber() {
        val base = ScrapeSource.Javbus to ScrapedMovieInfo(number = "SSIS-115", title = "A")
        val incoming = ScrapeSource.TheJavDB to ScrapedMovieInfo(number = "SSIS-001", title = "B")
        val (_, merged) = PeachFieldMerge.merge(base, incoming)
        // 番号由链尾统一改写成查询番号；中途被别的来源改写会让后续一致性判断读到脏值。
        assertEquals("SSIS-115", merged.number)
    }

    @Test
    fun mergeWithNullBaseReturnsIncoming() {
        val incoming = ScrapeSource.Javbus to info(title = "唯一一份")
        val (source, merged) = PeachFieldMerge.merge(null, incoming)
        assertEquals(ScrapeSource.Javbus, source)
        assertEquals("唯一一份", merged.title)
    }

    @Test
    fun mainSourceLabelFollowsTheHigherTierContributor() {
        val base = ScrapeSource.Javbus to info(title = "社区")
        val incoming = ScrapeSource.R18Dev to info(title = "镜像")
        val (source, merged) = PeachFieldMerge.merge(base, incoming)
        assertEquals(ScrapeSource.R18Dev, source)
        assertEquals(ScrapeSource.R18Dev.label, merged.source)
    }

    @Test
    fun premieredPrefersTheOfficialIssuer() {
        // FIELD_SOURCE_PRIORITY 里 Premiered 先 FC2 再一本道：这两家的日期就是作品页上的
        // 「販売日/配信日」，目录站对这两类号常写成上架日或干脆没有。
        val chosen = PeachFieldMerge.pick(
            field = ScrapeField.Premiered,
            candidates = listOf(
                PeachFieldMerge.Candidate(ScrapeSource.OnePondo, "2024-03-04"),
                PeachFieldMerge.Candidate(ScrapeSource.Fc2, "2024-01-02")
            ),
            isUsable = nonBlank
        )
        assertEquals("2024-01-02", chosen?.value)
    }
}