# 批量重命名 G 轮修复（第十三轮）· 变更说明

> 基线快照：`GY20260827_172108.zip`
> 修复模型：**传播窗口黑盒** —— 服务端目录索引在写操作后存在秒级传播窗口，窗口内客户端的**读和写都不可信**。
> 核心结论：一切历史失败（D / F / 第十二轮）都发生在**依赖链**上（目标名 = 前序项的旧名）；而**无冲突面**的改名从未失败过。
> 破局：把「有依赖的改名」分解成**两次无冲突面的改名**。

---

## 一、改动文件清单（4 个）

| 文件 | 修复项 | 说明 |
|---|---|---|
| `app/src/main/java/com/example/localmovielibrarz/cloudguangya/GuangyaBrowserRepository.kt` | **G-1 / G-2 / G-3① / G-4** | `batchRenameFiles` 重写为两阶段临时名中转；移除 F-1 校验补偿循环 |
| `app/src/main/java/com/example/localmovielibrarz/ui/guangya/RenameOps.kt` | **G-3②** | 新增 `visualizeInvisibleChars()` |
| `app/src/main/java/com/example/localmovielibrarz/ui/guangya/GuangyaBrowserViewModel.kt` | **G-3②** | 两处失败消息接入不可见字符可视化 |
| `app/src/test/java/com/example/localmovielibrarz/ui/guangya/RenameOpsTest.kt` | 测试同步 | 删除 4 个废弃 F-1 测试，新增 G-1 逻辑测试 |

其余 219 个文件与基线快照完全一致。

---

## 二、G-1（P0，核心）：两阶段临时名中转

### 依赖项判定
目标名（规范化 `renameNameKey` 后）命中**任何待改名文件的旧名**即判为「依赖项」——不止拓扑前驱，因为窗口内**任何腾名假设都不可信**。自身旧名排除在外（自改自名不算依赖）。

### 执行链路

```
splitIndependentDependent(renames, namesById)
        │
        ├── independent（目标名不撞任何待改名旧名）
        │      └─ 第 1 段：直改。无 delay / 无临时名 / 无额外 listFiles
        │         （历史证明该路径从未失败；无依赖场景零劣化）
        │
        └── dependent（目标名命中他项旧名）
               ├─ 第 2 段：全部改到临时名 tmp-ren-{n}-{原名}
               │            ★ 零冲突面：三不撞（目录现有名 / 全部目标名 / 彼此）
               ├─ 第 3 段：delay(3_000) 等传播窗口过 → listFiles 校验临时名
               │            （窗口后读可信；虚假成功项在此重发，临时名零冲突面重试必成）
               └─ 第 4 段：临时名 → 目标名
                            ★ 零冲突面：所有旧名已实际腾出，目标名唯一「占用者」
                              是自己的临时名（同 fileId）→ 服务端无冲突可判
```

### 与 F-1 的本质区别

| | F-1（第十二轮，已移除） | G-1（本轮） |
|---|---|---|
| B 的写目标 | 直接改「A 的旧名」 | 先改临时名，窗口后再改目标名 |
| 窗口内该名占用状态 | **不确定**（赌黑盒） | 临时名**不存在任何占用争议** |
| 失败后手段 | 校验 + 补偿重试（读也不可信 → 误判 → 恶化） | 无需补偿，每次写都落在无冲突面 |

### 覆盖场景
- **F 轮场景**（B 目标名 = A 旧名、B 在前）→ 仅 B 判依赖，走临时名中转。
- **互换名 A↔B** → 双方目标名均命中对方旧名，均判依赖，各自经临时名两阶段，天然正确。
- **环形依赖（3 环及以上）** → 同上，无需环检测。
- **D 轮拓扑顺序问题** → 已并入依赖判定，不再依赖排序正确性。

---

## 三、G-4（P0）：移除 F-1 校验补偿循环

删除内容：`maxVerifyRounds` 轮询、`PendingRename` 数据类、`partitionRenameResults()`、终局「未生效」判死逻辑。

