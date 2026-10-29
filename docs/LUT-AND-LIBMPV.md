# 实时调色（LUT）与 libmpv 构建来源

> 本文件说明本轮两件事：① 参考包 `MPV实时LUT播放.zip` 的调色能力落到 Android/本工程的实现方式与边界；② `libmpv.so` 的最终来源与自定义编码栈（Yaozhi patches）的适用性判断。

---

## 一、结论速览

| 事项 | 结论 |
|---|---|
| LUT 设置入口 | 设置 → 播放器设置 → **实时调色**（页面 id `SettingsPage.PlayerColorGrade`） |
| LUT 生效内核 | **仅 mpv**。exo 侧没有等价色彩管线，切到 exo 后设置保留但不下发 |
| 下发机制 | 参数变更 → 在 IO 线程生成 3D LUT → 写 `<cacheDir>/lut/lut_<签名>.cube` → `set target-lut <posix 路径>` |
| 自动分析（截帧 + 人脸检测 + 搜参） | **已移植**：播放页「自动分析」→ mpv `screenshot-to-file` 截一帧 → 中心区域提肤色 → 搜 7 个参数 → 写设置并下发。不引入 OpenCV / MediaPipe，见 §4.1 |
| `libmpv.so` 来源 | `scripts/fetch-libmpv.sh` 自建（复用 mpv-android buildscripts 交叉编译整棵树），CI 见 `.github/workflows/build-libmpv.yml` |
| Yaozhi 自定义编码栈 | 补丁**可选**注入（`--extra-patches`），默认不启用；逐项适用性见 §五 |

---

## 二、LUT 落地结构

### 2.1 纯 JVM 算法层（可单测，无 Android 依赖）

| 文件 | 职责 |
|---|---|
| `playback/lut/LutParams.kt` | 7 个色彩参数（色温/色调/饱和度/亮度/对比度/高光/阴影）、`LutParamKey` 枚举（prefKey + 中文标签 + 提示）、`LutPreset` 四档预设 |
| `playback/lut/LutColorMath.kt` | 调色链 `applyTempTint → applySaturation → applyBrightness → applyContrast → applyHighlightShadow`，`generateLut` / `generateScaledLut` |
| `playback/lut/CubeLut.kt` | 3D LUT 数据结构、`identity()`、`blendTowards()`（强度混合）、`toCubeText()`（`.cube` 序列化，6 位小数、拒绝科学计数法）、`writeTo()`（内容不变则不重写） |
| `playback/lut/LutSettings.kt` | `enabled` / `lutSize` / `strengthPercent` / `params` 四要素 + `shouldApply` / `signature()` / `effectiveFileName()` |
| `playback/lut/LutFileWriter.kt` | 落盘到 `<cacheDir>/lut/`，按签名复用、临时文件 + rename 原子替换、清理旧文件 |
| `playback/lut/LutSkinStats.kt` | `LutSkinFeatures` 七元组 + 三个模板的目标区间 + `isExtremeDark` / `isHighMatch` / `pyRound` |
| `playback/lut/LutSkinAnalyzer.kt` | 一帧 ARGB → 中心区域的肤色像素 → `LutSkinFeatures`（见 §4.1） |
| `playback/lut/LutParamPredictor.kt` | 肤色统计 → 7 个调色参数（`predict_params` 的移植，含模板选择与逐维搜索） |
| `playback/lut/LutAutoAnalyzer.kt` | 把上面三段串起来，出结论文案与「预期生效后」的预演统计 |

关键设计约束（改动时必须维持）：

1. **签名即内容**：`signature()` 覆盖所有影响生成结果的输入（7 参数 + 尺寸 + 强度），不含 `enabled`。开关变化走"下发空路径"，不产生新文件，因此不会出现"名字对、内容旧"。
2. **强度在生成阶段混合**：mpv 的 `--target-lut` 只接受一个文件、没有不透明度参数，所以强度通过 `blendTowards(identity, 1 - k)` 混进数据；`k=1` 直接返回原表（避免无谓的 1 - k = 0 分支误差）。
3. **`shouldApply` 三重短路**：关闭 / 强度 0 / 参数全 0 都不下发。只省一次文件写入，还能避免 mpv 为"画面完全不变"的 LUT 重载一次着色而产生可见的一帧闪烁。
4. **写文件先写 `.tmp` 再 rename**：mpv 可能恰好在此期间读该路径，半截文件会让它加载失败并停在上一张 LUT（表现为"偶发不生效"，极难复现）。

### 2.2 引擎侧接入

`playback/engine/MpvPlaybackEngine.kt`：

```kotlin
fun applyLut(settings: LutSettings)   // :365
fun captureFrameForAnalysis(timeoutMs: Long = 2_000, maxSide: Int = CAPTURE_MAX_SIDE /* 640 */): Bitmap?
```

