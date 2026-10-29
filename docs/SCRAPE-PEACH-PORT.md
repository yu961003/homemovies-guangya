# 刮削链改造交付说明（peach 主链接入 + 文件夹批量进度 + 元数据补全停止态）

> 基线：本仓库上一轮交付 `homemovies_guangya_mpv_dual_20261027.zip`
> 本轮增量：**18 个文件修改 + 29 个文件新增**（全部在 `app/src/main/**` 内，见文末清单）
> 本机无 Gradle / Android SDK / NDK，未执行 `assembleDebug`；验证方式见「验证总表」，**未验证项单列一节不省略**。

---

## 0. 三个议题与结论速览

| # | 议题 | 结论 | 主改动点 |
|---|---|---|---|
| 1 | 现有刮削链降为**最终兜底**，peach 刮削链加进来作**默认首选** | 已实现。自动链默认先跑桃链，桃链拿不到结果/不可用/抛异常才落到 legacy；用户点名来源时行为完全不变 | `StrmScrapeRepository.scrapeWithFallback`、`PeachScrapeChain`、`PeachSourceCatalog`、`ScrapeChainMode` |
| 2 | 网盘文件夹刮削**看不到整体进度**、**不能对文件夹任务暂停/继续** | 已实现。两处界面（云盘浏览页面板 + 设置页刮削任务）都有整体进度、逐行进度、暂停/继续/取消 | `FolderBatchProgress`、`CloudFolderBatchTaskRepository`、`CloudFolderBatchTaskRunner`、`CloudBrowserScreen`、`SettingsScreen` |
| 3 | 元数据补全**点停止后一直停在那个状态** | 已修复。取消路径把内存态落成 `isRunning=false` 并给可读文案，之后能再次点开始 | `MovieMetadataRefreshRunner` |

---

## 1. 议题一：peach 刮削链作为默认主链

### 1.1 目标

1. 自动刮削（`ScrapeSource.Priority`）**默认**走 peach 的分档链路；
2. legacy 链**只在桃链拿不到东西时**出场，不是平级并联；
3. 用户点名某个来源时，行为与改造前**逐字一致**（不排序、不裁剪、不换源）；
4. 保留一键退回 legacy 的开关（桃链的站点行为只有真机能验，需要现场处置手段）；
5. peach 有而项目没有的能力，**要么实现，要么显式说明缺什么**，不默认省略。

### 1.2 改动点

按「从下游到上游」排列，每层都只做一件事：

**(a) 失败语义层 —— `scraper/PeachFailureReason.kt`（新）**

把 peach 的 12 档失败原因搬进来（`cloudflare_challenge` / `ip_banned` / `geo_restricted` / `not_found` / `rate_limited` / `auth_required` / `server_error` / `network` / `parse_error` / `blocked` / `timeout` / `unknown`），并给出每一档的「可否重试 / 冷却时长 / 是否算负缓存」三元映射。

关键取舍：peach 的 `parse_error` **永久不重试**，而项目原有 `ScrapeFailure.retryable` 对 `ParseError` 给的是 `true`（`ScrapeFailure.kt:55`）。两边冲突时**以项目原有语义为准**——项目里 parse error 多数是「站点改版导致选择器偏移」，重试一次换个 UA 有真实收益；桃链内部按 peach 语义走（不重试），跨链时按项目语义走。这条差异写在 `PeachFailureReason` 的 KDoc 里。

**(b) 内容路由层 —— `scraper/PeachContentRoute.kt`（新）+ `ScrapeRouteRules.routeForPeach`（改）**

按番号形态分档（有码/业余/无码/FC2/合集/未知），与 legacy 的 `routeFor` 有**两处有意差别**：

| 场景 | legacy `routeFor` | peach `routeForPeach` | 为什么不一样 |
|---|---|---|---|
| 该档位裁完为空（如 kmib/other） | **回退完整配置顺序** | 保持空链 + 给 `fallbackReason` | 回退完整顺序会把「同号异片」的站重新问一遍，正是 peach 裁空想避免的 |
| 形态认不出（`Unknown`） | 用完整配置顺序 | 社区档优先，**官方档仍留在后面** | 完全按 peach「unsure 只问社区」会漏掉官方站；这里保留官方但降权，属有意的保守偏差 |

**(c) 来源目录 —— `scraper/PeachSourceCatalog.kt`（新）**

