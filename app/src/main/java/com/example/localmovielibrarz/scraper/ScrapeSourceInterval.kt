package com.example.localmovielibrarz.scraper

/**
 * 按来源的最小请求间隔。
 *
 * 出处：peach 的 `library_processing.SOURCE_INTERVALS` 里只对 javdb 一家写了 3 秒，
 * 其余站点走 2 秒默认值。理由是 javdb 按出口 IP 计配额，连打两下就会开始返 403。
 *
 * 鸭影这边的情况不完全一样——没有独立的限流层，来源是一次批量刮削里被顺序打过去的，
 * 一次几百部片子意味着同一家站会被连续敲几百下。所以这里照 peach 的两档取值，
 * 但把它做成**每个来源单独计时**：`CustomJson` 是用户自建的端点，不替用户做限速决定；
 * `Priority` 是链标记而不是真实来源，不参与计时。
 *
 * 计时状态放在 [ScrapeSourceThrottle] 里而不是本对象：object 一旦持有时钟就变成全局可变
 * 状态，测试之间会互相污染，也会让「上次什么时候打的」跨批次残留。
 */
object ScrapeSourceInterval {

    /** javdb 按出口 IP 计配额，peach 实测需要 3 秒；其余联网来源沿用 peach 的 2 秒默认值。 */
    private const val COMMUNITY_INTERVAL_MS = 2_000L
    private const val JAVDB_INTERVAL_MS = 3_000L

    /**
     * 返回该来源两次请求之间至少要隔多久。返回 0 表示不限速。
     */
    fun minIntervalMillis(source: ScrapeSource): Long = when (source) {
        // 用户自建端点：限速应该由他自己在服务端定，鸭影替他放慢只会让他以为程序卡了。
        ScrapeSource.CustomJson -> 0L
        // 链标记，永远不会作为真实请求方出现。
        ScrapeSource.Priority -> 0L
        // javdb 按出口 IP 计配额，隔得不够它直接返 403，冷却反而更久。
        ScrapeSource.TheJavDB -> JAVDB_INTERVAL_MS
        ScrapeSource.Dmm,
        ScrapeSource.Dmm2,
        ScrapeSource.Official,
        ScrapeSource.Mgstage,
        ScrapeSource.Javbus,
        // r18.dev 是静态 JSON，两页几十 KB，官方口径是主机间隔默认 2 秒、不带 Cookie。
        ScrapeSource.R18Dev,
        // peach 这三家都按主机 `DEFAULT_INTERVAL = 2.0` 走：avbase / 1pondo 的 `SiteConfig`
        // 没写 interval，fc2 也没写，取的就是同一档默认值。
        ScrapeSource.Avbase,
        ScrapeSource.OnePondo,
        ScrapeSource.Fc2,
        // 本轮补进来的六家，interval 全部取 peach 的 2 秒默认值：
        // - faleno / dahlia / makers 的 `SiteConfig` 没写 interval；
        // - fc2cmadb / fc2ppvdb / javten 也没写——它们靠 Cookie 与浏览器通道避免被拦，
        //   不是靠拉长间隔，所以这里不该替它们放慢到比 peach 更宽。
        ScrapeSource.Faleno,
        ScrapeSource.Dahlia,
        ScrapeSource.MakerSite,
        ScrapeSource.Fc2Cmadb,
        ScrapeSource.Fc2PpvDb,
        ScrapeSource.Javten,
        -> COMMUNITY_INTERVAL_MS
    }
}
