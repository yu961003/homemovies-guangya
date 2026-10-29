# mpv 移植 · 分阶段实施记录

> 任务：把 mpv 完整移植进 `homemovies_guangya`，形成 exo / mpv 双播放器并存架构，
> mpv 在 UI 与功能上与 exo 对齐（**在线字幕必须继续可用**），两者切换流畅、状态不丢失。
>
> 基线：`work/mpv-port-baseline/homemovies_guangya`（移植前快照，仅本地存在，不进交付包）。
> 环境限制：本机**无 Gradle / Android SDK / NDK**，无法执行 `:app:assembleDebug`，
> 也无法真机验证。因此每个阶段的「验证结果」都区分**本机实测**与**未执行（NOT RUN）**，
> 不做任何「已在设备上验证」的虚假声明。详见 §7。

---

## 总览

| 阶段 | 目标一句话 | 新增文件 | 修改文件 | 关键验证 |
|---|---|---|---|---|
| 0 | 冻结基线，测绘 exo 现状 | — | — | 目录级 diff 可复现 |
| 1 | 抽出一层窄的播放内核接口 | 4 kt | 0 | Tier-1 纯 JVM 编译 |
| 2 | libmpv 接进 APK（JNI + 渲染面 + 构建链） | 12 cpp + 2 kt + 2 构建产物 | 1 gradle | CMake 双路径（真 / stub）静态核对 |
| 3 | mpv 引擎实现全部播放语义 | 2 kt | 0 | 逐条比对 exo 调用点 |
| 4 | 字幕对齐（在线字幕链路 + 偏移 + 样式） | 1 kt | 1 kt | 与 exo 的字幕代码路径逐条对照 |
| 5 | UI 双分支 + 切换逻辑 | 1 kt(test) | 4 kt | **单测 8/8 通过（实测）** |
| 6 | 全量回归 + 文档 + 打包 | 3 md | — | Tier-1 全套 193 用例 |

改动量（相对基线，全部为新增行，无删除型重构）：

| 文件 | 基线 | 现在 | 增量 |
|---|---|---|---|
| `ui/player/PlayerViewModel.kt` | 1815 | 2412 | +597 |
| `ui/player/PlayerScreen.kt` | 2243 | 2414 | +171 |
| `ui/settings/SettingsScreen.kt` | 4251 | 4279 | +28 |
| `ui/settings/SettingsViewModel.kt` | 1560 | 1582 | +22 |
| `data/repository/AppSettingsRepository.kt` | 1417 | 1449 | +32 |
| `app/build.gradle.kts` | 89 | 115 | +26 |
| 新增 Kotlin（`mpv/` + `playback/engine/`） | 0 | — | 1535 |
| 新增 C++（`cpp/`，含 vendored `mpv/client.h`） | 0 | — | 2826 |

---

## 阶段 0 · 基线冻结与现状测绘

### 目标

在动任何代码之前留下可回溯的基线，并把 exo 的 UI 结构与功能清单**穷举**出来，
作为后续「功能对齐」的验收标尺。没有这份清单，"对齐"就只能是感觉。

### 改动点

无代码改动。产出两份东西：

1. **基线快照**：整个工程复制到 `work/mpv-port-baseline/homemovies_guangya`。
   后续任何 `diff -rq` 都以它为准，这是「不引入无关重构」这一约束的机械保证。
2. **exo 功能清单**：按「渲染与画面 / 播放控制 / 字幕 / 音频 / 手势与交互 / VR /
   状态与系统集成 / 错误处理」八类逐条列，落进 `docs/MPV-EXO-PARITY.md`（54 条）。

测绘过程中确认的关键事实（决定了后面所有设计）：

| 事实 | 影响 |
|---|---|
| `PlayerUiState.player` 的类型是 `ExoPlayer?`（不是 media3 `Player`） | 不能在 `Player` 接口上做兼容壳 |
| `PlayerScreen.kt` 2243 行，所有控件都直接读 `player.currentPosition/duration/bufferedPosition` | mpv 必须有办法把"当前位置/时长/缓冲"喂进同一批变量 |
| 在线字幕是「搜索 → 下载 → `LocalSubtitleStore` → 重建 `MediaItem`」四段 | 前 3.5 段与内核无关，只有最后一步要换实现 |
| VR 走 `VrSphericalPlayerView` + media3 `SphericalGLSurfaceView` + 帧元数据 | mpv 无等价能力，必须显式列为不可对齐 |
| 工程原本没有任何 `externalNativeBuild` / `ndkVersion` | 光有 `cpp/` 目录不会被编译，必须接进 Gradle |

