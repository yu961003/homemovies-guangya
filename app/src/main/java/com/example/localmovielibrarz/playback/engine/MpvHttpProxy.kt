package com.example.localmovielibrarz.playback.engine

import android.util.Log
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.RejectedExecutionHandler
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * mpv 的 **回环 HTTP 代理**：把 mpv/FFmpeg 的 TLS 栈整个绕过，让网络由 OkHttp 代跑。
 *
 * ## 为什么必须这么做（不是选择，是结构决定）
 *
 * 本仓 `libavformat.so` 是**静态 mbedtls 构建**，实测二进制里 `ssl_tls.c` 与
 * `No CA chain is set` 两个符号串都在，但 `/system/etc/security`、`cacerts` 一律 0 命中：
 * 它**不读 Android 系统信任库**，自己也没有内建根证书。而 mpv 0.41 的
 * `stream_lavf.c` 里 `tls_verify` 默认值是 **false**（`OPT_FLAG("tls-verify", …, 1, false)`，
 * 语料取自 mpv v0.41.0 源码）——也就是说：
 *
 * - 默认情况下 mpv 不做证书校验，但**仍要过 TLS 握手**，握手参数、SNI、ALPN、
 *   TLS 版本、密码套件全部由 app 里这份 mbedtls 决定；
 * - 云盘直链最常见的拦截点是 **UA / Referer / Cookie 缺失**（→ 403）、
 *   **跨协议重定向**（http 302 到 https）、**SNI 与 Host 不一致**、**http/1.1 要求**，
 *   这些在 exo 侧全部由 `DefaultHttpDataSource` 处理掉了：
 *   `setAllowCrossProtocolRedirects(true)` + `setDefaultRequestProperties(headers)` + 系统 TLS。
 *   mpv 没有任何一项能对等地配上。
 *
 * 所以「把 mpv 的网络换成 exo 的网络」是唯一能做到**逐字节等价**的办法：
 * mpv 只跟 `127.0.0.1` 说明文 HTTP，真正的 https 请求、UA、Referer、Cookie、
 * 重定向、证书校验由 OkHttp（= 系统 TLS 栈）负责。
 *
 * ## 行为约定
 *
 * - 上游 URL 与请求头由 [register] 登记，**客户端（mpv）发来的头除 `Range` 外一律忽略**，
 *   避免 mpv 的全局 `user-agent` 覆盖掉 exo 的 UA；
 * - 支持 `GET` / `HEAD`；`Range` 原样透传，`206` + `Content-Range` 原样回传；
 * - 响应体流式转发（64 KB 一块，边收边 flush），不整体读进内存；
 * - 上游长度未知（chunked）时对下游改用 `Transfer-Encoding: chunked`；
 * - 一律 `Connection: close`：ffmpeg 对短连接是安全的，长连接反而会因为代理实现
 *   不到位而出错（seek 时 mpv 会新开连接，正常）。
 *
 * **安全**：只 bind `127.0.0.1`（不 bind `0.0.0.0`，局域网不可达），路径带一次性
 * random token，别的 app 猜不到路径；即便如此它也只转发到 [register] 指定的上游。
 */
class MpvHttpProxy {

    private data class Upstream(
        val url: HttpUrl,
        val headers: Map<String, String>
    )

    private val token: String = UUID.randomUUID().toString().replace("-", "")

