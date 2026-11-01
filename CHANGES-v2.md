# HomeMovie v2 改造说明

> 版本：versionCode 21 → **22**，versionName 3.2 → **3.3**
> 依据：`Homemovies-src-sanitized-20261031.zip`（homemovies）与 `github.com/Riteshp2001/mpvRx` 主分支快照（mpvRx）
> 约束执行：未做 Android 构建；纯 Kotlin 的 LUT / 人脸几何 / 增强逻辑已在 JVM 上编译并跑通单测（见 §9）

---

## 0. 先说差异：源码与任务描述不一致的地方

| # | 任务描述 | 源码实际情况 | 本次处理 |
|---|---|---|---|
| D1 | 「完善 MediaPipe 的人脸/肤色分析」 | homemovies **没有任何 MediaPipe 代码或依赖**。`LutSkinAnalyzer` 的注释写明「人脸框路径未移植」，只有整帧肤色路径；MediaPipe 只出现在注释里（指参考实现） | 按「从零接入 + 完善」处理：加依赖与模型，打通真实调用链（§5） |
| D2 | 「参考 mpvrx 接入能力」 | mpvRx 采用 **AGPL-3.0-or-later**；homemovies 未声明许可证（待补充：homemovies 的许可证与分发方式） | 不复制 mpvRx 的 Kotlin 源码，按能力与 mpv 选项层面的事实**独立重写**；只引入 MIT 的 hdr-toys 着色器（§2） |
| D3 | 设置页写着「exo 内核不支持调色」 | 源码已有 `ExoLutEffect`（media3 `SingleColorLut`），exo 侧调色**早已可用** | 属于过时文案，已更正（§6 审查项 R7） |
| D4 | — | mpvRx compileSdk 37 / targetSdk 36；homemovies compileSdk 35 | mpvRx 的依赖不能直接搬，进一步支持「重写而非迁移」 |
| D5 | — | homemovies 已有 Anime4K（从 4.0.0 移植，14 个 GLSL、六档链） | 不重复引入 mpvRx 的 Anime4K，只补它缺的「4K 守卫 + 热降级」 |

---

## 1. mpvRx 能力清单与可复用模块对应关系

可迁移性：**A** = 已接入（重写）；**B** = 部分接入 / 以等价方式接入；**C** = 不接入（冲突或缺前提）。

### 1.1 播放与画质

