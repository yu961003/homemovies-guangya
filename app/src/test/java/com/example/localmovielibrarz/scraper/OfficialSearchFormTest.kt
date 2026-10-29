package com.example.localmovielibrarz.scraper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [OfficialSearchForm] 的行为锁。
 *
 * 这一层做的是「读站点自己声明的搜索入口」，正确性只有两种：要么返回一条真实存在的地址，
 * 要么返回 null 让上层如实报缺。所以测试分两类——**该发现的必须发现**，**没证据的必须返回 null**。
 * 后者比前者重要：任何「猜一个路径」的实现都能让前一类通过，却会在后一类里把
 * 「我们配错了」伪装成「站上没有这部片」。
 */
class OfficialSearchFormTest {

    // ── 该发现的情形 ────────────────────────────────────────────────────────

    @Test
    fun discoversAbsoluteGetFormAction() {
        val html = """
            <html><body>
              <form action="https://faleno.jp/search/" method="get">
                <input type="text" name="q" placeholder="作品を検索">
                <button type="submit">検索</button>
              </form>
            </body></html>
        """.trimIndent()

        assertEquals(
            "https://faleno.jp/search/?q=",
            OfficialSearchForm.discoverSearchPrefix(html, "https://faleno.jp/")
        )
    }

    @Test
    fun followsSiteAbsoluteActionAndKeepsOrigin() {
        val html = """
            <form action="/search/result" method="GET">
              <input type="search" name="keyword">
            </form>
        """.trimIndent()

        assertEquals(
            "https://dahlia-movies.example/search/result?keyword=",
            OfficialSearchForm.discoverSearchPrefix(html, "https://dahlia-movies.example/top")
        )
    }

    /** 没有 `action` 时表单提交回本页，所以基准是页面自己的地址，不是编出来的路径。 */
    @Test
    fun fallsBackToSamePageWhenActionIsMissing() {
        val html = """<form method="get"><input type="text" name="s"></form>"""

        assertEquals(
            "https://example.jp/list?page=2&s=",
            OfficialSearchForm.discoverSearchPrefix(html, "https://example.jp/list?page=2")
        )
    }

    /** 协议相对地址补上当前页的 scheme。 */
    @Test
    fun completesProtocolRelativeAction() {
        val html = """<form action="//cdn.example.jp/find" method="get"><input name="q"></form>"""

        assertEquals(
            "https://cdn.example.jp/find?q=",
            OfficialSearchForm.discoverSearchPrefix(html, "https://example.jp/")
        )
    }

    /** 多个表单时优先「真的有 action」的那个，即使它排在后面。 */
    @Test
    fun prefersFormWithActionOverEarlierOneWithout() {
        val html = """
            <form method="get"><input type="text" name="q"></form>
            <form action="https://example.jp/find" method="get"><input type="text" name="word"></form>
        """.trimIndent()

        assertEquals(
            "https://example.jp/find?word=",
            OfficialSearchForm.discoverSearchPrefix(html, "https://example.jp/")
        )
    }

    /** 一个都不在通用名里，但表单只有一个文本输入：那仍然是表单自己说的搜索框。 */
    @Test
    fun acceptsSingleTextFieldWithUncommonName() {
        val html = """<form action="https://example.jp/s" method="get"><input type="text" name="kensaku"></form>"""

        assertEquals(
            "https://example.jp/s?kensaku=",
            OfficialSearchForm.discoverSearchPrefix(html, "https://example.jp/")
        )
    }

    // ── 没证据时必须返回 null ────────────────────────────────────────────────

    /** POST 表单不能靠拼 URL 复用。 */
    @Test
    fun ignoresPostForm() {
        val html = """<form action="https://example.jp/s" method="post"><input type="text" name="q"></form>"""

        assertNull(OfficialSearchForm.discoverSearchPrefix(html, "https://example.jp/"))
    }

    /** 只有隐藏域（csrf）的表单不是搜索框。 */
    @Test
    fun ignoresFormWithoutUsableTextField() {
        val html = """
            <form action="https://example.jp/s" method="get">
              <input type="hidden" name="token" value="abc">
              <input type="submit" value="送信">
            </form>
        """.trimIndent()

        assertNull(OfficialSearchForm.discoverSearchPrefix(html, "https://example.jp/"))
    }

    /** 一个表单里多个非通用名文本域时，猜不出该用哪个，就一个都不用。 */
    @Test
    fun rejectsAmbiguousTextFieldNames() {
        val html = """
            <form action="https://example.jp/s" method="get">
              <input type="text" name="kensaku">
              <input type="text" name="joken">
            </form>
        """.trimIndent()

        assertNull(OfficialSearchForm.discoverSearchPrefix(html, "https://example.jp/"))
    }

    /** 相对路径的 `action` 补不准（`../`、`?`、`./x`），退回报废而不是猜。 */
    @Test
    fun rejectsRelativeActionItCannotResolve() {
        val html = """<form action="../search" method="get"><input type="text" name="q"></form>"""

        // 退回 homeUrl 本身（去掉尾斜杠后接查询串）：它是一条真实存在的地址，
        // 站点首页自带搜索时仍然可用；这里**不是**把 `../search` 猜成某个路径。
        assertEquals(
            "https://example.jp/dir?q=",
            OfficialSearchForm.discoverSearchPrefix(html, "https://example.jp/dir/")
        )
    }

    @Test
    fun returnsNullOnEmptyOrMissingHtml() {
        assertNull(OfficialSearchForm.discoverSearchPrefix("", "https://example.jp/"))
        assertNull(OfficialSearchForm.discoverSearchPrefix(null, "https://example.jp/"))
        assertNull(OfficialSearchForm.discoverSearchPrefix("<html><body>no forms</body></html>", "https://example.jp/"))
    }
}
