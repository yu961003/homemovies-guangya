package com.example.localmovielibrarz.ui.shared

import com.example.localmovielibrarz.data.local.CloudFolderBatchTaskEntity
import com.example.localmovielibrarz.data.local.CloudFolderBatchTaskStatus

/**
 * 网盘文件夹批量任务「现在到底跑到哪了」。
 *
 * 为什么要单独判形态而不是直接拿 `processed / queued` 算百分比：
 * `queuedVideos` 在任务的两个阶段里含义不同——
 *
 * 1. **收集阶段**（先递归读子文件夹、把候选攒齐）：`queuedVideos` 只等于
 *    `queuedCandidates + collectionSkipped`，而 `processedVideos` 同样等于
 *    `outcomes.size + collectionSkipped`。这两个数在收集期是**一起从 0 长起来的**，
 *    直接相除会得到一个恒为 100% 的假进度条，直到收集结束才突然跌回去。
 * 2. **处理阶段**（`summary.queuedCandidates = candidates.size` 之后）：分母固定，
 *    分子一格一格涨，此时百分比才有意义。
 *
 * 两个阶段在数据库里没有独立字段（加列要动 Room schema 版本），所以这里用
 * `queued == 0 || processed >= queued` 粗判「分母还没定下来」——它的代价是：
 * 处理阶段真正跑到最后一格、以及刮削结算等待期，也会被归进「未定」，于是那几秒
 * 显示的是不定态进度条而不是 100%。宁可显示「还在收尾」也不要显示一个看起来
 * 已完成、实际还在跑的数字。
 */
sealed interface FolderBatchProgress {
    /** 分母未知：显示不定态进度条 + `label` 说明在干什么。 */
    data class Indeterminate(val label: String) : FolderBatchProgress

    /** 分母已定：`ratio` 在 0f..1f 之间。 */
    data class Determinate(val completed: Long, val total: Long, val ratio: Float) : FolderBatchProgress
}

/** 单条任务的行内进度（设置页每行一个）。 */
fun CloudFolderBatchTaskEntity.folderBatchProgress(): FolderBatchProgress {
    if (status == CloudFolderBatchTaskStatus.Completed.name) {
        return FolderBatchProgress.Determinate(
            completed = queuedVideos.toLong(),
            total = queuedVideos.toLong(),
            ratio = 1f
        )
    }
    val total = queuedVideos.toLong()
    val completed = processedVideos.coerceAtMost(queuedVideos).toLong()
    if (total <= 0L) {
        val scanning = currentPath?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        return FolderBatchProgress.Indeterminate(
            label = if (scanning != null) "正在扫描 $scanning" else "正在读取文件夹"
        )
    }
    if (completed >= total) {
        // 收集期恒等、或处理已到最后一格但刮削还没结算 —— 都不能报 100%。
        return FolderBatchProgress.Indeterminate(
            label = if (status == CloudFolderBatchTaskStatus.Running.name) "正在收尾（等待刮削结算）" else "等待继续"
        )
    }
    return FolderBatchProgress.Determinate(
        completed = completed,
        total = total,
        ratio = (completed.toFloat() / total.toFloat()).coerceIn(0f, 1f)
    )
}

/**
 * 多条任务的汇总进度（设置页总览、云盘浏览页面板共用）。
 *
 * 汇总规则：只要有一条还没定下分母，整体就不给百分比——把「未知」和「已算出」
 * 混在一个分母里加总，得到的百分比既不代表任务数也不代表影片数。
 */
fun List<CloudFolderBatchTaskEntity>.folderBatchAggregateProgress(): FolderBatchProgress {
    val unfinished = filter { it.status != CloudFolderBatchTaskStatus.Completed.name }
    if (unfinished.isEmpty()) {
        val done = size.toLong()
        return FolderBatchProgress.Determinate(completed = done, total = done, ratio = 1f)
    }
    val total = unfinished.sumOf { it.queuedVideos.toLong() }
    val completed = unfinished.sumOf { it.processedVideos.coerceAtMost(it.queuedVideos).toLong() }
    val allDeterminate = unfinished.all { task ->
        task.queuedVideos > 0 && task.processedVideos.coerceAtMost(task.queuedVideos) < task.queuedVideos
    }
    if (!allDeterminate || total <= 0L) {
        return FolderBatchProgress.Indeterminate(
            label = "正在收集候选视频（已完成 $completed，总数待定）"
        )
    }
    return FolderBatchProgress.Determinate(
        completed = completed,
        total = total,
        ratio = (completed.toFloat() / total.toFloat()).coerceIn(0f, 1f)
    )
}