- 入口先比签名，相同直接 return（滑杆连续拖动时避免重复生成）。
- 队列内二次去重：`pendingLutSignature`（`@Volatile`）在入队前登记，任务真正开跑时与 post 回主线程时各复检一次签名。没有这一层时，一次拖动会产生成串请求，排队的旧请求仍会各自生成一张 LUT 并 `set target-lut`——白算（33³≈20ms/次），且中间几帧会显示过时的调色。最终态虽正确，但会看到「滑杆动得越快，画面越晚才追上」。
- 生成 + 写文件在 `lutExecutor`（单线程池）上；JNI `setString("target-lut", path)` 统一 post 回主线程，与 `create/init/destroy` 同线程，避免 mpv 句柄在跨线程销毁瞬间被使用。
- 路径做 `replace('\\', '/')`（mpv 在 Android 上按 POSIX 解析；参考实现同样处理）。
- 生成失败时**保持上一张 LUT**而不是闪回原色。
- `release()` 里 `lutExecutor.shutdownNow()`，且每次 post 回主线程都复检 `released` / `created`。

`ui/player/PlayerViewModel.kt`：

- `startLutObserver()`（:1963）在 `viewModelScope` 订阅 `AppSettingsRepository.observeLutSettings()`，同步先读一次值避免起播首帧用默认值。
- 回调里仅当 `isMpvActive()` 才下发；`startMpvSession()` 在 `engine.load(...)` **之前**调一次 `engine.applyLut(currentLutSettings)`——`target-lut` 是 vo 级选项，装载前设置与装载无关，但装载后第一帧就已经带上调色，不会先看到原片再跳变。
- 切内核到 mpv 时同样走 `startMpvSession`，因此调色设置跨内核切换自动恢复。
- `autoTuneLut()`（:2001）：自动分析调参的入口，截帧 → 提肤色 → 搜参 → 写设置 → 下发。
  详见 §4.1.2；`lutAutoTuneBusy` 防重入，`onCleared()` 里取消 `lutAutoTuneJob`。

`ui/player/PlayerScreen.kt`：播放模式侧面板底部新增「调色」分组，里面是「自动分析调参」条目。
`busy` 时标题变「正在分析当前画面…」并禁用点击，结果文案落在这条的副标题里（不另起弹窗，
分析结果同时已经写进设置，用户点开调色面板就能接着微调）。

### 2.3 设置层

`data/repository/AppSettingsRepository.kt`：

- `getLutSettings()` / `saveLutSettings()` / `observeLutSettings()`（:987）。
- 偏好键全部 `lut_` 前缀（`lut_enabled` / `lut_size` / `lut_strength` / `lut_temp` …），观察者即按前缀过滤。
- 读侧做夹取：尺寸夹到 `CubeLut.MIN_SIZE..MAX_SIZE`、强度夹到 `0..100`、参数缺键/越界回落 0——手工改 prefs 或降级残留都不会让播放器起不来。

`ui/settings/SettingsViewModel.kt`：

- `SettingsUiState.lutSettings`（默认值取 `LutSettings()` 自身 companion 常量，与播放侧同源）。
- 所有 setter 统一经 `commitLutSettings { }`：**写盘后回读**，UI 显示的永远是磁盘真实值（含夹取结果）。
- `updateLutEnabled(true)` 时若参数全 0 自动套「标准」预设——全 0 是恒等变换，开了等于没开，用户会以为功能坏了。
- `applyLutPreset(LutPreset.Neutral)` 会同时把开关置 false，避免"预设写着关闭调色、开关却还亮着"的矛盾态。

`ui/settings/SettingsScreen.kt`：

- `SettingsPage.PlayerColorGrade`，标题「实时调色」，返回键回到播放器设置。
- 入口挂在播放器设置的「播放器控件」卡片，副标题三种状态可分辨：关闭 / 已开启但参数恒等（画面无变化）/ 已开启且生效（显示尺寸 · 强度）。
- 页面含：总开关、四档预设（数值与参考实现一字不差）、7 个参数的滑杆 + 每项一句方向提示、强度滑杆、LUT 尺寸档（直接复用 `CubeLut.Sizes`，不另维护一份）、能力边界说明。

### 2.4 单测

`app/src/test/java/.../playback/lut/LutColorMathTest.kt`（10 个用例）证明的是"不看画面也能证伪"的部分：

- 参数全 0 → 逐格恒等；
- 强度 0 → 等于恒等（即使参数很强）；强度 100 → 等于原参数；
- 强度 50 → 落在恒等与原表之间；
- `.cube` 文本：首行 `LUT_3D_SIZE n`、数据行数恰为 `n³`、每行 3 个分量、无科学计数法；
- 签名随参数/强度/尺寸变化、但不随 `enabled` 变化；
- 关闭 / 全 0 / 强度 0 → `effectiveFileName()` 为 null；
- 预设数值与参考实现对齐；`set()` 越界夹取而不抛异常。

