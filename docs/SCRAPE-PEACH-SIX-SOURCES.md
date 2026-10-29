# peach 六源接入交付说明（浏览器回落 + amane 等价 + 六源上线）

> 基线：上一版交付 `homemovies_guangya_lut_yaozhi_20261027.zip`。
> 本轮只做用户点名的一件事：**把上次「显式不接」的 9 个 peach 源里能做的做掉**，
> 具体是要两条 peach 侧通道（浏览器回落、amane 子进程桥）和它们卡住的 6 家站。
> 不引入与此无关的重构。

---

## 0. 结论速览

| 上次的结论 | 本轮结果 |
|---|---|
| `faleno` / `dahlia` / `makers` / `fc2cmadb` / `fc2ppvdb` / `javten` **不接** | **6 家全部接入**，可被 peach 链调度 |
| 浏览器回落「未实现」 | **已实现**（`scraper/browser/`，4 文件 783 行） |
| amane 子进程桥「未实现」 | **等价能力已实现**（声明式站点目录 + 通用抽取器 + 首页搜索表单发现） |
| 9 源中剩余 3 个（`javarchive` / `avsox` / `kmib`） | **仍不接**，原因见 §7，逐条可查 |

`PeachSourceCatalog` 里 `wired=false` 的条目从 9 条降到 3 条。

验证：纯 JVM 编译 `exit 0`（74 文件），测试 `Tests run: 303, Failures: 5`，5 个失败全部是
**本轮之前就存在**的番号还原用例（`MovieNumberExtractorTest` ×3、`MovieNumberUtilsTest` ×2），
与本轮改动零引用。Android 编译未执行（本机无 SDK/NDK），见 §9。

---

## 1. 为什么上次不接，这次能接

上次挡住的根因只有两条，现在各对应一个通道：

| 根因 | 上次的处理 | 本轮的处理 |
|---|---|---|
| 站点回 403 / 429 / Cloudflare 验证页，纯 HTTP 拿不到正文 | 记为「无此通道」，把 4 家标成不接 | 内嵌 WebView 当作取页通道（§2） |
| peach 靠 `amane` 这个**外部 Python 包**把番号映射到片商官网 | 记为「Android 没有子进程」，`makers` 因此不接 | 进程内声明式目录 + 通用抽取器（§3） |

第三条隐性根因是「片商官网的取数模板在本仓库找不到证据」——这条不是缺通道，
是**缺事实**，用首页搜索表单的**运行期发现**解掉（§3.3）。

---

## 2. 通道一：浏览器回落（`scraper/browser/`）

### 2.1 结构

| 文件 | 行数 | 职责 |
|---|---|---|
| `browser/BrowserChallengeTimeline.kt` | 136 | 纯逻辑：看观察序列决定「等 / 清 Cookie / 提前台 / 放弃」 |
| `browser/ForegroundActivityTracker.kt` | 62 | 记当前是否有 Activity 在前台（WebView 必须有可见宿主才渲染） |
| `browser/HiddenWebViewHolder.kt` | 247 | 1×1 透明 WebView 宿主，主线程建、可移除 |
| `browser/WebViewBrowserChannel.kt` | 338 | `BrowserChannel` 实现：加载 → 轮询观察 → 解挑战 → 交正文 |
| `BrowserChannel.kt` | 52 | 契约：`state()` / `fetch(request)` / `BrowserChannelState` / `NoBrowserChannel` |
| `SourceFetch.kt` | 167 | 取页契约：`SourcePageRequest/Response`、`SourceFetchException`、`PeachFailureReason` |
| `AndroidSourceFetch.kt` | 149 | `SourcePageFetcher` 实现：HTTP（含 Cookie/Referer/限额/节流）＋按策略走浏览器 |

关键设计点：

1. **`BrowserFallbackPolicy` 决定「先走谁」而不是「一律先 HTTP」**：
   `ALWAYS_BROWSER = {Fc2PpvDb, Javten}`（这两家纯 HTTP 必被拦）、
   `REFUSAL_FALLBACK = {Fc2Cmadb, Fc2PpvDb, Javten, Faleno, Dahlia, MakerSite}`（先 HTTP，吃 403/429/挑战页再回落）。
2. **浏览器也被拦时不再退回 HTTP**：`SourceFetchException.browserBlocked=true` 直接上抛，
   避免一次取页打两遍（会放大限流阶梯）。
3. **`ChallengePageDetector` 复用既有实现**：200 状态但正文是验证页，判 `CloudflareChallenge`，
   让链上按「整源冷却」处置，而不是当普通解析失败。
