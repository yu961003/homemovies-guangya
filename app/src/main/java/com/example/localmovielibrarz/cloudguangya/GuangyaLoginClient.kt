package com.example.localmovielibrarz.cloudguangya

import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import com.example.localmovielibrarz.util.HttpClients

/** 发码结果：verification_id + 本次实际生效的 captcha_token（重试后可能已刷新）。 */
data class GuangyaSmsSendResult(
    val verificationId: String,
    val captchaToken: String
)

/** 扫码登录会话（OAuth Device Code 第一步）。qrPayload 为二维码内容。 */
data class GuangyaQrSession(
    val deviceCode: String,
    val userCode: String?,
    val qrPayload: String
)

/** 扫码轮询结果。 */
sealed class GuangyaQrPollResult {
    /** 登录成功，携带令牌对。 */
    data class Success(val tokenPair: GuangyaTokenPair) : GuangyaQrPollResult()

    /** 等待用户确认。 */
    data object Pending : GuangyaQrPollResult()

    /** 轮询过快，服务端要求降频。 */
    data object SlowDown : GuangyaQrPollResult()

    /** 二维码过期，需重新发起。 */
    data object Expired : GuangyaQrPollResult()
}

/**
 * 光鸭登录客户端：短信验证码两阶段登录，严格对齐官方 guangyaclient SDK 与 AList 驱动。
 *
 * 流程：
 *   smsInit   → 人机验证初始化，返回 captcha_token（meta 需 username/phone_number/VERIFICATION_PHONE 三键）
 *   smsSend   → 发送短信验证码（需 x-captcha-token 头），返回 verification_id
 *   smsVerify → 校验短信验证码，返回 verification_token
 *   smsSignin → 完成登录（需 x-captcha-token 头），返回 access / refresh token
 *
 * 全程 App 内完成，不跳转浏览器——解决旧 device-code 流程 verification_uri 404 的问题。
 * 鉴权头复刻 AList 驱动的 _account_headers()（x-client-id / x-device-id / x-device-sign 等）。
 */
