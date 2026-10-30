# 第四轮反馈修复记录（2026-10-30）

对应反馈四条：exo 仍掉帧 / 刮削链重复请求且全源失败 / 设置-文件夹发现任务看不到进度也无法暂停 / LUT 仍无效且自动调参提示抓不到画面。

## 1. 刮削链全源失败：真根因是读取层写错（不是网络、不是站点拦爬）

### 结论

`scraper/ScraperResponseGuard.kt` 的 `readLimited()` 用 `body.source().readByteArray(maxBytes + 1)` 读正文。Okio 的 `readByteArray(byteCount)` 语义是**恰好读满 byteCount**，读不满就抛 `EOFException`（且该分支抛出的异常没有 message）。上限是 1 MiB（DMM/DMM2/OnePondo）~4 MiB（其它），而正常页面只有几十到几百 KB —— **每次读正文都必然抛 EOFException**。

这解释了真机日志里三个毫无关系的站点在同一时刻报同一个裸 `EOFException`：

```
21:29:27 [Official] 类型=Network ... EOFException
21:29:38 [DMM2]     类型=Network ... EOFException
21:29:45 [AVBase]   类型=Network ... EOFException
```

也解释了为什么 `TheJavDB` 能正常返回 `HTTP 404` —— 它只看状态码，不经过 `readLimited`。

`readLimited` 的调用点共 12 处，全部受影响：`AndroidSourceFetch` / `Avbase` / `CustomJson` / `Dmm2` / `Dmm` / `Fc2` / `Javbus` / `Mgstage`（两处）/ `Official` / `OnePondo` / `PeachCoverProbe` / `R18Dev`。

### 修法

改成按块读、读满或读到 EOF 为止，超限才抛错：

```kotlin
val source = body.source()
val buffer = Buffer()
var total = 0L
while (true) {
    val read = source.read(buffer, maxBytes + 1 - total)
    if (read == -1L) break
    total += read
    if (total > maxBytes) throw IOException("响应体超过 ${maxBytes / 1024 / 1024}MB 限制，已中止读取")
}
return buffer.readByteArray()
```

### 站点本身有没有这部片（实测）

| 来源 | RBD-883 | 对照 RBD-885 | 判定 |
| --- | --- | --- | --- |
| TheJavDB `api.thejavdb.net/v1/movies?q=` | HTTP 404 `{"error":"No videos found"}` | HTTP 200，完整 JSON（标题「高慢社長夫人 凌●制裁 夏目彩春」） | 端点活着，RBD-883 确实没有 |
| AVBase `avbase.net/works?q=` | HTTP 200，`__NEXT_DATA__` 里 `total=0`、`works=[]` | `total=1` | 站点可用，RBD-883 没有 |
| DMM GraphQL（用源码 `SEARCH_QUERY` 原文重打） | `totalCount=0` | `totalCount=1`（id=`rbd00885`） | 接口可用，RBD-883 没有 |
| ATTACKERS 官网 | 首页 200 但 `<form>` 数为 0；`/search/list?keyword=` 返回 37 KB 固定壳、0 个作品链接 | 同左 | 内容由 JS 渲染，服务端拿不到 |

DMM 号段抽样（同系列）：`rbd00880/881/882/883/884/890` 全 0，`rbd00885` = 1。-> 该系列在 DMM 有收录，但 883 这个号确实没有。

**所以「这么多来源一个都没成功」有两层原因**：①读取层 bug 让所有走 `readLimited` 的站必然失败；②RBD-883 这部片在 TheJavDB / AVBase / DMM 三个独立库里确实都没有。修好①之后，可用的站能正常返回；这部片仍会因「确实没有」而失败 —— 但日志会如实显示 `NotFound` 而不是 `Network`。

## 2. 刮削链重复请求

`scrapeWithFallback()` 在桃链返回 `NoResult` 后降级到 legacy 链，两条链各自用 `routeForPeach` / `routeFor` 重建一份几乎相同的来源顺序 —— 同一个号、同一个站，一次刮削里最多被打 6 次（每链 3 次重试）。

修法：桃链通过出参集合 `visitedSources` 记录「真的发过请求的来源」（配置性跳过不算），降级时传给 legacy 链，legacy 建好 plan 后过滤掉这些来源，并输出一行日志：

```
已跳过桃链问过的 5 个来源，不再重复请求：DMM2、FALENO 官网、DAHLIA 官网、Official、AVBase
```

若过滤后为空（桃链已问遍），退回原顺序再兜一次并说明原因。

## 3. 官网首页抓取失败被误判 ParseError

`OfficialSiteScraper.discoverSearchPrefix()` 只返回 `String?`，首页抓取失败和「首页没有搜索表单」都返回 null，上层统一落成「这家片商没有配置取数模板」并归 `ParseError`（冷却 300 秒）。而真机日志里的首因是「首页取不到（网络或传输故障）」—— 明明是网络问题，却按配置问题冷却。

