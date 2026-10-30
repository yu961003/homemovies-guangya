# 刮削失败复盘：SNIS-205 为什么一直刮不出来

> 分析对象：`app/src/main/java/com/example/localmovielibrarz/scraper/` + `data/repository/StrmScrapeRepository.kt`
> 样本：`SNIS-205`，两次运行（01:17 单次、08:49–08:56 完整一次）
> 证据等级：**Observed**（日志原文 / 代码位置 / 本机联网实测）与 **Inferred**、**Assumed** 逐条标注

---

## 一、结论摘要

1. **这次刮不出元数据的第一责任是「这个番号在上游确实不存在」**，不是代码坏了。
   DMM GraphQL、S1 官网、AVBase、TheJavDB 四个互相独立的源，对 `SNIS-205` 全部返回「查无此片」；
   而同一批接口对 `SNIS225` / `SNIS-210` / `SSIS-001` / `IPX-001` 都正常返回数据。
   → **站点没有 = 真 · 100%（就本样本而言）**

2. **但「失败体验」被三类工程缺陷显著放大**，它们决定了本来该 20 秒结束的一次刮削拖成 7 分钟：
   - **预算被单个来源吃光**：桃链 90 秒墙钟预算，被 Official 一家的 `30s × 3` 连接超时耗尽，后面 6 家一次都没问到；
   - **DMM2 失败被归成 `Unknown`**：抛异常时丢了 cause，分类器拿不到 `EOFException` → 冷却 0 秒 → 同一部片在一次刮削里被重打两遍，且不触发传输层重试；
   - **官网档四个来源其实是同一台站**：Official / FALENO 官网 / DAHLIA 官网 / 片商官网 对 `SNIS-205` 全部路由到 `s1s1s1.com`，同一站被问 3–4 遍。

3. **网络层是第二责任**：国内 DNS 对 `s1s1s1.com` 的解析被污染，返回 `2001::/32`（Teredo 保留段）黑洞地址——日志里三次超时的目标 `2001::76b8:4e4e` 就在这个段里；而刮削 client 既没有 `callTimeout`，也没有 IPv4 优先，一个源就能消耗 90 秒以上。
   → **网络 = 第二位，约三成**

4. **番号格式不是根因**：`SNIS-205` / `snis-205` / `SNIS205` 三种写法在 TheJavDB 上返回**完全相同**的 404；本机复刻 `MovieNumberExtractor` 对 `www.98T.la@SNIS-205_LTX.mp4` 提取出的就是 `SNIS-205`，`@` 前缀与 `_LTX` 后缀都会被剥离。
   → **格式 = 0%**

5. **刮削源接线有三处确定性问题**：12 家片商官网模板全空、靠「回首页发现搜索表单」兜底，而 S1 首页 `<form>` 数量实测为 **0**，这条兜底对 S1 恒失败；Javten 只认 FC2 号，但 legacy 链对「有码」不裁剪它；TheJavDB 只有一个域名且无备用。
   → **源没接好 = 第三位，约一成**

---

## 二、逐来源分析表

日志顺序即链上顺序。**「归因」列只写能从日志行或代码位置证实的那一条。**

