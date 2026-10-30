package com.example.localmovielibrarz.data.repository

import android.content.Context
import android.os.SystemClock
import com.example.localmovielibrarz.data.local.MovieEntity
import com.example.localmovielibrarz.util.normalizeMovieNumber
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * JavDB TOP 榜单里的一条记录。
 *
 * [number] 存归一化后的番号（`ABP-123` 形态），与影片库共用
 * [normalizeMovieNumber]，所以榜单与本地实体能稳定对上。
 */
data class Top250Rank(
    val rank: Int,
    val number: String,
    val title: String? = null
)

/**
 * 榜单命中结果：卡片角标与详情页角标共用的显示模型。
 *
 * 为什么不直接把 [Top250Rank] 传进 Composable：卡片只需要排名数字，
 * 而排名数字决定角标配色（TOP3 用金色强调、其余用弱化色），
 * 拆出 [label] 后 UI 侧不必再判断排名区间。
 */
data class Top250CardRank(
    val rank: Int,
    val label: String
)

/**
 * 卡片角标尺寸（对应 5.0.5 的 `BADGE_ICON_LARGE / SMALL / NONE` 三档）。
 *
 * 放在 data 层而不是 ui 层：它是**持久化的用户设置**，读写方是
 * [AppSettingsRepository]，UI 只是消费方。放进 ui 层会让 data 反向依赖 ui。
 * [stored] 是落盘的字符串值，改名前后的旧值靠 [fromStored] 归一，
 * 未知值一律回落到 [Small]（宁可显示小角标，也不要因为脏数据整块消失）。
 */
enum class Top250BadgeSize(val stored: String) {
    Large("large"),
    Small("small"),
    None("none");

    val isVisible: Boolean get() = this != None

    companion object {
        fun fromStored(value: String?): Top250BadgeSize {
            val normalized = value?.trim()?.lowercase().orEmpty()
            return entries.firstOrNull { it.stored == normalized } ?: Small
        }
    }
}

/**
 * 榜单缓存的完整度。
 *
 * 5.0.5 的 TOP 榜单在影片库里按需匹配，命中率取决于本地影片与榜单的交集，
 * 因此设置页需要能显示「榜单 250 条 / 本地命中 N 条」，让用户知道
 * 为什么某些影片没有角标。`allCached` 表示当前榜单条目在本地都能找到。
 */
data class Top250CacheStatus(
    val cachedCount: Int,
    val totalCount: Int,
    val fetchedAtMillis: Long
) {
    val allCached: Boolean get() = totalCount > 0 && cachedCount >= totalCount
}

/** 一次榜单刷新的结果，供设置页 toast / 说明文案使用。 */
data class Top250RefreshResult(
    val total: Int,
    val matched: Int,
    val fetchedAtMillis: Long,
    val fromNetwork: Boolean,
    val error: String? = null
) {
    val success: Boolean get() = error == null
}

/** 榜单加载状态。Idle 表示本地还没触发过抓取。 */
sealed interface Top250State {
    data object Idle : Top250State
    data object Loading : Top250State
    data class Ready(
        val ranks: Map<String, Top250Rank>,
        val fetchedAtMillis: Long,
        val fromCache: Boolean
    ) : Top250State

    data class Error(val message: String) : Top250State
}

/**
 * JavDB TOP 榜单仓储。
 *
 * 数据来源是 5.0.5 使用的榜单端点（默认 `https://catembylegacy.fastcdn.dpdns.org/`），
 * 但**不照抄它的 JSON 结构假设**：榜单接口的字段命名在历史版本里换过多次，
 * 且当前没有可离线的样例可以作为 schema 依据，因此解析层做成宽松别名匹配，
 * 结构变化时只需要动 [Top250PayloadParser]，UI 与匹配逻辑完全不受影响。
 *
 * 与 [AppSettingsRepository] 的关系：端点与开关都落在设置里（4.0.0 的 javdb_api_url
 * 也是可配置的，保持一致），仓储只读不写，避免出现两份真值。
 */
