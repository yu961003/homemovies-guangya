package com.example.localmovielibrarz.scraper

/**
 * 来源链决策（参考 peach 的 source chain，但按鸭影的实际情况改了两处语义）。
 *
 * 鸭影原有的 `scrapeWithFallback` 是一个朴素的 for 循环：按配置顺序打，**第一个成功就返回**。
 * 这带来两个问题：
 *
 * 1. **薄结果被当成成功**。Javbus 之类站点能给标题，但经常没有演员/厂牌/封面；
 *    它排在前面时，后面的 DMM 根本不会被尝试，用户最终拿到的是「标题正确、其余空白」的影片。
 * 2. **失败原因被压成一句**。循环里每个失败都只往 failures 里塞一行字符串，最后
 *    `failures.lastOrNull()` 被当成整体原因——用户看到的是最后一个源的错误，而不是
 *    「所有源都被 403 挡了」这种真正有用的结论。
 *
 * peach 的做法是「按权威等级排序 + 硬阻断即停 + 字段级归属」，放到鸭影要改两处：
 * - **不同源之间不是同一个出口**，一个站点 403 不代表另一个站点也 403，所以这里不做
 *   「遇到 403 就整条链停」——那会让「只有 Javbus 被墙」变成「全部刮削失败」。
 *   真正该停的场景是「剩余所有源都在冷却中，打了也是白打」。
 * - **硬阻断收窄到「同一源」**：某个源被判为配置错误（未启用/地址非法）时，本轮的剩余
 *   尝试直接跳过它（本来冷却检查也会跳过，这里提前到决策层，省一次无意义等待）。
 */
object ScrapeSourceChain {

    /** 完成度门槛：低于它认为「结果偏薄」，值得继续问下一个源。 */
    const val DEFAULT_COMPLETENESS_THRESHOLD: Double = 0.7

    /**
     * 链序：配置顺序 + 前缀命中时把 MGStage 提到最前（沿用既有规则），
     * 再把「正在冷却」的源挪到末尾而不是直接删掉——它们仍可能在长时间任务中恢复，
     * 保留在末尾可以让「全部冷却」时给出完整的失败原因。
     */
    fun order(
        configured: List<ScrapeSource>,
        pinned: ScrapeSource?,
        coolingRemaining: (ScrapeSource) -> Long
    ): List<ScrapeSource> {
        val deduped = (listOfNotNull(pinned) + configured).distinct()
        val ready = deduped.filter { coolingRemaining(it) <= 0L }
        val cooling = deduped.filter { coolingRemaining(it) > 0L }
        return ready + cooling
    }

    /**
     * 一次尝试后的下一步。
     *
     * @param attempt 刚结束的那次尝试（成功带 info，失败带 failure）
     * @param remaining 链上还没打的源（已按 [order] 排好，且调用方已滤掉本轮跳过的）
     * @param coolingRemaining 查询某源剩余冷却毫秒数
     */
    fun decide(
        attempt: SourceAttempt,
        remaining: List<ScrapeSource>,
        coolingRemaining: (ScrapeSource) -> Long,
        completenessThreshold: Double = DEFAULT_COMPLETENESS_THRESHOLD
    ): ChainDecision {
        val runnableRemaining = remaining.filter { coolingRemaining(it) <= 0L }
        val info = attempt.info
        if (info != null) {
            val score = fieldCompleteness(info)
            return when {
                // 够好了：停。继续打只会拖慢刮削并增加被风控的概率。
                score >= completenessThreshold -> ChainDecision.Succeeded(
                    info = info,
                    source = attempt.source,
                    reason = "完成度 ${formatScore(score)} >= ${formatScore(completenessThreshold)}"
                )
                // 偏薄但还有可打的源：继续，但把这份结果当底稿（由调用方 merge）。
                runnableRemaining.isNotEmpty() -> ChainDecision.ContinueWith(
                    info = info,
                    reason = "完成度 ${formatScore(score)} 偏低，继续用 ${runnableRemaining.first().label} 补充"
                )
                // 偏薄且没源可打了：只能接受它——有结果总比报错强。
                else -> ChainDecision.Succeeded(
                    info = info,
                    source = attempt.source,
                    reason = "完成度 ${formatScore(score)} 偏低，但已无可尝试的来源"
                )
            }
        }

        val failure = attempt.failure ?: ScrapeFailure(ScrapeFailureKind.Unknown, "未知失败")
        return when {
            runnableRemaining.isNotEmpty() -> ChainDecision.ContinueWith(
                info = null,
                reason = "${attempt.source.label} 失败（${failure.kind.name}），改用 ${runnableRemaining.first().label}"
            )
            // 剩下全在冷却：这是唯一值得「整链停」的场景，因为再打只会加深封锁。
            remaining.isNotEmpty() -> ChainDecision.Abort(
                reason = buildString {
                    append(attempt.source.label)
                    append(" 失败（")
                    append(failure.kind.name)
                    append("）；其余来源正在冷却，本轮不再尝试")
                }
            )
            else -> ChainDecision.Abort(reason = "${attempt.source.label} 失败：${failure.message}")
        }
    }