把 peach 的 32 个源逐个落成 `profile(...)` 条目（站点标识、档位、支持的番号形态、解析器、取信层级、覆盖的字段）。**能接的直接接，接不了的写明 `unwiredReason`**（见 1.5）。

**(d) 链编排 —— `scraper/PeachScrapeChain.kt`（新）**

```
负缓存命中 → 跳过（不占预算）
冷却中     → 跳过（不占预算）
预算用尽   → 收尾，交回已拿到的部分
fetch      → Success：按取信层级合并 → settlesPeach 命中即停
           → Failure：仅 NotFound 且番号确实匹配时写负缓存
```

链只返回三种结果，语义边界是这一层的核心：

| 结果 | 含义 | 调用方动作 |
|---|---|---|
| `Success` | 拿到元数据 | 直接返回 |
| `NoResult` | 链跑完了，一家都没给 | 降级 legacy（最终兜底出场） |
| `Unavailable` | 链压根没跑起来 | 降级 legacy |

**(e) 三闸门 —— `scraper/ScrapeBudget.kt`（新）+ `scraper/ScrapeByteMeter.kt`（新）**

peach 是 `6000 次请求 / 1 GiB / 4 小时`，那是**整批**口径；本项目的 `scrapeWithFallback` 粒度是**单部片**，照搬等于不设闸。改成单片口径：**12 次请求 / 32 MiB / 90 秒**。

三项全部落地：
- **请求数**：`PeachAttempt` 里每次真正发出去的请求算一格（命中负缓存、冷却跳过、配置错误都不算）；
- **墙钟**：从链开始计时；
- **字节数**：由 `ScraperResponseGuard.readLimited` 顺手记进 `ScrapeByteMeter`（进程级、按来源累计），取数前后各取一次快照，差值即这一次的字节数，回填到 `PeachAttempt.responseBytes`。

字节统计的口径缺陷（**显式说明，不掩饰**）：`readLimited` 之外的读正文路径（若有）不计入，所以预算是**低估**的。选它是因为另一条路要么改 `MovieScraper` 接口（连带 8 个实现 + 3 个单测），要么在仓库层包 counting stream（OkHttp body 只能读一次，包了就得自己管缓存）——两条都比「少算一点字节」代价大。

**(f) 负缓存 —— `scraper/ScrapeMissCache.kt`（新）**

只记 `NotFound`，TTL 7 天，容量 4096（`LinkedHashMap` LRU）。**进程内、不落库**：目的是「同一批里别再重复问同一个 404」，不是跨重启的知识库。

**(g) 封面选择 —— `scraper/PeachCoverPick.kt`（新）+ `scraper/PeachCoverProbe.kt`（新）**

peach 选封面不是「列表里第一个成功的」，而是：`Range: bytes=0-65535` 只取头部 → 放进 `BitmapFactory` 读**真实像素尺寸** → 按面积去重 → 取面积最大者。项目原来按列表首个成功，会拿到 200×300 的缩略图当封面。

**(h) 字段合并 —— `scraper/PeachFieldMerge.kt`（新）**

peach 的「官方覆盖社区、社区补官方空缺」落成 `ScrapeAuthority.mergeByAuthority` 的调用序：按 `SourceTier` rank 从高到低，高档先写、低档只补空字段。

**(i) 新解析器 —— `AvbaseParser/Scraper`、`OnePondoParser/Scraper`、`Fc2Parser/Scraper`（各新）**

补 3 个 peach 有、项目完全没有的来源。**默认不启用**（不在 `DEFAULT_PRIORITY_SCRAPE_SOURCES` 里），需在设置页手动加入来源列表。

**(j) 接线 —— `data/repository/StrmScrapeRepository.kt`（改）**

`scrapeWithFallback` 拆成「桃链 → legacy」两段：

```kotlin
if (requestedSource != ScrapeSource.Priority) return scrapeLegacyChain(...)   // 点名来源：原样
if (settingsRepository.getScrapeChainMode() == LegacyOnly) return scrapeLegacyChain(...)
when (val r = runCatching { scrapePeachChain(...) }) {
    Success      -> r.info
    NoResult     -> legacy 兜底
    Unavailable  -> legacy 兜底
    null         -> CancellationException 原样抛出，其余 legacy 兜底
}
```

