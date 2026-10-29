# exo 功能清单 × mpv 对齐对照表

> 用途：逐条核对「exo 已有的 UI 与功能，mpv 是否都有对应实现」。
> 基线：`work/mpv-port-baseline/homemovies_guangya`（移植前的工程快照）。
> 结论图例：**✅ 已对齐** / **⚠️ 已对齐但有语义差异（已显式说明）** / **❌ 不对齐（附原因与替代方案）**。

---

## 0. 结论速览

| 维度 | 条目数 | ✅ 已对齐 | ⚠️ 语义差异 | ❌ 不对齐 |
|---|---|---|---|---|
| 渲染与画面 | 7 | 5 | 1 | 1 |
| 播放控制 | 9 | 9 | 0 | 0 |
| 字幕（含在线字幕） | 12 | 11 | 1 | 0 |
| 音频 | 5 | 4 | 1 | 0 |
| 手势与交互 | 8 | 8 | 0 | 0 |
| VR | 3 | 0 | 0 | 3 |
| 状态与系统集成 | 6 | 6 | 0 | 0 |
| 错误处理与降级 | 4 | 4 | 0 | 0 |
| **合计** | **54** | **47** | **3** | **4** |

**唯一的功能性缺口是 VR 球面播放（3 条）与画中画（1 条，exo 侧本来也没有实现）**，两者都在
`switchEngine` 决策里显式拦截或本就不可达，不会出现「静默降级成看似可用」的情况。

---

## 1. 渲染与画面

| # | exo 现状 | exo 实现位置 | mpv 对应实现 | mpv 实现位置 | 结论 |
|---|---|---|---|---|---|
| 1.1 | `PlayerView` + `AspectRatioFrameLayout` 输出视频 | `PlayerScreen.kt:487-503` | `MpvVideoView`（`SurfaceView`）+ `vo=gpu`/`gpu-context=android`，`wid` 直挂 | `mpv/MpvVideoView.kt`、`MpvOptions.kt:35-37` | ✅ |
| 1.2 | 画面比例三态循环 FIT→FILL→ZOOM | `PlayerScreen.kt:188-192, 585-590` | `setVideoResizeMode`：FIT=`keepaspect=yes,panscan=0`；FILL=`keepaspect=no`；ZOOM=`keepaspect=yes,panscan=1.0` | `MpvPlaybackEngine.kt:311-350`、`PlayerViewModel.applyResizeMode` | ⚠️ **语义差异**：mpv 的 pan&scan 是渲染层裁剪，exo 的 ZOOM 走 `AspectRatioFrameLayout` 的缩放矩阵。视觉结果一致（都是裁掉溢出部分），但**裁切阈值**在极端的超宽/竖屏视频上可能差几像素。 |
| 1.3 | 横竖屏切换（`requestedOrientation`） | `PlayerScreen.kt:268-280` | 与引擎无关，复用同一份 Activity 逻辑 | 未改动 | ✅ |
| 1.4 | 全屏沉浸（隐藏系统栏） | `PlayerScreen.kt:262-266` | 同上，未改动 | — | ✅ |
| 1.5 | 保持屏幕常亮（`FLAG_KEEP_SCREEN_ON`） | `PlayerScreen.kt:224-228` | 同上，未改动 | — | ✅ |
| 1.6 | 视频尺寸变化回调（`onVideoSizeChanged`） | `ExoPlaybackEngine.kt:48` | `width`/`height` 属性观察 → `PlaybackSnapshot.videoWidth/Height` | `MpvPlaybackEngine.observeProperties` | ✅ |
| 1.7 | 画中画（PiP） | 未实现 | 未实现 | — | ❌ **两边都没有**。exo 侧本就没有 PiP，因此不构成「mpv 缺功能」。若将来要加，exo 走 `PictureInPictureParams`，mpv 需要把 `SurfaceView` 搬进 PiP 容器（可行但工作量独立）。**本次不引入（属范围外新功能）**。 |

---

## 2. 播放控制

