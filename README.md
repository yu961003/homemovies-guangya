# 光鸭云盘接入 · 实现说明（基于 HomeMovie / LocalMovieLibrary）

本目录是 **HomeMovie（LocalMovieLibrary）** 的整合副本，已接入**光鸭云盘**支持，同时**完整保留 115 网盘的登录 + STRM 生成 + 播放**。

> ⚠️ 本工程由 AI 在沙箱中产出，**未经过真机/本地编译验证**（沙箱无 Android SDK 且无外网到 Google Maven / Gradle）。请在 Android Studio 或 GitHub Actions 中执行一次 `./gradlew assembleDebug` 验证。

## 1. 改动清单

### 新增文件（光鸭平行供应商包，不影响 115）
| 文件 | 职责 |
|---|---|
| `cloudguangya/GuangyaConstants.kt` | 端点、client_id、MediaType 常量 |
| `cloudguangya/GuangyaTokenStore.kt` | EncryptedSharedPreferences 加密存令牌（顺手修了 115 明文存 Cookie） |
| `cloudguangya/GuangyaTokenProvider.kt` | 取/刷令牌，刷新带 Mutex 并发锁 |
| `cloudguangya/GuangyaLoginClient.kt` | 设备码登录 / 轮询 / 刷新（**顶层 JSON 解析**，已修 P0-1） |
| `cloudguangya/GuangyaApiClient.kt` | 列目录 / 直链（信封解析 + **401 刷新重试**，已修 P1-4/P1-7） |
| `cloudguangya/GuangyaRateLimiter.kt` | 读350/写1200ms 节流（**Mutex 版**，已修 P1-1） |
| `cloudguangya/GuangyaFileItem.kt` | 云文件模型（字段兜底，P1-3） |
| `cloudguangya/GuangyaBrowserRepository.kt` | 浏览编排（根目录 `parentId=""`） |
| `data/repository/GuangyaStrmRepository.kt` | 生成 STRM，内容写 `${baseUrl}/guangya_res/$fileId/$name` |
| `data/repository/GuangyaDirectLinkResolver.kt` | fileId → signedURL 解析 + 缓存 |
| `data/local/GuangyaDirectLinkDao.kt` + `GuangyaDirectLinkEntity.kt` | 直链缓存表 |
| `playback/GuangyaDirectLinkExpiryParser.kt` | 从 signedURL 解析过期（P1-6） |
| `ui/guangya/GuangyaLoginViewModel.kt` | 设备码登录 ViewModel（含 `loggedIn` 持久态 + `factory`） |
| `ui/guangya/GuangyaLoginPanel.kt` | 光鸭登录面板，**已接入**「设置 → 网盘设置」页（115 面板下方） |
| `ui/guangya/GuangyaBrowserViewModel.kt` + `GuangyaBrowserScreen.kt` | 浏览 / 生成 STRM UI（独立可组合项，未接入导航图） |

### 修改文件
| 文件 | 改动 |
|---|---|
| `playback/PickcodeExtractor.kt` | 新增 `routeOf()`，路由表加 `guangya_res`/`guangya_vod`（P0-2） |
| `playback/PlaybackResolver.kt` | 第33/49 行按 route 分流到 `GuangyaDirectLinkResolver`；新增必填构造参数（P0-2/P2-4） |
| `data/AppContainer.kt` | 装配全部光鸭实例 + `MIGRATION_15_16`（新建表） |
| `data/repository/AppSettingsRepository.kt` | 新增 `isGuangyaLoggedIn()` / `setGuangyaLoggedIn()` |
| `data/local/AppDatabase.kt` | 新增 `GuangyaDirectLinkEntity`、DAO，版本升到 16 |
| `app/build.gradle.kts` | 新增 `androidx.security:security-crypto:1.1.0-alpha06`（P1-2） |
| `ui/player/PlayerViewModel.kt` | 构造 + 工厂新增 `guangyaDirectLinkResolver` 参数 |
| `ui/LocalMovieLibraryAppRoot.kt` | 工厂调用传入 `guangyaDirectLinkResolver` |
| `ui/settings/SettingsScreen.kt` | **接入光鸭登录面板**：`CloudSettingsPage` 新增「光鸭云盘」分区，挂载 `GuangyaLoginPanel`（VM 经 `LocalMovieLibraryApp.container` 创建） |

## 2. 本地编译 / 出 APK
- 需要：Android SDK（platform-35 + build-tools;35.0.0）、JDK 17、AGP 8.7.3、Kotlin 2.0.21。
- 在项目根执行：`./gradlew assembleDebug`
- 产物：`app/build/outputs/apk/debug/app-debug.apk`

## 3. 出 APK（GitHub Actions，推荐）
本仓库已包含 `.github/workflows/build.yml`：
1. Fork / 推送到你自己的 GitHub 仓库（含本目录全部文件，保留 `.github/`）。
2. 在仓库 **Actions → Build Debug APK (光鸭云盘)** 点击 **Run workflow**。
3. 运行完成后在 **Artifacts → app-debug** 下载 APK。
> 该工作流会自动安装 JDK 17、Android SDK、platform-35、build-tools;35.0.0 并接受许可证。

## 4. 登录入口（已接入）
光鸭登录面板**已接入** App 内，入口位置：

> **设置 → 网盘设置 → 「光鸭云盘」分区**（位于「115 Cookie」面板正下方）

面板三态：
- **未登录**：显示「登录光鸭云盘」按钮 → 点击后通过设备码流程拿到 `verification_uri`，点「在浏览器打开」跳转光鸭网页端确认；后台自动轮询，确认成功后写加密令牌并标记已登录。
- **登录中**：显示验证链接 + 「取消」按钮 + 进度圈，等待网页端确认。
- **已登录**：显示「已登录光鸭云盘」+「退出登录」。

浏览 / 生成 STRM 界面（`GuangyaBrowserScreen`）目前**仍未接入导航图**，作为独立可组合项存在。如需完整浏览页，可新增一个导航目的地挂载它（ViewModel 构造传 `appContainer.guangyaBrowserRepository` / `guangyaStrmRepository` / `settingsRepository`）。播放时 `PlaybackResolver` 已按 route 自动分流到光鸭直链，无需额外接线。

## 5. 合规与风险（务必阅读）
- **client_id 冒充**：`GuangyaConstants.CLIENT_ID` 来自第三方 guangyapan 库，本质是冒充该应用身份访问光鸭，可能违反光鸭 ToS；建议向光鸭申请自有 client_id 替换。
- **人机校验**：光鸭登录可能触发 captcha / shield，设备码流程需预留该分支（当前轮询仅处理 pending/成功）。
- **协议稳定性**：光鸭无公开稳定 API，所有端点集中于 `GuangyaConstants` / `GuangyaApiClient`，便于快速切换或降级。

## 6. 协议要点（反编译 guangyapan 确认）
- 登录/刷新：`account.guangyapan.com`，**顶层 JSON**（`device_code` / `access_token`）。
- 业务：`api.guangyapan.com`，**`{msg,data,error}` 信封**（`data.list` / `data.signedURL`）。
- 鉴权：仅 `Authorization: Bearer <token>`，无签名。
- STRM 存 fileId 不存直链；播放时实时解析 `get_res_download_url`。
