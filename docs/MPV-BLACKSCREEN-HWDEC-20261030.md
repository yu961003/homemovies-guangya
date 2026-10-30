# mpv 黑屏根因 + 内核切换按钮 + hwdec 三档（2026-10-30）

本轮处理真机反馈的三条问题：

1. mpv 已能显示**播放进度和时长**，但**画面黑屏**（声音正常）；
2. mpv / exo 切换按钮**太大、位置不对**，会遮挡字幕按钮；
3. mpv 模式下**看不到 hw / hw+ / sw 子模式**的切换入口——到底是没有这些子模式，还是没做按钮。

结论先说：① 是 **mpv 视频输出初始化时拿不到 Surface**（`wid` 设晚了）的确定性 bug；
② 是顶栏独立浮层与右侧按钮抢位；③ 是**确实没做**——旧代码只有「开/关」两态，而且那个开关
**全仓没有任何 UI 入口**（`saveMpvHardwareDecodingEnabled` 零调用点）。

---

## 1. 黑屏：`loadfile` 抢在 `wid` 之前

### 1.1 证据链（全部来自本仓源码 + mpv 上游源码）

`app/src/main/cpp/src/render.cpp` 把 Java `Surface` 的 JNI 全局引用直接当 `wid` 传，这**是对的**——
mpv 的 android VO 本来就把 `wid` 当 `jobject` 用：

```c
// mpv: video/out/android_common.c
if (vo->opts->WinID == 0 || vo->opts->WinID == -1) {
    MP_FATAL(ctx, "Missing surface pointer\n");
    goto fail;
}
jobject surface = (jobject)(intptr_t)vo->opts->WinID;
ctx->native_window = ANativeWindow_fromSurface(env, surface);
```

问题在于**时机**。`options/options.c` 里 `wid` 属于 `mp_vo_opts` 且带 `UPDATE_VO`：

```c
{"wid", OPT_INT64(WinID), .flags = UPDATE_VO},
```

而 `player/command.c` 重建 VO 的前提是 **`mpctx->video_out` 已经存在**：

```c
if (flags & UPDATE_VO && mpctx->video_out)
```

也就是说：**VO 首次初始化失败后 `video_out` 为 NULL，此时再怎么补 `wid` 都不会自动重来。**

回头看本仓的时序（修复前）：

| 步骤 | 代码位置 | 发生了什么 |
|---|---|---|
| 1 | `PlayerViewModel.startMpvSession` | `engine.initialize()` → `engine.load(...)`，返回后才 `engineKind = MPV` |
| 2 | `MpvPlaybackEngine.load → startLoad` | 直接 `MPVLib.cmd("loadfile", ...)` |
| 3 | `PlayerScreen` 的 `AndroidView` | `if (uiState.mpvReady) attach()` —— **`mpvReady` 要等装载完成才为 true** |

于是第一次 `loadfile` 到达 mpv 时，`wid` **必然还是 0** → `vo_android_init()` 打印
`Missing surface pointer` 并失败 → mpv 退化成「只放声音」：**音频、进度、时长全部正常，画面永远黑屏，
而且不会有任何 end-file 错误码**。这与真机看到的现象逐项吻合（也解释了为什么 `-13` 那条错误
在本次反馈里不再出现：装载本身是成功的）。

### 1.2 修复（三处，缺一不可）

**A. 原生状态可见** — `app/src/main/java/com/example/localmovielibrarz/mpv/MPVLib.kt`

```kotlin
@Volatile
var surfacePresent: Boolean = false
    private set
```

`attachSurfaceSafe` 返回 0 时置 `true`，`detachSurfaceSafe` 先置 `false`。

**B. 装载前等渲染面** — `app/src/main/java/com/example/localmovielibrarz/playback/engine/MpvPlaybackEngine.kt`

`startLoad` 尾部由「直接下发」改为 `awaitSurfaceThen { ... }`：

```kotlin
private fun awaitSurfaceThen(attempt: Int = 0, action: () -> Unit) {
    if (released) return
    if (MPVLib.surfacePresent) { action(); return }
    if (attempt >= SURFACE_WAIT_MAX_ATTEMPTS) { Log.w(...); action(); return }
    mainHandler.postDelayed({ awaitSurfaceThen(attempt + 1, action) }, SURFACE_WAIT_RETRY_MS)
}
```

