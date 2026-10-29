package com.example.localmovielibrarz.cloudguangya

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 目标文件夹引用（离线下载保存位置）。 */
data class GuangyaFolderRef(
    val fileId: String,
    val name: String,
    val parentPath: List<GuangyaFolderRef> = emptyList() // 审查 P3：从根到父的路径链，导航时记录并持久化，支持「返回上级」
)

/**
 * 光鸭离线下载记忆系统（移植 guangya-txt-cloud-add 油猴脚本记忆模块）。
 *
 * 内存缓存 + 3 秒批量落盘（避免每次变更都序列化整个 map）：
 * - submitted：已成功提交的磁力链（hash -> ts），用于去重与断点续跑
 * - failed：永久失败黑名单（hash -> reason），下次不再提交
 * - 每日成功计数 + 每日限额（默认 1000，可配置 1..100000，失败不占名额）
 * - 历史累计提交数、目标文件夹
 * - 导出 / 导入 JSON 备份、清空
 *
 * 线程安全：内部全部 synchronized(lock)；序列化/写盘发生在本类协程（IO）与调用方（离线提交引擎也在 IO 线程）。
 */
class GuangyaOfflineMemory(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val lock = Any()
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val submittedMap: MutableMap<String, Long> = readLongMap(KEY_SUBMITTED)
    private val failedMap: MutableMap<String, String> = readJsonMap(KEY_FAILED)
    private var flushJob: Job? = null

    // ---------------- 已提交记忆（去重 / 断点续跑） ----------------

    fun isSubmitted(hash: String): Boolean = synchronized(lock) { submittedMap.containsKey(hash) }

    fun submittedCount(): Int = synchronized(lock) { submittedMap.size }

    fun markSubmitted(hash: String) {
        synchronized(lock) {
            submittedMap[hash] = System.currentTimeMillis()
            scheduleFlushLocked()
        }
    }

    // ---------------- 永久失败黑名单 ----------------

    /** 临时失败特征（网络 / 超时 / 限频）：不记黑名单，可重试（移植脚本 TEMP_FAIL_RE）。 */
    private val tempFailRe = Regex(
        "网络|timeout|超时|429|限频|速率|rate|timed out|ETIMEDOUT|ECONN|socket|proxy|连接|中断|暂时|稍后|太频繁",
        RegexOption.IGNORE_CASE
    )

    /** 今日额度超限（服务端硬限额）：应立即停止（移植脚本 QUOTA_FAIL_RE）。 */
    private val quotaFailRe = Regex(
        "超限|次数已用|次数不足|额度|quota|limit|每日|自然日|明天|已达上限",
        RegexOption.IGNORE_CASE
    )

    fun isTempFailure(msg: String): Boolean = tempFailRe.containsMatchIn(msg)

    fun isQuotaExceeded(msg: String): Boolean = quotaFailRe.containsMatchIn(msg)

    fun isFailed(hash: String): Boolean = synchronized(lock) { failedMap.containsKey(hash) }

    fun failedCount(): Int = synchronized(lock) { failedMap.size }

    fun markFailed(hash: String, reason: String) {
        synchronized(lock) {
            failedMap[hash] = reason.take(200)
            scheduleFlushLocked()
        }
    }

    // ---------------- 每日限额 ----------------

    fun todayStr(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    fun getTodayCount(): Int = prefs.getInt(KEY_TODAY_PREFIX + todayStr(), 0)

    fun getDailyLimit(): Int {
        val v = prefs.getInt(KEY_DAILY_LIMIT, DEFAULT_DAILY_LIMIT)
        return if (v in 1..MAX_DAILY_LIMIT) v else DEFAULT_DAILY_LIMIT
    }

    fun setDailyLimit(n: Int) {
        if (n in 1..MAX_DAILY_LIMIT) prefs.edit().putInt(KEY_DAILY_LIMIT, n).apply()
    }

    fun todayRemaining(): Int = (getDailyLimit() - getTodayCount()).coerceAtLeast(0)

    fun todayLimitReached(): Boolean = getTodayCount() >= getDailyLimit()

    /** 成功提交时调用：今日计数 +1，历史累计 +1（失败不占名额）。 */
    fun bumpSuccessCounters() {
        val day = todayStr()
        prefs.edit()
            .putInt(KEY_TODAY_PREFIX + day, getTodayCount() + 1)
            .putInt(KEY_TOTAL, getTotalSubmitted() + 1)
            .apply()
    }

    fun getTotalSubmitted(): Int = prefs.getInt(KEY_TOTAL, 0)

    // ---------------- 目标文件夹 ----------------

    private fun folderRefToJson(ref: GuangyaFolderRef): JSONObject =
        JSONObject().apply {
            put("fileId", ref.fileId)
            put("name", ref.name)
            val path = JSONArray()
            ref.parentPath.forEach { path.put(folderRefToJson(it)) }
            put("parentPath", path)
        }

    private fun folderRefFromJson(o: JSONObject?): GuangyaFolderRef? {
        o ?: return null
        val fileId = o.optString("fileId", "")
        if (fileId.isBlank()) return null
        val pathArr = o.optJSONArray("parentPath")
        val parentPath = buildList {
            if (pathArr != null) {
                for (i in 0 until pathArr.length()) {
                    folderRefFromJson(pathArr.optJSONObject(i))?.let { add(it) }
                }
            }
        }
        return GuangyaFolderRef(fileId = fileId, name = o.optString("name", ""), parentPath = parentPath)
    }

    fun targetFolder(): GuangyaFolderRef? = synchronized(lock) {
        val raw = prefs.getString(KEY_TARGET_FOLDER, null) ?: return null
        runCatching { folderRefFromJson(JSONObject(raw)) }.getOrNull()
    }

    fun setTargetFolder(folder: GuangyaFolderRef?) {
        synchronized(lock) {
            val editor = prefs.edit()
            if (folder == null || folder.fileId.isBlank()) {
                editor.remove(KEY_TARGET_FOLDER)
            } else {
                editor.putString(
                    KEY_TARGET_FOLDER,
                    folderRefToJson(folder).toString()
                )
            }
            editor.apply()
        }
    }

    // ---------------- 导出 / 导入 / 清空 ----------------

    /** 导出记忆为 JSON 备份（含已提交 / 黑名单 / 每日计数 / 累计 / 限额 / 目标文件夹）。 */
    fun exportJson(): String {
        flushNow()
        return synchronized(lock) {
            val submitted = JSONObject()
            submittedMap.forEach { (k, v) -> submitted.put(k, v) }
            val failed = JSONObject()
            failedMap.forEach { (k, v) -> failed.put(k, v) }
            val root = JSONObject()
                .put("version", 1)
                .put("exportedAt", System.currentTimeMillis())
                .put("submitted", submitted)
                .put("failed", failed)
                .put("todayCount", getTodayCount())
                .put("today", todayStr())
                .put("total", getTotalSubmitted())
                .put("dailyLimit", getDailyLimit())
            targetFolder()?.let { root.put("targetFolder", folderRefToJson(it)) }
            root.toString()
        }
    }

    /** 导入记忆备份，返回恢复的已提交磁力链数量。 */
    fun importJson(json: String): Int {
        val root = JSONObject(json)
        val submitted = root.optJSONObject("submitted") ?: JSONObject()
        val failed = root.optJSONObject("failed") ?: JSONObject()
        synchronized(lock) {
            submittedMap.clear()
            submitted.keys().forEach { key -> submittedMap[key] = submitted.optLong(key) }
            failedMap.clear()
            failed.keys().forEach { key -> failedMap[key] = failed.optString(key) }
            persistSubmittedLocked()
            persistFailedLocked()
        }
        val day = root.optString("today", todayStr())
        if (root.has("todayCount")) prefs.edit().putInt(KEY_TODAY_PREFIX + day, root.optInt("todayCount", 0)).apply()
        if (root.has("total")) prefs.edit().putInt(KEY_TOTAL, root.optInt("total", 0)).apply()
        val limit = root.optInt("dailyLimit", -1)
        if (limit in 1..MAX_DAILY_LIMIT) prefs.edit().putInt(KEY_DAILY_LIMIT, limit).apply()
        folderRefFromJson(root.optJSONObject("targetFolder"))?.let { setTargetFolder(it) }
        return submittedMap.size
    }

    /** 清空记忆数据（已提交 / 黑名单 / 今日计数 / 累计 / 目标文件夹），保留每日限额配置。 */
    fun clearAll() {
        synchronized(lock) {
            submittedMap.clear()
            failedMap.clear()
            val editor = prefs.edit()
            prefs.all.keys
                .filter { it.startsWith(KEY_TODAY_PREFIX) || it == KEY_SUBMITTED || it == KEY_FAILED || it == KEY_TOTAL || it == KEY_TARGET_FOLDER }
                .forEach { editor.remove(it) }
            editor.apply()
        }
    }

    /** 立即把内存写盘（离线批处理结束时调用，保证断点续跑）。 */
    fun flushNow() {
        synchronized(lock) {
            flushJob?.cancel()
            flushJob = null
            persistSubmittedLocked()
            persistFailedLocked()
        }
    }

    /**
     * 释放内部协程作用域（审查 I9：ioScope 此前从未取消，持有 applicationContext 引用，
     * 若实例长期存活会导致进程内协程泄漏）。宿主（Application / DI 容器）销毁时务必调用。
     */
    fun dispose() {
        ioScope.cancel()
    }

    // ---------------- 内部实现 ----------------

    private fun scheduleFlushLocked() {
        flushJob?.cancel()
        flushJob = ioScope.launch {
            delay(FLUSH_DELAY_MS)
            flushNow()
        }
    }

    private fun persistSubmittedLocked() {
        val obj = JSONObject()
        submittedMap.forEach { (k, v) -> obj.put(k, v) }
        prefs.edit().putString(KEY_SUBMITTED, obj.toString()).apply()
    }

    private fun persistFailedLocked() {
        val obj = JSONObject()
        failedMap.forEach { (k, v) -> obj.put(k, v) }
        prefs.edit().putString(KEY_FAILED, obj.toString()).apply()
    }

    private fun readJsonMap(key: String): MutableMap<String, String> {
        val raw = prefs.getString(key, null) ?: return mutableMapOf()
        return runCatching {
            val obj = JSONObject(raw)
            obj.keys().asSequence().associateWith { obj.optString(it) }.toMutableMap()
        }.getOrElse {
            Log.w(TAG, "读取记忆 $key 失败，重置为空：${it.message}")
            mutableMapOf()
        }
    }

    private fun readLongMap(key: String): MutableMap<String, Long> {
        val raw = prefs.getString(key, null) ?: return mutableMapOf()
        return runCatching {
            val obj = JSONObject(raw)
            obj.keys().asSequence().associateWith { obj.optLong(it) }.toMutableMap()
        }.getOrElse {
            Log.w(TAG, "读取记忆 $key 失败，重置为空：${it.message}")
            mutableMapOf()
        }
    }

    private companion object {
        const val TAG = "GuangyaOfflineMemory"
        const val PREFS_NAME = "guangya_offline_memory"
        const val KEY_SUBMITTED = "submitted"
        const val KEY_FAILED = "failed"
        const val KEY_TODAY_PREFIX = "today_"
        const val KEY_TOTAL = "total"
        const val KEY_DAILY_LIMIT = "daily_limit"
        const val KEY_TARGET_FOLDER = "target_folder"
        const val DEFAULT_DAILY_LIMIT = 1000
        const val MAX_DAILY_LIMIT = 100000
        const val FLUSH_DELAY_MS = 3000L
    }
}
