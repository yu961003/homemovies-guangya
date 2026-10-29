# 刮削来源与顺序：peach / stash 对比及鸭影的取舍

> 本文回答「peach 和 stash 各自用哪些来源、按什么顺序刮削、两者异同在哪」，并逐条说明鸭影吸收了什么、改了什么、放弃了什么。
> 所有对 peach / stash 的描述都来自源码勘察，行号附在每条后面。两份参考实现只有只读，未改动。

---

## 1. 一句话对比

| | peach | stash |
| --- | --- | --- |
| 来源清单从哪来 | **代码里的固定表** `ROUTES`（按内容类型分七条链） | **用户拖拽出来的有序数组** `Options.Sources` |
| 顺序由谁决定 | 程序按番号类型选链，链内顺序写死 | 用户拖拽决定，服务端原样保序 |
| 内置站点 | 约 20 个 JAV 站（prestige/faleno/dahlia/makers/r18dev/dmm/avbase/javbus/javdb/mgstage/1pondo/fc2 系…） | **只有 2 个**：`builtin_freeones`（演员）、`builtin_autotag`（无网络，贴库内已有 ID） |
| 一条链问到什么时候停 | 必填标量齐了才停，且官方档之间会为了「类别」多问一家 | 第一家返回非空就停，**只取 `results[0]`** |
| 多来源结果如何处理 | **按字段跨源合并**，每字段再按层级/来源表定胜负 | **不合并**，单源胜出 |
| 多条候选怎么办 | 不看候选数（每家只取一条） | `SkipMultipleMatches`：多结果 → 整条跳过 + 可打标签留人工 |
| 限流 | 按站最小间隔（javdb 3s / 其余 2s）+ 三闸门 + 指数冷却 | 无 |

---

## 2. peach：来源清单写死在代码里，按内容类型分链

### 2.1 七种内容类型

`peach-master/src/peach/metadata_routes.py:32`

```
CONTENT_TYPES = ("censored", "amateur", "uncensored", "fc2", "kmib", "other", "unsure", "unknown")
```

`unknown` 是番号认不出、`other` 是认得出但没有站点可问（国产/欧美）、`unsure` 是形状像有码但本机证据对不上。

### 2.2 每条链长什么样

`metadata_routes.py:58-110` 的 `ROUTES`：

| 类型 | 链 | 这条链**故意不含**谁，以及原因 |
| --- | --- | --- |
| censored（有码） | prestige → faleno → dahlia → makers → r18dev → dmm → avbase → javbus → javdb | — |
| amateur（素人） | mgstage → avbase → javbus → javdb → r18dev | 不含 DMM：实测 `MIUM 1239`、`LUXU 1475` 在 DMM 零结果，素人号不在它目录上 |
| uncensored（无码） | 1pondo → avbase → javbus → javdb → avsox | 不含 r18dev：无码号在 r18.dev 上不存在 |
| fc2 | fc2 → fc2cmadb → fc2ppvdb → javten → javarchive → javdb | 不含 avbase/javbus：1213 份来源证据里这两家对 FC2 一份都没给过 |
| kmib（韩国 MIB） | **空** | JAV 目录站按同形番号会返回别的作品，错值只能靠人认 |
| other（国产/欧美） | **空** | 同上 |
| unsure | avbase → javbus → javdb | 只问综合索引一遍 |
| unknown | **空** | — |

值得注意：**每条链最有信息量的部分是「谁不在这条链上」**。省请求靠的不是把常成功的往前排，而是干脆不问那些按该类型必然返回错值或必然搜不到的站。

### 2.3 链内还叠了一层「本机证据」过滤

`metadata_routes.py:262-283` `route_for_code`：无码链上的 1pondo 要本机证据指着一本道才问；有码链上 Prestige/FALENO/DAHLIA 只在证据认得是自家番号时才问，且 `MAKER_EVIDENCE` 里三家有一家认了就不再问 `makers`。

### 2.4 什么时候停（`settles`）

`metadata_routes.py:357-374`，三条依次判断：

1. 用户要类别（`wants_tags`）、当前结果没有 tags、且下一档仍属官方档 → **不结算**，多问一家。理由：FALENO/DAHLIA 官网本来就不给类别。
2. 没有必填项 → 结算。
3. 否则 `all(field in given for field in required)`，`required` 是还缺的必填标量。