`CancellationException` 必须原样抛回：`runCatching` 会把它一起吞掉，否则「用户取消刮削」会被当成「桃链出错」而多跑一遍 legacy。

**(k) 开关 —— `scraper/ScrapeChainMode.kt` + 设置页**

`PeachPrimary`（默认）/ `LegacyOnly` 两档。**缺键时的默认值就是桃链优先**（而不是首次启动写入），这样老安装升级上来读到的也是桃链优先。

设置页「默认刮削（优先级）」区块顶部加了开关行（`SettingsSwitchRow`），保存后**回读** `getScrapeChainMode()` 再落 UI——存进去的值会经过 `fromStoredValue` 归一，回读才反映真实生效值。

### 1.3 降级策略的边界（重要）

桃链失败后降级 legacy，**存在重复请求同一批站的风险**：桃链已经把「404」的那批站问过了，legacy 不知道桃链的负缓存，会再问一遍。撞击点是 `ScrapeSourceInterval` 的 3 秒级最小间隔 + 15min→24h 冷却阶梯。

处理方式：
- 桃链这一趟的失败**已经在 `sourceHealthTracker` 里记过账**，legacy 的冷却判断会接手，冷却中的站不会被重打；
- 真正可能被重问的只有「明确答复没有」的那批（404），代价是一次 404，**远小于刮不出东西**；
- 这个取舍写进了 `scrapeWithFallback` 的 KDoc，不是隐性行为。

### 1.4 验证结果

| 项 | 结果 |
|---|---|
| tier1 纯 JVM 编译（58 个纯 Kotlin 文件，含全部 `scraper/` 新增类） | **exit 0** |
| JUnit（29 个测试类，含本轮新增 8 个） | **Tests run: 268, Failures: 5** —— 5 个失败全是基线已有（`MovieNumberUtilsTest` 等编号解析用例），与本次改动无关 |
| 本轮新增 8 个测试类 | `PeachScrapeChainTest` / `ScrapeBudgetTest` / `ScrapeMissCacheTest` / `PeachContentRouteTest` / `PeachCoverPickTest` / `PeachFieldMergeTest` / `PeachSourceParserTest` / `PeachAttemptWiringTest` 全部编译并执行 |
| 括号平衡检查（改动涉及的 63 个 kt） | 61 OK / 2 误报，误报原因已定位：检查器不认 `'{'`、`'['`、`'"'` 这类字符字面量（`PeachJson.kt:79-83`、`CustomJsonScraper.kt:177`），这两个文件在 tier1 编译中通过 |

### 1.5 无法对齐项（显式列出，不默认省略）

peach 的 32 个源里，**9 个已接入、9 个显式不接**，另外 14 个按形态归并到已接入的源上（peach 里本就是同一家的分站或镜像）。不接的 9 个及原因：

| 源 | 不接的原因 |
|---|---|
| `faleno` | 官网对非日本出口返回 403，且番号前缀（FSDSS/FCDSS/FNS）不在 `OfficialScraper` 的覆盖表里 |
| `dahlia` | 官网是 WordPress 站，页面上拿不到结构化的发行日/厂牌字段；数据由 r18.dev 镜像覆盖 |
| `makers` | peach 用 amane 子进程桥把番号映射到具体片商官网，本项目没有这条通道 |
| `fc2cmadb` | 站点靠 Cloudflare 反爬，peach 侧带 Cookie/会话与浏览器回落才能取页 |
| `fc2ppvdb` | 同上：Cloudflare 反爬 + 需要登录态才能看完整字段 |
| `javten` | 站点已大范围返回 403/验证页，peach 侧靠浏览器回落取页；本工程无此通道 |
| `javarchive` | 站点数据来自旧的 JavBus 抓取快照，字段口径与本工程 `JavbusScraper` 重叠，收益低于维护成本 |
| `avsox` | peach `sources/` 里没有独立实现（路由表里出现但无 `avsox.py`） |
| `kmib` | peach 自己对 kmib 就是空链（`ROUTES['kmib'] = ()`），故意不问任何站；本工程沿用这条取舍 |

这 9 条全部写进了 `PeachSourceCatalog` 的 `unwiredReason` 字段，代码里可以直接查到，不依赖本文档。

另外三项 peach 能力**未完全对齐**：