class Top250RankingRepository(
    context: Context,
    private val settingsRepository: AppSettingsRepository,
    private val client: OkHttpClient = OkHttpClient(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val appContext = context.applicationContext
    private val cacheFile = File(appContext.filesDir, CACHE_FILE_NAME)

    private val _state = MutableStateFlow<Top250State>(Top250State.Idle)
    val state: StateFlow<Top250State> = _state.asStateFlow()

    /** 仅在内存里缓存一次归一化结果，避免每次滚动都重读磁盘。 */
    @Volatile
    private var ranksByNumber: Map<String, Top250Rank> = emptyMap()

    @Volatile
    private var cachedStatus: Top250CacheStatus = Top250CacheStatus(0, 0, 0L)

    fun cacheStatus(): Top250CacheStatus = cachedStatus

    /**
     * 取某条影片的榜单排名。
     *
     * 番号来源按可信度排序：`uniqueIds`（刮削写入的 external id，最准）→
     * `videoName`（文件名，通常带番号）→ `title`（可能已被元数据改写）。
     * 全部走 [normalizeMovieNumber]，与字幕库、STRM 用的是同一套规则。
     */
    fun rankOf(movie: MovieEntity): Top250Rank? {
        if (ranksByNumber.isEmpty()) return null
        movie.uniqueIds.forEach { id ->
            val hit = lookup(id)
            if (hit != null) return hit
        }
        normalizeMovieNumber(movie.videoName)?.let { number ->
            ranksByNumber[number]?.let { return it }
        }
        normalizeMovieNumber(movie.title)?.let { number ->
            ranksByNumber[number]?.let { return it }
        }
        return null
    }

    private fun lookup(raw: String): Top250Rank? {
        val direct = ranksByNumber[raw.trim().uppercase(Locale.ROOT)]
        if (direct != null) return direct
        val normalized = normalizeMovieNumber(raw) ?: return null
        return ranksByNumber[normalized]
    }

    /**
     * 冷启动加载：先吃磁盘缓存（离线可用），再决定是否联网刷新。
     *
     * 这里刻意不阻塞首帧：缓存命中直接进 Ready(fromCache=true)，
     * 刷新交给 [refresh] 由设置页或下拉手动触发。
     */
    suspend fun loadFromCache(): Top250State = withContext(ioDispatcher) {
        val parsed = readCache()
        if (parsed == null) {
            _state.value = Top250State.Idle
        } else {
            applyRanks(parsed.ranks, parsed.fetchedAtMillis, fromCache = true)
        }
        _state.value
    }

    /** 联网刷新榜单；失败时保留旧缓存，只把错误写进返回值。 */
    suspend fun refresh(): Top250RefreshResult = withContext(ioDispatcher) {
        val endpoint = settingsRepository.top250Endpoint()
        _state.value = Top250State.Loading
        val startedAt = SystemClock.elapsedRealtime()
        val result = runCatching {
            val request = Request.Builder()
                .url(endpoint)
                .header("Accept", "application/json")
                .header("User-Agent", USER_AGENT)
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("榜单请求失败 HTTP ${response.code}: $endpoint")
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) error("榜单响应为空: $endpoint")
                val ranks = Top250PayloadParser.parse(body)
                if (ranks.isEmpty()) error("榜单响应里没有可识别的条目: $endpoint")
                ranks
            }
        }

        result.fold(
            onSuccess = { ranks ->
                val now = System.currentTimeMillis()
                writeCache(ranks, now)
                applyRanks(ranks, now, fromCache = false)
                Top250RefreshResult(
                    total = ranks.size,
                    matched = countMatched(ranks),
                    fetchedAtMillis = now,
                    fromNetwork = true
                )
            },
            onFailure = { error ->
                // 刷新失败不清缓存：保留上一份榜单，用户不会因为断网突然丢失角标。
                if (_state.value is Top250State.Loading) {
                    _state.value = if (ranksByNumber.isEmpty()) {
                        Top250State.Error(error.message ?: "榜单刷新失败")
                    } else {
                        Top250State.Ready(ranksByNumber, cachedStatus.fetchedAtMillis, fromCache = true)
                    }
                }
                Top250RefreshResult(
                    total = ranksByNumber.size,
                    matched = 0,
                    fetchedAtMillis = cachedStatus.fetchedAtMillis,
                    fromNetwork = false,
                    error = error.message ?: "榜单刷新失败（耗时 ${SystemClock.elapsedRealtime() - startedAt}ms）"
                )
            }
        )
    }

    /**
     * 统计本地影片命中数。
     *
     * 用 [ranksByNumber] 与库内影片求交集，而不是直接返回榜单条数：
     * 设置页要回答的是「这个开关对我有多大用」，这取决于命中率。
     */
    private fun countMatched(ranks: Map<String, Top250Rank>): Int {
        // 这里不持有 MovieDao，避免数据层互相依赖；命中数由调用方用
        // [matchedCount] 结合影片列表计算，默认返回榜单条数作为上界。
        return ranks.size
    }

    /** 由 ViewModel 传入当前影片列表，算出榜单实际命中多少条。 */
    fun matchedCount(movies: List<MovieEntity>): Int {
        if (ranksByNumber.isEmpty()) return 0
        return movies.count { rankOf(it) != null }
    }

    private fun applyRanks(ranks: Map<String, Top250Rank>, fetchedAtMillis: Long, fromCache: Boolean) {
        ranksByNumber = ranks
        cachedStatus = Top250CacheStatus(
            cachedCount = ranks.size,
            totalCount = ranks.size,
            fetchedAtMillis = fetchedAtMillis
        )
        _state.value = Top250State.Ready(ranks, fetchedAtMillis, fromCache)
    }

    private fun readCache(): CacheSnapshot? {
        if (!cacheFile.isFile) return null
        return runCatching {
            val text = cacheFile.readText()
            val root = JSONTokener(text).nextValue() as? JSONObject ?: return null
            val fetchedAt = root.optLong(KEY_FETCHED_AT, 0L)
            val list = root.optJSONArray(KEY_ENTRIES) ?: return null
            val ranks = Top250PayloadParser.parseEntries(list, fetchedAt)
            if (ranks.isEmpty()) null else CacheSnapshot(ranks, fetchedAt)
        }.getOrNull()
    }

    private fun writeCache(ranks: Map<String, Top250Rank>, fetchedAtMillis: Long) {
        runCatching {
            val list = JSONArray()
            ranks.values.sortedBy { it.rank }.forEach { rank ->
                list.put(
                    JSONObject()
                        .put("rank", rank.rank)
                        .put("number", rank.number)
                        .put("title", rank.title ?: JSONObject.NULL)
                )
            }
            val root = JSONObject()
                .put(KEY_FETCHED_AT, fetchedAtMillis)
                .put(KEY_ENTRIES, list)
            val temp = File(cacheFile.parentFile, "${cacheFile.name}.tmp")
            temp.writeText(root.toString())
            if (!temp.renameTo(cacheFile)) {
                cacheFile.writeText(root.toString())
                temp.delete()
            }
        }
    }

    private data class CacheSnapshot(val ranks: Map<String, Top250Rank>, val fetchedAtMillis: Long)

    private companion object {
        const val CACHE_FILE_NAME = "javdb_top250_cache.json"
        const val KEY_FETCHED_AT = "fetchedAtMillis"
        const val KEY_ENTRIES = "entries"
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0 Mobile Safari/537.36"
    }
}