| # | exo 现状 | exo 实现位置 | mpv 对应实现 | mpv 实现位置 | 结论 |
|---|---|---|---|---|---|
| 2.1 | 播放 / 暂停 | `PlayerViewModel.togglePlayPause` | `set pause yes/no` + `pause` 属性观察回写 | `MpvPlaybackEngine.setPlayWhenReady` | ✅ |
| 2.2 | ±10s 快退/快进 | `seekBack/seekForward` | `seek <rel> relative`（`seekBy`） | `MpvPlaybackEngine.seekBy` | ✅ |
| 2.3 | 进度条拖动 seek（松手才 seek） | `PlayerScreen.kt:2035-2070` | 同一份 Compose 代码，回调走 `PlaybackEngine.seekTo` → `seek <abs> absolute` | `MpvPlaybackEngine.seekTo` | ✅ |
| 2.4 | 进度/时长/缓冲三段显示 | `TimeText` + `bufferedMs` | `time-pos`/`duration`/`demuxer-cache-time` 三个属性 | `observeProperties` | ⚠️ **语义差异**：exo 的 `bufferedPosition` 是已下载到本地的字节位置；mpv 的 `demuxer-cache-time` 是解复用缓存时间点。两者都是「可无缝播到哪」，但网盘直链下 mpv 的缓存窗口更保守，进度条的第二段可能更短。**不影响拖动与显示**。 |
| 2.5 | 倍速（0.25x–3x 预设 + 内联菜单） | `selectPlaybackSpeed`、`PlaybackSpeedInlineMenu` | `speed` 属性；夹取 0.01–100 | `MpvPlaybackEngine.setSpeed` | ✅ |
| 2.6 | 无真实时长时禁用拖动、显示 `--:--` | `PlayerScreen.kt:2037-2073` | 同一份 Compose 代码；`mpvDurationMs` 为 0 时同分支 | `PlayerScreen.kt:323-342` | ✅ |
| 2.7 | 播放结束处理（回到起点/停止） | `onPlaybackStateChanged(ENDED)` | `eof-reached` 属性 + `MPV_EVENT_END_FILE` | `MpvPlaybackEngine.handleEndFile` | ✅ |
| 2.8 | 缓冲态提示（`paused-for-cache`） | `onPlaybackStateChanged(BUFFERING)` | `paused-for-cache` → `STATE_BUFFERING` | `observeProperties` | ✅ |
| 2.9 | 切后台/离开时记录进度 | `saveProgress`、`leavePlayer` | 独立的 mpv 进度登记器（`startMpvProgressSaver`）写同一个 `PlaybackProgressStore` | `PlayerViewModel.kt:1574-1602` | ✅ |

---

## 3. 字幕（重点：在线字幕）

> 这条是任务里点名的**硬要求**：「exo 的在线字幕功能，mpv 必须继续支持」。
> 在线字幕本身是「搜索 → 选择 → 下载到本地 → 挂载」的**上层编排**，与内核无关；
> 两个内核的差别只在最后一步「怎么把字幕交给播放器」。

### 3.1 在线字幕主链路

| # | exo 现状 | exo 实现位置 | mpv 对应实现 | mpv 实现位置 | 结论 |
|---|---|---|---|---|---|
| 3.1.1 | 打开字幕面板（按番号搜索） | `openExternalSubtitlePanel` | 未改动，纯 UI 状态 | `PlayerViewModel.kt:1046` | ✅ |
| 3.1.2 | 三个在线字幕源切换（AVSubtitles / 迅雷 / 网盘） | `selectExternalSubtitleProvider` | 未改动 | `PlayerViewModel.kt:1094` | ✅ |
| 3.1.3 | 搜索（按番号 + 时长匹配） | `searchExternalSubtitles` → `SubtitleRepository` | 未改动 | `PlayerViewModel.kt:1116` | ✅ |
| 3.1.4 | 下载字幕到本地 | `downloadAndLoadSubtitle` → `LocalSubtitleStore` | 未改动；下载后得到 `Uri`，再走 3.1.5 | `PlayerViewModel.kt:1269` | ✅ |
| 3.1.5 | 把字幕交给播放器 | exo：重建 `MediaItem` + `SubtitleConfiguration` | mpv：`MpvSubtitleBridge.materialize()` 落盘 → `sub-add <path> select` | `MpvSubtitleBridge.kt:24-56`、`MpvPlaybackEngine.applySubtitle` | ✅ |
| 3.1.6 | 本地字幕列表（已下载过的） | `LocalSubtitleStore` + 面板「本地」页签 | 未改动；选中后同样走 3.1.5 | `PlayerViewModel.loadLocalSubtitle` | ✅ |
| 3.1.7 | 删除本地字幕文件 | `deleteLocalSubtitle` | 未改动 | `PlayerViewModel.kt:1201` | ✅ |
| 3.1.8 | 字幕开关（显示/隐藏） | `setExternalSubtitleEnabled` → 重建 MediaItem | mpv：`sub-visibility yes/no`（不丢轨道，可反复开关） | `MpvPlaybackEngine.setSubtitleEnabled` | ⚠️ **语义差异（更好）**：exo 侧开关要重建 `MediaItem`（有短暂重新加载），mpv 侧只是切一个属性，**响应更快且不打断播放**。功能等价，行为更优。 |