| mpvRx 模块 | 作用 | 依赖 | 可迁移性 | 本次处理 / 接入点 |
|---|---|---|---|---|
| `domain/hdr/HdrToysManager` + `HdrToysProfile`、`ui/player/HdrScreenOutput` | hdr-toys 着色器 HDR 管线（PQ/HLG/BT.2020/Linear） | hdr-toys GLSL（MIT）、gpu-next | **A** | `MpvEnhancements.kt`（`HdrToneMode`/`HdrToysChain`/`MpvHdrShaders`/`HdrPipelineProperties`）→ `MpvOptions.build` → `MpvPlaybackEngine.initialize / onFileLoadedEnhancements`；新增 **AUTO** 模式（按片源自动启用） |
| `preferences/DecoderPreferences`（deband 系列） | GPU 去色带 | mpv `deband*` | **A** | `DebandSettings` → `MpvOptions.build` |
| `ui/player/RendererBackendPolicy` + README 电池建议 | gpu / gpu-next / Vulkan 选择 | Vulkan 构建、设备支持 | **B** | 冲突：`target-lut` 依赖 gpu-next。改为显式「省电渲染」：无调色、无 HDR 时才用 `vo=gpu`；Vulkan 不接入（随包 libmpv 是否带 Vulkan：待补充） |
| `ui/player/ThermalMonitor` | 热余量采样、Anime4K 热降级、环境光预算 | `PowerManager.getThermalHeadroom`（API 30+） | **A** | `ThermalGuard.kt` → `MpvPlaybackEngine.thermalPoll`（只降不升） |
| `domain/anime4k/Anime4KManager`（4K 守卫） | 高分辨率自动关 Anime4K | — | **A** | `ThermalGuard.anime4kAllowedFor` → `onFileLoadedEnhancements` |
| `domain/anime4k`（着色器链本身） | Anime4K 链 | GLSL | — | homemovies 已有（`MpvAnime4k.kt`），不重复 |
| `domain/autocrop/AutoCropAnalyzer` | 自动黑边裁切 | 截帧 + mpv `video-crop` | **A** | `AutoCropDetector`（`ThermalGuard.kt` 内）→ `MpvPlaybackEngine.autoCrop` → 面板「自动裁黑边」；exo 不支持并给替代建议 |
| `ui/player/controls/GestureHandler`、`DoubleTapSeekOverlay` | 分区双击、多击累加、长按倍速、横滑进度、双指缩放 | Compose 手势 | **A** | `PlayerGestureLayer.kt` → `PlayerScreen`（mpv：`video-zoom/pan`；exo：缩放渲染面） |
| `VideoZoomGeometry`、`VideoZoomSheet` | 缩放/平移 | mpv `video-zoom` | **A** | 同上 + 面板「重置缩放」 |
| A-B 循环（PlayerViewModel 内） | A-B 循环 + 进度条标记 | mpv `ab-loop-a/b` | **A** | `PlayerTools.cycleAbLoop`（exo 用位置轮询实现）+ `PlayerSeekBar` 标记 |
| `FrameNavigationSheet` | 逐帧 | mpv `frame-step` | **A** | `PlayerTools.frameStep`（exo 临时切 `SeekParameters.EXACT`） |
| 睡眠定时 | 定时暂停 | — | **A** | `PlayerTools.setSleepTimer` + 面板「播放」页 |
| `domain/framecapture`、`screenshot/` | 截图保存/模板命名 | mpv `screenshot-to-file`、MediaStore | **A** | `PlayerTools.takeScreenshot`（Android 10+ 写相册 `Pictures/HomeMovie`，更早写应用专属目录，免存储权限） |
| Stats Page 6 | 统计（fps/丢帧/编码/网络） | mpv 属性 | **A** | `MpvPlaybackEngine.readStats` / exo `videoDecoderCounters` → `PlayerStatsOverlay` |
| Background poll optimization | 控件隐藏时降低轮询 | — | **A** | `PlayerScreen` mpv 镜像轮询 500→1000ms；统计浮层暂停时 1s→2s |
| Dynamic Refresh Rate | 匹配片源帧率 | `preferredDisplayModeId` | **A** | `PlayerScreen.pickDisplayModeFor`（与 exo 既有 `ONLY_IF_SEAMLESS` 不冲突） |
| Volume boost | 超过 100% 音量 | mpv `volume-max`；exo 需系统效果器 | **A** | mpv `volume`；exo `LoudnessEnhancer` |
| Remember brightness | 记住亮度 | — | **A** | `PlayerFeatureSettings.savedBrightness` |
| `preferences/MpvConfigOverride` + `ui/editor` | 编辑 mpv.conf | Sora 编辑器、TextMate | **B** | 以「自定义 mpv 选项」文本框接入，并加**保护名单**（渲染/网络/TLS/OSD 不可覆盖）`MpvUserOptions`；不引入代码编辑器依赖 |
| `FilterPreset` / `PostProcessingPreset` | 视频滤镜/后期预设 | mpv eq 属性 / AGPL 着色器生成器 | **B** | 冲突：eq 属性只在 mpv 有、后期着色器生成器是 AGPL 代码。改为 **LUT 风格预设 `LutLook`**（两个内核都生效，§4） |
| `AmbientShaderBuilder` 环境光 | 画面外延光效 | AGPL 着色器生成器、GPU 负载 | **C** | 代码不可复制 + 耗电；待后续 |
| `ui/player/scopes` | 波形/示波器 | — | **C** | 与影片库定位关系弱；待后续 |
| `ui/player/clip` | 片段导出 | media3-transformer | **C** | 待后续 |
| `MPVPipHelper`、`MediaPlaybackService`、`ui/background` | 画中画 / 后台播放 | 独立播放 Activity、MediaSession | **C** | 前提缺失：homemovies 是单 Activity + Compose 导航，PiP 会把整个 NavHost 缩进小窗；需要先拆出播放 Activity 并重新验证 mpv 渲染面生命周期（该链路历史上出过多次黑屏），风险高，待后续 |
| 双字幕 / ASS 覆写模式 / 字体管理 | 次字幕、样式 | mpv `secondary-sid`、`sub-font*` | **C** | 随包 libmpv 裁掉了 `sub-font` 等选项（`MpvPlaybackEngine` 注释已核过）；待补充：libmpv 是否支持 `secondary-sid` |
| 在线字幕（Wyzie/SubtitleHub/TMDB） | 字幕搜索 | TMDB/IMDb ID | **C** | homemovies 已有迅雷/115/字幕站/本地库四源；元数据无 IMDb/TMDB ID（待补充） |
| Skip Segments（IntroDB/AniSkip…） | 跳片头片尾 | IMDb ID | **C** | 前提缺失（同上） |

