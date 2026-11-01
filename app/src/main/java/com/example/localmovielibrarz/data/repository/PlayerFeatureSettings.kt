package com.example.localmovielibrarz.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.example.localmovielibrarz.cloud115.Cloud115CookieProvider
import com.example.localmovielibrarz.playback.engine.DebandSettings
import com.example.localmovielibrarz.playback.engine.HdrToneMode
import com.example.localmovielibrarz.playback.engine.MpvEnhancementOptions
import com.example.localmovielibrarz.playback.engine.MpvUserOptions
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * 播放器增强功能设置（v2，承载从 mpvRx 能力清单接入的各项开关）。
 *
 * ## 为什么不继续塞进 AppSettingsRepository
 *
 * `AppSettingsRepository` 已有 1600+ 行、横跨网盘 / 刮削 / 字幕 / 播放器 / 更新五个领域，
 * 是典型的「上帝对象」：任何改动都要在一个巨型文件里找位置，测试也无从下手。
 * 新增的播放器增强项全部收在本类，与旧类共用同一个 SharedPreferences 文件（不需要迁移），
 * 键名统一加 `player_fx_` 前缀，避免撞名。
 *
 * 所有默认值都等价于「不改变 v1 行为」。
 */
class PlayerFeatureSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(Cloud115CookieProvider.PREFS_NAME, Context.MODE_PRIVATE)

    fun snapshot(): PlayerFeatures = PlayerFeatures(
        hdrMode = HdrToneMode.fromId(prefs.getString(KEY_HDR_MODE, null)),
        debandEnabled = prefs.getBoolean(KEY_DEBAND, false),
        debandStrength = prefs.getInt(KEY_DEBAND_STRENGTH, DEFAULT_DEBAND_STRENGTH).coerceIn(1, 3),
        powerSaveRender = prefs.getBoolean(KEY_POWER_SAVE, false),
        anime4kGuards = prefs.getBoolean(KEY_ANIME4K_GUARDS, true),
        volumeBoostPercent = prefs.getInt(KEY_VOLUME_BOOST, 0).coerceIn(0, MAX_VOLUME_BOOST),
        customMpvOptions = prefs.getString(KEY_CUSTOM_MPV, "").orEmpty(),
        doubleTapSeek = prefs.getBoolean(KEY_DOUBLE_TAP, true),
        longPressSpeed = prefs.getFloat(KEY_LONG_PRESS_SPEED, DEFAULT_LONG_PRESS_SPEED).coerceIn(1.25f, 4f),
        longPressEnabled = prefs.getBoolean(KEY_LONG_PRESS, true),
        pinchZoom = prefs.getBoolean(KEY_PINCH_ZOOM, true),
        matchRefreshRate = prefs.getBoolean(KEY_MATCH_REFRESH, false),
        rememberBrightness = prefs.getBoolean(KEY_REMEMBER_BRIGHTNESS, false),
        savedBrightness = prefs.getFloat(KEY_SAVED_BRIGHTNESS, -1f),
        statsOverlay = prefs.getBoolean(KEY_STATS_OVERLAY, false)
    )

    fun update(transform: (PlayerFeatures) -> PlayerFeatures) {
        val next = transform(snapshot())
        prefs.edit()
            .putString(KEY_HDR_MODE, next.hdrMode.id)
            .putBoolean(KEY_DEBAND, next.debandEnabled)
            .putInt(KEY_DEBAND_STRENGTH, next.debandStrength.coerceIn(1, 3))
            .putBoolean(KEY_POWER_SAVE, next.powerSaveRender)
            .putBoolean(KEY_ANIME4K_GUARDS, next.anime4kGuards)
            .putInt(KEY_VOLUME_BOOST, next.volumeBoostPercent.coerceIn(0, MAX_VOLUME_BOOST))
            .putString(KEY_CUSTOM_MPV, next.customMpvOptions)
            .putBoolean(KEY_DOUBLE_TAP, next.doubleTapSeek)
            .putFloat(KEY_LONG_PRESS_SPEED, next.longPressSpeed.coerceIn(1.25f, 4f))
            .putBoolean(KEY_LONG_PRESS, next.longPressEnabled)
            .putBoolean(KEY_PINCH_ZOOM, next.pinchZoom)
            .putBoolean(KEY_MATCH_REFRESH, next.matchRefreshRate)
            .putBoolean(KEY_REMEMBER_BRIGHTNESS, next.rememberBrightness)
            .putFloat(KEY_SAVED_BRIGHTNESS, next.savedBrightness)
            .putBoolean(KEY_STATS_OVERLAY, next.statsOverlay)
            .apply()
    }

    fun saveBrightness(value: Float) {
        prefs.edit().putFloat(KEY_SAVED_BRIGHTNESS, value).apply()
    }

    fun observe(): Flow<PlayerFeatures> = callbackFlow {
        trySend(snapshot())
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key != null && key.startsWith(PREFIX)) trySend(snapshot())
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    companion object {
        private const val PREFIX = "player_fx_"
        const val KEY_HDR_MODE = "${PREFIX}hdr_mode"
        const val KEY_DEBAND = "${PREFIX}deband"
        const val KEY_DEBAND_STRENGTH = "${PREFIX}deband_strength"
        const val KEY_POWER_SAVE = "${PREFIX}power_save_render"
        const val KEY_ANIME4K_GUARDS = "${PREFIX}anime4k_guards"
        const val KEY_VOLUME_BOOST = "${PREFIX}volume_boost"
        const val KEY_CUSTOM_MPV = "${PREFIX}custom_mpv"
        const val KEY_DOUBLE_TAP = "${PREFIX}double_tap_seek"
        const val KEY_LONG_PRESS_SPEED = "${PREFIX}long_press_speed"
        const val KEY_LONG_PRESS = "${PREFIX}long_press"
        const val KEY_PINCH_ZOOM = "${PREFIX}pinch_zoom"
        const val KEY_MATCH_REFRESH = "${PREFIX}match_refresh"
        const val KEY_REMEMBER_BRIGHTNESS = "${PREFIX}remember_brightness"
        const val KEY_SAVED_BRIGHTNESS = "${PREFIX}saved_brightness"
        const val KEY_STATS_OVERLAY = "${PREFIX}stats_overlay"

        const val DEFAULT_DEBAND_STRENGTH = 2
        const val DEFAULT_LONG_PRESS_SPEED = 2f
        const val MAX_VOLUME_BOOST = 100
    }
}