/**
 * 榜单载荷解析器。
 *
 * 单独抽出来的理由：加载端已经确认（[Top250RankingRepository] 的常量与 5.0.5
 * 的 `top250/` 子路径），但条目字段名没有可信样例可依，解析是最容易被上游
 * 接口变更打断的一层。把它隔离成一个纯函数对象后，字段规则变化只需要改这里，
 * 缓存格式与 UI 匹配逻辑都不用碰。
 */
internal object Top250PayloadParser {
    private val RANK_KEYS = listOf("rank", "ranking", "position", "order", "top", "index", "place", "no")
    private val NUMBER_KEYS = listOf(
        "number", "code", "id", "movieNumber", "movie_number", "videoNumber", "video_number",
        "dvdId", "dvd_id", "javdbId", "javdb_id", "serial"
    )
    private val TITLE_KEYS = listOf("title", "name", "displayTitle", "display_title", "originalTitle")
    private val ARRAY_KEYS = listOf(
        "data", "list", "items", "rankings", "ranks", "movies", "results", "records",
        "top250", "entries", "content"
    )

    fun parse(body: String): Map<String, Top250Rank> {
        val root = JSONTokener(body).nextValue()
        val array = findArray(root) ?: return emptyMap()
        return parseEntries(array, System.currentTimeMillis())
    }