`SURFACE_WAIT_RETRY_MS = 16L`（约一帧）、`SURFACE_WAIT_MAX_ATTEMPTS = 150`（2.4s）。
SurfaceView 的 `surfaceCreated` 与 `attachSurfaceSafe` 同样在主线程，所以 16ms 轮询与它们天然串行，
不需要再引入一套监听器生命周期。**超时仍然下发**（只出声好过什么都不播），但不许静默——
交给下面的自检兜底。

**C. 挂载不再被门控** — `app/src/main/java/com/example/localmovielibrarz/ui/player/PlayerScreen.kt`

```kotlin
factory = { MpvVideoView(it).apply { ...; attach() } },
update  = { view -> view.attach() },
```

`isMpv` 分支本身就保证了当前内核是 mpv，再叠一层 `mpvReady` 只会把 `wid` 推到装载之后。
另外 `MpvVideoView.attachSurface` 失败分支补上 `pendingAttach = true`：否则「Surface 比 mpv 实例先到」
（原生侧返回 -900）会丢掉待挂状态，下一轮也不再重试。

### 1.3 兜底自检：把「静默黑屏」变成一句能查的话

`FILE_LOADED` 后延后 1.5s 执行 `auditVideoOutput()`：

- 判据一：`MPVLib.getBoolean("vo-configured") == false`；
- 判据二：最近 mpv 日志里出现下列任一（这些字符串在本仓 `libmpv.so` 里逐字存在，已用字符串表核对）：

  `Missing surface pointer` / `Failed to create ANativeWindow` / `Could not attach java VM` /
  `Video output initialization failed` / `Could not create video output`

两条同时命中才动手。此时 Surface 通常已经挂上，所以**先给一次** `MPVLib.cmd("video-reload")`
（这是 `video_out == NULL` 时唯一能重建 VO 的入口），1.2s 后复查；仍失败才写 `errorMessage`：

```
mpv 画面未建立：视频输出初始化失败（<命中的 fatal 文案>）。
，url=...（scheme=https）；渲染面=已挂载/未挂载，vo=<current-vo>，hwdec=<档位>；mpv 日志：...
```

每次 `loadfile` 前 `resetVideoOutputAudit()` 复位，且**已有其他错误时不覆盖**（别用「黑屏」盖掉真正的根因）。

---

## 2. 内核切换按钮：从顶栏浮层 → 顶栏内两段式 pill

旧实现是 `PlayerScreen` 里飘在 `TopEnd` 的独立 `EngineSwitchChip`，文案拼的是两个枚举的 label：

```
内核 MPV（万能解码） → ExoPlayer（默认）
```

——又长又宽，而 `TopGradientBar` 右端本来就排着「音轨 / 字幕 / 播放模式」三个 48dp 圆钮，
两者抢同一块区域，实测遮挡字幕按钮。

现在改成一个 **96dp × 30dp 的两段式 pill**，插在 `TopGradientBar` 右侧按钮组**之前**：

- 每段只写内核缩写 `MPV` / `EXO`，当前档反白高亮，另一档半透明；
- 点击非当前段才触发切换（`onSwitch()`），切换中显示 11dp 进度圈并禁用点击；
- 垂直位置：顶栏按钮中心在 `MinTouchTarget / 2 = 24dp`，pill 高 30dp，故 `padding(top = 9.dp)` 后中线对齐。

数据流没有变：`uiState.engineKind`（只读）+ `onSwitchEngine`（唯一写入口），
`PlayerOverlay` 新增 `engineKind / engineSwitching / onSwitchEngine` 三个参数透传给 `TopGradientBar`。

---

## 3. hw / hw+ / sw：确实没做，这次补上

### 3.1 旧状态

```kotlin
// 修复前 MpvOptions.kt
fun decideHwdec(preferHardware: Boolean): String {
    if (!preferHardware) return "no"
    if (!MPVLib.isHwdecBridgeReady()) return "no"
    return "mediacodec"      // 只有这一种硬解
}
```

- 只有 `mediacodec` / `no` 两态，没有 `mediacodec-copy`（回拷）档，也就没有「hw+ / hw / sw」。
- `AppSettingsRepository.saveMpvHardwareDecodingEnabled(...)` **全仓零调用点**——
  设置界面里从来没有这个开关，播放器里也没有，所以用户「找不到切换方法」是准确的反馈。

