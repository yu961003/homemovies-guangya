# 鸭影 · 批量刮削修复 + 四模块增强 · 交付说明

> 交付物：完整源码 zip（不含 APK、不含 `work/` 构建缓存）。
> 基线：`GY20260911_145726.zip` → 本仓库工作副本。
> 环境限制：本机无 JDK/Gradle/Android SDK，**未执行 `:app:assembleDebug`**（见 §5 验证方式）。

---

## 1. 批量刮削 Bug：根因与修复

方案文档基于 `GY20260911_145726.zip` 写成。逐条比对当前源码后，**A1/B1/C1/C3 四条在该基线之后已被修掉**（`prepareForStart` 已无条件复位僵尸任务、不再预插入任务行、`strmScrapeTracker` 已接线、已统计）。仍真实存在的五条按原方案落地：

| 编号 | 根因 | 修复点（文件 / 函数） |
| --- | --- | --- |
| **F-1** | 断点只存在内存里，进程被杀后 `processedPickcodes` 丢失，重启从头再跑一遍 | `CloudFolderBatchTaskEntity.processedPickcodesCsv` + `CloudPickcodeProgressCodec` 持久化；`CloudFolderBatchTaskRunner.runTask` 进入时 `decode(...)` 跳过已完成项；`CloudFolderBatchTaskRepository.resumeForRun(...)` |
| **F-2** | 只要 `cloud_strm_records` 有行就显示「已添加」，但 STRM 生成 ≠ 已挂到影片：`movieId == null`（还在队列）与「挂了但刮削失败」都被误报成完成 | `CloudStrmRecordRepository` 按 `movieId`/刮削状态三分：`queuedPickcodes` 与「已添加」分离，只有真正挂片成功才算完成 |
| **F-3** | 设置页「刷新」按钮只改一句文案，数据不重查 | `SettingsViewModel.refreshScrapeTaskSummary()` 真正重查任务表并回灌 UI |
| **F-4** | 终止路径不落库：`cancel()` 不写状态，`stop()` 只杀协程不写原因，卡片永远停在「运行中」 | `markCancelled` / `markPaused` / `markFailed` 三态分别落库，`runPendingTasks` 的 `CancellationException` 分支按 `cancelRequested` 区分「取消」与「停止」 |
| **F-5** | 进程被杀后留下的 `Running` 没人接管，用户以为还在跑 | `CloudFolderBatchTaskRepository.countInterrupted()` + `CloudFolderBatchTaskRunner.resumeInterruptedIfNeeded()`（只在确有中断残留时才自动开跑，不惊动用户主动暂停/取消的任务）；`AppContainer.resumeInterruptedTasksAsync()` 在冷启动统一做「来源冷却回灌 + 账本失联归位 + 批量任务续跑」 |

**触发条件（修前可复现）**：网盘文件夹批量入库中途杀进程 → 重开 App，断点丢失从头跑（F-1）；STRM 落盘但番号解析失败 → 列表显示「已添加」实际没入库（F-2）；点刷新无反应（F-3）；任务失败/取消后卡片仍转圈（F-4）；冷启动后无人接管（F-5）。

**与文档的冲突点**：方案文档里的 A1/B1/C1/C3 在当前代码上已不成立，若照抄会造成重复复位与二次预插入（反而制造新 Bug）。故仅按 F-1~F-5 落地，未改动已修复部分。

**回归**：`prepareForStart()` 仍然只做「僵尸 Running → Pending」，F-1 的断点写回走 `resumeForRun(...)`，两者互不覆盖；其它调用方（`CloudVideoTaskRunner`、设置页统计）签名未变。

---

## 2. 四模块增强：采纳 / 调整 / 放弃

参考对象是 peach-study（Python/FastAPI/SQLite/服务端形态）。鸭影是单机 Android + Room + Compose，**没有服务端**，因此逐条判断「等价能力」而不是照搬代码结构。

### 2.1 元数据（采纳）

| peach | 鸭影落法 |
| --- | --- |
| `field_owners` 表钉住「谁写了这个值」 | `movie_field_provenance` 表：`(movieId, fieldKey)` 主键，记 source / authority / confidence / manualOverride |
| `POLICY_VERSION` + 来源分级决定覆盖 | `MetadataAuthority` 枚举 + `MetadataMergeEngine` 纯函数，输出 `Apply / KeepCurrent / NeedsReview` 并带理由码（`Reasons.*` 常量） |
| `entity` 三分法与规范名 | `EntityNameNormalizer` + `entity_aliases` 表，只解决「同一个人的多种写法」归一 |

**调整**：不引入 peach 的 6 种 entity kind（鸭影的实体只有演员/片商/系列三类，多出来的维度没有消费方）；不加 `POLICY_VERSION` 落库，改为枚举 + 纯函数决策——单机场景没有多端策略同步需求。
**放弃**：peach 的服务端合并任务与策略下发（无服务端，且会让元数据写入依赖网络）。

### 2.2 刮削（采纳 + 调整）

- **采纳**：`ScrapeFailureReason` 失败分档 + `SourceHealthRegistry`（连续失败计数、冷却窗口）+ `ScrapeSourceChain`（按来源等级与健康度排序，命中硬阻断即停）。
- **调整**：`SourceHealthEntity` 落库并在冷启动回灌（`SourceHealthRepository.primeTracker`）——否则重启后立刻又去打刚 403 的站点。不同源之间不共享 403 结论，冷却按源隔离。
- **风险控制**：`StrmScrapeRepository` 原有内联回退逻辑保持不变，`ScrapeSourceChain.order(...)` 作为「排序建议」接入，取源顺序可复议，行为可回退。
- **放弃**：peach 的服务端抓取队列与跨进程重试（Android 侧已有 WorkManager/协程，引入会多一层）。
- **第二轮补充**：另从 peach 取「人机校验页识别」与「按番号内容类型路由」，从 stash 取「声明式后处理链」，详见 §4.3。

### 2.3 任务系统（采纳 + 调整）

