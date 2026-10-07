# Sol 审定材料 · 小提交② 代码复审（快照 2026-09-28）

本目录在仓库外，用于承接交给 Sol（当前账户，仅决策与复核）的文件；不随 git 提交，也不含任何原始响应或个人信息。

- 代码基线：`handoff/stage6-local` @ **`97653a4`**（2026-09-28；未推送）；②=`9d40a12`、flake=`7a5eb5f`
- 目的：小提交②已按第四次审定实现并连过 7 轮独立复核；**随后 Sol 的代码复审（`9.0-stage6-specified-semester-code-review5.md`）判暂不通过**，四条已修，请 Sol 再审
- **本包可直接送交**：② 的代码与 Sol 审过、再审的两份增量 diff 都在包内（见下）

## 送 Sol 再审要看什么（按顺序）

| 文件 | 用途 |
| --- | --- |
| `9.0-stage6-specified-semester-code-review8.md` | **Sol 最终判定：本范围通过** —— 指定学期两阶段导入及所收增量的静态复审结束，转真实环境验收 |
| `9.0-stage6-specified-semester-code-review7.md` | Sol 上一轮判定（新阻断：`loggedOut` 被正文覆盖） |
| `9.0-stage6-specified-semester-code-review7-response.md` | 对应回执（含 Sol review8 要求更正的样本描述） |
| `9.0-stage6-specified-semester-commit6.diff` | 最终代码增量（4 文件 / sha256 `370bf06f…`） |
| `9.0-stage6-specified-semester-commit5.diff` | review7 所依据的增量（5 文件 / 76035 B / sha256 `88374455…`） |
| `9.0-stage6-specified-semester-code-review6.md` / `-response.md` | 更早一轮判定与回执（到期与在途下载并发的锁内定序） |
| `9.0-stage6-specified-semester-commit4.diff` | review7 审过的那份增量 |
| `9.0-stage6-specified-semester-code-review5.md` / `-response.md` | 更早一轮（失效会话、TTL、密码时点、响应学年核对） |
| `9.0-stage6-specified-semester-commit2.diff` | 小提交② 的原始 diff（6 文件，规范化 SHA-256 `e8af542e…`） |
| `9.0-stage6-specified-semester-review4.md` | ② 的范围来源（第四次审定的放行与任务拆分） |

**状态**：小提交② 的代码复审**已通过**（Sol review8），循环结束。分支 `handoff/stage6-local` @ `3b2f5d7`，**未推送**（推送需用户确认）。剩余：真机（OPPO/ColorOS）与 CI 验收、阶段 6 下一子任务（稳定课程身份与多课次导入）。

## 本包内容与校验

**要审的东西（代码）**

| 文件 | 内容 | 校验 |
| --- | --- | --- |
| `9.0-stage6-specified-semester-commit2.diff` | 提交 `9d40a12` 的完整差异（6 文件 / 37 hunks / 115393 B） | 文件 SHA-256 = `f37f5d6cb25a3a7380175384905370dfeafe8e8c93d47d0909eb14889eba5b77`；**其规范化内容 SHA-256 = `e8af542e8951e0c5e41ee2e7bdda35aefb90dfe8c4bfcb1ce7d2b48cfeaf9a37`**（= 7 轮复核席核对过、也是提交信息里 `pre-review e8af542e clean` 指的那份，逐字节等价） |

**文档快照（按推荐阅读顺序）**

1. `9.0-stage6-specified-semester-review4.md` — **先读**：Sol 第四次审定的放行与任务拆分（② 的范围来源）
2. `9.0-stage6-specified-semester-audit.md` — 现状审计、方案 A/B、S1–S12 测试矩阵、§7 真实 option 对照
3. `9.0-stage6-test-gaps.md` — 测试缺口标记与最新执行结果（§8）
4. `9.0-stage6-checkpoint.md` — 阶段 6 状态
5. `9.0-stage6-zju-field-evidence.md` / `...-appendix.md` / `...-selection-key-groups.md` — 字段与选择键证据（脱敏）
6. `9.0-stage6-course-identity-design.md` / `...-review2.md` / `...-review3.md` — 身份线设计契约与历次审定（背景）

> 快照一致性：以上 10 份文档均已与仓库 `docs/` 当前版本逐文件比对一致（刷新于 2026-09-28）。仓库文档若再更新，需重新复制。

## 小提交② 与本次修复

- **小提交①**：`4a3d52a` — 纯 option 解析/选择校验/请求表单组合 + 合成测试（预审 `d534089c` clean）
- **小提交②**：`9d40a12` — 两阶段指定学期流程 + 7 轮复核修复（预审 `e8af542e` clean）
  - `git show --stat 9d40a12`：6 文件，**+1744 / −236**；其中 `ZjuTimetableClient.kt` +496、`ZjuTimetableImportActivity.kt` +505、`ZjuTimetableImportActivityTest.kt` +164（新增）、`ZjuTimetableSessionTest.kt` +769（新增）、`ZjuTimetableClientTest.kt` −10、`ZjuTimetableImportUiTest.kt` +36
  - 验证：提交前 debug 与 release 双变体全量单测各 **150 类 933 项 0 失败**（`--rerun-tasks` 真跑）
- 附带（独立提交，与②无关）：`7a5eb5f` — `ReminderReceiverTest` 午夜窗口 flake 修复（+17/−5）

## 本轮预审缺陷修复（3 条 + 复核追加）

首轮预审（hash `1746b170`）指出 3 条缺陷，均由只读复核确认并修复：