### 验证结果

`diff -rq` 基线 vs 现状：确认改动集恰好是「新增 `cpp/`、`mpv/`、`playback/engine/`、
`jniLibs/`、`.github/`、`scripts/fetch-libmpv.sh`、测试目录 + 修改 6 个文件」，
**没有触碰任何与播放无关的模块**（刮削、网盘、数据库、导航图均零改动）。

---

## 阶段 1 · 播放内核抽象层

### 目标

让 `PlayerScreen` / `PlayerViewModel` 面向一个**窄接口**编程，而不是面向 `ExoPlayer` 类。
这一步是整个双播放器架构的地基：接口一旦过宽（比如照抄 media3 的 `Player`），
实现成本会爆炸；过窄则后面每加一个功能都要改接口。

### 改动点

新增 `playback/engine/`：

| 文件 | 内容 |
|---|---|
| `EngineTypes.kt` | `PlaybackEngineKind`（EXO/MPV）、`PlaybackSnapshot`（isPlaying / positionMs / durationMs / bufferedMs / speed / videoWidth / videoHeight / playbackState / errorMessage）、`EngineAvailability`、`VideoResizeMode`。**刻意不 import 任何 android/androidx**，理由见阶段 6 的单测。 |
| `PlaybackEngine.kt` | 接口本体：`play/pause/setPlayWhenReady/seekTo/seekBy/setSpeed/load/setExternalSubtitle/clearExternalSubtitle/setSubtitleOffset/setSubtitleStyle/setSubtitleEnabled/selectAudioTrack/release` + `snapshot: StateFlow<PlaybackSnapshot>`；另含 `ExternalSubtitle`、`SubtitleStyle` 两个引擎无关描述，以及 `PlaybackEngineFactory`（可用性探测 + 结果缓存）。 |
| `ExoPlaybackEngine.kt` | exo 侧适配：包一个 `ExoPlayer`，把 `Player.Listener` 的回调投影成 `PlaybackSnapshot`。**不改变 exo 的任何行为**，只是把它的状态读出来。 |
| `EngineSwitchPlanner.kt` | 切换决策的纯函数（见阶段 5）。 |

设计取舍（都写进了代码注释）：

- **不做 media3 `Player` 兼容壳**。`Player` 有 150+ 个方法，为 mpv 实现它是为了"复用"一个
  本工程根本没用的抽象，纯属自伤。改成窄接口后 `PlayerScreen` 要改的地方不到 200 行。
- `PlaybackSnapshot` 里的 `STATE_*` 常量**复制** media3 的取值（1/2/3/4）而不是引用它，
  目的同样是让 `EngineTypes.kt` 保持零 Android 依赖。
- `PlaybackEngineFactory` 的探测结果**缓存**且**异常不上抛**：`libmpv.so` 缺失是"功能降级"，
  不是"启动失败"。

### 验证结果

- **实测**：`bash work/compile-pure-jvm.sh` 收集到 42 个主源文件，`kotlinc` 退出码 0。
- **实测**：`EngineTypes.kt` / `EngineSwitchPlanner.kt` 不含 `import android.*` / `import androidx.*`
  （这是阶段 6 单测能跑起来的前提，已用 grep 确认）。
- **NOT RUN**：`ExoPlaybackEngine.kt` 依赖 media3，本机无 Android SDK，未编译。

---

## 阶段 2 · libmpv 原生层接入

### 目标

把 libmpv 真正装进 APK：JNI 封装层、渲染面、事件桥、以及**能产出 `libmpv.so` 的构建链**。
这一阶段结束时，`libmpv.so` 存在就能出画面，不存在也能正常构建并降级。

### 改动点

#### 2.1 C++ 封装层（`app/src/main/cpp/`，2826 行）