| # | 来源 | 日志表现（原文/时间） | 代码位置 | 归因 | 类别 |
|---|---|---|---|---|---|
| 1 | **DMM2** | `类型=Unknown，可重试=true，冷却=0s`；同一部片在桃链与 legacy 各打一次 | `scraper/Dmm2Scraper.kt:125`（`throw RuntimeException("DMM2 GraphQL 请求连续失败：…")` 无 cause）<br>`scraper/ScrapeFailure.kt:207`（type-walk 含 `EOFException`）<br>`scraper/ScrapeFailure.kt:248`（`else -> Unknown`）<br>`scraper/ScrapeFailure.kt:63`（`Unknown(retryable=true, cooldown=0L)`） | 异常丢 cause → 分类落 Unknown → 冷却 0 → 无冷却重打且不重试 | **代码缺陷** |
| 2 | **TheJavDB** | `TheJavDB request failed HTTP 404: https://api.thejavdb.net/v1/movies?q=SNIS-205` | `scraper/JavdbScraper.kt:45`（单一 `API_URL`）、`:23`（`?q=`）、`:37`（非 2xx 直接 error） | 站内确实没有该番号；端点本身活着 | **站点没有** |
| 3 | **AVBase** | 三次失败（`:20` `:22` `:26`，间隔 2s/4s 与退避表吻合）；`EOFException` / `Connection reset`；冷却 15s | `scraper/AvbaseScraper.kt:38`（单次 `execute()`，无 try/catch）<br>`scraper/ScrapeFailure.kt:206-212`（`IO異常 → Network`） | 传输层抖动，非反爬（带 Referer 实测 200） | **网络** |
| 4 | **JAVten** | `这个番号认不出 FC2 商品号` | `scraper/JavtenScraper.kt:36-43`（`Fc2Parser.videoId` 为空即抛 NotFound）<br>`scraper/Fc2Parser.kt:33`（文案常量） | 该源只服务 FC2 商品号，对 `SNIS-205` 构造上无路可走；**legacy 链对它不做裁剪**（`ScrapeRouteRules.kt:216-217` 有码档 `emptySet()`） | **源没接好** |
| 5 | **Official（官网档）** | 08:50:41 / 08:51:43 / 08:52:47 三次连接超时，目标 `2001::76b8:4e4e`；单源吃掉 90s | `scraper/OfficialScraper.kt:19-21`（`/search/list?keyword=` 路径）<br>`StrmScrapeRepository.kt:1611`（`SCRAPE_TIMEOUT_SECONDS = 30L`）<br>`StrmScrapeRepository.kt:104-114`（client 无 `callTimeout`、无 DNS 策略） | DNS 污染投出不可路由 IPv6 + 重试放大 | **网络** |
| 6 | **FALENO 官网** | 08:52:49 → 08:53:51（62s）后 `首页取不到（超时），无法发现搜索入口` | `scraper/OfficialSiteScraper.kt:179`（`PageOutcome.Failed` 分支）<br>`OfficialMakerCatalog.kt` 的 `s1` 条目（`detailUrlTemplate=""`、`searchUrlTemplate=""`） | 空模板 → 回首页发现表单；首页超时 | **网络 + 源没接好** |
| 7 | **DAHLIA 官网** | 62s，同上 | 同上（三家共用 `OfficialMakerCatalog.BUILT_IN`，`route()` 只按前缀匹配、不区分 source） | 同上，且**与 #6/#8 打的是同一个站** | **源没接好** |
| 8 | **片商官网** | 08:54:53 → 08:55:25（32s）`没有配置取数模板，首页也没有可用的搜索表单，跳过` | `scraper/OfficialSiteScraper.kt:132-135`<br>`scraper/OfficialSearchForm.kt`（只认 `<form>`） | S1 首页实测 `<form>` 数量为 **0**，发现通道恒失败 | **源没接好** |

**交叉验证（本机实测，出口为代理线路，与用户手机不是同一网络实例 → 只能证明「站点侧」结论，不能直接证明用户手机的 DNS 行为）：**

| 源 | 请求 | 结果 |
|---|---|---|
| TheJavDB | `GET /v1/movies?q=SNIS-205` | **404** `{"error":"No videos found"}` |
| TheJavDB | `q=SSIS-001` / `q=IPX-001` / `q=PRED-001` | **200**，2365 / 2473 / 2166 B 完整 JSON |
| TheJavDB | `q=snis-205` / `q=SNIS205` | **同样 404**（与 `SNIS-205` 字节级一致） |
| DMM GraphQL | `snis00205` / `snis205` | `total = 0` |
| DMM GraphQL | `snis00225` / `ssis001` / `ipx001` | `total = 1 / 95 / 84` |
| DMM GraphQL | `snis00210/215/220/225/230` | 全 `total = 1`（同段邻近号都在，205 是号段空洞） |
| S1 官网 | `/search/list?keyword=SNIS205` | 200，无「全N作品」、0 个 `works/detail` 链接 |
| S1 官网 | `/search/list?keyword=SNIS225` | 200，「全1作品」+ 1 个 `works/detail/SNIS225` |
| S1 官网 | `/works/detail/SNIS205` | 200 但 **38067 B 空壳**（无 `<title>`）——不能当「存在」证据 |
| S1 官网 | `/works/detail/SNIS225` | 200，**75918 B**，有完整标题 |
| AVBase | `/works?q=SNIS-205`（带 `Referer: https://www.avbase.net/`） | 200，`__NEXT_DATA__.pageProps = {"works":[],"total":0,"q":"SNIS-205"}` |
| AVBase | 不带 Referer | **403**（→ 服务端有 Referer 白名单，但 App 侧 `AvbaseScraper.kt:29-36` 本来就带了） |
| AVBase | `/works?q=SNIS-210` / `q=SSIS-001` / `q=IPX-001` | `total = 1 / 2 / 1` |