`app/src/test/java/.../playback/lut/LutAutoAnalyzerTest.kt`（13 个用例）覆盖自动分析调参：

- 没肤色 → 退回标准模板（不是随机数），结论文案里点明原因；
- 三组典型肤色（偏暖 / 过红 / 偏暗）的搜参结果与参考实现 `predict_params` **逐参数相等**——
  golden 值由 `work/autolutspec/`（本机工具，不随仓库/交付包分发）在同样的 float32 输入上跑参考实现取回；
- 任何输入下 7 个参数都不越界（越界写进 `LutParams` 会直接抛异常）；
- 强制模板优先于自动选择；极暗肤色在自动模式下会选到极暗模板且提亮更多；
- 中性参数预演肤色像素不变（逐像素 1e-6 容差）；
- 肤色提取：整帧同色命中、灰帧（R/B=1.0）不命中、像素数与尺寸不符当场抛 `IllegalArgumentException`；
- `LutAutoAnalyzer` 的预演统计等于「用搜出来的参数再采样一遍」，中间层不改参数。

`work/autolutspec/`（不进仓库、也不进交付包，是移植期的本机对拍工具）用 Python 参考实现生成
34 组 golden（含三个模板各自的强制分支），再让 Kotlin 版跑同一批输入比对 7 个参数与 7 个统计量。

---

## 三、与参考实现的映射

参考包 `mpv_realtime_lut.py`（2187 行，Windows 桌面 Python 工具）与本工程实现的对应关系：

| 参考实现 | 本工程 | 说明 |
|---|---|---|
| `_PARAM_KEYS` + `_PARAM_LABELS_CN` 两个字典 | `LutParamKey` 单枚举 | 键名、中文标签、方向提示合并一处 |
| `apply_temp_tint` / `apply_saturation` / … | `LutColorMath` 同名方法 | 调色链顺序、系数（Rec.709 luma 权重等）一致 |
| `generate_lut(size, params)` | `LutColorMath.generateLut` | 格点遍历顺序 `b→g→r`、`r_in = r/(size-1)` 一致 |
| `write_cube(filename, lut, size)` | `CubeLut.toCubeText()` + `LutFileWriter` | 6 位小数一致；另加原子替换与内容去重 |
| `mpv.command("set", "target-lut", path)` | `MPVLib.setString("target-lut", path)` | 参考实现先置空再设值（强制重载）；本工程用签名判重替代，语义等价且少一次闪烁 |
| `JY_TEMPLATE` / `_HIGH_MATCH` / `_EXTREME_DARK` | `LutPreset.Standard` / `HighMatch` / `ExtremeDark` | 数值一字未改 |
| 强度（无此概念，参数为绝对值） | `strengthPercent` + `blendTowards` | 移动端滑杆需要一个"力度"旋钮；用与恒等表混合实现，不依赖 mpv 特性 |
| `extract_skin`（OpenCV 阈值 + MediaPipe 人脸） | `LutSkinAnalyzer` | 去掉 cv2/MediaPipe，改中心区域 + Cr 动态分位；见 §4.1 |
| `_sample_skin_rgb_statistics` | `LutSkinStats` | 7 个统计量同式；float32 与参考实现同精度 |
| `predict_params`：搜参主流程 | `LutParamPredictor` | 模板、搜索顺序、夹取、联动全部对齐；见 §4.1 |
| `analyze_and_apply()`：截帧 → 肤色提取 → 搜参 | `captureFrameForAnalysis` + `LutAutoAnalyzer` + `autoTuneLut` | 已移植；取帧改走 mpv `screenshot-to-file`，见 §4.1 |

---

## 四、显式偏离与不可对齐项（不默认省略）

### 4.1 自动分析调参（截帧 + 人脸/肤色检测 + 参数搜索）

**已移植。** 参考实现的 `analyze_and_apply()`（`mpv_realtime_lut.py:1741-1830`）在桌面端是
「IPC 截图 → `cv2.imread` → `extract_skin`（OpenCV 阈值 + MediaPipe 人脸）→ `predict_params`
→ 下发 LUT → 同步滑杆」。本工程按同样的四段拆开，每段都能单测：

| 段 | 本工程 | 依赖 | 与参考实现的差异 |
|---|---|---|---|
| 截帧 | `MpvPlaybackEngine.captureFrameForAnalysis()` | libmpv（已有） | 走 `screenshot-to-file <path> video`，不是 mss/win32gui；见下 |
| 肤色提取 | `playback/lut/LutSkinAnalyzer.kt` | 纯 Kotlin | 去掉 cv2 与 MediaPipe，见下 |
| 统计 | `playback/lut/LutSkinStats.kt` | 纯 Kotlin | 无差异（7 个统计量同式） |
| 搜参 | `playback/lut/LutParamPredictor.kt` | 纯 Kotlin | 无差异（模板/顺序/夹取/联动全对齐） |
| 串联 + 文案 | `playback/lut/LutAutoAnalyzer.kt` | 纯 Kotlin | 无差异（同单帧语义） |
| 接线 | `PlayerViewModel.autoTuneLut()` + 播放页按钮 | Android | 参考实现是独立窗口按钮，这里是播放器上的按钮 |