`SCALAR_FIELDS = ("title", "performers", "studio", "release_date")`（:151），`LIST_FIELDS = ("tags", "cover_url")`（:155），`LIST_FIELD_DEPTH = 3`（:156，注释写明「3 是下限不是上限」）。**列表字段（标签、封面）不参与短路**。

### 2.5 综合索引那一档故意不逐家短路

`metadata_routes.py:112-128` 的分档：`COMMUNITY_STAGE = ("avbase", "javbus", "javdb")`。这一档不逐家短路的原因写在注释里——免复核要两家一致、封面互证要两个图源，问到第一家就停等于把判据样本降到一家。

### 2.6 结算侧是另一根轴（`metadata_policy.py`）

查询侧答「问谁、什么顺序」，结算侧答「同一字段几个来源都给了，听谁的」：

- 层级 `CHAIN_LOCAL_NFO(0) < CHAIN_OFFICIAL(1) < CHAIN_PREFERRED(2) < CHAIN_COMMUNITY(3) < CHAIN_FALLBACK(4) < CHAIN_UNKNOWN(5)`（:114-115）
- `PREFERRED_COMMUNITY_SOURCE = "javdb"`（:99），理由逐字：用户逐条核对过 javdb 比 javbus 准（`n0780` 片长 javbus 报 36 分、javdb 报 96 分，盘里那条 98 分）
- `FALLBACK_SOURCES = ("javbus",)`（:105），理由：javbus 取值常来自另一部片（`259LUXU-891` 它答的是 `259LUXU-1891`）
- `LOCAL_NFO_SOURCE = "local_nfo"` 排最前——用户自己整理的 NFO 优先于一切联网来源
- 排序键 `(source_tier, within, name)`（:163-176），层内再用 `FIELD_SOURCE_ORDER` 按字段排一次

**注意一个反直觉的地方**：javbus 在结算上是兜底（排很后），但在查询顺序上排在 javdb 前面。原因是 javdb 按出口 IP 计配额、主机间隔 3 秒，先问最贵的站等于先用完额度。

### 2.7 限流与冷却

- `SOURCE_INTERVALS = {'javdb.com': 3.0, 'jdbstatic.com': 3.0}`，其余默认 2.0 秒
- 三闸门按任务计：`MAX_SOURCE_REQUESTS=6000` / `MAX_SOURCE_BYTES=1GiB` / `MAX_SOURCE_SECONDS=4h`
- 冷却：`FIRST_BLOCKED_PAUSE = 900`（15 分钟起），403 档 `min(900 * 2**(blocks-1), limit)`，429 档 `max(retry_after, 900)`；上限 javdb 24h、其余 6h
- 「说过没有」记忆 `_MissCache`：只记 NotFound，TTL 7 天，键是站名

### 2.8 用户覆盖

写法 `censored=r18dev,javdb;fc2=fc2`，**整条替换而不是逐项合并**。理由写在 :248-259：逐项合并的表现是「删不掉一家」——用户想摘掉 javdb 得先知道内建表里有它；整条替换让设置里那一行自己就是答案。

---

## 3. stash：来源清单是用户拖出来的，串行问、第一家赢

### 3.1 内置来源只有两个

- `builtin_freeones`（`pkg/scraper/freeones.go:10`）：内嵌 YAML 字符串常量，**只支持演员**（`performerByName` / `performerByURL`）
- `builtin_autotag`（`pkg/scraper/autotag.go:16`）：纯 Go、无网络（`supportsURL` 直接 `return false`），只把库内已有 performer/tag/studio 的 id 写成 `StoredID`

全仓 `grep "builtin_"` 只有三处命中（freeones.go:10、autotag.go:16、IdentifyDialog.tsx:31）。**没有 `builtin_javdb`/`fansdb` 之类**——那些是用户把 CommunityScrapers 的 `.yml` 丢进 scrapers 目录，`id` 取文件名去扩展名（`definition.go:239-247`）。

### 3.2 顺序：有序数组 + 串行 for + 首个命中 return

`internal/identify/options.go:18-20` 的注释就是答案：

```go
type Options struct {
	// An ordered list of sources to identify items with. Only the first source that finds a match is used.
	Sources []*Source `json:"sources"`
```

唯一消费点在 `internal/identify/identify.go:102-129`：