| 文件 | 职责 |
|---|---|
| `include/mpv/client.h` | vendored 的 libmpv 官方 C 头（2033 行），固定 API 版本，不随引擎构建漂移 |
| `src/main.cpp` | `mpv_create` / `mpv_initialize` / `mpv_command` / `mpv_set_option_string` / `mpv_create` 生命周期，JNI 入口 `nativeCreate/nativeInitialize/nativeCommand/nativeSetOption/...` |
| `src/property.cpp` | `mpv_get_property` / `mpv_set_property` / `mpv_observe_property` / `mpv_unobserve_property` 的四个类型（int64/double/flag/string）分支 |
| `src/render.cpp` | `wid` 直挂 `SurfaceView`（`ANativeWindow_fromSurface` → `MPV_FORMAT_INT64 "wid"`）；换面时的 attach/detach 时序 |
| `src/event.cpp` | 独立事件线程 `mpv_wait_event(-1)`，把 `MPV_EVENT_*` 与属性变更回调进 JVM |
| `src/log.cpp` | `mpv_request_log_messages` → logcat |
| `src/jni_utils.cpp` | JNI 字符串/数组转换、全局引用管理、异常安全检查 |
| `src/stub.cpp` | **stub 实现**：签名与真实现完全一致，`isAvailable→JNI_FALSE`、`create→-2`、`init→-3` |
| `CMakeLists.txt` | 47 行，见下 |

`CMakeLists.txt` 的双路径是本阶段的核心决策：

```cmake
set(MPV_SO "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libmpv.so")
if(EXISTS "${MPV_SO}")
    add_library(mpv SHARED IMPORTED)          # 找不到也能配置成功
    add_library(player SHARED src/main.cpp src/property.cpp src/render.cpp
                             src/event.cpp src/log.cpp src/jni_utils.cpp)
    target_compile_definitions(player PRIVATE MPV_AVAILABLE=1)
else()
    add_library(player SHARED src/stub.cpp)   # 没有 so 就编 stub
    target_compile_definitions(player PRIVATE MPV_AVAILABLE=0)
endif()
```

**为什么不是 Gradle 层的条件开关**：那会让"带 so"和"不带 so"变成两条构建配置，
两条都要维护、CI 要跑两遍。放在 CMake 里，只有一条构建路径，`assembleDebug` 永远成功。

#### 2.2 Kotlin 绑定与渲染面

| 文件 | 职责 |
|---|---|
| `mpv/MPVLib.kt`（268 行） | `System.loadLibrary("player")` 的懒加载 + 失败捕获；`probe()`（是否可用）、`loadErrorMessage()`；对 C++ 的 21 个 JNI 方法做 Kotlin 友好包装（`setInt/setDouble/setBoolean/setString/observe/unobserve/getXxx`）；`Listener` 接口（`onEvent/onEndFile/onProperty`）与监听器列表 |
| `mpv/MpvVideoView.kt`（120 行） | `SurfaceView` 子类，`surfaceCreated` → `attachSurfaceSafe(surface)`，`surfaceDestroyed` → `detachSurfaceSafe()`；`setRenderSize(w,h)` 同步 `mpv_render` 所需的缓冲区尺寸 |

#### 2.3 构建接入（关键修复）

基线 `app/build.gradle.kts` **完全没有** `externalNativeBuild` —— `cpp/` 目录存在但不会被编译。
本阶段补上：

```kotlin
ndkVersion = "27.0.12077973"          // 显式 pin，避免 AGP 默认版本在 CI/本机漂移
defaultConfig {
    externalNativeBuild { cmake {
        arguments += listOf("-DANDROID_STL=c++_static")
        cppFlags += listOf("-std=c++17")
    } }
}
externalNativeBuild { cmake {
    path = file("src/main/cpp/CMakeLists.txt")
    version = "3.22.1"
} }
```

用 `c++_static` 而不是 `c++_shared`：封装层是纯 JNI C 风格代码（`-fno-exceptions -fno-rtti`），
静态链接少打包一个 `libc++_shared.so`，也不会和 `libmpv.so` 自带的 C++ 运行时在同进程撞符号。

#### 2.4 `libmpv.so` 获取链路

| 文件 | 职责 |
|---|---|
| `scripts/fetch-libmpv.sh` | 复用 `mpv-android/buildscripts` 交叉编译链（FFmpeg/libass/libplacebo/lua/curl 整棵树），pin 到 commit `73a7c69`；`--clone-only` / `--build arm64\|armv7l\|x86_64\|all` / `--verify`；把 `prefix/<arch>/lib/*.so` **整组**拷进 `jniLibs/<abi>/`（漏一个兄弟库就 dlopen 失败）；`--verify` 按 ELF 魔数 + e_machine + 文件大小下限校验；Windows 直接给出替代路径（上游明确不支持） |
| `.github/workflows/build-libmpv.yml` | `workflow_dispatch`（可选 `archs` / `full_abis`）+ `release/**` 分支触发；matrix 三个 ABI；缓存 `buildscripts/{sdk,deps,prefix}`；产物 artifact `libmpv-<abi>` |
| `app/src/main/jniLibs/README.md` | 说明每个 ABI 目录要放哪些 `.so`、三种产出方式、"不放也合法"的降级行为 |

