package com.example.localmovielibrarz.data.repository

import com.example.localmovielibrarz.data.local.EntityAliasDao
import com.example.localmovielibrarz.data.local.EntityAliasEntity
import com.example.localmovielibrarz.data.local.EntityAliasKind
import com.example.localmovielibrarz.data.local.MovieFieldProvenanceDao
import com.example.localmovielibrarz.data.local.MovieFieldProvenanceEntity
import com.example.localmovielibrarz.metadata.EntityNameNormalizer
import com.example.localmovielibrarz.metadata.MetadataAuthority
import com.example.localmovielibrarz.metadata.MetadataCandidate
import com.example.localmovielibrarz.metadata.MetadataDecision
import com.example.localmovielibrarz.metadata.MetadataFieldKey
import com.example.localmovielibrarz.metadata.MetadataMergeEngine
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * 元数据归属账本的读写门面。
 *
 * 分工刻意做成「纯逻辑在 metadata 包、持久化在这里」：
 * `MetadataMergeEngine` 是无依赖的纯函数（可单测、可复议），本类只负责把结论落库与读回。
 * 这样即使以后换存储（比如换成文件或远端），合并规则也不会跟着搬家。
 *
 * 写入口有两个语义：
 * - [recordWrite]：已经决定要写某个字段，记一笔归属（刮削/手改/NFO 导入都走这里）。
 * - [decideIncoming]：还没决定要不要写，先问引擎——把「当前值 + 账本里的权威等级」喂进去，
 *   拿到 Apply/KeepCurrent/NeedsReview 后再决定动不动 movies 表。
 */