```go
for _, source := range t.Sources {                                 // :104
    results, err := source.Scraper.ScrapeScenes(ctx, scene.ID)     // :106
    if err != nil { logger.Errorf(...); continue }                 // :107-110
    if len(results) > 0 {                                          // :112
        if len(results) > 1 && utils.IsTrue(options.SkipMultipleMatches) {
            return nil, &MultipleMatchesFoundError{Source: source} // :114-117
        } else {
            return &scrapeResult{result: results[0], source: source}, nil // :120-124
        }
    }
}
return nil, nil                                                    // :128
```

全链路没有 goroutine（`grep "go func\|errgroup\|WaitGroup"` 在 `internal/identify/` 与 `task_identify.go` 零命中），上层 `BatchProcess` 也是顺序 for。

### 3.3 顺序从哪来：UI 拖拽

`ui/v2.5/.../IdentifyDialog/Sources.tsx:147-158` 的 `onDragOver` 用 `splice` 搬移数组元素，`:165-171` `onDrop` 提交，`:184` `draggable`，`:195` grip 图标。提交时 `IdentifyDialog.tsx:276-291` 原样保序 map，不重排也不注入默认值。`IdentifyDialog.tsx:242-268` 在没有默认值时给的是「第一个 stash-box + autotag」。

### 3.4 没有全局优先级表

`grep "priority\|Priority" pkg/scraper/ internal/identify/ task_identify.go` **零命中**。`ListScrapers` 只按名字字母序（`cache.go:202-220`），那是展示序不是优先级。

**stash 自己也踩过没有顺序的坑**：`cache.go:312-333` `ScrapeURL` 遍历 `c.scrapers`（Go map 随机序），首个 `supportsURL` 命中即返回，多个 scraper 覆盖同一域名时选谁不确定。

### 3.5 多结果：整条跳过 + 打标签，而不是随便挑一个

`identify.go:114-117` 抛 `MultipleMatchesFoundError`；`Identify` 捕获后整条 scene 跳过，若配了 `SkipMultipleMatchTag` 就给 scene 打标签留给人工复核（:69-86）。

**两个实现坑值得记住**：

1. `options.go:40-41` 注释写「defaults to true if not provided」，但 `pkg/utils/boolean.go:4-6` `IsTrue` 是 `b != nil && *b`，**裸调 API 时实际是 false**。以 UI 路径为准才是 true（`IdentifyDialog.tsx:68`）。
2. 这个开关**对 YAML scraper 永远不触发**：`task_identify.go:312-328` 的 `scraperSource.ScrapeScenes` 恒返回长度 1 的切片，只有 stash-box 源可能返回多条。

### 3.6 字段策略：IGNORE / MERGE / OVERWRITE + CreateMissing

`options.go:50-69`：三策略，默认 `MERGE`（`identify.go:451-460`）。标量走 `shouldSetSingleValueField`（:462-471）：IGNORE 跳过；已有值只有 OVERWRITE 才覆盖。URL 是特例——`hasExistingValue` 硬编码 false（:410-432），MERGE 实际是「追加去重」。

零变更短路 `UpdateSet.IsEmpty()`（`pkg/scene/update.go:29-34`）→ `identify.go:309-312` 什么都不写。封面先取现有字节再 `bytes.Equal` 比对，不同才换（`scene.go:257-281`）。

### 3.7 三条入口互不相通

identify 只走 `sceneByFragment`（`defined_scraper.go:64-71`），**从不试 sceneByURL 也不试 sceneByName**。名字搜索需要 `sceneByName` + `sceneByQueryFragment` 同时存在才算支持（`definition.go:288-290`），因为它是「搜索出候选 → 用户点选 → 再拉详情」的两段式。

### 3.8 没有跨源字段级合并

自动路径首命中直接 return，函数返回后不再碰其他源；手动路径 `currentSource` 是单值（`context.tsx:118`），ScrapeDialog 是「单源结果 vs 当前值」逐字段二选一。

唯一的变通互补是：因为默认 MERGE + 单值字段「已有值就跳过」，**连续跑两次 identify（第一次 A 源、第二次 B 源）能补空字段**。这是跨任务的手工串行，不是单次任务内的合并。

---

## 4. 两者异同

### 4.1 相同的地方

1. **都是串行、都有顺序**，没有一家把来源并发打出去。理由一致：并发打同一批站立刻触发风控。
2. **都有自己的「够了就停」判据**（peach 的 `settles`、stash 的「非空即停」）。
3. **都不在 scrape 阶段合并多来源结果**（stash 完全没有；peach 的合并发生在结算侧，且是逐字段的）。
4. **都记录了「这条数据是哪来的」**（peach 的 ledger、stash 的 `stash_ids`）。