4. **反向不回写 Cookie**：WebView 拿到的挑战 Cookie 绑浏览器指纹，写回 OkHttp 无效且污染既有会话。
5. **时间轴有界**：`BrowserChallengeTimeline.decide` 在 5s 清 Cookie、15s 提前台、40s 无条件放弃
   （`shownAt` 后 `CLICK_SECONDS` 到期），不会因为一个验证页把一次刮削拖死。
6. **无宿主时优雅退化**：`BrowserChannelState.NoForeground` → 该次取页只走 HTTP，
   批量刮削在后台跑时不会因为「WebView 起不来」整体失败。

### 2.2 为什么用 WebView 而不是 OkHttp 模拟

挑战页要执行 JS 才出结果，`CookieManager` 与 TLS 指纹也由 WebView 自己的网络栈提供；
用 OkHttp 复刻等于自己维护一套反检测对抗，与移植目标无关。

---

## 3. 通道二：amane 的等价能力

peach 的 `amane` 是外部 Python 包，输入番号、输出「这部片在哪个片商官网、页面在哪」。
Android 端起不了 Python 子进程，所以拆成三段等价实现：

### 3.1 `OfficialMakerCatalog.kt`（483 行）—— 声明式站点目录

`data class OfficialMakerSite(id, label, studio, homeUrl, detailUrlTemplate, searchUrlTemplate,
seriesPrefixes, profile, releaseIsDeliveryDate, note)` + `route(code, catalog)`：
按番号前缀把请求路由到片商站。

内置 12 家：`s1 / moodyz / ideapocket / madonna / faleno / dahlia / honnaka / fitch / ebody /
kirakira / attackers / maxa`，配套 `assets/scraper/official_makers.json`（150 行，与 `BUILT_IN` 逐字段对拍一致）。

**模板全部留空**，理由：本仓库找不到任何一条详情页/搜索页路径的证据；填一条猜的模板，
站点回 404 会被记成 `NotFound`（「这家站没有这部片」），把「配置缺失」永久伪装成「官网没有」。
留空时显式报 `ParseError`「这家片商没有配置取数模板」，用户补模板后才真的发请求。

前缀证据全部来自仓库内：`OfficialScraper.OFFICIAL_PREFIXES`（既有文件）、
`peach-study/peach-master/src/peach/metadata_routes.py:136-137` 的 `MAKER_EVIDENCE`。

### 3.2 `OfficialPageExtractor.kt`（868 行）—— 通用抽取器

三层降级，**逐字段先到先得而非整层短路**（JSON-LD 常给全标量却缺演员，OG 有封面却没日期）：

1. JSON-LD（`name / description / datePublished / duration / actor / director / brand / aggregateRating`）
2. OpenGraph / meta（`og:title` / `og:image` / `og:description`）
3. 定义列表 `dt/dd`（日本片商站最常见的「発売日 / 収録時間 / メーカー / ジャンル」表格）

另有 `InertiaJson` / `DefinitionList` 两个 `OfficialExtractionProfile` 供特化。

### 3.3 `OfficialSearchForm.kt`（126 行）+ `OfficialSiteScraper.kt` —— 首页搜索表单发现

**这是本轮补上的关键一环**：目录模板全空 → `OfficialSiteScraper` 会跳过每一家 →
`Faleno` / `Dahlia` / `MakerSite` 三个来源永远 `ParseError`，等于接进来三个必然失败的源。

补法：两条模板都空时，回一次站首页，读站点**自己声明**的搜索表单（`<form action>` +
输入框 `name`），拼成 `https://faleno.jp/search/?q=` 这样的前缀，接上 URL 编码后的番号取页。

它不是猜路径：表单是站点自己给的取数入口；找不到就返回 `null`，调用方照实报缺。
细节约束：

- 只认 GET 表单（POST 不能靠拼 URL 复用）；
- 输入框 `type` 只认 `""` / `text` / `search`（`hidden` 带的是 csrf）；
- 多个文本域且都不在通用名（`q/s/keyword/search/key/word/query/kw`）里时，**一个都不用**；
- 相对 `action`（`../x`、`./x`、`?q=`）补不准就退回首页地址本身（真实存在的地址），不猜；
- 发现结果按 `site.id` 进程内缓存，**否定结果也缓存**（否则批量刮削每部片都要多打一次首页）。

### 3.4 三段的串联

