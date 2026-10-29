package com.example.localmovielibrarz.scraper

/**
 * 桃链（peach 刮削链）：默认刮削路径。
 *
 * 为什么单独成类，而不是把 `StrmScrapeRepository.scrapeWithFallback` 原地改写成两套分支：
 * 那条函数已经承载了 legacy 链的全部历史行为（冷却跳过、throttle、重试、番号一致性校验、
 * 层级合并、结算），再塞进第二套语义会让「哪一行属于哪条链」无法阅读，而且 legacy 的
 * 既有测试会一起失去意义。这里的做法是把**控制流**（问谁、问几家、何时收尾、何时放弃）
 * 抽到本类，把**单次取数**（throttle / 重试 / 校验 / 记账）留给
 * [PeachSourceExecutor] 的实现——后者由 repository 提供，直接复用它的既有私有方法。
 *
 * 结果是三态，而不是「成功 / 失败」两态，因为调用方需要区分「该不该回退 legacy」：
 * - [PeachChainResult.Success]：拿到元数据。
 * - [PeachChainResult.NoResult]：链跑完了但**一行可用元数据都没有**（全部源失败，或结果为空）。
 *   按用户定的规则，这一态要回退 legacy 链——兜底的意义就在这里。
 * - [PeachChainResult.Unavailable]：链根本没跑起来（一个来源都没配，且没有任何来源被按类型裁掉）。
 *   同样回退 legacy：那时 legacy 也会立刻失败，回退只是让错误信息更完整。
 *
 * 注意「链跑过」与「有结果」是两件事：NoResult 说明**问过了、没有**，回退时会由 legacy 的冷却
 * 判断接手（桃链记账的失败会在 legacy 侧生效，冷却中的站不会被重打一遍）。
 */