- **采纳**：单表账本 `task_runs` + 六态状态机（`Pending/Running/Paused/Succeeded/Failed/Cancelled`）+ 去重键 + `recoverInterrupted`。
- **调整（关键）**：peach 用「部分唯一索引」做活跃态互斥，**Room 不支持部分唯一索引**。改法：`TaskRunPolicy.shouldStartNewRun` 判定 + `claimMutex` 进程内串行 + 事务内 SELECT 后插入。失败模式已在 `TaskRunPolicy` 注释中写明：多进程同时写同一 dedupeKey 时，靠「心跳超时判失联 + 旧行降级 Paused」兜底，而不是靠数据库约束。
- **接线**：`TaskRunRepository` 已接入 `CloudFolderBatchTaskRunner`——每个文件夹任务占一条 run（scope 用 `folderCid`，同文件夹互斥），成功/失败/暂停/取消分别落终态。注意 `finish()` 内部 `require(state.isTerminal)`，**`Paused` 必须走 `notifyPaused()`**，否则暂停会被吞成「仍在运行」，冷启动恢复会把它当失联任务接管。
- **放弃**：peach 的跨进程 worker 租约（单进程 App，无收益）。
- **第二轮补充**：五个 `TaskKind` 已全部接到真实执行点（含本轮新增的「元数据补全」runner），详见 §4.1 / §4.2。

### 2.4 媒体与播放（采纳 + 调整 + 放弃）

- **采纳**：`PlaybackFailure` + `PlaybackFailureClassifier`——把 ExoPlayer 异常归一成「可自愈 / 不可自愈」，401/403 → 自动刷新直链重试一次，404/410 → 提示重新扫描，429/503 → 退避，已接入 `PlayerViewModel`。
- **调整**：peach 的 `/stream` 决策链 + 三个 HTTP 状态码，在 Android 上没有服务端可供输出，等价物是**直链租约**：`DirectLinkLease`（签发/失效/续期判定，时钟注入可卡 1ms 边界）+ `DirectLinkLeaseStore`（提前续期）+ `DirectLinkSingleFlight`（同 key 并发合并）。已在 `PlaybackResolver.cachedDirectUrl(...)` 接入 115 与光鸭两条路由：内存租约命中即返回，未命中走单飞，`invalidatePickcode` / `invalidateRoute` 同步清租约（否则 403 刷新会从租约层又拿回旧链）。
- **采纳**：`SubtitleSidecarLocator`（同名 srt/ass/vtt/sub + 语言推断）接入 `LocalSubtitleStore.listLocalSubtitles`：原有 `isSubtitleNameFor` 过滤保留不变，在其后叠加 `SubtitleSidecarLocator.find(...)` 做**已知语言优先、forced 垫底、同级按文件名**的排序（原先只有按扩展名过滤、没有排序）。**保守约束**：基名忽略大小写后必须完全相等——挂错字幕比没字幕更糟，故不放开匹配宽度，只补排序。
- **放弃**：peach 的 Range/断点续传服务端实现（ExoPlayer 自行处理）、外部播放器 Intent 工厂（`ExternalPlayerIntentFactory`，本次未引入，见 §6）。

---

## 3. 数据库变更（19 → 20）

新增 4 张表，均在 `AppDatabase` bump 版本并注册 `MIGRATION_19_20`：

| 表 | 用途 |
| --- | --- |
| `movie_field_provenance` | 字段级来源归属（元数据） |
| `entity_aliases` | 实体别名归一（元数据） |
| `source_health` | 刮削源健康度 / 冷却（刮削） |
| `task_runs` | 统一任务账本（任务系统） |

迁移只 `CREATE TABLE`，不改动既有表结构；`exportSchema = true`，未新增导出 json 之外的破坏性语句。

---

## 4. 后续增补：任务账本接线 + 刮削链路按 peach / stash 优化

### 4.1 四个 `TaskKind` 全部接入 `task_runs`

第一轮只接通了「文件夹批量入库」一条链路，`VideoAdd / MovieScrape / MetadataRefresh / LinkRefresh`
只定义了枚举与状态机。本轮把它们逐个接到真实执行点：

| TaskKind | 接入点 | dedupe scope | 记账粒度 | 占位失败时 |
| --- | --- | --- | --- | --- |
| `VideoAdd` | `CloudVideoAddProcessor.processLocked` | `pickcode` | 每个视频一行 | 降级为不记账，继续处理 |
| `MovieScrape` | `MovieScrapeTaskRunner.runIssues` | `"batch"` | 一批一行，逐片 `updateProgress` | 同上 |
| `MetadataRefresh` | `MovieMetadataRefreshRunner`（本轮新增） | `"batch"` | 一批一行 | 同上 |
| `LinkRefresh` | `DirectLinkRepository.resolve`（**仅真实走网络续签时**） | `pickcode` | 一次续签一行 | 同上 |

两处刻意的语义选择：

1. **占位失败一律不阻断业务**。`TaskRunRepository.claim` 在「同键已有 Running/Paused 且未失联」时返回
   `null`。若照搬文件夹批量的 `?: continue`，用户手动发起的刮削会被一条历史 `Paused` 行整批吞掉；
   而冷启动的 `recoverInterrupted()` 恰好会把所有 `Running` 降级成 `Paused` 并保留 10 分钟失联窗口——
   重启后紧接着点「开始」正好落进这个窗口。账本是观测设施，不该成为业务的前置闸门，故降级为「不记账继续跑」，
   并把原因写进刮削日志。
2. **`LinkRefresh` 只在真的打网络时记一行**。缓存命中属于本地秒回，不是「刷新」；
   若把它也记一行，常用影片每次播放都会顶掉设置页的最近任务列表。

`MetadataRefresh` 的 scope 用固定 `"batch"` 而不是逐片，是为了让「元数据补全」在账本里表现为一条可汇总的任务，
而不是几百条 `MovieMetadataRefresh:<id>` 把 `observeRecent(20)` 挤满。

### 4.2 元数据补全：新 runner，而不是复用刮削

`MovieMetadataRefreshRunner` 只做「已入库且已有 NFO 的影片补文本空位」：

- 判据只在 `MovieDao.getMetadataGapMovies()`：`nfoUri IS NOT NULL` 且 `actors/studios/genres/plot` 有空。
  **不含 `posterUri`**——那是本地文件 Uri，补全任务不下载图片，把站点 URL 写进 Uri 列会让详情页拿到死链。
- 抓取复用 `StrmScrapeRepository.refreshMetadataOnly(movie, source)`（= `findTargetForMovie` + `scrapeWithFallback`），
  但**跳过** `rewriteScrapeFilesInPlace` / `recordScrapedProvenance` / `downloadActorAvatars`——
  补全不该改写已整理的文件名，这与「重新刮削」是两件事，复用 `rescrapeMovie` 会误伤。