### 1.2 外观与界面

| mpvRx 模块 | 作用 | 依赖 | 可迁移性 | 本次处理 |
|---|---|---|---|---|
| `ui/theme/*`（25+ 主题、AMOLED、Material You） | 多主题 | Compose M3 | **A** | `ThemeSettingsRepository` + 重写 `AppTheme.kt`（6 色板 + 动态取色 + AMOLED + 跟随系统） |
| `ui/theme/Motion`、Expressive 组件 | 动效与形状 | — | **A** | `AppMotion` 令牌、`AppRadius` 升级为 MD3 Expressive 阶梯、`ui/components/HmComponents.kt` |
| Responsive & dual-pane | 宽屏布局 | — | **B** | 宽度 ≥ 600dp 用 `NavigationRail`；双栏待后续 |
| `ui/liquidglass` | 液态玻璃控件 | 自研渲染 | **C** | 视觉风格与 MD3 方向不一致 |
| `ui/tv` | TV 焦点导航 | Leanback 习惯 | **C** | 定位外 |

### 1.3 与 homemovies 定位冲突的能力（不接入）

| mpvRx 模块 | 冲突点 |
|---|---|
| Jellyfin / Navidrome / Audiobookshelf / Seerr、音乐库、有声书、歌词、可视化 | 定位冲突：homemovies 是本地 + 115/光鸭影片库，不是音乐/媒体服务器客户端 |
| 种子引擎（Go bridge）、yt-dlp（Python bridge） | 需要额外原生库，APK 体积与 ABI 风险；homemovies 已显式 `ytdl=no` |
| Google Cast | 依赖 Play 服务；云盘直链需要 UA/Referer/Cookie，Default Media Receiver 无法带请求头 |
| AI（字幕翻译/重命名）、语音转字幕 | 需要第三方 API Key，涉及隐私与外发 |
| Lua/JS 脚本（QuickJS） | 原生依赖；随包 libmpv 是否带 Lua：待补充 |
| 安全文件夹、SMB/FTP/WebDAV 浏览器、Syncplay、远程控制、下载管理 | 与现有 115/光鸭/SAF 链路重叠或定位外 |

---

## 2. 冲突与取舍汇总

| 冲突 | 取舍 |
|---|---|
| **许可**：mpvRx 是 AGPL | 只参考能力与 mpv 选项层面的接口事实，代码独立编写；随包第三方资产只有 hdr-toys（MIT，许可证随包：`assets/mpv/shaders-hdr-toys/LICENSE-hdr-toys.txt`）与 MediaPipe 模型（Apache-2.0，`assets/mediapipe/README.md`） |
| **渲染后端**：mpvRx 自动选 gpu/gpu-next；homemovies 的 `target-lut` 只有 gpu-next 有 | 默认保持 gpu-next；「省电渲染」开关只在本次会话无调色、无 HDR 管线时才用 gpu |
| **HDR 管线 × 实时调色**：LUT 作用在输出色彩空间 | HDR 管线生效时调色暂停（与 exo 侧 HDR 透传一致），回到 SDR 片源自动恢复，并用横幅告知 |
| **init 期下发约束**（homemovies：glsl-shaders/target-lut 必须 init 前） | 强制 HDR 模式走 init 期；AUTO 模式必须等 `FILE_LOADED` 才知道片源，运行期用整组属性 + 完整 `glsl-shaders` 列表一次性下发，避免历史状态残留 |
| **滤镜预设**：mpv eq 属性在 exo 无对应 | 做成 LUT 风格预设，两个内核一致 |
| **PiP/后台播放**：需拆 Activity | 本次不做，列为后续 |

