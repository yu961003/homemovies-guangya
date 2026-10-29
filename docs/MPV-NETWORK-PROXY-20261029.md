# mpv https 直链播放失败：根因更正与回环代理方案（2026-10-29）

## 0. 一句话

`loading_failed（error=-13, reason=error/4）` **不是 CA 信任锚缺失**（Round-1 的判断是误判，已更正）。
真正的原因是：**strm 里存的是云盘直链（`https://<云盘域名>/...`），这条链路的成功条件全部挂在
exo 的 `DefaultHttpDataSource` 上**——UA、默认请求头、跨协议重定向、系统 TLS 栈；
而 mpv 侧用的是自己那份 **静态 mbedtls 的 libavformat**，上面四条**一条都配不出来**。
所以修法不是「给 mpv 补参数」，而是**让 mpv 不要自己走网络**：
用进程内回环代理（127.0.0.1）把请求转给 OkHttp，让 mpv 与 exo 共用同一套网络实现。

---

## 1. 证据：mpv 0.41 的 `tls-verify` 默认值是 **false**

Round-1 的结论建立在「mbedtls 后端没有 CA → 握手失败 → -13」这个推理上。
该推理的**前提不成立**，因为 mpv 默认根本不校验证书。

语料（mpv v0.41.0 源码，`stream/stream_lavf.c`）：

```c
const struct m_sub_options stream_lavf_conf = {
    .opts = (const m_option_t[]) {
        OPT_STRING("protocols", ..., 0),
        OPT_STRING("tls-ca-file", ...),
        OPT_FLAG("tls-verify", NULL, 0, false),   /* ← 默认 false */
        ...
    },
    .size = sizeof(struct stream_lavf_params),
    .defaults = &stream_lavf_params_defaults_e,
};
```

- `OPT_FLAG("tls-verify", …, false)`：默认**不校验**对端证书。
- 也就是说：「没有 CA 包」**不会**让 https 打开失败；本仓 `libavformat.so` 里确实有
  `ssl_tls.c` 与 `No CA chain is set` 两个字符串，但前者是 mbedtls 的编译单元名、
  后者是**只有 `verify != 0` 时才会打印**的分支文案，两者都不能作为「默认开启校验」的证据。

> **判定**：Round-1 的根因（"TLS 信任锚没配"）**已被证伪**，`docs/LIBMPV-TLS-CA-FIX-20261029.md`
> 的结论部分已就地标注更正。CA 相关代码保留，但降级为**兜底路径**的配置，不再是主修法。

---

## 2. 为什么 exo 能播、mpv 不能播（逐项拆开）

`PlayerViewModel.createPlayer()` 给 exo 的与 `MpvPlaybackEngine.startLoad()` 给 mpv 的，
**输入是同一条 URL**（`MpvMediaSources.resolve` 对 `http(s)` 原样透传，见 `MpvMediaSource.kt:89-95`），
差别全在「这条 URL 是怎么被打开的」。

| 环节 | exo（能播） | mpv（播不了） | 后果 |
|---|---|---|---|
| HTTP 实现 | `DefaultHttpDataSource`（OkHttp 无关，但用 `HttpURLConnection` + 平台 TLS） | `libavformat` 的 lavf http 协议 + **静态 mbedtls** | 两套完全不同的网络栈 |
| TLS 库 | 系统 Conscrypt（系统 CA + 用户 CA，支持 TLS 1.3 / ALPN / SNI） | 编译进 so 的 mbedtls（版本未知，**不读** `/system/etc/security/cacerts`） | 老版本 mbedtls 对 TLS 1.3、部分密码套件、SNI 组合支持不全 → 握手失败 |
| 证书校验 | 校验（系统级） | **默认不校验**（见 §1），且本仓显式置 `tls-verify=yes` 后**又没有可用 CA** | Round-1 的 `tls-verify=yes + 缺 CA` 组合，反而**可能**把一条本来能通的链接弄断（见 §5 未验证项 1） |
| UA | `setUserAgent(DEFAULT_USER_AGENT)`（Chrome 111 / macOS） | `user-agent` 全局项（Round-1 前是自造的 `Homemovies/MPV`；本轮已改为同一个常量） | UA 不一致 → 云盘直链 403 |
| 默认请求头 | `setDefaultRequestProperties(mapOf("User-Agent" to ua))`，**逐跳生效** | `http-header-fields` / `user-agent` 属 **stream_lavf 流选项**，本轮之前靠 `file-local-options/` 前缀投机下发 | 下发失败即静默 → 403 |
| 重定向 | `setAllowCrossProtocolRedirects(true)`（http↔https 均可） | lavf 默认 `redirection_limit=0`（不跟随）或按版本行为不一 | 302 → 直接失败 |
| 错误可见性 | `PlaybackException` 带 http 状态码 | 一律折叠成 `-13` | 无法定位（本轮已修：错误文案带 mpv 日志尾巴，见 §4） |

