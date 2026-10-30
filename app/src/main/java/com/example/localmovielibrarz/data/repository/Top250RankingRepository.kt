package com.example.localmovielibrarz.data.repository

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 单条榜单名次。
 *
 * [year] 为 `null` 表示命中总榜（`all.json`），非空表示命中该年度榜。
 * 详情页按「总榜在前、其后 2026 降序到 2008」渲染多枚徽章。
 */
data class Top250Rank(
    val ranking: Int,
    val label: String,
    val year: Int?
)

/**
 * 卡片角标用的最简名次。**没有 label** —— 卡片文案由 UI 现场拼接，
 * 因为总榜与年度的句式不同（"总榜 TOP250 第N名" / "2026 年度 TOP250 第N名"）。
 */
data class Top250CardRank(
    val ranking: Int,
    val year: Int?
)

/**
 * 缓存状态。`revision` 是唯一的全局失效信号：每次落盘或重算都会 +1，
 * 卡片角标的 produceState 把它当 key，因此任一份榜单变化都会让可见角标重查。
 */
data class Top250CacheStatus(
    val allCached: Boolean = false,
    val cachedYears: List<Int> = emptyList(),
    val initialAttempted: Boolean = false,
    val isLoading: Boolean = false,
    val completed: Int = 0,
    val total: Int = 0,
    val revision: Int = 0
)

/**
 * 一轮刷新的结果。[failed] 非空时设置页显示失败份数；
 * [initial] 区分「首次全量 20 份」与「日常只更新 2 份」。
 */
data class Top250RefreshResult(
    val updated: List<String>,
    val failed: List<String>,
    val initial: Boolean
)

/**
 * JavDB TOP 榜单仓储（移植自 5.0.5 `Top250RankingRepository`）。
 *
 * ## 与抓削链路无关
 * TOP250 的数据来自自建 CDN 上**预生成**的静态 JSON，不来自 [com.example.localmovielibrarz.scraper.JavdbScraper]。
 * 两条链路唯一的共同点是都被叫做 "javdb"，内部没有共享代码。
 *
 * ## 端点与文件
 * 路径 = [BASE_URL] + name + ".json"，name 共 20 个：`all` 与 `year-2008` … `year-2026`。
 * 没有 query、没有自定义 header。
 *
 * ## 为什么不预热
 * 全应用只有设置页按钮会触发网络请求，启动不拉。榜单一天一更，
 * 冷启动联网既拖慢首屏又白耗流量。
 */