- 逐字段走 `MetadataMergeEngine`：用户手改过的字段（`NeedsReview`）只计数不覆盖；
  账本不可用时退化为「只填空位」这一最保守策略。
- 落库是 `MovieRepository.applyMetadataGapPatch`：**重读实体**再 `copy()`，只改放行字段，
  避免用任务开始时的快照把抓取期间的用户编辑吞掉；零变更不写库，避免无谓触发列表 Flow 重发。

### 4.3 刮削链路：从 peach / stash 各取一件，逐条改造而非移植

参考项目与本项目的形态差异决定了只做「取机制、不搬实现」：

| 借鉴点 | 出处 | 鸭影的落地方式 | 取舍 |
| --- | --- | --- | --- |
| 人机校验页识别只看 `<title>` 且只读前 4096 字节 | peach | `scraper/ChallengePageDetector.kt`：`SNIFF_LIMIT = 4096`，精确匹配 `just a moment...` / `请稍候…` / `attention required! \| cloudflare` | **采纳**。不认 `challenge-platform`、不扫 body——那两种做法在正常页面上误报率明显更高 |
| 按番号内容类型决定来源顺序 | peach | `scraper/ScrapeRouteRules.kt`：`classify(number, fileName)` → `Censored/Amateur/Uncensored/Fc2/Kmib/Other/Unknown`，`orderFor(type, configured)` 把首选来源提到最前（保持 `configured` 内相对次序） | **采纳**，但只做「排序」不做「删减」：鸭影的来源列表是用户显式配置的，擅自丢弃会违背设置页的语义 |
| 结算条件区分「必填标量」与「列表字段」 | peach | `ScrapeSourceChain` 的完备度判定只算 `title/actors/genres/studio/poster/year` 六项；`ScrapeRouteRules.missingRequiredFields` 供调用方判断还缺哪些必填标量 | **调整**。peach 会在必填齐了就短路，鸭影保留「完备度阈值 + 来源链」两段式，因为多来源互补（一家没演员、另一家有）在本项目更常见 |
| 声明式后处理链（replace / trim / split / map / parseDate） | stash | `scraper/JsonFieldTransform.kt`（`sealed interface` + `JsonFieldTransforms.parse/applyScalar/applyList`）与 `scraper/CustomJsonPostProcessChains.kt`（TEXT / PLOT / DATE / RUNTIME / LIST_ITEM 五条内置链） | **采纳**。这是本轮最有价值的一条：自定义 JSON 源的质量差异几乎全在字段清洗上 |
| 用户可写后处理规则 | stash | `CustomJsonScrapeConfig.postProcessSpec` + 设置页「后处理规则（可选）」输入框；`CustomJsonScraper` 把 `内置链 + 用户链` 串起来 | **调整**。stash 用 YAML 全量声明爬虫；鸭影已有可视化配置页，只开放一个紧凑串（如 `replace /\[中文字幕\]// \| trim`），既给逃生舱又不重做配置模型 |

**不予采纳**的部分及原因：

- **YAML 声明式爬虫（stash 的 `scraper/*.yml`）**：鸭影的 `CustomJsonScrapeConfig` 是 Room 实体 + 设置页表单，
  迁到 YAML 等于重做配置层与迁移，且用户可见行为会变——属**不适用**，不是能力不足。
- **`javascript` 后处理**：需要 JS 引擎（Rhino/QuickJS），为一个字段清洗引入运行时依赖不划算。
- **`subScraper`（用前一次抓取的结果再发一次请求）**：会打破 `scrapeWithFallback` 的「一次尝试一个来源」记账节奏，
  失败归因也会变得不可解释。
- **peach 的三闸门（请求数 / 字节数 / 时长上限）与全站冷却公式（15 分钟起翻倍）**：鸭影是单机 App，
  不存在 AWD 式的持续压测；已有 `ScrapeFailureKind.cooldownMillis`（403 → 30 分钟、限流 → 10 分钟）足够，
  再叠加「翻倍」会让一次误判把来源锁死数小时，用户无从解开。

---

### 4.4 第三轮增补：来源与顺序按 peach 重做，跨源合并按层级定胜负

第三轮的问题是「peach 和 stash 各自用哪些来源、按什么顺序刮，两者异同在哪，鸭影该学谁」。
完整的对比分析（含双方源码行号、七条内容类型链、settles 三条规则、stash 的串行首命中模型）写在
**[`docs/SCRAPE-SOURCE-ORDER.md`](SCRAPE-SOURCE-ORDER.md)**，这里只记落地结论。

一句话概括两者的差异：**peach 把「哪种番号该问哪几家」当领域知识写进代码，stash 把它当用户偏好交给 UI。**
stash 只有 2 个内置来源（演员站 freeones 与无网络的 autotag），JAV 站点全靠用户丢 `.yml` 进去；
顺序是用户在 IdentifyDialog 里拖出来的数组，串行 for 循环、第一个返回非空就 `return results[0]`、
**永不跨源合并**。peach 相反：七条硬编码的内容类型链、每家只取一条、结算侧按字段跨源合并。

鸭影是「用户可配来源列表 + 多来源互补」的形态，与 peach 更接近，所以主路径学 peach；
stash 的「首命中即停」明确放弃（`ScrapeSourceChain.merge` 的存在本身就说明用户期待 A 站给标题、B 站给演员，
改成首命中即停是功能倒退）。

改造前鸭影的三个真问题：①素人号先去问 DMM 与 Official，而这两家必然搜不到（DMM 不收录素人号、
`OfficialScraper` 的前缀白名单里一个素人厂牌都没有），用户看到的失败其实只是「问错了站」；
②用户把 Javbus 配在 TheJavDB 前面时，`ScrapeSourceChain.merge` 的「先到非空优先」会让一份来自别的片的值
顶掉更准的值；③批量刮削在一小时内把同一家站敲几百下，间隔不够直接吃 403，然后冷却锁半小时。

