package com.example.localmovielibrarz.cloudguangya

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 光鸭请求频控（P1-1 修正版：Mutex 可编译，锁内不调用挂起函数）。
 *
 * 读 350ms / 写 1200ms 节流，对齐 guangyapan 默认节奏。delay 在锁外执行。
 */
class GuangyaRateLimiter(
    private val readDelayMs: Long = 350,
    private val writeDelayMs: Long = 1200
) {
    private val mutex = Mutex()
    private var lastRead = 0L
    private var lastWrite = 0L

    suspend fun acquireRead() {
        val wait = mutex.withLock {
            val now = System.currentTimeMillis()
            val delta = (lastRead + readDelayMs - now).coerceAtLeast(0L)
            lastRead = now + delta
            delta
        }
        if (wait > 0) delay(wait)
    }

    suspend fun acquireWrite() {
        val wait = mutex.withLock {
            val now = System.currentTimeMillis()
            val delta = (lastWrite + writeDelayMs - now).coerceAtLeast(0L)
            lastWrite = now + delta
            delta
        }
        if (wait > 0) delay(wait)
    }
}
