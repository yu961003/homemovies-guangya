package com.example.localmovielibrarz.data.local

import androidx.room.Entity
import androidx.room.Index

/**
 * 字段归属账本：记录「某个影片的某个字段，最后一次是谁写的、什么权威等级、什么时候写的」。
 *
 * 为什么需要单独一张表而不是给 movies 加十几列：字段有 18 个（见 metadata.MetadataFieldKey），
 * 每个都要记 authority/source/confidence/updatedAt 的话就是 72 列，而绝大多数影片只被一个源
 * 刮过一次，那些行的存在毫无价值。用 `(movieId, fieldKey)` 作主键的稀疏表，只为「真的发生过
 * 写入」的字段留一行，既能回答「用户手改过标题吗」，又不会让 movies 表继续膨胀。
 *
 * 与 movies 表的关系是**旁路**：本表只做判断依据，真正的值仍然写在 movies 里。这样即使账本
 * 丢失（降级安装、迁移失败），最坏结果是重新按默认规则刮一次，不会让影片数据显示不出来。
 */
@Entity(
    tableName = "movie_field_provenance",
    primaryKeys = ["movieId", "fieldKey"],
    indices = [
        Index(value = ["movieId"]),
        Index(value = ["fieldKey"]),
        Index(value = ["updatedAtMillis"])
    ]
)
data class MovieFieldProvenanceEntity(
    val movieId: Long,
    /** metadata.MetadataFieldKey.name，例如 "Title"、"Actors"。 */
    val fieldKey: String,
    /** metadata.MetadataAuthority.name，决定合并时能不能覆盖现有值。 */
    val authority: String,
    /** 人类可读的来源标签，例如 "DMM"、"本地 NFO"、"用户编辑"。 */
    val sourceLabel: String,
    val confidence: Double = 1.0,
    /**
     * 写入时的值摘要（超长截断）。
     *
     * 只留摘要而不是完整值，是为了让「字段变更为空」也有据可查：如果某个源把演员列表
     * 写成了空串，movies.actors 是空、账本里却留着上一次的摘要，就不会出现「值没了但
     * 不知道谁弄没的」这种无法排查的状态。
     */
    val valuePreview: String? = null,
    val updatedAtMillis: Long = 0L
)