| 改动 | 文件 | 说明 |
| --- | --- | --- |
| 按内容类型**裁剪**来源链 | `scraper/ScrapeRouteRules.kt` 新增 `RoutePlan` / `routeFor()` / `excludedFor()` | 素人剔 Dmm/Dmm2/Official，无码与 FC2 剔 Official/Dmm/Dmm2/Mgstage，韩国 MIB 与国产只留自定义 JSON。`orderFor()` 保留不动——原有测试断言「配置了哪些就查哪些」，新语义进新 API |
| 裁剪后空链**回退而非失败** | 同上，`RoutePlan.fallbackReason` | peach 的 kmib/other 返回空链是因为它有人工流程兜底；鸭影用户可能只开自定义 JSON 一家，照抄空链等于「点了什么也没发生」。回退时写日志说明被裁掉了哪些 |
| 结算只认**还缺的必填标量** | `ScrapeRouteRules.settles(info, remainingSources)` 重写 | 完成度 0.7 阈值退出判定——它会让「标题、厂牌、发行日有，演员为空」直接结算，而演员恰恰是用户翻库最常筛的一项。另吸收 peach 的类别例外：`genres` 为空且后面还排着官方源时不结算 |
| 跨源合并改按**层级**定胜负 | 新增 `scraper/ScrapeAuthority.kt`：`SourceTier` + `orderByTier` + `mergeByAuthority` | Official(1) > 首选社区 TheJavDB(2) > 社区 CustomJson(3) > 兜底 Javbus(4)。逐字段取值：incoming 空取 base、base 空取 incoming、两边都非空比层级。`draft` 从 `ScrapedMovieInfo?` 改成 `Pair<ScrapeSource, ScrapedMovieInfo>?` 以带上来源身份 |
| 按来源**最小请求间隔** | 新增 `scraper/ScrapeSourceInterval.kt` + `ScrapeSourceThrottle.kt` | javdb 3s（按出口 IP 计配额）、其余 2s、自定义 JSON 不限速；只在自动链生效，用户点名来源不等。计时器时钟可注入，纯逻辑可单测 |

**与 peach 的四处刻意偏离**：

1. **内容类型判定只用番号形状 + 文件名，不用「本机证据」**。peach 的 `route_for_code` 能查 ledger 判「一家一本道」，
   鸭影的 `movies` 表里只有已入库的片，拿它判会把没入库的号全判成 unsure。宁可偶尔把 unsure 判成 censored。
2. **裁剪后空链回退到配置顺序并记日志**（理由见上表）。
3. **类别例外比 peach 宽**：peach 只在「下一档属官方档」时多问一家；鸭影判据是「剩下还有 Official/Dmm/Dmm2/Mgstage」，
   代价是偶尔多打一次。
4. **不做 peek 的 `_MissCache`（NotFound 记忆 7 天）**：需要新表 + 站名/番号复合键，而鸭影的批量刮削是用户主动点的，
   重复查同一批号的机会不多，收益不抵迁移成本。

### 4.5 第四轮增补：真正补一个来源（r18.dev），而不是再接一个前台站点

第三轮做完的是「顺序与裁剪」，来源集合本身没动。这一轮回答的是「peach 那几家 JAV 站点要不要都搬过来」。

**先量了一下现网可达性**：`curl https://video.dmm.co.jp/` / `https://www.dmm.co.jp/` /
`https://api.video.dmm.co.jp/graphql` 三个全部 `000` + exit 35（SSL connect error）——**DMM 在本机网络不可达**。
鸭影的 `Dmm` / `Dmm2` 两个来源在有码链的最前面，它们一断，整条链对普通有码作品就只剩 JavBus 与 TheJavDB
两个 HTML 站；素人链（`Mgstage` 打头）也是同理，MgStage 一跳就只有社区站。

所以补来源的优先级不是「照 peach 表从上往下抄」，而是**补一条与 DMM 同数据面、但走 API 的路**：

| peach 的来源 | 结论 | 理由 |
| --- | --- | --- |
| `r18dev` | **采纳**，新增 `ScrapeSource.R18Dev` | 官方 REST：`https://r18.dev/videos/vod/movies/detail/-/combined=${id}/json`，`dvd_id` 直接按番号查，返回结构化 JSON（title/actors/genres/maker/date/runtime/cover）。**它就是 DMM 目录的镜像**，走的却是 API——正好补上 DMM 不可达的空档。无码/素人的 `dvd_id` 在它上面不存在（实测返回空结果），所以已在 `excludedFor()` 里把素人、无码、FC2 三类的 r18.dev 裁掉 |
| `prestige` / `faleno` / `dahlia` / `makers` | **放弃** | 四家都是「按厂牌官网搜」，鸭影的 `OfficialScraper` 已经是同一形态（前缀白名单 + 官网搜索），再加四套解析器等于把同一件事做五遍，维护成本全在解析器上。真要扩，正确做法是往 `OfficialScraper` 的 `OFFICIAL_PREFIXES` 里加前缀，而不是加来源 |
| `avbase` / `avsox` | **放弃** | 纯 HTML 站，且 peach 只在「综合索引」档用它们兜底；其页面已是反爬重点，选择器一变就整条链失效。鸭影已有 JavBus / TheJavDB 两家同档来源，边际收益低 |
| `1pondo`（无码） | **放弃（暂）** | 鸭影无码链当前只有 Dmm2（已不可达）+ 社区站；补 1pondo 要新写一套无码站解析器，且无码番号的命名比有码更散。留作后续按需加，不阻塞本轮 |
| `fc2*` / `javten` / `javarchive` | **放弃** | FC2 系列要 4–5 个专用站才凑得齐；鸭影当前 FC2 由 Dmm2 + 自定义 JSON 覆盖，先让用户用自定义 JSON 顶，不做四套一次性解析器 |

**顺带补上的一个真缺陷（原「已知风险 1」的实际修法）**：第三轮把风险 1 记成「`classify` 只看番号形状，
可能把 unsure 判成 censored 去白问一次 DMM」。重新看了一遍调用链，**这个描述低估了问题**——
真正的隐患不在「问错站」，而在「问回来的结果没有被验过」：`scrapeWithFallback` 收尾时无条件执行
`accepted.second.copy(number = outputNumber)`，等于假定了站点返回的就是这一部。社区站按番号搜索最常见的
失败形态不是「搜不到」，而是「搜到了同号异片」——`259LUXU-891` 被答成 `259LUXU-1891` 这类，
错片的标题/演员/封面就会被静默写到正确文件名的影片上，用户全程没有任何提示。

修法是在 `onSuccess` 里、构造 `SourceAttempt` 之前加一道 `ScrapeNumberGuard.conflicts(sourceNumber, info.number)`：
判为冲突就按 `ScrapeFailureKind.NotFound` 处理（记失败、进冷却、换下一个源），而不是接受这份结果。
「问错站」那份代价本来就很低——`OfficialScraper` 前缀不在白名单时是**零请求**直接抛错，
DMM 打一次也就几百毫秒，所以没有为它改 `classify`。