    /**
     * 与 exo 的 `DefaultHttpDataSource` 对齐：
     * - 跟随重定向，且**允许跨协议**（http↔https），对应 `setAllowCrossProtocolRedirects(true)`；
     * - **不覆盖系统代理**：exo 的 `DefaultHttpDataSource` 建在 `HttpURLConnection` 上，
     *   走 `ProxySelector.getDefault()`，用户开 VPN/系统代理时 exo 会走代理；
     *   早先这里写死 `Proxy.NO_PROXY` 是对齐缺陷（会让「exo 能播、mpv 不能」在代理环境下复现），
     *   已改回 OkHttp 默认行为 = 系统 ProxySelector；
     * - 无整体超时（`callTimeout=0`）：一部电影要读几十分钟；
     * - 读超时给到 120 秒（见下）。
     */
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        // 读超时 = 建连后**连续多久收不到字节**就放弃。30 秒对 4K 大码率偏紧：
        // 服务端一次响应慢或网络抖动就可能被误判成读失败，而 mpv 那边只会记 -13。
        // 这里放大到 2 分钟——真正卡死的链接由 mpv 自己的 network-timeout 兜底。
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val upstreams = ConcurrentHashMap<Int, Upstream>()
    private val nextId = AtomicInteger(1)
    private val running = AtomicBoolean(false)

    /** 已 accept 的连接，供 [close] 真正切断在途读取（否则要等 30 s 的 soTimeout）。 */
    private val activeSockets: MutableSet<Socket> =
        java.util.Collections.newSetFromMap(ConcurrentHashMap<Socket, Boolean>())

    /**
     * 最近一次转发拿到的上游 HTTP 状态码，0 表示还没发生过请求。
     *
     * 存在的唯一理由：mpv 只会把上游 403/404/410 折叠成通用的
     * `loading_failed(-13, reason=4)`，界面上看不出到底谁拒了。有了这个值，
     * [com.example.localmovielibrarz.playback.engine.MpvPlaybackEngine] 才能在错误文案里
     * 直接写出「上游 HTTP 403」，把「TLS 问题 / 签名过期 / UA 被拦」三类原因当场区分开。
     */
    @Volatile
    var lastUpstreamStatus: Int = 0
        private set

    /** 最近一次转发的上游主机（不含 path/query，避免把签名写进日志）。 */
    @Volatile
    var lastUpstreamHost: String = ""
        private set

    /**
     * 最近一次转发**在拿到响应之前**就失败的异常摘要；成功拿到响应时清空。
     *
     * 这种情况以前是「一声不响关 socket」，ffmpeg 只看到连接重置，最终又是一个无信息量的 -13。
     * 现在既回 502（让 mpv 日志里出现确定的状态行），也把原文留在这里供错误文案引用。
     */
    @Volatile
    var lastUpstreamError: String = ""
        private set

    private var serverSocket: ServerSocket? = null

    /**
     * 每个入站连接（= 每个 mpv 的上游请求）一个线程。
     *
     * **必须有上界**：早先是 `newCachedThreadPool`（无上限），mpv 在 seek/重连风暴下
     * 加上 `reconnect` 选项会叠加连接，理论上有把线程数打爆的风险。
     * 这里用「直接交递队列 + 有界最大线程数」：核心 4 个常驻（正常播放只占 1~2 个），
     * 来不及时最多扩到 16 个，再多的连接**直接关闭**（mpv 会自己重试），
     * 绝不让 accept 线程阻塞——阻塞会让代理整体假死，表现为播放卡住而不是报错。
     */
    private val workers: ExecutorService = ThreadPoolExecutor(
        MIN_WORKER_THREADS,
        MAX_WORKER_THREADS,
        WORKER_KEEP_ALIVE_SECONDS,
        TimeUnit.SECONDS,
        SynchronousQueue<Runnable>(),
        ThreadFactory { runnable -> Thread(runnable, "mpv-http-proxy").apply { isDaemon = true } },
        object : RejectedExecutionHandler {
            override fun rejectedExecution(r: Runnable, e: ThreadPoolExecutor) {
                // 故意抛出去：acceptLoop 捕获后关掉这个 socket，mpv 看到连接被拒会重试。
                throw RejectedExecutionException("mpv-http-proxy 线程池已满（上限 $MAX_WORKER_THREADS）")
            }
        }
    )

    /** 实际监听端口；未启动时为 -1。 */
    val port: Int get() = serverSocket?.localPort ?: -1

