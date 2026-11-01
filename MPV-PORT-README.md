# mpv 双播放器内核 · 移植说明

本工程从「只有 ExoPlayer」改为 **ExoPlayer / libmpv 双内核并存**：两套内核共用同一套播放器 UI，
可在播放中随时切换且不丢状态。在线字幕（搜索 / 下载 / 本地库 / 偏移 / 样式）在 mpv 内核下完整可用。

## 为什么是「薄适配器」而不是重写播放器

`PlaybackEngine` 只抽象 6 个动作（prepare / play / pause / seek / setSurface / release），
`ExoPlaybackEngine` 与 `MpvPlaybackEngine` 各自实现；`PlayerViewModel.createPlayer()` 仍按原样构建
`ExoPlayer` 实例，mpv 只是另一条渲染路径。这样内核切换不动播放列表、进度、字幕与 UI 状态。

## 原生库

`app/src/main/jniLibs/arm64-v8a/` 已带 9 个 `.so`：
`libmpv` `libavcodec` `libavdevice` `libavfilter` `libavformat` `libavutil`
`libswresample` `libswscale` `libc++_shared`。

```bash
# 校验（CI 与本机 preBuild 都会跑）
python scripts/assemble-libmpv-jnilibs.py --verify-only --abis arm64-v8a

# 从 Maven 的 libmpv AAR 重新装齐
python scripts/assemble-libmpv-jnilibs.py --source libmpv-1.0.0.aar
```

缺库不再静默降级：`verifyMpvNativeLibs` 直接构建失败并逐个 ABI 列出缺什么。

## 启动顺序（错序即黑屏）

`loadfile` 必须晚于 `wid`：android VO 初始化时读到 `WinID=0` 会 `Missing surface pointer`，
表现为只剩声音、画面全黑而进度/时长正常。现在的顺序是「等渲染面就绪 → 建 mpv → 设 wid →
loadfile」，并用 `vo-configured` + mpv 日志双判据自检，把失败原因写进错误条而不是吞掉。

## 画质增强

| 能力 | 内核 | 实现位置 |
|---|---|---|
| 3D LUT | mpv | `--target-lut`（init 前下发，需 `vo=gpu-next`），`MpvOptions.kt` |
| 3D LUT | exo | media3 `SingleColorLut` + `ExoLutEffect`，HDR 走 `PassthroughShaderProgram` |
| Anime4K 超分 | mpv | 14 个 GLSL + 六档链，`MpvAnime4k.kt`（`glsl-shaders` 以 `:` 连接） |

Anime4K 的六档链（`A+` / `B+` / `C+` / `A` / `B` / `C`）与质量三档（L / M / S）是固定组合，
换链要重建 shader program，因此改档后**下次起播生效**，运行期不热切换。
任一着色器缺失或解包失败 → 以无超分起播，画质增强绝不挡住播放。
