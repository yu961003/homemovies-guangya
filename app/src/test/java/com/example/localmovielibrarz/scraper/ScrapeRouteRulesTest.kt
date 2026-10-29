package com.example.localmovielibrarz.scraper

import org.junit.Assert.*
import org.junit.Test

class ScrapeRouteRulesTest {

    @Test
    fun classifiesFc2PpvNumber() {
        assertEquals(ScrapeContentType.Fc2, ScrapeRouteRules.classify("FC2-PPV-1234567"))
    }

    @Test
    fun classifiesFc2NumberWithUnderscoreAndMixedCase() {
        assertEquals(ScrapeContentType.Fc2, ScrapeRouteRules.classify("fc2_ppv_1234567"))
        assertEquals(ScrapeContentType.Fc2, ScrapeRouteRules.classify("FC2-1234567"))
    }

    @Test
    fun classifiesAmateurPrefix() {
        assertEquals(ScrapeContentType.Amateur, ScrapeRouteRules.classify("SIRO-1234"))
    }

    @Test
    fun classifiesCensoredNumberWithNumericPrefix() {
        assertEquals(ScrapeContentType.Censored, ScrapeRouteRules.classify("300MIUM-001"))
    }

    @Test
    fun classifiesStandardCensoredNumber() {
        assertEquals(ScrapeContentType.Censored, ScrapeRouteRules.classify("SSIS-115"))
    }

    @Test
    fun classifiesUncensoredPrefix() {
        assertEquals(ScrapeContentType.Uncensored, ScrapeRouteRules.classify("1PONDO-010123_001"))
    }

    @Test
    fun classifiesUnrecognizedNameAsUnknown() {
        assertEquals(ScrapeContentType.Unknown, ScrapeRouteRules.classify("随便一个文件名"))
    }

    @Test
    fun classifiesKoreanMibFromFileName() {
        assertEquals(ScrapeContentType.Kmib, ScrapeRouteRules.classify("", "韩国 MIB - 某作品.mp4"))
    }

    @Test
    fun classifiesDomesticProduction() {
        assertEquals(ScrapeContentType.Other, ScrapeRouteRules.classify("麻豆-001"))
        assertEquals(ScrapeContentType.Other, ScrapeRouteRules.classify("MD-0123"))
    }

    @Test
    fun domesticMarkersDoNotSwallowRealCensoredNumbers() {
        assertEquals(ScrapeContentType.Censored, ScrapeRouteRules.classify("MDVR-123"))
        assertEquals(ScrapeContentType.Censored, ScrapeRouteRules.classify("ABP-919"))
        assertEquals(ScrapeContentType.Censored, ScrapeRouteRules.classify("CAWD-091"))
    }

    @Test
    fun orderForKeepsEveryConfiguredSourceForEachContentType() {
        val configured = ScrapeSource.values().toList() - ScrapeSource.Priority
        for (type in ScrapeContentType.values()) {
            val ordered = ScrapeRouteRules.orderFor(type, configured)
            assertEquals("类型 $type 的长度不应变化", configured.size, ordered.size)
            assertEquals("类型 $type 的集合不应变化", configured.toSet(), ordered.toSet())
        }
    }

    @Test
    fun orderForCensoredPinsOfficialDmmAndDmm2InConfiguredOrder() {
        val configured = listOf(
            ScrapeSource.Javbus,
            ScrapeSource.Dmm2,
            ScrapeSource.Mgstage,
            ScrapeSource.Official,
            ScrapeSource.Dmm,
            ScrapeSource.CustomJson,
        )
        val ordered = ScrapeRouteRules.orderFor(ScrapeContentType.Censored, configured)
        assertEquals(listOf(ScrapeSource.Dmm2, ScrapeSource.Official, ScrapeSource.Dmm), ordered.take(3))
        assertEquals(configured.size, ordered.size)
        assertEquals(configured.toSet(), ordered.toSet())
    }

    @Test
    fun orderForAmateurPinsMgstage() {
        val configured = listOf(ScrapeSource.Javbus, ScrapeSource.Dmm, ScrapeSource.Mgstage)
        val ordered = ScrapeRouteRules.orderFor(ScrapeContentType.Amateur, configured)
        assertEquals(ScrapeSource.Mgstage, ordered.first())
        assertEquals(configured.size, ordered.size)
        assertEquals(configured.toSet(), ordered.toSet())
    }

    @Test
    fun orderForFc2PinsDmm2AndCustomJsonInConfiguredOrder() {
        val configured = listOf(ScrapeSource.Javbus, ScrapeSource.CustomJson, ScrapeSource.Dmm2)
        val ordered = ScrapeRouteRules.orderFor(ScrapeContentType.Fc2, configured)
        assertEquals(listOf(ScrapeSource.CustomJson, ScrapeSource.Dmm2), ordered.take(2))
        assertEquals(configured.size, ordered.size)
        assertEquals(configured.toSet(), ordered.toSet())
    }

    @Test
    fun orderForUncensoredPinsDmm2() {
        val configured = listOf(ScrapeSource.Dmm, ScrapeSource.Official, ScrapeSource.Dmm2)
        val ordered = ScrapeRouteRules.orderFor(ScrapeContentType.Uncensored, configured)
        assertEquals(ScrapeSource.Dmm2, ordered.first())
        assertEquals(configured.size, ordered.size)
        assertEquals(configured.toSet(), ordered.toSet())
    }

    @Test
    fun orderForUnknownReturnsConfiguredUnchanged() {
        val configured = listOf(ScrapeSource.Javbus, ScrapeSource.Dmm, ScrapeSource.Mgstage)
        assertEquals(configured, ScrapeRouteRules.orderFor(ScrapeContentType.Unknown, configured))
    }
}
