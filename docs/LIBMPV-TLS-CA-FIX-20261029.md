# mpv https 直链 `loading_failed（error=-13,reason=error/4）` 根因与修复（2026-10-29）

> ⚠️ **本文结论已于同日被证伪并更正，请以 `docs/MPV-NETWORK-PROXY-20261029.md` 为准。**
>
> 更正要点：mpv v0.41.0 的 `stream/stream_lavf.c` 里 `tls-verify` 的默认值是 **false**
> （`OPT_FLAG("tls-verify", NULL, 0, false)`），即 **mpv 默认不校验证书**。
> 因此「无 CA 信任锚 → 握手失败 → -13」的因果链**不成立**，本轮 `-13` 的真因是
> **网络层实现整体被换掉**（UA / 默认请求头 / 跨协议重定向 / TLS 栈四类差异），
> 修法改为**回环代理**，见新文档。本文保留作为排查过程的记录与兜底路径的说明。
>
> 依然成立的部分：CA 包 + `tls-ca-file` 仍是「退回直连」时的正确配置；错误码 -13 确实
> 由 lavf 打开失败折叠而来。已失效的部分：把它当作**主根因**。

> 本文对应交付包 `homemovies_guangya_fix1028_all_in_one_arm64` 的这一次修复。
> 现象原文：`提示 mpv 播放失败：loading_failed（error=-13,reason=error/4），url=XXXXX.mp4（scheme=https）`

## 0. 一句话结论

**`libavformat.so` 是静态 mbedtls 构建，mbedtls 后端没有「读取 Android 系统 CA」的能力，
而应用既没有下发 `tls-ca-file`、包里也没有任何 PEM 证书包；在 mpv 默认 `tls-verify=yes`
下，https 握手在「无信任锚」处失败，ffmpeg 打不开 URL → mpv 折叠成 `MPV_ERROR_LOADING_FAILED(-13)`
→ `end_file(reason=MPV_END_FILE_REASON_ERROR=4)`。** 修复 = 随包带一份 PEM 根证书包 +
启动时解到 `filesDir` + 以 `tls-ca-file` 显式喂给 libmpv。

exo 不踩这个坑：`DefaultHttpDataSource` 走平台 TLS（系统 CA + 用户 CA 自动生效）。
这就是「换 mpv 内核后 https 全挂、切回 exo 就正常」的结构性差异。

## 1. 证据（可复核）

| 证据 | 值 / 结论 | 复核命令 |
|---|---|---|
| mpv 版本 | v0.41.0（`libmpv.so` 内字面量） | `strings libmpv.so \| grep -m1 '^mpv v'` |
| TLS 后端 | mbedtls 静态链入；`DT_NEEDED` 里**没有** `libssl.so` / `libcrypto.so` | `llvm-readelf -d libavformat.so` |
| mbedtls 证据 | `libavformat.so` 内命中 `ssl_tls.c`、`No CA chain is set…`、`mbedtls_ssl_get_verify_result reported problems…`（offset ≈ `0x4f7f3`） | `strings -t x libavformat.so \| grep -i 'CA chain'` |
| 系统 CA 路径 | `libavformat.so` / `libmpv.so` 内 `/system/etc/security`、`cacerts`、`/etc/ssl`、`SSL_CERT_FILE` **全部 0 命中** | `strings libavformat.so \| grep -cE 'cacerts\|SSL_CERT_FILE'` |
| lavf TLS 选项 | 只有 `ca_file`，**没有 `ca_path`**；`tls_mbedtls` 在 `ca_file=NULL` 时不自寻 CA | `strings libavformat.so \| grep -E '^ca_file$\|ca_path'` |
| 应用侧设置 | 全仓 `tls-verify` / `tls-ca-file` / `cacert` / `ca_file` 改动前 **0 命中**，无 `.pem/.crt/.cer` | `grep -rn 'tls-ca-file\|cacert' app/` |
| ABI / so 加载 | 9 个 `.so` 的 `PT_LOAD p_align = 0x4000`（满足 Android 15 16 KB 页）；`libmpv.so` 的 `DT_NEEDED` 闭包完整 | `python scripts/assemble-libmpv-jnilibs.py --verify-only` |
| 决定性反证 | so 加载失败只会得到「未检测到 libmpv.so」+ `isAvailable()==false`，**根本不会出现 -13**；能回报 -13 ⇒ libmpv 已加载且工作 | 见 `MpvMediaSource.kt` 的 content:// 归因分支 |