`ScrapeNumberGuard` 的判定刻意做成「**只在能确认不是同一部时才拦**」：归一化（去非字母数字 → 大写 →
拆「可选前导数字 + 字母 + 数字」→ 数字去前导零）后比较，`SSIS-123` / `ssis00123` / `SSIS-0123` 视为同一部，
`300MIUM-123` 与 `MIUM-123` 也视为同一部（数字前缀规则会把前者改写成后者去查 MGStage，站点回哪种写法都可能）；
`FC2-PPV-1234567`、`ABC-123-4K`、纯数字这些认不出的形态**一律放行**。理由是误拒会把正确结果变成「刮削失败」，
而误收只是可能写入错值——两害相权，只拦认得出来的。

`R18DevScraper` 的实现要点（对齐本仓库既有刮削器的骨架，不是新范式）：

- 构造函数 `(client: OkHttpClient = …, baseUrl: String = DEFAULT_BASE_URL)`，便于测试替换 baseUrl。
- `requireSafeScrapeNumber(number)` 走 `ScraperResponseGuard`；`readLimited(body, ScraperResponseGuard.MAX_JSON_BYTES)` 限量读。
- `ChallengePageDetector.requireNotChallenge(body, ScrapeSource.R18Dev)` —— 与其它四家一致。
- 按 `dvd_id` 精确匹配（API 的 `combined=` 查询会做模糊匹配，返回列表里的第一条未必是这一部，
  所以拿 `content_id` 逐条比，比不到才退回第一条，避免把 `ABC-123` 的元数据写进 `ABC-1234`）。
- 复用 `ScrapeFailureClassifier`：HTTP 403/429 → 对应可重试档；空结果 → `NotFound`；JSON 结构异常 → `ParseError`。

**同时改的接线点**（不加来源就只是死代码）：`ScrapeModels` 枚举 + `fromStoredName`、`ScrapeAuthority.tierOf`（与 Dmm/Dmm2 同属 `Official` 层）、
`ScrapeRouteRules.excludedFor`、`StrmScrapeRepository.scrapeSource()`、`ScrapeSourceInterval.intervalFor`、
设置页来源列表（由 `ScrapeSource.entries` 驱动，自动出现）、`MovieScrapeTaskRunner` 的来源标签。

---

## 5. 改动文件

新增（第一轮）：`metadata/{MetadataAuthority,MetadataCandidate,MetadataFieldKey,MetadataMergeEngine,EntityNameNormalizer}.kt`、`scraper/{ScrapeFailure,ScrapeSourceChain,ScrapeModels}.kt`、`tasks/{TaskState,TaskKind,TaskRunPolicy,TaskRunSnapshot}.kt`、`playback/{DirectLinkLease,PlaybackFailure,SubtitleSidecarLocator}.kt`、`data/local/{TaskRun*,SourceHealth*,MovieFieldProvenance*,EntityAlias*}`、`data/repository/{TaskRunRepository,SourceHealthRepository,MovieMetadataLedgerRepository}.kt`、`util/RetryBackoff.kt`。
修改（第一轮）：`data/local/AppDatabase.kt`、`data/AppContainer.kt`、`data/local/CloudFolderBatchTask*`、`data/repository/{CloudFolderBatchTaskRepository,CloudFolderBatchTaskRunner,CloudStrmRecordRepository,StrmScrapeRepository}.kt`、`ui/{LocalMovieLibraryAppRoot,player/PlayerViewModel,settings/SettingsViewModel,settings/SettingsScreen,cloud/*,detail/DetailViewModel}.kt`、`subtitle/LocalSubtitleStore.kt`、`playback/PlaybackResolver.kt`。

新增（第二轮）：`data/repository/MovieMetadataRefreshRunner.kt`、`metadata/MetadataGapPatch.kt`、`scraper/{ChallengePageDetector,ScrapeRouteRules,JsonFieldTransform,CustomJsonPostProcessChains}.kt`。
修改（第二轮）：`data/repository/{MovieScrapeTaskRunner,CloudVideoAddProcessor,DirectLinkRepository,MovieRepository,StrmScrapeRepository}.kt`、`data/local/{MovieDao,AppDatabase}.kt`、`scraper/{CustomJsonScraper,DmmScraper,JavbusScraper,MgstageScraper,OfficialScraper}.kt`、`data/AppContainer.kt`、`ui/settings/{SettingsViewModel,SettingsScreen}.kt`。

新增（第三轮）：`scraper/{ScrapeAuthority,ScrapeSourceInterval,ScrapeSourceThrottle}.kt`、`docs/SCRAPE-SOURCE-ORDER.md`。
修改（第三轮）：`scraper/ScrapeRouteRules.kt`（新增 `RoutePlan`/`routeFor`/`excludedFor`，重写 `settles`）、`data/repository/StrmScrapeRepository.kt`（自动链改走 `routeFor`、跨源合并改走 `mergeByAuthority`、循环内按来源等待最小间隔）。

新增（第四轮）：`scraper/R18DevScraper.kt`、`scraper/ScrapeNumberGuard.kt`。
修改（第四轮）：`data/repository/StrmScrapeRepository.kt`（`scrapeWithFallback` 的 `onSuccess` 加番号一致性校验）、`scraper/ScrapeModels.kt`（新增 `R18Dev` 枚举与存名解析）、`scraper/ScrapeAuthority.kt`（`tierOf` 归入 `Official` 层）、`scraper/ScrapeRouteRules.kt`（`excludedFor` 按类型裁掉）、`scraper/ScrapeSourceInterval.kt`（r18.dev 间隔）、`data/repository/StrmScrapeRepository.kt`（`scrapeSource()` 分支）、`ui/settings/SettingsScreen.kt`（来源展示已由枚举驱动，仅确认无遗漏）。

测试新增：`tasks/{TaskStateTest,TaskRunPolicyTest}`、`metadata/{MetadataMergeEngineTest,EntityNameNormalizerTest}`、`playback/{DirectLinkLeaseStoreTest,DirectLinkSingleFlightTest,SubtitleSidecarLocatorTest}`、`scraper/{ChallengePageDetectorTest,ScrapeRouteRulesTest,JsonFieldTransformTest}`、`scraper/{ScrapeRouteRulesRoutePlanTest,ScrapeRouteRulesSettlesTest,ScrapeSourceThrottleTest}`。