**这就是「同一 URL、exo 能播、mpv 报 -13」的结构性原因**：不是某个参数写错，
而是**整个网络层的实现换掉了**。只要云盘直链对 UA / Referer / 重定向 / TLS 指纹有任何要求
（对象存储直链基本都有），mpv 就必然打不开，且错误码永远是那一个 `-13`。

---

## 3. 修法：进程内回环代理（`MpvHttpProxy`）

```
mpv ──HTTP/1.1 明文──▶ 127.0.0.1:<随机端口> ──▶ MpvHttpProxy ──OkHttp──▶ https://<云盘>/xxx.mp4
                          (Range 透传)                        (系统 TLS / UA / 重定向 / 代理策略)
```

设计要点（代码见 `app/src/main/java/com/example/localmovielibrarz/playback/engine/MpvHttpProxy.kt`）：

1. **只 bind `127.0.0.1`**，端口随机（`ServerSocket(0)`），路径带一次性 `UUID` token
   → 局域网不可达、别的 app 猜不到路径。
2. **上游 URL 与请求头在 `register()` 时登记**；mpv 发来的请求头**除 `Range` 外一律忽略**
   → 彻底避免 mpv 的全局 `user-agent` 污染 exo 的 UA。
3. **`Range` 原样透传，`206` + `Content-Range` 原样回传** → seek / 断点续读行为与 exo 等价。
4. **流式转发**（64 KB 一块，边收边 flush）→ 首块到达即可起播，不等整片。
5. OkHttp 侧：`followRedirects(true)` + `followSslRedirects(true)`
   → 对应 exo 的 `setAllowCrossProtocolRedirects(true)`；`callTimeout=0`（一部电影读几十分钟）；
   读超时 120s（连续无字节才算断）；**不覆盖系统代理**——exo 的 `DefaultHttpDataSource` 建在
   `HttpURLConnection` 上会走 `ProxySelector.getDefault()`，所以这里也必须走默认 ProxySelector。
   （早先写死 `Proxy.NO_PROXY` 是错的，已改。）
6. 未启动/注册失败时 **返回 null → 退回原直连**，只记一条 WARN，不阻断播放。
7. **线程池有上界**：`ThreadPoolExecutor(4, 16, 30s, SynchronousQueue, …)`。核心 4 个常驻，
   来不及时扩到 16，再多就抛 `RejectedExecutionException` 让 `acceptLoop` 关掉该 socket
   （mpv 会重试）。绝不让 accept 线程阻塞——阻塞表现为播放卡住而不是报错。
   早先是 `newCachedThreadPool`（无上限），seek/重连风暴下线程数没有天花板。

### 落到代码的调用点

| 位置 | 改动 |
|---|---|
| `MpvPlaybackEngine.kt:145-152` | `initialize()` 里启动代理；失败则 `httpProxy = null` 并 WARN |
| `MpvPlaybackEngine.kt:311-326` | `startLoad()`：`val proxiedUrl = httpProxy?.register(url, headers)`，命中则 `loadfile <127.0.0.1…>` 并**跳过** `applyHeaders` |
| `MpvPlaybackEngine.kt:241-250` | `release()`：`destroyEngine()` **之后**再 `httpProxy.close()`（先断 socket 会让 mpv 多吐一条 read error） |
| `MpvOptions.kt:51-78` | `user-agent` 改为与 exo **同一个常量** `DEFAULT_USER_AGENT`；`tls-verify`/`tls-ca-file` 注明只服务直连兜底，且**两者必须成对**：有 CA 包才写 `tls-verify=yes`，没有就显式写 `tls-verify=no`（否则 mbedtls 报 `No CA chain is set`，等于自己造一个 -13） |
| `MPVLib.kt:308-341` | 新增 `LogSink` 旁路 + `logSinks`，把 mpv 日志同时喂给引擎 |
| `MpvHttpProxyTest.kt`（新增单测） | 本地假上游 + 手写极小 HTTP 客户端，钉死「逐字节等价」：200/206/HEAD/chunked/502/403/token 校验/`close()` 后拒连 |