    /** 是否正在监听。false 时 [register] 返回 null，调用方必须退回直连。 */
    val isRunning: Boolean get() = running.get()

    /**
     * 在 `127.0.0.1` 上随机端口起监听。失败只记日志并返回 false——
     * 代理挂了不应让整个 mpv 不可用，调用方会退回直连（旧行为）。
     */
    fun start(): Boolean {
        if (running.get()) return true
        return try {
            val socket = ServerSocket()
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(InetAddress.getByName(LOOPBACK), 0), BACKLOG)
            serverSocket = socket
            running.set(true)
            Thread({ acceptLoop(socket) }, "mpv-http-accept").apply {
                isDaemon = true
                start()
            }
            Log.i(TAG, "回环代理已启动：http://$LOOPBACK:${socket.localPort}/（mpv 侧不再自己走 TLS）")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "回环代理启动失败，退回 mpv 直连（https 直链可能重新出现 -13）", t)
            running.set(false)
            runCatching { serverSocket?.close() }
            serverSocket = null
            false
        }
    }

    /**
     * 登记一次播放，返回给 mpv 用的 `http://127.0.0.1:<port>/<token>/<id>/<name>`。
     *
     * 返回 null 表示代理没起来或 URL 非法，调用方必须用原 URL。
     */
    fun register(upstreamUrl: String, headers: Map<String, String>): String? {
        if (!running.get()) return null
        val trimmed = upstreamUrl.trim()
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) return null
        // 先让 OkHttp 解析并做百分号归一：直链的 query 里常带签名/中文文件名，
        // 直接拼进 Request.Builder.url(String) 会因为空格、中文、裸 `|` 抛 IllegalArgumentException。
        val httpUrl = trimmed.toHttpUrlOrNull()
        if (httpUrl == null) {
            Log.w(TAG, "上游 URL 无法解析，退回 mpv 直连：${hideQuery(trimmed)}")
            return null
        }
        // 直链签名通常对「原始 URL 字节」计算。OkHttp 把 URL 解析成 HttpUrl 再序列化时，
        // 会按 RFC 3986 重新做百分号归一（例如小写 %2f→%2F、空格→%20、path 里的中文字符）。
        // 绝大多数签名算法对规范化后的串是无感的（服务端也解析），但不保证。
        // 这条日志就是给「403 但头都发出去了」的现场留证据：出现即说明 URL 被改写过。
        val normalized = httpUrl.toString()
        if (normalized != trimmed) {
            Log.w(
                TAG,
                "上游 URL 被重新编码（长度 ${trimmed.length}→${normalized.length}）；" +
                    "若上游返回 403，优先怀疑签名失配：${hideQuery(normalized)}"
            )
        }
        lastUpstreamStatus = 0
        lastUpstreamError = ""
        lastUpstreamHost = httpUrl.host
        return try {
            val id = nextId.getAndIncrement()
            upstreams[id] = Upstream(httpUrl, headers.filterValues { it.isNotBlank() })
            pruneUpstreams(id)
            val rawName = trimmed.substringBefore('?').substringAfterLast('/')
            val name = sanitizeName(rawName)
            "http://$LOOPBACK:$port/$token/$id/$name"
        } catch (t: Throwable) {
            Log.w(TAG, "登记上游失败，退回 mpv 直连：${hideQuery(trimmed)}", t)
            null
        }
    }

    override fun toString(): String =
        "MpvHttpProxy(running=$isRunning, port=$port, upstreams=${upstreams.size})"

    /**
     * 关闭监听与所有在途连接。
     *
     * 必须在 mpv `destroyEngine()` **之后**调用：先让 mpv 自己收尾，
     * 否则正在读的 socket 会被立刻切断，日志里多一条无意义的 read error。
     *
     * 「所有在途连接」以前只是注释，实现只关了 `serverSocket`；真正要等在途 worker
     * 自己读到 `soTimeout`（30 秒）才会退出。这里把 accept 到的 socket 记在
     * [activeSockets] 里逐个关掉，`close()` 才是真的同步收尾。
     */
    fun close() {
        if (!running.getAndSet(false)) return
        upstreams.clear()
        runCatching { serverSocket?.close() }
        serverSocket = null
        // 先断在途连接，再让线程池停：先 shutdownNow 只会中断线程，socket 仍可能卡在 read。
        for (socket in activeSockets) {
            runCatching { socket.close() }
        }
        activeSockets.clear()
        workers.shutdownNow()
        Log.i(TAG, "回环代理已关闭")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 监听与逐连接处理
    // ─────────────────────────────────────────────────────────────────────────

    private fun acceptLoop(server: ServerSocket) {
        while (running.get()) {
            val socket = try {
                server.accept()
            } catch (t: Throwable) {
                if (running.get()) Log.w(TAG, "accept 失败", t)
                break
            }
            runCatching { workers.execute { handleClient(socket) } }
                .onFailure { runCatching { socket.close() } }
        }
    }

    private fun handleClient(socket: Socket) {
        activeSockets.add(socket)
        try {
            socket.use { s ->
                try {
                    s.soTimeout = SOCKET_TIMEOUT_MS
                    val input = BufferedInputStream(s.getInputStream())
                    val output = BufferedOutputStream(s.getOutputStream())

                    val head = readHead(input) ?: return
                    val lines = head.split("\r\n")
                    if (lines.isEmpty()) return
                    val requestLine = lines[0].split(' ')
                    if (requestLine.size < 2) return
                    val method = requestLine[0].uppercase(Locale.ROOT)
                    val target = requestLine[1]

                    var range: String? = null
                    for (index in 1 until lines.size) {
                        val colon = lines[index].indexOf(':')
                        if (colon <= 0) continue
                        val key = lines[index].substring(0, colon).trim().lowercase(Locale.ROOT)
                        if (key == "range") range = lines[index].substring(colon + 1).trim()
                    }

                    val path = target.substringBefore('?')
                    val segments = path.trim('/').split('/')
                    // 期望形如 /<token>/<id>/<name>
                    if (segments.size < 2 || segments[0] != token) {
                        writeStatus(output, 403, "Forbidden")
                        return
                    }
                    val upstream = segments[1].toIntOrNull()?.let { upstreams[it] }
                    if (upstream == null) {
                        writeStatus(output, 404, "Not Found")
                        return
                    }

                    forward(upstream, method, range, output)
                } catch (t: Throwable) {
                    // 播放器主动断流（切集/停止）会走到这里，属正常路径
                    Log.d(TAG, "回环连接结束：${t.javaClass.simpleName} ${t.message.orEmpty()}")
                }
            }
        } finally {
            activeSockets.remove(socket)
        }
    }

    private fun forward(
        upstream: Upstream,
        method: String,
        range: String?,
        output: OutputStream
    ) {
        val isHead = method == "HEAD"
        val builder = Request.Builder().url(upstream.url)
        // **必须显式要求 identity**：OkHttp 默认会加 `Accept-Encoding: gzip` 并对响应
        // 透明解压。一旦上游真的对媒体做了 gzip，`body.contentLength()` 会变 -1，
        // 我们退化成 chunked 转发，而 ffmpeg 拿到的字节偏移与声明的 Range 就不再对应，
        // 表现是 seek 后花屏/错位。媒体响应本来就极少 gzip，但「极少」不是「不会」。
        builder.header("Accept-Encoding", "identity")
        for ((key, value) in upstream.headers) {
            if (key.equals("Host", true) || key.equals("Connection", true)) continue
            if (key.equals("Range", true)) continue
            if (key.equals("Accept-Encoding", true)) continue
            // 以前是 runCatching 静默吞：Referer 里带中文/空格时 OkHttp 抛 IAE，
            // 这个头就被**整条丢掉**，上游回 403，而日志里看不出「头没发出去」。
            // 现在必须留痕——这正是「exo 能播、mpv 403」最难查的一类原因。
            try {
                builder.header(key, value)
            } catch (t: IllegalArgumentException) {
                Log.w(TAG, "请求头 $key 被 OkHttp 拒绝，本次不再发送该头：${t.message.orEmpty()}")
            }
        }
        if (!range.isNullOrEmpty()) builder.header("Range", range)
        builder.method(if (isHead) "HEAD" else "GET", null)

        val response = try {
            client.newCall(builder.build()).execute()
        } catch (t: Throwable) {
            // **绝不能一声不响地关 socket**：那样 ffmpeg 只看到连接被重置，
            // mpv 仍旧折叠成 -13，新代码就把「不可诊断」原样复制了一遍。
            // 这里必须回一个带明确原因的状态行，让上游失败原因进入 mpv 日志。
            lastUpstreamStatus = -1
            lastUpstreamHost = upstream.url.host
            lastUpstreamError = "${t.javaClass.simpleName}: ${t.message.orEmpty()}"
            Log.e(TAG, "上游请求失败：${hideQuery(upstream.url)} → ${lastUpstreamError}", t)
            val reason = lastUpstreamError.replace('\r', ' ').replace('\n', ' ')
            runCatching { writeStatus(output, 502, "Bad Gateway: $reason") }
            return
        }

        response.use { resp ->
            lastUpstreamStatus = resp.code
            lastUpstreamHost = upstream.url.host
            lastUpstreamError = ""
            val body = resp.body
            val length = body?.contentLength() ?: -1L
            val chunked = !isHead && length < 0

            val header = StringBuilder()
            header.append("HTTP/1.1 ").append(resp.code).append(' ')
                .append(resp.message.ifBlank { "OK" }).append("\r\n")
            appendHeader(header, "Content-Type", resp.header("Content-Type"))
            appendHeader(header, "Content-Range", resp.header("Content-Range"))
            appendHeader(header, "Accept-Ranges", resp.header("Accept-Ranges"))
            appendHeader(header, "Last-Modified", resp.header("Last-Modified"))
            appendHeader(header, "ETag", resp.header("ETag"))
            when {
                isHead -> if (length >= 0) {
                    header.append("Content-Length: ").append(length).append("\r\n")
                }
                chunked -> header.append("Transfer-Encoding: chunked\r\n")
                else -> header.append("Content-Length: ").append(length).append("\r\n")
            }
            header.append("Connection: close\r\n\r\n")
            output.write(header.toString().toByteArray(Charsets.ISO_8859_1))
            output.flush()

            Log.i(
                TAG,
                "代理 ${method} ${hideQuery(upstream.url)} → ${resp.code}" +
                    (range?.let { "（Range: $it）" } ?: "（无 Range）") +
                    (if (length >= 0) "，$length B" else "，长度未知")
            )

            if (!isHead) {
                try {
                    body?.byteStream()?.use { stream -> copyBody(stream, output, chunked) }
                } catch (t: Throwable) {
                    // 中途断流：已经写出去的响应头收不回来，只能让连接按协议正常终结，
                    // 别让对端干等到 SO_TIMEOUT。chunked 必须补终止块，否则 ffmpeg 会一直等。
                    lastUpstreamError = "body 传输中断：${t.javaClass.simpleName}: ${t.message.orEmpty()}"
                    Log.w(TAG, "转发中断：${hideQuery(upstream.url)} → $lastUpstreamError")
                    if (chunked) runCatching { output.write(LAST_CHUNK) }
                    runCatching { output.flush() }
                    return@use
                }
            }
            output.flush()
        }
    }

    private fun copyBody(input: InputStream, output: OutputStream, chunked: Boolean) {
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (chunked) {
                output.write(Integer.toHexString(read).toByteArray(Charsets.ISO_8859_1))
                output.write(CRLF)
                output.write(buffer, 0, read)
                output.write(CRLF)
            } else {
                output.write(buffer, 0, read)
            }
            // 边收边发：mpv 的 demuxer 拿到首块就能起播，不必等整个分片下完
            output.flush()
        }
        if (chunked) output.write(LAST_CHUNK)
    }

    private fun writeStatus(output: OutputStream, code: Int, reason: String) {
        val text = "HTTP/1.1 $code $reason\r\n" +
            "Content-Length: 0\r\n" +
            "Connection: close\r\n\r\n"
        output.write(text.toByteArray(Charsets.ISO_8859_1))
        output.flush()
    }

    private fun appendHeader(sb: StringBuilder, name: String, value: String?) {
        if (value.isNullOrEmpty()) return
        sb.append(name).append(": ").append(value).append("\r\n")
    }

    /**
     * 读到 `\r\n\r\n` 为止（兼容只有 `\n\n` 的实现）。
     * 上限 64 KB：请求头不会这么大，超了就是协议异常。
     */
    private fun readHead(input: InputStream): String? {
        val buffer = ByteArrayOutputStream(1024)
        var tail = 0
        while (true) {
            val byte = input.read()
            if (byte < 0) break
            buffer.write(byte)
            tail = (tail shl 8) or byte
            if (tail == CRLF_CRLF || tail == LF_LF) break
            if (buffer.size() > MAX_HEAD_BYTES) break
        }
        if (buffer.size() == 0) return null
        return String(buffer.toByteArray(), Charsets.ISO_8859_1)
    }

    private fun pruneUpstreams(currentId: Int) {
        if (upstreams.size <= KEEP_UPSTREAMS) return
        val cutoff = currentId - KEEP_UPSTREAMS
        for (key in upstreams.keys.toList()) {
            if (key < cutoff) upstreams.remove(key)
        }
    }

    private fun sanitizeName(raw: String): String {
        val decoded = runCatching { java.net.URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
        val cleaned = buildString {
            for (ch in decoded) {
                append(if (ch.isLetterOrDigit() || ch == '.' || ch == '-' || ch == '_') ch else '_')
            }
        }
        return cleaned.ifBlank { "media" }.take(96)
    }

    private fun hideQuery(url: String): String {
        val queryStart = url.indexOf('?')
        if (queryStart < 0) return url
        return url.substring(0, queryStart) + "?<已隐藏 ${url.length - queryStart - 1} 字节>"
    }

    /** 已归一化的 HttpUrl 版本：直链 query 里是签名，日志里一律不带。 */
    private fun hideQuery(url: HttpUrl): String = url.toString().substringBefore('?')

    private companion object {
        const val TAG = "MpvHttpProxy"
        const val LOOPBACK = "127.0.0.1"
        const val BACKLOG = 8
        const val SOCKET_TIMEOUT_MS = 30_000
        const val BUFFER_SIZE = 64 * 1024
        const val MAX_HEAD_BYTES = 64 * 1024
        // 保留最近 32 条。理论上 mpv 只读最新的那条（id 单调递增，`cutoff = current - N`
        // 永远保得住自己），但切集/回退时可能还有一次迟到的 Range 打在旧 id 上，
        // 8 条在连续切换几次之后就够不着了，代价只是 404。32 条的额外内存可以忽略。
        const val KEEP_UPSTREAMS = 32
        const val MIN_WORKER_THREADS = 4
        const val MAX_WORKER_THREADS = 16
        const val WORKER_KEEP_ALIVE_SECONDS = 30L

        val CRLF = byteArrayOf(13, 10)
        val LAST_CHUNK = "0\r\n\r\n".toByteArray(Charsets.ISO_8859_1)
        const val CRLF_CRLF = 0x0D0A0D0A
        const val LF_LF = 0x0A0A
    }
}