---

## 6. 验证方式（本机可执行部分，已真实跑过）

无法打包 APK（本机无 JDK/Gradle/Android SDK），但把「能脱离 Android 的逻辑」真实编译 + 跑单测：

```
bash work/compile-pure-jvm.sh
[tier1 compile] 37 files        exit 0
[tier1 test compile] 19 files   exit 0
Tests run: 185,  Failures: 5
```

- 5 个失败全部在 `MovieNumberExtractorTest` / `MovieNumberUtilsTest`，属**既有基线问题**（数字前缀规则依赖运行时下发的 JSON 规则表，离线环境取不到），与本次改动无关；`tasks` / `metadata` / `scraper` / `playback` 链路 0 失败。
- 全量源码符号审计（`bash work/audit-all-symbols.sh`，196 个 `.kt` 一次性交给 kotlinc，缺 Android/AndroidX/Room 依赖）：
  用「本项目声明的顶层符号 ∩ 未解析符号集合」定位真错误，最终交集为空——即**没有任何自有符号解析不到**。
  该审计第二轮真实抓出并修掉了三个编译错误：`AppContainer.kt` 缺 `MovieMetadataRefreshRunner` import、
  `StrmScrapeRepository.kt` 缺 `ScrapeRouteRules` import、`MovieMetadataRefreshRunner.kt` 误写
  `MetadataMergeEngine.Action.Apply`（`Action` 实际是同包顶层枚举）；
  第三轮改 `StrmScrapeRepository` 时，审计还抓出并修掉了一处 `val routeType` **重复声明**（改含 `fileName` 参数时旧行未删）。
- 该审计的局限：`androidx.room` / `androidx.compose` 缺失，`Room`、`@Composable` 相关类型**无法类型检查**，
  只能保证语法与符号层面无误——这是本环境能做到的上限，首次在 Android Studio 编译时请重点看 `data/` 与 `ui/` 两侧。
- 工具链差异：本地 kotlinc 为 **2.1.21**，项目为 Kotlin **2.3.20**。因此 `StrmScrapeRepository.kt:428`
  的 `?.let { … continue }`（inline lambda 内 `continue`，2.2 起才合法）在本机报错、在项目里合法，**未做改写**。

---

## 7. 未完成项与已知风险

1. **未生成 APK**（按任务要求），未跑 Gradle/Android SDK，`data/` 与 `ui/` 层仅有语法级校验。
2. **`ExternalPlayerIntentFactory`**（peach 的「外部播放器 + 续播位置」）未引入：鸭影内建 ExoPlayer 播放器已覆盖主场景，另有 VR 播放设置，引入第二套播放入口会分散「一键播放」的交互，属**不适用**，非能力缺失。
3. **`MetadataMergeEngine` 的 `NeedsReview` 分支**只落库 + 打日志，尚无人工复核 UI。当前没有「待复核」承载页面，强做会引入半成品入口，故留作后续。
4. **Room 缺少部分唯一索引**带来的多进程互斥缺口（见 §2.3），单进程 App 下不可达，但不是绝对安全。
5. **`task_runs` 只写不读**：五类 `TaskKind` 已全部接入真实执行点，但 UI 侧目前只有设置页的三个旧状态源，尚未有一个「统一任务横幅」消费 `observeRecent` / `observeActiveCount`。账本数据是完整的，缺的是展示层。
6. **字幕**只加了排序，未放开匹配宽度：`isSubtitleNameFor` 的基名完全相等语义保持不变（挂错字幕比没字幕更糟）。因此 `影片名.chs.srt` 这类「视频带分辨率后缀、字幕不带」的历史命名仍不被识别——这是**有意保留**的旧行为，若要放开应单独做一次可关闭的宽松模式。
7. **元数据补全的字段决策依赖账本**：`MovieMetadataLedgerRepository.decideIncoming` 在首次运行（`movie_field_provenance` 为空）时对所有字段返回 `NO_CURRENT`，即「没有当前值」——首个补全批次的判定等同于全放行。这是刻意的（历史数据没有权威度记录），但意味着**第一次补全**不受手改保护，之后才生效。
8. **`LinkRefresh` 只覆盖播放时触发的续签**：没有「批量预刷新所有过期直链」的定时任务。要加需先解决 115 的请求频率与失败退避，超出本轮范围。
9. **按来源最小间隔只在自动链生效**：用户点名某个来源时不等（点了就是想立刻看结果）。代价是「连续点十次单部刮削 Javdb」仍可能触发 403；冷却机制兜底，但用户会看到「来源冷却中」。
10. **`ScrapeSourceInterval` 的两档取值（3s / 2s）来自 peach 的实测，不是鸭影自己的实测**：鸭影没有做过「间隔多少才不返 403」的对照实验。若实际仍频繁 403，应把 `COMMUNITY_INTERVAL_MS` 调大后复测，而不是直接照搬更大的值。
11. **内容类型裁剪依赖番号形状**：自定义 JSON 源如果配的是「按标题搜」这种不依赖番号的查询，遇到 KMIB/国产番号会被裁掉（因为 `excludedFor(Kmib)` 只留 `CustomJson`——这条恰好是对的；但若用户把它当成唯一来源，`routeFor` 会走空链回退路径，此时日志里会有 `fallbackReason`，行为是「仍按原顺序问」，不会静默失败）。
12. **r18.dev 只补有码这一面**：它是 DMM 目录的镜像，素人/无码/FC2 的番号在它上面查不到，所以四类里只对有码链生效。无码链（1pondo 一家）与 FC2 链（5 家）本轮**明确放弃**，理由见 §4.5——不是能力不足，是每个站都要一套独立解析器，边际收益不抵维护成本。若后续确需无码覆盖，优先加 `1pondo` 一家，而不是把 FC2 那五家全搬来。
13. **r18.dev 的 `combined=` 是模糊查询**：`ScrapeSource.R18Dev` 已按 `content_id` 逐条精确比对，比不到才退回第一条。残余风险是「番号在它上面收录为别的写法」（如 `ABC-0123` vs `ABC-123`），此时会退回到第一条而可能取错片——已由 `MetadataMergeEngine` 的权威度与用户手改保护兜底，但不是零风险。

### 7.1 第三轮遗留的三条风险 · 处置结果

