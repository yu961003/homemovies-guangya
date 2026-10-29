# mpv `reason=4, error=-13` 修复（content:// 直传 mpv 的移植缺陷）

> 结论先行：**Native 移植没有失败，JNI 桥是通的。** `reason=4` = `MPV_END_FILE_REASON_ERROR`、
> `error=-13` = `MPV_ERROR_LOADING_FAILED`，这两个值只有 libmpv 已经 `create` + `initialize`
> 成功、能回调 Java、能往回送 `mpv_error` 时才可能出现。真正的故障点是**把
> `content://` 直接塞给了 `loadfile`**——mpv 的 stream 层按 scheme 选实现，`content` 不在它的
> 协议表里。

---

## 1. 证据链（Observed）

### 1.1 错误码定位

| 字段 | 值 | 含义（`include/mpv/client.h`） |
|---|---|---|
| `reason` | 4 | `MPV_END_FILE_REASON_ERROR` |
| `error` | -13 | `MPV_ERROR_LOADING_FAILED` |

`reason=4` 只会在「播放被错误终止」时发出；`-13` 由 `player/loadfile.c` 的
「Failed to open <url>」路径置位，属于 **stream open 阶段失败**，不是解码失败、不是
VO/AO 失败（那两个是 `-15 VO_INIT_FAILED` / `-14 AO_INIT_FAILED`），更不是「库没加载」
（那会是 `未检测到 libmpv.so` + `isAvailable()==false`）。

### 1.2 mpv 的协议表里没有 `content`

对三份 ABI 的 `libmpv.so` 做 `llvm-strings | grep -x`，standalone 的协议名只有：

```
arm64-v8a     : data ffmpeg http https lavf mmsh mmst rtmp rtsp
armeabi-v7a   : ffmpeg http https lavf
x86_64        : ffmpeg http https lavf
```

`content` / `android_content` **命中 0 条**。上游 `stream/stream_lavf.c` 的
`stream_info_lavf.protocols[]` 是白名单式匹配，未登记的 scheme 不会进入 FFmpeg。

对照组：FFmpeg 侧**确实**编进了 content 协议、也**确实**有 JNI 挂点——

- `libavformat.so` 里有 `android_content`、`content`、
  `getContentResolver`、`openFileDescriptor`、`detachFd`；
- `libavcodec.so` 导出 `av_jni_set_android_app_ctx` 与 `av_jni_set_java_vm`
  （`--dyn-syms` 实测，版本符号 `LIBAVCODEC_62`）。

也就是说：**FFmpeg 会读 content://，但 mpv 不会把 content:// 交给 FFmpeg。**
光有 appctx 不够，必须让 mpv 拿到一个它认得的 URL。

### 1.3 调用链

```
LibraryScanner → video.uri.toString()          // content://com.android.externalstorage.documents/…
PlaybackResolver.directRequest(mediaUri)       // 原样透传（非 strm 分支）
PlayerViewModel.startMpvSession()
  └→ MpvPlaybackEngine.load(uri = request.mediaUri, …)
       └→ MPVLib.cmd("loadfile", uri, "replace")   // ← 缺陷就在这一行
```

字幕早就有 `MpvSubtitleBridge` 把 `content://` 落成普通文件（`docs/MPV-PORT-STAGES.md`
明确写了「mpv 的 `sub-add` 不走 ContentResolver，`content://` 直接传必失败」），
**唯独视频主链路漏了同一步**——这是移植遗漏，不是架构失败。

ExoPlayer 之所以正常：它自己走 `ContentResolver` / `DefaultDataSource`，天然支持 `content://`。

---

## 2. 改动

### 2.1 新增 `playback/engine/MpvMediaSource.kt`

把 `PlaybackRequest.mediaUri` 归一化成 mpv 打得开的 URL：

| 原始 scheme | 交给 mpv 的 URL | 依据 |
|---|---|---|
| `content://`（可 seek） | `/proc/self/fd/<fd>` | `openFileDescriptor(uri,"r")` 后 `pfd.statSize >= 0`；mpv 用普通 POSIX `open()` 再复制一份 fd，之后我们的 fd 关不关都不影响它；seek / SI / 跳转全正常 |
| `content://`（管道） | `cacheDir/mpv-media/<sha1>.<ext>` | `statSize == -1` 不可 seek，MP4 的 moov 在尾部，不落盘根本无法 demux |
| `file://` | 去掉 scheme 的纯路径 | mpv 两种都认，纯路径还能让 `sub-auto=fuzzy` 找同名字幕 |
| `http(s)` 等 | **原样透传** | 不碰网络链路 |

