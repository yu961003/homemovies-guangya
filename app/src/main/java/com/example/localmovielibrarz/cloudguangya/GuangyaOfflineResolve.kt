package com.example.localmovielibrarz.cloudguangya

import org.json.JSONArray
import org.json.JSONObject

/**
 * 离线下载（云添加）解析结果模型 + 解析器。
 *
 * ## 协议对齐（光鸭网页端官方前端 bundle 实测）
 * 解析：`POST /cloudcollection/v1/resolve_res  { url }`
 * ```js
 * let t = await resolveRes({url})
 * return { resType: t.data.resType,
 *          info: t.data.btResInfo || t.data.urlResInfo || t.data.emuleResInfo,
 *          url: t.data.url }          // ★ 注意：url 取的是服务端返回的 data.url
 * ```
 * 提交：`POST /cloudcollection/v1/create_task`
 * - BT（resType 1/2，info 带 subfiles）：`{ fileIndexes: [...], url: res.url, parentId }`
 * - 其它（ed2k / http 单文件）：`{ url: res.url, parentId }`
 *
 * ## H 轮修复动机
 * 旧实现丢弃 resolve_res 的返回值，直接把「用户粘贴的原始链接」提交给 create_task，
 * 与官方前端不一致：电驴链接（data 里是 emuleResInfo）被服务端按 BT 处理，批量提交大量返回
 * 「BT 文件不存在」。改为提交服务端规范化后的 data.url，并按 resType 附带 fileIndexes。
 */
data class GuangyaResolvedResource(
    /** 提交 create_task 应使用的链接（服务端规范化的 data.url，拿不到时回退原始链接）。 */
    val url: String,
    /** 用户输入的原始链接（日志/诊断用）。 */
    val rawUrl: String,
    /** data.resType：1/2 = BT（btResInfo.subfiles），其它 = 单文件资源（urlResInfo / emuleResInfo）。 */
    val resType: Int,
    /** 命中的信息体：bt / url / emule / null。 */
    val infoKind: String?,
    /** 资源名（info.fileName / info.name），仅用于日志。 */
    val name: String?,
    /** BT 子文件索引（仅 BT 类型且节点自带 fileIndex 时非空；空表示不传 fileIndexes）。 */
    val fileIndexes: List<Int>,
    /**
     * 是否拿到服务端规范化的 data.url（false = 回退到原始链接）。
     * 注意：不能用「url != rawUrl」判断——服务端返回的规范链接常常与原始链接字面相同。
     */
    val usedResolvedUrl: Boolean
) {
    val isBt: Boolean get() = resType == RES_TYPE_BT_1 || resType == RES_TYPE_BT_2

    val isEd2k: Boolean get() = infoKind == KIND_EMULE || rawUrl.startsWith("ed2k://", ignoreCase = true)

    companion object {
        const val RES_TYPE_UNKNOWN = -1
        const val RES_TYPE_BT_1 = 1
        const val RES_TYPE_BT_2 = 2
        const val KIND_BT = "bt"
        const val KIND_URL = "url"
        const val KIND_EMULE = "emule"
        /** fileIndexes 单次提交上限（对齐网页端分批思路，防止超大 BT 撑爆请求体）。 */
        const val FILE_INDEX_BATCH = 500
    }
}

object GuangyaOfflineResolve {
    private const val MAX_URL_LEN = 4096
    private const val MAX_FILE_INDEXES = 5000

    /**
     * 解析 resolve_res 的 data。
     * @param rawUrl 用户输入的原始链接
     * @param data   resolve_res 返回的 data（解析失败/为空时退化为「直接用原始链接提交」）
     */
    fun parse(rawUrl: String, data: JSONObject?): GuangyaResolvedResource {
        val resolvedUrl = optText(data, "url")?.takeIf { it.length <= MAX_URL_LEN }
        val url = resolvedUrl ?: rawUrl
        val resType = data?.optInt("resType", GuangyaResolvedResource.RES_TYPE_UNKNOWN)
            ?: GuangyaResolvedResource.RES_TYPE_UNKNOWN
        val (kind, info) = pickInfo(data)
        val name = optText(info, "fileName") ?: optText(info, "name") ?: optText(info, "title")
        val fileIndexes = if (resType == GuangyaResolvedResource.RES_TYPE_BT_1 ||
            resType == GuangyaResolvedResource.RES_TYPE_BT_2
        ) {
            extractFileIndexes(info)
        } else {
            emptyList()
        }
        return GuangyaResolvedResource(
            url = url,
            rawUrl = rawUrl,
            resType = resType,
            infoKind = kind,
            name = name,
            fileIndexes = fileIndexes,
            usedResolvedUrl = resolvedUrl != null
        )
    }

    private fun pickInfo(data: JSONObject?): Pair<String?, JSONObject?> {
        if (data == null) return null to null
        val candidates = arrayOf(
            GuangyaResolvedResource.KIND_BT to "btResInfo",
            GuangyaResolvedResource.KIND_URL to "urlResInfo",
            GuangyaResolvedResource.KIND_EMULE to "emuleResInfo"
        )
        for ((kind, key) in candidates) {
            val obj = data.optJSONObject(key)
            if (obj != null) return kind to obj
        }
        return null to null
    }

    /**
     * 递归收集 BT 子文件索引：只取**节点自带 fileIndex** 的非目录节点
     * （与网页端 `e.fileIndex ?? 0` 同源；没有 fileIndex 字段时返回空，交由调用方按「不传 fileIndexes」提交）。
     */
    private fun extractFileIndexes(info: JSONObject?): List<Int> {
        val subfiles = info?.optJSONArray("subfiles") ?: return emptyList()
        val out = LinkedHashSet<Int>()
        collectIndexes(subfiles, out)
        return out.toList()
    }

    private fun collectIndexes(array: JSONArray, out: MutableSet<Int>) {
        for (i in 0 until array.length()) {
            if (out.size >= MAX_FILE_INDEXES) return
            val node = array.optJSONObject(i) ?: continue
            val children = node.optJSONArray("subfiles")
            val isDir = node.optBoolean("isDir", false)
            if (isDir) {
                if (children != null) collectIndexes(children, out)
                continue
            }
            val index = node.optInt("fileIndex", -1)
            if (index >= 0) out.add(index)
            else if (children != null) collectIndexes(children, out)
        }
    }

    /** org.json 的 optString 在字段缺失/JSON null 时会给出 "" 或 "null"，这里统一成 null。 */
    private fun optText(obj: JSONObject?, key: String): String? {
        val value = obj?.opt(key) ?: return null
        if (value == JSONObject.NULL) return null
        val text = value.toString().trim()
        if (text.isEmpty() || text.equals("null", ignoreCase = true)) return null
        return text
    }
}