1. **配置变更（旋转）后 `running` 卡死**：`onComplete` 先判 `isFinishing || isDestroyed` 就 return，共享 ViewModel 永远收不到复位。现改为：异步状态与终态（`outcome`）全部收敛到 `ZjuImportViewModel`；Activity 用 `LaunchedEffect(outcome)` 观察，`setResult`/`finish` 只在存活实例执行。
2. **后台线程只捕获 `Exception`**：`OutOfMemoryError` 等 `Throwable` 路径不回调。现改为 `catch (error: Throwable)`，并且线程外还有一层兜底，保证任何路径恰好一次 `onComplete`。
3. **第一阶段取消无法中断在途网络**：`cancelSession` 只移除 map 条目。现改为：`ZjuHttpClient` 增加 `cancel()`/`isCanceled()`，`HttpSession` 检查取消标志并断开 `activeConnection`；`ViewModel.cancelAllWaiting()` 无条件取消本实例第一阶段的传输。

复核追加修复（第二轮预审前）：取消与放回的原子性（`restoreLock`）、取消与线程启动的竞态复查、取消不再被报成超时（`CANCELED_MESSAGE`）、孤儿 `PendingSession` 清理、过期会话顺带取消、同步抛错也复位 `running`、失败文案与门控一致。

## 请 Sol 复审的边界

- 只改 `ZjuTimetableClient.kt`、`ZjuTimetableImportActivity.kt` 与相关 ZJU 测试；未改 `Course.id`/导入归并/提醒/Room/版本号
- 密码仍在提交后立即从 UI 与 `CharArray` 清除；`PendingSession` 只持有会话与 option 候选
- 自动路径收紧：无 selected 且多候选 → 失败，不取第一项
- 未推送/未合并/未发布；Room 产品激活仍关闭

## 验证证据（本地·最终）

- 提交前全量：`:app:testDebugUnitTest` 与 `:app:testReleaseUnitTest` → **各 150 类 933 项，0 失败/错误/跳过**（`--rerun-tasks` 真跑，7m55s；本机 Gradle 8.13 + Android SDK android-36，`-g D:\focusflow\.gradle-home`）
- **7 轮独立只读复核**（每轮两席，DSH 等价门禁；末轮两席 CLEAN）。复核共拦下 **4 个阻断缺陷**、多个真缺陷与 **3 处假通过测试**——而那 933 项绿灯对 4 个阻断项的**检出率为 0**
- `git diff --check` clean；APK 双构建与稳定签名核对在 09:2x 那版做过（证书 `650a17f2…`、release `8.3.0-rc.24`/558）；真机与 CI 仍未执行
- 另修既有测试缺陷（与②分开提交 `7a5eb5f`）：`ReminderReceiverTest` 在 23:10–23:54 与 00:20–00:59 会生成非法节次表/查不到开始时间，已把节次表锚定并夹到 1440

## 本轮审查方式与"去哪看"（重要）

- 原 `review_stage` 是 opencode 插件，DSH 无法调用；经用户确认，**按 review-gate 规则在 DSH 内做等价审查**（独立只读复核席 + 逐条文件/行号/触发/证据 + 末行 `REVIEW-VERDICT`），未绕过门禁。策略已于 2026-09-28 起按风险分档（文档见工作区外 `D:\focusflow\.tools\REVIEW-POLICY.md`）。
- **② 的代码不在仓库远端**：分支 `handoff/stage6-local` **未推送**（推送需用户确认）。所以本包才随附 `...commit2.diff` —— **本包是自足的：Sol 拿到这一包即可复审，不依赖任何远端或仓库访问**。若 Sol 恰好能访问本机仓库，可 `git show 9d40a12` 交叉核对，应与包内 diff 逐字节一致。

### 建议 Sol 优先复核的 5 处（复核席认为价值最高的边界）

1. **取消语义的完整闭环**：`cancelSession` / `cancelTransport` 均"锁内摘两张表 + 置 `canceled`"，阻塞的 socket 断开移到锁外；`finalizeAttempt` 是唯一收尾点（成功 `restore=false`、失败按 `sessionUsable`）。请核 `restorePendingLocked` 的 `!canceled` 门禁是否足以防止句柄复活。
2. **恰好一次回调**：`ConfirmAttempt` 三态 + 三条互斥出口 + `runCatching { dispatch }` + 外层兜底 catch；请核是否存在 0 次或 2 次回调路径（会表现为 UI 卡在 `running` 或重复 `finish`）。
3. **会话可用性判定**：取消 → `CANCELED_MESSAGE`；超时/DNS/一般异常 → `sessionUsable=true` 放回句柄；解析失败 → 带回解析层原文案并放回。请核这套判定是否与 UI 文案（`FailureCard`）一致、有没有"让用户重试但注定失败"的路径。
4. **旋转与会话保留**：`onDestroy` 的 `isChangingConfigurations` 门控与 `LaunchedEffect(outcome)` 的消费顺序（先 `consumeOutcome` 再处理），以及 `ViewModel` 跨重建保留 `pendingSession`/`options`。
5. **测试可证伪性**：`ZjuTimetableSessionTest` 中取消/超时/DNS/解析失败各用例是否**真能变红**（把对应生产逻辑改坏它必须失败）；`ActivityTest` 的旋转用例已加 `setQualifiers("+night")` + `assertNotSame` 以保证"重建确实发生"。

## 边界与合规

- 只含脱敏结构、设计与状态文档；不含原始响应、账号、Cookie、课程名、地点、教师姓名、学号或原始 ID 值。
- 原始教务样本与脚本在仓库外 `D:\focusflow\.tmp\zju-evidence-20260927\`，绝不提交或上传。