---

## 3. 接入点与调用链（不出现功能回退的保证）

```
设置页「播放增强」 ──写──▶ PlayerFeatureSettings (prefs: player_fx_*)
                                  │ snapshot().toMpvEnhancements()
PlayerViewModel.startMpvSession ──▶ MpvPlaybackEngine.initialize(…, enhancements)
                                  ├─ MpvHdrShaders.ensure()          （AUTO/强制模式解包 hdr-toys）
                                  ├─ MpvOptions.build(…, enhancements)  vo / glsl-shaders / deband / volume-max / 用户选项
                                  └─ FILE_LOADED ─▶ onFileLoadedEnhancements()
                                        ├─ videoInfoReporter → uiState.videoFps → 刷新率匹配
                                        ├─ applyAutoHdrPipeline() → suspend/resumeLut
                                        ├─ Anime4K 4K 守卫
                                        └─ thermalPoll（10s，只降不升）
PlayerScreen 手势/控件 ─▶ PlayerViewModel ─▶ PlayerTools ─▶ 当前内核（mpv 引擎方法 / ExoPlayer）
```

| 能力 | 关键文件 / 类 / 方法 |
|---|---|
| HDR 管线 | `MpvEnhancements.kt`；`MpvOptions.build`；`MpvPlaybackEngine.applyAutoHdrPipeline / suspendLutForHdr / resumeLutAfterHdr / applyShaderList` |
| 去色带、省电渲染、音量上限、自定义选项 | `MpvOptions.build`（`enhancements` 参数，默认值与 v1 选项表一致） |
| 热管理与 4K 守卫 | `ThermalGuard`；`MpvPlaybackEngine.thermalPoll / onFileLoadedEnhancements` |
| 自动裁边 | `AutoCropDetector`；`MpvPlaybackEngine.autoCrop / clearCrop`；`PlayerViewModel.autoCrop` |
| A-B / 逐帧 / 睡眠 / 统计 / 截图 / 音量增强 | `PlayerTools`；`MpvPlaybackEngine.setAbLoop / frameStep / readStats / screenshotTo / setVolumePercent` |
| 手势与缩放 | `PlayerGestureLayer.playerGestures`；`PlayerViewModel.beginSpeedBoost / setZoom`；`MpvPlaybackEngine.setVideoZoom`；exo 在 `PlayerView` 的 `update` 中缩放 `videoSurfaceView` |
| 刷新率匹配、记住亮度、轮询退避 | `PlayerScreen`（`pickDisplayModeFor`、`rememberBrightness`、mpv 镜像轮询） |
| 切内核状态保持 | `startMpvSession` 把音量增强 / A-B / 缩放带到新实例；exo 重建时 `PlayerTools.onExoRecreated` |

**兼容性**：所有新开关默认值都等价于 v1 行为（HDR=关闭、去色带=关、省电渲染=关、刷新率匹配=关、音量增强=0）。`MpvOptions.build` 新参数有默认值，`MpvPlaybackSmokeTest` 的调用不变。唯一默认生效的新行为是手势（双击/长按/双指），可在设置里逐项关闭；开启双击后单击显隐控件会有约 300ms 的双击判定延迟（关闭双击即恢复即时响应）。

---

## 4. LUT：性能与调色能力

### 4.1 定位到的问题

| 问题 | 位置（v1） | 影响 |
|---|---|---|
| 同一条调色链写了两份（生成 / 搜参），常量各抄一份 | `LutColorMath.applyAll` 与 `LutParamPredictor.apply*InPlace` | 改一处漏一处，搜出的参数与渲染结果不一致 |
| 每个格点 6 次 `Triple` 装箱；色温增益（含除法）每格点重算 | `LutColorMath` | 65³ 约 165 万个对象，ART 上 GC 抖动 |
| 强度混合要「生成 → 分配单位 LUT → 再分配混合结果」三份数组 | `generateScaledLut` | 内存与时间 ×3 |
| 写盘前把旧文件整份读回来比较字符串；序列化先拼几 MB 的 String | `CubeLut.writeTo / toCubeText` | 比生成本身还慢 |
| 解析用 `split` + `ArrayList<Float>` 装箱 | `CubeLut.parse` | 65³ 要近半秒 |
| exo 侧在**主线程**同步生成 LUT | `ExoLutApplier.apply` | 滑杆拖动时掉帧 |
| mpv / exo / 新建实例各自重复生成同一张 LUT | 三处调用 | 重复计算 |
| Cr 每像素算两次；两个分位数各排序一次 | `LutSkinAnalyzer` | 分析耗时 |
| 搜参在 4~20 万个肤色像素上评估约 150 组参数 | `LutParamPredictor` | 最重的一步 |

