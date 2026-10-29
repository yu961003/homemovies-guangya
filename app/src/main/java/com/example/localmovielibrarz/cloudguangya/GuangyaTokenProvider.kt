package com.example.localmovielibrarz.cloudguangya

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 取 / 刷令牌的并发安全封装。
 *
 * - [getValidAccessToken]：协程内预取令牌（P2-6：不在 OkHttp 拦截器里 runBlocking）。
 * - [refreshAndStore]：带 Mutex 并发锁，避免多个请求同时触发刷新（P1-4）。
 */
class GuangyaTokenProvider(
    private val store: GuangyaTokenStore,
    private val loginClient: GuangyaLoginClient
) {
    private val refreshMutex = Mutex()

    suspend fun getValidAccessToken(): String {
        val token = store.getAccessToken() ?: error("光鸭未登录，请先完成设备码登录")
        // 审查 O1：未记录过期时间时保持旧行为（不主动刷新，依赖 401）；否则提前 5 分钟刷新
        if (isAccessTokenValid(token)) return token
        // F10 修复：不再在此处加锁，直接调用 refreshAndStore（它内部已有锁 + 双重检查）。
        // 原实现在此处 withLock 后调用 refreshAndStore，后者再次 withLock 同一把 Mutex，
        // 由于 kotlinx.coroutines.sync.Mutex 不可重入，导致同一协程永久挂起（死锁）。
        refreshAndStore()
        return store.getAccessToken() ?: error("光鸭未登录，请先完成设备码登录")
    }

    /** true=Token 仍有效（或未知过期）；按绝对过期时刻提前 300s 判定，避免无谓 401。 */
    private fun isAccessTokenValid(token: String): Boolean {
        val expiresAt = store.getAccessTokenExpiresAt()
        if (expiresAt <= 0) return true
        return expiresAt > System.currentTimeMillis() / 1000 + 300L
    }

    suspend fun refreshAndStore() = refreshMutex.withLock {
        // F10 修复：双重检查 — 进入锁后再次检查 token 是否已有效（可能其他协程已刷新）
        val current = store.getAccessToken()
        if (current != null && isAccessTokenValid(current)) return@withLock
        try {
            val refresh = store.getRefreshToken() ?: throw GuangyaTokenExpiredException()
            store.save(loginClient.refreshTokens(refresh))
        } catch (e: GuangyaTokenExpiredException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: java.io.IOException) {
            // 断网/超时等网络问题：透传，由调用方提示"网络连接失败"，不误报令牌失效（需求12）
            throw e
        } catch (e: Throwable) {
            // 刷新失败（refresh_token 缺失 / 服务端拒绝刷新）统一包装为"需重新登录"语义，保留原始信息
            throw GuangyaTokenExpiredException("刷新令牌失败：${e.message ?: e.javaClass.simpleName}")
        }
    }
}
