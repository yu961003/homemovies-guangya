package com.example.localmovielibrarz.scraper

/**
 * 刮削请求统一使用的浏览器指纹（对齐 peach 的 `user_agent.py:9-10` 与 `jav_cover_fetch.py:282-283`）。
 *
 * 为什么单独抽出来：改造前七个 scraper 各自写死了一份 UA 与一份语言偏好，版本号散成
 * Chrome/120、124、125、148 四种，语言偏好散成四种（`ja-JP,…`、`ja,…`、`zh-CN,…`）。
 * 同一台设备对同一批站点发出四种版本的自称，本身就是一条可被判为「非真实浏览器」的信号；
 * 而 peach 是全局单一 UA + 单一 Accept-Language，逐站再覆盖（`extra_headers`）。
 *
 * 版本号写在两处是为了让 `Sec-CH-UA`（客户端提示）能与 `User-Agent` 保持一致：
 * 只改 UA 不改 CH，等于在说「我是 Chrome 153，但我的客户端提示是 148」，
 * 比不发送 CH 更容易被识别。
 */
internal object ScrapeUserAgent {
    /** peach `user_agent.py:9-10` 的 USER_AGENT 原值。 */
    const val VALUE =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/153.0.0.0 Safari/537.36"

    /** [VALUE] 里的主版本号，供 `Sec-CH-UA` 复用。 */
    const val MAJOR_VERSION = "153"

    /**
     * peach `jav_cover_fetch.py:283` 的全局 Accept-Language。
     *
     * 注意它**不是**日语优先到会改变页面语言：peach 用同一个值抓 JavBus 的繁体页，
     * 解析器读的仍是「識別碼 / 發行日期」这些繁体标签（`sources/javbus.py:45-62`）。
     * 也就是说 `ja,en;q=0.9` 在这几家站的实测结果是繁体中文页，而不是日文页——
     * 这正是它可以全局统一、不必给 JavBus 单独留 `zh-CN` 的依据。
     */
    const val ACCEPT_LANGUAGE = "ja,en;q=0.9"
}