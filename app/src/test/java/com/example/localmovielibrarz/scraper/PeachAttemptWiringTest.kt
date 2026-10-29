package com.example.localmovielibrarz.scraper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 接线形状探针：确认桃链侧的两个数据类与失败归类器的调用形状与仓库层一致。
 *
 * 为什么需要它：仓库层（StrmScrapeRepository）依赖 Android，进不了本机的纯 JVM 编译集，
 * 它那几处「把 SourceAttempt.peachReason 传进 PeachAttempt.Failure.reason」的改动
 * 只能靠这里用同样的具名参数调一遍来保证同形——参数名或类型对不上时这个测试就编译失败。
 */
class PeachAttemptWiringTest {

    @Test
    fun attemptCarriesPeachReasonThrough() {
        val attempt = SourceAttempt(
            source = ScrapeSource.Avbase,
            failure = ScrapeFailure(ScrapeFailureKind.NotFound, "没有"),
            numberMatched = false,
            responseBytes = 1024L,
            peachReason = PeachFailureReason.NotFound
        )
        assertEquals(ScrapeFailureKind.NotFound, attempt.failure?.kind)
        assertEquals(PeachFailureReason.NotFound, attempt.peachReason)
        assertEquals(1024L, attempt.responseBytes)
    }

    @Test
    fun failureAttemptBuildsFromSourceAttempt() {
        val attempt = SourceAttempt(
            source = ScrapeSource.Fc2,
            failure = ScrapeFailure(ScrapeFailureKind.ParseError, "结构变了"),
            peachReason = PeachFailureReason.ParseError
        )
        val peach = PeachAttempt.Failure(
            failure = attempt.failure ?: ScrapeFailure(ScrapeFailureKind.Unknown, "未取得数据"),
            numberMatched = attempt.numberMatched,
            responseBytes = attempt.responseBytes,
            reason = attempt.peachReason
        )
        assertEquals(PeachFailureReason.ParseError, peach.reason)
        assertEquals(true, peach.reason?.permanent)
        assertEquals(false, peach.reason?.blocksWholeSource)
    }

    @Test
    fun successfulAttemptHasNoPeachReason() {
        assertNull(SourceAttempt(source = ScrapeSource.Javbus).peachReason)
    }
}