class GuangyaLoginClient(
    private val tokenStore: GuangyaTokenStore,
    private val device: GuangyaDevice = GuangyaDevice,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .connectionPool(HttpClients.sharedConnectionPool)
        .dispatcher(HttpClients.sharedDispatcher)
        .build()
) {
    /** 持久化设备标识 did：首次生成后落盘，登录与业务请求共用同一个（对齐 guangya_flutter StorageKeys.deviceID）。 */
    private val did: String = tokenStore.getOrCreateDeviceId { device.generateDid() }

    private val accountHeaders: Map<String, String>
        get() = mapOf(
            "accept" to "*/*",
            "content-type" to "application/json",
            "origin" to "https://www.guangyapan.com",
            "referer" to "https://www.guangyapan.com/",
            "user-agent" to GuangyaConstants.CHROME_UA,
            "x-client-id" to GuangyaConstants.CLIENT_ID,
            "x-client-version" to "0.0.1",
            "x-device-id" to did,
            "x-device-model" to "chrome%2F147.0.0.0",
            "x-device-name" to "PC-Chrome",
            "x-device-sign" to device.generateDeviceSign(did),
            "x-net-work-type" to "NONE",
            "x-os-version" to "MacIntel",
            "x-platform-version" to "1",
            "x-protocol-version" to "301",
            "x-provider-name" to "NONE",
            "x-sdk-version" to "9.0.2"
        )

    /** 步骤1：人机验证初始化，返回 captcha_token。meta 需 username/phone_number/VERIFICATION_PHONE 三键（AList 驱动确认）。 */
    suspend fun smsInit(phone: String): String = withContext(Dispatchers.IO) {
        val normalized = normalizePhoneE164(phone)
        val body = JSONObject().apply {
            put("client_id", GuangyaConstants.CLIENT_ID)
            put("action", "POST:/v1/auth/verification")
            put("device_id", did)
            put(
                "meta",
                JSONObject().apply {
                    put("username", normalized)
                    put("phone_number", normalized)
                    put("VERIFICATION_PHONE", normalized)
                }
            )
        }
        val json = post(GuangyaConstants.ACCOUNT_BASE + "/v1/shield/captcha/init", body)
        json.optString("captcha_token").takeIf { it.isNotBlank() }
            ?: error("光鸭人机验证初始化失败（可能需网页验证）")
    }

    /**
     * 步骤2：发送短信验证码，返回 verification_id + 生效的 captcha_token。
     * 若服务端判定 captcha 无效/过期（captcha_invalid / captcha_token expired），
     * 自动强制刷新 captcha_token 并重试一次（对齐 AList 驱动 ensureCaptchaToken 行为）。
     */
    suspend fun smsSend(phone: String, captchaToken: String): GuangyaSmsSendResult =
        withContext(Dispatchers.IO) {
            val normalized = normalizePhoneE164(phone)
            try {
                val vid = doSmsSend(normalized, captchaToken)
                GuangyaSmsSendResult(vid, captchaToken)
            } catch (e: Exception) {
                val msg = e.message.orEmpty()
                if (msg.contains("captcha_invalid") || msg.contains("captcha_token expired") ||
                    msg.contains("4002")
                ) {
                    // 7.1 修复：强制刷新 captcha_token 前短暂延迟，
                    // 避免服务端持续返回 captcha 无效（可能被风控）时形成快速重试循环。
                    delay(1_000)
                    // 强制刷新 captcha_token 后重试一次
                    val refreshed = smsInit(phone)
                    val vid = doSmsSend(normalized, refreshed)
                    GuangyaSmsSendResult(vid, refreshed)
                } else {
                    throw e
                }
            }
        }

    private fun doSmsSend(normalizedPhone: String, captchaToken: String): String {
        val headers = accountHeaders.toMutableMap().apply { put("x-captcha-token", captchaToken) }
        val body = JSONObject().apply {
            put("phone_number", normalizedPhone)
            put("target", "ANY")
            put("client_id", GuangyaConstants.CLIENT_ID)
        }
        val json = post(GuangyaConstants.ACCOUNT_BASE + "/v1/auth/verification", body, headers)
        return json.optString("verification_id").takeIf { it.isNotBlank() }
            ?: error("光鸭发送验证码失败")
    }

    /** 步骤3：校验短信验证码，返回 verification_token。 */
    suspend fun smsVerify(verificationId: String, code: String): String = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("verification_id", verificationId)
            put("verification_code", code)
            put("client_id", GuangyaConstants.CLIENT_ID)
        }
        val json = post(GuangyaConstants.ACCOUNT_BASE + "/v1/auth/verification/verify", body)
        json.optString("verification_token").takeIf { it.isNotBlank() }
            ?: error("光鸭验证码校验失败")
    }

    /** 步骤4：完成登录，返回令牌对。 */
    suspend fun smsSignin(
        code: String,
        verificationToken: String,
        phone: String,
        captchaToken: String
    ): GuangyaTokenPair = withContext(Dispatchers.IO) {
        val headers = accountHeaders.toMutableMap().apply { put("x-captcha-token", captchaToken) }
        val body = JSONObject().apply {
            put("verification_code", code)
            put("verification_token", verificationToken)
            put("username", normalizePhoneE164(phone))
            put("client_id", GuangyaConstants.CLIENT_ID)
        }
        val json = post(GuangyaConstants.ACCOUNT_BASE + "/v1/auth/signin", body, headers)
        val access = json.optString("access_token").takeIf { it.isNotBlank() }
            ?: error("光鸭登录失败")
        val refresh = json.optString("refresh_token").takeIf { it.isNotBlank() } ?: ""
        GuangyaTokenPair(accessToken = access, refreshToken = refresh, obtainedAt = nowSeconds(), expiresInSeconds = json.optLong("expires_in", 0))
    }

    /**
     * 刷新令牌（供 GuangyaTokenProvider 调用，签名保持不变）。
     * 需 x-action: 401 头，对齐 SDK refresh_token()。
     */
    suspend fun refreshTokens(refreshToken: String): GuangyaTokenPair = withContext(Dispatchers.IO) {
        val headers = accountHeaders.toMutableMap().apply { put("x-action", "401") }
        val body = JSONObject().apply {
            put("client_id", GuangyaConstants.CLIENT_ID)
            put("grant_type", "refresh_token")
            put("refresh_token", refreshToken)
        }
        val json = post(GuangyaConstants.ACCOUNT_BASE + GuangyaConstants.AUTH_TOKEN_PATH, body, headers)
        val access = json.optString("access_token").takeIf { it.isNotBlank() } ?: error("光鸭刷新失败")
        // 幂等：新 refresh_token 缺失时沿用旧值
        val refresh = json.optString("refresh_token").takeIf { it.isNotBlank() } ?: refreshToken
        GuangyaTokenPair(accessToken = access, refreshToken = refresh, obtainedAt = nowSeconds(), expiresInSeconds = json.optLong("expires_in", 0))
    }

    // ---------------- 扫码登录（OAuth Device Code） ----------------

    /**
     * 第一步：发起 Device Code 授权。
     * POST /v1/auth/device/code，body {"scope":"user","client_id":CLIENT_ID}。
     * 二维码内容取 verification_uri_complete（新字段，非旧 404 的 verification_uri）。
     */
    suspend fun qrInit(): GuangyaQrSession = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("scope", GuangyaConstants.DEVICE_CODE_SCOPE)
            put("client_id", GuangyaConstants.CLIENT_ID)
        }
        val json = post(GuangyaConstants.ACCOUNT_BASE + GuangyaConstants.DEVICE_CODE_PATH, body)
        val deviceCode = json.optString("device_code").takeIf { it.isNotBlank() }
            ?: error("光鸭扫码登录初始化失败")
        val qrPayload = json.optString("verification_uri_complete").takeIf { it.isNotBlank() }
            ?: json.optString("verification_uri").takeIf { it.isNotBlank() }
            ?: error("光鸭扫码登录缺少二维码内容")
        GuangyaQrSession(
            deviceCode = deviceCode,
            userCode = json.optString("user_code").takeIf { it.isNotBlank() },
            qrPayload = qrPayload
        )
    }

    /**
     * 第二步：轮询令牌。每 5 秒一次；slow_down 时调用方需拉长间隔。
     * 返回密封结果：Success(tokenPair) / Pending / SlowDown / Expired。
     */
    suspend fun qrPoll(deviceCode: String): GuangyaQrPollResult = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
            put("device_code", deviceCode)
            put("client_id", GuangyaConstants.CLIENT_ID)
        }
        try {
            val json = post(GuangyaConstants.ACCOUNT_BASE + GuangyaConstants.AUTH_TOKEN_PATH, body)
            val access = json.optString("access_token").takeIf { it.isNotBlank() }
            if (access != null) {
                GuangyaQrPollResult.Success(
                    GuangyaTokenPair(
                        accessToken = access,
                        refreshToken = json.optString("refresh_token").takeIf { it.isNotBlank() } ?: "",
                        obtainedAt = nowSeconds(),
                        expiresInSeconds = json.optLong("expires_in", 0)
                    )
                )
            } else {
                // 无 access_token 且响应正常，视为等待确认
                GuangyaQrPollResult.Pending
            }
        } catch (e: Exception) {
            val msg = e.message.orEmpty()
            when {
                msg.contains("slow_down") ->
                    GuangyaQrPollResult.SlowDown
                msg.contains("expired_token") || msg.contains("expired") ||
                    (e is GuangyaTokenExpiredException && !msg.contains("authorization_pending")) ->
                    GuangyaQrPollResult.Expired
                msg.contains("authorization_pending") || msg.contains("access_denied") ->
                    GuangyaQrPollResult.Pending
                else -> GuangyaQrPollResult.Pending
            }
        }
    }

    private fun post(
        url: String,
        body: JSONObject,
        extraHeaders: Map<String, String> = emptyMap()
    ): JSONObject {
        val headers = accountHeaders + extraHeaders
        val req = Request.Builder().url(url)
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .post(body.toString().toRequestBody(GuangyaConstants.MEDIA_JSON))
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                val raw = resp.body?.string().orEmpty()
                val detail = runCatching { JSONObject(raw) }.getOrNull()?.let { json ->
                    json.optString("msg").takeIf { it.isNotBlank() }
                        ?: json.optString("message").takeIf { it.isNotBlank() }
                        ?: json.optString("error_description").takeIf { it.isNotBlank() }
                        ?: json.optString("error").takeIf { it.isNotBlank() }
                } ?: raw.take(160)
                val suffix = detail.takeIf { it.isNotBlank() }?.let { " $it" } ?: ""
                // 需求 12 修复 1：400/401/403 → 令牌/凭据失效，其余（429/5xx）→ 网络/服务端异常
                when {
                    resp.code == 400 || resp.code == 401 || resp.code == 403 ->
                        throw GuangyaTokenExpiredException("光鸭请求失败：HTTP ${resp.code}$suffix")
                    resp.code == 429 || resp.code >= 500 ->
                        throw java.io.IOException("光鸭服务暂不可用：HTTP ${resp.code}$suffix")
                    else ->
                        throw java.io.IOException("光鸭请求失败：HTTP ${resp.code}$suffix")
                }
            }
            return JSONObject(resp.body?.string().orEmpty())
        }
    }

    /**
     * 手机号归一化为光鸭 E164 格式（对齐 AList normalizePhoneE164）：
     * - "+86138xxxx" → "+86 138xxxx"（带空格，与浏览器请求一致）
     * - "138xxxx"（11 位大陆号）→ "+86 138xxxx"
     * - 其他格式原样返回
     */
    private fun normalizePhoneE164(phone: String): String {
        val p = phone.trim()
        if (p.isEmpty()) return ""
        val noSpace = p.replace(" ", "")
        if (noSpace.startsWith("+")) {
            if (noSpace.startsWith("+86") && noSpace.length > 3) {
                return "+86 " + noSpace.removePrefix("+86")
            }
            return noSpace
        }
        val digits = noSpace.filter { it.isDigit() }
        return if (digits.length == 11) "+86 $digits" else noSpace
    }

    private fun nowSeconds(): Long = System.currentTimeMillis() / 1000L
}