---

## 4. 顺带修掉的「错误不可诊断」

原实现把 end-file 事件折叠成一句
`mpv 播放失败：loading_failed（error=-13, reason=error/4），url=…（scheme=https）`。
**这条信息量约等于零**：TLS 握手失败、HTTP 403、DNS 失败、协议不支持，在 mpv 里全是 -13。

现在错误文案追加两段：

1. `，网络=回环代理（OkHttp/系统 TLS）` 或 `，网络=mpv 自带 TLS 直连`
   —— 一眼看出这次走的是哪条路径（两条路径的失败含义完全不同）；
2. `；mpv 日志：[lavf] HTTP error 403 Forbidden | [lavf] Failed to open …`
   —— mpv 自己的 lavf/http 报错，最多 8 条、每条截断 200 字。

同时代理层会把**上游状态码**打进 logcat：
`MpvHttpProxy: 代理 GET https://<host>/<path> → 403（Range: bytes=0-）`。

而且引擎会直接把这个码**写进错误文案**（不只是 logcat）：

```
mpv 播放失败：loading_failed（error=-13, reason=ERROR/4），url=https://host/x.mp4（scheme=https），
网络=回环代理（OkHttp/系统 TLS），上游 HTTP 403（cdn.example.com）；
mpv 日志：[lavf] HTTP error 403 Forbidden | [lavf] Failed to open …
```

这一步是**分诊关键**：

| 错误文案里出现 | 说明 |
|---|---|
| `上游 HTTP 403` | 上游拒了。与 TLS 栈、mpv 移植**无关**；查签名过期 / UA 被拦 / 链接失效 |
| `上游 HTTP 404/410` | 链接已死，刷新直链 |
| `上游未返回任何响应` | 连接/握手阶段就失败（DNS、TCP、TLS），才轮到怀疑网络栈 |
| 无「网络=…」段 | 这条 URL 根本不是 http(s)，与网络层无关 |

> 也就是说：下一次再失败，日志里会直接出现 `403` / 上游未响应 / TLS 报错原文，
> 而不是只有一个 `-13`。

**请求头被丢弃也要留痕**：早先 `runCatching { builder.header(...) }` 会静默吞掉
`IllegalArgumentException`（Referer 里带中文/空格时），该头整条丢失、上游回 403，
而日志里看不出「头没发出去」。现在记 `请求头 <name> 被 OkHttp 拒绝` 的 WARN。

**URL 被重新编码也要留痕**：直链签名通常对原始 URL 字节计算，而 OkHttp 会把 URL
解析成 `HttpUrl` 再序列化、按 RFC 3986 归一化。若发现 `normalized != trimmed`，
`register()` 记一条 WARN 并在其中提示「若上游返回 403，优先怀疑签名失配」。

---

## 5. 未验证项（不得当作已验证结论）

1. **代理是否能真正解决用户的这条链接**：本地无 JDK/Android SDK，也拿不到用户的真实直链
   （用户已脱敏为 `XXXXX.mp4`），因此**未在真机复现验证**。
   验证方法：装本轮 APK → 播放失败时看 logcat
   `MpvHttpProxy: 代理 GET … → <status>`；若 status=200/206 却仍失败，则问题在 mpv 的解复用而非网络。