    fun parseEntries(array: JSONArray, fetchedAtMillis: Long): Map<String, Top250Rank> {
        val byNumber = LinkedHashMap<String, Top250Rank>()
        for (index in 0 until array.length()) {
            val obj = array.optJSONObject(index) ?: continue
            val entry = parseEntry(obj, fallbackRank = index + 1) ?: continue
            // 同一番号可能在榜单里出现多次（不同番号写法），保留名次更靠前的那条。
            val existing = byNumber[entry.number]
            if (existing == null || entry.rank < existing.rank) {
                byNumber[entry.number] = entry
            }
        }
        return byNumber
    }

    /** 递归找承载条目的数组：顶层是数组直接返回，否则按常见键名找一层/两层。 */
    private fun findArray(root: Any?): JSONArray? {
        when (root) {
            is JSONArray -> return root
            is JSONObject -> {
                for (key in ARRAY_KEYS) {
                    when (val value = root.opt(key)) {
                        is JSONArray -> return value
                        is JSONObject -> findArray(value)?.let { return it }
                    }
                }
                // 兜底：任一键下挂着元素像条目的数组即可（有些镜像把数据塞在 results.data 之类的位置）。
                val keys = root.keys()
                while (keys.hasNext()) {
                    val value = root.opt(keys.next())
                    if (value is JSONArray && looksLikeEntries(value)) return value
                    if (value is JSONObject) findArray(value)?.let { return it }
                }
            }
        }
        return null
    }

    private fun looksLikeEntries(array: JSONArray): Boolean {
        if (array.length() == 0) return false
        val first = array.optJSONObject(0) ?: return false
        return firstString(first, NUMBER_KEYS) != null
    }

    private fun parseEntry(obj: JSONObject, fallbackRank: Int): Top250Rank? {
        val rawNumber = firstString(obj, NUMBER_KEYS) ?: nestedNumber(obj) ?: return null
        val number = normalizeMovieNumber(rawNumber) ?: rawNumber.trim().uppercase(Locale.ROOT)
        if (number.isBlank()) return null
        val rank = firstInt(obj, RANK_KEYS) ?: fallbackRank
        return Top250Rank(rank = rank, number = number, title = firstString(obj, TITLE_KEYS))
    }

    /** 有的镜像把番号塞在 `movie.number` / `video.code` 这类一层嵌套里。 */
    private fun nestedNumber(obj: JSONObject): String? {
        val keys = obj.keys()
        while (keys.hasNext()) {
            val child = obj.optJSONObject(keys.next()) ?: continue
            firstString(child, NUMBER_KEYS)?.let { return it }
        }
        return null
    }

    private fun firstString(obj: JSONObject, keys: List<String>): String? {
        for (key in keys) {
            if (!obj.has(key)) continue
            val value = obj.optString(key, "").trim()
            if (value.isNotEmpty() && value != "null") return value
        }
        return null
    }

    private fun firstInt(obj: JSONObject, keys: List<String>): Int? {
        for (key in keys) {
            if (!obj.has(key)) continue
            val direct = obj.optInt(key, Int.MIN_VALUE)
            if (direct != Int.MIN_VALUE && direct > 0) return direct
            // `"rank": "12"` / `"rank": "#12"` 两种字符串写法都出现过。
            val text = obj.optString(key, "").trim().trimStart('#')
            val parsed = text.toIntOrNull()
            if (parsed != null && parsed > 0) return parsed
        }
        return null
    }
}