## 2. 错误码为什么长这样

mpv 的 `MPV_ERROR_LOADING_FAILED = -13` 是**装载层**的聚合码：`loadfile` 之后任何让
`demux_open` 失败的原因都被压成它，原始错误只进日志（`[ffmpeg]` / `[cplayer]`）。
`reason=4` 是 `MPV_END_FILE_REASON_ERROR`，即「非 EOF 的异常结束」。
所以 **-13 单独不可用于判因**——TLS 无锚、HTTP 403、DNS/超时都能给出 -13。

本次能定死 TLS 的理由不是错误码，是三个结构性事实的合取：
① lavf 里没有任何系统 CA 路径字符串；② lavf 只有 `ca_file` 没有 `ca_path`；
③ 应用从未下发过 `ca_file`。三者同时成立时，https **必然**在验证阶段失败。

## 3. 交叉审查结论与冲突判定

三个独立视角（移植正确性 / 与 exo 对齐 / 根因对抗）结论与冲突：

| # | 议题 | 视角 A（移植正确性） | 视角 B（exo 对齐） | 视角 C（根因对抗） | 判定 |
|---|---|---|---|---|---|
| 1 | -13 主因 | TLS 信任锚缺失 | exo 走平台 TLS，mpv 无对等配置 | 支持 TLS，但指出单凭错误码不可分 | **TLS 无锚（高置信）** |
| 2 | HTTP 403 / UA 是否主因 | 非主因，属第二候选 | exo 的 `setDefaultRequestProperties` 与 mpv 的 `http-header-fields` 不对等 | 未验证，真实第二候选 | **未验证，保留为第二候选** |
| 3 | native 是否走系统代理 | 不涉及 | 不涉及 | App 代理是 OkHttp 层，mpv 原生 socket 不吃 | **未验证（盲区）** |
| 4 | `cache-dir` 是否生效 | 不生效（选项不存在） | exo 有 `LoadControl` | 确认 `-5 = MPV_ERROR_OPTION_NOT_FOUND` | **不生效，已修** |
| 5 | `hwdec` 判定时机 | 时序错（在 `createEngine` 前问） | — | 与 -13 无关 | **移植缺陷，已修** |
| 6 | `http-header-fields` 逗号 | 潜在拆分风险 | exo 用 Map 无此问题 | — | **移植缺陷，已修** |
| 7 | 字幕样式选项 | `sub-font` 等名在 v0.41 不存在 | exo `CaptionStyleCompat` 有对等需求 | — | **已改为 `sub-ass-style-overrides`＋`sub-scale`；「描边+底框同时存在」判定为不可等价** |

**显式冲突点（必须点名）**：

1. **「`sub-font` 存在吗」** —— `docs/MPV-EXO-PARITY.md` 把 `sub-font=…` 记成 ✅，
   但 `libmpv.so` 全量字符串里 `sub-font` **0 命中**，`sub-font-provider` 同样 0 命中。
   本仓 libmpv 是把字体相关编译裁剪过的构建。**判定：以二进制为准，文档误报**；
   字体兜底改走 `sub-ass-style-overrides` 里的 `FontName=sans-serif`。
2. **「错误码能不能定位原因」** —— 一方认为 `-13/reason=4` 已足以判 TLS，
   另一方（对抗视角）坚持单凭错误码不可分。**判定：采纳对方观点**。
   本报告不靠错误码定因，靠 §1 的三条结构性事实；错误码只用于确认「装载层失败」。
3. **「mbedtls 会不会读系统 CA」** —— 对抗视角试了「它可能读 `/system/etc/security/cacerts`」，
   结论是否证（0 命中，且 lavf 无 `ca_path`，而 Android 系统 CA 是 DER 目录格式、
   `x509_crt_parse_file` 只吃 PEM 单文件）。**判定：不支持读系统 CA，反证成立**。

## 4. 修复清单（每处对应哪条原因）