| 风险 | 处置 | 说明 |
| --- | --- | --- |
| 内容类型判定只用番号形状 | **已改判并部分修复** | 真正的隐患不是「问错站」而是「没验结果」（见 §4.5），已在 `scrapeWithFallback` 的 `onSuccess` 加 `ScrapeNumberGuard` 校验。`classify` 仍不读本机证据——那是刻意的，理由见 §4.4 的偏离说明 1 |
| 限流只在自动链生效 | **保留原行为** | 点名来源是即时动作，等 3 秒会让界面像卡住。且默认刮削源被强制写成 `Priority` 走自动链，批量刮削全程受限流保护；点名单部一次只发 1–3 个请求，触发 403 的概率低于批量 |
| `orderFor` / `arrange` 仅测试使用 | **保留，不删** | 任务边界要求「不删除现有可用功能」；`orderFor` 的语义（只调顺序不裁剪）仍是 `routeFor` 的对照基线，测试固化它有价值。后续若确要清理，应连同 `ScrapeRouteRulesTest` 里的两条断言一起改，而不是单独删函数 |

---

## 8. CI 修复：`Failed to find package 'tools'`

失败现场（仓库 `yu961003/homemovies-guangya`，run `40253956078`，commit `18fff2ef`）：
`Checkout` / `Unzip source code` / `Set up JDK 17` 全 success，**`Setup Android SDK`（第 5 步）failure**，
其后步骤全部 skipped。

```
Warning: Failed to find package 'tools'
The process '/usr/local/lib/android/sdk/cmdline-tools/16.0/bin/sdkmanager' failed with exit code 1
    at ExecState._setResult (.../android-actions/setup-android/v3/dist/index.js:1823:25)
```

**根因（两条事实叠加，均已核对官方 action.yml 与 v3 的 dist 源码）**：

1. `android-actions/setup-android@v3` 的 `action.yml` 里 `packages` 默认值是
   **`'tools platform-tools'`**，`cmdline-tools-version` 默认 `12266719` 就是
   **cmdline-tools 16.0** —— 与报错路径一致。仓库 `build.yml` 那一步没写 `with:`，
   于是吃默认值去装 `tools`。
2. legacy `tools` 包自 **cmdline-tools 16.0** 起已从 SDK 仓库移除，`sdkmanager` 打印
   `Failed to find package 'tools'` 后 `exit 1`；它不会因清单里其它包成功而返回 0，
   所以 job 在**编译开始前**就死，与项目代码无关，重跑多少次都一样。

**修复**：给 `build.yml` 的 `Setup Android SDK` 补 `with:`，显式覆盖 `packages`：

```yaml
      - name: Setup Android SDK
        uses: android-actions/setup-android@v3
        with:
          packages: 'platform-tools platforms;android-35 build-tools;35.0.0'
          accept-android-sdk-licenses: true
          log-accepted-android-sdk-licenses: false
```

两个坑：

- action 内部是 `.split(' ')`（`v3/dist/index.js` 第 28422–28423 行附近），**只按空格切**，
  再对每个包单独 `exec.exec(sdkManager, [arg])`。所以必须**单行空格分隔**（或 `>-` 折叠）；
  用 `|` 字面块会把换行留在值里，整段成为含 `\n` 的包名，照样 `Failed to find package`。
- 别传 `packages: ''`（覆盖成空列表，`platform-tools` 不装），也别删这个键（回落到默认值，
  `tools` 又回来了）。

**交付物（均在 zip 外）**：

| 文件 | 说明 |
| --- | --- |
| `deliver/ci-fix/build.yml` | 修好的完整工作流，可直接覆盖仓库 `.github/workflows/build.yml` |
| `deliver/ci-fix/build.yml.patch` | 同一改动的最小 unified diff（20 行，只碰 SDK 那一处） |
| `docs/CI-SETUP.md`（zip 内） | 根因、三种改法、推送步骤、验收标准、下一批失败分流表 |

**为什么不把 `.github/` 塞进源码 zip**：仓库的 `build.yml` 是先把仓库根目录的 zip 解压再构建，
CI 配置本身属于仓库、不属于源码包；包里再放一份只会在工作区里出现两份工作流。

**未验证**：`./gradlew :app:assembleDebug` 在本机仍未执行（无 JDK/Gradle/Android SDK、无外网到
`google()`/`mavenCentral()`）。只做了程序性校验：`build.yml` 可被 YAML 解析、`packages` 按空格切
恰好是三个合法包、无 `tools` 残留、与仓库原文件相比仅 SDK 步骤有差异、zip 内 `.github` 已移除。
构建结论以该仓库 Actions 首次跑通为准。

## 9. 第二批：`compileDebugKotlin` 的 20 条错误

§8 修完后 CI 推进到编译阶段，`:app:compileDebugKotlin` 报 20 条错误、跨 5 个文件。
20 条只有 **5 个根因**——一条符号／语法错误会向下连坐一整片，所以这是"改 7 处、消 20 条"：

| 根因 | 位置 | 改法 |
| --- | --- | --- |
| 全限定名写错包 | `MovieMetadataRefreshRunner.kt:44` `kotlin.coroutines.CoroutineDispatcher` | 改 `kotlinx.coroutines.CoroutineDispatcher`（`kotlin.coroutines` 下只有 `coroutineContext` 这类） |
| 少一个 `)` | `StrmScrapeRepository.kt:554` `settles(settled, sources.drop(index + 1)` | 补 `)`。缺这个括号时紧跟的 `{` 被当成 `settles` 的 trailing lambda → 报"参数过多"，里面的 `break` 又被报成"跨函数边界"，`Syntax error` 也是它连带的（`559/560/562` 三条一起消失） |
| 不存在的常量 | `PlaybackFailure.kt:126` `ExoPlaybackException.TYPE_DECODER` | 删该分支，只留 `TYPE_RENDERER`；media3 里解码失败就是 Renderer 抛 `DecoderException` |
| 可空接收者 | `R18DevScraper.kt:107` `optString(...).cleanText()` | `JSONObject.optString()` 返回 `String?`，`cleanText()` 是 `String` 上的扩展 → `(rows.optJSONObject(i)?.optString("name_kanji").orEmpty()).cleanText()` |
| 调错对象 + 少 `val` | `CloudBrowserViewModel.kt:158/935` 与 `:933` | `classifyVisibleItems` 在 **`CloudStrmRecordRepository`**（入参 `Set<String>`）上，不在 `Cloud115StrmRepository` 上 → 改调 `recordRepository.classifyVisibleItems(items.mapNotNull { it.pickcode }.toSet())`；`items = strmRepository.listFiles(...)` 补 `val`。调用无解会连带 `runCatching`/`async` 的类型参数与后续 `.addedPickcodes` 全报未解析 |