三处有意的差异，逐条说明理由：

1. **取帧不再用 mss / win32gui。** 参考实现虽然 `import mss`，但 `analyze_and_apply` 实际走的是
   `capture_mpv_frame_via_ipc`（`mpv_realtime_lut.py:1631`）——往 mpv 的 IPC 端口发
   `screenshot-to-file <path> "video"` 再读盘。Android 没有 IPC 端口，但**同一个命令经 JNI 到达
   同一个 mpv 实例**，所以 `MPVLib.cmd("screenshot-to-file", posixPath, "video")` 是等价路径，
   不需要新增 vo 回读通道，也不涉及解码链改动。
   - 参数用 `video` 而不是 `window`：`video` 是解码后的片源帧，不含字幕与 OSD；而 `target-lut`
     是 vo 级 shader，截图取的是片源帧，拿到的正是**未调色的原片**——这才是「当前画面适合
     什么参数」的输入。若取 `window`，分析结果会被上一次的 LUT 反噬。
   - 命令是异步的（mpv 等下一帧渲染完才落盘），没有完成回调可用，因此按「文件存在且非空」
     作为完成信号轮询，上限 `CAPTURE_TIMEOUT_MS`（2s）；文件用完即删，不攒 cacheDir。
2. **肤色提取不用 OpenCV + MediaPipe。** 参考实现的人脸路径是 MediaPipe FaceMesh（3.7MB
   `face_landmarker.task` + TFLite），失败时回退「中心 90% 区域」。这里**直接走中心区域路径**，
   并收紧到中心 **50%**：人脸框本来就是它的兜底，而中心区域越小，混进背景肤色（木地板、
   墙壁）的概率越低。判据（Cr 动态分位区间 + R/B + R/G + 亮度上限）全部按参考实现移植——
   R/G 那条是关键，它把「偏黄的木地板」和「偏红的皮肤」分开。
   缩放用普通双线性，不是 OpenCV 的定点实现；像素级差 1，对 7 个统计量的影响远小于 float32
   舍入量级（见 `LutSkinStats` 注释），不会让搜参跳到相邻档位。
3. **单帧，不做多帧采样。** 参考实现在 `analyze_and_apply` 里把 `used_deviated` /
   `deviated_ratio` / `deviated_direction` 三个键写死成「不使用偏离帧」，多帧偏离检测在它的
   单次分析路径里本来就不生效。这里保持同样的单帧语义——多帧会让「按一下按钮」变成需要
   等若干秒的采集过程。

**没有引入任何新依赖**：不增加 native 库、不增加模型文件，APK 体积不变。

#### 4.1.1 模板选择

参考实现的 `predict_params(features, K=3, use_extreme=False, use_high_match=False)` 默认**不启用**
极暗/高匹配模板，由调用方决定。本工程把它接到 `LutParamPredictor.selectMode()` 上自动选：

- `isExtremeDark`（`skin_lum < 0.28` 且 `full_hl_ratio < 0.01` 且 `full_sh_ratio > 0.24`，判据与
  参考实现 `is_extreme_dark` 同式）→ 极暗模板；
- 否则 `isHighMatch` → 高匹配模板；
- 否则标准模板。

自动选择**只换模板，不改搜索流程**。想复现参考实现的默认行为（永远从标准模板起步）可以传
`mode = LutAnalysisMode.Standard`——`LutAutoAnalyzer.analyze(frame, w, h, mode = ...)` 支持强制指定。

#### 4.1.2 交互与落盘

`PlayerViewModel.autoTuneLut()`：

- **只对 mpv 生效**：exo 没有 `target-lut` 通道（§4.2），对它搜出来的参数无处可用，因此直接给
  一句提示而不是假装成功。
- 截帧（最长 2s 轮询）与肤色统计（几十万像素）都在 `Dispatchers.IO`；只有写设置与下发回主线程，
  与 `applyLut` 的约定一致。位图取完像素立刻 `recycle()`（一帧 1080p 的 ARGB 是 8MB）。
- 结果写进设置：`enabled = true` + 搜出来的 7 个参数。因此分析完点开设置页，滑杆位置就是这次
  分析的结果，用户还能继续手动微调——**分析不是一次性黑盒，它把结果交给原有的滑杆 UI**。
- 没检测到肤色时不改设置，只回一句原因（"换个能看清人脸的画面再试"）。这是最常见的结果，
  静默失败会让用户以为按钮坏了。
- `lutAutoTuneBusy` 防重入；`onCleared()` 里 `lutAutoTuneJob?.cancel()`。

