package com.example.localmovielibrarz.util

import kotlin.math.roundToLong
import kotlin.random.Random

/**
 * 指数退避 + 抖动（参考 peach 的租约/重试节奏，但只取鸭影需要的那一层）。
 *
 * 早期的重试用的是固定随机区间（2~5 秒，最多 3 次），对「网盘接口临时抽风」够用，
 * 但对「限流」不够：第 1、2、3 次都在 5 秒内打完，等于把限流窗口又撞满一次。
 * 这里改成 base × 2^(attempt-1) 增长，再叠加 ±jitter 的抖动，
 * 避免多任务同时重试形成节拍同步的尖峰。
 *
 * 用独立的类而不是内联算术，是为了能脱离 Android 单测：注入固定 random 就能断言精确值。
 */
class RetryBackoff(
    private val baseMillis: Long,
    private val maxMillis: Long,
    private val multiplier: Double = 2.0,
    /** 抖动比例：0.3 表示结果落在 [0.7x, 1.3x]。 */
    private val jitterRatio: Double = 0.3,
    private val random: Random = Random.Default
) {
    init {
        require(baseMillis > 0L) { "baseMillis must be positive" }
        require(maxMillis >= baseMillis) { "maxMillis must not be less than baseMillis" }
        require(multiplier >= 1.0) { "multiplier must be >= 1.0" }
        require(jitterRatio in 0.0..1.0) { "jitterRatio must be within 0.0..1.0" }
    }

    /** [attempt] 从 1 开始（第 1 次失败后的等待）。 */
    fun delayForAttempt(attempt: Int): Long {
        val safeAttempt = attempt.coerceAtLeast(1)
        val exponential = baseMillis.toDouble() * Math.pow(multiplier, (safeAttempt - 1).toDouble())
        val capped = exponential.coerceAtMost(maxMillis.toDouble())
        if (jitterRatio <= 0.0) return capped.roundToLong().coerceIn(baseMillis, maxMillis)
        val jitterSpan = capped * jitterRatio
        val low = (capped - jitterSpan).coerceAtLeast(1.0)
        val high = (capped + jitterSpan).coerceAtMost(maxMillis.toDouble())
        if (high <= low) return low.roundToLong()
        val offset = random.nextDouble()
        return (low + (high - low) * offset).roundToLong().coerceIn(1L, maxMillis)
    }
}