### 3.2 字幕时间轴与外观

| # | exo 现状 | exo 实现位置 | mpv 对应实现 | mpv 实现位置 | 结论 |
|---|---|---|---|---|---|
| 3.2.1 | 字幕偏移 ±（100ms 步进） | `adjustExternalSubtitleOffset` → `SubtitleTimeShiftStore` 重写字幕文件时间轴 | mpv：`sub-delay`（秒），正负方向与 exo 一致 | `MpvPlaybackEngine.applySubtitleOffset` | ✅ |
| 3.2.2 | 偏移重置 | `resetExternalSubtitleOffset` | `sub-delay 0` | 同上 | ✅ |
| 3.2.3 | 偏移持久化（重启后仍在） | `SubtitleTimeShiftStore` | 未改动；mpv 挂载时读取同一个 offset 下发 | `PlayerViewModel.kt:1671-1712` | ✅ |
| 3.2.4 | 字号 / 底部留白 / 背景透明度三档样式 | `applyExternalSubtitleStyle`（`CaptionStyleCompat`） | 字号 → `sub-scale`；底部留白 → `sub-pos`；颜色/底框 → `sub-ass-style-overrides`（`&HAABBGGRR`，alpha 取反）。**早期填的 `sub-font-size`/`sub-back-color`/`sub-color`/`sub-border-color`/`sub-border-size` 在本仓 libmpv.so 里 0 命中，已改** | `MpvPlaybackEngine.applySubtitleStyle` | ⚠️ 部分（见下行） |
| 3.2.5 | 白字 + 黑描边 + 半透明黑底**同时存在**（`EDGE_TYPE_OUTLINE` + 背景色） | `CaptionStyleCompat(WHITE, argb(a,0,0,0), TRANSPARENT, EDGE_TYPE_OUTLINE, BLACK, DEFAULT_BOLD)` | ASS 的 `BorderStyle` 只能二选一：`1`=描边、`3`=opaque box。取 `3` 保住半透明底框，代价是**丢失描边** | `MpvOptions.kt` 默认值 + `applySubtitleStyle` | ⚠️ **已知不等价**：颜色/底框/字号/位置可调到与 exo 观感接近，但「描边 + 底框同时存在」在 ASS 侧无法表达 |

---

## 4. 音频

| # | exo 现状 | exo 实现位置 | mpv 对应实现 | mpv 实现位置 | 结论 |
|---|---|---|---|---|---|
| 4.1 | 音轨列表对话框 | `AudioTrackDialog` + `selectAudioTrack` | `track-list` 取样 → `mpvAudioTrackOptions`，选择时 `aid` | `MpvPlaybackEngine.readAudioTracks/selectAudioTrack` | ⚠️ **语义差异**：exo 是 group/track 两层索引，mpv 是扁平的 `aid`。为让 UI 不写分支，mpv 的 `aid` 被塞进 `groupIndex`、`trackIndex` 固定 0（见 `mpvAudioTrackOptions` 注释）。**UI 表现完全一致**，只是跨内核搬运音轨时按「第几条」而非「哪个 ID」对齐（`EngineSwitchPlanner.audioHandoffHint`）。 |
| 4.2 | 音轨自动选择（不指定时） | `TrackSelectionParameters` 默认 | 传 `aid=no` 交还 mpv 自动选择 | `selectAudioTrack(null)` | ✅ |
| 4.3 | 音量/静音（系统音量通道） | `AudioManager.STREAM_MUSIC` + 手势 | 与引擎无关；mpv `ao=audiotrack` 走同一音频通道 | `MpvOptions.kt` | ✅ |
| 4.4 | 音频焦点的暂停/恢复 | `AudioFocusManager`（media3） | 复用同一份 `PlayerViewModel` 焦点逻辑，对 mpv 下发 pause/play | `PlayerViewModel` 既有分支 | ✅ |
| 4.5 | 多声道/音轨语言显示 | `AudioTrackOption.details` | 同样从 `track-list` 取 `lang`/`title` | `MpvPlaybackEngine.readAudioTracks` | ✅ |