    /**
     * 把多个源的结果合并成一份：**先到的非空值优先**。
     *
     * 与「后到的覆盖先到的」相反，是因为链序本身已经表达了偏好（配置顺序 + 前缀提升），
     * 后打的源只是用来补空位。字段级归属（`metadata.MetadataAuthority`）负责
     * 「用户手改过的不许被覆盖」，这里只处理「同一轮里多源拼装」。
     */
    fun merge(base: ScrapedMovieInfo?, extra: ScrapedMovieInfo): ScrapedMovieInfo {
        if (base == null) return extra
        return base.copy(
            number = base.number.ifBlank { extra.number },
            title = base.title.ifBlank { extra.title },
            originalTitle = base.originalTitle.ifBlank { extra.originalTitle },
            plot = base.plot.ifBlank { extra.plot },
            outline = base.outline.ifBlank { extra.outline },
            year = base.year.ifBlank { extra.year },
            premiered = base.premiered.ifBlank { extra.premiered },
            runtime = base.runtime.ifBlank { extra.runtime },
            studio = base.studio.ifBlank { extra.studio },
            publisher = base.publisher.ifBlank { extra.publisher },
            series = base.series.ifBlank { extra.series },
            directors = base.directors.ifEmpty { extra.directors },
            actors = base.actors.ifEmpty { extra.actors },
            actorImageUrls = if (base.actorImageUrls.isEmpty()) extra.actorImageUrls else base.actorImageUrls,
            genres = base.genres.ifEmpty { extra.genres },
            tags = base.tags.ifEmpty { extra.tags },
            rating = base.rating.ifBlank { extra.rating },
            trailer = base.trailer.ifBlank { extra.trailer },
            website = base.website.ifBlank { extra.website },
            source = base.source.ifBlank { extra.source },
            thumbUrl = base.thumbUrl.ifBlank { extra.thumbUrl },
            posterUrl = base.posterUrl.ifBlank { extra.posterUrl },
            fanartUrl = base.fanartUrl.ifBlank { extra.fanartUrl },
            thumbImageUrls = base.thumbImageUrls.ifEmpty { extra.thumbImageUrls },
            posterImageUrls = base.posterImageUrls.ifEmpty { extra.posterImageUrls }
        )
    }

    /**
     * 字段完成度：只看「用户真正会在列表页/详情页注意到」的六项。
     *
     * 不把 plot/runtime/rating 计入，是因为它们在多数源上都是可选的，计入会把
     * 正常结果误判为「薄」从而触发不必要的二次请求；也不把 number 计入——它来自文件名，
     * 与来源无关，计入会让每个源的完成度都虚高。
     */
    fun fieldCompleteness(info: ScrapedMovieInfo): Double {
        val checks = listOf(
            info.title.isNotBlank(),
            info.actors.isNotEmpty(),
            info.genres.isNotEmpty(),
            info.studio.isNotBlank(),
            info.posterUrl.isNotBlank() || info.posterImageUrls.isNotEmpty(),
            info.year.isNotBlank()
        )
        return checks.count { it }.toDouble() / checks.size
    }