### 4.2 exo 内核无调色

**不可对齐。** media3/ExoPlayer 的 `PlayerView` 没有可注入 3D LUT 的渲染管线（`setVideoEffects` 仅支持 `RgbAdjustment`/`RgbFilter` 这类 3x3 矩阵与 GL 效果，无法表达任意 3D 查找表，也没有等价于 `target-lut` 的入口）。处理方式：设置项保留、exo 下不下发，页面明写"仅 mpv 生效"。

### 4.3 影响面

LUT 是"调色"而非"解码"，因此不影响：在线字幕、字幕样式、进度条、倍速、画面比例、音轨切换、内核切换。切换内核时 LUT 设置跨内核保留，mpv 接管后自动恢复。

---

## 五、libmpv.so 的最终来源

### 5.1 为什么是自建

- 本仓库不携带二进制（libmpv + FFmpeg 全量 `.so` 约 40MB/ABI，不适合进 Git）。`app/src/main/cpp/CMakeLists.txt` 以 IMPORTED 方式链接 `app/src/main/jniLibs/<abi>/libmpv.so`；缺失时自动退化成 stub 版 `libplayer.so`（`MPV_AVAILABLE=0`），App 仍可构建，运行期 `PlaybackEngineFactory` 判定 mpv 不可用并降级 exo。
- 自建链复用 **mpv-android buildscripts**（FFmpeg / libass / libplacebo / lua / curl 的整套交叉编译），而不是自己手搓 meson 交叉文件——那套依赖关系（尤其 libplacebo 与 FFmpeg 的版本咬合）自己维护的成本远高于收益。

### 5.2 用法

```bash
./scripts/fetch-libmpv.sh --clone-only                 # 只克隆构建链，检视用
./scripts/fetch-libmpv.sh --build arm64                # 编译并装进 jniLibs/arm64-v8a
./scripts/fetch-libmpv.sh --build all                  # arm64 + armv7l + x86_64
./scripts/fetch-libmpv.sh --verify                     # 校验 jniLibs 里已有产物
./scripts/fetch-libmpv.sh --build arm64 --yaozhi       # 打 Yaozhi 通用补丁后编译（见 §5.4）
./scripts/fetch-libmpv.sh --build arm64 \
    --extra-patches ../some/dir                        # 手动指定补丁目录（见 §5.5）
```

- 构建**只能在 Linux / macOS**（上游 buildscripts 明确不支持 Windows）。Windows 侧走 `.github/workflows/build-libmpv.yml`（workflow_dispatch，产物为 `libmpv-<abi>` artifact）或 WSL2 / Docker。
- 版本 pin：`MPV_ANDROID_REF=73a7c69e5f3d9a7e9b3a27e38ca5a8ed702cf866`。升级 = 改这一个常量，属显式决策。
- 安装步骤把 `prefix/<arch>/lib/*.so` **全部**拷进 jniLibs：libmpv 运行期按 `DT_NEEDED` dlopen `libavcodec` / `libass` / `libplacebo` 等兄弟库，缺一个就是 dlopen 失败。
- `--verify` 做三重校验：文件大小 < 100KB 判假产物、ELF 魔数、按 `e_ident[EI_CLASS]` + `e_machine` 验架构。

### 5.3 关于"Yaozhi 定制解码"

`mpv-Yaozhi-core` 是面向 **Windows mpv** 的整合包（WASAPI / SDL2 音频布局、Dolby Vision FEL、VVC、AV3A、HDR Vivid、蓝光 ISO seek、uosc 弹幕等）。它的补丁分两类：

- **Windows 平台相关**（WASAPI / SDL2 / 蓝光 ISO seek / nvenc）：Android 上既 apply 不上也没有语义，**不适用**。
- **编解码与渲染相关**（FFmpeg 侧 VVC/webp/AAC 回移、vo_gpu_next 图形字幕色彩空间、HDR Vivid 元数据、demux 中断与 hwdec surface pool）：改的是 FFmpeg/mpv 通用代码，**可以交叉编译到 Android**。

因此本工程把"能用的一部分"做成 `--yaozhi` 预设（候选集见 §5.4），其余显式排除并给出理由。

**排除清单与理由**（这是"不得默认省略"的部分，逐条写清）：