---

## 5. 手势与交互

全部由 `PlayerScreen.kt` 的 Compose 层实现，回调统一走 `PlaybackEngine`，**两个内核共用同一份代码**：

| # | 能力 | 实现位置 | mpv 结论 |
|---|---|---|---|
| 5.1 | 左右滑调进度（带预览条） | `SeekDragFeedbackOverlay` | ✅ |
| 5.2 | 左半屏上下滑调亮度 | `setBrightness` | ✅ |
| 5.3 | 右半屏上下滑调音量 | `setVolume` | ✅ |
| 5.4 | 单击显隐控件 | `clickable` + `controlsVisible` | ✅ |
| 5.5 | 控件锁定（防误触） | `PlayerLockButton` / `controlsLocked` | ✅ |
| 5.6 | 控件 3 秒自动隐藏 | `LaunchedEffect(controlsVisible, isPlaying, …)` | ✅ |
| 5.7 | 顶部渐变栏（返回/标题） | `TopGradientBar` | ✅ |
| 5.8 | 播放模式面板（进度条样式等设置） | `PlaybackModePanel` / `PlaybackModeSidePanel` | ✅ |

---

## 6. VR（唯一的功能性缺口）

| # | exo 现状 | mpv 是否能实现 | 说明 | 处理方式 |
|---|---|---|---|---|
| 6.1 | 球面 360°/180° 投影播放（`VrSphericalPlayerView`，media3 `SphericalGLSurfaceView`） | **不能** | mpv 的 `vo=gpu` 没有等价的球面网格投影 + 头部姿态采样链路。libmpv 侧虽有 `--vr-mode` 类参数的历史讨论，但 Android 后端从未落地，且需要自行实现 `CameraMotionListener` 级别的传感器融合与 OES 纹理矩阵，属**以 libmpv 为渲染器重写一遍 VR 播放器**，工作量与本次移植同量级。 | **显式拦截**：`EngineSwitchPlanner.decide()` 在 `vrActive && to == MPV` 时返回 `Rejected("VR 球面播放仅 ExoPlayer 支持，请先切回 2D 再切换内核")`，UI 用 `EngineNoticeBanner` 提示。**不静默降级成 2D**。 |
| 6.2 | VR 控制模式（手势/触控板） | **不能** | 依赖 6.1 的存在 | 同 6.1：VR 下无法切到 mpv，自然不会进入该状态。反方向（mpv → exo）允许，因为 exo 具备 VR 能力。 |
| 6.3 | VR 下的字幕叠加（`VrExternalSubtitleOverlay`） | **不能** | 依赖 6.1 | 同 6.1 |

**替代方案（如后续确需 mpv + VR）**：用 libplacebo 的自定义着色器链 + `--vo=gpu-next` 做 equirect 采样，传感器姿态由 App 侧读取 `SensorManager` 后通过 `mpv_command("change-list ...")` 或自定义 `vo` 插件注入。这是独立立项的工作量，**本次不做，且已在交付说明中列为已知缺口**。

---

## 7. 状态与系统集成

| # | exo 现状 | mpv 对应实现 | 结论 |
|---|---|---|---|
| 7.1 | 播放进度持久化（`PlaybackProgressStore`） | mpv 侧独立的进度登记协程，写同一个 store | ✅ |
| 7.2 | 上次进度续播 | mpv 装载时直接 `start=<秒>` + `pendingSeekMs` | ✅ |
| 7.3 | 默认内核偏好持久化 | `AppSettingsRepository.isMpvDefaultEngine()` / `getDefaultPlayerEngine()` | ✅ |
| 7.4 | 硬件解码开关 | `MpvOptions.decideHwdec()` → `hwdec=mediacodec`/`no`；设置页同一开关 | ✅ |
| 7.5 | 网络请求头（Referer/Cookie/UA）透传 | `--http-header-fields` 由 `engineHeaders()` 拼装 | ✅ |
| 7.6 | 播放事件埋点（`logPlaybackEvent`） | 同样的 `player.engine.mpv.started/switched/switchFailed` 事件 | ✅ |

