package com.example.localmovielibrarz.playback

const val USER_AGENT =
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/111.0.0.0 Safari/537.36"

const val DEFAULT_USER_AGENT = USER_AGENT

/** 路由常量（与 [PickcodeExtractor.supportedRoutes] 对应；115 的三个路由统一归一化为 [ROUTE_115]）。 */
const val ROUTE_115 = "115"
const val ROUTE_GUANGYA_RES = "guangya_res"
const val ROUTE_GUANGYA_VOD = "guangya_vod"

data class PlaybackRequest(
    val mediaUri: String,
    val title: String?,
    val userAgent: String?,
    val referer: String?,
    val cookie: String?,
    val pickcode: String? = null,
    val headers: Map<String, String> = emptyMap(),
    /**
     * 命中路由：[ROUTE_GUANGYA_RES] / [ROUTE_GUANGYA_VOD] = 光鸭，[ROUTE_115] = 115，
     * null = 直链或未知。H 轮新增：供 UI 按真实网盘显示文案，并按路由失效正确的直链缓存。
     */
    val route: String? = null
) {
    /** 是否来自光鸭云盘。 */
    val isGuangya: Boolean get() = route == ROUTE_GUANGYA_RES || route == ROUTE_GUANGYA_VOD
}