### 4.2 关键差异

| 维度 | peach | stash |
| --- | --- | --- |
| 顺序的**来源** | 程序内建表（按番号类型） | 用户拖拽 |
| 顺序的**稳定性** | 确定（同类型同一份链） | 取决于用户配置，未配置时是字母序展示 + 默认 autotag |
| 会不会**整家不问** | 会（kmib/other/unknown 链为空） | 不会（用户配了什么就问什么） |
| 跨源合并 | **有**，逐字段按层级 | **没有** |
| 多条候选 | 不看 | 整条跳过（stash-box 场景） |
| 失败后怎么办 | 换下一家，冷却 + 指数退避 | `logger.Errorf` + `continue`，无冷却 |
| 限流 | 有（按站间隔、三闸门、指数冷却） | 无 |
| 扩展方式 | 改代码里的表 | 丢一个 `.yml` 进目录 |
| 面向的场景 | **一个重度用户、自己维护的影视库，追求字段最准** | **通用媒体服务器，用户自己决定信谁** |

一句话：**peach 把「哪种番号该问哪几家」当成领域知识写进代码，stash 把它当成用户偏好交给 UI。**

---

## 5. 鸭影实际是什么情况

改造前（第三轮之前）的鸭影：

- 来源是**用户可配的列表**（`getPriorityScrapeSources()`），8 个来源：`Dmm / Dmm2 / Official / Mgstage / Javbus / TheJavDB / CustomJson`，`Priority` 只是「走自动链」的标记
- `ScrapeRouteRules.orderFor()` **只重排不删减**——按番号类型把该优先的往前挪，但一家都不剔除
- `ScrapeSourceChain.merge()` 是**先到非空优先**
- 结算 `settles()` 是 `完成度 >= 0.7 || 必填项齐全`
- 全仓**没有任何按站最小间隔**

三个问题：①素人号会先去问 DMM 和 Official，而这两家必然搜不到（DMM 不收录素人号、Official 的前缀白名单里一个素人厂牌都没有），用户看到的失败其实只是「问错了站」；②用户把 Javbus 配在 TheJavDB 前面时，先到优先会让一份来自别的片的值顶掉更准的值；③批量刮削在一小时内把同一家站敲几百下，间隔不够直接吃 403，然后冷却锁半小时。

---

## 6. 鸭影采纳了什么、改了什么、放弃什么

### 6.1 采纳（4 项，都已落地并带单测）

| 吸收自 | 做法 | 鸭影的落点 |
| --- | --- | --- |
| peach | **按内容类型裁剪来源链**，且每条链显式写出「谁不在这条链上」 | `ScrapeRouteRules.routeFor()` + `RoutePlan`：素人剔 Dmm/Dmm2/Official，无码与 FC2 剔 Official/Dmm/Dmm2/Mgstage，韩国 MIB 与国产只留自定义 JSON |
| peach | **查询轴与结算轴分离**，结算按层级定胜负 | 新增 `scraper/ScrapeAuthority.kt`：`SourceTier` 五层 + `mergeByAuthority()` 逐字段按层级取值 |
| peach | **结算只认还缺的必填标量**，列表字段不参与短路 | `ScrapeRouteRules.settles(info, remainingSources)` 重写：0.7 完成度阈值不再参与判定 |
| peach | **按站最小请求间隔** | 新增 `scraper/ScrapeSourceInterval.kt` + `ScrapeSourceThrottle.kt`：javdb 3s、其余 2s、自定义 JSON 不限速；自动链里生效，点名来源不等 |

### 6.2 调整（4 项）

1. **内容类型判定的依据从「番号 + 本机证据」降为「番号 + 文件名」**。peach 有 ledger 可以直接查「本机有没有这个号的历史证据」，鸭影没有等价物——`movies` 表里只有已入库的片，拿它判「一家一本道」会把没入库的号全判成 unsure。所以鸭影只用番号形状与文件名关键词，宁可偶尔把 unsure 判成 censored。

2. **`orderFor()` 保留不动，另加 `routeFor()`**。原有测试 `orderForKeepsEveryConfiguredSourceForEachContentType` 断言「每种类型长度与集合都不变」，这是对「用户显式配置不该被程序悄悄删掉」的约束。新语义进新 API，两套并存。

