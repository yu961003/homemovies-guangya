package com.example.localmovielibrarz.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EntityNameNormalizerTest {

    @Test
    fun foldsFullWidthCharactersAndCommonPunctuation() {
        assertEquals("abc 123", EntityNameNormalizer.normalize("ＡＢＣ　１２３"))
        assertEquals("(葵)", EntityNameNormalizer.normalize("（葵）"))
        assertEquals("[葵]", EntityNameNormalizer.normalize("【葵】"))
        assertEquals("a,b.c", EntityNameNormalizer.normalize("Ａ，Ｂ。Ｃ"))
    }

    @Test
    fun trimsAndCollapsesWhitespace() {
        assertEquals("葵 つかさ", EntityNameNormalizer.normalize("  葵   つかさ  "))
        assertEquals("葵 つかさ", EntityNameNormalizer.normalize("葵\tつかさ"))
        assertEquals("葵 つかさ", EntityNameNormalizer.normalize("葵\u00A0つかさ"))
        // ・ 在日文名里当分隔符用，折叠成空格后应与普通空格写法等价。
        assertEquals("葵 つかさ", EntityNameNormalizer.normalize("葵・つかさ"))
    }

    @Test
    fun removesZeroWidthCharacters() {
        assertEquals("葵つかさ", EntityNameNormalizer.normalize("葵\u200Bつかさ"))
        assertEquals("葵つかさ", EntityNameNormalizer.normalize("\uFEFF葵\u200Dつかさ"))
        assertEquals("葵つかさ", EntityNameNormalizer.normalize("葵\u00ADつかさ"))
    }

    @Test
    fun lowercasesForComparisonButPreservesDisplayCase() {
        assertEquals("aoi tsukasa", EntityNameNormalizer.normalize("Aoi  TSUKASA"))
        assertEquals("Aoi TSUKASA", EntityNameNormalizer.canonicalFrom("  Aoi   TSUKASA "))
        assertEquals("AOI TSUKASA", EntityNameNormalizer.canonicalFrom("ＡＯＩ　ＴＳＵＫＡＳＡ"))
    }

    @Test
    fun groupsAliasSpellingsAndKeepsFirstSeenForm() {
        val groups = EntityNameNormalizer.aliasGroups(
            listOf(
                "葵つかさ",
                "葵 つかさ",
                "ＡＯＩ　ＴＳＵＫＡＳＡ",
                "aoi tsukasa",
                "別の人"
            )
        )

        assertEquals(
            listOf(
                listOf("葵つかさ", "葵 つかさ"),
                listOf("AOI TSUKASA", "aoi tsukasa"),
                listOf("別の人")
            ),
            groups
        )
    }

    @Test
    fun keysOfDeduplicatesByNormalizedFormAndPreservesOrder() {
        val keys = EntityNameNormalizer.keysOf(
            listOf("Aoi Tsukasa", "aoi tsukasa", "葵つかさ", "葵 つかさ", "Aoi Tsukasa")
        )

        assertEquals(3, keys.size)
        assertEquals(
            listOf(
                EntityNameNormalizer.EntityKey(canonical = "Aoi Tsukasa", normalized = "aoi tsukasa"),
                EntityNameNormalizer.EntityKey(canonical = "葵つかさ", normalized = "葵つかさ"),
                // 有无空格是不同的 normalized，keysOf 不合并；交给 aliasGroups 归组。
                EntityNameNormalizer.EntityKey(canonical = "葵 つかさ", normalized = "葵 つかさ")
            ),
            keys
        )
    }

    @Test
    fun aliasGroupsSkipsBlankValuesAndKeepsStableOrder() {
        val values = listOf("B", "   ", "A", "b", "C")

        val groups = EntityNameNormalizer.aliasGroups(values)

        assertEquals(listOf(listOf("B", "b"), listOf("A"), listOf("C")), groups)
        assertEquals(groups, EntityNameNormalizer.aliasGroups(values))
        assertTrue(EntityNameNormalizer.aliasGroups(listOf(" ", "\u200B")).isEmpty())
    }

    @Test
    fun normalizationIsIdempotent() {
        val once = EntityNameNormalizer.normalize("　ＡＯＩ　つかさ\t")
        assertEquals(once, EntityNameNormalizer.normalize(once))
        assertEquals("AOI つかさ", EntityNameNormalizer.canonicalFrom("　ＡＯＩ　つかさ\t"))
    }
}