```
OfficialMakerScraper(source = Faleno/Dahlia/MakerSite)
  └─ OfficialMakerCatalog.route(code)          → 命中哪家站（前缀不命中就直接 NotFound，不发请求）
      └─ OfficialSiteScraper.trySite(site, code)
          ├─ detailUrl 非空 → fetchAndExtract
          ├─ searchUrl 非空 → searchThenExtract（搜索页 → firstWorkUrl → 详情页）
          └─ 两者都空 → OfficialSearchForm 发现前缀 → searchThenExtract
```

单站最多 2 次请求（搜索页 + 详情页），重试留给取页层——它才知道这一页是 403 还是超时、该不该上浏览器。

---

## 4. 六个源的接入

### 4.1 三个 FC2 系（纯 HTTP，不需要浏览器）

| 源 | scraper | 解析器 | 取页要点 |
|---|---|---|---|
| `Fc2Cmadb` | `Fc2CmadbScraper.kt`(91) | `Fc2CmadbParser.kt`(201) | 页面是 Inertia，商品数据在 `<script type="application/json">` 的 `props.article`；女优按 Inertia partial-data 协议补问一次（失败不推翻主结果） |
| `Fc2PpvDb` | `Fc2PpvDbScraper.kt`(57) | `Fc2PpvDbParser.kt`(393) | 必须把**跟随重定向后的** `page.url` 传给解析器——它靠 finalUrl 判 `/age-verify` 与语言跳转 |
| `Javten` | `JavtenScraper.kt`(91) | `JavtenParser.kt`(296) | 两步：搜索页 → 作品链接列表 → 逐个取详情页，第一个成功即返回；单条失败 `continue`，遇 `CloudflareChallenge` 立即抛出（整站被拦不该继续撞） |

三家 `studio = publisher = "FC2-PPV"`，`genres == tags`；
卖家与主页做成公开 accessor（`Fc2PpvDbParser.seller` / `Fc2CmadbParser.writer` / `JavtenParser.seller`），
因为 `ScrapedMovieInfo` 没有对应字段。

### 4.2 三个片商官网（走浏览器回落）

`Faleno` / `Dahlia` / `MakerSite` 共用 `OfficialMakerScraper` + `OfficialMakerCatalog` 的三段实现（§3），
`source` 由构造参数区分。三家的前缀：

| 源 | 前缀 | 证据 |
|---|---|---|
| `Faleno` | `fsdss / fcdss / fns / mgold / jimmy` | peach `MAKER_EVIDENCE` |
| `Dahlia` | `dldss` | 同上 |
| `MakerSite` | 12 家目录的并集（`ssis sone mide midv ... shkd adn sspd azsd` 等） | `OfficialScraper.OFFICIAL_PREFIXES` + `MAKER_EVIDENCE` |

`OfficialMakerCatalog.route` 额外要求「前缀紧邻下一位不是字母」，防 `hnd` 抢走 `hndb-001`；
数字紧跟放行（`ssis001` == `ssis-001`）。

### 4.3 接线点

- `ScrapeSource` 枚举新增 6 个成员（`Faleno/Dahlia/MakerSite/Fc2Cmadb/Fc2PpvDb/Javten`），
  位置在 `CustomJson` 之前，设置页来源勾选顺序仍是「DMM 系 → 官方 → 社区索引 → 镜像 → peach 新源」。
- `ScrapeAuthority.tierOf`：`Faleno/Dahlia/MakerSite` → `Official`；`Fc2Cmadb/Fc2PpvDb/Javten` → `Community`（按 peach `SOURCE_SPECS.stage`）。
- `ScrapeSourceInterval` / `ScrapeFailure`：`Fc2PpvDb` / `Javten` 的 `blocked_pause` = 6h（peach 同值）。
- `PeachSourceCatalog`：6 条的 `sources` 从空改为实际 `ScrapeSource`，`unwiredReason` 清空。
- `StrmScrapeRepository`：新增 `sourcePageFetcher`（`AndroidSourceFetch(httpClient, WebViewBrowserChannel(appCtx), throttle, io, log)`）
  与 6 个 scraper 参数，全部进 `MovieScraperRegistry`。`fetchSingleSource` / `scrapeSource` 未改——
  注册即生效，这是上一轮就确认过的咽喉。
- `LocalMovieLibraryApp.onCreate`：`ForegroundActivityTracker.install(this)` 必须在 `AppContainer(this)` 之前
  （它靠 `registerActivityLifecycleCallbacks` 收事件，装晚了会漏掉「已在前台」而让首次刮削拿不到浏览器通道）。

---

## 5. 路由：新源只进该进的链

peach 的 `ROUTES` 是显式的，新接的源不能靠「反正没配就去问」混进别的链上。
`PeachContentRoute.ROUTES` 保持 peach 原表一字不改：