    /** 把零散的失败聚合成一条「用户能据此行动」的结论。 */
    fun aggregateFailureReason(failures: List<Pair<ScrapeSource, ScrapeFailure>>): String {
        if (failures.isEmpty()) return "刮削失败（没有可用来源）"
        val kinds = failures.groupBy { it.second.kind }
        val allBlocked = failures.all {
            it.second.kind == ScrapeFailureKind.Forbidden ||
                it.second.kind == ScrapeFailureKind.Blocked ||
                it.second.kind == ScrapeFailureKind.RateLimited
        }
        if (allBlocked) {
            return "所有来源都被站点拦截（403/验证页/限流），出口 IP 可能被风控；建议稍后重试或更换网络：${
                kinds.entries.joinToString("；") { (kind, list) ->
                    "${kind.name}×${list.size}"
                }
            }"
        }
        val allNotFound = failures.all { it.second.kind == ScrapeFailureKind.NotFound }
        if (allNotFound) {
            return "所有来源都搜不到该番号（有可能是番号错误或站点未收录），无需重试"
        }
        return failures.joinToString("；") { (source, failure) ->
            "${source.label}[${failure.kind.name}]: ${failure.message}"
        }
    }

    private fun formatScore(value: Double): String =
        ((value * 100).toInt() / 100.0).toString().let { raw ->
            if (raw.length <= 4) raw else raw.substring(0, 4)
        }
}

/**
 * 单次来源尝试的结构化结果：成功带 info，失败带 failure，二选一。
 *
 * [numberMatched] 只在失败时有意义：它区分「这家站确实没有这个番号」与「这家站答了别的片」。
 * 两者都归 [ScrapeFailureKind.NotFound]（对用户都是「这家没拿到」），但只有前者能进负缓存
 * ——把「会答错片」记成「没有这部」，等于 7 天内静默跳过一条真实缺口。
 */
data class SourceAttempt(
    val source: ScrapeSource,
    val info: ScrapedMovieInfo? = null,
    val failure: ScrapeFailure? = null,
    val numberMatched: Boolean = true,
    /**
     * 这一次取数实际读进来多少字节响应体（0 表示没读或没记账）。
     *
     * 只给桃链的字节闸门用（[ScrapeBudget]）。legacy 链不读它，所以默认 0 不会改变既有行为。
     */
    val responseBytes: Long = 0L,

    /**
     * 失败时按 peach 十二档归的类；成功或没归出来时为 null。
     *
     * 为什么不直接用 [failure] 的 `kind`：那是本项目的九档口径（驱动冷却与按钮文案），
     * 而桃链判「这家是不是该整源停」用的是 peach 的档位语义——比如 `ParseError` 在 peach 里
     * 是**永久**失败（站点改版了，重试没意义），在本项目的九档里只是「不可重试」，
     * 两者在「要不要把这家排到队尾」上的结论并不总是一致。分类在
     * `fetchSingleSource` 拿到原始异常那一刻做（那里才有 Throwable），这里只负责往下带。
     */
    val peachReason: PeachFailureReason? = null
)

sealed class ChainDecision {
    data class Succeeded(
        val info: ScrapedMovieInfo,
        val source: ScrapeSource,
        val reason: String
    ) : ChainDecision()

    /** 继续下一个源；[info] 非空表示这一轮已有可用底稿，需要 merge。 */
    data class ContinueWith(
        val info: ScrapedMovieInfo?,
        val reason: String
    ) : ChainDecision()

    data class Abort(val reason: String) : ChainDecision()
}