### 3.2 新实现

`playback/engine/EngineTypes.kt` 新增 `MpvHwdecMode`：

| 档位 | id | 文案 | mpv `hwdec` |
|---|---|---|---|
| `HW_PLUS` | `hw_plus` | 硬件解码 + ／零拷贝直出（最省电，个别机型可能花屏） | `mediacodec` |
| `HW` | `hw` | 硬件解码 ／回拷上屏，兼容性更好（推荐） | `mediacodec-copy` |
| `SW` | `sw` | 软件解码 ／CPU 解码，兼容一切片源，高码率更费电 | `no` |

- `MpvOptions.resolveHwdecValue(mode)` 负责翻译；`SW` 直接 `no`，硬解档在 **FFmpeg JNI 桥接不可用时降级为 `no`**
  （桥接缺失会让 `mediacodec*` 在解码器初始化阶段失败，表现正是「黑画面 + 有声」，所以必须降级而不是硬上）。
- 持久化：新键 `mpv_hwdec_mode`，并从旧布尔 `mpv_hwdec` 迁移（`true → HW`、`false → SW`）；
  写入时**同时写旧键**（`mode != SW`），用户回滚旧版本也不会行为跳变。
- 运行期热切换：`MpvPlaybackEngine.setHwdecMode(mode)` → `MPVLib.setString("hwdec", ...)`。
  `hwdec` 是可选值属性，mpv 会丢弃当前解码器并按新档位重建视频链，**不需要重新 loadfile，也不打断播放位置**。
- UI 入口：**播放模式侧栏**（右下 `MoreVert` → 播放模式）里新增「解码（MPV）」段，仅在 mpv 内核下显示；
  三项 `PlaybackModeOptionRow` 直接复用现有行样式（标题 + 副标题 + 选中态）。

---

## 4. 验证与未验证

### 4.1 CI（已执行）

| 项目 | 结果 |
|---|---|
| run | [40982639135](https://github.com/yu961003/homemovies-guangya/actions/runs/40982639135)（branch `main`，commit `60500d3`） |
| 结论 | **success，11/11 步全绿** |
| 覆盖 | `assembleDebug -PmpvAbis=arm64-v8a` 编译通过；APK 内 `libmpv.so` + 8 个兄弟库断言；`assets/mpv/cacert.pem` 断言；`MpvHttpProxyTest` 通过 |
| 产物 | artifact `apk-debug-60500d3d…`（39.8MB zip → 53.9MB APK） |

产物体检（本机解包核对）：`lib/arm64-v8a/` 11 个 `.so`（含 `libmpv.so` 6.48MB 与 `libplayer.so`）；
`assets/mpv/cacert.pem` 297255B / 150 张证书；dex 内可检索到 `mpv_hwdec_mode`、`mediacodec-copy`、
`硬件解码 +`、`mpv 画面未建立`、`Missing surface pointer`、`surfacePresent`、`EngineSwitchPill`。

### 4.2 未验证（必须真机复验）

CI 没有 Android 设备，**播放行为无法在 CI 上验证**。装上新 APK 后请按下面三点确认：

1. **画面**：mpv 内核下应直接出画面（不再黑屏）。若仍黑，错误条会显示
   `mpv 画面未建立：视频输出初始化失败（…）；渲染面=…，vo=…，hwdec=…` —— 把那句话发出来即可定位。
2. **按钮**：顶栏右侧应出现 `MPV | EXO` 小药丸，不再遮挡「字幕」按钮；
   点非当前段即切换内核。
3. **三档**：mpv 内核 → 右下「播放模式」→「解码（MPV）」，可选「硬件解码 + / 硬件解码 / 软件解码」。
   若 `硬件解码 +` 花屏，切到 `硬件解码`（回拷）通常即可；两者都异常再切 `软件解码`。
   切换后 logcat 应出现 `运行期切换解码档位：… → hwdec=…`。

### 4.3 已知限制

- `setHwdecMode` 在属性不可写时返回负的 mpv 错误码，只写日志、不阻断播放（档位选择仍会被持久化，
  下次新建实例生效）。
- exo 内核下没有对等三档（硬解由 media3 自行决定），面板里不显示该段；攒下的选择在切回 mpv 时生效。
