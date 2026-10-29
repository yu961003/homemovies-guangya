package com.example.localmovielibrarz.playback

import kotlinx.coroutines.CompletableDeferred

/**
 * 一条已经解析出来的网盘播放直链，以及它的「租约」窗口（签发时刻 → 失效时刻）。
 *
 * 为什么直链需要租约，而不是只缓存一个 url 字符串：
 * - 直链是带签名的临时凭证。115 的 `t` 参数、光鸭的 `expires` 参数都有硬过期时间，
 *   过了那个时刻服务端一定返回 401/403，本地再怎么重试也没用。
 * - 只存 url 的话，调用方无法回答「现在还能不能播」这个问题，只能等 ExoPlayer
 *   报错再回头换链，用户先看到的是一次黑屏 + 错误提示。
 * - 把「还剩多久、要不要提前续」做成租约自身的派生方法，调用方只需要传入 now，
 *   时钟由外部注入，单测里可以精确卡在 1 毫秒的边界上，不依赖真实时间流逝。
 */
data class DirectLinkLease(
    /** 可直接交给播放器的直链（含签名参数，属于敏感数据，不要写入日志或持久化）。 */
    val url: String,
    /** 本地签发时刻（epoch millis），用于诊断「这条链是什么时候拿到的」。 */
    val issuedAtMillis: Long,
    /** 服务端失效时刻（epoch millis）。到达这一毫秒即视为过期。 */
    val expiresAtMillis: Long,
    /** 来源标签（如 "115" / "guangya_res"），只用于日志与界面文案。 */
    val sourceLabel: String
) {
    /**
     * 距离失效还剩多少毫秒。
     *
     * 故意不在 0 处截断：过期后返回负数，调用方既能区分「刚好到期」与「早就过期」，
     * 也能从负值大小判断用户卡在错误页之前已经过了多久。
     */
    fun remainingMillis(now: Long): Long = expiresAtMillis - now

    /**
     * 是否已过期。
     *
     * 用 `>=` 而不是 `>`：服务端在同一毫秒已经拒绝该签名，本地再拿它去请求只会浪费一次往返。
     */
    fun isExpired(now: Long): Boolean = now >= expiresAtMillis

    /**
     * 是否该提前换链：剩余毫秒数 <= [leadMillis] 即需要续期，已过期的租约必然为 true。
     *
     * [leadMillis] 为负时按 0 处理。否则「已经过期很久」的租约（剩余 -10s、lead -20s）
     * 会算出「还没到续期点」，与「过期一定要续」的前提直接矛盾。
     */
    fun needsRenewal(now: Long, leadMillis: Long): Boolean =
        remainingMillis(now) <= leadMillis.coerceAtLeast(0L)

    companion object {
        /**
         * 以 [now] 为签发时刻、[ttlMillis] 为有效期构造租约。
         *
         * ttl 为负说明上游解析出的过期时间早于当前时刻（时钟漂移或签名已作废），
         * 按 0 寿命处理，让 [needsRenewal] 立刻为 true，而不是造出一条「负寿命」的租约
         * 让调用方误以为它曾经有效。
         */
        fun leaseFromNow(url: String, sourceLabel: String, ttlMillis: Long, now: Long): DirectLinkLease =
            DirectLinkLease(
                url = url,
                issuedAtMillis = now,
                expiresAtMillis = now + ttlMillis.coerceAtLeast(0L),
                sourceLabel = sourceLabel
            )
    }
}

/**
 * 直链租约的内存表，key 通常是 pickcode / 资源 id。
 *
 * 为什么是内存表而不是落库：
 * - 直链本身带时效（115 的 t 参数、光鸭的 expires，通常 30 分钟到 2 小时）。落库之后，
 *   进程重启或隔天冷启动仍会读到旧链，播放器先黑屏再报 403，用户看到的是「播不了」；
 *   内存表随进程消失，最坏情况只是多一次取链请求，换来「手上的链一定能播」。
 * - 直链是敏感数据，少一份持久化就少一份泄露面；加密落库还要维护密钥轮换、过期清理、
 *   数据库迁移，收益远小于成本。
 * - 需要跨进程共享时，以仓库层的持久缓存为准；本表只负责单进程内的去重与续期判断。
 *
 * 线程安全：所有读写都在私有锁内完成，[clock] 也在锁内调用；时钟实现不能回调本类，
 * 否则会在同一线程上重入同一把锁。
 */