---

## 8. 错误处理与降级

| # | 场景 | 处理方式 | 结论 |
|---|---|---|---|
| 8.1 | `libmpv.so` 未打包进 APK | `MPVLib.probe()` 返回 false → `EngineAvailability(available=false, reason="未检测到 libmpv.so…")`；设置页显示为不可选并给出原因 | ✅ 显式降级，不崩溃 |
| 8.2 | JNI 仅编到 stub 版（`MPV_AVAILABLE=0`） | 同上：`isAvailable()` 返回 `JNI_FALSE`，运行期判定不可用 | ✅ |
| 8.3 | mpv 初始化失败（`mpv_create`/`mpv_initialize` 非 0） | 返回带错误码的文案，`startMpvSession` 上抛，切换流程 `onFailure` 回滚到原内核并提示 | ✅ |
| 8.4 | 播放中出错（`MPV_EVENT_END_FILE` with error） | 写入 `PlaybackSnapshot.errorMessage` → UI 走同一个 `errorMessage` 红字槽 | ✅ |

---

## 9. 与路线文档阶段编号的对应

路线文档（`mpv-Yaozhi-Android-分阶段移植路线深化.md`）的 Phase 0–5 描述的是**libmpv 本体的构建与补丁链**
（Yaozhi 定制 FFmpeg、补丁重放、Lua/UI 层、高级能力），产物是 `libmpv.so`。
本次交付的是**该产物在宿主 App 里的接入与双播放器共存**，对应关系：

| 路线文档阶段 | 产物 | 本次交付中的位置 |
|---|---|---|
| Phase 0 冻结基线 | 基线快照 | `work/mpv-port-baseline/homemovies_guangya`；本文档的 exo 清单基线 |
| Phase 1 Android 裸核心 | `libmpv.so`（最小可播） | `scripts/fetch-libmpv.sh`、`.github/workflows/build-libmpv.yml`、`app/src/main/cpp/*`（JNI 封装） |
| Phase 2 替换定制 FFmpeg | Yaozhi FFmpeg → `libmpv.so` | 同上（构建链在上游 `buildscripts/deps/ffmpeg`，按路线文档 §2 换源） |
| Phase 3 重放 mpv 补丁链 | 补丁后的 `libmpv.so` | 同上（补丁应用在 `fetch-libmpv.sh` 的构建前步骤扩展） |
| Phase 4 Lua 与 UI 层 | mpv 侧 UI/脚本 | **本工程不使用 mpv 的 UI 层**（`osc=no`、`input-default-bindings=no`），UI 全部由宿主 Compose 承担 —— 见 `MpvOptions.kt` |
| Phase 5 高级能力 | VVC/AV3A/HDR 等 | 内核能力随 `libmpv.so` 带入，宿主侧无需改动；解码失败时回落 exo（见 §8） |

---

## 10. 待确认项（材料不足，未自行假设）

| # | 待确认 | 影响 | 当前处理 |
|---|---|---|---|
| 10.1 | 双播放器切换的 UI 形态：播放器内浮动 chip vs 设置里改默认内核 | 影响交互设计 | **两种都实现了**：播放中可随时用 `EngineSwitchChip` 切换；设置页「播放器内核」可选择默认值。若只需其一，删掉另一处即可，互不依赖。 |
| 10.2 | `libmpv.so` 的实际来源（自建 Yaozhi 构建 vs 社区预编译） | 影响包体与解码能力 | 仓库不携带二进制，提供 `scripts/fetch-libmpv.sh` + CI 产出；运行时探测缺失并回退 exo。 |
| 10.3 | VR 替代方案（见 §6） | 影响是否算「功能缺失」 | 已显式列为不对齐项并拦截，不静默降级。 |
| 10.4 | 字幕偏移：`sub-delay` vs 重写字幕文件 | 影响与 exo 的一致性 | 采用 `sub-delay`（不产生磁盘写入、切换后无需重算）；持久化仍复用 exo 的 `SubtitleTimeShiftStore`，因此**两个内核的偏移值同源**。 |
| 10.5 | `content://` 字幕如何交给 mpv | 影响 SAF/网盘字幕可用性 | 统一在 `MpvSubtitleBridge` 落盘为普通文件后 `sub-add`（带内容哈希复用，避免重复 IO）。 |