| 能力 | 状态 | 说明 |
|---|---|---|
| 浏览器回落（Playwright/子进程渲染取页） | **未实现** | 这是 `fc2cmadb` / `fc2ppvdb` / `javten` / `makers` 四家不接的直接原因。Android 端要落地得内嵌 WebView 驱动取页，属于独立工程，本次不做 |
| amane 子进程桥（番号→片商官网映射） | **未实现** | 同上，`makers` 因此不接 |
| 整批口径闸门（6000 次 / 1 GiB / 4 h） | **改为单片口径** | 不是遗漏，是刻意：本项目调用粒度是单部片，整批数字在这里等于不设闸 |

---

## 2. 议题二：文件夹刮削的整体进度与暂停/继续

### 2.1 目标

1. 添加网盘文件夹刮削后，**有一个地方能看见整个文件夹的进度**（不是只能看见单部影片的进度）；
2. 能对文件夹任务做**暂停 / 继续 / 取消**；
3. 进度不能是假的——不能在收集阶段显示一个恒为 100% 的进度条。

### 2.2 根因（为什么原来「看不到进度」）

`CloudFolderBatchTaskEntity` 里只有 `queuedVideos` 与 `processedVideos` 两个计数，但它们在任务的两个阶段含义不同：

- **收集阶段**（递归读子文件夹、把候选攒齐）：`queuedVideos` 只等于 `queuedCandidates + collectionSkipped`，而 `processedVideos` 同样等于 `outcomes.size + collectionSkipped`。两个数**一起从 0 长起来**，直接相除得到**恒为 100%** 的假进度条，直到收集结束才突然跌回去。
- **处理阶段**（`summary.queuedCandidates = candidates.size` 之后）：分母固定，分子一格一格涨，此时百分比才有意义。

两个阶段在数据库里没有独立字段（加列要动 Room schema 版本，代价过大），所以只能靠数值形态粗判。

### 2.3 改动点

**(a) 进度判形 —— `ui/shared/FolderBatchProgress.kt`（新）**

```kotlin
sealed interface FolderBatchProgress {
    data class Indeterminate(val label: String) : FolderBatchProgress
    data class Determinate(val completed: Long, val total: Long, val ratio: Float) : FolderBatchProgress
}
fun CloudFolderBatchTaskEntity.folderBatchProgress(): FolderBatchProgress
fun List<CloudFolderBatchTaskEntity>.folderBatchAggregateProgress(): FolderBatchProgress
```

判形规则：`Completed` → `Determinate(1f)`；`total<=0` → `Indeterminate("正在扫描 X"/"正在读取文件夹")`；`completed>=total` 且仍在跑 → `Indeterminate("正在收尾（等待刮削结算）")`、否则 `Indeterminate("等待继续")`；其余 → `Determinate`。

汇总规则：只要有一条还没定下分母，整体就**不给百分比**（`Indeterminate("正在收集候选视频（已完成 N，总数待定）")`）。把「未知」和「已算出」混进同一个分母加总，得到的数字既不代表任务数也不代表影片数。

代价（**显式说明**）：处理阶段真正跑到最后一格、以及刮削结算等待的那几秒，也会被归进「未定」，显示不定态进度条而不是 100%。宁可显示「还在收尾」也不要显示一个看起来已完成、实际还在跑的数字。

**(b) 任务操作 —— `CloudFolderBatchTaskRepository.kt` / `CloudFolderBatchTaskRunner.kt`（改）**

新增 `markPending(taskId, message)`：把**单行**放回 `Pending`，让 runner 重新拾起。

为什么不复用 runner 的 `start()`：`start()` 会先跑 `prepareForStart()`，那条 SQL 会把**所有** Paused / Failed / Cancelled 一起推回 Pending。用户在某一行的「继续」按钮上点一下，却把其它几个手动暂停的任务一起唤醒，属于越权副作用。所以拆成两个入口：`start()` = `launchRun(poolPausedRows = true)`（「开始全部」语义），`resumeTask()` = `launchRun(poolPausedRows = false)`（只跑库里已经是 Pending 的行）。

`markPending` / `markPaused` / `markCancelled` 都**只改 status 与文案**，不动 `processedPickcodesCsv` 与各计数——断点是续跑的唯一真相源，清零等于把已完成的影片再刮一遍。

三个操作的实际语义（runner 是**单协程顺序处理**，没有「只停当前这条、别的继续跑」的轻量实现）：