| 排除项 | 代表补丁 | 不适用于 Android 的理由 |
|---|---|---|
| WASAPI / SDL2 音频布局 | `0002`, `0003`, `0005`, `0006` | mpv-android 走 AudioTrack/OpenSL，没有 Windows 音频栈，也没有 SDL2 目标；补丁改的是 `ao_wasapi_utils.c` / `SDL_wasapi.c`，这些文件在构建链里不被编译 |
| 蓝光 ISO / 光盘 demux | `0016-stream-bluray-support-seekable-remote-images.patch`, `0013-demux-scope-disc-subtitle-cache.patch` 的 disc 部分 | 依赖 libbluray；Android 构建链不编 libbluray（`--enable-libbluray` 缺失），`demux_disc.c` 不参与编译 |
| Dolby Vision FEL 配对 / 拆分 | `build/dolby-vision/*`（8 个） | 成组依赖：FFmpeg 侧 `ffmpeg-dovi-split-*` 引入 `dovi_split` bsf，mpv 侧需要 `demux/dovi_split.c`——**该文件在本仓库里并不存在**（只有 `.patch` 引用它）。单打 FFmpeg 侧会因 bsf 未登记而配置失败 |
| AV3A / AVS3 / DAVS2 | `build/av3a/*`（含 `ffmpeg-av3a-*`, `avs3-*`, `davs2-*`） | 需要私有库 `libarcdav3a` / `libavs3_common` / `av3a_binaural_render`，仓库内无其源码；补丁只改"调用方"，缺库时 meson 配置阶段就失败 |
| nvenc | `build/av3a/ffmpeg-nvenc-sdk-13.1.patch` | 需要 Windows CUDA SDK；Android 无 NVENC |
| Atmos 侧车与 pin 脚本 | `build/atmos/*` | 改的是 Yaozhi 自己的构建脚本（`scripts/apply-patches-master.sh`），不是 mpv/FFmpeg 源码 |
| 旧版 core-candidate（main-99xx） | `build/core-candidate/main-99*.patch` | 面向 `mpv@99b4c12` 这一具体基线；构建链拉的是上游 master/发行版，改用内容等价的 `mpv-880x` 系列（同一批上游提交，见该目录 README） |
| 只对特定 ffmpeg 版本的 `ffmpeg-8800-truehd-mat-padding` | 同名补丁 | 依赖 `spdifenc.c` 的特定上下文；构建链 FFmpeg 版本不同，大概率 `--check` 不过（会由脚本自动跳过，不阻断构建） |

**启用方式**：

```bash
./scripts/fetch-libmpv.sh --build arm64 --yaozhi
# 补丁仓库不在默认位置时：
./scripts/fetch-libmpv.sh --build arm64 --yaozhi --yaozhi-repo /path/to/mpv-Yaozhi-core
```

执行语义（三条保障，均已在沙箱实测）：

1. **先 `git apply --check`，通过才 `git apply`**。不通过只告警，源码不会处于半应用状态。
2. **可重复执行**：先试 `git apply --check --reverse`，反向能干净应用说明"已打过"，直接算成功。避免第二次运行把已应用的补丁误报为冲突。
3. **裸 unified diff 回退**：少数补丁缺 `diff --git` 头，`git apply` 不认时退回 `patch -p1 --dry-run` 再 `patch -p1`。

### 5.4 `--yaozhi` 候选集（= 脚本里 `YAOZHI_PATCHES` 数组）

| # | 补丁 | 目标树 | 作用 |
|---|---|---|---|
| 1 | `build/vvc/ffmpeg-native-default.patch` | FFmpeg | VVC 原生解码默认开启 |
| 2 | `build/ffmpeg9/ffmpeg-animated-webp-backport.patch` | FFmpeg | 动图 WebP 解码回移 |
| 3 | `build/ffmpeg9/ffmpeg-he-aac-960-backport.patch` | FFmpeg | HE-AAC 960 帧回移 |
| 4 | `patches/0001-vo_gpu_next-add-image-subtitle-colorspace-control.patch` | mpv | 图形字幕（PGS/VobSub/DVB）色彩空间控制，新增 `image-subs-colorspace` |
| 5 | `patches/0004-video-expose-hdr-vivid-metadata.patch` | mpv | 暴露 HDR Vivid 元数据 |
| 6 | `patches/0007-swresample-use-native-canonical-input-layout.patch` | mpv | swresample 使用原生声道布局 |
| 7 | `build/core-candidate/mpv-8804-demux-interrupt-discarded-read.patch` | mpv | 丢弃读中断修复 |
| 8 | `build/core-candidate/mpv-8805-vo-frame-refs.patch` | mpv | VO 帧引用计数（#9 的前置提交） |
| 9 | `build/core-candidate/mpv-8805-hwdec-surface-pool.patch` | mpv | 按 VO 需求自动设置 hwdec 表面池 |

顺序有意义：#8 必须排在 #9 之前（`core-candidate/README.md` 明确写 vo-frame-refs 是 hwdec auto-sizing 的直接前置）。

**仍会失败的情形**：候选集过的是 `apply --check`，不等于过得去编译。补丁对应的上游 mpv 基线是 `99b4c12`，构建链拉的是当前 master/发行版；版本漂移后 `--check` 就会失败并被跳过——这时输出里会看到"跳过（与当前 mpv/FFmpeg 版本不匹配）"，不是静默失效。想锁死基线，把 `MPV_ANDROID_REF` 改成包含对应 mpv tag 的提交。

