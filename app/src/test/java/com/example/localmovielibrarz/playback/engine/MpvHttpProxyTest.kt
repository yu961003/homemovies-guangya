package com.example.localmovielibrarz.playback.engine

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * `MpvHttpProxy` 的纯 JVM 测试。
 *
 * 为什么必须有这层测试：这个代理存在的唯一目的是**让 ffmpeg 看到的字节与直连时完全一致**
 * （否则 seek 错位、Content-Length 虚报、Range 失效）。这些性质在真机上极难排查，
 * 一旦坏了表现出来仍然只是 mpv 的 `-13`，和当初要查的问题长得一模一样。
 * 所以用一台本地假上游把「请求怎么进来、响应怎么出去」逐字节钉死。
 *
 * 跑法（无需设备）：`./gradlew :app:testDebugUnitTest --tests '*MpvHttpProxyTest'`
 */
class MpvHttpProxyTest {

    private lateinit var upstream: FakeUpstream
    private lateinit var proxy: MpvHttpProxy

    @Before
    fun setUp() {
        upstream = FakeUpstream().also { it.start() }
        proxy = MpvHttpProxy()
        assertTrue("代理未能在回环地址上起监听", proxy.start())
        assertTrue(proxy.port in 1..65535)
    }

    @After
    fun tearDown() {
        proxy.close()
        upstream.stop()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 登记
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun registerRejectsNonHttpAndUnparsableUrls() {
        assertNull("ftp 不该被接受", proxy.register("ftp://example.com/a.mp4", emptyMap()))
        assertNull("空串不该被接受", proxy.register("   ", emptyMap()))
        assertNull("解析不了的串不该被接受", proxy.register("http://", emptyMap()))
    }

    @Test
    fun registerReturnsLoopbackUrlKeepingFileName() {
        val local = proxy.register("${upstream.baseUrl}/movies/%E6%B5%8B%E8%AF%95.mp4?sig=abc", emptyMap())
        assertNotNull("合法 http 直链必须登记成功", local)
        assertTrue("回环 URL 必须指向本机代理：$local", local!!.startsWith("http://127.0.0.1:${proxy.port}/"))
        assertTrue("尾段必须保留扩展名，ffmpeg 靠后缀猜 demuxer：$local", local.endsWith(".mp4"))
        assertTrue("签名 query 不能被带进回环 URL：$local", !local.contains("sig=abc"))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 转发：逐字节等价
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun plainGetForwardsBodyAndLength() {
        val body = ByteArray(4096) { (it % 251).toByte() }
        upstream.respondWith(status = 200, body = body)

        val local = proxy.register("${upstream.baseUrl}/plain.mp4", mapOf("User-Agent" to "ua-1"))!!
        val response = rawGet(local)

        assertEquals("状态码必须原样透传", 200, response.status)
        assertEquals("Content-Length 必须等于上游真实长度", body.size.toLong(), response.contentLength)
        assertArrayEquals("响应体必须与上游逐字节相同", body, response.body)

        // 上游必须收到我们下发的 UA——这是「exo 带 UA、mpv 不带」那条差异的落点。
        assertEquals("ua-1", upstream.lastHeaders["user-agent"])
    }

    @Test
    fun rangeRequestReturns206WithContentRangeEchoed() {
        val full = ByteArray(8192) { (it % 97).toByte() }
        upstream.respondWith(status = 206, body = full.copyOfRange(1024, 3072), contentRange = "bytes 1024-3071/8192")

        val local = proxy.register("${upstream.baseUrl}/seek.mp4", emptyMap())!!
        val response = rawGet(local, range = "bytes=1024-3071")

        assertEquals("Range 必须原样转发，上游才会回 206", "bytes=1024-3071", upstream.lastHeaders["range"])
        assertEquals(206, response.status)
        assertEquals("bytes 1024-3071/8192", response.headers["content-range"])
        assertEquals("206 的 Content-Length 必须是分片长度", 2048L, response.contentLength)
        assertArrayEquals(full.copyOfRange(1024, 3072), response.body)
    }

    @Test
    fun headRequestForwardsHeadersWithoutBody() {
        val body = ByteArray(2048) { 7 }
        upstream.respondWith(status = 200, body = body)

        val local = proxy.register("${upstream.baseUrl}/head.mp4", emptyMap())!!
        val response = rawGet(local, method = "HEAD")

        assertEquals(200, response.status)
        assertEquals("HEAD 不该有响应体", 0, response.body.size)
        // 不断言具体长度：OkHttp 对 HEAD 的 `body.contentLength()` 依实现可能是 0 或头里的
        // Content-Length，两种都不影响播放（ffmpeg 拿不到 size 就退回 GET 探测）。
        // 真正要钉的是「HEAD 必须有一个合法的 Content-Length 头且不发 body」。
        assertNotNull("HEAD 响应必须带 Content-Length", response.headers["content-length"])
    }

    @Test
    fun responseWithoutLengthFallsBackToChunked() {
        val body = "abcdefghij".toByteArray()
        upstream.respondWithChunked(status = 200, body = body)

        val local = proxy.register("${upstream.baseUrl}/nolength.mp4", emptyMap())!!
        val response = rawGet(local)

        assertEquals(200, response.status)
        assertEquals("长度未知时必须用 chunked，否则客户端会一直等 EOF", "chunked", response.headers["transfer-encoding"])
        assertArrayEquals("chunked 解码后必须还原原始字节", body, response.body)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 失败必须可见
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun unreachableUpstreamYields502AndRecordsError() {
        // 起一个立刻关闭的服务端，拿到一个「有人监听但马上消失」的端口
        val deadPort = ServerSocket(0).use { it.localPort }
        val local = proxy.register("http://127.0.0.1:$deadPort/gone.mp4", emptyMap())!!

        val response = rawGet(local)
        assertEquals("上游连不上必须回 502，不能一声不响关 socket", 502, response.status)
        assertTrue("错误原因要能被 describeNetworkPath 读出", proxy.lastUpstreamError.isNotEmpty())
        assertEquals("上游 host 要留在快照里", "127.0.0.1", proxy.lastUpstreamHost)
        assertEquals("没拿到任何 HTTP 响应时状态码记 -1", -1, proxy.lastUpstreamStatus)
    }

    @Test
    fun upstreamErrorStatusIsPassedThrough() {
        upstream.respondWith(status = 403, body = ByteArray(0))

        val local = proxy.register("${upstream.baseUrl}/denied.mp4", emptyMap())!!
        val response = rawGet(local)

        assertEquals("上游 403 必须原样透传，让日志出现 HTTP 403", 403, response.status)
        assertEquals(403, proxy.lastUpstreamStatus)
    }

    @Test
    fun wrongTokenIsRejected() {
        proxy.register("${upstream.baseUrl}/a.mp4", emptyMap())
        val response = rawGet("http://127.0.0.1:${proxy.port}/deadbeef/1/a.mp4")

        assertEquals("token 不对必须 403", 403, response.status)
        assertEquals("上游不该被访问到", 0, upstream.hitCount)
    }

    @Test
    fun closeStopsAcceptingConnections() {
        val port = proxy.port
        proxy.close()

        // close() 返回不等于端口立刻拒绝连接：内核 backlog 里已经完成三次握手的连接
        // 仍可能被 accept 接住。本测试原先直接断言「立刻连不上」，在 CI 上偶发失败
        // （断言是时序依赖的，不是 close() 真的没生效）。语义应当是「最终不再接受新连接」，
        // 所以这里给一个有界收敛窗口，超时才算失败。
        val deadline = System.nanoTime() + CLOSE_CONVERGE_TIMEOUT_MS * 1_000_000
        var stillConnectable = true
        while (System.nanoTime() < deadline) {
            val result = runCatching { Socket("127.0.0.1", port).use { it.soTimeout = 200 } }
            if (result.isFailure) {
                stillConnectable = false
                break
            }
            Thread.sleep(CLOSE_CONVERGE_POLL_MS)
        }
        assertFalse(
            "close() 之后 ${CLOSE_CONVERGE_TIMEOUT_MS}ms 内端口仍能连上（backlog 未被回收）",
            stillConnectable
        )
        assertNull("close() 之后不该还能登记上游", proxy.register("${upstream.baseUrl}/x.mp4", emptyMap()))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 极简 HTTP 客户端（避免依赖 HttpURLConnection 对 chunked/HEAD 的隐式改写）
    // ─────────────────────────────────────────────────────────────────────────

    private data class RawResponse(
        val status: Int,
        val headers: Map<String, String>,
        val body: ByteArray,
        val contentLength: Long
    )

    private fun rawGet(url: String, method: String = "GET", range: String? = null): RawResponse {
        val withoutScheme = url.removePrefix("http://")
        val hostPort = withoutScheme.substringBefore('/')
        val path = "/" + withoutScheme.substringAfter('/')
        val host = hostPort.substringBefore(':')
        val port = hostPort.substringAfter(':').toInt()

        Socket(host, port).use { socket ->
            socket.soTimeout = 5000
            val out = socket.getOutputStream()
            val request = buildString {
                append("$method $path HTTP/1.1\r\n")
                append("Host: $hostPort\r\n")
                if (range != null) append("Range: $range\r\n")
                append("Connection: close\r\n\r\n")
            }
            out.write(request.toByteArray())
            out.flush()

            val input = BufferedInputStream(socket.getInputStream())
            val head = readHead(input)
            val lines = head.split("\r\n")
            val status = lines[0].split(' ')[1].toInt()
            val headers = LinkedHashMap<String, String>()
            for (index in 1 until lines.size) {
                val colon = lines[index].indexOf(':')
                if (colon <= 0) continue
                headers[lines[index].substring(0, colon).trim().lowercase()] =
                    lines[index].substring(colon + 1).trim()
            }
            val body = when {
                method == "HEAD" -> ByteArray(0)
                headers["transfer-encoding"]?.contains("chunked", true) == true -> readChunked(input)
                else -> readFully(input, headers["content-length"]?.toLongOrNull() ?: -1L)
            }
            val length = headers["content-length"]?.toLongOrNull() ?: -1L
            return RawResponse(status, headers, body, if (length >= 0) length else body.size.toLong())
        }
    }

    private fun readHead(input: InputStream): String {
        val buffer = ByteArrayOutputStream(1024)
        var matched = 0
        while (true) {
            val byte = input.read()
            if (byte < 0) break
            buffer.write(byte)
            matched = when {
                matched == 0 && byte == 13 -> 1
                matched == 1 && byte == 10 -> 2
                matched == 2 && byte == 13 -> 3
                matched == 3 && byte == 10 -> return buffer.toString("ISO-8859-1").dropLast(4)
                byte == 13 -> 1
                else -> 0
            }
            if (buffer.size() > 65536) break
        }
        return buffer.toString("ISO-8859-1")
    }

    private fun readFully(input: InputStream, length: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        var remaining = if (length >= 0) length else Long.MAX_VALUE
        while (remaining > 0) {
            val want = minOf(buffer.size.toLong(), remaining).toInt()
            val read = input.read(buffer, 0, want)
            if (read < 0) break
            out.write(buffer, 0, read)
            if (length >= 0) remaining -= read
        }
        return out.toByteArray()
    }

    private fun readChunked(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val sizeLine = readLine(input) ?: break
            val size = sizeLine.substringBefore(';').trim().toIntOrNull(16) ?: break
            if (size == 0) {
                readLine(input) // 吃掉结束空行
                break
            }
            out.write(readFully(input, size.toLong()))
            readLine(input) // 每块结尾的 CRLF
        }
        return out.toByteArray()
    }

    private fun readLine(input: InputStream): String? {
        val buffer = ByteArrayOutputStream()
        while (true) {
            val byte = input.read()
            if (byte < 0) return if (buffer.size() == 0) null else buffer.toString("ISO-8859-1")
            if (byte == 10) return buffer.toString("ISO-8859-1").trimEnd('\r')
            buffer.write(byte)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 假上游：一台只会按剧本回一次的 loopback HTTP 服务
    // ─────────────────────────────────────────────────────────────────────────

    private class FakeUpstream {
        private val server = ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"))
        private var worker: Thread? = null
        private var plan: Plan? = null

        @Volatile
        var hitCount: Int = 0

        @Volatile
        var lastHeaders: Map<String, String> = emptyMap()

        val baseUrl: String get() = "http://127.0.0.1:${server.localPort}"

        private data class Plan(
            val status: Int,
            val body: ByteArray,
            val contentRange: String?,
            val chunked: Boolean
        )

        fun start() {
            worker = thread(isDaemon = true, name = "fake-upstream") {
                while (!server.isClosed) {
                    val socket = runCatching { server.accept() }.getOrNull() ?: break
                    runCatching { serve(socket) }
                }
            }
        }

        fun respondWith(status: Int, body: ByteArray, contentRange: String? = null) {
            plan = Plan(status, body, contentRange, chunked = false)
        }

        fun respondWithChunked(status: Int, body: ByteArray) {
            plan = Plan(status, body, null, chunked = true)
        }

        private fun serve(socket: Socket) {
            socket.use { s ->
                s.soTimeout = 5000
                val input = BufferedInputStream(s.getInputStream())
                val out = s.getOutputStream()

                val head = readHeadStatic(input)
                val lines = head.split("\r\n")
                val headers = LinkedHashMap<String, String>()
                for (index in 1 until lines.size) {
                    val colon = lines[index].indexOf(':')
                    if (colon <= 0) continue
                    headers[lines[index].substring(0, colon).trim().lowercase()] =
                        lines[index].substring(colon + 1).trim()
                }
                lastHeaders = headers
                hitCount++

                val current = plan ?: Plan(404, ByteArray(0), null, false)
                val header = StringBuilder()
                header.append("HTTP/1.1 ").append(current.status).append(" X\r\n")
                header.append("Content-Type: video/mp4\r\n")
                current.contentRange?.let { header.append("Content-Range: ").append(it).append("\r\n") }
                if (current.chunked) {
                    header.append("Transfer-Encoding: chunked\r\n")
                } else {
                    header.append("Content-Length: ").append(current.body.size).append("\r\n")
                }
                header.append("Connection: close\r\n\r\n")
                out.write(header.toString().toByteArray(Charsets.ISO_8859_1))

                if (current.chunked) {
                    out.write("${current.body.size.toString(16)}\r\n".toByteArray())
                    out.write(current.body)
                    out.write("\r\n0\r\n\r\n".toByteArray())
                } else if (lines[0].startsWith("HEAD")) {
                    // HEAD 只发头
                } else {
                    out.write(current.body)
                }
                out.flush()
            }
        }

        fun stop() {
            runCatching { server.close() }
            worker?.interrupt()
        }

        private fun readHeadStatic(input: InputStream): String {
            val buffer = ByteArrayOutputStream(1024)
            var matched = 0
            while (true) {
                val byte = input.read()
                if (byte < 0) break
                buffer.write(byte)
                matched = when {
                    matched == 0 && byte == 13 -> 1
                    matched == 1 && byte == 10 -> 2
                    matched == 2 && byte == 13 -> 3
                    matched == 3 && byte == 10 -> return buffer.toString("ISO-8859-1").dropLast(4)
                    byte == 13 -> 1
                    else -> 0
                }
                if (buffer.size() > 65536) break
            }
            return buffer.toString("ISO-8859-1")
        }
    }
}

/** close() 之后端口停止接受新连接的收敛窗口上限（避开内核 backlog 的竞态）。 */
private const val CLOSE_CONVERGE_TIMEOUT_MS = 3_000L

/** 收敛窗口内轮询间隔。 */
private const val CLOSE_CONVERGE_POLL_MS = 50L