| 操作 | 当前正在跑的那条 | 排队中/未轮到的那些 |
|---|---|---|
| 暂停 | `stop()` 掉这一轮（取消分支把它落成 `Paused`），再 `launchRun(poolPausedRows=false)` 让剩下的 Pending 继续——等价于「暂停这一条，队列不塌」 | 直接 `markPaused` |
| 继续 | `markPending` 放回 Pending + `launchRun(poolPausedRows=false)` | 同上（用户点哪行就放哪行） |
| 取消 | `cancel()` 掉这一轮（落成 `Cancelled`）+ 重启队列，避免一条被取消后把后面排队的饿死 | 直接 `markCancelled` |

`Paused` 与 `Cancelled` 的差别只在终态：前者是「先停一下，等下续」，后者是「这条不用再跑了」。

**(c) 云盘浏览页面板 —— `ui/cloud/CloudBrowserScreen.kt` + `CloudBrowserViewModel.kt`（改）**

在文件夹所在界面就地出面板：整体进度（两分支：Determinate 带 `progress = { aggregate.ratio }`，Indeterminate 走不定态条）+ 每条任务一行 + 暂停/继续/取消三个按钮。

**(d) 设置页刮削任务区块 —— `ui/settings/SettingsScreen.kt`（改）**

`CloudFolderBatchSummary` 与 `CloudFolderBatchTaskRow` 同样改走共享判形；保留 `totalQueued/totalProcessed` 用于「已处理 N 部」副文本。

### 2.4 验证结果

| 项 | 结果 |
|---|---|
| 括号平衡 / 结构检查（`CloudBrowserScreen.kt`、`SettingsScreen.kt`、两个 repository/runner） | 全部 OK |
| 回调链完整性（逐层 grep 确认，非目测） | 云盘页：`CloudBrowserScreen:275-277` → 面板 `onPause/onResume/onCancel`（`:795-797`）→ 按钮（`:865/872/879`）；设置页：根 `:416-418` → `:2446-2450` 签名 → `:2500-2502` → `:3399-3404` → `:3470-3475` → 按钮 |
| 判形逻辑单测 | **无**——`FolderBatchProgress.kt` 位于 `ui/shared/`，tier1 只收 `tasks/scraper/playback/metadata/util`，该文件不在编译与测试范围内（见「未验证项」） |

---

## 3. 议题三：元数据补全点停止后卡住

### 3.1 根因

`MovieMetadataRefreshRunner` 的取消是**协程级硬中断**：`runInner` 里那条「全部完成 → `isRunning = false`」的赋值永远不会被执行到。于是：

- 界面永久停在「正在补全 3/40」；
- `start()` 开头的 `if (isRunning) return` 会把之后**每一次**点击都吃掉，表现就是「停止后一直卡在那个状态，没法再开始」。

### 3.2 改动点（`data/repository/MovieMetadataRefreshRunner.kt`）

在 `CancellationException` 分支里补上内存状态落盘：

```kotlin
val interruptedTotal = _state.value.total
val interruptedCurrent = _state.value.current
_state.update {
    it.copy(
        isRunning = false,
        message = if (cancelRequested) {
            "已停止补全：已处理 $interruptedCurrent/$interruptedTotal 部，已写入的字段保留"
        } else {
            "补全被中断：已处理 $interruptedCurrent/$interruptedTotal 部，下次开始会重新扫描缺口"
        }
    )
}
```

两个设计点：

- **进度保留不清零**：那一批里已经写进去的字段是真的落库了，清零反而让用户以为白跑一趟；
- **文案区分「用户停的」与「被系统中断的」**：后者下次会自动重新扫描缺口，前者需要用户自己再按一次开始。

### 3.3 验证结果

| 项 | 结果 |
|---|---|
| 结构检查 | OK |
| 行为验证 | **未验证**（需真机/仪器测试触发取消路径，见「未验证项」） |

---

## 4. 验证总表

| 验证手段 | 覆盖范围 | 结果 |
|---|---|---|
| `bash work/compile-pure-jvm.sh`（tier1） | `tasks/` `scraper/` `playback/` `metadata/` `util/` 下 58 个纯 Kotlin 文件 | **exit 0** |
| JUnit（同上脚本内置） | 29 个测试类 | **Tests run: 268, Failures: 5**（5 个均为基线已有编号解析用例失败，与本次改动无关） |
| `work/brace2.py` 括号/结构检查 | 本轮改动涉及的 63 个 kt | 61 OK / 2 误报（字符字面量，已定位） |
| 回调链 grep 核对 | 议题二的两处 UI 按钮链 | 全部接通 |
| 符号核对（逐个人工） | 本轮新增的 public API 调用点（`getScrapeChainMode` / `saveScrapeChainMode` / `ScrapeChainMode.DEFAULT` / `SettingsSwitchRow` 四参数 / `uiState.scrapeChainMode` / `savedMessage: String?`） | 全部一致 |