class PeachScrapeChain(
    private val executor: PeachSourceExecutor,
    private val missCache: ScrapeMissCache,
    private val budget: ScrapeBudget = ScrapeBudget()
) {

    /**
     * 跑完这条链。
     *
     * @param number 查询番号（已按数字前缀规则改写后的最终形态）。
     * @param plan 路由结果；[ScrapeRouteRules.RoutePlan.sources] 已按层级排好序。
     * @param wantedScalars 是否要凑齐必填标量。false 即薄请求（只要封面/标签这类字段），
     *   第一家给到任意非空值就收工。
     */
    suspend fun run(
        number: String,
        plan: ScrapeRouteRules.RoutePlan,
        wantedScalars: Boolean = true
    ): PeachChainResult {
        if (plan.sources.isEmpty()) {
            val reason = plan.fallbackReason ?: "没有可用的刮削来源"
            executor.log("桃链未启动：$reason")
            // 有 skipped 说明是「按类型裁空」（kmib/other 之类）——这是终态，不是「链坏了」。
            return if (plan.skipped.isEmpty()) {
                PeachChainResult.Unavailable(reason)
            } else {
                PeachChainResult.NoResult(reason)
            }
        }

        val failures = mutableListOf<Pair<ScrapeSource, ScrapeFailure>>()
        var draft: Pair<ScrapeSource, ScrapedMovieInfo>? = null
        var askedCount = 0
        var cooledCount = 0
        var missSkippedCount = 0
        var stopReason: String? = null

        for ((index, source) in plan.sources.withIndex()) {
            // ① 负缓存：这家站 7 天内明确说过没有这个番号，直接跳过。
            //    排在冷却判断之前——缓存命中连「该不该等冷却」都不用算了。
            if (missCache.isMiss(source, number)) {
                missSkippedCount += 1
                executor.log("桃链跳过 ${source.label}：7 天前查过这个番号，站内没有（负缓存命中）")
                continue
            }

            // ② 冷却：与 legacy 同口径。排到末尾只保证最后才打，不保证不打。
            val coolingRemaining = executor.cooldownRemaining(source)
            if (coolingRemaining > 0L) {
                val minutes = (coolingRemaining / 60_000L).coerceAtLeast(1L)
                val kind = executor.lastFailure(source)?.kind ?: ScrapeFailureKind.Unknown
                failures += source to ScrapeFailure(kind, "正在冷却，剩余 $minutes 分钟")
                cooledCount += 1
                executor.log("桃链跳过 ${source.label}：来源冷却中，剩余 $minutes 分钟（${kind.name}）")
                continue
            }

            // ③ 预算：每次真正要发请求前检查，而不是每轮循环检查——
            //    缓存命中与冷却跳过都不消耗网络预算。
            val budgetStop = budget.exhaustedReason()
            if (budgetStop != null) {
                stopReason = budgetStop
                executor.log("桃链收尾：$budgetStop")
                break
            }

            executor.log(
                "桃链尝试 ${index + 1}/${plan.sources.size}：${source.label}，number=$number" +
                    "（层级 ${ScrapeAuthority.tierOf(source).label}）"
            )
            budget.markStarted()
            val attempt = executor.fetch(source, number)
            if (attempt.requestAttempted) {
                askedCount += 1
                budget.requestUsed()
                // 字节闸门：与请求闸门同一时机记账——只有真发了请求才可能读到正文。
                budget.bytesUsed(attempt.responseBytes)
            }

            when (attempt) {
                is PeachAttempt.Success -> {
                    // 逐字段判胜（peach 的 `field_owners` 口径），而不是整份挑一份：
                    // 一份结果里 title 可能来自官网、actors 来自 FC2 索引，互不干扰。
                    draft = PeachFieldMerge.merge(draft, source to attempt.info)
                }

                is PeachAttempt.Failure -> {
                    failures += source to attempt.failure
                    // 档位只用于说清楚「这家为什么没给」：永久失败（站点改版）与
                    // 「被挡了」在用户眼里都是失败，但排障时的下一步完全不同。
                    attempt.reason?.let { reason ->
                        executor.log(
                            "桃链失败 ${source.label}：peach 档位=${reason.name}" +
                                "（${reason.kind.label}${if (reason.permanent) "，永久" else ""}" +
                                "${if (reason.blocksWholeSource) "，停整源" else ""}）"
                        )
                    }
                    // 只有「站内确实没有」才进负缓存。号码不一致的丢弃在 executor 里被归成
                    // NotFound（口径正确：那家回答不出这个番号），但**不能**写进负缓存——
                    // 它说明的是「这家会答错片」，不是「这家没有这部」，记进去等于 7 天内
                    // 静默跳过一条真实的缺口。
                    if (attempt.failure.kind == ScrapeFailureKind.NotFound && attempt.numberMatched) {
                        missCache.rememberMiss(source, number)
                    }
                }
            }

            // ④ 结算（peach 的 settles）：与 legacy 共用同一套判据实现，但用愿意接受薄请求的
            //    [ScrapeRouteRules.settlesPeach]。顺序上先判 settles 再判「还剩几家」——
            //    settles 需要知道后面还排着谁（第 ① 条要问「后面还有官方档吗」）。
            val merged = draft?.second
            val remaining = plan.sources.drop(index + 1)
            if (merged != null && ScrapeRouteRules.settlesPeach(merged, remaining, wantedScalars)) {
                stopReason = if (wantedScalars) {
                    val missing = ScrapeRouteRules.missingRequiredFields(merged)
                    if (missing.isEmpty()) {
                        "必填字段已齐"
                    } else {
                        "必填字段仍未齐（缺 ${missing.joinToString("、")}），但后面已没有能补的官方来源"
                    }
                } else {
                    "本次只要封面/标签，已拿到可用值"
                }
                executor.log("桃链结算：$stopReason，不再追问后续来源")
                break
            }
        }

        val accepted = draft
        if (accepted != null) {
            return PeachChainResult.Success(
                info = accepted.second.copy(
                    number = number,
                    source = accepted.second.source.ifBlank { accepted.first.label }
                ),
                source = accepted.first
            )
        }

        val summary = buildString {
            append("桃链未拿到元数据")
            stopReason?.let { append("（$it）") }
            append("：问了 $askedCount 家")
            if (cooledCount > 0) append("，$cooledCount 家在冷却")
            if (missSkippedCount > 0) append("，$missSkippedCount 家命中负缓存")
            if (failures.isNotEmpty()) append("；${ScrapeSourceChain.aggregateFailureReason(failures)}")
        }
        executor.log(summary)
        return PeachChainResult.NoResult(summary)
    }
}

