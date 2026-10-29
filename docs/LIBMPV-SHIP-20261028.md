# libmpv 真正进包 · 2026-10-28 修复记录

> 症状：`未检测到 libmpv.so（或 libplayer.so 加载失败）：原生库缺失`，mpv 内核恒不可用。
> 结论：**根因不是"移植失败"，是产物里从来没有过 `libmpv.so`。** 本次把它装进
> `jniLibs` 并加了构建期门禁，`assembleDebug` 产出的 APK 里三个 ABI 都带完整的一套
> `.so`；运行期播放验证用例已写好（`androidTest`），但本机沙箱内**未能**跑起来（见 §6，
> 不做虚假声明）。

---

## 1. 故障链（逐环取证）

| # | 环节 | 证据 |
|---|---|---|
| 1 | `app/src/main/jniLibs/<abi>/` 一个 `.so` 都没有 | 目录里只有 `README.md`（Observed） |
| 2 | CMake 的 `if(EXISTS "${MPV_SO}")` 为假 → 走 else 分支编 stub | `app/src/main/cpp/CMakeLists.txt:20`（真）/ `:59` `target_compile_definitions(player PRIVATE MPV_AVAILABLE=0)`（假） |
| 3 | stub 版 `libplayer.so` 的 `isAvailable()` 恒返回 false | `app/src/main/cpp/src/main.cpp:143` `return MPV_AVAILABLE ? JNI_TRUE : JNI_FALSE;` |
| 4 | Kotlin 侧据此判定引擎不可用并回退 ExoPlayer | `app/src/main/java/com/example/localmovielibrarz/playback/engine/PlaybackEngine.kt:103` |
| 5 | 整个 `assembleDebug` **成功**，只有等到点开视频才报错 | 这正是上一版能构建、能安装、但 mpv 永远灰掉的原因 |

关键判断：`dlopen("libplayer.so")` 本身是成功的（stub 也是合法 `.so`），所以日志里
"原生库缺失" 那句是**业务层对 `MPV_AVAILABLE=0` 的转述**，不是 `UnsatisfiedLinkError`。
按这条链排查就不会在 `System.loadLibrary` / `abiFilters` / `extractNativeLibs` 上浪费轮次。

---

## 2. 修了什么

### 2.1 装配预编译 libmpv（新增 `scripts/assemble-libmpv-jnilibs.py`）

Windows 主机没有可用 WSL/Docker，上游 `buildscripts` 不支持 Windows（`scripts/fetch-libmpv.sh`
的平台检查会直接退出），所以本机不可能从 FFmpeg 源码起编整棵树。本工程采用的是
**集成预编译 libmpv**：

```
dev.jdtech.mpv:libmpv:1.0.0  (AAR, 47,574,467 B)
  sha256 = df146592480fc8418415a06b1f1a1d6318b0088e21f52254b0e9a82b61ca8fa2
  内含 jni/<abi>/*.so，ABI = arm64-v8a / armeabi-v7a / x86 / x86_64，共 40 个 .so
```

装配脚本做四件事，每件都可单独复核：

1. **按 `DT_NEEDED` 算闭包，不靠猜** —— 从 `libmpv.so` 的 `DT_NEEDED` 递归取同 ABI 目录里的
   兄弟库，系统库（`libEGL.so` / `libOpenSLES.so` / `libmediandk.so` / `libc.so` …）由设备提供，不打包。
2. **拒绝 AAR 自带的 `libplayer.so`**（`NEVER_COPY`）—— 那是上游 `dev.jdtech.mpv` 自己的 JNI 封装，
   本工程的 `libplayer.so` 由 `app/src/main/cpp` 编译产出；混进 `jniLibs` 会和 CMake 产物同名，
   轻则 AGP 报 duplicate，重则运行期加载了错的那个 JNI 符号。
3. **逐文件 sha256** —— `--write-manifest` 落盘成 `docs/verify/jnilibs-sha256.json`，
   `--manifest` 反向校验别人给的一套库。
4. **装配后立即自检** —— 每个非系统 `DT_NEEDED` 必须在同 ABI 目录里存在，否则退出码非 0。

结果（`arm64-v8a` / `armeabi-v7a` / `x86_64` 各 9 个）：