| # | 文件 | 改动 | 对应原因 |
|---|---|---|---|
| 1 | `app/src/main/assets/mpv/cacert.pem`（新增，297 255 B） | 随包分发 Mozilla 根证书 PEM 包（certifi 同源） | 原因 A：无信任锚 |
| 2 | `playback/engine/MpvCaBundle.kt`（新增） | asset → `filesDir/mpv/cacert.pem`（先写 `.tmp` 再 rename，体积校验 > 64 KiB），返回绝对路径；放 `filesDir` 因为 `cacheDir` 会被系统清空，路径消失即重回 -13 | 原因 A |
| 3 | `playback/engine/MpvOptions.kt` | `tls-verify`/`tls-ca-file` **成对**下发：有 CA 包才 `tls-verify=yes` + `tls-ca-file=<绝对路径>`；CA 包不可用时显式写 `tls-verify=no`（退回 mpv 默认）。**不改**成「无条件 yes 但缺 ca_file」——那会让 mbedtls 报 `No CA chain is set, but required to operate.` 并 -13 | 原因 A |
| 4 | `playback/engine/MpvPlaybackEngine.kt` | `initializeEngine()` 调 `MpvCaBundle.ensure()` 并传入；失败打 error 日志 | 原因 A |
| 5 | `MpvOptions.kt` | `cache-dir` → `cache-on-disk=yes` + `demuxer-cache-dir`（原来 `-5` 静默丢弃，缓存目录从未生效） | 原因 D |
| 6 | `MpvOptions.kt` | `sub-font` / `sub-color` / `sub-border-color` / `sub-back-color` / `sub-border-size` / `sub-shadow-offset`（v0.41 本构建全不存在）→ `sub-ass-style-overrides`（`&HAABBGGRR` 颜色 + `BorderStyle/Outline/Shadow/FontName`）+ `embeddedfonts=yes` | 原因 E |
| 7 | `MpvPlaybackEngine.kt` | `decideHwdec()` 从 `createEngine()` **之前**移到**之后**（mediacodec 依赖 `av_jni_set_java_vm`，桥在 `mpv_create` 链里注册）；原来永远退软解 | 原因 F |
| 8 | `MpvPlaybackEngine.kt` | `applyHeaders` 对每个值做 `\` → `\\`、`,` → `\,` 转义（`http-header-fields` 是 `M_OPT_TYPE_STRING_LIST`，Cookie 里的逗号会把一个头裂成两个假头 → 403） | 原因 B（潜在诱发） |
| 9 | `.github/workflows/build-apk.yml` | 新增「断言 APK 内含 CA 包」：源文件 > 64 KiB、含 `BEGIN CERTIFICATE`、APK 内 `assets/mpv/cacert.pem` 存在 | 防回归：CA 包被误删/漏打包 → 构建期失败，而不是真机上报 -13 |
| 10 | `app/src/androidTest/.../MpvPlaybackSmokeTest.kt` | 两处 `MpvOptions.build(...)` 补 CA 参数；`engineCreatesAndInitializes` 增加 CA 包存在性断言 | 防回归 |
| 11 | `cpp/src/property.cpp`、`cpp/src/stub.cpp`、`mpv/MPVLib.kt` | `setPropertyString` / `setPropertyDouble` 由 `void` 改为**返回 `jint`**（JNI 符号名不含返回类型，且无重载，ABI 安全）；Kotlin `setString` / `setDouble` 同步返回 `Int` | 原因 G：原实现吞掉 `-5 MPV_ERROR_OPTION_NOT_FOUND`，导致「下发了个不存在的选项、界面上毫无反馈」这类故障无法定位——本次 6/7/13 三条根因都是被它藏住的 |
| 12 | `MpvPlaybackEngine.kt` | 请求头改走 `setStreamOption()`：先 `file-local-options/<name>`（与包内 ytdl_hook 同一写法，下一次 `loadfile` 生效），再裸名兜底；两者都失败才告警，并把 `-13` 后果写进日志 | 原因 B：`http-header-fields`/`user-agent` 在 mpv 里是 **stream_lavf 的选项**，裸名 `set_property` 打不进去，UA 实际未下发 → 需要 UA 的直链回 403 → 仍是 -13 |
| 13 | `MpvPlaybackEngine.kt` | `applySubtitleStyle` 重写：字号→`sub-scale`、位置→`sub-pos`、颜色/底框→`sub-ass-style-overrides`（ASS alpha **取反**）；`MpvOptions` 默认值同步为 `BorderStyle=3` + `&H66000000` | 原因 E：原 `sub-*` 名字全不存在，**字幕样式功能此前实际不生效**（不是「生效但难看」） |

### 未改动但需要知道的差异（有意保留）

- **错误码映射**：exo 的 `PlaybackException.errorCodeForIOException`（`ERROR_CODE_IO_*`）
  在 mpv 侧没有逐项对位，`-13` 是一个聚合码。要做到可分辨，只能解析 `[ffmpeg]` 日志行。
  本次**不做**：会引入日志解析的脆弱耦合，收益小于风险。
- **播放期故障回退 exo**：`MPV_EVENT_END_FILE` 的 `error != 0` 目前只上报文案，不自动切 exo。
  属于产品行为选择，不在本次 -13 范围。
- **代理**：mpv/ffmpeg 原生 socket 不吃 Android Wi-Fi 代理，若部署环境需要代理，mpv 仍会失败。

## 5. 验证

### 5.1 已在本地做的（静态，可复核）

```
python work/ref/check_brackets.py  →  4 个 .kt 文件括号/字符串配平，无未闭合
cacert.pem 297255 B，grep -c 'BEGIN CERTIFICATE' = 150（BEGIN/END 各 150，成对）
cacert.pem sha256 = c55b21f907f7f86d48add093552fb5651749ff5f860508ccbb423d6c1fbd80c7
jniLibs/arm64-v8a 9 个 .so，sha256 与 docs/verify/jnilibs-sha256.json 一致
MpvOptions.build 调用点全仓 3 处（engine 1 + androidTest 2），全部已同步签名
逐字字符串核验（本仓 libmpv.so / libavformat.so）：sub-ass-style-overrides=1、sub-scale=6、
  sub-reload=1、sub-ass-override=3、http-header-fields=3、user-agent=4、file-local-options=13、
  tls-ca-file=1、tls-verify=1、demuxer-cache-dir=1、cache-on-disk=1；
  而 sub-font-size / sub-color / sub-back-color / sub-border-color / sub-border-size 全部 = 0