**DNS 取证：**
- `s1s1s1.com` 的权威解析（Cloudflare DoH / Google DoH）只有 A 记录：`18.176.232.167`、`35.79.198.203`、`13.193.172.197`（AWS 东京），**AAAA 查询只返回 SOA、无 AAAA**；
- 而 `dns.alidns.com` 返回 `AAAA=2001::45c5:99b4` + `A=104.244.43.234`（RDAP：`104.244.40.0/21` 属 FENEC5-ARIN / Twitter 段）→ 国内解析被投毒；
- `2001::/32` RDAP 归属 IANA-ARIN（Teredo 保留段），本机 `ping -6` 100% 丢包、`curl https://[2001::45c5:99b4]/` 0.17s 直接失败；
- 用户日志里三次超时的目标 `2001::76b8:4e4e` 同属 `2001::/32` → **日志里的「IPv6 连接 30s 超时」是 DNS 污染投出的不可路由地址，不是站点 IPv6 慢。**

---

## 三、根因详述（四问逐一回答）

### ① 站点是不是真没有？—— **是，且是首位原因**

四个独立源、四条不同协议栈（GraphQL / 服务端渲染 HTML / Next.js JSON / REST JSON）对 `SNIS-205` 一致回答「没有」，而同一批接口对邻近号与同厂其它号都正常返回。这不是「一个源挂了」的形态。

补充一个容易误判的点：S1 的 `/works/detail/<任意号>` 对不存在的号也返回 **200**（38067 B 空壳页），所以**不能靠 HTTP 200 判断存在**——`OfficialScraper.parseDetail` 靠正则抽 `p-workPage__title` 等标记，空壳页抽不出内容，这一层本来是稳的。

### ② 番号格式是否不匹配？—— **否（本样本上贡献 0）**

- `MovieNumberExtractor.extract` 的正则 + `normalizeDigits`，本机复刻实测：
  `www.98T.la@SNIS-205_LTX.mp4 → SNIS-205`、`www.98T.la@snis00025_LTX.mp4 → SNIS-025`、`snis-025.mp4 → SNIS-025`、`SNIS205 → SNIS-205`、`SNIS-205-C.mp4 → SNIS-205`；
- `DEFAULT_IGNORED_SUFFIXES = setOf("HHB")` 里没有 `LTX`，但 `_LTX` 在提取阶段就被正则边界吃掉了，不参与后续；
- 唯一进请求串的改写是用户级 `NumberPrefixRewriteRule`，**无内置默认规则**，对 `SNIS-205` 恒等；
- 实测三种写法对 TheJavDB 返回一致 404，说明即使归一化口径不同，结果也不变。

→ 这条可以**明确排除**。唯一残余风险是「用户文件名里根本没有可识别番号」这类情况，但那会在更早的 `extract` 阶段报错，不会走到网络。

### ③ 刮削源是否没接好？—— **部分是，三处确定的接线问题**

1. **官网档 12 家模板全空**（`OfficialMakerCatalog.BUILT_IN`），靠 `OfficialSearchForm` 回首页发现 `<form>` 兜底。实测 S1 首页 `<form>` 数量 **0**（`https://s1s1s1.com/` 200 / 11074 B），→ 兜底对 S1 恒失败，必然落 `ParseError("这家片商没有配置取数模板")`。
   而 S1 的真实入口是**路径式**：`/search/list?keyword=SNIS205`（`{code_compact}`，无连字符）+ `/works/detail/SNIS225`（同为 compact 写法）。