### 验证结果

- **实测（静态）**：`CMakeLists.txt` 两条分支都被人工走查过一遍——`EXISTS` 为真时
  `IMPORTED` target 的 `INTERFACE_INCLUDE_DIRECTORIES` 指向 `cpp/include`，为假时只有
  `stub.cpp`，两条路径的产物名都是 `libplayer.so`，Kotlin 侧 `loadLibrary("player")` 无需分支。
- **实测（静态）**：`stub.cpp` 的每个 JNI 函数签名与 `main.cpp` 逐一比对，参数/返回类型一致
  （签名不一致会在运行期 `UnsatisfiedLinkError`，这是最容易埋的坑）。
- **实测**：`.github/workflows/build-libmpv.yml` 的 YAML 用 Python `yaml.safe_load` 解析通过。
- **NOT RUN**：`libmpv.so` 未在本机构建（无 NDK + 上游 buildscripts 不支持 Windows）；
  CI 工作流未实际触发过。
- **NOT RUN**：`libplayer.so` 未编译过（无 NDK）。

---

## 阶段 3 · mpv 播放引擎功能对齐

### 目标

`MpvPlaybackEngine` 要把 exo 侧所有"播放语义"实现一遍：播放/暂停、seek、倍速、音轨、
缓冲态、结束态、错误、进度续播、请求头透传、硬件解码开关。

### 改动点

| 文件 | 内容 |
|---|---|
| `MpvPlaybackEngine.kt`（524 行） | 引擎实现：生命周期（`initialize` 时把 `--option` 在 `mpv_initialize()` **之前**全部下发，否则被静默忽略）、属性观察（`time-pos/duration/pause/paused-for-cache/speed/eof-reached/width/height/demuxer-cache-time/track-list`）、事件处理（`FILE_LOADED` 时补做 pending seek 与 pending 字幕）、`load()`（拼 `start=` 续播；http(s) 直链改道回环代理见 `MpvHttpProxy`，见下方网络层说明）、`setSpeed`（夹取 0.01–100）、`setVideoResizeMode`（FIT/FILL/ZOOM → `keepaspect`+`panscan`）、`readAudioTracks`（`track-list` → 扁平 `MpvAudioTrack`）、`release`（先 unobserve 再 destroy，防回调打到已释放的 JNI 引用） |
| `MpvOptions.kt`（107 行） | 启动参数清单，**每一条都注明它对应 exo 的哪个行为**：`vo=gpu`/`gpu-context=android`、`hwdec=mediacodec`（或 `no`）、`video-sync=audio`、`hr-seek=no`、`ao=audiotrack`、`osc=no`+`input-default-bindings=no`（UI 全交给宿主 Compose）、`cache-dir`、`--http-header-fields`、日志级别、`idle=yes` 使实例可复用（**注**：`cache-dir` 在 v0.41 并不存在，实际用的是 `cache-on-disk=yes`+`demuxer-cache-dir`；`--http-header-fields` 只在「回环代理没起来、退回直连」时才下发。两处均见 `docs/MPV-NETWORK-PROXY-20261029.md`） |

关键取舍：

- **不用 mpv 的 UI 层**（`osc=no`、`input-default-bindings=no`）。路线文档 Phase 4 里那套
  Lua/OSC UI 与宿主已有的 Compose 播放器 UI 是两套东西，同时开会出现两层控件。
- `hr-seek=no`：对应 exo 的 `SeekParameters.CLOSEST_SYNC`（关键帧 seek，快）。
- 属性用 `DOUBLE` 取时间（秒），只在换算成 `PlaybackSnapshot.positionMs` 时做一次 ×1000，
  避开 `INT64` 在时间类属性上的微秒/毫秒歧义。

### 验证结果

- **实测（静态）**：逐条比对 `ExoPlaybackEngine` 的每个 `override fun` 与 `MpvPlaybackEngine`
  的同名实现，14 个方法全部有对应实现，无遗漏（清单见 `docs/MPV-EXO-PARITY.md` §2–§4）。