### 4.2 改动

- **`LutGradeKernel`（新）**：参数预编译一次、逐像素零分配；LUT 生成与自动分析共用（搜参模式仅过曝保护规则不同）。参考链的每个浮点表达式按原顺序书写，**创意层中性时输出与 v1 逐位一致**。
- **`LutColorMath`**：生成时就地完成强度混合；33³ 及以上按蓝色切片 4 线程并行；单算子函数签名保持不变。
- **`CubeLut`**：直接写 ASCII 字节；逐字节解析 + 预分配数组；新增四面体插值 `sample()`（中性灰保持中性）。
- **`LutEngine`（新）**：按签名缓存最近 3 张 LUT，mpv / exo / 新实例共享；风格 LUT 按路径缓存解析结果。
- **`ExoLutApplier`**：生成移到后台线程，用「代次」丢弃过期结果；量化由截断改为四舍五入。
- **`LutSkinStats.sampleWeighted` + `LutParamPredictor.compressSkin`**：肤色像素压成「唯一 8 bit 颜色 + 出现次数」再搜参。均值/计数类的和在 Double 里精确，结果不变（中位数用等价的加权中位数）。

### 4.3 性能数据（JVM 基准，同一台机器同一组输入，单位 ms）

| 项目 | v1 | v2 | 提升 |
|---|---:|---:|---:|
| 生成 + 强度混合 17³ | 1.05 | 0.56 | 1.9× |
| 生成 + 强度混合 33³ | 3.01 | 0.56 | 5.4× |
| 生成 + 强度混合 65³ | 16.78 | 4.48 | 3.7× |
| `.cube` 序列化 33³ | 4.74 | 2.87 | 1.7× |
| `.cube` 序列化 65³ | 31.30 | 18.75 | 1.7× |
| `.cube` 解析 33³ | 56.56 | 3.68 | 15× |
| `.cube` 解析 65³ | 483.84 | 19.39 | 25× |
| 720p 肤色分析 | 38.02 | 22.07 | 1.7× |
| 自动分析搜参（45,758 个肤色像素） | 94.01 | 20.89 | 4.5× |

说明：JVM 的逃逸分析会消掉一部分 `Triple` 分配，ART 上 v1 的分配开销更大，实际收益通常更明显（待真机复测）。另有两项结构性收益不在表中：exo 侧生成不再占主线程；同一组设置在 mpv/exo/新实例之间只生成一次。

**等价性验证**：以未改动的 v1 代码为基准，60 组随机参数 × 3 种尺寸 + 65³ 的 LUT 数据逐位相同、`.cube` 文本逐字相同；2,000 组单算子输入结果相同；17 帧合成画面 × 4 种模板的自动分析参数完全相同。

### 4.4 调色能力提升

| 能力 | 实现 |
|---|---|
| 创意层（`LutCreativeParams`） | 自然饱和度（低饱和区多加、已饱和区与肤色基本不动）、色相旋转 ±30°、褪色（抬黑位/压白位）、分离色调（阴影/高光各自的色相与强度 + 平衡点）、肤色保护（按 HSV 肤色权重削弱改色算子）。全部在 **Oklab** 中计算，亮度基本不变 |
| 保色相软裁剪 | 创意层生效时，越界颜色沿「像素 → 同亮度灰点」方向收色度，不再逐通道硬切导致色相偏移 |
| 风格预设 `LutLook` | 自然 / 鲜艳 / 电影感 / 暖调胶片 / 冷调夜景 / 柔和粉彩 / 黑白 / 深邃黑；设置页给出调色前后色样；播放页一键切换 |
| 风格 LUT 叠加 | 导入任意 3D `.cube`（`LutOverlayImporter`，以内容哈希存入私有目录），参数调色之后以四面体插值采样，可调混合比例 |
| 兼容 | 偏好键都以 `lut_` 开头，`observeLutSettings` 自动监听；默认值下签名与 v1 逐字相同，旧缓存文件继续命中 |

