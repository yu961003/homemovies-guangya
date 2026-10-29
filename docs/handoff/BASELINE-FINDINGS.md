# 基线核对：修复方案文档 vs 当前源码

> 本文记录动手前对 `文件夹批量刮削Bug根因与修复方案.md` 逐条核对的结果。
> 结论：文档基于 `GY20260911_145726.zip` 基线，**当前源码已修复其中 4 条**，剩余 4 条真实存在。
> 后续改动只针对「仍存在」的部分，避免重复实现已有逻辑。

## 一、文档结论中已失效的（当前源码已修）

| 文档条目 | 文档说法 | 当前源码实际 |
| --- | --- | --- |
| P0-1 / A1（杀手 1） | `start()` 里 `job?.isActive == true` 直接 return，`prepareForStart()` 永不执行 | `CloudFolderBatchTaskRunner.kt:40-55`：短路仍在，但 `runMutex.withLock{}` 内部**已无条件先调用** `taskRepository.prepareForStart()`（第 45 行），僵尸 `Running` 会被 `prepareForStart()` 的 SQL 复位为 `Pending`。**A1 已修复** |
| B1（预插入整个文件夹） | `enqueueFolder()` 把文件夹内所有 pickcode 预插入 `cloud_video_tasks` | `CloudFolderBatchTaskRepository.kt:21-49` 现在只创建/复用**一条** `cloud_folder_batch_tasks` 记录，不再逐条 enqueue。**B1 已修复** |
| C1（按钮接线不完整） | 「开始所有刮削任务」未调用 folder runner | `SettingsViewModel.kt:1042-1060` `startCloudFolderBatchTasks()` 已调用 `cloudFolderBatchTaskRunner.start()`，`SettingsScreen.kt:2376` 已接线。**C1 已接线** |
| C3（统计不含文件夹） | 面板统计只有影片+网盘视频 | `SettingsScreen.kt:2424-2438` 已有 `folderUnfinished` 并拼接进统计行。**C3 已修复** |

## 二、仍然真实存在的缺陷（本次要修）

| 编号 | 现象与根因（当前代码定位） | 修复策略 |
| --- | --- | --- |
| **F-1 断点不保留** | `CloudFolderBatchTaskRepository.resetForRun()`（`:73-93`）把 `processedVideos/addedVideos/skippedVideos/failedVideos/failedFolders` 全部清零；`CloudFolderBatchTaskRunner.runTask()`（`:91-199`）每次从 `resetForRun` 起步、全量重新枚举候选并重新提交，等于「全量重跑」 | 新增 `processedPickcodesCsv` 列持久化已处理 pickcode；`resetForRun` → `resumeForRun`（保留计数，只清 `failedVideos/failedFolders/failureMessage`）；runner 读回跳过集合，命中直接计 skipped 且不再 enqueue；候选表在 Collection 后按 pickcode 过滤 |
| **F-2 「已添加」误判** | `CloudBrowserViewModel.loadCurrent()`（`:862`）与 `searchFiles()`（`:154`）用 `existingPickcodesForVisibleItems()`，其语义是「`cloud_strm_records` 里有没有这个 pickcode」；而该表在 **STRM 落盘时**就写入（`CloudStrmRecordRepository.upsertGenerated`），`movieId` 可能为 null。于是「STRM 已生成但刮削失败/未完成」的影片也显示「已添加」并置灰不可重试 | 保留 `addedPickcodes` 语义不变（列表用），新增 `queuedPickcodes`（记录存在但 `movieId == null` = 已入库未刮完）与 `failedPickcodes`（`movies.scrapeTaskStatus='Failed'`）；UI 拆三态：已添加（置灰）/ 刮削中（置灰+文案）/ 刮削失败（**可点重试**） |
| **F-3 刷新按钮是摆设** | `SettingsViewModel.refreshCloudFolderBatchTasks()`（`:1095-1101`）只改文案 `"任务列表已刷新"`，不读库、不查运行态 | 改为真实查询：`isRunning.first()` + `observeUnfinishedTaskCount().first()` + 各状态计数，文案给出「运行中/未运行 + 未完成 N 项 + 失败 M 项」；同时把 runner 的 `lastProgressAt` 纳入，长时间无进展时提示 |
| **F-4 终止路径缺 failureMessage** | `CloudFolderBatchTaskRunner.runPendingTasks()`（`:71-86`）在 `stop()` 路径只 `markPaused(taskId, "已手动停止")`，`cancel()` 路径只写日志不落库；`stop()` 不写库、之后 `prepareForStart()` 会把 `Running→Pending` 且清空 message，用户看不到中断原因 | `cancelRequested` 路径改为 `markCancelled()`（新状态 `Cancelled`，`prepareForStart()` 的 SQL 也需包含）；`stop()` 在协程 `finally` 里把当前任务落 `Paused` 并写入原因与进度快照 |
| **F-5 冷启动不自动恢复** | `LocalMovieLibraryAppRoot.kt` 的启动 `LaunchedEffect` 只做更新检查与 source backfill，没有恢复 folder runner；文档 P0-1 第 3 点未落地 | AppRoot 启动时调用 `appContainer.resumeInterruptedTasks()`：folder runner `start()`（其内部 `prepareForStart` 会复位僵尸 Running）+ 新增的 TaskRun 账本 `recoverInterrupted()` |

## 三、与文档表述不一致但需保留的既有行为

- `prepareForStart()` 的 SQL 当前覆盖 `Running/Paused/Failed → Pending`，本次扩展为同时覆盖新增的 `Cancelled`；**不清零进度字段**（这是断点续跑的前提）。
- 单视频队列（`cloud_video_tasks`）的 `Completed` 判定不参与 `isAdded`，`isAdded` 只看 `cloud_strm_records`；因此 F-2 的修复必须靠 `movieId + movies.scrapeTaskStatus` 联合判断，不能改成依赖单视频任务表（文件夹批处理会绕过该表直接走 `CloudVideoAddProcessor`）。
- 文档建议的「入队层不预插入」已经实现，无需再改 `enqueueFolder` 的入队行为，只新增断点字段即可。
