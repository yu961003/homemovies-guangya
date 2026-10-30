# 光鸭云盘接入 · 实现说明（基于 HomeMovie / LocalMovieLibrary）

本目录是 **HomeMovie（LocalMovieLibrary）** 的整合副本，已接入**光鸭云盘**支持，同时**完整保留 115 网盘的登录 + STRM 生成 + 播放**。

> ✅ **可直接编译，CI 已跑绿**：仓库现在放的是**工程本体**（源码树 + `gradlew` + 预编译 `jniLibs`），
> 不再放交付 zip。任何 push 都会触发 `.github/workflows/build.yml`：
> `assembleDebug -PmpvAbis=arm64-v8a` → 断言 APK 内含 `libmpv.so` 与 8 个兄弟库 → 断言
> `assets/mpv/cacert.pem` 入包 → 跑 `MpvHttpProxyTest` → 上传 artifact。
>
> 本机也可以直接 `./gradlew :app:assembleDebug -PmpvAbis=arm64-v8a`（需 JDK 17 + NDK 27.0.12077973）。
>
> 📱 **装到手机上要有 APK**：Actions → `Android CI` → 对应 run → Artifacts → `apk-debug-<sha>`。
> ⚠️ 真机播放**尚未实证**（CI 无 Android 设备）：`loading_failed(-13, reason=4)` 的两条修复
> （回环代理 + CA 包）是否解决你那条 https 直链，需要用本包在设备上复验，见
> `docs/LIBMPV-TLS-CA-FIX-20261029.md` §5.3 的 logcat 判读表。

> 🖼️ **mpv 黑屏已修**（2026-10-30）：根因是 `loadfile` 抢在 `wid` 之前下发，android VO 初始化时读到
> `WinID=0` 直接 `Missing surface pointer` 失败 → 只剩声音、画面全黑（进度/时长正常）。现在装载前会等
> 渲染面就绪，并有 `vo-configured` + mpv 日志双判据的自检把原因写进错误条。同一提交还把内核切换按钮
> 收进顶栏（`MPV | EXO` 小药丸，不再挡字幕按钮），并补上 **hw+ / hw / sw** 三档硬解（播放模式 → 解码（MPV））。
> 详见 `docs/MPV-BLACKSCREEN-HWDEC-20261030.md`。
>
> 🔍 **刮削失败复盘**（2026-10-30）：`SNIS-205` 这类「反复失败」分两层看——**该号在上游确实不存在**
> （DMM GraphQL / S1 官网 / AVBase / TheJavDB 四个独立源交叉验证全为 0/404，换 `SNIS225`、`SSIS-001` 都正常），
> 而**失败体验被工程缺陷放大**：桃链 90s 预算被单个源吃光、DMM2 异常丢 cause 归成 `Unknown`（冷却 0s 导致同片重打）、
> 官网档四个来源其实是同一台 `s1s1s1.com`、S1 首页 `<form>` 为 0 使「回首页发现搜索框」恒失败，
> 叠加国内 DNS 对该域投毒返回 `2001::/32` 黑洞地址。已于同一批提交修掉 7 处（含 S1 专用抽取档 `WorkPageTable`）。
> 完整证据链与四类根因占比见 `docs/SCRAPE-FAILURE-ANALYSIS-20261030.md`。

> 📦 **包名变更**：`com.example.localmovielibrary` → **`com.example.localmovielibrarz`**（全部 Kotlin package/import、namespace、applicationId、目录结构均已同步，含 test 目录）。

## 1. 改动清单

