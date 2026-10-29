package com.example.localmovielibrarz.scraper

/**
 * 刮削链模式（用户可切换的唯一开关）。
 *
 * 为什么需要一个开关，而不是直接把 legacy 删掉：桃链是这一轮才接进来的，站点侧的行为差异
 * （尤其 Javbus/JavDB 这类社区站的解析）只有真机长时间跑才暴露得出来。留一个能一键退回
 * 原链的开关，出问题时用户不必等发版。但**默认值必须是桃链优先**——否则升级后拿到的是旧行为，
 * 等于这次移植白做。
 *
 * 存的是字符串而不是枚举序号（见 [storedValue] / [fromStoredValue]）：
 * SharedPreferences 里存序号，将来往枚举中间插一档就会把用户的选择整体错位。
 */
enum class ScrapeChainMode(
    /** 存进 SharedPreferences 的值。一旦发布就不能再改，否则老安装的选择会读不出来。 */
    val storedValue: String,
    val label: String
) {
    /** 桃链优先：自动链先走桃链，桃链给不出可用结果时才降级到 legacy 链。 */
    PeachPrimary("peach_primary", "桃链优先（默认）"),

    /**
     * 只用 legacy 链。
     *
     * 保留它是为了「桃链在某个站点上解析错了、我要先能刮出东西」这种现场处置；
     * 它**不是**给新用户准备的默认值。
     */
    LegacyOnly("legacy_only", "只用原有刮削链");

    companion object {

        /**
         * 默认值。
         *
         * 用「缺键时返回它」而不是「首次启动时写入它」：老安装升级上来时 prefs 里根本没有这个键，
         * 读出来就该是桃链优先；若靠写入时机，那么任何一次漏写（用户清过数据、备份还原、
         * 设置页没接线）都会让默认值退化成 legacy——那正是本轮要避免的结果。
         */
        val DEFAULT = PeachPrimary

        /** 未知/空值一律回落到 [DEFAULT]，不抛异常：设置项损坏不该让刮削直接失败。 */
        fun fromStoredValue(value: String?): ScrapeChainMode {
            val normalized = value?.trim()?.lowercase()
            return entries.firstOrNull { it.storedValue == normalized } ?: DEFAULT
        }
    }
}
