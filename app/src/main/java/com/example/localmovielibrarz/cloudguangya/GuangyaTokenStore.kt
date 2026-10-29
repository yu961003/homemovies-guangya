package com.example.localmovielibrarz.cloudguangya

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * 光鸭令牌加密存储（P1-2）。
 *
 * 使用 EncryptedSharedPreferences + Android Keystore，令牌以明文绝不落盘。
 * 顺手修复了 115 明文存 Cookie 的旧账：光鸭令牌走加密通道。
 */
data class GuangyaTokenPair(
    val accessToken: String,
    val refreshToken: String,
    val obtainedAt: Long,
    val expiresInSeconds: Long = 0
)

class GuangyaTokenStore(context: Context) {
    private val masterKey = MasterKey.Builder(context.applicationContext)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context.applicationContext,
        "guangya_tokens",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun getAccessToken(): String? = prefs.getString(KEY_ACCESS, null)?.takeIf { it.isNotBlank() }
    fun getRefreshToken(): String? = prefs.getString(KEY_REFRESH, null)?.takeIf { it.isNotBlank() }

    /**
     * 设备标识（did）：首次生成后持久化，登录与业务请求共用同一个（对齐 guangya_flutter StorageKeys.deviceID）。
     * 修复二轮问题 D：此前每次启动重新生成 did，与登录时的 did 不一致，导致服务端身份校验拒绝文件操作请求。
     */
    fun getOrCreateDeviceId(generate: () -> String): String {
        prefs.getString(KEY_DEVICE_ID, null)?.takeIf { it.isNotBlank() }?.let { return it }
        val did = generate()
        prefs.edit().putString(KEY_DEVICE_ID, did).apply()
        return did
    }

    fun getDeviceId(): String? = prefs.getString(KEY_DEVICE_ID, null)?.takeIf { it.isNotBlank() }

    fun save(pair: GuangyaTokenPair) {
        prefs.edit().apply {
            putString(KEY_ACCESS, pair.accessToken)
            // 幂等（反编译确认：拿到新 refresh_token 后旧 token 仍可用），缺失则保留旧值
            pair.refreshToken.takeIf { it.isNotBlank() }?.let { putString(KEY_REFRESH, it) }
            // 审查 O1：记录绝对过期时刻（秒），供 Provider 主动刷新；无过期信息则不写
            if (pair.expiresInSeconds > 0) {
                putLong(KEY_ACCESS_EXPIRES_AT, pair.obtainedAt + pair.expiresInSeconds)
            }
            apply()
        }
    }

    fun getAccessTokenExpiresAt(): Long = prefs.getLong(KEY_ACCESS_EXPIRES_AT, 0L)

    /** 登出：清令牌但保留设备标识 did（设备级标识不随账号登出重置，避免与后续登录/业务请求分离）。 */
    fun clear() {
        val deviceId = getDeviceId()
        prefs.edit().clear().apply()
        deviceId?.takeIf { it.isNotBlank() }?.let {
            prefs.edit().putString(KEY_DEVICE_ID, it).apply()
        }
    }

    private companion object {
        const val KEY_ACCESS = "access_token"
        const val KEY_REFRESH = "refresh_token"
        const val KEY_ACCESS_EXPIRES_AT = "access_token_expires_at"
        const val KEY_DEVICE_ID = "device_id"            // 新增：持久化设备标识
    }
}