/**
 * 播放器增强设置快照（不可变值，便于 StateFlow 广播与 Compose 比较）。
 */
data class PlayerFeatures(
    val hdrMode: HdrToneMode = HdrToneMode.OFF,
    val debandEnabled: Boolean = false,
    /** 1 = 轻、2 = 标准、3 = 强（映射到 deband 迭代/阈值）。 */
    val debandStrength: Int = PlayerFeatureSettings.DEFAULT_DEBAND_STRENGTH,
    val powerSaveRender: Boolean = false,
    val anime4kGuards: Boolean = true,
    val volumeBoostPercent: Int = 0,
    val customMpvOptions: String = "",
    val doubleTapSeek: Boolean = true,
    val longPressSpeed: Float = PlayerFeatureSettings.DEFAULT_LONG_PRESS_SPEED,
    val longPressEnabled: Boolean = true,
    val pinchZoom: Boolean = true,
    val matchRefreshRate: Boolean = false,
    val rememberBrightness: Boolean = false,
    val savedBrightness: Float = -1f,
    val statsOverlay: Boolean = false
) {
    val deband: DebandSettings
        get() = when (debandStrength) {
            1 -> DebandSettings(enabled = debandEnabled, iterations = 1, threshold = 32, range = 16, grain = 16)
            3 -> DebandSettings(enabled = debandEnabled, iterations = 3, threshold = 64, range = 24, grain = 48)
            else -> DebandSettings(enabled = debandEnabled)
        }

    /** 解析用户 mpv 选项（含被拦下的受保护键，供设置页提示）。 */
    val parsedCustomOptions: MpvUserOptions.Parsed
        get() = MpvUserOptions.parse(customMpvOptions)

    /** 翻译成引擎 init 期选项。 */
    fun toMpvEnhancements(): MpvEnhancementOptions = MpvEnhancementOptions(
        hdrMode = hdrMode,
        deband = deband,
        powerSaveRender = powerSaveRender,
        // 上限固定放开到 200%：播放中随时可以拖音量增强滑杆，而 volume-max 只能在 init 前设置。
        // 默认音量仍是 100，不开增强时听感与 v1 完全相同。
        volumeMaxPercent = 100 + PlayerFeatureSettings.MAX_VOLUME_BOOST,
        userOverrides = parsedCustomOptions.options,
        anime4kGuards = anime4kGuards
    )
}