**验证边界**：本机仍无 JDK/Gradle/Android SDK，**没跑过 `:app:compileDebugKotlin`**。
可复现的机器检查：`work/audit-all-symbols.sh` → 自己包内未解析符号 0 个（余下全是缺 Android 依赖的噪音）；
5 个改动文件括号配平扫描全过；`work/compile-pure-jvm.sh` → `Tests run: 185, Failures: 5`，与改前一致（无新增失败）。
另外：本机 kotlinc 2.1.21 在 `StrmScrapeRepository.kt:1060`、`DetailScreen.kt:1324`、
`GuangyaLoginClient.kt:146` 报同类 nullable 错误，但 CI（Kotlin 2.3.20）清单里没有这三处，
判定为新旧编译器推断差异，**刻意未动**。细节与逐条对照见 `docs/CI-SETUP.md` §7。

## 10. 第三批：对照 peach 重做刮削链路（"所有刮削都报网络错误"）

§9 修完编译后能出包，但真机上**所有来源都报网络错误**。不逐条打补丁，改成按 peach 的
刮削来源与配置整体对齐。基准表（数值出处）在 `deliver/scrape-align/peach-config.md`，
改动补丁与用法在 `deliver/scrape-align/`（`scrape-align.patch` + `README.md`）。

### 10.1 根因

旧实现在**代理开关关闭**（默认值）时给 OkHttp 返回 `Proxy.NO_PROXY`——这是**显式直连**，
不是"跟随系统"。OkHttp 只有完全没装 `proxySelector` 时才用 `ProxySelector.getDefault()`；
自己装了选择器又返回 `NO_PROXY`，Wi-Fi 代理与 VPN 下发的代理全部被跳过。
peach 的默认是 `environment`（`trust_env=True`），本项目把这一个默认值做反了，
于是国内网络下**所有站点同时不可达**——正好对上"不是某家站挂了，而是全部报网络错误"。

这是**推断链**（症状全来源 + 代码默认值反了 + peach 默认相反三方对齐），不是抓包实测；
验证方式见 §10.4。

### 10.2 五个改动面（20 文件，+583 / -100）

| 面 | 改动 | 关键位置 |
| --- | --- | --- |
| 代理三档 | 新增 `ScrapeProxyMode`（environment/direct/proxy），旧布尔键单向迁移（开→proxy，关→environment）；选择器三分支且**永不返回空列表**（空列表会抛 `SocketException: No route to host`） | `scraper/ScrapeProxyMode.kt`（新增）、`scraper/ScrapeProxySelector.kt:25`、`data/repository/AppSettingsRepository.kt:371` |
| 超时/重试 | 重试总次数 3 → **5**（peach 是 1+4），退避 `(2,4,6,8)` 秒四跳全用；探测从注入 client 派生 5s 副本（原先声明 5s、实际吃 30s） | `data/repository/AppSettingsRepository.kt:1346`、`data/repository/StrmScrapeRepository.kt:1320`、`scraper/NetworkProbe.kt:26` |
| 请求指纹 | 新增 `ScrapeUserAgent`：UA 统一 `Chrome/153`（原 `Chrome/111 macOS`）、`Accept-Language` 统一 `ja,en;q=0.9`（原 javbus 用 `zh-CN`）、`Sec-CH-UA` 版本改引 `MAJOR_VERSION`（原硬编码 120/124/125/148 会与 UA 自相矛盾）、`Referer` 统一 `base_url + "/"` | `scraper/ScrapeUserAgent.kt`（新增）、七个 scraper、`data/repository/ImageDownloadService.kt`、`scraper/OfficialScraper.kt:109` |
| 页上限 | 按来源定上限：DMM/DMM2 1 MiB、R18Dev 2 MiB、其余 4 MiB（原 HTML 20 MB / JSON 8 MB）；并补上 `Dmm2Scraper`、`CustomJsonScraper` 两处原先**完全没有上限**的 `body.string()` | `scraper/ScraperResponseGuard.kt:21-39` |
| 冷却阶梯 | 403/验证页改为 `min(900s × 2^(次数-1), 上限)`，上限 javdb 24h、javbus 6h、其余 24h（原固定 30min）；`404/410`→NotFound、`408`→Network；阶梯计数不落库，重启从第一档重算（刻意更宽松） | `scraper/ScrapeFailure.kt:77`（`ScrapeCooldownPolicy`）、`:273`、`data/repository/SourceHealthRepository.kt` |

### 10.3 验证（本机真实跑过）

- `work/verify-scrape-patch.sh`：正向 `patch -p1 --binary` → 与工作树逐字节一致；反向应用 → 与基线 zip 逐字节一致。
  **必须带 `--binary`**：20 个文件里 7 个是 CRLF，GNU patch 默认「Stripping trailing CRs」会让 CRLF 文件的 hunk 失败，`git apply` 同样不认。
- `work/scrape-align-verify/CooldownPolicyCheck.kt`：35 个 check **ALL PASS**（阶梯八跳与封顶、javbus 6h、`shl` 不溢出、404/429/Network/ParseError 不受阶梯影响、成功清零、冷启动回灌、reset）。
- `work/audit-all-symbols.sh`：198 files、未解析符号（去重）**0 个**。
- `work/compile-pure-jvm.sh`：`Tests run: 185, Failures: 5`，与改前**完全一致**（既有基线，全在数字前缀解析用例）。

### 10.4 未验证

- **`:app:compileDebugKotlin` 没跑过**（本机无 JDK/Gradle/Android SDK）。符号审计只能证明"没有新的未解析引用"，证明不了类型正确（Compose 参数类型、Room 注解都不在审计范围内）。
- **真机联网没验证**：`ProxySelector` 的实际生效需要设备上跑一次，看日志里是否出现经代理的连接。
- 与 peach 仍未对齐：cookie/session 复用、浏览器 gate/挑战页的真实绕过、amane 子进程桥接的 9 个站、peach 有 20+ 源而本项目 `ScrapeSource` 只有 9 个枚举。
