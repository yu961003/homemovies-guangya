# 交接说明（脱敏版）

本包是 **HomeMovie（LocalMovieLibrary）** 工程的脱敏源码树，供外部协作方继续迭代。

## 1. 包里有什么

```text
416 个文件 / 31 MB
├── app/                     Android 应用模块（Kotlin + Compose）
│   ├── src/main/java/       源码，含 cloud115 / cloudguangya / playback / scraper / subtitle / ui
│   ├── src/main/jniLibs/    9 个预编译 .so（arm64-v8a，含 libmpv.so）
│   ├── src/main/assets/     mpv CA 包 + 14 个 Anime4K GLSL
│   ├── src/test/            单元测试（MpvHttpProxyTest 等）
│   ├── schemas/             Room schema（17 / 21 / 22）
│   └── build.gradle.kts
├── gradle/ + gradlew(.bat)  Gradle 8.12.1 wrapper
├── scripts/                 原生库装配与校验脚本
└── .github/workflows/       Android CI
```

## 2. 怎么开始

```bash
# 前置：JDK 17、Android SDK（platform 35 / build-tools）、NDK 27.0.12077973、CMake 3.22.1
echo "sdk.dir=/你的/android/sdk" > local.properties     # 本包不含此文件
./gradlew :app:assembleDebug -PmpvAbis=arm64-v8a
```

已验证：本包在干净状态下单独构建 **BUILD SUCCESSFUL**，产出 APK 55,472,859 B，
内含 11 个 `lib/` 条目、`assets/mpv/cacert.pem`(302,152 B) 与 14 个 Anime4K GLSL(513,938 B)。

Windows 上构建若报 `debug.keystore.lock` 相关 `AccessDeniedException`，把
`ANDROID_USER_HOME` 指到一个可写目录即可（如 `<项目>/.android-home`）。

## 3. 脱敏做了什么

| 处理 | 说明 |
|---|---|
| 移除 `.git/` | 不携带任何提交历史、作者身份与远端地址；本包是**工作树快照**不是克隆 |
| 移除 `docs/` | 原内部排查笔记与取证文件（涉及本机环境、本项目内部过程），共 19 个文件 |
| 移除 `local.properties` | 机器相关（SDK 路径），原本就被 `.gitignore` 排除 |
| 移除构建产物 | `build/`、`.gradle/`、`.kotlin/`、`.cxx/`、`app/schemas/build/` |
| 重写根 README | 原 README 引用了已移除的 `docs/`，两个根文档按当前工程实测事实重写 |
| 校验 | 全文扫描 9 类模式（本地绝对路径 / 所有者名 / 仓库 URL / 私钥 / GitHub token / AWS key / 明文 Bearer / 115 Cookie 字段 / JWT）：**0 命中**；`.so` 与 assets 等 22 个二进制同样 0 命中 |

**包里从来就没有的东西**（不是被删掉，是原工程本就没有）：账号密码、Cookie 值、
access/refresh token、手机号、API key、签名私钥、`.keystore`。所有登录态都是运行时
写入 `EncryptedSharedPreferences`。代码中出现的 `UID=` / `CID=` 是**解析前缀字符串**，不含值；
`P115Cipher.kt` 里的 64 字节常量是 115 官方客户端的公开协议固定值（多份开源实现同款），不是任何人的密钥。

## 4. 交接后的注意点

- **CI**：`.github/workflows/build.yml` 在 push 时自动跑 `assembleDebug` + APK 内容断言 +
  `MpvHttpProxyTest`。把本包 `git init` 后推到自己的仓库即可复用，无需改配置。
- **签名**：仓库不含签名配置，debug 包由本地 SDK 自动签名；要发 release 需自行配置 keystore。
- **真机未验证项**：https 直链播放在 CI 无机器的前提下无法实证，`docs/` 里原有的
  logcat 判读表已随目录移除，接手后需在设备上复验。
- **`app/schemas/`**：`exportSchema = true`，任何表结构改动都会新增 schema 文件，
  手写迁移在 `data/AppContainer.kt`，DDL 必须与生成的 schema 逐字一致。

## 5. 已实现的 4.0.0 蓝本能力

| 能力 | 位置 |
|---|---|
| Anime4K 超分（14 GLSL + 六档链 + 三档质量） | `playback/engine/MpvAnime4k.kt` |
| exo 侧 3D LUT（复用 media3 `SingleColorLut`，HDR 降级） | `playback/engine/ExoLutEffect.kt` |
| 字幕库周期增量重扫（WorkManager，12h，只写差异行） | `subtitle/SubtitleLibraryRescan{Worker,Scheduler}.kt` |
| 外挂字幕字体族 / 文字颜色 | `AppSettingsRepository` + 设置页 + 播放器样式 |
| STRM 再生成独立任务表 | `data/local/StrmRegenerationTask*.kt` + `MIGRATION_21_22` |