---

## 5. MediaPipe 人脸/肤色分析

### 5.1 v1 的不足

1. 没有人脸检测：整帧找肤色，木地板、墙面、暖光床单会拉偏 R/B 与亮度统计；
2. Cr 分位区间按整帧计算，阈值同样被背景带偏；
3. 自动分析只支持 mpv，exo 内核下直接拒绝；
4. 结论无法区分「没有人」与「有人但没采到」。

### 5.2 实现与调用链

```
播放页面板「调色 → 自动分析」
  → PlayerViewModel.autoTuneLut()                     （两个内核都支持）
  → analyzeCurrentFrame()
       ├─ mpv: MpvPlaybackEngine.captureFrameForAnalysis()   screenshot-to-file … video（原片）
       └─ exo: ExoFrameGrabber.grab()                        MediaMetadataRetriever 取原片关键帧（带播放请求头）
  → MediaPipeFaceRegionDetector.detect(bitmap)
       ├─ 1 级 FaceLandmarker（face_landmarker.task）：轮廓凸包 − 眼/眉/唇（点索引取自 MediaPipe 连线常量）
       ├─ 2 级 FaceDetector（blaze_face_short_range.tflite）：6 个关键点 → 两颊 + 鼻梁
       ├─ 整帧没检出时，中央 2/3 区域放大重试（远景小脸）
       └─ 任一级失败只降级，不抛异常
  → LutAutoAnalyzer.analyze(argb, w, h, faces)
       → LutSkinAnalyzer：LutFaceGeometry.rasterize 生成掩码 → 只在脸上算 Cr 区间与肤色 → 不足 150 像素回整帧
  → LutParamPredictor.predict（压缩后搜参）→ 写回设置并即时下发（mpv target-lut / exo SingleColorLut）
  → 诊断日志 player.lut.autoTune.faces（backend / 人脸数 / 耗时 / 失败原因）
```

- 纯 Kotlin 契约 `LutFaceRegion` / `LutFaceGeometry`（凸包、扫描线光栅化、关键点推区域）放在 `playback/lut/`，可在 JVM 单测；Android 部分只在 `playback/face/`。
- 依赖：`com.google.mediapipe:tasks-vision:0.10.26.1`。arm64 原生库三个 `PT_LOAD` 段 `p_align = 0x4000`（满足工程的 16 KB 页对齐约束，已解包核对）；1.x 会额外拉入端侧大模型运行时，不采用。
- 构建：`androidResources.noCompress += ["tflite", "task"]`（模型必须不压缩才能 mmap）。
- 结论文案带上采样来源（人脸网格 N 张 / 检测框 N 张 / 整帧）。

---

## 6. 代码审查与重构