3. **裁剪后链为空时回退到配置顺序并记日志，而不是照 peach 那样直接返回空**。peach 的 kmib/other 返回空是因为它有一整条人工流程兜底；鸭影的用户可能只开了自定义 JSON 一家，照抄空链等于「点了刮削什么也没发生」。`RoutePlan.fallbackReason` 就是为这条日志准备的。

4. **「类别缺失且后面还有官方源就多问一家」的条件收窄**。peach 只在「下一档属官方档」时才多问；鸭影的 `OfficialScraper` 其实会解析 tags（`OfficialScraper.kt:88` `genres = tags`），但部分厂牌官网页面确实没有类别区块。所以判据写成「`remainingSources` 里还有 Official/Dmm/Dmm2/Mgstage 就当不够」，比 peach 略宽，代价是偶尔多打一次。

### 6.3 放弃（5 项，附理由）

| 放弃项 | 理由 |
| --- | --- |
| **stash 式的「用户拖拽有序列表 + 首命中即停」作为主路径** | 鸭影已经是「多来源互补」形态（`ScrapeSourceChain.merge` 存在即说明用户期待 A 站给标题、B 站给演员）。改成首命中即停是功能倒退，且 stash 自己那份代码里也没有跨源合并可抄 |
| **stash 的 `SkipMultipleMatches`（多结果整条跳过 + 打标签）** | 鸭影的 JAV 抓取器是按番号查详情页，一个番号对应一个详情页，不存在「多条候选」；`CustomJsonScraper` 的 `resultPath` 取的是单对象。硬加一个永远不触发的开关只会增加配置项噪音 |
| **peach 的三闸门（6000 请求 / 1GiB / 4h）** | 鸭影是单机 App，一次批量刮削的规模是「几百部片子」，离闸门差两个数量级。加闸门需要额外的持久化计数与跨批次语义，收益接近零 |
| **peach 的指数冷却（15 分钟起翻倍，上限 6h/24h）** | 鸭影已有 `ScrapeFailureKind.cooldownMillis`（403 → 30 分钟、限流 → 10 分钟）。叠加翻倍会把一家站锁上几小时，而用户在这个 App 里没有「手动解锁」的入口——被锁住只能等。宁可多试一次 |
| **peach 的 `_MissCache`（NotFound 记忆 7 天）** | 键是站名 + 番号，需要新的持久化表；而鸭影的批量刮削本身就是「用户主动点开始」，重复查同一批号的机会不多。收益不抵一张新表的迁移成本 |

另外两处**本来就不适用**（不是取舍问题）：

- **peach 的 `route_for_code` 本机证据过滤**：依赖 ledger 里的历史来源证据，鸭影没有对应数据源。
- **stash 的 YAML 声明式抓取器**：鸭影的抓取器是手写 Kotlin（每家的解析逻辑差异太大：DMM 走 GraphQL、Official 走前缀白名单 + 搜索页、MGStage 要拼直链再退回搜索），改成声明式等于重写全部抓取器。

---

## 7. 落地清单（本轮新增/改动）

| 文件 | 变更 |
| --- | --- |
| `scraper/ScrapeAuthority.kt` | **新增**：`SourceTier` 五层、`orderByTier`、`mergeByAuthority` |
| `scraper/ScrapeSourceInterval.kt` | **新增**：按来源的固定最小间隔 |
| `scraper/ScrapeSourceThrottle.kt` | **新增**：可注入时钟的计时器（纯逻辑，可脱离协程单测） |
| `scraper/ScrapeRouteRules.kt` | 新增 `RoutePlan` / `routeFor()` / `excludedFor()`；**重写** `settles(info, remainingSources)`，完成度阈值退出判定 |
| `data/repository/StrmScrapeRepository.kt` | 自动链改用 `routeFor()`；`draft` 由 `ScrapedMovieInfo?` 改为 `Pair<ScrapeSource, ScrapedMovieInfo>?` 并走 `mergeByAuthority`；循环内按来源等待最小间隔 |
| `test/.../ScrapeRouteRulesRoutePlanTest.kt` | **新增** 14 个用例（路由裁剪、回退、去重、层级比较、合并取胜） |
| `test/.../ScrapeRouteRulesSettlesTest.kt` | **新增** 5 个用例（必填标量、类别例外、发行日两种来源） |
| `test/.../ScrapeSourceThrottleTest.kt` | **新增** 9 个用例（两档间隔、独立计时、时钟回拨、reset） |