### 4.1 未验证项（显式列出）

| 项 | 为什么没验证 | 怎么补 |
|---|---|---|
| `assembleDebug` / `assembleRelease` | 本机无 Gradle、无 Android SDK、无 NDK | 在有 Android SDK 的机器跑 `./gradlew assembleDebug` |
| 议题二、三的**行为**验证（真机上点暂停/继续/取消、点停止） | 需要真机或仪器测试 | 真机手测：添加文件夹 → 观察整体进度 → 暂停 → 继续 → 取消；元数据补全 → 开始 → 停止 → 再开始 |
| 桃链在**真实站点**上的取数效果 | 需要联网访问目标站，且站点有反爬/地区限制 | 真机跑一部片的自动刮削，看日志里「桃链」前缀的取数记录 |
| `FolderBatchProgress.kt` 的单测 | 位于 `ui/shared/`，不在 tier1 收集范围（该目录依赖 Android/Room 实体） | 若要覆盖，需把 `ui/shared` 纳入 tier1 或补 Room 实体 stub |
| `StrmScrapeRepository` 等 data 层的编译 | tier1 明确排除 `import android.*` / `import com.example...data.*` 的文件 | 同上，需 Android SDK |

---

## 5. 本轮文件清单

### 5.1 新增（29）

**scraper 核心（20）**
`PeachFailureReason.kt` `PeachContentRoute.kt` `PeachSourceCatalog.kt` `PeachScrapeChain.kt` `ScrapeBudget.kt` `ScrapeByteMeter.kt` `ScrapeMissCache.kt` `ScrapeChainMode.kt` `PeachCoverPick.kt` `PeachCoverProbe.kt` `PeachFieldMerge.kt` `PeachJson.kt` `PeachParseResult.kt` `PeachCodeIdentity.kt` `AvbaseParser.kt` `AvbaseScraper.kt` `Fc2Parser.kt` `Fc2Scraper.kt` `OnePondoParser.kt` `OnePondoScraper.kt`

**UI（1）**
`ui/shared/FolderBatchProgress.kt`

**测试（8）**
`PeachScrapeChainTest.kt` `ScrapeBudgetTest.kt` `ScrapeMissCacheTest.kt` `PeachContentRouteTest.kt` `PeachCoverPickTest.kt` `PeachFieldMergeTest.kt` `PeachSourceParserTest.kt` `PeachAttemptWiringTest.kt`

### 5.2 修改（18）

`AppSettingsRepository.kt`（+`getScrapeChainMode`/`saveScrapeChainMode`/`KEY_SCRAPE_CHAIN_MODE`）
`StrmScrapeRepository.kt`（桃链接线 + legacy 拆段）
`ScrapeRouteRules.kt`（`routeForPeach`/`settlesPeach`/`hasAnyValue`）
`ScrapeAuthority.kt` / `ScrapeFailure.kt` / `ScrapeModels.kt` / `ScrapeSourceChain.kt` / `ScrapeSourceInterval.kt` / `ScraperResponseGuard.kt`（配合上述能力）
`CloudFolderBatchTaskRepository.kt` / `CloudFolderBatchTaskRunner.kt` / `CloudVideoTaskRepository.kt` / `CloudVideoTaskRunner.kt`（任务操作）
`MovieMetadataRefreshRunner.kt`（停止态）
`CloudBrowserScreen.kt` / `CloudBrowserViewModel.kt` / `SettingsScreen.kt` / `SettingsViewModel.kt`（UI 接线）

---

## 6. 与上一轮（mpv 交付）的关系

本轮**不改动** mpv 相关代码（`mpv/`、`playback/engine/`、`app/src/main/cpp/`、`scripts/fetch-libmpv.sh`、`.github/`、`jniLibs/` 全部未动）。mpv 双播放器方案保持上一轮交付状态，本轮只在其之上叠加刮削侧改动。