### 5.5 `--extra-patches` 语义（手动指定目录）

1. 要求 deps 就绪（脚本会先自动跑 `download.sh`，幂等）。
2. 对目录内每个 `*.patch` / `*.diff`，按文件名推断目标树：`ffmpeg-*` → `deps/ffmpeg`，其余 → `deps/mpv`。
3. 逐个执行 §5.3 那三条保障。

需要精确控制目标树时，把补丁按树分目录，分两次 `--extra-patches` 调用即可。

### 5.6 若要继续扩大 Yaozhi 补丁覆盖

1. 在 Linux 环境跑一次 `--build arm64`（不带补丁），得到 `build/mpv-android/deps/{ffmpeg,mpv}`。
2. 从 `mpv-Yaozhi-core/build/{dolby-vision,vvc,av3a,hdr-pgs}` 里挑目标补丁，**每次只加 1~2 个**。
3. `--extra-patches <该目录> --build arm64`，看"应用/跳过"计数、真机播放效果与实际报错。
4. 一次只加一个维度，否则出问题时无法定位是哪个补丁。

> 风险提示：AV3A / DoVi FEL 等栈还依赖**私有编解码库**，仓库里没有这些库的源码，`--extra-patches` 无法补上这一层。缺库时表现为 meson 配置失败或运行期 decoder 注册失败，而不是补丁应用失败。

---

## 六、验证记录

| 验证项 | 方式 | 结果 |
|---|---|---|
| 纯 JVM 算法层编译 | `work/compile-pure-jvm.sh` tier1 | `[tier1 compile] 78 files` / `[tier1 test compile] 33 files`，两次 exit 0 |
| 单测（tier1 全量） | 同上，tier1 test | `Tests run: 316, Failures: 5`——5 个失败全部是既有的番号前缀用例（`MovieNumberExtractorTest` / `MovieNumberUtilsTest`，与基线一致），`LutColorMathTest` 10 个 + `LutAutoAnalyzerTest` 13 个全绿 |
| 新增单测单独跑 | `krun.sh lut/ work/junitstub/ LutAutoAnalyzerTest.kt -- junitstub.RunnerKt com.example.localmovielibrarz.playback.lut.LutAutoAnalyzerTest` | `cases: 13 passed, 0 failed` |
| 自动调参端到端冒烟 | `krun.sh lut/ work/autolutspec/kt/AutoMain.kt -- auto.AutoMainKt` | `ALL AUTO SMOKE PASSED`——无肤色帧回落标准模板、合成肤色帧提出 R/B=1.710 并搜出参数、`simulateOnSkin` 只读不改参、强制模板生效、尺寸/长度校验抛异常 |
| 搜参与参考实现对拍 | `krun.sh lut/ work/autolutspec/kt/GoldenMain.kt work/autolutspec/kt/GoldenData.kt -- golden.GoldenMainKt work/autolutspec/kt/golden.txt` | `cases=34 statsFail=0 paramFail=0` / `ALL GOLDEN CASES PASSED`——34 组输入（含三模板强制分支）的 7 参数与 7 统计量逐个相等 |
| 数值鲁棒性 | `work/autolutspec/sens.py` | float32 抖动 1e-6 后 40 组随机样本参数全部不变（搜索不会跳到相邻档位） |
| 括号/结构完整性 | `python work/brace-check-any.py <8 个改动文件>` | `LutSkinStats` / `LutSkinAnalyzer` / `LutParamPredictor` / `LutAutoAnalyzer` / `LutAutoAnalyzerTest` / `MpvPlaybackEngine` / `PlayerViewModel` / `PlayerScreen` 全部 `OK` |
| Android 侧三文件语法 | 单文件 `kotlinc`（仅看语法类错误） | 只有 `unresolved reference`（缺 `android.jar` / 同包类，无 Android SDK 无法消除），无 `expecting` / `unexpected` / `unbalanced` 类语法错误 |
| 脚本语法 | `bash -n scripts/fetch-libmpv.sh` | OK |
| 脚本 `--help` | `bash scripts/fetch-libmpv.sh --help` | 正常输出用法（含 `--yaozhi` / `--yaozhi-repo` / `--extra-patches`） |
| 补丁三态语义 | `work/prep-patchtest.py` 造合成仓库 + `work/patchtest/harness.sh` 实跑 `apply_one_patch` | case1 首次应用 `rc=0`；case2 重复应用识别为"已是应用状态" `rc=0` 且文件未被二次改动；case3 不匹配补丁 `rc=1` 且源码保持原样 |
| `--yaozhi` 候选集静态校验 | `work/check-yaozhi-list.py` | 9 个候选补丁全部存在、头部路径与归属源码树一致（异常 0 个） |
| 全量 APK 编译 | 未执行（本机无 Android SDK / NDK） | `NOT RUN` |

**未验证项**（明确不冒充已验证）：

