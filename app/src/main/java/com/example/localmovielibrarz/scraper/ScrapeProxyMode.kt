package com.example.localmovielibrarz.scraper

/**
 * 刮削出口的代理模式。
 *
 * 为什么要有三档而不是「开/关」一个布尔：
 * - 旧实现的「关」= 给 OkHttp 返回 [java.net.Proxy.NO_PROXY]，这是**显式直连**，
 *   不是「按系统默认走」。OkHttp 只在完全没设置 proxySelector 时才用
 *   `ProxySelector.getDefault()`；一旦自己装了 selector 又返回 NO_PROXY，系统代理
 *   （Wi-Fi 代理、部分 VPN 应用下发的 http.proxyHost）就被彻底跳过。
 * - peach 的默认值是 `environment`（`trust_env=True`，跟随环境/系统代理），
 *   `direct` 才是 `trust_env=False` 的显式直连。这里按 peach 的三档对齐。
 *
 * 语义边界（照抄 peach 的 `peach_proxy.values()` + `client_options()`）：
 * - [Environment]：交给 `ProxySelector.getDefault()`，即跟随系统代理；没有系统代理时它自己返回 DIRECT。
 * - [Direct]：显式 DIRECT，等同于旧版「关闭代理开关」的行为。
 * - [Proxy]：使用用户填写的固定代理；地址非法时**回退到 [Environment] 而不是直连**，
 *   因为用户既然开了代理，直连几乎一定失败，跟随系统代理至少还有机会。
 */
enum class ScrapeProxyMode(val storageValue: String, val label: String) {
    /** 跟随系统代理（peach 的 environment，默认值）。 */
    Environment("environment", "跟随系统"),

    /** 强制直连，不走任何代理（peach 的 direct）。 */
    Direct("direct", "直连"),

    /** 使用下方填写的固定代理（peach 的 proxy）。 */
    Proxy("proxy", "自定义代理");

    companion object {
        /** peach 的默认模式。 */
        val DEFAULT: ScrapeProxyMode = Environment

        /**
         * 从存储值解析；无法识别时回退 [DEFAULT]。
         *
         * 容错方向刻意选「跟随系统」而不是「直连」：历史上「关开关」等于直连，
         * 而本枚举上线前用户的这个状态已被下面的迁移规则改写成 [Environment]，
         * 走到这里的分支只可能是脏数据，此时跟随系统比强制直连更可能让刮削可用。
         */
        fun fromStorage(value: String?): ScrapeProxyMode =
            entries.firstOrNull { it.storageValue == value?.trim()?.lowercase() } ?: DEFAULT

        /**
         * 旧布尔键 `scrape_proxy_enabled` 的迁移。
         *
         * 为什么「开 → Proxy、关 → Environment」：
         * - 开：用户自己填了地址并启用，语义就是「用我填的这个」，直接映射到 [Proxy]；
         * - 关：旧语义是直连，但**用户从未表达过「我要绕过系统代理」**，只是从没设过。
         *   把它迁成 [Direct] 等于把「默认值」固化成「显式选择」，本次要修的正是这个默认值，
         *   所以迁成 [Environment]。真想直连的用户可以在设置页手动选「直连」。
         */
        fun migrateFromLegacyEnabled(legacyEnabled: Boolean): ScrapeProxyMode =
            if (legacyEnabled) Proxy else Environment
    }
}