2. **Round-1 的 `tls-verify=yes` 是否曾把链接弄断**：mpv 默认 false 是确定的（§1），
   但本仓 `libmpv.so` 是否被改过默认值**未验证**（需 `mpv_get_property("tls-verify")` 实测）。
   本轮已把主路径移出 TLS 分支，且把 `tls-verify` 与 `tls-ca-file` 改成**成对下发**
   （无锚则显式 `no`），"开了校验却没有锚"这个组合在代码层面已不可能再出现。
3. **mbedtls 版本**：只知道是静态链接，具体版本与是否支持 TLS 1.3 未确定
   （需读 so 里的 mbedtls 版本串，本地无 readelf，未做）。
4. **云盘直链是否真的校验 UA**：属于推断（对象存储直链普遍校验），没有该域名的对照实验。
5. **代理对 4K 大码率的吞吐影响**：本地回环多一次内存拷贝（64 KB 缓冲），
   理论带宽远高于解码需求，但**未实测**。

---

## 6. 与 exo 的对齐结论（本层）

| 维度 | 结论 |
|---|---|
| 网络实现 | ✅ 本轮起两条路径**共用 OkHttp/系统 TLS**（mpv→回环代理→OkHttp） |
| UA | ✅ 同一个常量 `DEFAULT_USER_AGENT` |
| 默认请求头 | ✅ 由代理在**每一个上游请求**上逐跳注入（等价于 `setDefaultRequestProperties`） |
| 跨协议重定向 | ✅ OkHttp `followSslRedirects(true)` |
| Range / 206 | ✅ 原样透传 |
| 错误码 | ⚠️ 仍不可能 1:1（exo 有 HTTPS 状态码分类 + 自动刷新，mpv 只有 -13），但错误文案已带上游状态码 + mpv 日志 + 网络路径，**可定位性追平**；自动刷新直链仍未接 |
| 系统代理 | ✅ exo 的 `DefaultHttpDataSource` 走系统 `ProxySelector`；代理侧已改回 OkHttp 默认（删除 `Proxy.NO_PROXY`），同语义 |
| 请求头 | ✅ 逐跳注入；被 OkHttp 拒绝时记 WARN，不再静默丢弃 |
| 并发 | ✅ 线程池有界（4/16 + SynchronousQueue），拒绝即断连让 mpv 重试 |

---

## 7. 第二轮 hardening：把代理自身的失败面堵掉（本轮）

代理把「mpv 的网络」换成了「OkHttp 的网络」，但它自己也是新代码，任何一处把字节搞错，
表现出来的仍然是同一个 `-13`——**比原来更难查**。所以本轮逐条对着「会让代理自己造出 -13」
的可能做了加固，每一条都能对应到一句可验证的行为：

| # | 原缺陷（会让代理自己造 -13） | 现在 | 验收方式 |
|---|---|---|---|
| 1 | `forward()` 里 `execute()` 抛异常时只 `Log.d`，socket 直接关，一个字节都不写 | 回 `502 Bad Gateway: <异常>` 并写进 `lastUpstreamError` | `unreachableUpstreamYields502AndRecordsError` |
| 2 | OkHttp 默认协商 gzip 并透明解压 → `contentLength()` 变 -1、Range 偏移失真 | 显式 `Accept-Encoding: identity` | `plainGetForwardsBodyAndLength` / `rangeRequestReturns206WithContentRangeEchoed` |
| 3 | `close()` 只关 `serverSocket`，在途连接要等 30 s `soTimeout` 才死 | `activeSockets` 逐个 close，`soTimeout` 只是兜底 | `closeStopsAcceptingConnections` |
| 4 | 请求头被 OkHttp 拒绝时 `runCatching` 静默丢弃（Referer 带中文就丢） | `try/catch` + WARN 留痕 | 无单测（需构造非法头），靠代码审查 |
| 5 | 线程池无界（`newCachedThreadPool`） | 4/16 + `SynchronousQueue`，满了直接拒连让 mpv 重试 | 无单测 |
| 6 | 上游表只留 8 条，切集后旧 id 的迟到 Range 会 404 | 留 32 条 | 无单测 |
| 7 | **非代理缺陷**：`g_mpv` 是进程级单例，旧引擎迟到的 `release()` 会把新实例拆掉 | `liveEngine` 所有权判定，非属主跳过 `destroyEngine()` | 代码审查（`MpvPlaybackEngine.kt:246-256`） |
| 8 | `ytdl_hook` 会拦截所有 `^https?://`（含 `127.0.0.1`）去跑一遍不存在的 yt-dlp | `ytdl=no`（`libmpv.so` 里 `ytdl` 是独立全局选项，见 §7.1） | 代码审查 + 二进制取证 |