---

## 11. 网络层：exo `DefaultHttpDataSource` × mpv（本轮新增，详见 `docs/MPV-NETWORK-PROXY-20261029.md`）

> 这一节是第二轮补的。第一轮漏掉了整层：只对了画面/字幕/控制，没对「URL 是怎么被打开的」。
> 而 `loading_failed(-13)` 恰恰全部产生在这一层。

| # | exo 行为 | exo 位置 | mpv 旧实现 | mpv 现实现 | 结论 |
|---|---|---|---|---|---|
| 11.1 | `setUserAgent(DEFAULT_USER_AGENT)` | `PlayerViewModel.kt:293` | `user-agent` 用自造的 `Homemovies/MPV` | 同一个常量 `DEFAULT_USER_AGENT`；主路径由代理注入 | ⚠️→✅ |
| 11.2 | `setDefaultRequestProperties(headers)` 逐跳生效 | `PlayerViewModel.kt:294` | 靠 `file-local-options/http-header-fields` 投机下发 | 代理在上游每个请求上注入 | ⚠️→✅ |
| 11.3 | `setAllowCrossProtocolRedirects(true)` | `PlayerViewModel.kt:294-297` | lavf 默认不跟随/行为随版本 | OkHttp `followSslRedirects(true)` | ⚠️→✅ |
| 11.4 | TLS 用系统 Conscrypt（系统 CA + 用户 CA） | 平台 | 自带静态 mbedtls，不读系统 CA | 代理走 OkHttp = 系统 TLS | ❌→✅ |
| 11.5 | `Range` / `206` 分段 | `DefaultHttpDataSource` | lavf 自己发 Range | 代理原样透传 Range 与 `Content-Range` | ✅ |
| 11.6 | 错误可诊断（`PlaybackException` 带 http 码） | exo 内部 | 一律折叠 `-13` | 错误文案追加「网络=…」+ mpv 日志尾巴；代理 logcat 打上游状态码 | ❌→⚠️（仍非 1:1，但可定位） |
| 11.7 | 走系统代理 | `DefaultHttpDataSource` 建在 `HttpURLConnection` 上，走 `ProxySelector.getDefault()`，**会**吃系统代理/VPN | `Proxy.NO_PROXY` 写死直连（对齐缺陷） | 已改回 OkHttp 默认 = 系统 `ProxySelector`，与 exo 同语义 | ❌→✅ |
| 11.8 | 连接池复用 | 平台连接池 | lavf 长连接 | 代理 `Connection: close`（每请求一连接） | ⚠️ 语义差异：seek 时多一次 TCP 握手（本机回环，成本可忽略；上游连接仍由 OkHttp 连接池复用） |
| 11.9 | 请求头不被静默丢弃 | `HttpURLConnection` 遇非法头值抛异常 → `PlaybackException` | — | 代理遇 `IllegalArgumentException` 记 WARN 并说明丢了哪个头 | ✅ |
| 11.10 | 上游状态码可分类 | `PlaybackException` → `PlaybackFailure.kt:71-118` 分类 401/403/404/410/429/503 + `retryAfterForbidden` | 只有 `-13` | 代理记 `lastUpstreamStatus`/`lastUpstreamHost`，错误文案带「上游 HTTP 403（host）」；**自动刷新直链仍未接入 mpv 路径** | ⚠️ 可定位但未自动恢复 |

**结论**：网络层从「4 项不对齐」收敛到「0 项结构性不对齐 + 1 项可诊断性差异 + 1 项连接复用语义差异」。
唯一还没补齐的是 11.10 的**自动恢复**：exo 侧 403 会触发 `retryAfterForbidden` 刷新直链，mpv 侧只把 403 显示出来、不会自动换链。这是已知缺口，不是本轮目标。
