package com.example.localmovielibrarz.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

/**
 * 本地字幕库索引的读写口。
 *
 * 这张表随库规模线性增长（几千部影片的库会有等量甚至更多的字幕行），
 * 所以每条查询都落在已建索引的列上（`uri` / `movieNumber` / `libraryRootUri` /
 * `fileName`），没有全表扫描的入口。
 *
 * 写入按「库根整体替换」而不是逐条 upsert：扫描器一次产出某个根目录下的全集，
 * [replaceRoot] 先删后插，用户删掉或移动过的字幕自然从索引里消失。
 * 逐条 upsert 做不到这一点，只会让索引越攒越脏（搜索结果点开报「文件不存在」）。
 */
@Dao
interface LocalSubtitleLibraryDao {

    /** 某个库根下的字幕条数。 */
    @Query("SELECT COUNT(*) FROM local_subtitle_library WHERE libraryRootUri = :rootUri")
    suspend fun countByRoot(rootUri: String): Int

    /**
     * 按番号列出字幕。
     *
     * 同番号多语言 / 多版本时大字幕优先：体积大的通常是未经二次压缩的原始字幕，
     * 而且很多字幕文件名压根没写语言（`ABC-123.srt`），先把最完整的那份给用户。
     */
    @Query("SELECT * FROM local_subtitle_library WHERE movieNumber = :number ORDER BY sizeBytes DESC")
    suspend fun searchByNumber(number: String): List<LocalSubtitleLibraryEntity>

    /**
     * 按文件名模糊搜索。
     *
     * `ESCAPE '\'` 让调用方能显式转义 `%` 与 `_`：不加这个子句时，用户搜「100%」
     * 里的 `%` 会被当成通配符，搜 `ABC_123` 里的 `_` 会匹配任意单字符。
     * 这里只声明转义符，转不转义由调用方决定——搜 `srt` 这种普通关键词不需要处理。
     *
     * @param limit 默认 50：搜索框是即时联想，返回全库命中既慢也没人看。
     */
    @Query("SELECT * FROM local_subtitle_library WHERE fileName LIKE '%' || :keyword || '%' ESCAPE '\\' ORDER BY fileName LIMIT :limit")
    suspend fun searchByKeyword(keyword: String, limit: Int = 50): List<LocalSubtitleLibraryEntity>

    /** 某个库根下的全部字幕，按相对路径排序（扫描落库后按目录顺序展示、也便于核对）。 */
    @Query("SELECT * FROM local_subtitle_library WHERE libraryRootUri = :rootUri ORDER BY relativePath")
    suspend fun listByRoot(rootUri: String): List<LocalSubtitleLibraryEntity>

    /** 清空某个库根的索引；[replaceRoot] 的第一段。 */
    @Query("DELETE FROM local_subtitle_library WHERE libraryRootUri = :rootUri")
    suspend fun deleteByRoot(rootUri: String)

    /**
     * 批量写入。
     *
     * [OnConflictStrategy.REPLACE] 命中 `uri` 唯一索引时按「同一个文件的新元数据」处理：
     * 覆盖旧行。自增 id 会换新，但没有任何表用 id 做外键，换 id 无副作用。
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<LocalSubtitleLibraryEntity>)

    /**
     * 用一次扫描的结果替换某个库根下的全部索引。
     *
     * 为什么要 [Transaction]：先删后插之间存在「表里空了一段」的中间态，
     * 若被并发读命中，UI 会先看到字幕全没了、再看到又回来了——一帧闪烁
     * 就足够让用户以为数据丢了。放进同一事务，读方要么看到旧全集，要么看到新全集。
     *
     * @param items 必须全部属于 [rootUri]（扫描器保证）。混入别的根的行走完
     *              [deleteByRoot] 之后就会变成孤儿行，只有下一次扫描到那个根才会被清掉。
     */
    @Transaction
    suspend fun replaceRoot(rootUri: String, items: List<LocalSubtitleLibraryEntity>) {
        deleteByRoot(rootUri)
        if (items.isNotEmpty()) {
            insertAll(items)
        }
    }

    /**
     * 最近一次索引时间；该根下没有任何行时返回 null。
     *
     * 返回可空是因为 `MAX()` 在空表上就是 SQL NULL：用 0 代替会和「时间戳 0」混为一谈，
     * 调用方该显式处理 null（通常是「从没扫过，那就扫一遍」）。
     */
    @Query("SELECT MAX(indexedAt) FROM local_subtitle_library WHERE libraryRootUri = :rootUri")
    suspend fun lastIndexedAt(rootUri: String): Long?

    /**
     * 增量扫描的「没变过」判定：同根下大小与修改时间都相同的文件。
     *
     * 为什么用 (lastModified, sizeBytes) 而不是读内容算哈希：SAF 这两列由 provider
     * 一次 query 直接给出；算哈希得把每个字幕 openInputStream 读一遍，
     * 几千个文件的成本远超「偶尔多扫几个没变的文件」。
     *
     * 少数 provider 会把 lastModified 返回 0（该列不可知），那时这条判定会退化成
     * 「同时长为 0 的文件都算没变」，调用方要按「查不到就重扫」处理：宁可重扫，不可漏扫。
     */
    @Query(
        """
        SELECT * FROM local_subtitle_library
        WHERE libraryRootUri = :rootUri AND lastModified = :lastModified AND sizeBytes = :sizeBytes
        LIMIT 1
        """
    )
    suspend fun findUnchanged(
        rootUri: String,
        lastModified: Long,
        sizeBytes: Long
    ): LocalSubtitleLibraryEntity?
}