```
libmpv.so  libavcodec.so  libavdevice.so  libavfilter.so  libavformat.so
libavutil.so  libswresample.so  libswscale.so  libc++_shared.so
```

`x86` 有意不装：预编译包虽提供，但 32 位 x86 设备上 mpv 这条路本来就不发；少一个 ABI
就少一种 "有 `libplayer.so` 没 `libmpv.so`" 的半装组合。

### 2.2 钉住 ABI 白名单（`app/build.gradle.kts`）

原来没有 `abiFilters`，AGP 默认按 4 个 ABI（含 `x86`）各跑一遍 CMake，于是**四个 ABI 全都**
编出 stub 版 `libplayer.so`。现在：

```kotlin
// 文件顶部
val mpvAbis: List<String> = (findProperty("mpvAbis") as String?)
    ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.takeIf { it.isNotEmpty() }
    ?: listOf("arm64-v8a", "armeabi-v7a", "x86_64")

// defaultConfig 内
ndk { abiFilters += mpvAbis }
```

用 `-PmpvAbis` 可以在不改文件的前提下收窄：`./gradlew :app:assembleDebug -PmpvAbis=arm64-v8a`
（Observed：同一棵装了三套库的树，这样构建出的 APK 里 `lib/` 只有 `arm64-v8a` 的 11 个 .so，
53,698,546 B）。CI 的 `build-apk` 就靠这个开关一次只编一个 ABI。

目的很具体：让 "`jniLibs` 里有哪几个 ABI" 与 "APK 里编哪几个 ABI" 变成同一件事。两边一旦
不一致，就会出现某个 ABI 只有 `libplayer.so`、没有 `libmpv.so` 的静默降级——这正是本次故障的
成因，不该有机会重演。

### 2.3 构建期门禁 `verifyMpvNativeLibs`

缺库时 CMake 会**安静地**编 stub，构建照样绿。这个任务把这件事变成红灯，检查三项：

1. 白名单里每个 ABI 的 `libmpv.so` 都在；
2. 它在 `dlopen` 时会连带加载的兄弟库也在（缺一个，`dlerror` 仍只会说 "libmpv.so not found"，最容易误判）；
3. 目录里**不许**出现 `libplayer.so`（那是 CMake 产物，手工放进去会重名）。

通过时输出（Observed）：

```
> Task :app:verifyMpvNativeLibs
[mpv] jniLibs 校验通过：arm64-v8a=9, armeabi-v7a=9, x86_64=9 个 .so
```

### 2.4 运行期验证用例（新增 `androidTest`）

`app/src/androidTest/java/com/example/localmovielibrarz/mpv/MpvPlaybackSmokeTest.kt`，四级递进，
每一级失败都能定位到具体一层：

| 用例 | 验证什么 | 失败说明 |
|---|---|---|
| `packagedNativeLibsArePerAbi()` | 当前 ABI 目录下 `libmpv.so` 及其闭包真的被装进 APK | 打包层没进包 |
| `engineCreatesAndInitializes()` | `System.loadLibrary("player")` + `mpv_create` + `mpv_initialize` | 加载层/链接层断了 |
| `decodesVideoFramesHeadless()` | `vo=null`、`ao=null` 下 `time-pos` 前进且 `core-idle` 转 false | demux/解码链路不通 |
| `rendersToSurface()` | 真实 `Surface` 上 `vo-configured` 为真、`video-out-params` 非空 | 渲染链路不通 |

---

## 3. 产物实测证据

全部命令可复现，`$SRC` = 源码树根，`$APK` = `app/build/outputs/apk/debug/app-debug.apk`。

### 3.1 APK 里到底有什么（Observed）

```bash
unzip -v "$APK" | grep -E '\.so$'
```

- **33 个 `.so`**：3 ABI × 11（9 个来自 `jniLibs` + CMake 产出的 `libplayer.so` + androidx 的 `libandroidx.graphics.path.so`）
- 全部为 `Stored`（不压缩）——`extractNativeLibs=false` 时系统直接从 APK `mmap`，压缩过就装不上
- `x86` 一个都没有（白名单生效）
- `app-debug.apk` = 107,171,113 B（三个 ABI 的 FFmpeg 全量，体积是这条路的固有代价）

### 3.2 链接关系与导出符号（Observed）