修法：引入 `PrefixDiscovery` 三态（`Found` / `NotFound` / `Unavailable(reason, detail)`），`Unavailable` 按真实 `PeachFailureReason`（Network / Timeout）归类并带上 detail。

## 4. exo 掉帧口径漏掉 skipped

真机日志：`droppedFrames=18 skippedOutputBuffers=140 renderedOutputBuffers=63`。`skippedOutputBufferCount` 是「已解码但渲染端来不及显示而丢弃」，正是体感卡顿最直接的那一类，但旧公式 `deltaDropped / (deltaDropped + deltaRendered)` 把它完全排除在外，且首采样 delta 全 0 → 界面显示 `dropRate=0%`。

修法：分母改为 `deltaDropped + deltaSkipped + deltaRendered`，新增 `renderLossRate` 与 `absoluteLossRate`（用累计值算，首个采样就有意义数字），过载判据改用 `renderLossRate`。

## 5. mpv 帧统计从不触发

`frameStatsPoll` 的守卫是「三值都 ≥ 0 且数值发生变化才回调」。真机 39 秒里一条 `player.mpv.frames` 都没有 —— 要么三值读不到（-1），要么三值恒 0（这 39 秒确实没丢帧），两种情况都被当作「没什么可报」静默掉了。

修法：取消 changed 守卫，改为「首采样必报 + 数值变化时再报」；计数不可读时累计到第 3 次报一条 `readable=false` 的事件；事件里同时带上 `vo-configured` / `current-vo` / `estimated-vf-fps` / `display-fps` —— 用来区分「VO 没起来（黑屏）」和「解码在跑但不出帧」。

## 6. LUT 开了和没开一样

`applyLut()` 是「异步落盘 → post 到主线程 → `mpv_set_property_string("target-lut")`」，而 `loadfile` 紧随其后。`target-lut` 是 VO 级选项，改它要重建渲染管线；VO 在 loadfile 时就初始化完了，之后再设不会重新渲染。

修法：`initialize(initialLutSettings)` 在 `mpv_initialize()` **之前**把 `target-lut` 作为启动选项下发（`MpvOptions.build` 新增第 5 个参数），运行期滑杆改动仍走 `applyTargetLut` 热更。

## 7. 自动调参「抓不到画面」

`captureFrameForAnalysis()` 里 `MPVLib.cmd("screenshot-to-file", ...)` 的返回值拿不到（JNI 是 `void`），失败只剩一条「截帧超时（2000ms）」。

修法：① `MpvOptions` 加 `screenshot-sw=yes` —— hwdec=mediacodec* 的零拷贝帧上 `Failed creating target FBO for screenshot!` 是已知失败路径，软件截图绕开；② 截帧失败时把失败原因 + mpv 日志尾部拼进错误提示；③ 超时 2000ms → 4000ms。

## 8. 设置-文件夹发现任务看不到进度也无法暂停

`CloudFolderBatchTaskList` 用 `filter { it.status != Completed }`。上一轮把「全部跑完但有刮削失败」的任务改成 `Completed` 之后，这类任务直接从列表消失 —— 用户既看不到进度，也没有暂停/继续按钮。

修法：未完成任务之外，保留最近 3 条已完成（灰显，只显示统计，不显示取消按钮），空态文案改为「暂无文件夹发现任务。整夹添加后会在这里显示进度，可在设置页随时回来查看。」

## 9. 诊断日志字段

- `player.dropFrames`：新增 `renderLossRate` / `absoluteLossRate` / `renderedOutputBuffers`。
- `player.decoderOverload`：新增 `renderLossRate`。
- `player.mpv.frames`：新增 `readable` / `voConfigured` / `currentVo` / `estimatedFps` / `displayFps`。

日志页的重点字段表同步更新。

## 提交与验证

| 提交 | 内容 |
| --- | --- |
| `145a920` | 上述 1–8 全部落地（7 文件，+365 −59） |
| `bad1b8a` | 诊断日志字段（第 9 项） |

CI：`41070534257`（145a920）11/11 success；`41071468883`（bad1b8a）见后续回执。

## 未验证项

1. 修好 `readLimited` 后，各站在真机上是否真的能返回数据 —— 需要一次真机刮削看日志（判据：日志里不再出现裸 `EOFException`，`TheJavDB` / AVBase / DMM2 至少有一个能进入解析阶段）。
2. RBD-883 在 TheJavDB / AVBase / DMM 三个源确实没有 —— 已实测，但样本只有 1 个号（+ RBD-885 作对照）。
3. `screenshot-sw=yes` 能否让自动调参抓到帧 —— 需要真机点一次「自动调参」。
4. LUT 在 init 前下发后画面上是否真有变化 —— 需要真机肉眼看（可用强度 100% 的极端参数放大差异）。
5. mpv 的 `frame-drop-count` 在本包 libmpv 上是否可读 —— 下次真机日志看 `readable` 字段。
