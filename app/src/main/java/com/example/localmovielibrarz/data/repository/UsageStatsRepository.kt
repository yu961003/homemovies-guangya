package com.example.localmovielibrarz.data.repository

import android.content.Context
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class DailyUsageStats(val day: String, val appMillis: Long, val playbackMillis: Long)

class UsageStatsRepository(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("usage_stats", Context.MODE_PRIVATE)
    // 串行化读-改-写，防止首页 addAppMillis 与播放器 addPlaybackMillis 并发覆盖（安全审查修复）
    private val lock = Any()

    fun addAppMillis(millis: Long) = add("app", millis)
    fun addPlaybackMillis(millis: Long) = add("playback", millis)

    fun recent(days: Int = 7): List<DailyUsageStats> {
        val app = JSONObject(prefs.getString("app", "{}").orEmpty())
        val playback = JSONObject(prefs.getString("playback", "{}").orEmpty())
        return (0 until days).map { offset ->
            val day = dayKey(System.currentTimeMillis() - offset * DAY_MS)
            DailyUsageStats(day, app.optLong(day), playback.optLong(day))
        }.reversed()
    }

    fun all(): List<DailyUsageStats> {
        val app = JSONObject(prefs.getString("app", "{}").orEmpty())
        val playback = JSONObject(prefs.getString("playback", "{}").orEmpty())
        val keys = buildSet {
            app.keys().forEach { add(it) }
            playback.keys().forEach { add(it) }
        }.sorted()
        return keys.map { day -> DailyUsageStats(day, app.optLong(day), playback.optLong(day)) }
    }

    private fun add(key: String, millis: Long) = synchronized(lock) {
        // 整个读-改-写序列持锁；调用频率低（切后台/暂停时），锁竞争可忽略
        if (millis <= 0) return@synchronized
        val json = JSONObject(prefs.getString(key, "{}").orEmpty())
        val day = dayKey(System.currentTimeMillis())
        json.put(day, json.optLong(day) + millis)
        prefs.edit().putString(key, json.toString()).apply()
    }

    // 注意：MM-dd 格式使用默认 Locale 为既有行为，保持不动；如需跨 Locale 稳定可改 Locale.ROOT
    private fun dayKey(time: Long) = SimpleDateFormat("MM-dd", Locale.getDefault()).format(Date(time))
    private companion object { const val DAY_MS = 86_400_000L }
}
