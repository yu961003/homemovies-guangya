package com.example.localmovielibrarz.cloudguangya

import okhttp3.MediaType.Companion.toMediaType

/**
 * 光鸭云盘接入常量。
 *
 * 协议端点来自官方 Python SDK guangyaclient (v0.0.2) 与历史第三方包 guangyapan 的反编译。
 * 登录方式：短信验证码 + OAuth Device Code 扫码登录（两者共存，均为 App 内完成）。
 */
object GuangyaConstants {
    /** 鉴权域名：短信登录 / 扫码登录(device code) / 令牌刷新。返回【顶层】JSON。 */
    const val ACCOUNT_BASE = "https://account.guangyapan.com"

    /** 短信登录令牌交换 / 扫码轮询端点。 */
    const val AUTH_TOKEN_PATH = "/v1/auth/token"

    /** 扫码登录：发起 Device Code 授权（返回 device_code + verification_uri_complete）。 */
    const val DEVICE_CODE_PATH = "/v1/auth/device/code"

    /** 扫码登录 scope（guangya_flutter-main 同款）。 */
    const val DEVICE_CODE_SCOPE = "user"

    /** OAuth Device Code 轮询间隔（秒）。 */
    const val DEVICE_POLL_INTERVAL_SECONDS = 5L

    /** 复刻官方 SDK 的桌面 Chrome UA（光鸭鉴权头 user-agent / x-device-model 依赖）。 */
    const val CHROME_UA =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Safari/537.36"

    /** 业务域名：文件列表 / 直链。返回 {msg, data, error} 信封。 */
    const val API_BASE = "https://api.guangyapan.com"

    /**
     * 客户端 ID。
     *
     * ⚠️ 合规风险提示（P2-5）：此值取自第三方 guangyapan 库，本质是“冒充该应用身份”访问光鸭，
     * 可能违反光鸭 ToS；且登录可能触发 captcha / shield 校验。建议向光鸭申请自有 client_id 后替换。
     */
    const val CLIENT_ID = "aMe-8VSlkrbQXpUR"

    val MEDIA_JSON = "application/json; charset=utf-8".toMediaType()
}