### 7.1 `ytdl` 是全局选项，不是 `stream-lavf` 选项

在 `libmpv.so` 里 `ytdl` 只以独立 NUL 结尾字符串出现一次（`0x10af7b`），邻居是
`embeddedfonts | force-rgba-osd-rendering | system | current | shuffle | ytdl | load-osd-console …`
——这是 `options/options.c` 里那批 `OPT_FLAG` 名字的连续落点，说明它和 `embeddedfonts`
同级，是**全局**选项，可以在 `mpv_initialize()` 之前用 `mpv_set_option_string` 下发。
`ytdl_hook.lua` 的来源串 `ytdl_hook` 只有 3 处（`0x7d4d6` 日志区、`0x9336e` 与 `0x95d9b` 脚本区），
没有任何 `Load URLs via` 之类的帮助串，与"它是由内置脚本消费的全局开关"这一判断一致。

### 7.2 本轮的单元测试

新增 `app/src/test/java/com/example/localmovielibrarz/playback/engine/MpvHttpProxyTest.kt`
（纯 JVM，无需设备、不联网）。它自带一台本地假上游，并手写一个极小的 HTTP 客户端，
避免 `HttpURLConnection` 对 chunked / HEAD 的隐式改写干扰断言。覆盖：

| 用例 | 钉住的性质 |
|---|---|
| `registerRejectsNonHttpAndUnparsableUrls` | 非 http(s)、空串、不可解析串必须返回 null（调用方才能退回直连） |
| `registerReturnsLoopbackUrlKeepingFileName` | 回环 URL 带端口、保留扩展名、**不带签名 query** |
| `plainGetForwardsBodyAndLength` | 状态码 / Content-Length / body 逐字节一致；UA 真的发出去了 |
| `rangeRequestReturns206WithContentRangeEchoed` | `Range` 原样转发、`206` + `Content-Range` 原样回传、分片长度正确 |
| `headRequestForwardsHeadersWithoutBody` | HEAD 有合法 Content-Length 且不发 body |
| `responseWithoutLengthFallsBackToChunked` | 上游无长度 → 下游 chunked，解码后字节不变 |
| `unreachableUpstreamYields502AndRecordsError` | 连不上 → 502 + `lastUpstreamError` + `lastUpstreamStatus=-1` |
| `upstreamErrorStatusIsPassedThrough` | 上游 403 原样透传并记进 `lastUpstreamStatus` |
| `wrongTokenIsRejected` | token 不对 → 403，且**上游一次都没被访问** |
| `closeStopsAcceptingConnections` | `close()` 后端口拒连、`register()` 返回 null |

为让它们能在纯 JVM 上跑，`app/build.gradle.kts` 加了
`testOptions { unitTests.isReturnDefaultValues = true }`（否则碰 `android.util.Log` 会抛
`Method i in android.util.Log not mocked`）。

CI 侧在「构建 APK」之前插入一步：

```bash
./gradlew --no-daemon :app:testDebugUnitTest --tests '*MpvHttpProxyTest*' -PmpvAbis="$ABIS"
```

放在构建之前是有意的：这些用例几秒钟跑完，先挡住"代理把字节搞错"这一类问题，
比等十几分钟编完 APK 再发现要划算。

### 7.3 仍未验证（不得当作已验证）

1. **上述单测没有在本机跑过**——本机无 JDK / Android SDK（`command -v java` 为空，
   `~/.gradle` 下只有空的 `wrapper` 目录）。它们的首次真实执行会发生在 CI。
2. **代理是否真能让用户那条 `XXXXX.mp4` 播起来**：仍缺真机复现。
3. **`ytdl=no` 是否被该版本接受**：`mpv_set_option_string` 的返回值已在调用点记 WARN，
   真机日志里出现 `设置 mpv 选项 ytdl=no 失败: -5` 即说明判断有误。
