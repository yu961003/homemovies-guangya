package com.example.localmovielibrarz.cloudguangya

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import com.example.localmovielibrarz.util.HttpClients

class GuangyaTokenExpiredException(message: String = "光鸭访问令牌已失效") : Exception(message)
class GuangyaApiException(message: String, code: String = "") : Exception(message)

/**
 * 光鸭业务客户端：列目录 / 直链。返回 {msg, data, error} 信封（P1-7）。
 *
 * - 仅注入 `Authorization: Bearer <token>`，无签名 / HMAC（反编译确认）。
 * - 401 或信封错误 → 抛 [GuangyaTokenExpiredException]，由 [apiCall] 触发刷新重试（P1-4）。
 */
class GuangyaApiClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(12, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .connectionPool(HttpClients.sharedConnectionPool)
        .dispatcher(HttpClients.sharedDispatcher)
        .build(),
    private val tokenProvider: GuangyaTokenProvider,
    private val rateLimiter: GuangyaRateLimiter,
    private val tokenStore: GuangyaTokenStore
    ) {
    private companion object {
        const val MAX_PAGES = 50 // 循环翻页上限（≤5000 项；文件夹场景 pageSize=500 时≤25000），防止接口异常导致无限循环
    }

    private suspend fun <T> apiCall(block: suspend () -> T): T = try {
        block()
    } catch (e: GuangyaTokenExpiredException) {
        tokenProvider.refreshAndStore() // P1-4 刷新（带并发锁）+ 存盘
        block()                         // 重试一次
    }

    suspend fun listFiles(parentId: String): List<GuangyaFileItem> =
        apiCall {
            // 第四轮 1A：稳定排序 + 跨页 fileId 去重 + total 校验（对齐参考脚本 stableMerge/listPage）
            // orderBy:3 sortType:1 = 稳定排序，避免翻页窗口漂移导致同文件计入多页
            val all = LinkedHashMap<String, GuangyaFileItem>()
            var page = 0
            var expectedTotal = -1
            while (true) {
                rateLimiter.acquireRead() // 每页都限频，避免连续翻页触发光鸭限频
                val data = postEnvelope(
                    "/userres/v1/file/get_file_list",
                    mapOf("parentId" to parentId, "page" to page, "pageSize" to 100,
                        "orderBy" to 3, "sortType" to 1)
                )
                val list = data.optJSONArray("list")
                if (list == null || list.length() == 0) break
                val t = data.optInt("total", -1)
                if (t >= 0) {
                    if (expectedTotal < 0) expectedTotal = t
                    else if (t != expectedTotal) break   // 分页期间目录变化，停止拼接防重复
                }
                for (i in 0 until list.length()) {
                    parseFile(list.optJSONObject(i))?.let { all.putIfAbsent(it.fileId, it) }
                }
                if (list.length() < 100 || page >= MAX_PAGES) break
                if (expectedTotal > 0 && all.size >= expectedTotal) break
                page++
            }
            all.values.toList()
        }

    /** 原始直链（核心路径）。返回 data.signedURL（临时地址，播放时实时解析）。 */
    suspend fun fetchDirectUrl(fileId: String): String =
        apiCall {
            rateLimiter.acquireRead()
            val data = postEnvelope("/userres/v1/get_res_download_url", mapOf("fileId" to fileId))
            data.optString("signedURL").takeIf { it.isNotBlank() } ?: error("光鸭直链为空")
        }

    /**
     * VOD(m3u8) 直链（可选，P2-2 首期不建议启用）。
     * gcid 为空时按 P1-5 先取文件详情补 gcid。
     */
    suspend fun fetchVodUrl(fileId: String, gcid: String?): String =
        apiCall {
            val realGcid = gcid ?: getFileDetailGcid(fileId)
            rateLimiter.acquireRead()
            val data = postEnvelope(
                "/userres/v1/file/get_vod_download_url",
                mapOf("fileId" to fileId, "gcid" to (realGcid ?: ""))
            )
            data.optString("signedURL").takeIf { it.isNotBlank() } ?: error("光鸭 VOD 直链为空")
        }

    private suspend fun getFileDetailGcid(fileId: String): String? =
        runCatching {
            rateLimiter.acquireRead()
            val data = postEnvelope("/userres/v1/file/get_file_detail", mapOf("fileId" to fileId))
            data.optString("gcid").takeIf { it.isNotBlank() }
        }.getOrNull()

    // ==================== 文件夹选择（离线下载目标目录） ====================

    /** 列出某目录下的一级子文件夹（resType=2），用于离线下载目标文件夹选择（移植脚本 listFoldersUnder）。 */
    suspend fun listFolders(parentId: String): List<GuangyaFileItem> =
        apiCall {
            // 审查 P1(A)：文件夹选择器同样需循环翻页，避免大目录被静默截断
            val all = mutableListOf<GuangyaFileItem>()
            var page = 0
            while (true) {
                rateLimiter.acquireRead()
                val data = postEnvelope(
                    "/userres/v1/file/get_file_list",
                    mapOf("parentId" to parentId, "page" to page, "pageSize" to 500, "orderBy" to 0, "sortType" to 0, "resType" to 2)
                )
                val list = data.optJSONArray("list")
                if (list == null || list.length() == 0) break
                val parsed = buildList {
                    for (i in 0 until list.length()) {
                        parseFile(list.optJSONObject(i))?.let { if (it.isDir) add(it) }
                    }
                }
                all += parsed
                if (parsed.size < 500 || page >= MAX_PAGES) break
                page++
            }
            all
        }

    /** 全局搜索文件夹（search_files，resType=2），用于离线下载目标文件夹搜索（移植脚本 searchFolders）。 */
    suspend fun searchFolders(keyword: String): List<GuangyaFileItem> =
        apiCall {
            // 审查 P1(A)：文件夹搜索同样需循环翻页，避免大结果被静默截断
            val all = mutableListOf<GuangyaFileItem>()
            var page = 0
            while (true) {
                rateLimiter.acquireRead()
                val data = postEnvelope(
                    "/userres/v1/file/search_files",
                    mapOf("name" to keyword, "page" to page, "pageSize" to 100, "orderBy" to 0, "sortType" to 0, "resType" to 2)
                )
                val list = data.optJSONArray("list")
                if (list == null || list.length() == 0) break
                val parsed = buildList {
                    for (i in 0 until list.length()) {
                        val o = list.optJSONObject(i) ?: continue
                        // 搜索结果每项可能直接有 fileId/fileName，或包在 fileInfo 里（脚本已确认）
                        val fi = o.optJSONObject("fileInfo") ?: o
                        val fileId = fi.optString("fileId").takeIf { it.isNotBlank() } ?: fi.optString("id", "")
                        val name = fi.optString("fileName").takeIf { it.isNotBlank() } ?: fi.optString("name", "")
                        if (fileId.isNotBlank() && name.isNotBlank()) {
                            add(
                                GuangyaFileItem(
                                    fileId = fileId,
                                    name = name,
                                    isDir = true,
                                    size = 0L,
                                    fileType = null,
                                    gcid = null,
                                    parentId = fi.optString("parentId", "")
                                )
                            )
                        }
                    }
                }
                all += parsed
                if (parsed.size < 100 || page >= MAX_PAGES) break
                page++
            }
            all
        }

    // ==================== 离线下载（cloudcollection/v1，借鉴 guangya-txt 脚本逆向接口） ====================

    /**
     * 解析离线下载链接（磁力链 / .torrent URL / ed2k），返回 data。
     *
     * H 轮：返回值由调用方（Repository）用 [GuangyaOfflineResolve] 解析出服务端规范化的
     * `data.url` / `data.resType` / `btResInfo|urlResInfo|emuleResInfo`，create_task 必须用它提交。
     */
    suspend fun resolveOfflineUrl(url: String): JSONObject =
        apiCall {
            rateLimiter.acquireRead()
            postCloud("/cloudcollection/v1/resolve_res", mapOf("url" to url))
        }

    /**
     * 创建云下载任务（离线下载）。
     *
     * @param url 提交给服务端的链接（应优先使用 resolve_res 返回的规范化 data.url）
     * @param parentId 目标目录
     * @param fileIndexes BT 资源要下载的子文件索引；为空则不传该字段（ed2k / http 单文件走此路径）
     */
    suspend fun createOfflineTask(
        url: String,
        parentId: String = "",
        fileIndexes: List<Int> = emptyList()
    ): JSONObject =
        apiCall {
            rateLimiter.acquireRead()
            val body = LinkedHashMap<String, Any?>()
            body["url"] = url
            body["parentId"] = parentId
            if (fileIndexes.isNotEmpty()) {
                body["fileIndexes"] = JSONArray().apply { fileIndexes.forEach { put(it) } }
            }
            postCloud("/cloudcollection/v1/create_task", body)
        }

    /**
     * cloudcollection（离线下载）专用请求：`{code, msg, data}` 信封 + dt/traceparent 头。
     * 与业务接口的 `{msg, data, error}` 信封不同，不能复用 postEnvelope。
     */
    private suspend fun postCloud(path: String, body: Map<String, Any?>): JSONObject =
        withContext(Dispatchers.IO) {
            val token = tokenProvider.getValidAccessToken()
            val req = Request.Builder().url(GuangyaConstants.API_BASE + path)
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("dt", "4")
                .header("traceparent", GuangyaDevice.generateTraceparent())
                .post(JSONObject(body).toString().toRequestBody(GuangyaConstants.MEDIA_JSON))
                .build()
            http.newCall(req).execute().use { resp ->
                if (resp.code == 401) throw GuangyaTokenExpiredException() // 触发上层刷新重试
                val root = JSONObject(resp.body?.string().orEmpty())
                if (root.has("code") && root.optInt("code", -1) != 0) {
                    throw GuangyaApiException(
                        root.optString("msg").takeIf { it.isNotBlank() } ?: "code ${root.optInt("code")}",
                        root.optString("error", "")
                    )
                }
                return@withContext if (root.has("data")) root.getJSONObject("data") else root
            }
        }

    private suspend fun postEnvelope(path: String, body: Map<String, Any?>): JSONObject =
        withContext(Dispatchers.IO) {
            val token = tokenProvider.getValidAccessToken() // 协程内预取（P2-6），非拦截器 runBlocking
            val req = Request.Builder().url(GuangyaConstants.API_BASE + path)
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/json")
                .header("Content-Type", "application/json") // P1-8
                .post(JSONObject(body).toString().toRequestBody(GuangyaConstants.MEDIA_JSON))
                .build()
            http.newCall(req).execute().use { resp ->
                if (resp.code == 401) throw GuangyaTokenExpiredException() // 触发上层刷新重试
                val root = JSONObject(resp.body?.string().orEmpty())
                val msg = root.optString("msg", "success")
                if (msg != "success" || root.opt("error") != null) {
                    throw GuangyaApiException(msg, root.optString("error", ""))
                }
                return@withContext root.getJSONObject("data") // 信封 data
            }
        }

    /** 全局搜索文件+目录（search_files，resType 不限），用于列表页搜索（需求 2）。 */
    suspend fun searchFiles(keyword: String): List<GuangyaFileItem> =
        apiCall {
            val all = mutableListOf<GuangyaFileItem>()
            var page = 0
            while (true) {
                rateLimiter.acquireRead()
                val data = postEnvelope(
                    "/userres/v1/file/search_files",
                    mapOf("name" to keyword, "page" to page, "pageSize" to 100, "orderBy" to 0, "sortType" to 0)
                )
                val list = data.optJSONArray("list")
                if (list == null || list.length() == 0) break
                val parsed = buildList {
                    for (i in 0 until list.length()) {
                        val o = list.optJSONObject(i) ?: continue
                        val fi = o.optJSONObject("fileInfo") ?: o // 搜索结果可能包在 fileInfo 里
                        parseFile(fi)?.let { add(it) } // 复用统一字段兜底（fileId/id、fileName/name、fileSize/size、resType）
                    }
                }
                all += parsed
                if (parsed.size < 100 || page >= MAX_PAGES) break
                page++
            }
            all
        }

    // ==================== 文件操作（对齐 GuangyaTools-Scripts-master/src/services/guangyaApi.ts） ====================

    /** 任务终态：2=成功，3=失败（guangyaApi.ts:753-759）。 */
    private suspend fun postUserres(path: String, body: Map<String, Any?>): JSONObject =
        withContext(Dispatchers.IO) {
            val token = tokenProvider.getValidAccessToken()
            val did = tokenStore.getOrCreateDeviceId { GuangyaDevice.generateDid() }
            val req = Request.Builder().url(GuangyaConstants.API_BASE + path)
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("dt", "4")
                .header("did", did)
                .header("traceparent", GuangyaDevice.generateTraceparent())
                .post(JSONObject(body).toString().toRequestBody(GuangyaConstants.MEDIA_JSON))
                .build()
            http.newCall(req).execute().use { resp ->
                if (resp.code == 401) throw GuangyaTokenExpiredException()
                val bodyText = resp.body?.string().orEmpty()
                android.util.Log.d("GuangyaFileOp",
                    "POST $path ← ${JSONObject(body).toString().take(300)} → HTTP ${resp.code} : ${bodyText.take(800)}")
                if (resp.code == 429) throw GuangyaApiException("请求过于频繁，请稍后重试")
                // 403 参考脚本按「登录过期」处理
                if (resp.code == 403) throw GuangyaApiException("登录状态已过期，请重新登录光鸭")
                val root = JSONObject(bodyText)
                // 信封判定（对齐 guangyaApi.ts:339-357）：code === 0 才成功。
                // msg 校验仅在 code 缺失时兜底——部分接口成功的 msg 未必是 "success"，
                // 双重校验可能把 code=0 的成功误判为失败
                val code = root.opt("code")?.let { (it as? Number)?.toInt() ?: it.toString().toIntOrNull() }
                val msg = root.optString("msg", "")
                if (code != null && code != 0) throw GuangyaApiException(msg.ifBlank { "业务错误 code $code" })
                if (code == null && msg.isNotBlank() && msg != "success")
                    throw GuangyaApiException(msg, root.optString("error", ""))
                return@withContext if (root.has("data")) root.optJSONObject("data") ?: JSONObject() else root
            }
        }

    /** 回收站删除（异步任务）。每批 ≤50，返回本批 taskId；对齐 guangyaApi.ts trashItems。 */
    suspend fun trashItems(fileIds: List<String>): String {
        require(fileIds.isNotEmpty()) { "删除列表为空" }
        require(fileIds.size <= 50) { "单批最多 50 项" }
        val data = apiCall {
            rateLimiter.acquireWrite()
            postUserres("/userres/v1/file/delete_file", mapOf("fileIds" to fileIds))
        }
        return data.optString("taskId").ifBlank {
            throw GuangyaApiException("删除接口未返回任务 ID，结果未知，请刷新确认")
        }
    }

    /** 移动（异步任务），返回 taskId。 */
    suspend fun moveItems(fileIds: List<String>, parentId: String): String {
        require(fileIds.isNotEmpty()) { "移动列表为空" }
        require(fileIds.size <= 50) { "单批最多 50 项" }
        val data = apiCall {
            rateLimiter.acquireWrite()
            postUserres("/userres/v1/file/move_file", mapOf("fileIds" to fileIds, "parentId" to parentId))
        }
        return data.optString("taskId").ifBlank {
            throw GuangyaApiException("移动接口未返回任务 ID，结果未知，请刷新确认")
        }
    }

    /** 重命名（同步信封）。 */
    suspend fun renameItem(fileId: String, newName: String) {
        apiCall {
            rateLimiter.acquireWrite()
            postUserres("/userres/v1/file/rename", mapOf("fileId" to fileId, "newName" to newName))
        }
    }

    /** 轮询任务到终态。status 2=成功；3=失败（含 detail.code!=0 时取 detail.msg）。对齐 guangyaApi.ts waitTask。 */
    suspend fun waitTask(taskId: String, timeoutMs: Long = 60_000L) {
        if (taskId.isBlank()) return
        val startedAt = System.currentTimeMillis()
        var pollMs = 800L
        while (System.currentTimeMillis() - startedAt < timeoutMs) {
            kotlinx.coroutines.delay(pollMs)
            runCatching {
                apiCall {
                    rateLimiter.acquireRead()
                    postUserres("/userres/v1/get_task_status", mapOf("taskId" to taskId))
                }
            }.onSuccess { data ->
                val status = data.optInt("status", -1)
                val detail = data.optJSONObject("detail")
                val detailCode = detail?.optInt("code", 0) ?: 0
                when {
                    status == 2 && detailCode != 0 ->
                        throw GuangyaApiException(detail?.optString("msg").orEmpty().ifBlank { "任务失败" })
                    status == 2 -> return          // 成功
                    status == 3 ->
                        throw GuangyaApiException(detail?.optString("msg").orEmpty().ifBlank { "任务失败" })
                }
            }
            pollMs = (pollMs * 1.45f).toLong().coerceAtMost(5_000L)
        }
        throw GuangyaApiException("等待任务完成超时，结果未知，请刷新确认")
    }

    /** P1-3 列目录字段兜底（对齐 guangya.py:542-553 的 fileId/id、fileName/name、fileSize/size）。 */
    private fun parseFile(o: JSONObject?): GuangyaFileItem? {
        o ?: return null
        val fileId = o.optString("fileId").takeIf { it.isNotBlank() } ?: o.optString("id", "")
        val name = o.optString("fileName").takeIf { it.isNotBlank() } ?: o.optString("name", "")
        if (fileId.isBlank() || name.isBlank()) return null
        val rawSize = o.opt("fileSize") ?: o.opt("size") ?: o.opt("fileLength")
        val size = when (rawSize) {
            is Number -> rawSize.toLong()
            is String -> rawSize.toLongOrNull() ?: rawSize.toDoubleOrNull()?.toLong() ?: 0L
            else -> 0L
        }
        // 目录判断：光鸭用 resType==2（文件为 1），响应中没有 isDir 字段（AList 驱动确认）
        val isDir = o.optInt("resType", 1) == 2 || o.optBoolean("isDir", false)
        return GuangyaFileItem(
            fileId = fileId,
            name = name,
            isDir = isDir,
            size = size,
            fileType = o.optString("fileType").takeIf { it.isNotBlank() },
            gcid = o.optString("gcid").takeIf { it.isNotBlank() },
            parentId = o.optString("parentId", "")
        )
    }
}