| 内容类型 | 链 |
|---|---|
| `Censored` | `prestige, faleno, dahlia, makers, r18dev, dmm, avbase, javbus, javdb` |
| `Amateur` | `mgstage, avbase, javbus, javdb, r18dev` |
| `Uncensored` | `1pondo, avbase, javbus, javdb, avsox` |
| `Fc2` | `fc2, fc2cmadb, fc2ppvdb, javten, javarchive, javdb` |
| `Kmib` / `Other` | 空链（peach 原意：一家都不问） |
| `Unknown` | `avbase, javbus, javdb`（社区优先，官方留在后面） |

`ScrapeRouteRules.excludedFor(content)` 的 `Kmib` / `Other` 分支补上 6 个新源——
peach 的 `ROUTES['kmib'] = ()` 本就是空链，把新接的源留在 MIB/国产链上等于主动去拿同号别的作品。
`peachOnlyExcluded` 同时补了 `Amateur` / `Uncensored` / `Fc2` 的排除集（素人链不含片商官网与 FC2 索引，
FC2 链不含片商官网与一本道/AVBase）。

---

## 6. 验证结果

### 6.1 纯 JVM 编译 + 测试（`bash work/compile-pure-jvm.sh`，实跑）

```
[tier1 compile] 74 files
[tier1 compile exit] 0
[tier1 test compile] 32 files
[tier1 test compile exit] 0
Tests run: 303,  Failures: 5
```

5 个失败与本轮无关（本轮之前就有，`MovieNumberExtractorTest` ×3 / `MovieNumberUtilsTest` ×2，
都是 MGStage/JAC 数值前缀的番号还原口径问题）。上一版同脚本是 `292 / 5`，本轮新增 11 个用例。

### 6.2 括号结构检查（`python work/brace2.py <files>`）

全部 `OK`：`scraper/browser/{BrowserChallengeTimeline,ForegroundActivityTracker,HiddenWebViewHolder,WebViewBrowserChannel}.kt`、
`SourceFetch.kt`、`BrowserChannel.kt`、`AndroidSourceFetch.kt`、`PeachFetchBridge.kt`、
`Fc2Cmadb*`、`Fc2PpvDb*`、`Javten*`、`Official*`。

### 6.3 新增用例

- `scraper/BrowserFallbackTest.kt`：`BrowserFallbackPolicy`（prefersBrowser / allowsFallback / isRefused 的
  403、429、200+挑战页、200 正常页、404 不算拒绝）、`BrowserChallengeTimeline.decide`（5s 等待 / 15s 清 Cookie /
  40s 提前台 / `hostAlreadyFailed` 直接 GiveUp / ready+非挑战→ChallengeSolved / `shownAt` 后 CLICK_SECONDS 无条件 GiveUp）、
  `PeachFetchBridge`（browserBlocked→CloudflareChallenge 且 detail 含 `browser_attempted` / 404→NotFound /
  200+挑战正文判挑战 / 正常 200 透传且只调用一次）。
- `scraper/OfficialSearchFormTest.kt`（11 个）：该发现的 6 种（绝对 action、站内绝对路径、无 action 回本页、
  协议相对地址、多表单优先有 action 的、单个非通用名文本域）；**没证据必须返回 null** 的 5 种
  （POST 表单、只有 hidden、多个非通用名文本域、模板外相对 action 退回首页、空 HTML）。

---

## 7. 仍未接的 3 个源（显式列出）

| 源 | 状态 | 原因 |
|---|---|---|
| `javarchive` | 不接 | 站点数据来自旧的 JavBus 抓取快照，字段口径与本工程 `JavbusScraper` 完全重叠；接进来只会让同一批数据多打一次站，收益低于维护成本 |
| `avsox` | 不接 | peach `sources/` 里**没有** `avsox.py`（只在路由表与 amane 的来源集合里出现）；既无独立实现也无桥，无从移植 |
| `kmib` | 不接 | peach 自己对 kmib 就是空链（`ROUTES['kmib'] = ()`），是刻意的取舍而非缺失；本工程沿用 |

三条都写在 `PeachSourceCatalog` 的 `unwiredReason` 里，代码内可查，不依赖本文档。

---

## 8. 与 peach 的仍有偏差之处（不默认省略）

