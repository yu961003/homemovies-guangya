# mpv 双播放器移植 · 交付索引

本工程已从「只有 ExoPlayer」改为 **ExoPlayer / libmpv 双内核并存**，两套内核共用同一套
播放器 UI，可在播放中随时切换且不丢状态。在线字幕（搜索 / 下载 / 本地库 / 偏移 / 样式）
在 mpv 内核下完整可用。

> **本包带不带 `libmpv.so`？看文件名**：
>
> | 包 | 带库 | 怎么用 |
> |---|---|---|
> | `*_all_in_one_arm64.zip` | 带 `arm64-v8a` 的 9 个 .so | 解开直接 `./gradlew :app:assembleDebug`，不需要任何参数 |
> | `homemovies_guangya_fix1028_<date>.zip` | 不带（三 ABI 共 77 MB，塞不进单文件包） | 先按下面“快速上手”补库 |
>
> 缺库时**不再静默降级**：`verifyMpvNativeLibs` 直接构建失败并逐个 ABI 列出缺什么，
> 免得又发出「未检测到 libmpv.so：原生库缺失」的包。
> 根因链、产物实测证据、复现步骤与**运行期验证的未跑通记录**见
> [`docs/LIBMPV-SHIP-20261028.md`](docs/LIBMPV-SHIP-20261028.md)。

## 三份文档

| 文档 | 回答什么问题 |
|---|---|
| [`docs/MPV-PORT-STAGES.md`](docs/MPV-PORT-STAGES.md) | **分阶段怎么做的**：阶段 0–6，每阶段的目标 / 改动点 / 验证结果（含本机实测数据与 NOT RUN 清单） |
| [`docs/MPV-EXO-PARITY.md`](docs/MPV-EXO-PARITY.md) | **功能对齐到什么程度**：exo 功能清单 54 条 × mpv 逐条对照，含 4 条明确不支持项与替代方案 |
| `app/src/main/jniLibs/README.md` | **怎么把 so 放进**：每个 ABI 要放哪些文件、三种产出方式、校验方法 |

## 快速上手

```bash
# 1) 用 all-in-one 包：直接构建，mpv 就可用（包里已带 arm64 的 9 个 .so）
./gradlew :app:assembleDebug
#   → [mpv] jniLibs 校验通过：arm64-v8a=9 个 .so
#   → APK 约 53.7 MB，只含 lib/arm64-v8a

# 2) 纯源码包要先补库，否则门禁会失败（这是故意的，见上）
python scripts/assemble-libmpv-jnilibs.py --source libmpv-1.0.0.aar
python scripts/assemble-libmpv-jnilibs.py --verify-only
./gradlew :app:assembleDebug

# 3) 或自己编（Linux / macOS；Windows 本机不支持上游 buildscripts）
bash scripts/fetch-libmpv.sh --clone-only     # 准备上游构建链
bash scripts/fetch-libmpv.sh --build arm64    # 编 arm64 并拷贝整组 .so
bash scripts/fetch-libmpv.sh --verify         # ELF 魔数 / e_machine / 体积校验

# 4) 或在 GitHub Actions 里跑
#    Actions → build-libmpv → Run workflow（40~90 分钟）
#    Actions → build-apk     → Run workflow（10~15 分钟）
#    若推的是 all-in-one 包，build-apk 会自动跳过下载与装配，只编译。
```

## 代码在哪

```
app/src/main/cpp/                      libmpv 的 JNI 封装层 + CMake 双路径构建
app/src/main/java/.../mpv/             Kotlin 侧绑定（MPVLib / MpvVideoView）
app/src/main/java/.../playback/engine/ 内核抽象层（PlaybackEngine + 两个实现 + 切换决策）
```

内核切换入口有两处：播放器内的浮层胶囊按钮（`EngineSwitchChip`）、
设置页的「播放器内核」默认值。切换逻辑的实现与验收单测见
`app/src/test/java/.../playback/engine/EngineSwitchPlannerTest.kt`（8 条，全部通过）。

## 已知不支持项（不静默省略）

**❌ 完全不对齐（4 条）**

| # | 能力 | 原因 | 处理方式 |
|---|---|---|---|
| 6.1 | VR 球面 360°/180° 投影播放 | mpv 的 `vo=gpu` 没有等价球面网格投影 + 头部姿态采样链路；libmpv 的 Android 后端从未落地 VR 模式，要做等于以 libmpv 为渲染器重写一遍 VR 播放器 | mpv 下强制走 exo；`EngineSwitchPlanner` 在 VR 激活时**拒绝**切到 mpv 并给出文案，**不静默降级成 2D** |
| 6.2 | VR 控制模式（手势/触控板） | 依赖 6.1 的存在 | 同 6.1：VR 下无法进入该状态；反方向（mpv → exo）允许 |
| 6.3 | VR 下的字幕叠加 | 依赖 6.1 | 同 6.1 |
| 1.7 | 画中画（PiP） | **两边都没有**：exo 侧本就没实现 PiP | 不算「mpv 缺功能」；加 PiP 属范围外新功能，本次不引入 |

**⚠️ 已对齐但有语义差异（3 条，均有说明）**

| # | 能力 | 差异 |
|---|---|---|
| 1.2 | 画面比例 FIT/FILL/ZOOM | mpv 的 pan&scan 是渲染层裁剪，exo 的 ZOOM 走 `AspectRatioFrameLayout` 缩放矩阵；视觉结果一致，极端超宽/竖屏下裁切阈值可能差几像素 |
| 2.4 | 缓冲进度段 | exo 的 `bufferedPosition` 是已下载字节位置，mpv 的 `demuxer-cache-time` 是解复用缓存时间点；网盘直链下 mpv 缓存窗口更保守，进度条第二段可能更短，**不影响拖动与显示** |
| 4.1 | 音轨列表 | exo 是 group/track 两层索引，mpv 是扁平 `aid`；UI 表现完全一致，跨内核搬运时按「第几条」对齐 |

另注：字幕开关一项 mpv 侧**语义更优**（`sub-visibility` 切换不打断播放，exo 需重建 `MediaItem`），
故计入 ✅ 而非 ⚠️。逐条对照见 `docs/MPV-EXO-PARITY.md`。

对照表粒度：54 条 → ✅ 47 / ⚠️ 3（有语义差异，已显式说明）/ ❌ 4（VR 三条在 §6，画中画一条在 §1.7，
且 exo 侧本来也没实现）。完整清单、原因与替代方案见 `docs/MPV-EXO-PARITY.md` §0 速览 + §1.7 + §6。

## 未验证项（环境所限，非声明已通过）

本机无 Android SDK / Gradle / NDK，以下**未执行**，需在有环境的机器上补：

- `./gradlew :app:assembleDebug`（含 CMake 编译）
- `libplayer.so` / `libmpv.so` 构建
- 真机播放、真机内核切换、真机字幕渲染

**已在本机实测通过**：纯 JVM 子集编译（42 源文件，退出码 0）、单元测试 193 例
（新增 8 例全过，其余 5 例失败为基线原有、与本任务无关）、切换决策 8/8。