1. 真机上 mpv 加载 LUT 后的实际观感；65³ 尺寸在低端机上的生成耗时（估算 150ms 级，已在 IO 线程，首次生成仍可能造成一次短暂卡顿）。
2. `--yaozhi` 候选补丁对**当前** mpv/FFmpeg 基线的 `git apply --check` 结果——需要 deps 下载完成（Linux + 网络 + 十几分钟）。本机只做到"清单与头部路径静态正确"。
3. 候选补丁通过 `apply --check` 后能否通过 meson 编译。版本漂移时补丁会先被跳过（有告警），不会静默失效。
4. Windows 侧 `fetch-libmpv.sh` 无法执行构建（上游 buildscripts 不支持），因此本地没有产出过任何 `libmpv.so`；CI workflow 未被实际触发。
5. **截帧链路只在代码层验证过**：`screenshot-to-file` 的落盘时序、2s 超时在真机上是否够用、
   Android 10+ 分区存储下 `cacheDir` 的可写性，都需要真机跑一次；本机无 Android SDK，`NOT RUN`。
6. **肤色提取与参考实现未做像素级对拍**：参考实现走 OpenCV + MediaPipe，本工程走中心区域，
   两者本就不是同一张掩码，因此只能保证「判据同式」，不能保证「同一帧选出同一批像素」。
   真机上的验收标准是「输出参数在合理范围内、按钮点下去画面有预期变化」，不是逐像素相等。
7. **移动端性能未实测**：640 长边下约 20 万像素的肤色遍历，加上约 150 次搜参评估，
   估算在 100ms 量级（已在 IO 线程）；低端机上的实际耗时与按钮转圈观感待真机确认。

---

## 七、同一链路里顺带修掉的缺陷

实现 §4.1 的过程中，在**已有代码**与**新写的截帧链路**上各查出缺陷，已全部修复。
两处都在 `playback/engine/MpvPlaybackEngine.kt`。

| # | 位置 | 现象 | 原因 | 修复 |
|---|---|---|---|---|
| 1 | `MpvPlaybackEngine.kt:337` + `:365`（`applyLut`） | 拖动调色滑杆时画面「追不上」：最终参数是对的，但中间有几帧在显示更早那一次的调色；同时成串的 LUT 被反复生成 | `lutExecutor` 是单线程队列，`applyLut` 只在**入口**比一次签名就把任务排进去。排队期间用户继续拖动，前面那些「已经不新鲜」的任务仍会各自 `materialize` 一张 LUT 并 `set target-lut`，把后面更新的那张覆盖掉 | 新增 `@Volatile pendingLutSignature`，入队前登记；任务真正开跑时（`:378`）与 post 回主线程时（`:392`）各复检一次，被更新请求覆盖的直接让位 |
| 2 | `MpvPlaybackEngine.kt:701`（`awaitFile`） | 若在主线程调用 `captureFrameForAnalysis()`，「自动分析」永远返回「没抓到画面」，且每次白等 2s | 截图命令本身是 `mainHandler.post` 投递的，主线程上轮询等文件会把那条命令一起堵在队列里——命令永远执行不到，文件永远不出现 | 进轮询前先判 `Looper.myLooper() == Looper.getMainLooper()`，命中直接返回 `false` 并打日志，把「必超时」变成明确的调用约定违例 |
| 3 | `MpvPlaybackEngine.kt:776`（`CAPTURE_MAX_SIDE`） | 1080p 帧完全不降采样，整幅 8MB ARGB 解码完再一路缩到 640，峰值内存多一倍 | 常量写成 1280，而 `decodeScaled` 的采样率取「降完**不小于** `maxSide` 的最大 2 的幂」，`1080p` 配 1280 算出 `sample=1`。注释里写的却是 640，两处不一致 | 常量改成 640，与 `LutSkinAnalyzer.MAX_SAMPLE_DIM` 一致；1080p 得到 `sample=2`（960 宽），再由分析器双线性缩到 640 |

第 2、3 条是新写的截帧链路在自查阶段修掉的（`captureFrameForAnalysis` 尚未随任何版本发布过）；
第 1 条是本次改动之前就存在的引擎缺陷，与自动分析调参共用同一条「生成 → 下发」路径，属于同一处改动面。

**明确不改的项**（与本次功能无关，按「不扩大改动范围」保留原样）：

- `playback/lut/LutFileWriter.kt:78`：`.tmp` 残留会被 `cleanup` 的两次过滤各删一次（`staleFileNames`
  已含 `.tmp`，后面又单独扫一遍）。重复删除幂等、无副作用，改动只会增加 diff 噪声。
- `ui/player/PlayerViewModel.kt:1974`：`engineHeaders` 的 `buildMap {        putAll(...)` 是一个基线上
  就存在的排版残留（大括号与语句挤在一行），语法合法、行为正确，仅可读性差。