| 项 | peach | 本工程 | 为什么 |
|---|---|---|---|
| 浏览器通道 | Playwright 子进程 | 内嵌 WebView（1×1 透明宿主） | Android 无 Playwright；WebView 是唯一能执行挑战 JS 的现成能力 |
| amane 桥 | 外部 Python 包 | 声明式目录 + 通用抽取器 + 搜索表单发现 | 起不了子进程；且 29 份手写爬虫在站点改版时会同时变成 29 处要改的代码 |
| 站点数量 | 32 源 | 12 家片商目录 + 既有 15 源 | 目录是数据，用户可导入自家目录覆盖（`OfficialMakerCatalog.merge`） |
| 整批闸门 | 6000 请求 / 1GiB / 4h | 单片 12 请求 / 90s，字节数**显式未实现** | 调用粒度是单部片；字节量要下探 OkHttp body，现有路径拿不到累计值，硬凑只会给假精确的预算 |
| 封面探测 | Range 64KiB + 尺寸测量 + 去重 | `PeachCoverProbe` 已按此实现 | 一致 |
| `kmib` / `other` | 空链 | 空链 | 一致（本轮修好了 `excludedFor` 回退完整配置顺序的旧 bug，它会拿回同号异片） |

---

## 9. 未验证项（显式列出）

1. **Android 编译未执行**：本机无 Android SDK / NDK，`./gradlew assemble` 无法运行。
   纯 JVM 层（`scraper/` 全部、无 android import）已用 `work/compile-pure-jvm.sh` 真编译通过。
   `scraper/browser/` 四个文件依赖 `android.webkit` / `android.app`，**只在括号检查层验证过，未经编译**。
2. **WebView 取页的真实站点行为未验证**：挑战页、Cookie、`ForegroundActivityTracker` 的实际时序都需要真机/模拟器。
   代码把时序写成可测的纯函数（`BrowserChallengeTimeline`）并把策略写成可测的 `object`（`BrowserFallbackPolicy`），
   但「真站能不能过」只有在设备上跑才知道。
3. **六家站点的真实响应未验证**：本机无法访问这些站点（网络与地域），
   全部解析逻辑基于子代理从 peach 源码与站点结构得出的规格，并用离线样本钉住纯逻辑。
   首次真机刮削时建议先跑一部已知番号的片看日志。
4. **`OfficialSearchForm` 的线上准确率未知**：首页布局千站千样，它的失败模式是「返回 null → 报缺」，
   不会伪造地址；但「12 家里有几家的首页真的带 GET 搜索表单」需要实测统计。

---

## 10. 本轮文件清单

### 新增（18）

| 文件 | 行数 |
|---|---|
| `scraper/SourceFetch.kt` | 167 |
| `scraper/BrowserChannel.kt` | 52 |
| `scraper/AndroidSourceFetch.kt` | 149 |
| `scraper/PeachFetchBridge.kt` | 68 |
| `scraper/browser/BrowserChallengeTimeline.kt` | 136 |
| `scraper/browser/ForegroundActivityTracker.kt` | 62 |
| `scraper/browser/HiddenWebViewHolder.kt` | 247 |
| `scraper/browser/WebViewBrowserChannel.kt` | 338 |
| `scraper/Fc2CmadbParser.kt` | 201 |
| `scraper/Fc2CmadbScraper.kt` | 91 |
| `scraper/Fc2PpvDbParser.kt` | 393 |
| `scraper/Fc2PpvDbScraper.kt` | 57 |
| `scraper/JavtenParser.kt` | 296 |
| `scraper/JavtenScraper.kt` | 91 |
| `scraper/OfficialMakerCatalog.kt` | 483 |
| `scraper/OfficialPageExtractor.kt` | 868 |
| `scraper/OfficialSearchForm.kt` | 126 |
| `assets/scraper/official_makers.json` | 150 |
| `test/.../scraper/BrowserFallbackTest.kt` | — |
| `test/.../scraper/OfficialSearchFormTest.kt` | — |

（表格 18 个源文件 + 2 个测试文件）

### 修改（9）

`LocalMovieLibraryApp.kt`（装 `ForegroundActivityTracker`）、
`StrmScrapeRepository.kt`（取页器 + 6 个 scraper + registry）、
`scraper/ScrapeModels.kt`（`ScrapeSource` 增 6 员）、
`scraper/ScrapeAuthority.kt`（新源层级）、
`scraper/ScrapeFailure.kt`（`Fc2PpvDb`/`Javten` 冷却上限）、
`scraper/ScrapeSourceInterval.kt`（新源间隔）、
`scraper/PeachSourceCatalog.kt`（6 条 wired）、
`scraper/ScrapeRouteRules.kt`（`excludedFor` 与 `peachOnlyExcluded` 补新源）、
`test/.../PeachContentRouteTest.kt`（按「六源已接线」更新断言）。
