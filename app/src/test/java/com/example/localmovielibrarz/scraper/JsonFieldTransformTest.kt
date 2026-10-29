package com.example.localmovielibrarz.scraper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 后处理链的行为测试。
 *
 * 这些用例刻意围绕「用户会怎么写、写错会怎样」来组织：除了正常转换，
 * 还钉住非法正则、越界下标、无法解析的日期——它们都必须是「保留原值」，
 * 而不是把异常抛给刮削流程。
 */
class JsonFieldTransformTest {

    private fun apply(raw: String, spec: String?): String =
        JsonFieldTransforms.applyScalar(raw, JsonFieldTransforms.parse(spec))

    @Test
    fun parsesSingleTrimAndEmptySpecs() {
        assertEquals(listOf(JsonFieldTransform.Trim()), JsonFieldTransforms.parse("trim"))
        assertTrue(JsonFieldTransforms.parse("").isEmpty())
        assertTrue(JsonFieldTransforms.parse(null).isEmpty())
    }

    @Test
    fun trimsWhitespaceAndCustomChars() {
        assertEquals("abc", apply("  abc  ", "trim"))
        assertEquals("abc", apply("---abc---", "trim -"))
    }

    @Test
    fun replacesUrlPrefixWithEmptyReplacement() {
        val spec = """replace /^https?:\/\/example\.com/"""
        assertEquals("/a/b", apply("https://example.com/a/b", spec))
        assertEquals("/a/b", apply("http://example.com/a/b", spec))
        // 域名不同就不该被动到
        assertEquals("https://other.example.com/a/b", apply("https://other.example.com/a/b", spec))
    }

    @Test
    fun concatAndPrependBuildFullImageUrl() {
        val spec = "prepend https://img.example.com/ | concat .jpg"
        assertEquals("https://img.example.com/cover.jpg", apply("cover", spec))
    }

    @Test
    fun substringClampsOutOfRangeInsteadOfThrowing() {
        assertEquals("ABCD", apply("ABCDEFG", "substring 0 4"))
        assertEquals("", apply("ABC", "substring 10 20"))
        assertEquals("ABC", apply("ABC", "substring 0 99"))
        assertEquals("ABC", apply("ABC", "substring 0"))
    }

    @Test
    fun splitProducesTrimmedDistinctListItems() {
        val transforms = JsonFieldTransforms.parse("split ,")
        assertEquals(
            listOf("a", "b", "c"),
            JsonFieldTransforms.applyList("a, b ,, c", transforms)
        )
    }

    @Test
    fun parsesDateAndKeepsUnparsableValue() {
        val spec = "date yyyy/MM/dd >> yyyy-MM-dd"
        assertEquals("2024-03-07", apply("2024/03/07", spec))
        assertEquals("not-a-date", apply("not-a-date", spec))
        // 多个源格式按顺序尝试
        assertEquals(
            "2024-03-07",
            apply("2024-03-07", "date yyyy/MM/dd;yyyy-MM-dd >> yyyy-MM-dd")
        )
    }

    @Test
    fun invalidRegexNeverThrowsAndKeepsValue() {
        val spec = """replace /[/ x"""
        // 解析层不该因为正则语法错误就抛异常；合法性到执行层才检查
        assertEquals(
            listOf(JsonFieldTransform.Replace("[", "x")),
            JsonFieldTransforms.parse(spec)
        )
        assertEquals("abc", apply("abc", spec))
    }

    @Test
    fun applyListDeduplicatesAndKeepsFirstOccurrenceOrder() {
        val transforms = JsonFieldTransforms.parse("split ,")
        assertEquals(
            listOf("a", "b"),
            JsonFieldTransforms.applyList("a,b,a", transforms)
        )
    }

    @Test
    fun applyListUsesPipeWhenNoSplitIsDeclared() {
        val transforms = JsonFieldTransforms.parse("trim")
        assertEquals(
            listOf("x", "y"),
            JsonFieldTransforms.applyList(" x | y | x ", transforms)
        )
        // 不足两项时整串就是唯一一项，不能把普通标量值吞掉
        assertEquals(
            listOf("abc"),
            JsonFieldTransforms.applyList("  abc  ", transforms)
        )
    }

    @Test
    fun applyListRunsActionsDeclaredAfterSplitOnEveryItem() {
        val transforms = JsonFieldTransforms.parse("split , | concat !")
        assertEquals(
            listOf("a!", "b!"),
            JsonFieldTransforms.applyList("a, b", transforms)
        )
    }

    @Test
    fun mapValueRequiresWholeStringMatch() {
        val spec = """map /^\d+$/ 数字"""
        assertEquals("数字", apply("123", spec))
        assertEquals("12a", apply("12a", spec))
    }

    @Test
    fun replaceHonorsIgnoreCaseFlag() {
        assertEquals("X", apply("ABC", "replace /abc/i X"))
        assertEquals("ABC", apply("ABC", "replace /abc/ X"))
    }

    @Test
    fun scalarChainStopsAtSplit() {
        val spec = "split , | concat !"
        assertEquals(" a,b ", apply(" a,b ", spec))
    }

    @Test
    fun skipsUnknownActionsAndKeepsTheRest() {
        assertEquals(
            listOf(JsonFieldTransform.Trim(), JsonFieldTransform.Concat(".jpg")),
            JsonFieldTransforms.parse("trim | unknown foo | concat .jpg")
        )
    }
}