```bash
RE=$ANDROID_NDK/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-readelf.exe
$RE -d lib/<abi>/libplayer.so | grep NEEDED   # 三个 ABI 均含 libmpv.so
$RE --dyn-syms -W lib/<abi>/libmpv.so | grep -E ' mpv_create$| mpv_initialize$| mpv_command$'
```

`libplayer.so` 的 `DT_NEEDED` 三个 ABI 都是：

```
libmpv.so  libandroid.so  liblog.so  libm.so  libdl.so  libc.so
```

`libmpv.so` 导出 `mpv_create` / `mpv_initialize` / `mpv_command`（三个 ABI 一致）。
所以 `System.loadLibrary("player")` 在加载期就能解析到 `libmpv.so`，不需要任何 `dlopen` 兜底代码。

### 3.3 符号闭包校验（新增 `scripts/verify-native-closure.py`）

不依赖设备也能回答"这套库自洽吗"：把每个 `.so` 的**强**未定义符号，拿去和
（同 ABI 打包库的导出 ∪ NDK sysroot 里该 API 的 stub 导出）求交集，缺一个就报错。
系统符号用 `minSdk=26` 的 sysroot stub 作为权威来源，而不是手写白名单。

```bash
python scripts/verify-native-closure.py <libroot> \
  --sysroot "$ANDROID_NDK/toolchains/llvm/prebuilt/windows-x86_64/sysroot/usr/lib" --api 26
```

结果（`docs/verify/closure-apk.json`，`ok=true`）：

| ABI | 库数 | `libplayer`→`libmpv` | 64 位 16 KB 对齐 | 强未定义符号 | 结论 |
|---|---|---|---|---|---|
| arm64-v8a | 11 | ✓ | `0x4000` | 无 | PASS |
| armeabi-v7a | 11 | ✓ | 32 位无要求 | 无 | PASS |
| x86_64 | 11 | ✓ | `0x4000` | 无 | PASS |

两个细节值得记下来，否则容易被误判成故障：

- **`memfd_create` 是 WEAK 未定义，不是错误。** NDK 对 `__INTRODUCED_IN(30)` 的调用发的是
  `WEAK UND`，加载器把它解析成 0，`dlopen` 不会失败。脚本把 WEAK 单列，只有 **STRONG** 未定义
  才算失败——一开始没区分，arm64 上每个库都"缺一个符号"，是假警报。
- **16 KB 页要求只针对 64 位 ABI。** AAR 的 `armeabi-v7a/libc++_shared.so` 是 `p_align=0x1000`，
  在 32 位上完全正常；把这条检查套到 32 位就是自己制造失败。`arm64-v8a` / `x86_64` 全组都是 `0x4000`，
  满足 Android 15 起 16 KB page size 的要求。

### 3.4 从零构建（Observed）

```bash
cd "$SRC"
export JAVA_HOME="D:/andtools/jdk17/jdk-17.0.20.1+1"
export ANDROID_HOME="D:/andtools/sdk"; export ANDROID_SDK_ROOT="D:/andtools/sdk"
export GRADLE_USER_HOME="D:/DeepSeek harness/Homemovies/work/gradlehome"
rm -rf app/build app/.cxx
./gradlew -Duser.home="D:/DeepSeek harness/Homemovies/work/android-home" :app:assembleDebug
```