class MovieMetadataLedgerRepository(
    private val provenanceDao: MovieFieldProvenanceDao,
    private val aliasDao: EntityAliasDao,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    /** 账本里某影片某字段的归属；没有记录返回 null（= 从未被标记，按默认规则处理）。 */
    suspend fun provenanceOf(movieId: Long, field: MetadataFieldKey): MovieFieldProvenanceEntity? =
        withContext(ioDispatcher) {
            provenanceDao.get(movieId, field.name)
        }

    suspend fun provenanceForMovie(movieId: Long): Map<MetadataFieldKey, MovieFieldProvenanceEntity> =
        withContext(ioDispatcher) {
            provenanceDao.getForMovie(movieId).mapNotNull { row ->
                MetadataFieldKey.entries.firstOrNull { it.name == row.fieldKey }?.let { it to row }
            }.toMap()
        }

    /**
     * 问引擎「这个新值能不能覆盖现有值」。
     *
     * currentValue 由调用方从 MovieEntity 现取（账本只存权威等级，不存完整值——存了就会与
     * movies 表产生两份真相）。current 为 null 表示该字段还没有账本记录，按 Provider 级
     * 的「空槽」处理：任何非空新值都能填。
     */
    suspend fun decideIncoming(
        movieId: Long,
        field: MetadataFieldKey,
        currentValue: String?,
        incomingValue: String,
        incomingAuthority: MetadataAuthority,
        sourceLabel: String
    ): MetadataDecision = withContext(ioDispatcher) {
        val row = provenanceDao.get(movieId, field.name)
        val currentAuthority = row?.authority?.let { stored ->
            MetadataAuthority.entries.firstOrNull { it.name == stored }
        }
        val current = if (row == null && currentValue.isNullOrBlank()) {
            null
        } else {
            MetadataCandidate(
                field = field,
                value = currentValue,
                authority = currentAuthority ?: MetadataAuthority.Provider,
                sourceLabel = row?.sourceLabel.orEmpty(),
                confidence = row?.confidence ?: 1.0,
                updatedAtMillis = row?.updatedAtMillis ?: 0L
            )
        }
        MetadataMergeEngine.decide(
            current = current,
            incoming = MetadataCandidate(
                field = field,
                value = incomingValue,
                authority = incomingAuthority,
                sourceLabel = sourceLabel
            )
        )
    }

    /** 记录一次已发生的写入（调用方负责真的写 movies）。 */
    suspend fun recordWrite(
        movieId: Long,
        field: MetadataFieldKey,
        authority: MetadataAuthority,
        sourceLabel: String,
        value: String?,
        now: Long = System.currentTimeMillis()
    ) = withContext(ioDispatcher) {
        provenanceDao.upsert(
            listOf(
                MovieFieldProvenanceEntity(
                    movieId = movieId,
                    fieldKey = field.name,
                    authority = authority.name,
                    sourceLabel = sourceLabel,
                    confidence = 1.0,
                    valuePreview = preview(value),
                    updatedAtMillis = now
                )
            )
        )
    }

    /** 批量记录（刮削成功一次写十几个字段时用，避免 N 次事务）。 */
    suspend fun recordWrites(
        movieId: Long,
        entries: List<ProvenanceWrite>,
        now: Long = System.currentTimeMillis()
    ) = withContext(ioDispatcher) {
        if (entries.isEmpty()) return@withContext
        provenanceDao.upsert(
            entries.map { entry ->
                MovieFieldProvenanceEntity(
                    movieId = movieId,
                    fieldKey = entry.field.name,
                    authority = entry.authority.name,
                    sourceLabel = entry.sourceLabel,
                    confidence = entry.confidence,
                    valuePreview = preview(entry.value),
                    updatedAtMillis = now
                )
            }
        )
    }

    /**
     * 用户手改：权威等级直接抬到 Manual，之后任何自动刮削都只能进 NeedsReview。
     * 返回被标记的字段数，便于 UI 提示「已保护 N 个字段」。
     */
    suspend fun recordManualEdit(
        movieId: Long,
        fields: List<Pair<MetadataFieldKey, String?>>,
        now: Long = System.currentTimeMillis()
    ): Int = withContext(ioDispatcher) {
        if (fields.isEmpty()) return@withContext 0
        provenanceDao.upsert(
            fields.map { (field, value) ->
                MovieFieldProvenanceEntity(
                    movieId = movieId,
                    fieldKey = field.name,
                    authority = MetadataAuthority.Manual.name,
                    sourceLabel = MANUAL_SOURCE_LABEL,
                    confidence = 1.0,
                    valuePreview = preview(value),
                    updatedAtMillis = now
                )
            }
        )
        fields.size
    }

    suspend fun clearForMovie(movieId: Long) = withContext(ioDispatcher) {
        provenanceDao.deleteForMovie(movieId)
    }

    suspend fun pruneOrphans(): Int = withContext(ioDispatcher) { provenanceDao.deleteOrphans() }

    fun observeFieldCount(): Flow<Int> = provenanceDao.observeCount()

    // ================= 实体别名 =================

    /**
     * 把一批实体名（演员/厂牌/系列）归一入库，返回「归一后应展示的规范写法」。
     *
     * 入库是幂等 upsert：同一个 normalizedKey 永远只落一行，后续以先到的 canonicalKey 为准。
     * 返回 map 的 value 用 canonical 写法，调用方拿它替换原始字符串即可实现聚合。
     */
    suspend fun registerEntities(
        kind: EntityAliasKind,
        names: List<String>,
        sourceLabel: String,
        now: Long = System.currentTimeMillis()
    ): Map<String, String> = withContext(ioDispatcher) {
        if (names.isEmpty()) return@withContext emptyMap()
        val groups = EntityNameNormalizer.aliasGroups(names)
        val rows = mutableListOf<EntityAliasEntity>()
        val result = LinkedHashMap<String, String>()
        groups.forEach { group ->
            val canonicalDisplay = EntityNameNormalizer.canonicalFrom(group.first())
            val canonicalKey = EntityNameNormalizer.normalize(group.first())
            group.forEach { name ->
                val normalized = EntityNameNormalizer.normalize(name)
                if (normalized.isNotBlank()) {
                    rows += EntityAliasEntity(
                        kind = kind.storageKey,
                        normalizedKey = normalized,
                        displayName = EntityNameNormalizer.canonicalFrom(name),
                        canonicalKey = canonicalKey,
                        sourceLabel = sourceLabel,
                        updatedAtMillis = now
                    )
                }
                result[name] = canonicalDisplay
            }
        }
        if (rows.isNotEmpty()) aliasDao.upsert(rows)
        result
    }

    /** 读回某类实体的全部别名，供「演员聚合」「筛选器去重」使用。 */
    suspend fun aliasesOf(kind: EntityAliasKind): Map<String, String> = withContext(ioDispatcher) {
        aliasDao.getByKind(kind.storageKey).associate { it.normalizedKey to it.displayName }
    }

    fun observeAliasCount(): Flow<Int> = aliasDao.observeCount()

    data class ProvenanceWrite(
        val field: MetadataFieldKey,
        val value: String?,
        val authority: MetadataAuthority,
        val sourceLabel: String,
        val confidence: Double = 1.0
    )

    private fun preview(value: String?): String? {
        val trimmed = value?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        return if (trimmed.length <= PREVIEW_LIMIT) trimmed else trimmed.take(PREVIEW_LIMIT) + "…"
    }

    companion object {
        const val MANUAL_SOURCE_LABEL: String = "用户编辑"
        private const val PREVIEW_LIMIT = 120
    }
}