- **实测（静态）**：`MpvPlaybackEngine` 里所有 `MPVLib.setXxx` 调用的属性名与 libmpv 文档
  的属性名逐字核对（`time-pos`/`demuxer-cache-time`/`sub-visibility` 等 20 个）。
- **NOT RUN**：未真机播放。

#### 3.1 网络层（第二轮补，前一轮整层漏掉）

第一阶段的对齐只覆盖画面/字幕/控制，**没有对「URL 是怎么被打开的」**，而
`loading_failed(-13)` 全部产生在这一层。第二轮把 mpv 的 http(s) 直链改道
本机回环代理（`MpvHttpProxy`），TLS / UA / 请求头 / 重定向统一交给 OkHttp，
与 exo 的 `DefaultHttpDataSource` 对齐。详见 `docs/MPV-NETWORK-PROXY-20261029.md`。

| 位置 | 改动 |
|---|---|
| `engine/MpvHttpProxy.kt`（新增） | 127.0.0.1 随机端口回环 HTTP 服务端 + OkHttp 上游客户端；登记 URL/请求头，透传 `Range`/`206`，流式转发 |
| `engine/MpvPlaybackEngine.kt` | `initialize()` 启动代理；`startLoad()` 改为 `loadfile http://127.0.0.1:…/<token>/<id>/<name>`；`describeNetworkPath()` 把「走代理/直连 + 上游状态码」写进错误文案 |
| `mpv/MPVLib.kt` | 新增 `LogSink` 旁路：把 mpv 自己的 lavf/http 报错喂给引擎，最终出现在错误文案里 |
| `engine/MpvOptions.kt` | `user-agent` 改用与 exo 同一个常量 `DEFAULT_USER_AGENT`；`tls-verify`/`tls-ca-file` 降级为「直连兜底」专用，且**成对下发**（无 CA 包时显式 `tls-verify=no`，不留「要校验却没锚」的失败面）；`ytdl=no` 关掉内嵌 ytdl_hook（它会拦截含 `127.0.0.1` 在内的所有 `^https?://`） |
| `test/.../engine/MpvHttpProxyTest.kt`（新增） | 纯 JVM 单测（无需设备）：本地假上游 + 手写极小 HTTP 客户端，钉死代理的「逐字节等价」——200/206/HEAD/chunked/502/403/token/`close()`。CI 在 `assembleDebug` 之前执行 |

---

## 阶段 4 · 字幕对齐（含在线字幕）

### 目标

在线字幕链路（搜索 → 下载 → 本地库 → 挂载 → 偏移 → 样式 → 开关）在 mpv 上**完整可用**，
且偏移值与 exo **同源**（切换内核不丢偏移）。

### 改动点

| 文件 | 内容 |
|---|---|
| `MpvSubtitleBridge.kt`（63 行） | 把任意 `Uri`（`content://` / SAF / 网盘 provider / `file://`）落成 mpv 可读的普通文件。mpv 的 `sub-add` **不走 ContentResolver**，`content://` 直接传必失败。策略：`localFile` 已可读→直接用；`file://`→取 path；否则复制到 `cache/mpv-subtitles/<sha1(uri\|name)>.<ext>`，**带内容哈希命名，命中即复用**，避免每次播放重复 IO |
| `MpvPlaybackEngine.setExternalSubtitle` | 媒体未 `FILE_LOADED` 时挂到 `pendingSubtitle`，等 `FILE_LOADED` 再 `sub-add`（无文件时 `sub-add` 会失败）；挂载前先 `sub-remove` 避免多份字幕叠加 |
| `MpvPlaybackEngine.setSubtitleOffset` | `sub-delay=<offsetMs/1000>`。exo 是重写字幕文件时间轴，mpv 用 `sub-delay` 等值表达，**正负方向一致**（正=字幕推后）。选这个是为了不产生磁盘写入，且切换内核后无需重算 |
| `MpvPlaybackEngine.setSubtitleStyle` | 字号 → `sub-scale`（18sp 为 1.0 基准）；底部留白 → `sub-pos`（= 100 − 底部留白百分比）；颜色/底框/字体 → `sub-ass-style-overrides`（`&HAABBGGRR`，alpha **取反**；`BorderStyle=3` 保底框）。<br>⚠️ 该行早期写的是 `sub-font-size` / `sub-color` / `sub-back-color` / `sub-border-color` / `sub-border-size`——这五个名字在本仓 `libmpv.so` 里**逐字 0 命中**（裁剪构建），下发只会拿到 `-5 MPV_ERROR_OPTION_NOT_FOUND` 并被旧版 `setString` 吞掉，**当时这条功能实际是不生效的**，现已改掉。另：ASS 的 `BorderStyle` 无法同时表达「描边 + 底框」，故与 exo 的 `EDGE_TYPE_OUTLINE` 不等价（见 `MPV-EXO-PARITY.md` 3.2.5） |
| `MpvPlaybackEngine.setSubtitleEnabled` | `sub-visibility yes/no`。**比 exo 更轻**：exo 侧开关要重建 `MediaItem`（重新加载），mpv 只切一个属性，不打断播放 |
| `PlayerViewModel` 字幕分支 | `downloadAndLoadSubtitle` / `loadLocalSubtitle` / `setExternalSubtitleEnabled` / `adjustExternalSubtitleOffset` / `resetExternalSubtitleOffset` 各加 `mpvEngine?.takeIf { isMpvActive() }` 分支；偏移仍写 `SubtitleTimeShiftStore`（与 exo 同一个 store），切换内核后由 `restoreSubtitleToMpv` 重新下发 |

