package com.example.localmovielibrarz.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.example.localmovielibrarz.cloud115.Cloud115CookieProvider
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** 明暗模式。 */
enum class ThemeMode(val id: String, val label: String) {
    SYSTEM("system", "跟随系统"),
    LIGHT("light", "浅色"),
    DARK("dark", "深色");

    companion object {
        fun fromId(id: String?): ThemeMode? = entries.firstOrNull { it.id == id }
    }
}

/**
 * 主题色板。[CINEMA] 是品牌色（青蓝），沿用 v1 手调过对比度的配色；其余色板由种子色生成。
 * 参考 mpvRx「多套主题 + AMOLED」能力，色板与数值为本项目自定。
 */
enum class ThemePalette(val id: String, val label: String, val seedArgb: Long) {
    CINEMA("cinema", "影院青", 0xFF36C5F0),
    AMBER("amber", "琥珀金", 0xFFE7A84B),
    ROSE("rose", "绯红", 0xFFE5677D),
    FOREST("forest", "松石绿", 0xFF4FBF8F),
    VIOLET("violet", "暮光紫", 0xFF9C88F0),
    GRAPHITE("graphite", "石墨", 0xFF9AA4B2);

    companion object {
        fun fromId(id: String?): ThemePalette = entries.firstOrNull { it.id == id } ?: CINEMA
    }
}

/** 一次主题渲染所需的全部输入。 */
data class ThemeSpec(
    val mode: ThemeMode = ThemeMode.DARK,
    val palette: ThemePalette = ThemePalette.CINEMA,
    val dynamicColor: Boolean = false,
    val amoled: Boolean = false
)

/**
 * 外观设置。
 *
 * ## 迁移
 *
 * v1 只有一个布尔 `light_theme_enabled`。首次读取时若没有新键，按旧值推出明暗模式
 * （旧用户升级后外观不变），写入时也同步回写旧键，降级到旧版本时同样保持一致。
 */
class ThemeSettingsRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(Cloud115CookieProvider.PREFS_NAME, Context.MODE_PRIVATE)

    fun snapshot(): ThemeSpec {
        val mode = ThemeMode.fromId(prefs.getString(KEY_MODE, null))
            ?: if (prefs.getBoolean(AppSettingsRepository.KEY_LIGHT_THEME_ENABLED, false)) ThemeMode.LIGHT else ThemeMode.DARK
        return ThemeSpec(
            mode = mode,
            palette = ThemePalette.fromId(prefs.getString(KEY_PALETTE, null)),
            dynamicColor = prefs.getBoolean(KEY_DYNAMIC, false),
            amoled = prefs.getBoolean(KEY_AMOLED, false)
        )
    }

    fun update(transform: (ThemeSpec) -> ThemeSpec) {
        val next = transform(snapshot())
        prefs.edit()
            .putString(KEY_MODE, next.mode.id)
            .putString(KEY_PALETTE, next.palette.id)
            .putBoolean(KEY_DYNAMIC, next.dynamicColor)
            .putBoolean(KEY_AMOLED, next.amoled)
            // 兼容旧键：仅在明确选择浅/深色时回写。
            .apply {
                when (next.mode) {
                    ThemeMode.LIGHT -> putBoolean(AppSettingsRepository.KEY_LIGHT_THEME_ENABLED, true)
                    ThemeMode.DARK -> putBoolean(AppSettingsRepository.KEY_LIGHT_THEME_ENABLED, false)
                    ThemeMode.SYSTEM -> Unit
                }
            }
            .apply()
    }

    fun observe(): Flow<ThemeSpec> = callbackFlow {
        trySend(snapshot())
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key != null && (key.startsWith(PREFIX) || key == AppSettingsRepository.KEY_LIGHT_THEME_ENABLED)) {
                trySend(snapshot())
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    companion object {
        private const val PREFIX = "theme_"
        const val KEY_MODE = "${PREFIX}mode"
        const val KEY_PALETTE = "${PREFIX}palette"
        const val KEY_DYNAMIC = "${PREFIX}dynamic"
        const val KEY_AMOLED = "${PREFIX}amoled"
    }
}
