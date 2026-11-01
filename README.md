# HomeMovie（LocalMovieLibrary）· 交接说明

Android 影片库客户端：本地影片管理 + 115 网盘 / 光鸭云盘接入 + ExoPlayer / libmpv 双播放内核
+ 多源刮削 + 在线字幕 + 3D LUT 调色。

```text
namespace / applicationId : com.example.localmovielibrarz
minSdk 26 · targetSdk 35 · compileSdk 35 · Kotlin 17 · AGP + Gradle 8.12.1
versionCode 22 · versionName 3.3（v2 改造版）
```

> **v2 改造说明见根目录 [`CHANGES-v2.md`](CHANGES-v2.md)**：mpvRx 能力清单与接入点、LUT 性能与调色能力、
> MediaPipe 人脸/肤色分析、代码审查与重构、MD3 界面重设计、脱敏校验结果。

## 1. 直接构建

```bash
# 需要：JDK 17、Android SDK（platform 35、build-tools）、NDK 27.0.12077973、CMake 3.22.1
echo "sdk.dir=/path/to/android-sdk" > local.properties
./gradlew :app:assembleDebug -PmpvAbis=arm64-v8a
```

`app/src/main/jniLibs/arm64-v8a/` 已带 9 个预编译 `.so`（含 `libmpv.so`），
`verifyMpvNativeLibs` 会在 `preBuild` 校验它们齐全；缺库时构建**直接失败并列出缺哪个 ABI**，
不会静默产出「没有 mpv」的包。要从源码重编原生库见 `scripts/assemble-libmpv-jnilibs.py`。

## 2. 模块地图

| 目录 | 职责 |
|---|---|
| `cloud115/` | 115 网盘：Cookie 登录 / 扫码登录 / 目录遍历 / 直链 / STRM 生成 |
| `cloudguangya/` | 光鸭云盘：手机号+短信登录、令牌加密存储、目录与下载直链 |
| `playback/` | 双播放内核抽象：`PlaybackEngine` 接口、Exo / mpv 两套适配器、内核切换规划 |
| `playback/engine/MpvAnime4k.kt` | Anime4K 超分：14 个 GLSL 的资产解包（SHA-256 增量）+ 六档着色器链 |
| `playback/engine/ExoLutEffect.kt` | exo 侧 3D LUT：复用 media3 `SingleColorLut`，HDR 自动降级透传 |
| `playback/lut/` | LUT 生成与匹配：`.cube` 解析/写出、参数预测、肤色统计、自动调参 |
| `scraper/` | 多源刮削：JavDB / DMM / S1 / MGStage / AVBase 等，含预算与降级 |
| `subtitle/` | 在线字幕：搜索、下载、本地字幕库索引与周期增量重扫（WorkManager） |
| `data/local/` | Room：13 张表，schema 版本 22，手写迁移集中在 `data/AppContainer.kt` |
| `ui/` | Compose：15 条路由（`ui/LocalMovieLibraryAppRoot.kt` 内的 `Route` 常量 + `NavHost` 注册） |
| `playback/face/` | v2：MediaPipe 人脸区域（网格 → 检测框 → 整帧三级降级）+ exo 原片抽帧 |
| `playback/lut/LutGradeKernel.kt` | v2：调色内核（参考链 + Oklab 创意层），LUT 生成与自动分析共用 |
| `playback/engine/MpvEnhancements.kt` | v2：HDR 色调映射（hdr-toys）/ 去色带 / 省电渲染 / 自定义 mpv 选项 |
| `playback/engine/ThermalGuard.kt` | v2：热余量监测、Anime4K 4K 守卫与热降级、自动黑边检测 |
| `ui/components/` | v2：MD3 组件集（分组卡片 / 列表项 / 开关 / 芯片 / 滑杆 / 选择卡片） |
| `ui/player/PlayerControlsV2.kt` | v2：播放器控件、分页面板、统计浮层；手势在 `PlayerGestureLayer.kt` |

## 3. 构建期硬约束（踩过坑，别再踩）

| 约束 | 为什么 |
|---|---|
| `glsl-shaders` / `target-lut` 必须在 `mpv_initialize()` **之前**下发 | 运行期再设不会重建渲染管线，等于没生效 |
| `vo=gpu-next` 才注册 `--target-lut` | `vo=gpu` 下该选项无效 |
| exo 的 LUT 遇到 HDR 必须降级透传 | media3 `SingleColorLut` 在 `useHdr=true` 时 `checkState` 抛异常，会中断整条渲染管线（黑屏） |
| `mpvAbis` 默认探测 `jniLibs` 下已存在的目录 | 不传 `-PmpvAbis` 时按实际有的 ABI 打包，避免产出空 `lib/` |
| Room `exportSchema = true`，schema 入库 `app/schemas/` | 手写迁移的 DDL 必须与生成的 schema 逐字一致，否则运行时崩溃 |
| `ksp.incremental=false` | Room 增量处理在本工程会残留旧生成源，`purgeStaleRoomGeneratedSources` 兜底 |

## 4. CI

`.github/workflows/build.yml`（Android CI）：源码树自检 → 装 NDK/CMake → 原生库闭包自检 →
`assembleDebug -PmpvAbis=arm64-v8a -x lint` → 断言 APK 内含 `libmpv.so` 与 8 个兄弟库 →
断言 `assets/mpv/cacert.pem` 入包 → 跑 `MpvHttpProxyTest` → 上传 artifact。
push 到任意分支即触发，40 分钟超时，`concurrency` 组按 ref 取消旧运行。

## 5. 本包不含什么

- 没有任何账号凭据：登录态全部在运行时写入 `EncryptedSharedPreferences`。
- 不含签名私钥（`*.jks` 被 `.gitignore` 排除），debug 签名由本地 SDK 生成。
- 不含 `local.properties`（机器相关，需自行创建）。
- 不含历史排查笔记（原 `docs/` 目录已移除，内容涉及本机环境与本项目内部过程）。
