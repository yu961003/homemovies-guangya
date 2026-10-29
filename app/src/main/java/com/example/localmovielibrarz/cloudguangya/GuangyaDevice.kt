package com.example.localmovielibrarz.cloudguangya

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * 端侧设备标识生成，严格对齐官方 guangyaclient SDK 的 tools.py。
 *
 * - did          = md5(urandom(16)).hexdigest()（32 位 hex）
 * - device_sign  = "wdi10.{did}{16 字节 hex}"
 * - traceparent  = "00-{32hex}-{16hex}-01"（W3C Trace Context）
 *
 * 光鸭登录与资源请求都强依赖这些头，缺失或格式不对会直接 401/拒绝。
 */
object GuangyaDevice {
    private val random = SecureRandom()

    /** 每个登录客户端实例固定一个 did（在 AppContainer 中 guangyaLoginClient 为单例）。 */
    fun generateDid(): String {
        val seed = ByteArray(16)
        random.nextBytes(seed)
        return md5Hex(seed)
    }

    fun generateDeviceSign(did: String): String {
        val rand = ByteArray(16)
        random.nextBytes(rand)
        return "wdi10.$did${rand.toHex()}"
    }

    fun generateTraceparent(): String {
        val traceId = randomHex(16) // 32 hex chars
        val parentId = randomHex(8) // 16 hex chars
        return "00-$traceId-$parentId-01"
    }

    private fun randomHex(byteCount: Int): String {
        val bytes = ByteArray(byteCount)
        random.nextBytes(bytes)
        return bytes.toHex()
    }

    private fun md5Hex(data: ByteArray): String {
        val md = MessageDigest.getInstance("MD5")
        return md.digest(data).toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