`-Duser.home=...` 这段不是可选项：AGP 的 debug keystore 定位在 `$HOME/.android/`，本机
`C:\Users\<user>\.android\` 不可写（`AccessDeniedException: debug.keystore.lock`），把
`user.home` 指到一个可写目录即可；该目录下放一份既有 `debug.keystore` 就能通过
`validateSigningDebug`。设 `ANDROID_USER_HOME` 不行——那是 AGP 另一个构建服务用的路径，
两者不是同一个东西。

产物：`app/build/outputs/apk/debug/app-debug.apk`（107 MB，含 3 ABI）。

实测（清空 `app/build` + `app/.cxx` 后重跑）：

```
BUILD SUCCESSFUL in 1m 23s
44 actionable tasks: 43 executed, 1 up-to-date
> Task :app:verifyMpvNativeLibs
[mpv] jniLibs 校验通过：arm64-v8a=9, armeabi-v7a=9, x86_64=9 个 .so
```

产物 `app-debug.apk` = 107,171,113 B；`unzip -l` 统计 `lib/<abi>/*.so` 三组各 11 个，
`libmpv.so` 三个 ABI 都在，`lib/x86/` 目录级条目为 0。

---

## 4. 复现步骤（拿到这套库的人怎么用）

**这个包不带二进制，所以直接 `./gradlew :app:assembleDebug` 一定会在
`verifyMpvNativeLibs` 上失败**——那 27 条「缺 libmpv.so / 缺 libav*.so」是门禁在
按设计工作，不是构建坏了。先装配再构建：

```bash
# 1) 装配进 jniLibs（幂等，可重复跑）。--source 直接吃 .aar，不用先解包；
#    给目录也行（AAR 解包后的 jni/ 或单个 ABI 的平铺目录，后者要配 --abi）。
python scripts/assemble-libmpv-jnilibs.py --source libmpv-1.0.0.aar \
  --write-manifest app/src/main/jniLibs/.sha256.json
#   → [assemble] OK — 27 个 .so → .../jniLibs（arm64-v8a, armeabi-v7a, x86_64）

# 2) 自检（不写文件，只校验现有目录是否成组）
python scripts/assemble-libmpv-jnilibs.py --verify-only

# 3) 构建（门禁会再校验一次）
./gradlew :app:assembleDebug

# 4) 静态复核（可选但推荐）
python scripts/verify-native-closure.py app/src/main/jniLibs \
  --sysroot "$ANDROID_NDK/toolchains/llvm/prebuilt/<host>/sysroot/usr/lib" --api 26
```

只要 `libmpv.so` 那一行不在失败清单里，就说明门禁认得这份库；`libplayer.so` 由
CMake 现场编译，**不从 AAR 拷**（AAR 里那个是 dev.jdtech.mpv 的 JNI 封装，覆盖进来
会和本工程的 `Java_..._MPVLib_*` 符号打架），脚本已把它列进 `NEVER_COPY`。

三条失败信息对应的含义，排障时按这个读：

| 现象 | 含义 | 动作 |
|---|---|---|
| 27 条全缺（3 ABI × 9） | jniLibs 是空的 | 跑步骤 1 |
| 只有某个 ABI 缺 | `--abis` / abiFilters 不一致 | 补该 ABI 或从 abiFilters 去掉 |
| `[assemble] 依赖不闭合` | 源里少了 libmpv 的某个兄弟 .so | 换一份完整 AAR |

**单 ABI 装配是受支持的**（交付的二进制按 ABI 拆包，单包 11–13 MB）：门禁读的是
`android.defaultConfig.ndk.abiFilters`，不是写死的三个 ABI。只留 `arm64-v8a` 时它就只查
arm64。`abiFilters` 与 jniLibs 里实际存在的目录必须一致——这本身就是门禁要拦的事。
不用改文件，用属性即可：

```bash
./gradlew :app:assembleDebug -PmpvAbis=arm64-v8a
```

### 4.1 在 GitHub Actions 里构建

仓库不收 `.so`，所以 CI 上「谁把库放进 jniLibs」必须有明确答案，否则必然撞门禁。
两个 workflow 分工：

| workflow | 干什么 | 耗时 | 产物 |
|---|---|---|---|
| `build-libmpv.yml`（已有） | 交叉编译 libmpv + FFmpeg 整棵树 | 40–90 min | artifact `libmpv-<abi>[-yaozhi]`，内容是 `jniLibs/<abi>/` 的 9 个 .so |
| `build-apk.yml`（新增） | 取上面那个 artifact → 装进 jniLibs → 编 APK | 10–15 min | artifact `apk-<sha>`，即 `app-debug.apk` |

用法（**顺序不能反**）：

1. Actions → `build-libmpv` → Run workflow（`archs` 默认 `arm64`，够用就只跑这个）
2. Actions → `build-apk` → Run workflow（`abis` 默认 `arm64-v8a`，必须与上一步产出的 ABI 一致）

`build-apk` 每一步都在验一件事，失败信息指向具体原因而不是"构建失败"：

- **下载**：`gh run list --workflow build-libmpv.yml --status success` 取最近一次成功 run，
  再 `gh run download -n libmpv-<abi> -D ci-libmpv/<abi>`。不用第三方 download-artifact
  action，因为每个 ABI 都是同名 9 个 .so，层级猜错就会把 A 架构的库塞进 B 的目录且不报错；
  `gh` 一 ABI 一目录，落盘位置确定。
- **架构自检**：读 ELF 头 `e_machine`（偏移 18）比对 `arm64-v8a=183 / armeabi-v7a=40 /
  x86_64=62`。这是上面那条风险的实际拦截——实测把 x86_64 的 9 个库当 arm64 喂进去，9/9 全被拒。
- **装配**：`cp` 进 `app/src/main/jniLibs/<abi>/`，并删掉误入的 `libplayer.so`。
- **闭包自检**：`--verify-only --abis "$ABIS"`（与本地同一条命令）。
- **构建**：`./gradlew --no-daemon :app:assembleDebug -PmpvAbis="$ABIS"`。
- **硬断言**：解包 APK 数 `lib/<abi>/*.so` 必须 ≥ 9 且含 `libmpv.so`。**stub 降级也会构建
  成功**，只靠 "BUILD SUCCESSFUL" 判断等于没判断，所以这条断言是必须的。

不想等 `build-libmpv` 时，也可以用 `libmpv_run_id` 输入钉住某一次历史 run。

### 4.2 单文件包：源码 + arm64 库（`*_all_in_one_arm64.zip`）

只允许传一个压缩包时用这个：**14,267,386 B，解开就能直接 `./gradlew :app:assembleDebug`**，
既不用再叠二进制包，也不用加 `-PmpvAbis`。

内容是源码 + `arm64-v8a` 的 9 个 .so（原始 24,931,856 B），另两个 ABI 目录只放 `.gitkeep`。
装得下的原因：三个 ABI 的库原始 77 MB、压缩后仍约 35 MB，超过单文件 24 MB 上限；单 arm64
压缩后约 11.5 MB，加源码约 2.7 MB 共 13.6 MB。

**为什么不用加参数**：`mpvAbis` 按 `jniLibs` 下**实际装了 `.so` 的目录**自动探测，包里只有
arm64 就只编 arm64。探测到空目录时**不会**退化成"没有 ABI"（那会产出不含任何 .so 的 APK），
而是回退到三个全 ABI 让门禁失败——宁可红灯，不出坏包。

要别的 ABI：把 `libmpv-jnilibs-<abi>.zip` 覆盖进去，探测结果随之改变，不必改文件。

`build-apk.yml` 同样认这个包：开头的「检测 jniLibs 是否已自带库」看到 `.so` 就跳过 artifact
下载与装配直接构建。**把这一包推上去，CI 里不用先跑 40~90 分钟的 `build-libmpv`。**

可复现的打包命令：

```bash
python scripts/pack-single-bundle.py . app/src/main/jniLibs/arm64-v8a \
  ../deliver/homemovies_guangya_fix1028_all_in_one_arm64.zip homemovies_guangya
```

纯源码包（不带二进制）仍单独提供：`docs/verify/jnilibs-sha256.json` 里有全套 27 个文件的
sha256 与字节数，可核对拿到的是不是同一套库；按 ABI 拆开的三个小包
（`libmpv-jnilibs-<abi>.zip`，各 11–13 MB）包内路径就是 `app/src/main/jniLibs/<abi>/`。

---

## 5. 没有回退已修好的功能

本次只碰三处，且都是"加法"：

| 文件 | 改动性质 |
|---|---|
| `app/build.gradle.kts` | 新增 `mpvAbis`（可 `-PmpvAbis` 覆盖）/ `verifyMpvNativeLibs` / `testInstrumentationRunner` / 两条 `androidTestImplementation`；CMake 那段注释里的脚本名从 `fetch-libmpv.sh` 改成 `assemble-libmpv-jnilibs.py` |
| `app/src/main/jniLibs/<abi>/*.so` | 新增二进制（原先为空） |
| `app/src/androidTest/...`、`scripts/*.py`、`.github/workflows/build-apk.yml` | 新增文件 |
| `.github/workflows/build-libmpv.yml` | **未改**（新 workflow 只消费它的 artifact） |

`ci-fix` / `compile-fix` / `mpv_dual` / `lut` 各轮修的 Java/Kotlin/C++/CMake 逻辑一律未动：
`CMakeLists.txt`、`cpp/src/*`、`MPVLib.java`、`PlaybackEngine.kt`、字幕链路、scrape 各轮结果
全部保持原样。`abiFilters` 里去掉 `x86` 不构成功能回退——32 位 x86 设备本就没有可用的 mpv 库，
保留它只会多编一份 stub（ExoPlayer 路径不受 ABI 白名单影响，仍覆盖全部设备）。

---

## 6. 运行期播放验证：本机未跑通（如实记录）

`androidTest` 四个用例已写好并**编译通过**（`assembleDebugAndroidTest` → `BUILD SUCCESSFUL`），
但**没有在任何设备/模拟器上执行过**。原因不是用例或库的问题，是这台沙箱机器起不了 Android guest：

| 尝试 | 结果 |
|---|---|
| AVD `hmv35`（android-35 / x86_64）+ WHPX | QEMU 打印 `Windows Hypervisor Platform accelerator is operational` 后停住；进程 CPU 停在 `0.42~0.48s`、RSS 停在 256 MB，guest 从未开始执行 |
| 同上改 TCG（`-accel off`） | 同样停在初始化后，CPU `0.43s`、RSS 89 MB，排除"加速器差异"这个方向 |
| `-gpu off` / `swiftshader_indirect` | 与 GPU 后端无关，停在同一位置 |
| `adb devices` | 始终为空；`emulator-5554` 在 `netstat` 里由 qemu 监听，`adb connect 127.0.0.1:5555` 拒绝 |
| `advancedFeatures.ini` 关 `Netsim` | `Netsim daemon failed to start: 拒绝访问。 (os error 5)` 依旧出现（emu3 那次曾成功监听 `127.0.0.1:10434`，之后每次都被拒） |

已排除的方向也记下来，免得下一个人重跑：命名管道与 loopback 绑定在本机 PowerShell 里**都正常**
（实测 `PIPE_OK` / `TCP_OK`），所以不是"沙箱禁管道"；`-accel off` 与 WHPX 停在同一位置，所以不是
hypervisor 本身；`-gpu off` 同样停滞，所以不是 SwiftShader/D3D11。

**因此本次交的是**：可编译、产物里确有 `libmpv.so`、链接与符号闭包静态可证；**不是**"已在设备上
验证播放成功"。要让这句变成事实，把它放到能跑模拟器或真机的机器上执行一次即可：

```bash
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.notAnnotation=androidx.test.filters.LargeTest
# 或指定单个用例，先看打包层与加载层
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.example.localmovielibrarz.mpv.MpvPlaybackSmokeTest#engineCreatesAndInitializes
```

用例里 `rendersToSurface()` 需要能建 `Surface` 的宿主，`decodesVideoFramesHeadless()` 只需要
`vo=null`，在无 GPU 的 CI runner 上也能跑——想先用最小成本确认"库真的能加载并解码"，跑这一条就够。

---

## 7. 一页速查

- 看到"未检测到 libmpv.so"先查 `jniLibs/<abi>/` 有没有 `libmpv.so`，别先怀疑 `System.loadLibrary`。
- `verifyMpvNativeLibs` 红灯 = 库没成组；`closure-apk.json` 的 `unresolved_symbols` 非空 = 库之间对不上。
- CI 上先 `build-libmpv` 再 `build-apk`，顺序反了 `build-apk` 会明确报"没有成功的 build-libmpv 运行"。
- 只编一个 ABI：`-PmpvAbis=arm64-v8a`（不改文件），门禁跟着一起收窄。
- APK 构建成功 ≠ 库进去了（stub 也会成功），必须查 `unzip -l app-debug.apk | grep libmpv.so`。
- `memfd_create` 的 WEAK 未定义是正常的；16 KB 对齐只查 64 位 ABI。
- 本机构建必须带 `-Duser.home=<可写目录>`，否则卡在 `debug.keystore.lock`。
- 交付包不带 `.so`；要二进制去 `dev.jdtech.mpv:libmpv:1.0.0`（sha256 `df146592480fc8418415a06b1f1a1d6318b0088e21f52254b0e9a82b61ca8fa2`），用 `scripts/assemble-libmpv-jnilibs.py` 装配。