### 新增文件（光鸭平行供应商包，不影响 115）
| 文件 | 职责 |
|---|---|
| `cloudguangya/GuangyaConstants.kt` | 端点、client_id、MediaType 常量 |
| `cloudguangya/GuangyaTokenStore.kt` | EncryptedSharedPreferences 加密存令牌（顺手修了 115 明文存 Cookie） |
| `cloudguangya/GuangyaTokenProvider.kt` | 取/刷令牌，刷新带 Mutex 并发锁 |
| `cloudguangya/GuangyaLoginClient.kt` | 短信验证码登录（init→send→verify→signin 四步）+ 刷新（**顶层 JSON 解析**，已修 P0-1） |
| `cloudguangya/GuangyaApiClient.kt` | 列目录 / 直链（信封解析 + **401 刷新重试**，已修 P1-4/P1-7） |
| `cloudguangya/GuangyaRateLimiter.kt` | 读350/写1200ms 节流（**Mutex 版**，已修 P1-1） |
| `cloudguangya/GuangyaFileItem.kt` | 云文件模型（字段兜底，P1-3） |
| `cloudguangya/GuangyaBrowserRepository.kt` | 浏览编排（根目录 `parentId=""`） |
| `data/repository/GuangyaStrmRepository.kt` | 生成 STRM，内容写 `${baseUrl}/guangya_res/$fileId/$name` |
| `data/repository/GuangyaDirectLinkResolver.kt` | fileId → signedURL 解析 + 缓存 |
| `data/local/GuangyaDirectLinkDao.kt` + `GuangyaDirectLinkEntity.kt` | 直链缓存表 |
| `playback/GuangyaDirectLinkExpiryParser.kt` | 从 signedURL 解析过期（P1-6） |
| `ui/guangya/GuangyaLoginViewModel.kt` | 短信登录 ViewModel（手机号/验证码状态机 + `loggedIn` 持久态 + `factory`） |
| `ui/guangya/GuangyaLoginPanel.kt` | 光鸭登录面板，**已接入**「设置 → 网盘设置」页（115 面板下方） |
| `cloudguangya/GuangyaMagnetExtractor.kt` | 磁力链提取（magnet / 40hex / 32base32 / .torrent URL / ed2k）+ `magnetHash` 稳定哈希（去重记忆用） |
| `cloudguangya/GuangyaOfflineMemory.kt` | **离线下载记忆系统**：已提交去重、永久失败黑名单、每日限额（默认 1000 可配）、目标文件夹、导出/导入 JSON 备份；内存缓存 + 3 秒批量落盘 |
| `cloudguangya/GuangyaOfflineSubmitter.kt` | **离线下载并发引擎**：3 路并发 + 429/限频自适应退避 + 临时失败指数重试（500ms→1s→2s）+ 暂停/继续 + 日志缓冲 |
| `ui/guangya/GuangyaBrowserViewModel.kt` + `GuangyaBrowserScreen.kt` | 浏览 / 添加 UI，**已接入导航图**（`Route.GuangyaBrowser`），入口在设置页光鸭面板下方 |

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
- 需要：Android SDK（platform-35 + build-tools;35.0.0）、JDK 17、AGP 8.10.0、Kotlin 2.3.20、KSP 2.3.11。
- 在项目根执行：`./gradlew assembleDebug`
- 产物：`app/build/outputs/apk/debug/app-debug.apk`

## 3. 出 APK（GitHub Actions，推荐）
工作流在**仓库根**：`.github/workflows/build.yml`（不在本 zip 内 —— 它先把仓库根目录的 zip
解压再构建，CI 配置本身属于仓库，不属于源码包）。流程：
1. 把本 zip 上传到仓库根（替换同名的旧 zip）。
2. 仓库 **Actions → Android CI** → **Run workflow**（或直接 push 触发）。
3. 运行完成后在 **Artifacts → app-debug-apk** 下载 APK。
> 该工作流会自动安装 JDK 17、Android SDK（platform-35、build-tools;35.0.0）并接受许可证。
> **注意**：`android-actions/setup-android@v3` 的默认 `packages` 是 `'tools platform-tools'`，
> 其中 legacy `tools` 包自 cmdline-tools 16.0 起已从 SDK 仓库移除，不覆盖就会报
> `Failed to find package 'tools'` 并 exit 1。修法是给该步骤补
> `with: packages: 'platform-tools platforms;android-35 build-tools;35.0.0'`；
> 完整排查、验收与替代写法见 `docs/CI-SETUP.md`。

## 4. 登录入口（已接入）
光鸭登录面板**已接入** App 内，入口位置：

