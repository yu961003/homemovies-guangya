# 本次增强的架构决策（Lead 冻结）

## 0. 环境事实

- 无 JDK / Gradle / Android SDK：**全量编译验证在本环境不可执行**，交付物附带 `VERIFY.md`。
- Room schema 现状：`AppDatabase.version = 18`，`exportSchema = true`，`app/schemas/.../17.json` 遗留（18 未导出），新迁移由 `AppContainer` 的 `MIGRATION_17_18` 模式手写。
- 迁移必须在 `AppContainer.addMigrations(...)` 注册，并 bump `AppDatabase.version`。

## 1. 与 peach 的映射关系（学习而非照搬）

| peach（Python/FastAPI/SQLite） | 鸭影（Kotlin/Android/Room）的落法 |
| --- | --- |
| `field_owners` 表钉「谁写了这个值」 | `movie_field_provenance` 表：`(movieId, fieldKey)` 主键，记录 source/authority/confidence/updatedAt/manualOverride |
| `POLICY_VERSION` + 来源分级决定自动应用 | `MetadataAuthority` 枚举 + `MetadataMergeEngine` 纯函数决策（Apply/Skip/KeepManual/NeedsReview），带理由码 |
| `entity` 三分法与规范名 | `EntityNameNormalizer` + `entity_aliases` 表：只做「同一个人的多种写法」归一，不引入 peach 的 6 种 kind |
| 采集层 `FailureReason` 12 档 + 来源链 + 403 记账 | `ScrapeFailureReason` 枚举 + `SourceHealthRegistry`（连续失败/冷却）+ `ScrapeSourceChain`（按等级与健康度排序，硬阻断即停） |
| `task_run` 单表六态 + 部分唯一索引互斥 + `recover_interrupted` | `task_runs` 表 + `TaskState` 六态 + `TaskRunCoordinator`（进程内 Mutex + 活跃态唯一约束）；因 Room 不支持部分唯一索引，互斥用「事务内 SELECT + 进程内 Mutex」并在文档中说明失败模式 |
| `/stream` 决策链 + 三个异常三状态码 + Range | Android 无服务端，等价能力是**直链租约**（`DirectLinkLeaseStore`：提前刷新、单飞、退避）+ `PlaybackFailure` 分类与一次性自动重试 |
| 侧挂字幕 / 外部播放器签名 | `SubtitleSidecarLocator`（同名 srt/ass/vtt + 语言推断）+ `ExternalPlayerIntentFactory`（续播位置） |

**不做的事**：不引入服务端、不引入 Node、不引入新依赖、不复制 peach 的代码结构（Python 模块/ADR 目录不搬到 Kotlin）。

## 2. 写作用域（避免并发冲突）

| 区域 | 文件 |
| --- | --- |
| 云盘批处理修复 | `data/repository/CloudFolderBatchTask*`、`CloudVideoAddProcessor`、`CloudStrmRecordRepository`、`ui/cloud/*`、`ui/settings/*` |
| 元数据 | `scraper/metadata/**`（新）、`data/local/MovieFieldProvenance*`、`data/local/EntityAlias*`、`data/repository/MovieMetadataLedgerRepository.kt` |
| 刮削 | `scraper/**`（除 `scraper/metadata/**`）、`data/local/SourceHealth*`、`data/repository/SourceHealthRepository.kt` |
| 任务系统 | `tasks/**`（新）、`data/local/TaskRun*`、`data/repository/TaskRunRepository.kt` |
| 播放 | `playback/**`、`ui/player/**` 最小侵入 |
| **Lead 独占** | `data/local/AppDatabase.kt`、`data/AppContainer.kt`、`ui/LocalMovieLibraryAppRoot.kt`、`data/local/MovieEntity.kt`、`data/local/MovieDao.kt`（新增查询除外）、`data/repository/MovieRepository.kt`、`data/repository/StrmScrapeRepository.kt`、`app/build.gradle.kts` |

## 3. 数据库变更总表（18 → 19）

新增表：
1. `task_runs`（任务账本）
2. `movie_field_provenance`（字段归属）
3. `entity_aliases`（别名归一）
4. `source_health`（来源健康度）

新增列：
5. `cloud_folder_batch_tasks.processedPickcodesCsv TEXT NOT NULL DEFAULT ''`
6. `cloud_folder_batch_tasks.lastProgressAt INTEGER NOT NULL DEFAULT 0`

版本：`AppDatabase.version` 18 → 19，新增 `MIGRATION_18_19`。

## 4. 验收口径

- 每个新增公共 API 必须有调用点（不能只建文件不接线）。
- 每个新逻辑模块必须有 JUnit4 纯 JVM 单测（项目已有 `junit:junit:4.13.2`，测试目录 `app/src/test/java/...`）。
- 所有 Room 查询字段名必须与 Entity 属性一一对应（无编译器，靠人工核对）。
- `docs/handoff/*` 记录每个新增 API 的签名、调用点、DDL 原文。