JNI 返回类型三处一致（property.cpp / stub.cpp / MPVLib.kt）：setPropertyString、setPropertyDouble
```

### 5.2 GitHub Actions

1. 解压交付包 → 推到仓库（`app/**` 或 `release/**` 触发，或 Actions → build-apk → Run workflow）。
2. 因包内自带 `jniLibs/arm64-v8a`，workflow 会命中「检测 jniLibs 是否已自带库」分支，
   **跳过** 40–90 分钟的 `build-libmpv`，直接 `:app:assembleDebug -PmpvAbis=arm64-v8a`。
3. 两个硬断言必须绿：
   - `断言 APK 内含 libmpv`：arm64-v8a 的 `.so` ≥ 9 且含 `libmpv.so`
   - `断言 APK 内含 CA 包`：APK 内存在 `assets/mpv/cacert.pem`

### 5.3 真机（本次**未验证**，需在设备上补）

```bash
adb install -r app-debug.apk
adb logcat -c && adb logcat | grep -E 'MpvCaBundle|MpvPlaybackEngine|\[ffmpeg\]|\[cplayer\]|\[http\]'
```

期望看到：

```
I MpvCaBundle: CA 包就绪：/data/user/0/<pkg>/files/mpv/cacert.pem（297255 B）
I MpvPlaybackEngine: mpv 引擎就绪，hwdec=mediacodec，CA=/data/user/0/<pkg>/files/mpv/cacert.pem
```

反向判别（若仍然 -13，按下面三行分流，**不要**再猜）：

| 日志 | 判定 |
|---|---|
| `[ffmpeg] No CA chain is set, but required to operate.` 或 `mbedtls_ssl_get_verify_result … flags: <非0>` | CA 包未被加载（检查 5.3 第一行日志是否存在） |
| `[http] HTTP error 403/404` | HTTP 层（UA/Referer/Cookie/直链过期），查 `PlaybackResolver` 的续期 |
| 无上述两者、只有超时/`Connection refused` | 网络路径 / 代理 |

## 6. 未验证项（NOT RUN，明确标注）

1. 真机 logcat 的上述三行实证 —— 本环境无 arm64 设备。
2. `tls-verify=no` 对照包 —— 未构建（会掩盖真因，只作诊断用，不进交付）。
3. `curl -A <UA> <直链>` 的 403 对照 —— 直链含一次性 token，无法在离线环境复现。
4. mpv 0.41 默认 `tls-verify` 的版本级确认 —— 依据是 mpv 文档与 `libmpv.so` 内含
   `tls-verify`/`tls-ca-file` 字面量，包内无版本说明书可证；即便默认是 `no`，
   显式 `tls-verify=yes` + `tls-ca-file` 也是**更安全**的配置，不构成回归风险。
5. CI 实跑回执 —— 需推到真实 GitHub 仓库后由 Actions 给出绿/红，本地无 Android SDK/JDK。