| # | 问题 | 依据 | 修改 |
|---|---|---|---|
| R1 | 调色算子重复实现 | 见 §4.1 | 合并为 `LutGradeKernel` |
| R2 | exo LUT 在主线程生成 | `ExoLutApplier` | 后台生成 + 代次防竞态 |
| R3 | `AppSettingsRepository` 是 1,600+ 行的「上帝对象」 | 横跨 5 个领域 | 新功能放进 `PlayerFeatureSettings`、`ThemeSettingsRepository`（同一个 prefs 文件，无需迁移） |
| R4 | `PlayerViewModel` 2,700+ 行 | 职责过多 | 新工具集中到 `PlayerTools`，通过 lambda 取当前内核，不持有过期引用 |
| R5 | `PlayerScreen` 2,500 行、控件与手势混写 | 难以维护 | 控件 → `PlayerControlsV2.kt`，手势 → `PlayerGestureLayer.kt`，`PlayerScreen` 降到约 1,900 行 |
| R6 | `MoviesTopBar` 里有 `if (false) { … }` 死代码，配本地假状态（`HomeSortState()`、空回调） | 点了不生效 | 删除；把一直传入但未显示的库统计显示出来 |
| R7 | 文案/注释过时：「exo 不支持调色」 | 与 `ExoLutEffect` 实际行为不符 | 更正设置页与 ViewModel 注释 |
| R8 | 品牌色写死（`MoviesAccent = 0xFF36C5F0`） | 换色板后不一致 | 改为主题 `primary` |
| R9 | 底部导航文案/图标单份硬编码，无宽屏适配 | 重复与平板体验差 | `TopDestinations` 单一数据源，底栏与侧栏共用 |
| R10 | `styles.xml` 写死白色状态栏图标 | 浅色主题下看不见（target SDK 35 在 Android 15 强制 edge-to-edge） | 系统栏图标明暗跟随主题 |
| R11 | 截帧后统计特征直接引用可变缓冲区 | `LutSkinStats.sample(out)` | 文档化引用语义，热循环只读标量 |
| R12 | `percentile` 每次调用都排序 | 两次排序 | 排序一次取两个分位 |
| R13 | `ExoLutApplier` 无释放入口 | 线程泄漏 | 新增 `release()`，ViewModel 销毁时调用 |
| — | `ExoPlaybackEngine`（未被引用的适配器）、`ManualControlOverlay`（未被调用） | 死代码 | **保留未删**：前者属于双内核抽象的一部分（待后续接线）；后者承载转义字面量，删除会减少脱敏字面量的出现次数，按「脱敏优先」原则保留 |

架构变更范围：新增模块全部是「旁路」接入（新设置仓库、新工具类、新组件），原有核心流程（解析 → 起播 → 双内核切换 → 进度/字幕 → 刮削/网盘）的调用关系未改。

---

## 7. UI 重设计（MD3）

| 层面 | 内容 |
|---|---|
| 主题系统 | 6 套色板（影院青为品牌默认，保留 v1 手调对比度）+ Material You 动态取色 + 跟随系统/浅/深 + AMOLED 纯黑；补齐 `surfaceContainer*`、`surfaceDim/Bright`、`inversePrimary` 等全部色彩角色 |
| 形状/排版/动效 | `AppRadius` 升级为 8/12/16/20/28（全应用 200+ 处圆角统一变化）；标题字重 SemiBold、Display/Headline 收紧字距；`AppMotion` 强调缓动令牌 |
| 组件集 | `ui/components/HmComponents.kt`：顶栏、分组卡片、列表项（色调圆形图标）、开关项、单选芯片、滑杆项、选择卡片；设置页旧组件全部委托到这里 |
| 导航 | 底部导航（surfaceContainer + 胶囊指示器 + 实心/描边图标双形态）；宽度 ≥ 600dp 自动切换侧边导航栏 |
| 首页 | 主色渐变大标题 + 库统计块 + 分区主色竖条 |
| 设置 | 新增「外观」「播放增强」页；「实时调色」页重写（风格卡片 + 前后色样、创意调色、风格 LUT 导入） |
| 播放器 | 上下渐变遮罩 + 中部三键（主色播放键）；顶栏状态芯片（内核/帧率/调色/HDR/A-B/睡眠/缩放）；自绘进度条（缓冲层、A-B 区间、拖动时间气泡、点击跳转）；底栏工具芯片；右侧分页面板（画面/调色/播放/解码）；双击涟漪、长按倍速徽标、统计浮层、轻提示 |

---

## 8. 脱敏

**原则**：已有的转义字面量一律保持原样字节；新代码不写出与它们相同的明文。

校验脚本（三条规则，对原包和新包都跑过）：

| 规则 | 内容 | 结果 |
|---|---|---|
| R1 | 原包 138 个含 `\uXXXX` 的字符串字面量，在新包中逐字节存在且出现次数不少于原包 | 通过 |
| R2 | 56 段协议/敏感字面量（`ProtocolLabels`、客户端标识、实体别名等非 UI 文件）解码后的文本，在全部源码（含注释）中的明文出现次数不增加 | 通过 |
| R3 | 64 条界面文案字面量解码后的完整文本，作为独立明文字符串字面量的出现次数不增加 | 通过 |