2. **四个来源其实是同一台站**：`Official`（走 `OfficialScraper` 的 `search/list`）、`Faleno` / `Dahlia` / `MakerSite`（走 `OfficialSiteScraper` + 空模板）对 `SNIS-205` 全部路由到 `s1s1s1.com`。`OfficialMakerCatalog.route()` 只按前缀匹配、**不看 source**，所以同一站被问 3–4 遍。
3. **Javten 在 legacy 链不被裁剪**：`ScrapeRouteRules.excludedFor(Censored)` 返回 `emptySet()`，桃链的 `peachOnlyExcluded` 才裁它。于是 legacy 每次必然白打一次并污染负缓存（`ScrapeMissCache` 只在 `NotFound && numberMatched` 时写，7 天 TTL）。

### ④ 网络环境 —— **是，第二位原因（而且它把 ③ 的影响放大了一个量级）**

- **DNS 污染**（见上）→ 30s 连接超时；
- **重试放大**：`SCRAPE_RETRY_DELAYS_MILLIS = [2s, 4s, 6s, 8s]`，`DEFAULT_SCRAPE_RETRY_COUNT = 5`，每次 `connectTimeout/readTimeout = 30s`，**没有 `callTimeout`** → 单源最坏 5×30s + 20s = **170s**；
- **桃链预算 90s**（`ScrapeBudget.DEFAULT_MAX_MILLIS`）→ 一个源就能把整条链打断；
- **JSON/EOF 抖动**：AVBase 的 `EOFException` / `Connection reset` 属于传输层，被正确归为 `Network` 并冷却 15s——这一档**行为是对的**，问题只在重试次数偏多。

### 四类占比（本样本，证据驱动）

| 类别 | 占比 | 口径 |
|---|---|---|
| **站点确实没有** | **~65%** | 决定「能不能出元数据」的主因；四源一致否定 |
| **网络（DNS 污染 + 超时重试放大）** | **~25%** | 决定「要花多久」；日志里 08:49 那次 90% 的墙钟耗在这 |
| **源没接好（空模板 / 同站重复 / Javten 未裁）** | **~10%** | 白打 + 掩盖真相 |
| **番号格式** | **0%** | 三写法同结果，提取器实测正确 |

> 注意口径：**「站点没有」与「网络」不在同一维度**——前者决定结果有无，后者决定耗时与是否还能问到别的源。混在一起谈「谁占比大」会得出错误结论。

---

## 四、优化方案（按优先级，全部可落地）

### P0 — 立刻能止损

| # | 改动 | 文件 | 预期效果 |
|---|---|---|---|
| 1 | DMM2 抛异常时**带上 cause**，并把文案改成含「请求失败」 | `scraper/Dmm2Scraper.kt:125` | 分类从 `Unknown/0s` 变成 `Network/15s`；重试生效；同一部片不再无冷却重打 |
| 2 | 文案判据都失配时，用异常自带的**结构化档位**兜底（只升 `Unknown`，不覆盖既有有意判定） | `scraper/ScrapeFailure.kt` `structuredKind()` + `classifyKind` 末支 | 改一个字就翻档的脆弱性消失 |
| 3 | 刮削 client 加 **`callTimeout`**（建议 45s） | `StrmScrapeRepository.kt:104-114` | 单源上限从 170s 压到 45s，预算不再被一家吃光 |
| 4 | 加**IPv4 优先 + 过滤 `2001::/32`** 的 `Dns` | 新增 `scraper/PollutionSafeDns.kt`，挂到 client | 绕开污染投出的 Teredo 黑洞地址 |
| 5 | 给 `s1` 补**实测过的模板**：`/search/list?keyword={code_compact}` + `/works/detail/{code_compact}` | `scraper/OfficialMakerCatalog.kt` s1 条目 | 官网档对 S1 从「必然 ParseError」变成「真能取到」 |
| 6 | `OfficialMakerScraper` 按 source 过滤 catalog | `scraper/OfficialMakerScraper.kt` | Faleno 只问 faleno.jp、Dahlia 只问 dahlia-av.jp、MakerSite 问全量；同一站不再被问 4 遍 |