### 验证结果

- **实测（静态）**：把 exo 的字幕代码路径与 mpv 的逐一对照，`docs/MPV-EXO-PARITY.md` §3 的
  12 条里 11 条 ✅、1 条 ⚠️（开关语义更优）。**在线字幕的搜索/下载/本地库三段完全未改动**
  （同一份 `SubtitleRepository` / `LocalSubtitleStore`），只有"交给播放器"这一步换了实现。
- **实测（静态）**：`MpvSubtitleBridge` 的三个分支（localFile / file:// / content://）
  都走查过；`openInputStream` 返回 null、复制抛异常、目标文件 0 字节三种失败都有处理且返回 null，
  上层写 `errorMessage`（不静默）。
- **NOT RUN**：未真机验证字幕渲染。

---

## 阶段 5 · UI 双分支 + 双播放器共存与切换

### 目标

1. mpv 模式下 UI 与 exo **完全一致**（同一套控件、同一份观感）；
2. 两个内核可随时切换，**进度 / 播放暂停 / 倍速 / 字幕及其偏移 / 音轨 / 画面比例全部保留**；
3. 切换不了的情况必须**说清楚为什么**，不能静默降级。

### 改动点

#### 5.1 输出面双分支（`PlayerScreen.kt`）

```kotlin
if (isMpv) {
    AndroidView(factory = { MpvVideoView(it).apply { if (uiState.mpvReady) attach() } },
                update = { if (uiState.mpvReady) it.attach() else it.detach() },
                onRelease = { it.detach() })
} else if (vrMode.isVr) {
    VrSphericalPlayerView(...)      // 既有 VR 路径，零改动
} else {
    AndroidView(factory = { PlayerView(it).apply { ... } })   // 既有 exo 路径，零改动
}
```

#### 5.2 播放事实镜像（让两套内核共用同一份控件代码）

exo 侧原本直接读 `player.currentPosition` / `player.duration` / `player.isPlaying`。
mpv 模式下 `uiState.player` 恒为 null，因此加了一个**镜像轮询**，把
`PlaybackSnapshot` 投影到**同一批 Compose 状态变量**上：

```kotlin
LaunchedEffect(isMpv) {
    if (!isMpv) return@LaunchedEffect
    while (true) {
        val snapshot = viewModel.uiState.value.mpvSnapshot
        // 时长：mpv duration 为 0 时回落到缓存时长（部分网盘直链拿不到总时长）
        // positionMs / bufferedMs / isPlaying 同源投影
        delay(if (durationMs <= 0L) 200L else 500L)
    }
}
```

**这是"UI 观感一致"的关键**：进度条、时间文本、播放态图标、控件自动隐藏逻辑全都读这几个
变量，两种内核下走的是同一段渲染代码，不存在"两套观感"。

#### 5.3 切换决策（`EngineSwitchPlanner.kt`，纯函数）

`decide(from, to, current, targetAvailability, vrActive, subtitleName, offsetMs, audioHandoffHint)`
返回 `Allowed(handoff)` 或 `Rejected(reason)`：