- 迁移过的转义字面量（如播放器顶栏「播放模式」、横竖屏标签、对勾符号）在新文件里仍以转义形式书写；新代码里与旧字面量同名的文案（如面板芯片「关闭」、标签页「播放」）也写成转义形式。
- 协议键、匹配逻辑、刮削解析器、网盘协议代码**未改动**，不存在协议键变更风险。
- 新增资产（hdr-toys 着色器、MediaPipe 模型）不含任何账号、路径或身份信息。

---

## 9. 验证情况

| 项目 | 状态 |
|---|---|
| Android 构建 / 真机 | **未执行**（按约束） |
| LUT 引擎、人脸几何、肤色分析、参数预测、`MpvUserOptions`、HDR 链选择、自动裁边、热守卫 | 在 JVM 上编译并运行：原有 `LutColorMathTest`、`LutAutoAnalyzerTest` + 新增 `LutEngineV2Test`、`MpvEnhancementsTest`，共 **36 个用例全部通过**；另有 v1/v2 逐位等价对比 |
| 源码结构 | 41 个新增/改动的 Kotlin 文件：括号平衡（按 Kotlin 字符串模板词法检查）、导入去重、已删除符号无残留引用 |

**待真机验证（风险点）**

1. 随包 libmpv 是否支持 `video-crop`、`glsl-shader-opts`、`target-colorspace-hint`（失败会写日志并提示，不影响播放）；
2. hdr-toys 运行期追加着色器在中低端 GPU 上的首帧编译耗时；
3. exo 侧缩放 `SurfaceView` 在各厂商 ROM 上的表现；
4. MediaPipe 在 arm64 设备上的推理耗时（预计 30~80 ms）与远景人脸召回率；
5. `MediaMetadataRetriever` 读取网盘直链的耗时（已设 8 秒硬超时）；
6. APK 体积增加：MediaPipe arm64 原生库约 14 MB（未压缩）+ 模型约 3.9 MB + hdr-toys 着色器约 0.2 MB。

**待补充**

- homemovies 的许可证与分发方式（决定能否直接复用 AGPL 代码；本次按不能复用处理）；
- 随包 libmpv 是否编入 Vulkan / Lua、是否支持 `secondary-sid`；
- 影片元数据是否可能补充 IMDb/TMDB ID（决定能否接入跳片头片尾与 TMDB 字幕搜索）。

---

## 10. 文件变更清单

**新增（Kotlin 18 个 + 测试 2 个）**
`playback/lut/`：`LutGradeKernel` `LutCreative` `LutEngine` `LutFaceRegion` `LutOverlayImporter`
`playback/face/`：`MediaPipeFaceRegionDetector` `ExoFrameGrabber`
`playback/engine/`：`MpvEnhancements` `ThermalGuard`
`data/repository/`：`PlayerFeatureSettings` `ThemeSettingsRepository`
`ui/components/`：`HmComponents`
`ui/player/`：`PlayerControlsV2` `PlayerGestureLayer` `PlayerTools`
`ui/settings/`：`AppearanceSettingsPage` `ColorGradeSettingsPage` `PlayerEnhancementSettingsPage`
`test/`：`LutEngineV2Test` `MpvEnhancementsTest`

**新增资产**：`assets/mediapipe/`（2 个模型 + 来源说明）、`assets/mpv/shaders-hdr-toys/`（9 个 GLSL + MIT 许可证）

**修改（22 个）**：`app/build.gradle.kts`、`AppContainer`、`AppSettingsRepository`、`ExoLutEffect`、`MpvOptions`、`MpvPlaybackEngine`、`CubeLut`、`LutAutoAnalyzer`、`LutColorMath`、`LutFileWriter`、`LutParamPredictor`、`LutSettings`、`LutSkinAnalyzer`、`LutSkinStats`、`AppTheme`、`DesignTokens`、`LocalMovieLibraryAppRoot`、`HomeScreen`、`PlayerScreen`、`PlayerViewModel`、`SettingsScreen`、`SettingsViewModel`；以及 `README.md`

**删除**：无（替换掉的播放器旧控件、旧调色设置页是在原文件内移除的私有函数）
