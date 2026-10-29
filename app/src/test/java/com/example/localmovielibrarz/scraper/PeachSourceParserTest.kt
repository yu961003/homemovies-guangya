package com.example.localmovielibrarz.scraper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 三家新源的**解析层**回归（Avbase / 一本道 / FC2）。
 *
 * 这一层是整条桃链里最容易坏的部分：三个站分别用 Next.js 内嵌 JSON、自研详情 JSON、ld+json，
 * 改版不需要通知任何人。把解析器与取数分开、只吃 `String`，就能用下面这些从真页面裁下来的
 * 样本片段做离线回归——否则这三段逻辑只能等线上出错才发现。
 *
 * 每个站点都钉三类断言：正常一页、番号对不上的另一部片、页面结构变了（改版/验证页）。
 * 第二类尤其重要：官网对「同一天发行的另一部片」是**有答案**的，认不出番号就等于把别人的
 * 资料写到这部片上，比没有资料更糟。
 */
class PeachSourceParserTest {

    // ---------------------------------------------------------------- AVBase

    private val avbasePage = """
        <html><head><title>SSIS-115 | AVBase</title></head><body>
        <script id="__NEXT_DATA__" type="application/json">{
          "props": {
            "pageProps": {
              "works": [
                {
                  "prefix": "ssis",
                  "work_id": "SSIS-115",
                  "title": "作品名",
                  "min_date": "Tue Jan 02 2024 09:00:00 GMT+0900 (Japan Standard Time)",
                  "actors": [{"name": "演员甲"}, {"name": "演员乙"}],
                  "products": [
                    {
                      "source": "duga",
                      "product_id": "ssis115",
                      "title": "商品名",
                      "image_url": "https://img.example.com/ssis-115-pl.jpg",
                      "date": "Tue Jan 02 2024 09:00:00 GMT+0900 (Japan Standard Time)",
                      "maker": {"name": "S1"},
                      "label": {"name": "S1 NO.1 STYLE"},
                      "series": {"name": "系列名"},
                      "iteminfo": {"director": "监督名"}
                    }
                  ]
                }
              ]
            }
          }
        }</script></body></html>
    """.trimIndent()

    @Test
    fun avbaseParsesWorkWithItsOwnProductOnly() {
        val result = AvbaseParser.parse(avbasePage, "SSIS-115")
        assertTrue(result is PeachParseResult.Record)
        val record = result as PeachParseResult.Record
        assertEquals("SSIS-115", record.info.number)
        assertEquals("商品名", record.info.title)
        assertEquals("2024-01-02", record.info.premiered)
        assertEquals("2024", record.info.year)
        assertEquals("S1", record.info.studio)
        assertEquals("S1 NO.1 STYLE", record.info.publisher)
        assertEquals("系列名", record.info.series)
        assertEquals(listOf("监督名"), record.info.directors)
        assertEquals(listOf("演员甲", "演员乙"), record.info.actors)
        assertEquals(listOf("https://img.example.com/ssis-115-pl.jpg"), record.coverUrls)
    }

    @Test
    fun avbaseReportsNotFoundWhenWorkIdDiffers() {
        // 同一天的另一部片：页面有内容，但 work_id 不是这一段。
        val result = AvbaseParser.parse(avbasePage, "SSIS-116")
        assertTrue(result is PeachParseResult.Failed)
        assertEquals(PeachFailureReason.NotFound, (result as PeachParseResult.Failed).reason)
    }

    @Test
    fun avbaseTreatsChallengePageAsBlockedNotParseError() {
        val html = "<html><head><title>Just a moment...</title></head><body></body></html>"
        val result = AvbaseParser.parse(html, "SSIS-115")
        assertTrue(result is PeachParseResult.Failed)
        // 验证页与改版都是「没拿到 __NEXT_DATA__」，但处置完全不同：验证页该整源冷却。
        assertEquals(PeachFailureReason.CloudflareChallenge, (result as PeachParseResult.Failed).reason)
    }

    @Test
    fun avbaseReportsParseErrorForChangedMarkup() {
        val result = AvbaseParser.parse("<html><body>改版了</body></html>", "SSIS-115")
        assertEquals(PeachFailureReason.ParseError, (result as PeachParseResult.Failed).reason)
    }