| 情况 | 结果 |
|---|---|
| `from == to` | `Rejected("当前已经在使用 …")` |
| 目标不可用（so 缺失 / stub） | `Rejected(<探测到的具体原因>)` |
| `vrActive && to == MPV` | `Rejected("VR 球面播放仅 ExoPlayer 支持，请先切回 2D 再切换内核")` |
| 其它 | `Allowed(Handoff(positionMs, wasPlaying, speed, subtitleName, subtitleOffsetMs, audioHandoffHint, from, to))` |

归一化（都在决策器里做，便于单测）：负进度 → 0；非正倍速 → 1；
`wasPlaying = isPlaying || playWhenReady`（覆盖"播完暂停"与"用户暂停"两种来源）。

#### 5.4 切换执行（`PlayerViewModel.switchEngine`）

顺序与理由：

1. 读当前 `from` / `playbackRequest` / `currentPlaybackFacts()` / 目标可用性 → 决策；
   被拒绝时写 `engineSwitchNotice` 直接返回（**不进错误槽**，见 5.6）。
2. **先停旧内核**（`mpvEngine?.pause()` + `player?.pause()`）：两套解码器同时出画面既会
   音频叠放，也会抢同一个 MediaCodec 实例。
3. 建新内核并搬运状态：`startMpvSession(..., startPositionMs = handoff.positionMs,
   autoPlay = handoff.wasPlaying)` → `setSpeed` → `restoreSubtitleToMpv`（含 offset）→
   音轨按"第几条"重选 → 画面比例 → `applyResizeMode`。
4. 成功才把 `engineKind` 切成目标值并清 `engineSwitchNotice`；失败则 `onFailure` 保留原内核，
   写 `"切换到 X 失败：<原因>"` 并埋点 `player.engine.switchFailed`。
5. 全流程受 `engineSwitchJob?.isActive` 保护，**重复点击不会并发建两个实例**。

#### 5.5 切换入口（两处，互不依赖）

| 位置 | 形态 | 说明 |
|---|---|---|
| 播放器内 | `EngineSwitchChip` 浮层（显示"内核 X → Y"，切换中显示进度圈） | 做成独立浮层而非塞进 `PlayerOverlay`：overlay 是两种内核共用的，塞进去会让"当前内核"出现两条写入路径；独立浮层只读 `current`、只写 `onSwitch`，单向数据流 |
| 设置页 | 「播放器内核」默认值选项 | 持久化在 `AppSettingsRepository.getDefaultPlayerEngine()` / `setDefaultPlayerEngine()` |

#### 5.6 切换提示与错误分离

新增 `EngineNoticeBanner`（`engineSwitchNotice`）与既有的 `errorMessage` 红字槽分开：
"mpv 不可用/VR 下不能切"不是播放错误，复用错误槽会让用户以为播放已中断。

#### 5.7 设置项

`AppSettingsRepository` +32 行：`getDefaultPlayerEngine()` / `setDefaultPlayerEngine()` /
`isMpvDefaultEngine()` / `isMpvHardwareDecodeEnabled()` / `setMpvHardwareDecodeEnabled()`；
`SettingsScreen` +28 / `SettingsViewModel` +22 渲染这两个开关，并在 MPV 不可用时
显示不可选 + 原因。

### 验证结果

- **实测（单测 8/8 通过）**：`app/src/test/.../playback/engine/EngineSwitchPlannerTest.kt`
  在纯 JVM 上真实运行，覆盖 8 条：
  `目标内核与当前相同时拒绝并说明原因`、`目标内核不可用时拒绝并透传探测原因`、
  `VR 模式下切到 mpv 被拒绝`、`从 mpv 切回 exo 在 VR 模式下允许`、
  `允许切换时进度倍速字幕偏移与音轨全部搬运`、`暂停状态下切换后仍然暂停`、
  `负进度与非正倍速被归一到安全值`、`目标引擎可用但 reason 为空时不再额外编造理由`。

  ```
  JUnit version 4.13.2
  ........
  OK (8 tests)
  ```

- **实测（静态）**：`PlayerScreen.kt` 中 `isMpv` 为真时 `PlayerView` 分支不可达，
  `uiState.player` 为 null 时既有代码的 `player?.xxx` 全部有 null 兜底（逐一 grep 确认）。
