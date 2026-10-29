package com.example.localmovielibrarz.cloudguangya

import com.example.localmovielibrarz.ui.guangya.BatchNameResolver
import com.example.localmovielibrarz.ui.guangya.renameNameKey
import com.example.localmovielibrarz.ui.guangya.sortedForResolve
import com.example.localmovielibrarz.ui.guangya.validateGeneratedName
import com.example.localmovielibrarz.ui.guangya.visualizeInvisibleChars
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** 光鸭云盘浏览业务编排（镜像 Cloud115Client 薄封装）。 */
class GuangyaBrowserRepository(
    private val apiClient: GuangyaApiClient
) {
    /** parentId="" 为根目录（115 用 Long 类型的 ROOT_CID，光鸭用空串，P2-3）。 */
    suspend fun listFiles(parentId: String): List<GuangyaFileItem> = withContext(Dispatchers.IO) {
        apiClient.listFiles(parentId)
    }

    /** 列出某目录下的一级子文件夹（离线下载目标文件夹选择）。 */
    suspend fun listFolders(parentId: String): List<GuangyaFileItem> = withContext(Dispatchers.IO) {
        apiClient.listFolders(parentId)
    }

    /** 全局搜索文件夹（离线下载目标文件夹搜索）。 */
    suspend fun searchFolders(keyword: String): List<GuangyaFileItem> = withContext(Dispatchers.IO) {
        apiClient.searchFolders(keyword)
    }

    /** 需求 2：全局搜索文件+目录（列表页搜索）。 */
    suspend fun searchFiles(keyword: String): List<GuangyaFileItem> = withContext(Dispatchers.IO) {
        apiClient.searchFiles(keyword)
    }

    /**
     * 单批回收（≤50）：提交 + 等终态。供执行器与外部逐批调用。
     * 不加 withContext(Dispatchers.IO)——trashItems/waitTask 内部已通过
     * postUserres → withContext(Dispatchers.IO) 执行，不会阻塞主线程。
     */
    suspend fun trashItemsBatch(batch: List<String>) {
        val taskId = apiClient.trashItems(batch)
        apiClient.waitTask(taskId)
    }

    /** 批量移入回收站：分批提交 delete_file 并等待每批任务终态；部分失败聚合报错。 */
    suspend fun recycleFiles(fileIds: List<String>) {
        if (fileIds.isEmpty()) return
        var ok = 0; var fail = 0; var lastError: String? = null
        fileIds.chunked(50).forEach { batch ->
            runCatching { trashItemsBatch(batch) }.onSuccess { ok += batch.size }
                .onFailure { fail += batch.size; lastError = it.message }
        }
        // 改抛 GuangyaApiException——若抛 IOException，ViewModel 的错误映射
        // （is IOException -> "网络连接失败"）会把业务失败误标为网络问题
        if (fail > 0) throw GuangyaApiException("回收站部分失败：成功 $ok / 失败 $fail" +
            (lastError?.let { "（$it）" } ?: ""))
    }

    /** 批量移动：分批提交并等待终态。 */
    suspend fun moveFiles(fileIds: List<String>, targetParentId: String) {
        if (fileIds.isEmpty()) return
        fileIds.chunked(50).forEach { batch ->
            val taskId = apiClient.moveItems(batch, targetParentId)
            apiClient.waitTask(taskId)
        }
    }

    /** 重命名（同步）。 */
    suspend fun renameFile(fileId: String, newName: String) = apiClient.renameItem(fileId, newName)

    /**
     * 需求 3：批量重命名文件/目录（扩展名由调用方保留）。
     *
     * 第十三轮 G-1 修复（两阶段临时名中转，取代 F-1 读后校验-补偿模型）：
     * 统一模型——服务端写操作后存在秒级「传播窗口」黑盒；窗口内读/写都不可信。
     * 一切历史失败都发生在依赖链上（目标名 = 前序项的旧名，窗口内该名占用状态不确定）。
     * 破局点：把「有依赖的改名」分解成两次**无冲突面**的改名：
     *   依赖项先改到互不冲突的临时名（零冲突面 → 受理即执行）→ 等传播窗口（3s，
     *   此时读已可信）→ 临时名改目标名（目标名唯一占用者是自己的临时名，零冲突面）。
     * 无依赖项直改（从未失败的安全路径，节奏 1200ms 不变，无 delay / 无临时名）。
     *
     * 配套 G-2：本地校验失败标注「本地校验：」前缀；
     *        G-3①：safeRenameItem 发送前硬 trim（覆盖一切上游漏 trim 的实现偏差）；
     *        G-4：整体移除 F-1 校验-补偿循环 / PendingConfirm / 终局「未生效」判死。
     */
    suspend fun batchRenameFiles(
        renames: List<Pair<String, String>>,
        parentId: String = ""
    ): BatchRenameResult {
        val failed = mutableListOf<BatchRenameFailure>()
        val succeeded = mutableListOf<Pair<String, String>>()
        var autoSuffixed = 0
        var retried = 0   // G-1 模型下不再有补偿重试，恒为 0（保留字段兼容 ViewModel 消费）

        // ---- 目录快照（非根目录才有 parentId，用于冲突判定与窗口后校验）----
        val namesById = if (parentId.isNotBlank()) {
            runCatching { apiClient.listFiles(parentId).associate { it.fileId to it.name } }
                .getOrDefault(emptyMap())
        } else emptyMap()

        // ---- ★ G-1 依赖项判定：目标名命中「任何待改名文件的旧名」（规范化比对，排除自身）----
        val (independent, dependent) = splitIndependentDependent(renames, namesById)

        // ---- ★ 需求 4 修复（B1）：目录既有名 = 目录现有名 − 本批将改走的旧名 ----
        // 语义严格区分：
        //   dirExistingNames  —— 目录里「别人」的文件，撞上才真该加 (1)；
        //   batchClaimedNames —— 本批同伴刚生成的新名，撞上只说明「收敛成同名」，属批内问题，
        //                       必须走 [BatchNameResolver] 统一消解，绝不能让同伴给同伴加序号。
        // 旧实现把两者塞进同一个 conflictSet 并逐条喂入，导致 N 个文件删同一字符串时
        // 第 2 项起被无端加 (1)(2)(3)…（用户反馈：「怎么可能重名？」）。
        val renamingOldNames = renames.mapNotNull { namesById[it.first] }.toSet()
        val dirExistingNames = (namesById.values - renamingOldNames).toSet()

        // ---- ★ 需求 4：批内统一消解（与预览层同源，保证所见即所得）----
        // 顺序经 [sortedForResolve] 统一约定，与预览层完全一致，避免序号互换。
        val resolvedIndependent = BatchNameResolver.resolveWithFlags(
            sortedForResolve(independent.map { (id, want) -> Triple(id, namesById[id] ?: "", want) }),
            dirExistingNames
        ).associateBy { it.fileId }

        // 目录既有名 + 本批已占用名（供两阶段临时名与依赖项阶段二使用）
        val conflictSet = dirExistingNames.toMutableSet()

        // ---- 第 1 段：无依赖项直改（从未失败的安全路径，无 delay / 无临时名）----
        independent.forEach { (fileId, newName) ->
            val oldName = namesById[fileId]
            // 取批内消解后的最终名（未被消解时 = 期望名），再交给落盘路径
            val planned = resolvedIndependent[fileId]?.final ?: newName
            runIndependentRename(fileId, planned, oldName, conflictSet, failed)?.let { finalName ->
                if (finalName != newName) autoSuffixed++
                succeeded.add(fileId to finalName)
            }
        }

        if (dependent.isEmpty()) {
            return BatchRenameResult(
                succeeded = succeeded,
                failed = failed,
                autoSuffixed = autoSuffixed,
                retried = retried
            )
        }

        // ---- 第 2 段：依赖项阶段一 → 全部改到临时名（★ 零冲突面）----
        // 临时名三不撞：不撞目录现有名（namesById.values）、不撞全部目标名、彼此不同；
        // 且必须过 validateGeneratedName（对齐参考实现 batchRename.ts:403——临时名同样校验）。
        val occupied = (namesById.values + renames.map { it.second })
            .mapTo(mutableSetOf()) { it.renameNameKey() }

        data class Stage1(val fileId: String, val tempName: String, val targetName: String)

        val atTemp = mutableSetOf<String>()   // 已成功改到临时名的 fileId
        val stage1 = mutableListOf<Stage1>()

        dependent.forEach { (fileId, targetName) ->
            val oldName = namesById[fileId]
            if (oldName == null) {
                failed.add(BatchRenameFailure(fileId, targetName, "本地校验：找不到原文件名"))
                return@forEach
            }
            val temp = uniqueTempName(oldName, occupied) ?: run {
                failed.add(BatchRenameFailure(fileId, targetName, "临时名生成失败（冲突过多）"))
                return@forEach
            }
            // 临时名同样校验（对齐参考实现）
            validateGeneratedName(temp)?.let { reason ->
                failed.add(BatchRenameFailure(fileId, targetName, "本地校验（临时名）：$reason"))
                return@forEach
            }
            // 零冲突面：受理即执行
            if (safeRenameItem(fileId, temp)) {
                occupied.add(temp.renameNameKey())
                atTemp.add(fileId)
                stage1.add(Stage1(fileId, temp, targetName))
            } else {
                conflictSet.add(oldName)   // 仍占旧名，回填冲突集
                failed.add(BatchRenameFailure(fileId, targetName, "中转至临时名失败（服务端拒绝）"))
            }
        }

        // ---- 第 3 段：等传播窗口，校验临时名（此时读已可信——窗口后索引稳定）----
        delay(3_000)
        val current = runCatching { apiClient.listFiles(parentId).associate { it.fileId to it.name } }
            .getOrNull()
        if (current != null) {
            // 阶段一虚假成功 → 重发（临时名零冲突面，重试必成）
            stage1.filter { s -> current[s.fileId]?.renameNameKey() != s.tempName.renameNameKey() }
                .forEach { s ->
                    if (safeRenameItem(s.fileId, s.tempName)) {
                        atTemp.add(s.fileId)
                    } else {
                        atTemp.remove(s.fileId)
                        failed.add(BatchRenameFailure(s.fileId, s.targetName,
                            "中转至临时名失败（重试后仍被服务端拒绝）"))
                    }
                }
        }
        // listFiles 失败：乐观信任阶段一受理结果，直接进第 4 段

        // ---- 第 4 段：阶段二 → 临时名改目标名（★ 零冲突面：所有旧名已实际腾出，
        //      目标名的唯一占用者是自己的临时名（同 fileId），服务端无冲突可判）----
        // ★ 需求 4：依赖项同样走 [BatchNameResolver] 统一消解——同一批内若多个依赖项收敛同名，
        //   也必须按稳定顺序分配序号，而不是像旧实现那样「谁先执行谁不加、后来的加 (1)」。
        val resolvedDependent = BatchNameResolver.resolveWithFlags(
            sortedForResolve(stage1.map { Triple(it.fileId, namesById[it.fileId] ?: "", it.targetName) }),
            dirExistingNames
        ).associateBy { it.fileId }

        stage1.forEach { s ->
            if (s.fileId !in atTemp) return@forEach   // 阶段一/重试未成功，跳过
            val finalTarget = resolvedDependent[s.fileId]?.final ?: s.targetName
            if (finalTarget != s.targetName) autoSuffixed++
            if (safeRenameItem(s.fileId, finalTarget)) {
                succeeded.add(s.fileId to finalTarget)
                conflictSet.add(finalTarget)
            } else {
                // ★ 临时名残留防御：文件停留在临时名，不回滚、不静默丢弃；计入 failed 并提示手动改名
                conflictSet.add(s.tempName)
                failed.add(BatchRenameFailure(s.fileId, s.targetName,
                    "已中转至临时名「${visualizeInvisibleChars(s.tempName)}」，请手动改名"))
            }
        }

        return BatchRenameResult(
            succeeded = succeeded,
            failed = failed,
            autoSuffixed = autoSuffixed,
            retried = retried
        )
    }

    /**
     * G-3①：重命名统一入口（Repository 层最后防线）。
     * 发送前硬 trim 首尾空格（覆盖一切上游漏 trim 的实现偏差），并打点告警日志，
     * 杜绝不可见空格以外泄到服务端（服务端文案为「文件名不能…」，与本地「名称不能…」一字之差）。
     * 返回是否受理成功（runCatching 兜底网络/业务异常，不向上抛）。
     */
    private suspend fun safeRenameItem(fileId: String, name: String): Boolean {
        val safeName = name.trim()
        if (safeName != name) {
            Log.w("GuangyaBatchRename", "发送前清除不可见首尾空格：'$name' → '$safeName'")
        }
        return runCatching { apiClient.renameItem(fileId, safeName) }.isSuccess
    }

    /**
     * 无依赖项单次改名（直改安全路径）。返回最终名；校验/受理失败则记 failed 并返回 null。
     *
     * ★ 需求 4：`finalName` 由调用方经 [BatchNameResolver] 批内统一消解后传入，
     *   **此处不再二次 dedupe**。旧实现在这里对 `conflictSet`（已被逐条喂入本批同伴新名）
     *   再做一次 dedupeNameAgainst，正是「同伴给我加序号」的缺陷根源。
     */
    private suspend fun runIndependentRename(
        fileId: String,
        finalName: String,
        oldName: String?,
        conflictSet: MutableSet<String>,
        failed: MutableList<BatchRenameFailure>
    ): String? {
        if (fileId.isBlank() || finalName.isBlank()) {
            failed.add(BatchRenameFailure(fileId, finalName, "本地校验：名称为空"))
            return null
        }
        validateGeneratedName(finalName)?.let { reason ->
            failed.add(BatchRenameFailure(fileId, finalName, "本地校验：$reason"))
            return null
        }
        // ★ 零冲突面：受理即执行。统一经 safeRenameItem（G-3① 发送前硬 trim）
        return if (safeRenameItem(fileId, finalName)) {
            conflictSet.add(finalName)
            finalName
        } else {
            oldName?.let { conflictSet.add(it) }   // 失败则仍占旧名，回填冲突集
            failed.add(BatchRenameFailure(fileId, finalName, "重命名请求被服务端拒绝"))
            null
        }
    }

    /**
     * 解析离线下载链接（磁力链/.torrent/ed2k）→ 服务端规范化资源。
     *
     * H 轮：不再丢弃 resolve_res 的返回。解析出的 [GuangyaResolvedResource.url]（data.url）
     * 才是 create_task 应该提交的链接；BT 资源还会带出 fileIndexes。
     * 接口本身失败（网络 / 信封错误）仍照旧抛出，由提交引擎记为「解析失败」。
     */
    suspend fun resolveOfflineResource(url: String): GuangyaResolvedResource =
        GuangyaOfflineResolve.parse(url, apiClient.resolveOfflineUrl(url))

    /**
     * 创建云下载任务（离线下载）。
     * @param resolved resolve_res 的解析结果（用其规范化 url；BT 时带 fileIndexes 分片提交）
     */
    suspend fun createOfflineTask(
        resolved: GuangyaResolvedResource,
        parentId: String,
        fileIndexes: List<Int> = resolved.fileIndexes
    ) = apiClient.createOfflineTask(resolved.url, parentId, fileIndexes)

    /** 创建云下载任务（离线下载）——直接使用链接的简便重载。 */
    suspend fun createOfflineTask(url: String, parentId: String) = apiClient.createOfflineTask(url, parentId)
}

