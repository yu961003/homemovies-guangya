package com.example.localmovielibrarz.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataMergeEngineTest {

    private fun candidate(
        value: String?,
        authority: MetadataAuthority,
        field: MetadataFieldKey = MetadataFieldKey.Title,
        confidence: Double = 1.0,
        sourceLabel: String = authority.name
    ): MetadataCandidate = MetadataCandidate(
        field = field,
        value = value,
        authority = authority,
        sourceLabel = sourceLabel,
        confidence = confidence
    )

    @Test
    fun emptyIncomingKeepsCurrentWithoutReview() {
        val decision = MetadataMergeEngine.decide(
            current = candidate("旧标题", MetadataAuthority.Provider),
            incoming = candidate("   ", MetadataAuthority.LocalNfo)
        )

        assertEquals(Action.KeepCurrent, decision.action)
        assertEquals("incoming_blank", decision.reason)
        assertEquals("旧标题", decision.value)
    }

    @Test
    fun placeholderValuesCountAsBlankButNotAsData() {
        assertTrue(candidate(null, MetadataAuthority.Provider).isBlank)
        assertTrue(candidate("", MetadataAuthority.Provider).isBlank)
        assertTrue(candidate("  ", MetadataAuthority.Provider).isBlank)
        assertTrue(candidate("N/A", MetadataAuthority.Provider).isBlank)
        assertTrue(candidate(" n/a ", MetadataAuthority.Provider).isBlank)
        assertTrue(candidate("未知", MetadataAuthority.Provider).isBlank)
        assertTrue(candidate("-", MetadataAuthority.Provider).isBlank)
        assertFalse(candidate("N/A 续集", MetadataAuthority.Provider).isBlank)

        val decision = MetadataMergeEngine.decide(
            current = candidate("旧标题", MetadataAuthority.Provider),
            incoming = candidate("N/A", MetadataAuthority.LocalNfo)
        )
        assertEquals(Action.KeepCurrent, decision.action)
        assertEquals("incoming_blank", decision.reason)
        assertEquals("旧标题", decision.value)
    }

    @Test
    fun missingCurrentAcceptsIncoming() {
        val applied = MetadataMergeEngine.decide(
            current = null,
            incoming = candidate("文件名标题", MetadataAuthority.Filename)
        )
        assertEquals(Action.Apply, applied.action)
        assertEquals("no_current", applied.reason)
        assertEquals("文件名标题", applied.value)

        val blank = MetadataMergeEngine.decide(
            current = null,
            incoming = candidate(" ", MetadataAuthority.Provider)
        )
        assertEquals(Action.KeepCurrent, blank.action)
        assertEquals("incoming_blank", blank.reason)
        assertNull(blank.value)
    }

    @Test
    fun higherAuthorityOverridesLowerCurrent() {
        val decision = MetadataMergeEngine.decide(
            current = candidate("站点标题", MetadataAuthority.Provider),
            incoming = candidate("NFO 标题", MetadataAuthority.LocalNfo)
        )

        assertEquals(Action.Apply, decision.action)
        assertEquals("higher_authority", decision.reason)
        assertEquals("NFO 标题", decision.value)
        assertTrue(MetadataAuthority.LocalNfo.canOverride(MetadataAuthority.Provider))
        assertFalse(MetadataAuthority.Provider.canOverride(MetadataAuthority.LocalNfo))
        assertFalse(MetadataAuthority.Provider.canOverride(MetadataAuthority.Provider))
    }

    @Test
    fun manualValueIsNotOverwrittenAutomatically() {
        val decision = MetadataMergeEngine.decide(
            current = candidate("用户改过的标题", MetadataAuthority.Manual),
            incoming = candidate("站点标题", MetadataAuthority.LocalNfo)
        )

        assertEquals(Action.NeedsReview, decision.action)
        assertEquals("manual_protected", decision.reason)
        // NeedsReview 的 value 是「建议值」，供待审界面展示，而不是直接写库的内容。
        assertEquals("站点标题", decision.value)
    }

    @Test
    fun manualClearedFieldWaitsForReviewInsteadOfRefill() {
        val decision = MetadataMergeEngine.decide(
            current = candidate("  ", MetadataAuthority.Manual),
            incoming = candidate("站点标题", MetadataAuthority.Provider)
        )

        assertEquals(Action.NeedsReview, decision.action)
        assertEquals("manual_protected", decision.reason)
        assertEquals("站点标题", decision.value)
    }

    @Test
    fun sameAuthorityOnlyFillsBlank() {
        val filled = MetadataMergeEngine.decide(
            current = candidate(" ", MetadataAuthority.Provider),
            incoming = candidate("站点标题", MetadataAuthority.Provider)
        )
        assertEquals(Action.Apply, filled.action)
        assertEquals("same_authority_fill_blank", filled.reason)
        assertEquals("站点标题", filled.value)

        val kept = MetadataMergeEngine.decide(
            current = candidate("先到的标题", MetadataAuthority.Provider),
            incoming = candidate("后到的标题", MetadataAuthority.Provider)
        )
        assertEquals(Action.KeepCurrent, kept.action)
        assertEquals("same_authority_no_gain", kept.reason)
        assertEquals("先到的标题", kept.value)
    }

    @Test
    fun manualToManualFollowsSameAuthorityRule() {
        val kept = MetadataMergeEngine.decide(
            current = candidate("用户写的标题", MetadataAuthority.Manual),
            incoming = candidate("用户又写的标题", MetadataAuthority.Manual)
        )

        assertEquals(Action.KeepCurrent, kept.action)
        assertEquals("same_authority_no_gain", kept.reason)
        assertEquals("用户写的标题", kept.value)
    }

    @Test
    fun lowerAuthorityOnlyFillsBlank() {
        val filled = MetadataMergeEngine.decide(
            current = candidate(" ", MetadataAuthority.LocalNfo),
            incoming = candidate("文件名推断", MetadataAuthority.Filename)
        )
        assertEquals(Action.Apply, filled.action)
        assertEquals("fill_blank_lower_authority", filled.reason)
        assertEquals("文件名推断", filled.value)

        val kept = MetadataMergeEngine.decide(
            current = candidate("NFO 标题", MetadataAuthority.LocalNfo),
            incoming = candidate("文件名推断", MetadataAuthority.Filename)
        )
        assertEquals(Action.KeepCurrent, kept.action)
        assertEquals("lower_authority", kept.reason)
        assertEquals("NFO 标题", kept.value)
    }

    @Test
    fun mergeAllPrefersHighestAuthorityThenConfidence() {
        val current = mapOf(
            MetadataFieldKey.Title to candidate("文件名标题", MetadataAuthority.Filename)
        )
        val incoming = listOf(
            candidate("站点高置信", MetadataAuthority.Provider, confidence = 0.99),
            candidate("NFO 低置信", MetadataAuthority.LocalNfo, confidence = 0.2),
            candidate("NFO 高置信", MetadataAuthority.LocalNfo, confidence = 0.8)
        )

        val decisions = MetadataMergeEngine.mergeAll(current, incoming)

        val title = decisions.getValue(MetadataFieldKey.Title)
        assertEquals(Action.Apply, title.action)
        assertEquals("higher_authority", title.reason)
        assertEquals("NFO 高置信", title.value)
    }

    @Test
    fun mergeAllDecidesEachFieldIndependentlyAndReturnsOnlyIncomingFields() {
        val current = mapOf(
            MetadataFieldKey.Title to candidate("旧标题", MetadataAuthority.Provider),
            MetadataFieldKey.Year to candidate("2001", MetadataAuthority.Provider)
        )
        val incoming = listOf(
            candidate("新标题", MetadataAuthority.Provider, field = MetadataFieldKey.Title),
            candidate("2002", MetadataAuthority.LocalNfo, field = MetadataFieldKey.Year),
            candidate("葵つかさ", MetadataAuthority.Provider, field = MetadataFieldKey.Actors)
        )

        val decisions = MetadataMergeEngine.mergeAll(current, incoming)

        assertEquals(setOf(MetadataFieldKey.Title, MetadataFieldKey.Year, MetadataFieldKey.Actors), decisions.keys)
        val title = decisions.getValue(MetadataFieldKey.Title)
        assertEquals(Action.KeepCurrent, title.action)
        assertEquals("same_authority_no_gain", title.reason)
        assertEquals("旧标题", title.value)
        assertEquals(Action.Apply, decisions.getValue(MetadataFieldKey.Year).action)
        assertEquals("2002", decisions.getValue(MetadataFieldKey.Year).value)
        assertEquals(Action.Apply, decisions.getValue(MetadataFieldKey.Actors).action)
        assertEquals("葵つかさ", decisions.getValue(MetadataFieldKey.Actors).value)
    }

    @Test
    fun mergeAllKeepsFirstCandidateWhenRankAndConfidenceTie() {
        val incoming = listOf(
            candidate("先到的值", MetadataAuthority.Provider, confidence = 0.5),
            candidate("后到的值", MetadataAuthority.Provider, confidence = 0.5)
        )

        val decision = MetadataMergeEngine.mergeAll(emptyMap(), incoming)
            .getValue(MetadataFieldKey.Title)

        assertEquals(Action.Apply, decision.action)
        assertEquals("先到的值", decision.value)
    }

    @Test
    fun fieldKeysMapBackToMovieEntityColumns() {
        assertEquals("runtimeMinutes", MetadataFieldKey.Runtime.storageColumn)
        assertEquals(MetadataFieldKey.Runtime, MetadataFieldKey.fromColumn("runtimeMinutes"))
        assertEquals(MetadataFieldKey.Runtime, MetadataFieldKey.fromColumn("Runtime"))
        assertEquals(MetadataFieldKey.Title, MetadataFieldKey.fromColumn(" title "))
        assertNull(MetadataFieldKey.fromColumn("unknownColumn"))
        assertNull(MetadataFieldKey.fromColumn("   "))

        assertTrue(MetadataFieldKey.Actors.isMultiValue)
        assertTrue(MetadataFieldKey.Studios.isMultiValue)
        assertFalse(MetadataFieldKey.Title.isMultiValue)

        // 列名必须两两不同，否则接线层按列名回查字段会拿到错的字段。
        val columns = MetadataFieldKey.entries.map { it.storageColumn.lowercase() }
        assertEquals(columns.size, columns.toSet().size)
    }
}