> **设置 → 网盘设置 → 「光鸭云盘」分区**（位于「115 Cookie」面板正下方）

> ⚠️ **光鸭没有二维码扫码登录**（无 passport 二维码体系，115 的扫码方式无法复刻）。光鸭官方 App/SDK 的唯一登录方式是**手机号 + 短信验证码**，因此本面板做成和 115 一样的「App 内完成」卡片，内容是两阶段短信登录，全程不跳浏览器。

面板流程：
- **未登录**：输入手机号 → 点「获取验证码」→ 光鸭下发短信（自动完成 `captcha/init` 人机验证初始化 + `verification` 发码）。
- **等验证码**：输入 6 位短信验证码 → 点「登录」（`verify` 校验 + `signin` 换 access/refresh token）→ 令牌加密入库并标记已登录；60 秒内可重发。
- **已登录**：显示「已登录光鸭云盘」+「退出登录」。

## 5. 影片库增强（批量操作 + 已刮削分类）
### 批量操作（影片 tab）
- **入口**：影片库顶栏右侧「☑ 批量」图标按钮（平时不打扰，需要时点击进入）。
- 进入批量模式后：顶栏显示「已选 N 部」，每张影片卡片右上角出现选中圈（点击卡片切换选中、再次点图标退出），底部出现操作栏：
  - **删除影片**：删除影片记录 + 本地 STRM 文件 + 网盘记录（不删云端文件），红色按钮带确认。
  - **清除刮削数据**：清除 NFO / 海报 / 元数据，**保留 STRM**，影片恢复未刮削状态。
  - **重新刮削**：用默认来源重新刮削并整理文件。
- 支持全选 / 取消全选（按当前分类范围内的影片数）；执行时显示进度「正在… N/M」，完成后自动提示并退出批量模式。

### 已刮削分类
- 影片库分类 Tab 新增 **「已刮削」**（位于「全部」之后）：只显示刮削成功、无刮削问题的影片（`excludeScrapeIssueMovies` 之后、排除 VR）。

## 6. 浏览 / 添加（已接入）
光鸭浏览页**已接入导航图**，两个入口：

> **① 底部导航「网盘」tab**：顶部「115 网盘 / 光鸭云盘」切换胶囊与页面顶栏共用**同一渐变区域**（状态栏、背景一体化），切换与路径/排序视觉连续、不再分离。
> **② 设置 → 网盘设置 → 「浏览光鸭网盘 / 生成 STRM」按钮**：独立全屏页面进入。

浏览页功能（**操作逻辑与 115 网盘完全一致**）：
- **未登录**：显示引导提示 + 「返回」按钮（浏览需要有效 token）。
- **已登录**：进入后自动加载根目录；目录可逐级进入，顶栏显示路径面包屑，「返回」按钮回上级（栈空时返回上级页面）。
- **点视频 = 直接播放**（构造 `guangya://guangya_res/$fileId` 交给播放器，PlaybackResolver 按 route 实时解析直链）。
- **行右侧「添加」按钮**：生成 STRM → 扫描入库 → 提取番号 → **自动刮削** → 整理同步，完成后显示「已添加」，添加中显示「添加中」（复用 115 完整链路，fileId 作为记录主键）。
- **目录行「添加」按钮**：弹确认框后**递归遍历**整个文件夹，逐个视频入库并刮削。
- **目录/文件区分**：光鸭接口用 `resType==2` 表示目录（文件为 1），`GuangyaApiClient` 已按此解析（修复：此前误读不存在的 `isDir` 字段，导致目录全被当成影片）。

## 7. 离线下载（完整功能版，移植 guangya-txt-cloud-add 油猴脚本）

入口：**光鸭浏览页 →「离线下载」按钮**。基于官方 `cloudcollection/v1` 接口（resolve_res → create_task），完整保留脚本能力：