/** 单条批量重命名失败信息（reason 透传；本地校验失败带「本地校验：」前缀，服务端拒绝保持原文）。 */
data class BatchRenameFailure(
    val fileId: String,
    val newName: String,
    val reason: String
)

/** 批量重命名整体结果：逐条容错，一条被拒不中断队列其余请求。 */
data class BatchRenameResult(
    val succeeded: List<Pair<String, String>>, // (fileId, 最终生效名)
    val failed: List<BatchRenameFailure>,
    val autoSuffixed: Int = 0, // ★ A-3：因撞名被自动加后缀的项数
    val retried: Int = 0       // ★ F-2 遗留字段：G-1 模型下恒为 0（补偿重试已移除，保留以兼容 UI 消费）
)

/**
 * G-1 依赖项拆分：目标名命中「任何其他待改名文件的旧名」即判为依赖项（窗口内该名的
 * 占用状态不确定，必须走临时名中转）；命中自身旧名（等价无改名）按无依赖直改处理。
 *
 * 与 [orderRenamesByDependency] 一样是纯函数并放在文件顶层：本函数只依赖入参与
 * `renameNameKey`，无仓库状态，顶层可见性让回归测试可以直接调用（Kotlin 测试源集
 * 无法访问成员函数的 internal 可见性之外的实例方法）。
 */
