package com.example.localmovielibrarz.cloudguangya

import com.example.localmovielibrarz.util.VideoFormats

/** 光鸭云盘文件模型（镜像 Cloud115FileItem，以 fileId 作为稳定标识）。 */
data class GuangyaFileItem(
    val fileId: String,
    val name: String,
    val isDir: Boolean,
    val size: Long,
    val fileType: String?,
    val gcid: String?,
    val parentId: String
) {
    /** ★ 统一走 [VideoFormats] 白名单（本轮起新增 WMV 等格式，不再各处各写一份）。 */
    fun isVideoFile(): Boolean = !isDir && VideoFormats.isVideoFileName(name)

    companion object {
        /** 带点的扩展名列表（保留旧签名供既有调用方使用）。 */
        val VIDEO_EXTENSIONS: List<String> =
            VideoFormats.VIDEO_EXTENSIONS.map { ".$it" }
    }
}