**正当性**：第十二轮失败项 2（`12345.com@YUI-XXX`「服务端返回成功但未生效」）实为 —— 校验时读到旧索引 → 判未生效 → 补偿重试受理成功 → 轮次达上限退出 → **未经二次校验直接判死**。补偿大概率已真实生效，属 **App 误报**。窗口内的读本就不可信，该机制从原理上不成立。

唯一保留的校验是 G-1 第 3 段（窗口**后**的临时名确认）——时机正确所以可信。

`orderRenamesByDependency` 作为纯工具函数保留（D 轮回归测试仍覆盖）。

---

## 四、G-2（P1）：失败原因标注来源

本地 `validateGeneratedName` 拦截的失败原因统一加 **`本地校验：`** 前缀；服务端拒绝保持响应原文。

用户下次看到「名称不能以空格开头或结尾」可一眼分辨是谁拦的（本地文案「名称不能…」vs 服务端文案「文件名不能…」，此前一字之差极易混淆）。

---

## 五、G-3（P1）：不可见空格硬防御 + 可视化

- **① 发送前硬 trim**：新增 `safeRenameItem()` 作为 Repository 层统一出口，所有 rename 请求经此发出。发送前 `trim()`，若发生变化则打点：
  ```
  Log.w("GuangyaBatchRename", "发送前清除不可见首尾空格：'…'")
  ```
  覆盖一切上游漏 trim 的实现偏差（无论空格来自生成链哪个分支）。
- **② 失败名可视化**：`visualizeInvisibleChars()` 把空格渲染为 `␠`。失败项若为空格问题，用户直接看到 `ABC-XXX␠.restored`，即刻定位。

---

## 六、临时名残留防御

阶段二若被服务端拒绝（如目标名含服务端不接受的字符），该项**停留在临时名、不回滚、不静默丢弃**，计入 `failed` 并提示：

> 已中转至临时名「tmp-ren-…」，请手动改名

——不允许静默残留，用户始终知道文件的真实状态。

---

## 七、回归验证清单

| 场景 | 验证项 |
|---|---|
| 先核对现状 | 网页端查看 `12345.com@YUI-XXX` 当前名 —— 预期已是目标名（证实补偿生效、App 误报，即 G-4 移除的正当性） |
| 复刻 F 场景 | 多队列依赖项（B 目标名 = A 旧名，B 在前）→ `succeeded=2`、`failed=0`；网页端核对真实改名、无 `(1)` 后缀、无残留 `tmp-ren-*` |
| 临时名残留防御 | 构造阶段二被拒 → 该项停在临时名并计入 failed，带手动改名提示 |
| 无依赖零劣化 | 普通多选改名 → 走第 1 段直改，无 delay / 无临时名 / 无额外 listFiles，与 F-1 前行为一致 |
| B 空格场景 | 「替换为」输入带尾随空格 → 失败名显示 `␠` + `本地校验：` 前缀；logcat 有「发送前清除」告警 |
| 既有回归 | D 轮拓扑、互换名、CD 净化、单规则路径 |

### 取证指令（B 项空格来源，修复后仍出现时执行）

```bash
adb logcat -s GuangyaFileOp:D GuangyaBatchRename:W
```

`GuangyaFileOp` 日志含每条 rename 请求的 `newName` 原文（`{"fileId":"…","newName":"…"}`，引号内空格无所遁形）：
- 引号内有首尾空格 → 本地生成链漏 trim（按 `GuangyaBatchRename:W` 的「发送前清除」日志定位上游）
- 无空格却被拒 → 服务端文案变体，记下响应 `msg` 原文补充分析

---

## 八、构建说明

沙箱无 Android SDK，未执行 Gradle 构建。已做的替代验证：
1. 独立脚本仿真 `splitIndependentDependent` + `uniqueTempName`，确认 F 场景、互换名、3 环、无依赖、自改自名、撞无关现有文件六类输入均符合预期。
2. 逐行复核 `suspend` 传染性、`delay`/`Log` 导入、`BatchRenameResult` 字段兼容性。
3. 全项目 grep 确认无 F-1 移除物的悬空引用。

请在本地执行：

```bash
./gradlew :app:compileDebugKotlin        # 编译校验
./gradlew :app:testDebugUnitTest         # 单元测试（RenameOpsTest）
```