### P1 — 结构性修正

| # | 改动 | 说明 |
|---|---|---|
| 7 | legacy 链补**单源墙钟上限**（`withTimeout` 45s） | 不是加总预算（会改变 legacy 行为），只给单个源封顶 |
| 8 | `excludedFor(Censored)` 补 `Javten` | legacy 对 FC2 专用源本来就不该发请求 |
| 9 | 负缓存区分「端点不可用」与「站内无此号」 | 目前 TheJavDB 的 404 一律进 7 天负缓存；若端点整体失效会把整站压制 7 天 |
| 10 | 桃链预算分配**按来源配额**而非先到先得 | 12 格请求 / 90s 均摊到 N 个源，避免首源独占 |

### P2 — 长期

| # | 改动 | 说明 |
|---|---|---|
| 11 | 可配置 DoH（阿里 / Cloudflare） | 从根上绕开污染；需评估跨境延迟 |
| 12 | 官网模板批量核实（其余 11 家） | 与 S1 同法：实测搜索入口 + 详情页形态后再写入 |
| 13 | `ScrapeBudget` 增加「单源超时计数」日志 | 让「预算被谁吃光」直接进日志，不用事后推 |

---

## 五、待验证项

1. **用户手机的 DNS 是否为污染源** —— 本次取证在本机代理线路完成，与用户手机不是同一网络实例。验证手段：在手机上 `nslookup s1s1s1.com`（或抓 mihomo 日志看重写后的 IP），若返回 `2001::/32` 即可确认。（**Assumed**）
2. **mihomo / jp 节点是否对该域做了 fake-ip 或 DNS 重写** —— 若节点侧已正确解析，则 P0-4 在用户环境里可能不生效。验证：`curl -v https://s1s1s1.com/` 看实际连接 IP。
3. **S1 补模板后能否命中真实元数据** —— 已推进两步：
   - 实测确认 S1 详情页**没有任何机器可读块**（ld+json / `__NEXT_DATA__` / `application/json` / `og:title` 全为 0，`og:image` 是 CDN logo），Generic 档只能拿到 `<title>`；
   - 因此补了 `OfficialExtractionProfile.WorkPageTable` 与对应抽取层，并在**同一份 75918 B 页面**上做本地复刻：标题、121 字剧情、`2014年9月7日`、`TAKE-D`、`星野ナミ`、5 个 `ジャンル`、`DVD 120分`（→ `2:00:00`）全部命中；`メーカー`/`レーベル` 该页没有，按既有兜底回退 `site.studio`。
   **仍未验证**：这条抽取层在真机（OkHttp 实际响应、字符集、Challenge 判据）上是否一致。验证：用 `SNIS225` 跑一次完整刮削，看 NFO 是否落盘。
4. **其余 11 家官网的模板** —— 全部留空，本次只补 S1。验证：按同法实测各家搜索入口后再补。
5. **样本依赖度说明** —— 本次结论建立在 **1 个番号 × 2 次运行** 上。「站点没有」这一条在四个独立源上交叉验证过，可信度高；「占比」数字是按本次日志的墙钟分布估的，**换番号会变**，不能当普适常量。

---

## 附：本次改动清单（见同批提交）

- `scraper/Dmm2Scraper.kt` — 异常带 cause + 文案含「请求失败」
- `scraper/ScrapeFailure.kt` — 分类器优先读结构化 reason
- `scraper/PollutionSafeDns.kt` — 新增：IPv4 优先 + 过滤 `2001::/32`
- `data/repository/StrmScrapeRepository.kt` — client 挂 `callTimeout` + 新 DNS
- `scraper/OfficialMakerCatalog.kt` — s1 条目补实测模板
- `scraper/OfficialMakerScraper.kt` — 按 source 过滤 catalog
- `scraper/OfficialMakerCatalog.kt` — 新增 `OfficialExtractionProfile.WorkPageTable`，s1 条目改用该档
- `scraper/OfficialPageExtractor.kt` — 新增 `workPageRaw` 层（`h2.p-workPage__title` / `p.p-workPage__text` / `div.th` 资料栏）

