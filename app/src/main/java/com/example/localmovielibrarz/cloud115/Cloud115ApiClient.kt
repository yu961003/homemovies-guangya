package com.example.localmovielibrarz.cloud115

import com.example.localmovielibrarz.playback.USER_AGENT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import com.example.localmovielibrarz.util.HttpClients

class Cloud115ApiClient(
    private val cookieProvider: Cloud115CookieProvider,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(12, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .connectionPool(HttpClients.sharedConnectionPool)
        .dispatcher(HttpClients.sharedDispatcher)
        .build()
) : Cloud115Client {
    override suspend fun listFiles(cid: Long): List<Cloud115FileItem> = withContext(Dispatchers.IO) {
        val cookies = cookieProvider.loadCookies()
            ?: error("115 Cookie 未配置，请先到设置页填写 Cookie")
        val result = mutableListOf<Cloud115FileItem>()
        var offset = 0
        var totalCount: Int? = null

        while (true) {
            val request = Request.Builder()
                .url("$FILES_URL?aid=1&cid=$cid&o=user_ptime&asc=0&offset=$offset&show_dir=1&limit=$FILES_PAGE_SIZE&format=json")
                .get()
                .header("Cookie", cookies)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json, text/plain, */*")
                .build()

            val rawPageSize = httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    error("115 目录读取失败：HTTP ${response.code}")
                }
                val raw = response.body?.string().orEmpty()
                val json = JSONObject(raw)
                val data = json.optJSONArray("data") ?: error("115 目录响应为空")
                totalCount = json.optString("count")
                    .toIntOrNull()
                    ?: json.optInt("count", -1).takeIf { it >= 0 }
                for (index in 0 until data.length()) {
                    val item = data.optJSONObject(index) ?: continue
                    val name = item.optString("n").takeIf { it.isNotBlank() } ?: continue
                    val fid = item.optString("fid").takeIf { it.isNotBlank() }?.toLongOrNull()
                    val cidValue = item.optString("cid").takeIf { it.isNotBlank() }?.toLongOrNull()
                    result +=
                        Cloud115FileItem(
                            name = name,
                            cid = cidValue,
                            fid = fid,
                            pickcode = item.optString("pc").takeIf { it.isNotBlank() },
                            size = item.optString("s").toLongOrNull(),
                            addedAt = item.optTimestamp("tp"),
                            modifiedAt = item.optTimestamp(
                                "t",
                                "user_ptime",
                                "ptime",
                                "pt",
                                "te",
                                "tu",
                                "mtime",
                                "utime"
                            ),
                            isDirectory = fid == null
                        )
                }
                data.length()
            }

            offset += FILES_PAGE_SIZE
            if (rawPageSize < FILES_PAGE_SIZE) break
            if (totalCount != null && offset >= totalCount) break
        }

        result
    }

    override suspend fun searchFiles(
        keyword: String,
        limit: Int,
        offset: Int,
        type: Int,
        cid: Long?
    ): List<Cloud115FileItem> = withContext(Dispatchers.IO) {
        val cookies = cookieProvider.loadCookies()
            ?: error("115 Cookie 未配置，请先到设置页登录或填写 Cookie")
        val query = keyword.trim()
        if (query.isBlank()) return@withContext emptyList()
        val urlBuilder = SEARCH_URL.toHttpUrl().newBuilder()
            .addQueryParameter("aid", "1")
            .addQueryParameter("search_value", query)
            .addQueryParameter("limit", limit.coerceIn(1, 100).toString())
            .addQueryParameter("offset", offset.coerceAtLeast(0).toString())
            .addQueryParameter("type", type.toString())
            .addQueryParameter("format", "json")
        // 需求 13.1：传入 cid 时限定在当前目录内搜索（115 files/search 支持 cid 范围参数）
        if (cid != null && cid > 0L) {
            urlBuilder.addQueryParameter("cid", cid.toString())
        }
        val url = urlBuilder.build()
        val request = Request.Builder()
            .url(url)
            .get()
            .header("Cookie", cookies)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json, text/plain, */*")
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("115 搜索失败：HTTP ${response.code}")
            }
            val raw = response.body?.string().orEmpty()
            val json = JSONObject(raw)
            if (!json.optBoolean("state", true)) {
                error(json.optString("error", "115 搜索失败"))
            }
            val data = json.optJSONArray("data") ?: return@withContext emptyList()
            buildList {
                for (index in 0 until data.length()) {
                    val item = data.optJSONObject(index) ?: continue
                    val name = item.optString("n").takeIf { it.isNotBlank() } ?: continue
                    val fid = item.optString("fid").takeIf { it.isNotBlank() }?.toLongOrNull()
                    val cidValue = item.optString("cid").takeIf { it.isNotBlank() }?.toLongOrNull()
                    add(
                        Cloud115FileItem(
                            name = name,
                            cid = cidValue,
                            fid = fid,
                            pickcode = item.optString("pc").takeIf { it.isNotBlank() },
                            size = item.optString("s").toLongOrNull(),
                            addedAt = item.optTimestamp("tp"),
                            modifiedAt = item.optTimestamp("t", "te", "pt", "mtime", "utime"),
                            isDirectory = fid == null
                        )
                    )
                }
            }
        }
    }

    override suspend fun fetchDirectUrl(pickcode: String): String = withContext(Dispatchers.IO) {
        val cookies = cookieProvider.loadCookies()
            ?: error("115 Cookie 未配置，请先到设置页填写 Cookie")
        val payload = JSONObject().put("pickcode", pickcode).toString()
        val encryptedPayload = P115Cipher.encrypt(payload)
        val body = FormBody.Builder()
            .add("data", encryptedPayload)
            .build()
        val request = Request.Builder()
            .url(DOWNLOAD_URL)
            .post(body)
            .header("Cookie", cookies)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json, text/plain, */*")
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("115 获取直链失败：HTTP ${response.code}")
            }
            val raw = response.body?.string().orEmpty()
            val json = JSONObject(raw)
            if (!json.optBoolean("state", false)) {
                error(json.optString("message", "115 获取直链失败"))
            }
            val encryptedData = json.optString("data")
            if (encryptedData.isBlank()) error("115 响应缺少直链数据")
            val data = JSONObject(P115Cipher.decrypt(encryptedData))
            extractDirectUrl(data) ?: error("115 响应中没有可播放直链")
        }
    }

    override suspend fun deleteFileByPickcode(pickcode: String, nameHint: String?): Boolean = withContext(Dispatchers.IO) {
        val cookies = cookieProvider.loadCookies()
            ?: error("115 Cookie 未配置，请先到设置页登录或填写 Cookie")
        val normalizedPickcode = pickcode.trim().takeIf { it.isNotBlank() } ?: error("缺少 pickcode，无法删除网盘文件")
        val searchKeyword = nameHint
            ?.substringBeforeLast('.', nameHint)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: normalizedPickcode
        val fid = searchFiles(searchKeyword, limit = 100, type = 99)
            .firstOrNull { item -> item.pickcode == normalizedPickcode && item.fid != null }
            ?.fid
            ?: error("未在 115 网盘中找到 pickcode=$normalizedPickcode 对应文件，已停止网盘删除")
        val body = FormBody.Builder()
            .add("fid[0]", fid.toString())
            .add("ignore_warn", "1")
            .build()
        val request = Request.Builder()
            .url(DELETE_URL)
            .post(body)
            .header("Cookie", cookies)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json, text/plain, */*")
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("115 网盘删除失败：HTTP ${response.code}")
            }
            val raw = response.body?.string().orEmpty()
            val json = JSONObject(raw)
            if (!json.optBoolean("state", false)) {
                error(json.optString("error").ifBlank { json.optString("message", "115 网盘删除失败") })
            }
            true
        }
    }

    override suspend fun deleteFoldersByCids(cids: List<Long>): Boolean = withContext(Dispatchers.IO) {
        val validCids = cids.filter { it > 0L }.distinct()
        if (validCids.isEmpty()) return@withContext true
        val cookies = cookieProvider.loadCookies()
            ?: error("115 Cookie 未配置，请先到设置页登录或填写 Cookie")
        // 115 rb/delete 支持 cid[] 参数批量删除目录（进回收站，非永久删除），每批最多 50 个
        val body = FormBody.Builder().apply {
            validCids.take(BATCH_DELETE_LIMIT).forEachIndexed { index, cid ->
                add("cid[$index]", cid.toString())
            }
            add("ignore_warn", "1")
        }.build()
        val request = Request.Builder()
            .url(DELETE_URL)
            .post(body)
            .header("Cookie", cookies)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json, text/plain, */*")
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("115 网盘删除失败：HTTP ${response.code}")
            }
            val raw = response.body?.string().orEmpty()
            val json = JSONObject(raw)
            if (!json.optBoolean("state", false)) {
                error(json.optString("error").ifBlank { json.optString("message", "115 网盘删除失败") })
            }
            true
        }
    }

    override suspend fun downloadBytes(url: String): ByteArray = withContext(Dispatchers.IO) {
        // 审查 I12（SSRF）：仅允许 115 域名的 https 地址，防止被污染响应指向内网。
        val parsed = url.toHttpUrl()
        require(parsed.scheme == "https") { "仅允许 https 下载地址：$url" }
        require(parsed.host == "115.com" || parsed.host.endsWith(".115.com")) {
            "下载地址域名不在白名单：${parsed.host}"
        }
        val request = Request.Builder()
            .url(parsed)
            .get()
            .header("User-Agent", USER_AGENT)
            .header("Accept", "*/*")
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("115 文件下载失败：HTTP ${response.code}")
            }
            response.body?.bytes() ?: error("115 文件响应为空")
        }
    }

    private fun extractDirectUrl(data: JSONObject): String? {
        data.optString("url").takeIf { it.startsWith("http", ignoreCase = true) }?.let { return it }
        val keys = data.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val item = data.optJSONObject(key) ?: continue
            val urlObject = item.optJSONObject("url")
            urlObject?.optString("url")?.takeIf { it.startsWith("http", ignoreCase = true) }?.let { return it }
            item.optString("url").takeIf { it.startsWith("http", ignoreCase = true) }?.let { return it }
        }
        return null
    }

    private fun JSONObject.optTimestamp(vararg keys: String): Long? {
        keys.forEach { key ->
            val value = optString(key).takeIf { it.isNotBlank() && it != "0" } ?: return@forEach
            value.toLongOrNull()?.let { raw ->
                return if (raw < 10_000_000_000L) raw * 1000L else raw
            }
        }
        return null
    }

    private companion object {
        const val FILES_PAGE_SIZE = 200
        const val BATCH_DELETE_LIMIT = 50
        const val FILES_URL = "https://webapi.115.com/files"
        const val SEARCH_URL = "https://webapi.115.com/files/search"
        const val DOWNLOAD_URL = "https://proapi.115.com/app/chrome/downurl"
        const val DELETE_URL = "https://webapi.115.com/rb/delete"
    }
}