    // ---------------------------------------------------------------- 一本道

    private fun onepondoJson(movieId: String) = """
        {
          "MovieID": "$movieId",
          "Title": "一本道作品",
          "Desc": "简介",
          "Release": "2024-01-02 00:00:00",
          "Series": "系列",
          "Duration": 2510,
          "UCNAME": ["无码", "单体"],
          "ActressesJa": ["女优甲"],
          "ThumbUltra": "https://img.example.com/1pon_ultra.jpg"
        }
    """.trimIndent()

    @Test
    fun onepondoParsesDetailJson() {
        val result = OnePondoParser.parse(onepondoJson("010224_001"), "010224-001")
        assertTrue(result is PeachParseResult.Record)
        val info = (result as PeachParseResult.Record).info
        assertEquals("010224_001", info.number)
        assertEquals("一本道", info.studio)
        // 官网给秒，账本按分钟：2510 秒 = 41.83 分钟。
        assertEquals("41.83", info.runtime)
        assertEquals("2024-01-02", info.premiered)
        assertEquals(listOf("女优甲"), info.actors)
        assertEquals("https://img.example.com/1pon_ultra.jpg", info.posterUrl)
    }

    @Test
    fun onepondoRejectsJsonFromAnotherRelease() {
        // 同一天发行的另一部片：MovieID 对不上就必须拒收，而不是把标题写进来。
        val result = OnePondoParser.parse(onepondoJson("010224_002"), "010224-001")
        assertEquals(PeachFailureReason.NotFound, (result as PeachParseResult.Failed).reason)
    }

    @Test
    fun onepondoReportsNotFoundForNonDatedCode() {
        val result = OnePondoParser.parse(onepondoJson("010224_001"), "SSIS-115")
        assertEquals(PeachFailureReason.NotFound, (result as PeachParseResult.Failed).reason)
    }

    @Test
    fun onepondoReportsParseErrorForNonJsonBody() {
        val result = OnePondoParser.parse("<html>not json</html>", "010224-001")
        assertEquals(PeachFailureReason.ParseError, (result as PeachParseResult.Failed).reason)
    }

    // ---------------------------------------------------------------- FC2

    private val fc2Page = """
        <html><head><title>【個人撮影】作品名</title></head><body>
        <script type="application/ld+json">
        {
          "@context": "https://schema.org",
          "@type": "Product",
          "sku": "4137487",
          "name": "[FC2-PPV-4137487] 作品名",
          "description": "作品简介",
          "image": {"@type": "ImageObject", "url": "https://contents-thumbnail2.fc2.com/w2560/storage33000.contents.fc2.com/article_image/4137/4137487.jpg"}
        }
        </script>
        <div class="items_article_headerInfo">
          <a href="/users/12345/">卖家名</a>
        </div>
        <p class="items_article_info">収録時間：41:50</p>
        <span>販売日：2024/01/02</span>
        </body></html>
    """.trimIndent()

    @Test
    fun fc2ParsesProductLdJsonAndRestoresOriginalImage() {
        val result = Fc2Parser.parse(fc2Page, "FC2-PPV-4137487")
        assertTrue(result is PeachParseResult.Record)
        val info = (result as PeachParseResult.Record).info
        assertEquals("FC2-PPV-4137487", info.number)
        // 标题开头的番号要剥掉，否则账本里会出现「FC2-PPV-4137487 [FC2-PPV-4137487] …」。
        assertEquals("作品名", info.title)
        assertEquals("FC2-PPV", info.studio)
        assertEquals("2024-01-02", info.premiered)
        // 41 分 50 秒 → 41.83 分钟：与一本道那条链（秒 → 分钟）同一口径，
        // 两条链写出的时长才可比。
        assertEquals("41.83", info.runtime)
        // contents-thumbnail 的 w2560 前缀是缩略图包装，原图在 storage*.fc2.com 上。
        assertEquals(
            "https://storage33000.contents.fc2.com/article_image/4137/4137487.jpg",
            info.posterUrl
        )
    }