internal fun splitIndependentDependent(
    renames: List<Pair<String, String>>,
    namesById: Map<String, String>
): Pair<List<Pair<String, String>>, List<Pair<String, String>>> {
    val allOldKeys = renames.mapNotNull { (id, _) -> namesById[id]?.renameNameKey() }.toSet()
    return renames.partition { (id, newName) ->
        val selfKey = namesById[id]?.renameNameKey()
        val others = allOldKeys - (selfKey ?: "")
        newName.renameNameKey() !in others
    }
}

/**
 * D-1：依赖拓扑排序（Kahn 贪心）——保留为纯工具函数（互换名/环检测仍有价值，且回归测试覆盖）。
 * 每轮挑出「目标名不占用任何待执行项旧名」的项；剩余无法挑出的即环形依赖（A↔B 互换名）。
 * 返回 (有序执行列表, 环成员)。G-1 的实际依赖判定已移至 splitIndependentDependent。
 */
internal fun orderRenamesByDependency(
    renames: List<Pair<String, String>>,
    namesById: Map<String, String>
): Pair<List<Pair<String, String>>, List<Pair<String, String>>> {
    val pending = renames.toMutableList()
    val ordered = mutableListOf<Pair<String, String>>()
    while (pending.isNotEmpty()) {
        val pendingOldKeys = pending.mapNotNull { (id, _) -> namesById[id]?.renameNameKey() }.toSet()
        val ready = pending.filter { (_, newName) -> newName.renameNameKey() !in pendingOldKeys }
        if (ready.isEmpty()) return ordered to pending.toList() // 剩余全为环
        ordered.addAll(ready)
        pending.removeAll(ready.toSet())
    }
    return ordered to emptyList()
}

/** D-1 / G-1 临时名生成：原名 stem + "tmp-ren-n-" 前缀，确保不撞 occupiedKeys（n 递增，上限防御），且过本地校验。 */
internal fun uniqueTempName(oldName: String, occupiedKeys: MutableSet<String>): String? {
    val ext = oldName.substringAfterLast('.', "")
    val stem = if (ext.isNotEmpty()) oldName.substringBeforeLast('.') else oldName
    var n = 1
    while (n <= 1000) {
        val next = if (ext.isNotEmpty()) "tmp-ren-$n-$stem.$ext" else "tmp-ren-$n-$stem"
        if (next.renameNameKey() !in occupiedKeys && validateGeneratedName(next) == null) return next
        occupiedKeys.add(next.renameNameKey())  // 生成失败候选也占位，避免下个依赖项重复
        n++
    }
    return null
}
