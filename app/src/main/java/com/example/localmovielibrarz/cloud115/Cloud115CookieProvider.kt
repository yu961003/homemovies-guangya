package com.example.localmovielibrarz.cloud115

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File

class Cloud115CookieProvider(private val context: Context) {
    fun loadCookies(): String? {
        // 优先读加密存储（安全审查 2.1：凭据统一迁移到 EncryptedSharedPreferences）
        val secure = securePrefs().getString(KEY_COOKIES, null)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        if (secure != null) return secure

        // 兼容迁移：旧明文残留 → 一次性搬入加密存储并清空
        val legacy = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_COOKIES, null)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        if (legacy != null) {
            securePrefs().edit().putString(KEY_COOKIES, legacy).commit()
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().remove(KEY_COOKIES).commit()
            return legacy
        }

        findSavedCookieFile()?.readText(Charsets.UTF_8)?.trim()?.takeIf { it.isNotBlank() }?.let { return it }

        findAssetCookieFile()?.let { assetName ->
            return runCatching {
                context.assets.open(assetName).bufferedReader().use { it.readText() }
            }.getOrNull()?.trim()?.takeIf { it.isNotBlank() }
        }

        return null
    }

    private fun securePrefs(): SharedPreferences = EncryptedSharedPreferences.create(
        context.applicationContext,
        SECURE_PREFS_NAME,
        MasterKey.Builder(context.applicationContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    private fun findSavedCookieFile(): File? =
        File(context.filesDir, COOKIE_DIR)
            .listFiles { file ->
                file.isFile &&
                    file.name.startsWith(COOKIE_FILE_PREFIX) &&
                    file.name.endsWith(COOKIE_FILE_SUFFIX)
            }
            ?.sortedBy { it.name.lowercase() }
            ?.firstOrNull()

    private fun findAssetCookieFile(): String? {
        val files = context.assets.list("").orEmpty()
        return files
            .filter { it.startsWith(COOKIE_FILE_PREFIX) && it.endsWith(COOKIE_FILE_SUFFIX) }
            .sorted()
            .firstOrNull()
            ?: files.firstOrNull { it == LEGACY_ASSET_COOKIE_FILE }
    }

    companion object {
        const val PREFS_NAME = "cloud115"
        const val KEY_COOKIES = "cookies"
        const val COOKIE_DIR = "115cookies"
        const val COOKIE_FILE_PREFIX = "115cookie_"
        const val COOKIE_FILE_SUFFIX = ".txt"
        const val LEGACY_ASSET_COOKIE_FILE = "115-cookies.txt"

        /** 敏感凭据加密存储文件名（与 AppSettingsRepository 共享，备份规则会排除此文件）。 */
        const val SECURE_PREFS_NAME = "secure_settings"
    }
}
