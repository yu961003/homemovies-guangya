package com.example.localmovielibrarz.util

import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import java.util.concurrent.TimeUnit

/**
 * 全局共享的 OkHttp 底层资源（连接池 + 调度线程池）。
 *
 * 各客户端（光鸭/115/刮削/播放/更新等 12 处）仍保留各自的超时与重试配置，
 * 但复用同一连接池与调度器，避免每个实例都创建独立线程池和连接（审查报告 4.3）。
 */
object HttpClients {
    val sharedConnectionPool: ConnectionPool =
        ConnectionPool(maxIdleConnections = 8, keepAliveDuration = 60, TimeUnit.SECONDS)

    val sharedDispatcher: Dispatcher = Dispatcher().apply {
        maxRequests = 64
        maxRequestsPerHost = 8
    }
}
