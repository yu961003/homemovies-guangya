package com.example.localmovielibrarz.scraper

/**
 * 取页层的失败 → 链上的失败档位。
 *
 * 为什么要单独一层：`SourcePageFetcher` 抛的是 [SourceFetchException]（带 `browserBlocked`
 * 这类通道信息），而链上认的是 [PeachSourceFailure]（带 peach 的 `FailureReason`）。
 * 三个新来源都要做这次翻译，写在各自 scraper 里就是三份逐字相同的 `catch`，
 * 而且将来 peach 加一档 `FailureReason` 时会漏改两处。
 *
 * 两处语义映射：
 * - `browserBlocked`（浏览器也没过去）→ 保持 `CloudflareChallenge`，链上按**整站冷却**
 *   处理，而不是当解析失败反复重试；
 * - 其余按 [SourceFetchException.reason] 原样传，那是取页层已经判好的档位。
 */
object PeachFetchBridge {

    fun translate(sourceLabel: String, error: SourceFetchException): PeachSourceFailure {
        // `Throwable.message` 是可空的；用类名兜底而不是 `orEmpty()`：空串会让日志里出现
        // 「FC2PPVDB 的 HTTP 与浏览器通道都被站方拦下：」这种没有原因的句子，排查时反而更慢。
        val detail = error.message ?: error::class.java.simpleName
        val message = if (error.browserBlocked) {
            "$sourceLabel 的 HTTP 与浏览器通道都被站方拦下：$detail"
        } else {
            detail
        }
        return PeachSourceFailure(
            reason = error.reason,
            message = message,
            httpCode = error.statusCode.takeIf { it > 0 },
            detail = if (error.browserAttempted) "browser_attempted" else ""
        )
    }

    /**
     * 取一页并要求成功。
     *
     * 200 也可能是验证页；`AndroidSourceFetch` 已经替我们判过一次（拦下就回落浏览器），
     * 但**没有**回落到浏览器通道的来源（或回落完仍是验证页）会原样把这一页交上来，
     * 所以这里再判一次正文——判据复用 [ChallengePageDetector]，不另写一套。
     */
    suspend fun fetchOrThrow(
        fetcher: SourcePageFetcher,
        request: SourcePageRequest,
        numberMatched: String
    ): SourcePageResponse {
        val response = try {
            fetcher.fetch(request)
        } catch (error: SourceFetchException) {
            throw translate(request.sourceLabel, error)
        }
        if (!response.isSuccessful) {
            val reason = PeachReasonClassifier.httpCodeToReason(response.status)
                ?: PeachFailureReason.ServerError
            throw PeachSourceFailure(
                reason,
                "${request.sourceLabel} 请求失败 HTTP ${response.status}",
                httpCode = response.status
            )
        }
        ChallengePageDetector.detect(response.body, request.source)?.let { failure ->
            throw PeachSourceFailure(
                PeachFailureReason.CloudflareChallenge,
                failure.message.orEmpty().ifBlank { "$numberMatched：${request.sourceLabel} 返回验证页" }
            )
        }
        return response
    }
}