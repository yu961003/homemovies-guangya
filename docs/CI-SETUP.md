# CI 修复：`Failed to find package 'tools'`

> 现场：仓库 `yu961003/homemovies-guangya`、分支 `main`、run
> [40253956078](https://github.com/yu961003/homemovies-guangya/actions/runs/40253956078)
> （commit `18fff2ef`，2026-10-26T01:23:11Z）。
> 失败步骤是 **`Setup Android SDK`（第 5 步）**，`Checkout` / `Unzip source code` /
> `Set up JDK 17` 全部 success，`Grant execute permission` 之后全部 skipped ——
> job 在编译开始前就死了。

## 1. 报错原文

```
Warning: Failed to find package 'tools'
The process '/usr/local/lib/android/sdk/cmdline-tools/16.0/bin/sdkmanager' failed with exit code 1
    at ExecState._setResult (.../android-actions/setup-android/v3/dist/index.js:1823:25)
    at ExecState.CheckComplete (.../android-actions/setup-android/v3/dist/index.js:1806:18)
```

堆栈里全是 `setup-android@v3` 自己的 `dist/index.js`，与工程代码、Gradle 脚本无关。

## 2. 根因：v3 的默认包清单里带着已被仓库删除的 `tools`

**(a) v3 的 `packages` 默认值就是 `'tools platform-tools'`**

`android-actions/setup-android@v3` 的 `action.yml`（Observed，取自
<https://raw.githubusercontent.com/android-actions/setup-android/v3/action.yml>）：

```yaml
inputs:
  cmdline-tools-version:
    default: '12266719'          # = cmdline-tools 16.0
  packages:
    default: 'tools platform-tools'
```

仓库里 `.github/workflows/build.yml` 的这一段：

```yaml
      - name: Setup Android SDK
        uses: android-actions/setup-android@v3
```

**没有 `with:`** → 吃默认值 → 去装 `tools`。`12266719` 正是 cmdline-tools 16.0 的构建号，
和报错路径 `/usr/local/lib/android/sdk/cmdline-tools/16.0/` 完全对上。

**(b) `tools` 已从 SDK 仓库移除**

`tools` 是旧版 Android SDK Tools（≤ 25.2.2）的包名，后来被 `cmdline-tools` 取代；
**自 cmdline-tools 16.0 起 legacy `tools` 包不再在 SDK 仓库中**。`sdkmanager` 打印
`Failed to find package 'tools'` 后 `exit 1` —— 它不会因为清单里 `platform-tools` 装成功就返回 0。
所以这是**确定性失败**，与网络、缓存、runner 镜像、代码都无关，重跑多少次都一样。

> 公开的同款修复记录（可对照）：
> <https://git.churchofmalware.org/leviathan/VIGIL/commit/d334f440452b7a62451b06c9efd0205d8e01ceb1>

> `main` 分支的 `action.yml` 已把默认值改成 `'platform-tools'`。**别指望它** —— 你写的是 `@v3`，
> 就锁在 v3 的默认值上，tag 不会自己变。

## 3. 修法：给这一步补 `with:`，显式覆盖 `packages`

改 `.github/workflows/build.yml` 里 `Setup Android SDK` 那一处（**只改这一处，别动其它步骤**）：

```yaml
      - name: Setup Android SDK
        uses: android-actions/setup-android@v3
        with:
          packages: 'platform-tools platforms;android-35 build-tools;35.0.0'
          accept-android-sdk-licenses: true
          log-accepted-android-sdk-licenses: false
```

本工程需要的组件（Observed，来自仓库里的 `app/build.gradle.kts` / `build.gradle.kts` /
`gradle/wrapper/gradle-wrapper.properties`）：

| 配置项 | 值 | 对应 SDK 包 |
| --- | --- | --- |
| `compileSdk` / `targetSdk` | 35 | `platforms;android-35` |
| AGP | 8.10.0 | 默认 build-tools `35.0.0` |
| `minSdk` | 26 | — |
| Java 源/目标 | 17 | — |
| Gradle wrapper | 8.12.1 | — |
| Kotlin / KSP | 2.3.20 / 2.3.11 | — |

`platform-tools` 是 adb/fastboot 标准包；`cmdline-tools` 由 action 自己装，不要手写。

### 三个必须知道的坑

1. **只能单行空格分隔，或 `>-` 折叠；不能用 `|` 块标量。**
   action 内部是 `.split(' ')`（`v3/dist/index.js` 第 28422–28423 行附近），只按空格切，
   再对每个包单独 `exec.exec(sdkManager, [arg])`。`|` 会把换行留在值里，
   整段变成一个含 `\n` 的包名 → 照样 `Failed to find package`。
2. **别写 `packages: ''`。** 空串会覆盖默认值成空列表，`platform-tools` 就不装了。
3. **别删 `packages` 键。** 删了就是回到默认值 `'tools platform-tools'`，原样复现。

### 想更省心的两个变体

只要 `platform-tools`、让 AGP 自己按 `compileSdk` 拉 platform 与 build-tools（`android.builder.sdkDownload`
默认开启，许可已由本 action 接受）——这也是上游同款修复采用的写法：

```yaml
        with:
          packages: 'platform-tools'
```

不用 action、直接吃 runner 镜像预装的 SDK（`/usr/local/lib/android/sdk` 就在镜像里）：

```yaml
      - name: Set up Android SDK
        run: |
          yes | sdkmanager --licenses > /dev/null
          sdkmanager --install "platform-tools" "platforms;android-35" "build-tools;35.0.0"
```

## 4. 改完之后怎么推

仓库是公开的，用网页就能改（和之前上传 zip 的操作方式一致）：

1. 打开 <https://github.com/yu961003/homemovies-guangya/blob/main/.github/workflows/build.yml>
2. 点铅笔图标编辑，把 `Setup Android SDK` 那两行换成上面 §3 的六行，Commit。
3. Actions → Android CI → Run workflow 重跑。

或者本地 `git`：

```bash
git clone https://github.com/yu961003/homemovies-guangya.git
cd homemovies-guangya
# 方式一：直接覆盖（deliver/ci-fix/build.yml 是改好的完整文件）
cp /path/to/deliver/ci-fix/build.yml .github/workflows/build.yml
# 方式二：只打补丁（deliver/ci-fix/build.yml.patch，只碰 SDK 那一处）
patch -p1 < /path/to/deliver/ci-fix/build.yml.patch
git add .github/workflows/build.yml
git commit -m "ci: stop installing the removed SDK 'tools' package"
git push
```

## 5. 验收

重跑后，`AssembleDebug` job 的步骤序列应该是：

```
Set up job                success
Checkout                  success
Unzip source code         success
Set up JDK 17             success
Setup Android SDK         success   <-- 这里不再有 Failed to find package
Grant execute permission   success
Validate Gradle wrapper    success
Build debug APK            success / 或进入下一类失败
Upload debug APK artifact
```

`Setup Android SDK` 步骤的成功标准：日志里能看到 `Accepting Android SDK licenses`，
并且 `platform-tools`、`platforms;android-35`、`build-tools;35.0.0` 三次安装都返回 0，
**没有** `Failed to find package` 字样。

## 6. 这一步过了之后可能遇到的下一批失败

本工程从未在 CI 上真正跑通 `assembleDebug`（历史唯一的 success 是 9 月的旧代码），
所以 SDK 步骤修好后，构建本身仍是**未验证状态**。按顺序看：

| 现象 | 原因 / 处置 |
| --- | --- |
| `Could not find ... androidx.compose:compose-bom:2026.05.00` | BOM 版本号不存在或太新，改 `app/build.gradle.kts` |
| `KSP / Room` 版本不匹配 | `room-ktx` 与 `room-compiler` 必须同版本（当前均 2.8.4），KSP 2.3.11 配 Kotlin 2.3.20 |
| `minCompileSdk` 报错 | 某依赖要求 compileSdk > 35；升 `compileSdk`，别降依赖 |
| `Unresolved reference` | 本机只做过 kotlinc 符号审计（无 AndroidX/Room 类型检查），真实编译才会暴露；按报错补 import / 符号 |
| `Upload debug APK artifact` 失败（`if-no-files-found: error`） | 构建没产出 APK，先看上一格 `Build debug APK` 的日志 |

## 7. 第二批：`:app:compileDebugKotlin` 的 20 条错误（2026-10-26 已修）

SDK 步骤修好后，构建推进到编译阶段，报出 20 条错误、分布在 5 个文件。
它们**不是 20 个独立问题**，实际只有 5 个根因，其中 2 个是「少一个字符」型的语法／
全限定名错误，各自又连带出一串推理失败：

| # | 文件 | 报错 | 根因 | 改法 |
| --- | --- | --- | --- | --- |
| 1 | `MovieMetadataRefreshRunner.kt:44` | `Unresolved reference 'CoroutineDispatcher'` | 写成了 `kotlin.coroutines.CoroutineDispatcher`；该接口属于 `kotlinx.coroutines`（`kotlin.coroutines` 下只有 `coroutineContext` / `Continuation` 这类） | 全限定名改 `kotlinx.coroutines.CoroutineDispatcher` |
| 2 | `StrmScrapeRepository.kt:554` | `Too many arguments for 'fun settles(info, remainingSources)'` | 调用少写一个 `)`，紧跟的 `{` 被解析成 `settles` 的 trailing lambda → 变成 3 个参数 | 补上 `)` |
| 3 | 同文件 `:559/560/562` | `'break' or 'continue' crosses a function or class boundary` + 两条 `Syntax error` | 与 #2 同一个根因：#2 的 trailing lambda 把 `{ … }` 变成了函数字面量，里面的 `break` 自然就"跨函数"了 | 随 #2 一起消失 |
| 4 | `PlaybackFailure.kt:126` | `Unresolved reference 'TYPE_DECODER'` | `ExoPlaybackException` 没有这个常量；解码失败统一表现为 `TYPE_RENDERER`（Renderer 内部抛 `DecoderException`） | 删掉 `TYPE_DECODER` 分支，保留 `TYPE_RENDERER` |
| 5 | `R18DevScraper.kt:107` | `Only safe (?.) or non-null asserted (!!.) calls are allowed on a nullable receiver of type 'String?'` | `JSONObject.optString()` 返回 `String?`，而 `cleanText()` 是 `String` 上的私有扩展 | `(rows.optJSONObject(index)?.optString("name_kanji").orEmpty()).cleanText()` |
| 6 | `CloudBrowserViewModel.kt:933` | `Unresolved reference 'items'` | `items = strmRepository.listFiles(...)` 少写 `val`，等于给不存在的属性赋值 | 补 `val` |
| 7 | 同文件 `:157/158/163-165` 与 `:935/938-941` | `Unresolved 'classifyVisibleItems'` + `Cannot infer type 'R'/'T'` + `Unresolved 'added/queued/failedPickcodes'` | `classifyVisibleItems` 定义在 **`CloudStrmRecordRepository`**（签名 `Set<String> -> CloudVisibleItemState`），却调在 `Cloud115StrmRepository` 上；调用无解 → `runCatching`/`async` 的类型参数推不出来 → 紧随其后的 `.addedPickcodes` 等一并无解 | 改调 `recordRepository.classifyVisibleItems(<pickcodes>)`，入参用 `mapNotNull { it.pickcode }.toSet()` 投影 |

能看出规律：**一条语法／符号错误会向下连坐一整片**。所以修这类错误时先看第一条，
别对着最后一条改——`break` 跨边界和 `Cannot infer type` 几乎都是被连带的。

### 7.1 本轮的验证边界（诚实版）

- 本机无 JDK / Gradle / Android SDK，**没有执行过 `:app:compileDebugKotlin`**，这 20 条
  的"已修"= 逐条对源代码核过、根因明确、改法与报错一一对应；**真正确认要靠下一轮 CI**。
- 做过的机器检查：
  - `work/audit-all-symbols.sh`（kotlinc 2.1.21 全量 196 文件）→ 去重后**自己包内未解析符号 0 个**；
    日志里剩下的全是 `androidx` / `MaterialTheme` / `items`(Compose `LazyListScope`) 这类缺依赖噪音。
  - 5 个被改文件的括号配平静态扫描（`work/brace-locate.py`）→ 全部 `balanced OK`。
  - `work/compile-pure-jvm.sh` → `Tests run: 185, Failures: 5`，与修改前**完全一致**
    （仍是 `MovieNumberExtractorTest` / `MovieNumberUtilsTest` 那 5 个既有基线失败，未新增）。
- 已知工具链差异造成的**误报**：本机 kotlinc 2.1.21 在
  `StrmScrapeRepository.kt:1060`、`DetailScreen.kt:1324`、`GuangyaLoginClient.kt:146`
  报同类 nullable 调用错误，但 CI（Kotlin 2.3.20）的报错清单里没有这三处——
  属新旧编译器对 `?.let{…}?.takeIf` 链与平台类型的推断差异，**本次刻意不动**，
  以免在 CI 本来没意见的地方引入改动。

## 8. 本轮未验证

- **未执行** `:app:assembleDebug`：交付环境无 JDK / Gradle / Android SDK，也连不到
  `google()` / `mavenCentral()`，产不出 APK，无法声称构建通过。
- 修复只覆盖「SDK 安装步骤」这一确定性失败；`build.yml` 其余步骤逻辑未改动
  （`unzip` 智能查找 `gradlew`、`cp -a` 合并 `.github` 的写法原样保留，已本地按同逻辑跑过一遍合并流程）。
- 交付包内**不带** `.github/`：权威的 CI 配置是仓库里的 `build.yml`，包里再放一份会变成两份工作流。