- **NOT RUN**：真机上"切换是否流畅"无法验证（无设备）。可验证的是逻辑层面——
  切换路径上不存在重复建实例、不存在同时播两路、失败可回滚，这三点由代码结构与单测保证。

---

## 阶段 6 · 全量回归、文档与交付

### 目标

在无 Android SDK 的机器上，尽可能把"能在本机验证的部分"真正跑一遍，
而不是靠人眼读代码；然后产出文档与源码包。

### 改动点（验证设施 + 文档）

| 产物 | 说明 |
|---|---|
| `work/compile-pure-jvm.sh`（既有，被复用） | 只收不依赖 Android 的"逻辑包"，用 kotlinc 真实编译 + JUnit 真实运行 |
| `docs/MPV-EXO-PARITY.md` | exo 功能清单 × mpv 对齐情况，54 条，逐条给实现位置与结论 |
| `docs/MPV-PORT-STAGES.md` | 本文件 |
| `app/src/main/jniLibs/README.md` | 二进制放置说明与三种产出方式 |

同时修了一个真实的工程问题：为了让切换决策能在纯 JVM 上被测试，把
`PlaybackEngineKind / PlaybackSnapshot / EngineAvailability / VideoResizeMode`
从 `PlaybackEngine.kt`（import 了 `android.content.Context`）拆到独立的
`EngineTypes.kt`（零 Android 依赖），并把 `EngineSwitchPlanner.decide` 的
`vrMode: VrMode` 参数改成 `vrActive: Boolean`（`VrMode` 会传递性引入 `androidx.media3.common.C`）。
这是"为了可测"而非"为了好看"的拆分，且不改变任何对外行为。

### 验证结果

| 验证项 | 命令 | 结果 |
|---|---|---|
| 纯 JVM 子集编译 | `bash work/compile-pure-jvm.sh` | **实测通过**：42 个主源文件，`kotlinc` 退出码 0 |
| 单元测试（全量） | 同上（脚本内 JUnitCore） | **实测**：`Tests run: 193, Failures: 5` |
| 单元测试（基线对照） | 对基线快照跑同一脚本 | **实测**：`Tests run: 185, Failures: 5` |
| 切换决策单测 | 单独跑 `EngineSwitchPlannerTest` | **实测**：`OK (8 tests)` |
| 依赖符号审计 | `bash work/audit-all-symbols.sh` | **实测失效**：见下 |

**关于那 5 个失败**：全部集中在 `MovieNumberExtractorTest` / `MovieNumberUtilsTest`，
断言的是番号数字前缀（`300MIUM-111` 是否保留 `300`），与本任务零交集；基线同样是这 5 个失败。
用例数 185 → 193 的增量正好是新增的 `EngineSwitchPlannerTest` 的 8 条，**无回归**。

**关于符号审计脚本**：`work/audit-all-symbols.sh` 把全部 206 个 `.kt` 一次性丢给 kotlinc，
再 grep `unresolved reference: 'x'`。实测输出 `0 个`是**假阴性**——kotlinc 的真实诊断格式是
`unresolved reference 'android'.`（无冒号），且 Android 依赖全缺时几乎所有符号都会报，
噪音淹没真错误。因此本阶段**不依赖该脚本**，改用：
(a) Tier-1 纯 JVM 编译（真编译）；
(b) 对改动文件做符号闭合性人工核对（新增符号是否都有定义、引用是否有实现）；
(c) 单测覆盖决策逻辑。

**NOT RUN（必须在有环境的机器上补）**：
- `./gradlew :app:assembleDebug`（本机无 Android SDK / Gradle）
- `libplayer.so` 编译（本机无 NDK）
- `libmpv.so` 构建（上游 buildscripts 不支持 Windows）
- 真机播放与切换

---

## 附：路线文档 Phase 0–5 的对应关系

路线文档描述的是 **libmpv 本体的构建与补丁链**（Yaozhi 定制 FFmpeg、补丁重放、Lua/UI 层、
高级能力），产物是 `libmpv.so`；本次交付的是该产物**在宿主 App 里的接入与双内核共存**。
逐阶段对应表见 `docs/MPV-EXO-PARITY.md` §9。

一句话概括：**路线文档的 Phase 0–3 由 `scripts/fetch-libmpv.sh` + CI 承担（产物是 so），
Phase 4 的 UI 层本工程不使用（改由宿主 Compose 承担），Phase 5 的高级能力随 so 带入、
解码不了时回落 exo。**