package com.example.localmovielibrarz.data.local

import androidx.room.Entity
import androidx.room.Index

/**
 * 实体别名表：把「同一个演员 / 厂牌 / 系列的不同写法」归到同一个规范键上。
 *
 * 鸭影现在的演员列表是 `List<String>` 直接落库，`葵つかさ` / `葵 つかさ` / `Aoi Tsukasa`
 * 在库里就是三条互不相干的字符串，演员头像与筛选因此各算各的。这里不引入 peach 的
 * 六种实体 kind（它要处理作品、人物、会社、系列、标签、分类的完整本体），只做鸭影真正
 * 会撞上的三种：演员、厂牌、系列——因为它们都直接参与「聚合展示」，而标签/分类是纯文本，
 * 归一的收益远小于维护成本。
 *
 * 主键是 `(kind, normalizedKey)`：normalizedKey 由 `metadata.EntityNameNormalizer.normalize`
 * 产出（去空白、全角转半角、小写），所以同一个别名重复入库是幂等的，不会越插越多。
 */
@Entity(
    tableName = "entity_aliases",
    primaryKeys = ["kind", "normalizedKey"],
    indices = [
        Index(value = ["kind"]),
        Index(value = ["canonicalKey"]),
        Index(value = ["updatedAtMillis"])
    ]
)
data class EntityAliasEntity(
    /** 实体类型：`actor` / `studio` / `series`，见 [EntityAliasKind]。 */
    val kind: String,
    /** 规范化后的比较键（不含空白、小写）。 */
    val normalizedKey: String,
    /** 展示用写法（保留原始大小写与空格），例如 "葵つかさ"。 */
    val displayName: String,
    /** 归组用的规范键（通常是该组首次出现的 normalizedKey）。 */
    val canonicalKey: String,
    /** 首次见到该写法的来源，便于排查「这条别名是怎么来的」。 */
    val sourceLabel: String = "",
    val updatedAtMillis: Long = 0L
)

enum class EntityAliasKind(val storageKey: String) {
    Actor("actor"),
    Studio("studio"),
    Series("series");

    companion object {
        fun fromStorageKey(raw: String?): EntityAliasKind? =
            entries.firstOrNull { it.storageKey.equals(raw?.trim(), ignoreCase = true) }
    }
}