落盘有 `.part` 中间态 + 命中即复用（同 URI 重播不重复拷贝），失败会回落到原 URI，
让 mpv 报真实错误码而不是被我们掩盖。

### 2.2 `MpvPlaybackEngine`

- `load()` 改为：IO 线程 `MpvMediaSources.resolve(...)` → 回主线程 `startLoad(...)`
  发 `loadfile`。`content://` 取 fd / 落盘都可能耗时，**不能堵主线程**。
- 新增 `loadGeneration`（`AtomicInteger`）：快速连点/切集时，晚到的旧解析结果直接丢弃。
- 新增 `mediaSources`（`ArrayDeque`，最多保留 2 个）：`/proc/self/fd/<fd>` 的 fd 必须撑到
  mpv 自己 open 完；`release()` 时统一关闭，不泄漏。
- 新增 `ioExecutor`，`release()` 里 `shutdownNow()`。
- `handleEndFile` 错误文案改成带符号名 + URL 摘要：

```
改动前：mpv 播放失败（reason=4, error=-13）
改动后：mpv 播放失败：LOADING_FAILED（error=-13, reason=ERROR/4），url=/proc/self/fd/57（scheme=content）
```

  http(s) 只保留 `scheme://host/path`，query 里的 token/签名不进界面、不进日志。

### 2.3 `MPVLib`

- 补 `mpv_end_file_reason`（`QUIT=3 / ERROR=4 / REDIRECT=5`）与 `mpv_error`
  （`-2 … -20`）常量；
- 加 `errorName(code)` / `endFileReasonName(reason)`，把数字翻成人能读的名字。
  未知码输出 `ERROR_<n>`，不猜。

---

## 3. 验证

### 3.1 构建（本机实测）

```bash
cd <repo>
export ANDROID_USER_HOME="<work>/android-home"
./gradlew --offline \
  -Duser.home="<work>/android-home" \
  -Djava.io.tmpdir="<work>/fix1028/tmp" \
  -PmpvAbis=arm64-v8a assembleDebug
```

预期：`BUILD SUCCESSFUL`，且 `[mpv] jniLibs 校验通过：arm64-v8a=9 个 .so`。

### 3.2 运行期（需要真机/模拟器）

```bash
# 1) 装包
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 2) 抓 mpv 自己的日志（关键）
adb logcat -c && adb logcat -s MPVLib:V "*:S"

# 3) 播一个本地视频（SAF / 媒体库选中的 content:// 文件）
```

判据，按顺序看：

| 观察点 | 期望 | 说明 |
|---|---|---|
| `MpvMediaSource` 行 | `content:// → /proc/self/fd/NN (seekable, size=…)` | 归一化生效 |
| `MpvPlaybackEngine` 行 | `loadfile url=/proc/self/fd/NN 原始 scheme=content` | 真正下发的 URL |
| mpv 事件 | `MPV_EVENT_FILE_LOADED` → `time-pos` 递增 | 播放成功 |
| 不再出现 | `LOADING_FAILED（error=-13` | 原故障消失 |

如果是 http/strm 片源失败，`error=-13` 的原因不同（403 / 证书 / header），
日志里会带 `scheme=http`，那时要看 `[ffmpeg]` / `[http]` 行，不在本次修复范围内。

### 3.3 三种片源的回归矩阵

| 片源 | 归一化后 URL | 期望 |
|---|---|---|
| 本地 SAF（外部存储 provider） | `/proc/self/fd/N` | 播放，可拖动 |
| MediaStore（无扩展名） | `/proc/self/fd/N` | 播放（content 探测不依赖后缀） |
| 云盘 / STRM（https） | 原样 | 走原有 http 链路 |

---

## 4. 未覆盖 / 风险

- **未做真机运行验证**：本机沙箱内 Android 模拟器 QEMU 无法完成 guest 启动
  （见 `docs/verify/emulator-probe-*.txt`），`adb devices` 为空列表。上面 §3.2 的判据
  需要在有设备的环境执行一次。
- `/proc/self/fd/<fd>` 依赖 Linux 内核行为，Android 全版本可用；但极少数自定义 provider
  返回的 fd 在进程外不可寻址的情况未实测。
- 管道型 provider 落盘会占用存储：一部 4 GB 影片 = 4 GB 缓存，命中即复用，但不会自动清理。
  当前只作为兜底路径（绝大多数 provider 返回的是可 seek 的普通文件）。

---

## 5. 一句话给用户

mpv 的库没问题，是 `content://` 这个 URL 它不认。现在已经把本地视频转成
`/proc/self/fd/N` 或缓存文件再交给 mpv，并补上了能读出 `-13` 到底是谁的牌的错误文案。