    @Test
    fun fc2ReportsNotFoundWhenSkuBelongsToAnotherProduct() {
        val result = Fc2Parser.parse(fc2Page, "FC2-PPV-4999999")
        assertEquals(PeachFailureReason.NotFound, (result as PeachParseResult.Failed).reason)
    }

    @Test
    fun fc2SoldOutPageIsNotFoundNotParseError() {
        // 下架商品回 200 但正文没有 Product 段：这是「站上没有」，不该触发整站冷却。
        val page = "<html><body>見つかりませんでした</body></html>"
        val result = Fc2Parser.parse(page, "FC2-PPV-4137487")
        assertEquals(PeachFailureReason.NotFound, (result as PeachParseResult.Failed).reason)
    }

    @Test
    fun fc2AcceptsAlternativeCodeSpellings() {
        assertEquals("4137487", Fc2Parser.videoId("FC2PPV-4137487"))
        assertEquals("4137487", Fc2Parser.videoId("fc2-ppv 4137487"))
        assertEquals("", Fc2Parser.videoId("SSIS-115"))
    }

    // ---------------------------------------------------------------- 番号身份

    @Test
    fun releaseIdentityKeepsDatedSeparatorButIdentifiesBothSpellings() {
        assertTrue(PeachCodeIdentity.sameReleaseCode("SSIS-115", "ssis00115"))
        assertTrue(!PeachCodeIdentity.sameReleaseCode("SSIS-115", "SSIS-116"))
        // 日期式番号的分隔符**不**抹平：一本道的 `092415_001` 与加勒比的 `092415-001`
        // 是两次不同发行，抹平就等于把两部片认成同一部。
        assertTrue(!PeachCodeIdentity.sameReleaseCode("010224-001", "010224_001"))
        // 但拿来源返回的 id 去核番号时必须两边都认，否则一本道那条链永远核不过。
        assertTrue(PeachCodeIdentity.identifiesCode("010224-001", listOf("010224_001")))
    }

    // ---------------------------------------------------------------- 失败归类

    @Test
    fun failureReasonsFollowPeachReasonKinds() {
        assertEquals(PeachReasonKind.Auth, PeachFailureReason.CloudflareChallenge.kind)
        assertEquals(PeachReasonKind.Auth, PeachFailureReason.IpBanned.kind)
        assertEquals(PeachReasonKind.Auth, PeachFailureReason.GeoRestricted.kind)
        assertEquals(PeachReasonKind.NotFound, PeachFailureReason.NotFound.kind)
        assertEquals(PeachReasonKind.NotFound, PeachFailureReason.Gone.kind)
        assertEquals(PeachReasonKind.NotFound, PeachFailureReason.NoUsableMetadata.kind)
        assertEquals(PeachReasonKind.Unavailable, PeachFailureReason.RateLimited.kind)
        assertEquals(PeachReasonKind.Unavailable, PeachFailureReason.Timeout.kind)
        assertEquals(PeachReasonKind.Unavailable, PeachFailureReason.ServerError.kind)
        assertEquals(PeachReasonKind.Unavailable, PeachFailureReason.Network.kind)
    }

    @Test
    fun onlyBlockedAndRateLimitedStopTheWholeSource() {
        assertEquals(PeachCooldownAction.Blocked, PeachFailureReason.CloudflareChallenge.cooldownAction)
        assertEquals(PeachCooldownAction.Blocked, PeachFailureReason.IpBanned.cooldownAction)
        assertEquals(PeachCooldownAction.Blocked, PeachFailureReason.GeoRestricted.cooldownAction)
        assertEquals(PeachCooldownAction.RateLimited, PeachFailureReason.RateLimited.cooldownAction)
        // 超时/网络/5xx 只是这一次不巧，换源即可，不该让整个站停半小时。
        assertEquals(null, PeachFailureReason.Timeout.cooldownAction)
        assertEquals(null, PeachFailureReason.Network.cooldownAction)
        assertEquals(null, PeachFailureReason.ServerError.cooldownAction)
    }

    @Test
    fun parseErrorIsTheOnlyPermanentReason() {
        val permanent = PeachFailureReason.entries.filter { it.permanent }
        assertEquals(listOf(PeachFailureReason.ParseError), permanent)
    }
}