/**
 * 桃链与真实抓取之间的唯一接缝。
 *
 * 这些能力目前都长在 `StrmScrapeRepository` 的私有方法里（throttle、重试、番号一致性校验、
 * 健康度记账、日志）。抽成接口而不是把实现搬进本包，是为了让本类保持**纯控制流**：
 * 单测可以用假实现覆盖「全部冷却 / 全部负缓存命中 / 预算耗尽 / 中途成功结算」这些在
 * 真机上极难复现的分支，而不必启动 OkHttp。
 */
interface PeachSourceExecutor {

    /** 该来源还要冷却多久（毫秒，0 表示可以打）。 */
    fun cooldownRemaining(source: ScrapeSource): Long

    /** 该来源最近一次失败；没有则 null。用于冷却跳过的日志。 */
    fun lastFailure(source: ScrapeSource): ScrapeFailure?

    /**
     * 向一家来源取一次数。
     *
     * 实现方负责：按来源改写数字前缀、throttle 等待与记账、传输层重试、番号一致性校验、
     * 成功/失败的健康度记账。本类只关心结果与「是否真的发了请求」。
     */
    suspend fun fetch(source: ScrapeSource, number: String): PeachAttempt

    fun log(message: String)
}

/** 单家来源的一次取数结果。 */
sealed interface PeachAttempt {

    /** 是否真的发出了网络请求。配置错误、提前跳过之类为 false，不计入预算。 */
    val requestAttempted: Boolean

    /**
     * 是否命中了「站里没有这个番号」。
     *
     * 只有 true 才允许写进负缓存。号码不一致被丢弃也归 [ScrapeFailureKind.NotFound]，
     * 但它表达的是「这家会答错片」而非「这家没有这部」，见 [PeachScrapeChain.run] 的注释。
     */
    val numberMatched: Boolean

    /**
     * 这次取数实际读进来多少字节的响应体（0 表示没发请求或没读到正文）。
     *
     * 由取数实现如实回报，喂给 [ScrapeBudget] 的字节闸门。漏报只会少算，
     * 不会误伤——见 [ScrapeBudget.bytesUsed] 的取舍说明。
     */
    val responseBytes: Long

    data class Success(
        val info: ScrapedMovieInfo,
        override val numberMatched: Boolean = true,
        override val requestAttempted: Boolean = true,
        override val responseBytes: Long = 0L
    ) : PeachAttempt

    data class Failure(
        val failure: ScrapeFailure,
        override val numberMatched: Boolean = false,
        override val requestAttempted: Boolean = true,
        override val responseBytes: Long = 0L,
        /**
         * peach 的十二档归类；老的八家源还没改抛 [PeachSourceFailure] 时会是 null。
         *
         * 单独带一档而不是复用 [ScrapeFailure] 的 `kind`：档位决定的是「这一家还值不值得在
         * 本次链里再考虑」（永久失败）与「要不要整源冷却」（验证页 / 封禁 / 地区限制），
         * 而 `kind` 只驱动按钮文案与重试判断。两者混用会让「站点改版」被当成暂时性失败
         * 反复重试，反过来又让「被 Cloudflare 挡了」只在按钮上显示一次。
         */
        val reason: PeachFailureReason? = null
    ) : PeachAttempt
}

/** 一条桃链跑完之后的三态结果。 */
sealed interface PeachChainResult {

    data class Success(val info: ScrapedMovieInfo, val source: ScrapeSource) : PeachChainResult

    /** 链跑完了但没有可用元数据：**要**回退 legacy（最终兜底）。 */
    data class NoResult(val reason: String) : PeachChainResult

    /** 链根本没跑起来：同样回退 legacy。 */
    data class Unavailable(val reason: String) : PeachChainResult
}