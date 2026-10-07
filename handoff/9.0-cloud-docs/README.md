# FocusFlow 9.0 云端文档归档（plan / principles / status / changelog）

本目录是 **只归档、不改产品** 的文档快照，由用户指令「把 9.0 相关的计划、原则、现状、变更历史文件推送至云端」生成。

- 归档时间：2026-10-07
- 来源：`D:\focusflow\`（工作区根目录）与 `D:\focusflow\focusflow-9.0\`（本仓库），以及仓库外的 `D:\focusflow\sol-review\`、`D:\focusflow\backups\`
- 推送目标：远端 `origin` = `https://github.com/five5-7/focusflow-android.git`，分支 `handoff/stage6-local`
- 本目录只新增文件，**未修改、未删除、未回退任何既有文件**；未改产品代码、Room schema、版本号或发布文案

## 目录结构与四类对应关系

| 目录 | 类别 | 内容 |
| --- | --- | --- |
| `01-plan/` | 计划 | `FocusFlow_9.0_实施计划.md`（工作区根目录最新版，138 KB；阶段 0–9 路线、检查点、候选版本策略） |
| `02-principles/` | 原则 | `AGENTS.md`、`VERSIONING.md`、`9.0-ai-collaboration.md`、`FocusFlow_9.0_AI_使用手册.md` |
| `03-status/` | 现状 | 交接单（根目录版与仓库版并存）、阶段 2/2A/4A/5/6 检查点与审计、阶段 6 身份线与指定学期线全部设计与复审件、`sol-review-request-20260930.md`、`阶段9-prompt修订与复跑结果.md`、`阶段9-真实key提供方式.md`、`阶段9-视觉导入诊断与修复方案-B类.md` |
| `04-changelog/` | 变更历史 | `CHANGELOG.md`、`backup-log.md`、`sol-review-*`（历次外部复审与回执全文）、`阶段9真机验收-第2–8轮记录.md`、`阶段9真机验收-覆盖缺口清单.md`、两份真机验收报告 |

## 命名约定与去重说明

同一份文档在两个位置内容不同时，**两份都保留并加前缀区分**，不做覆盖：

- `root-*`：来自 `D:\focusflow\` 工作区根目录的版本
- `repo-*`：来自本仓库 `docs/` 的、git 已跟踪的版本
- 无前缀（如 `阶段9-*.md`、`FocusFlow_9.0_实施计划.md`、`9.0-harness-handoff-20260927.md`、`sol-review-request-20260930.md`）：只存在于工作区根目录、仓库中无对应跟踪文件的版本
- `sol-review-<原名>`：来自仓库外 `D:\focusflow\sol-review\` 的复审材料原始件

已知内容差异（重要，勿按同名视为同一份）：

- `03-status/root-9.0-ai-handoff.md` 与 `03-status/repo-9.0-ai-handoff.md` 内容不同：前者是工作区根目录的 AI 交接单，后者是本仓库已跟踪的 `docs/9.0-ai-handoff.md`
- `03-status/root-9.0-stage6-specified-semester-review4.md` 与 `03-status/repo-9.0-stage6-specified-semester-review4.md` 内容不同：前者来自工作区根目录，后者来自本仓库 `docs/`，两份都保留（2026-10-07 补漏提交补入前者）

> 补漏说明：本目录首次推送为 `cbf82da`（69 份）。首次归档时漏收工作区根目录版 `9.0-stage6-specified-semester-review4.md`，已由后续补漏提交补入 `03-status/root-9.0-stage6-specified-semester-review4.md`，并把本 README 与 `04-changelog/backup-log.md` 更新到最新。

## 未包含项（有意排除）

- `sol-review/*.diff`：阶段 6 指定学期的代码增量差异文件，属代码差异而非四类文档；如需一并归档请另外说明
- `backups/*.bundle`、`*.zip`、`apk/`：二进制交接/构建产物，与本轮「文档」口径无关
- `legacy/`、`focusflow-7-3/`、`focusflow-7-4-design/`、`focusflow-7-5-design/`、`stage9-run554-acceptance/`：旧快照与其他工作树，不含 9.0 四类文档的唯一权威副本

## 与既有规则的冲突提示

历史交接单中多处写着「推送需用户明确同意」「未推送」，这些约束针对的是**未合并的开发提交与 `agent/focusflow-9.0-audit` 分叉分支**。本目录推送的是 `handoff/stage6-local`（该分支早已与远端同步，此前多次推送均经用户授权），且只新增文档、不触代码。若后续要把产品代码提交一并推送或合并，仍需用户单独确认。