| 脚本功能 | App 实现 |
|---|---|
| txt 批量导入 | ✅ 粘贴多行文本，或 **「选本地 txt 导入」**（SAF 文件选择，自动解析填充） |
| 自动识别格式 | ✅ magnet / 40hex / 32base32 / .torrent URL / ed2k，逐行去重 |
| 🧠 记忆去重 + 断点续跑 | ✅ `GuangyaOfflineMemory`：成功提交的 hash 落盘（3 秒批量 flush + 批次结束强制 flush），下次自动跳过 |
| ❌ 失败黑名单 | ✅ 永久失败（解析/创建业务错误）入黑名单不再提交；临时失败（网络/超时/429）不记黑名单，自动重试 |
| 📅 每日限额 | ✅ 默认每天 1000 次，面板可配置（1-100000）；失败不占名额，成功满额自动停；服务端配额超限立即停 |
| ⚡ 并发提交 | ✅ 3 路并发 worker，限频自动降并发（最低 1），成功恢复 |
| 🔁 自动重试 | ✅ 临时失败重试 2 次，指数退避 500ms→1s→2s |
| 📁 文件夹选择 | ✅ 选择器支持**逐级浏览**目录树 + **全局搜索**（search_files，resType=2），目标持久化记忆 |
| ⏸ 暂停/继续 | ✅ 随时暂停，恢复后断点继续（当前请求完成后停止） |
| 💾 导出/导入记忆 | ✅ 导出 JSON 备份到剪贴板，导入粘贴恢复（含黑名单/每日计数/目标文件夹） |
| 🚀 防卡死 | ✅ 记忆内存缓存、日志缓冲批量渲染（保留最近 200 条）、解析在 IO 线程 |

任务创建到**所选文件夹**（未选时默认当前浏览目录）。云端自动跑 BT 下载，完成后文件出现在该目录，可继续浏览/添加。

## 8. 合规与风险（务必阅读）
- **client_id 冒充**：`GuangyaConstants.CLIENT_ID` 来自官方 SDK `guangyaclient` 内置值，本质是复用官方应用身份访问光鸭，可能违反光鸭 ToS；建议向光鸭申请自有 client_id 替换。
- **人机校验**：`smsInit` 已内建 `captcha_token` 初始化（`/v1/shield/captcha/init`），但极端情况下光鸭可能下发**网页验证**（验证码图片等），App 内无法完成，届时登录会失败——这是官方 SDK 也绕不过的限制（该分支当前仅报错提示）。
- **协议稳定性**：光鸭无公开稳定 API，所有端点集中于 `GuangyaConstants` / `GuangyaApiClient`，便于快速切换或降级。

## 9. 协议要点（反编译官方 SDK guangyaclient v0.0.2 + AList 驱动确认）
- 登录（短信四步，全在 `account.guangyapan.com`，**顶层 JSON**）：
  1. `POST /v1/shield/captcha/init` → `captcha_token`；body 的 `meta` **必须带 `username` / `phone_number` / `VERIFICATION_PHONE` 三键**（只传 phone_number 会报 HTTP 400 `error_code=4002` `meta.username expected`）；手机号统一 E164 格式（`+86138...`→`+86 138...`）。
  2. `POST /v1/auth/verification`（需 `x-captcha-token` 头）→ `verification_id`；若返回 `captcha_invalid`/`captcha_token expired`，自动强制刷新 captcha_token 重试一次（对齐 AList）。
  3. `POST /v1/auth/verification/verify` → `verification_token`
  4. `POST /v1/auth/signin`（需 `x-captcha-token` 头）→ `access_token` / `refresh_token`
- 刷新：`POST /v1/auth/token`（`grant_type=refresh_token` + `x-action: 401` 头）。
- 业务：`api.guangyapan.com`，**`{msg,data,error}` 信封**（`data.list` / `data.signedURL`）。
- 鉴权头严格：`x-client-id` / `x-device-id` / `x-device-sign`（`wdi10.{did}{hex}`）/ `x-client-version` 等，缺失或格式不对直接拒绝。
- 业务 API 仅 `Authorization: Bearer <token>`，无签名。
- STRM 存 fileId 不存直链；播放时实时解析 `get_res_download_url`。