class DirectLinkLeaseStore(
    private val clock: () -> Long = System::currentTimeMillis,
    private val renewalLeadMillis: Long = 60_000L
) {
    private val lock = Any()
    private val leases = HashMap<String, DirectLinkLease>()

    /**
     * 读取租约，并顺带清理整张表里的过期项。
     *
     * 过期项与「不存在」等价：调用方拿到 null 就该去取一条新链，而不是把旧链交给播放器。
     * 播放主链路请优先用 [available]；[get] 面向诊断场景，会在读取时把其它失效项一并清掉。
     */
    fun get(key: String): DirectLinkLease? {
        synchronized(lock) {
            pruneExpiredLocked(clock())
            return leases[key]
        }
    }

    /**
     * 写入 / 覆盖某个 key 的租约。
     *
     * 不做新旧比较，由调用方决定是否覆盖：取链逻辑里「刚拿到的链」天然比旧链新，
     * 而合并并发请求时，后写入者只是把同一个结果再存一次，直接覆盖即可。
     */
    fun put(key: String, lease: DirectLinkLease) {
        synchronized(lock) {
            leases[key] = lease
        }
    }

    /** 主动失效一个 key（例如播放报 403 后决定立即换链）。 */
    fun invalidate(key: String) {
        synchronized(lock) {
            leases.remove(key)
        }
    }

    /** 清空所有租约（例如退出登录或切换账号时）。 */
    fun clear() {
        synchronized(lock) {
            leases.clear()
        }
    }

    /**
     * 仅在租约当前仍未过期时返回它，否则返回 null 并把失效项清掉。
     *
     * 这是播放主链路唯一应该使用的读取入口：返回非 null 就等于承诺「这一毫秒还能播」。
     */
    fun available(key: String): DirectLinkLease? {
        synchronized(lock) {
            val lease = leases[key] ?: return null
            if (lease.isExpired(clock())) {
                leases.remove(key)
                return null
            }
            return lease
        }
    }

    /**
     * 返回当前有效租约的只读快照（已剔除过期项）。
     *
     * 快照与内存表解耦：对返回的 Map 做任何操作都不会影响后续读取，适合诊断页与日志导出。
     */
    fun snapshot(): Map<String, DirectLinkLease> {
        synchronized(lock) {
            pruneExpiredLocked(clock())
            return leases.toMap()
        }
    }

    /**
     * 该 key 是否已进入续期窗口（剩余时间 <= 构造时配置的 [renewalLeadMillis]）。
     *
     * 没有租约时返回 true：对调用方来说「没有链」和「链快过期」是同一个动作——去取一条新链；
     * 返回 false 才表示「手上这条链还能安心用一阵」。
     */
    fun needsRenewal(key: String, now: Long = clock()): Boolean {
        synchronized(lock) {
            val lease = leases[key] ?: return true
            return lease.needsRenewal(now, renewalLeadMillis)
        }
    }

    private fun pruneExpiredLocked(now: Long) {
        val iterator = leases.values.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().isExpired(now)) iterator.remove()
        }
    }
}

/**
 * 同一 key 并发取链的单飞（single-flight）合并器。
 *
 * 为什么不能用 Mutex 串行化：Mutex 只能保证「一个接一个」，第 N 个调用仍会再执行一次 block，
 * 而这里要的是「同一时刻只有一个在取链，其余拿到它的结果」——并发 5 个请求也只打一次网盘接口。
 *
 * 为什么用 [CompletableDeferred]：它天然是「一次完成、多次读取」的容器；leader 把成功值或
 * 异常写入 deferred，所有等待者 await 同一个对象，于是异常也能原样传播给每一个等待者，
 * 不会出现「leader 失败、等待者永远挂起」的情况。
 *
 * 不同 key 使用不同 entry，互不阻塞；block 结束后（无论成功、失败还是被取消）都会在 finally
 * 里删除 entry，避免后续调用拿到已经结束的结果。
 */
class DirectLinkSingleFlight {
    private val lock = Any()
    private val inFlight = HashMap<String, CompletableDeferred<Any?>>()

    /**
     * 执行（或加入）[key] 对应的取链任务。
     *
     * 第一个调用者是 leader，真正执行 [block]；同一 key 的其余调用者只等待 leader 的结果。
     * block 抛出的异常会被原样交给所有等待者——调用方可以按正常的 try/catch 处理。
     *
     * [key] 必须能唯一标识一次取链（例如 "115:$pickcode"），同一 key 的调用者应使用相同的
     * 返回类型；key 不同则互不影响。
     */
    @Suppress("UNCHECKED_CAST")
    suspend fun <T> run(key: String, block: suspend () -> T): T {
        val (deferred, isLeader) = synchronized(lock) {
            val existing = inFlight[key]
            if (existing != null) {
                existing to false
            } else {
                val created = CompletableDeferred<Any?>()
                inFlight[key] = created
                created to true
            }
        }

        if (!isLeader) {
            return deferred.await() as T
        }

        return try {
            val value = block()
            deferred.complete(value)
            value
        } catch (error: Throwable) {
            // 先完成 deferred 再重抛：等待者拿到的异常与 leader 的完全一致（同一对象），
            // 随后 finally 清理 entry，后续调用不会复用这一次的失败。
            deferred.completeExceptionally(error)
            throw error
        } finally {
            synchronized(lock) {
                if (inFlight[key] === deferred) inFlight.remove(key)
            }
        }
    }
}
