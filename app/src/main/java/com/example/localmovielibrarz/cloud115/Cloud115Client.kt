package com.example.localmovielibrarz.cloud115

interface Cloud115Client {
    suspend fun listFiles(cid: Long): List<Cloud115FileItem>
    suspend fun searchFiles(
        keyword: String,
        limit: Int = 32,
        offset: Int = 0,
        type: Int = 99,
        cid: Long? = null
    ): List<Cloud115FileItem>
    suspend fun fetchDirectUrl(pickcode: String): String
    suspend fun deleteFileByPickcode(pickcode: String, nameHint: String? = null): Boolean
    /** 按目录 id 批量移入回收站（115 rb/delete 支持 cid[] 参数，≤50/批）。 */
    suspend fun deleteFoldersByCids(cids: List<Long>): Boolean
    suspend fun downloadBytes(url: String): ByteArray
}