class Top250RankingRepository(
    private val context: Context,
    private val client: OkHttpClient = defaultClient(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val operationMutex = Mutex()

    private val _status = MutableStateFlow(Top250CacheStatus())

    /** 缓存状态；设置页、卡片角标、榜单页都订阅它。 */
    val status: StateFlow<Top250CacheStatus> = _status.asStateFlow()

    /** 只按磁盘现有文件重算状态，不联网。进入设置页时调用。 */
    suspend fun readStatus(): Top250CacheStatus = withContext(ioDispatcher) {
        publishStatus(isLoading = _status.value.isLoading, completed = _status.value.completed, total = _status.value.total)
    }

    /**
     * 刷新榜单。首次（无 marker）拉全量 20 份，之后只拉 `all` 与 `year-2026`。
     * 单份失败不会中断整轮，只记入 [Top250RefreshResult.failed]。
     */
    suspend fun fetchOnDemand(): Top250RefreshResult = withContext(ioDispatcher) {
        operationMutex.withLock {
            val dir = directory()
            dir.mkdirs()
            val marker = File(dir, MARKER_NAME)
            val result = if (!marker.isFile) {
                fetchInitial(dir)
            } else {
                fetchLatest(dir)
            }
            publishStatus(isLoading = false, completed = 0, total = 0)
            result
        }
    }

    /**
     * 卡片角标查询：先总榜，再 2026 降序到 2008，返回**首个**命中。
     *
     * 顺序是刻意的 —— 同时上总榜与年度榜的影片，卡片上只显示总榜名次
     * （总榜含金量更高，且卡片宽度只够一个角标）。详情页才展示全部。
     */
    suspend fun cardRankFor(number: String): Top250CardRank? = withContext(ioDispatcher) {
        val key = normalizeNumber(number)
        if (key.isEmpty()) return@withContext null
        val dir = directory()
        val all = readRankings(dir, "all")
        val allRanking = all?.get(key)
        if (allRanking != null) return@withContext Top250CardRank(allRanking, null)
        for (year in YEAR_RANGE.reversed()) {
            val yearly = readRankings(dir, "year-$year")
            val ranking = yearly?.get(key)
            if (ranking != null) return@withContext Top250CardRank(ranking, year)
        }
        null
    }

    /**
     * 详情页查询：返回**全部**命中，总榜在前、其后 2026 降序到 2008，最多 20 条。
     */
    suspend fun ranksFor(number: String): List<Top250Rank> = withContext(ioDispatcher) {
        val key = normalizeNumber(number)
        if (key.isEmpty()) return@withContext emptyList()
        val dir = directory()
        val ranks = ArrayList<Top250Rank>(YEAR_RANGE.count() + 1)
        readRankings(dir, "all")?.get(key)?.let { ranks += Top250Rank(it, "总榜 TOP250", null) }
        for (year in YEAR_RANGE.reversed()) {
            readRankings(dir, "year-$year")?.get(key)?.let { ranks += Top250Rank(it, "$year 年度 TOP250", year) }
        }
        ranks
    }

    /**
     * 榜单页用：读出某一榜的完整名次表（归一化番号 → 名次）。
     * 年份越界或文件缺失一律返回空表，由调用方出空态。
     */
    suspend fun rankingsForList(year: Int?): Map<String, Int> = withContext(ioDispatcher) {
        if (year != null && year !in YEAR_RANGE) return@withContext emptyMap()
        val name = year?.let { "year-$it" } ?: "all"
        readRankings(directory(), name) ?: emptyMap()
    }

    // ---- 内部实现 ----

    private fun directory(): File = File(context.filesDir, DIRECTORY_NAME)

    private fun fetchInitial(dir: File): Top250RefreshResult {
        val names = listOf("all", "year-2026") + YEAR_RANGE.filter { it < 2026 }.map { "year-$it" }
        val updated = ArrayList<String>(names.size)
        val failed = ArrayList<String>()
        publishStatus(isLoading = true, completed = 0, total = names.size)
        names.forEachIndexed { index, name ->
            // 首次流程允许断点续拉：已落盘的文件跳过下载但仍计入 updated。
            val target = File(dir, "$name.json")
            if (target.isFile) {
                updated += name
            } else {
                try {
                    fetchAndSave(dir, name)
                    updated += name
                } catch (error: Exception) {
                    failed += name
                }
            }
            publishStatus(isLoading = true, completed = index + 1, total = names.size)
        }
        writeMarker(dir)
        return Top250RefreshResult(updated = updated, failed = failed, initial = true)
    }

    private fun fetchLatest(dir: File): Top250RefreshResult {
        val names = listOf("all", "year-2026")
        val updated = ArrayList<String>(names.size)
        val failed = ArrayList<String>()
        publishStatus(isLoading = true, completed = 0, total = names.size)
        names.forEachIndexed { index, name ->
            try {
                fetchAndSave(dir, name)
                updated += name
            } catch (error: Exception) {
                failed += name
            }
            publishStatus(isLoading = true, completed = index + 1, total = names.size)
        }
        return Top250RefreshResult(updated = updated, failed = failed, initial = false)
    }

    private fun fetchAndSave(dir: File, name: String): Map<String, Int> {
        val request = Request.Builder().url(BASE_URL + name + ".json").build()
        val text = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("TOP250 HTTP " + response.code)
            val body = response.body ?: throw IllegalStateException("TOP250 response is empty")
            body.string().ifEmpty { throw IllegalStateException("TOP250 response is empty") }
        }
        val parsed = parseRankings(text)
        if (parsed.isEmpty()) throw IllegalStateException("TOP250 list is empty")
        // AtomicFile：先写临时文件再 rename，保证磁盘上要么没有、要么是完整内容，
        // 中途断电不会留下半份 JSON 让下次解析炸掉。
        val atomic = AtomicFile(File(dir, "$name.json"))
        var stream: FileOutputStream? = null
        try {
            stream = atomic.startWrite()
            stream.write(text.toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (error: IOException) {
            stream?.let { atomic.failWrite(it) }
            throw error
        }
        synchronized(parsedCache) { parsedCache[name] = parsed }
        return parsed
    }

    private fun writeMarker(dir: File) {
        runCatching { File(dir, MARKER_NAME).writeText("1", Charsets.UTF_8) }
    }

    private fun readRankings(dir: File, name: String): Map<String, Int>? {
        synchronized(parsedCache) { parsedCache[name]?.let { return it } }
        val file = File(dir, "$name.json")
        if (!file.isFile) return null
        val parsed = try {
            parseRankings(file.readText(Charsets.UTF_8))
        } catch (error: Throwable) {
            // 解析失败不写缓存：文件可能正在被 AtomicFile 替换，下次读还能自愈。
            return null
        }
        synchronized(parsedCache) { parsedCache[name] = parsed }
        return parsed
    }

    private fun publishStatus(isLoading: Boolean, completed: Int, total: Int): Top250CacheStatus {
        val dir = directory()
        val next = Top250CacheStatus(
            allCached = File(dir, "all.json").isFile,
            cachedYears = YEAR_RANGE.filter { File(dir, "year-$it.json").isFile },
            initialAttempted = File(dir, MARKER_NAME).isFile,
            isLoading = isLoading,
            completed = completed,
            total = total,
            revision = _status.value.revision + 1
        )
        _status.value = next
        return next
    }

    companion object {
        /** 5.0.5 实际使用的 CDN 目录，末尾必须带斜杠。 */
        const val BASE_URL = "https://catembylegacy.fastcdn.dpdns.org/top250/"

        private const val DIRECTORY_NAME = "top250"
        private const val MARKER_NAME = "initial-attempt-complete"

        private val YEAR_RANGE = 2008..2026

        /** 进程内解析缓存：磁盘权威，内存只是省掉重复解析。 */
        private val parsedCache = LinkedHashMap<String, Map<String, Int>>()

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .build()

        /**
         * 番号归一：按 codePoint 保留字母/数字（含非 ASCII），再 `toUpperCase(Locale.ROOT)`。
         *
         * 用 ROOT 而不是默认 Locale：土耳其语环境下 "i" 会变成 "İ"，与 CDN 里的键永远对不上。
         */
        fun normalizeNumber(value: String): String {
            val builder = StringBuilder(value.length)
            var index = 0
            while (index < value.length) {
                val codePoint = value.codePointAt(index)
                if (Character.isLetterOrDigit(codePoint)) builder.appendCodePoint(codePoint)
                index += Character.charCount(codePoint)
            }
            return builder.toString().uppercase(Locale.ROOT)
        }

        /**
         * 解析一份榜单。只读 `movies[].number` 与 `movies[].ranking`，
         * 其余字段（标题、封面、链接）服务端可能给了但客户端从不使用。
         *
         * 名次只接受 1..250；同番号多条时 `putIfAbsent` 保留首次出现者。
         */
        fun parseRankings(text: String): Map<String, Int> {
            val result = LinkedHashMap<String, Int>()
            val movies = JSONObject(text).getJSONArray("movies")
            for (index in 0 until movies.length()) {
                val entry = movies.optJSONObject(index) ?: continue
                val number = entry.optString("number")
                val ranking = entry.optInt("ranking")
                val key = normalizeNumber(number)
                if (key.isEmpty()) continue
                if (ranking < 1 || ranking > 250) continue
                result.putIfAbsent(key, ranking)
            }
            return result
        }
    }
}
