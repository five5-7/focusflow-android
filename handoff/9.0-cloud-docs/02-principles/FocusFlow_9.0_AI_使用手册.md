# FocusFlow 9.0：低额度 AI 开发使用手册

- 版本：v1.7
- 更新：2026-09-27（北京时间）
- 适用：已有 **Claude CLI、DeepSeek API、OpenCode**，想减少 ChatGPT Work／Codex 本账户的用量，同时避免便宜模型理解片面造成反复返工。
- 本手册是操作指南；功能事实以当前仓库、`AGENTS.md`、`docs/9.0-stage6-checkpoint.md` 和最新《FocusFlow_9.0_实施计划》为准。当前本地 HEAD `417f1bc`（L2 文档提交后会再变化；接手前用 `git log -1 --oneline` 重新核实）。

## 先记住这三个东西

| 名称 | 白话解释 | 你现在用什么 |
| --- | --- | --- |
| 模型 | 负责理解和写答案的“大脑” | DeepSeek Flash 执行范围明确的任务；可用时 GLM 独立挑错；高风险规则由这里的 Sol 审定 |
| API | 调用模型的独立计费入口 | 已有 DeepSeek API；GLM 可另开独立额度或按量账户，先核对费用，不要求立即购买 |
| Harness | 让模型能读仓库、改文件、运行命令的“工作台” | OpenCode；Claude CLI 可作备用工作台，同一模型换工作台并不会自动变聪明 |

**换工作台不等于换大脑。** Claude CLI 接 DeepSeek API 时，模型仍是 DeepSeek；在同一个 OpenCode 里从 DeepSeek 切换到 GLM 才是换模型。OpenCode Zen 走另一个服务的账单。每次开始前核对提供方与实际模型。测试、CI 和手机才是代码是否可用的证据。

## 不用纠结的默认选择

1. **默认用已有配置：OpenCode＋DeepSeek Flash。** 先在 Plan 模式核对代码和验收，再用 Build 模式修改限定文件；自动测试、CI 与真机结果决定是否完成检查点。
2. 简单、可独立验收的测试矩阵、纯 Kotlin 测试和局部界面交给 DeepSeek。课程身份／Room 旧数据迁移／通知调度等高风险改动，先让这里的 Sol 一次性定规则、失败路径与验收，再交给 DeepSeek 实施。独立模型复核用于 C 给出证据但结论存疑的发现、高风险差异或两轮失败后的纠偏。
3. C 步骤如果挑出问题，先按原代码和实际测试逐项确认；若想增加独立模型，可在 OpenCode 新会话使用 GLM API 做只读复核，再比较它与 C 找到的问题。已有可用额度的真正 Claude 也可选。Claude CLI 若连接 DeepSeek，就不算独立模型。
4. 计划、编码、审查依次完成，每个检查点只指定一个写代码者。互不依赖且各用独立工作树的任务才并行；不让两个 AI 同时修改同一文件。

| 任务 | 工具／模型 | 需要交回 |
| --- | --- | --- |
| 查现有测试缺口、写一张任务卡 | OpenCode Plan＋DeepSeek Flash | 真实文件／测试名、缺失输入与反例 |
| 补一项纯 Kotlin 测试、做小范围 Compose 界面 | OpenCode Build＋DeepSeek Flash | 有限 diff、测试命令和实际结果 |
| 课程身份、多课次导入、Room 迁移 | 这里的 Sol 审定契约 → DeepSeek 按小任务实现 → GLM／真正 Claude（有额度时）或这里的 Sol 复核关键差异 | 旧 ID 映射、歧义样例、恢复与失败用例 |
| 课程／独立提醒跨类型调度 | 这里的 Sol 定边界 → DeepSeek 分模块实现 → 自动验证＋必要时独立复核 | 时区、静音、旧广播、重启的测试与真机清单 |
| 复核复杂改动 | 可选 OpenCode Plan＋GLM API，或 Claude CLI＋真正 Claude；不可用时由这里的 Sol 仅复核高风险差异 | 具体缺陷位置、复现条件及证据 |
| 安装前验收与发布 | GitHub Actions 构建＋你的 OPPO 真机；发布仍要你确认 | 双变体测试、签名 APK、实际通知时刻与设备状态 |

## 第零步：先让本地仓库包含真正的阶段 6 代码

当前 GitHub 远端与活跃本地分支分叉：编写本手册时，本地领先 **59** 个提交；本检出的远端跟踪引用另有 **32** 个提交不在本地（2026-09-26 实测，刷新远端被网络重置）。直接把 GitHub 网址交给外部 AI，它会漏掉阶段 5／6 本地进度。

- 若你手上的仓库已经在 `agent/focusflow-9.0-audit` 且 `git log -1 --oneline` 显示 `4708ce0`（或更新的本地提交），继续下一步。
- 否则先下载[本地提交包](sandbox:/workspace/scratch/4554773389c8/focusflow-9.0-local-handoff-20260926.bundle)，在 Windows PowerShell 中到一个**新建的空目录**执行下面命令。此下载链接属于当前工作区；以后若失效，应从活跃 Git 检出重新导出，不能把旧 GitHub 远端当作相同快照。示例假定提交包在“下载”文件夹；若实际位置不同，改文件路径即可：

```powershell
git clone https://github.com/five5-7/focusflow-android.git FocusFlow-handoff
cd FocusFlow-handoff
git fetch "$HOME\Downloads\focusflow-9.0-local-handoff-20260926.bundle" 'refs/heads/agent/focusflow-9.0-audit:refs/heads/handoff/stage6-local'
git switch handoff/stage6-local
git rev-parse --short HEAD
git status --short --branch
```

**正常结果：** HEAD 为 `4708ce0`（本次交接包 tip；若显示低于 `779d013`，说明拿到的是旧包，需要重导），工作区没有未提交改动。这个过程只是在本机导入快照；没有合并远端分叉提交，也没有推送、构建或发布。若 Git 报错，先停下并保留原输出，不运行 `reset --hard`、强制推送或重新克隆覆盖旧工作目录。较大的任务还需从[最新实施计划](sandbox:/workspace/scratch/4554773389c8/lib_sync/开发/FocusFlow_9.0_实施计划.md)读取对应章节；不要要求便宜模型反复读整份长文档。

## 第一步：在 Windows 上打开 OpenCode

在包含 `AGENTS.md` 的仓库目录打开 PowerShell，然后执行：

```powershell
git status --short --branch
opencode
```

第一次接入时，在 **OpenCode 里面**输入 `/connect`，选 **DeepSeek**，在它提供的密钥输入界面粘贴已有的 DeepSeek API key；再用 `/models` 选 `deepseek-flash`（界面若写 DeepSeek V4.1 Flash，也核对提供方为 DeepSeek）。选错成 OpenCode Zen、Anthropic 或 OpenAI，账单会属于别处。不要把密钥粘贴到 AI 对话、Git 仓库、截图或文档。官方建议 OpenCode 至少更新到 v1.18.30；现有版本能正常连接时无须为试跑先折腾安装。

**不要执行 `/init`**：这个仓库已有 `AGENTS.md`，通用教程中的 `/init` 会生成项目规则文件，可能改掉现有约束。请模型读已有文件即可。

按 **Tab** 可在 Plan／Build 间切换：Plan 用于先看代码与讨论，不修改正常项目文件；Build 才执行允许的更改。确认界面当前模式再发提示词。如果第一轮只是看缺口，先停在 Plan，接受其结论后切到 Build 再让它写那一份矩阵。

## 第二步：复制三段提示词，试跑一个检查点

**A．在 OpenCode 的 Plan 模式要求只读计划：**

> 我在做 FocusFlow 9.0 阶段 6。先报告当前分支、HEAD、工作区是否干净。只阅读 `AGENTS.md`、`docs/9.0-ai-handoff.md`、`docs/9.0-stage6-checkpoint.md`、`CourseReminderPolicyTest.kt`、`CourseReminderStorageTest.kt`、`StandaloneRemindersTest.kt` 及它们直接对应的生产代码。列出“已有测试／缺少的输入与预期／必须真机验证”矩阵，写出真实测试函数名；再补 3 个你认为容易漏掉的反例。现在只分析，不改文件、不运行发布命令；不要把未运行的测试说成通过。若 HEAD 不含阶段 6 本地提交，停止并告诉我。

**B．确认仍为 DeepSeek Flash，切到 OpenCode Build，把 A 的矩阵粘贴进去：**

> 你的任务只限于把下面这份阶段 6 测试矩阵与现有测试文件核对，再保存为 `docs/9.0-stage6-test-gaps.md`：〔粘贴 A 的矩阵〕。若某行找不到真实测试，请明确标为待补，不要照抄错误。禁止改业务代码、数据模型、版本号、`AGENTS.md` 及其他文件。报告变更文件与实际命令结果；若 Android 构建环境不足，明确写“未执行”。不要推送、合并、制作 APK 或发版。完成后停下。

**C．B 做完后，新开 OpenCode Plan 会话只读挑错；简单任务也可直接核对 diff 和测试。新会话若仍用 DeepSeek，只算一次交叉检查；高风险差异可交给 GLM／真正的 Claude（已有额度时）或这里的 Sol 复核：**

> 只读审查 `docs/9.0-stage6-test-gaps.md` 与对应测试、业务代码。重点找不存在的测试名、漏掉的旧广播／按课次隔离／临时地点／静音边界，以及把“未执行”写成“通过”的地方。逐条给文件和证据；不要改文件，也不要重复赞同原计划。

**C 报出问题后：**先把每条标成“有代码／测试证据”“需要复现”“暂未找到依据”。优先处理有证据且会影响行为的缺陷，逐项补复现和最小修复；不要让 B 一口气改掉 C 的所有猜测。若 C 指向课程身份、Room 迁移或通知调度，先把 C 原文、相应 diff 和测试输出交给这里核定范围。尚未看到 C 的具体报告，不能说这些问题已经修好。

### 已接入的独立复核（GLM／Kimi，经硅基流动）

2026-09-26 起不再需要单独注册智谱账户：GLM-5.3 与 Kimi-K2.7-Code 已通过你已有的硅基流动 key 接入 OpenCode。配置在 `~/.config/opencode/opencode.jsonc`（provider `siliconflow`，key 引用环境变量 `SILICONFLOW_API_KEY`，不落配置明文）；只读审查子代理在 `D:\focusflow\.opencode\agents\review.md`（GLM-5.3）与 `review-kimi.md`（Kimi-K2.7-Code）。完整规则见仓库 `docs/9.0-ai-collaboration.md`。

使用方式：让主代理在实现完成后调用 `review` 子代理，或新开会话选择该子代理；它只读、只报缺陷与证据。若仍想用智谱官方入口，国内普通按量 API 与 GLM Coding Plan 是不同计费入口，密钥与端点不可混用；国际 Z.AI 也需单独核对。批量模型（Qwen3-Coder-30B）暂缓启用。

### L2 自动化插件（2026-09-27 本地实现，重启后生效）

三个插件位于 `D:\focusflow\.opencode\plugin\`，由 OpenCode 自动加载；本机 node 桩测试已过，**重启后按 `docs/9.0-ai-collaboration.md` 第 5 节清单烟测，未烟测前不要当作已生效**。

- **提交前审查（review-gate）**：代理执行 `git commit` 前自动把已暂存差异交给 `review` 子代理；发现缺陷会阻止提交并回传缺陷，同一改动只审一次，审查服务不可用时放行并提示。临时停用：在 `D:\focusflow\.opencode\review-gate.json` 设 `"enabled": false`；`git add && git commit` 组合命令会被要求拆开执行。
- **后台任务（bg_start／bg_status／bg_stop）**：长构建放后台执行并保留日志（`.opencode\state\bg\`），完成后弹 TUI 通知；命令沿用 bash 权限规则，最多同时 4 个任务。
- **模型发现**：启动时同步硅基流动可用模型（缓存 6 小时，失败退回旧缓存），下线的白名单模型会从本次运行移除并警告；默认不自动新增模型，要按系列扩展时在 `~/.config/opencode/siliconflow-discovery.json` 写 `include` 通配符（如 `zai-org/GLM-*`）。
- 回滚：删除或改名对应插件文件即可；不影响仓库、用户数据与其他 L1 配置。

> 只读复核 C 的第〔序号〕条发现。先报告分支、HEAD 和相关 diff，再核对其声称的触发条件是否能从当前代码或测试推出。输出：结论（确认／存疑／不成立）、文件与函数、最小复现、建议补的测试；不改文件，不运行发布命令，不把另一个模型的说法当证据。

结束后在 PowerShell 看结果：

```powershell
git status --short --branch
git add -N docs/9.0-stage6-test-gaps.md
git diff --check
git diff -- docs/9.0-stage6-test-gaps.md
```

`git add -N` 只是让 Git 在差异中显示这个新文件，并未真正暂存文件内容。如果它改了不允许的文件，先让它**解释改动**，不要盲目接受。若两次都没按范围做，就停止同类低价尝试，把具体错误和 diff 交给强模型审定边界。

## 第三步：今后每轮都套这个小模板

> 任务：〔一句话，只有一个检查点〕。基线：〔分支＋HEAD〕。允许改：〔明确文件〕。依据：〔AGENTS、相关代码、交接文档〕。必须保持：〔旧数据字段／通知约束／版本等〕。验收：〔可观察行为＋测试命令〕。停下条件：〔缺数据、规则矛盾、测试失败两轮等〕。禁止：〔推送、合并、发布、泄露密钥〕。交回：〔变更文件、实际测试、风险、下一步〕。

大任务先让强模型填写模板里的**规则、失败情形和验收**，外部模型执行剩下的小任务。实现后给另一模型的审查提示词：

> 只读审查本检查点相对基线的 `git diff`。先寻找旧数据丢失、ID 错配、重复通知、过期广播和没有覆盖的失败路径。每个发现给文件位置、触发条件和证据；不能改代码。没有证据时说明审查范围与剩余不确定性。

交接提示词：

> 只根据 Git 状态、实际测试输出和本轮 diff 更新一页交接记录：分支／HEAD、改动、确实运行的验证、未执行的 CI／真机事项、阻塞及下一步。不得把计划、推断或人工预期写成已验收。

### 为什么这能减少“简单化、来回改”

- **理解偏了：**计划者必须指出现有代码位置、数据字段、反例；没有这些证据就不能发给实现者。实现者只处理已定规则。
- **做得片面：**审查者不复述实现者的解释，只对照需求、原代码与 diff 寻找遗漏；自动测试和真机反馈独立于模型意见。
- **反复修不好：**同一缺陷连续两轮仍失败，停止继续给便宜模型追加“再试一次”；把失败日志、最小复现和 diff 交给这里的 Sol 或其他更强模型缩小问题。解决后再把机械部分交回外部工具。
- **多人互相覆盖：**计划→实现→审查依次进行。需要真正并行时，每个写代码的 AI 必须在不同 Git worktree、独立分支、互不重叠的文件和任务上工作；最终由一个负责人逐项核对后整合。首次试跑不需要设置并行。

## 备用工具和付费界限

- **Claude CLI：**你已安装，可当 OpenCode 不顺手时作为备用工作台。它要接 DeepSeek，就必须按 DeepSeek 官方 Claude Code 指南将 API 地址设为 `https://api.deepseek.com/anthropic`，模型设为 `deepseek-flash`，并使用 DeepSeek key。不要只看程序名就以为用了 Claude；若仍走 Anthropic，会产生另一份账单。先核对当前配置，再决定是否改。官方示例还给出 Windows PowerShell 的临时会话设置方式。
- **独立复核：**可以选 GLM 普通 API（按量）或已持有的 GLM Coding Plan（订阅额度）；两种入口不能混用。已有真正 Claude 额度也可用于只读审查。先核对 C 的具体发现，再决定是否增加新花费。不同模型的意见须以代码、测试、CI 与真机结果确认。
- **费用控制：**DeepSeek 按 API token 计费；`deepseek-flash` 峰时每百万输入／输出为 $0.30／$1.20，非峰时减半。暂不默认开启 Pro、多代理并行或全仓库重复扫描。先看一项任务的 API 支出、返工和你的时间，再决定扩大分流范围。

## 常见问题：先看这里

| 现象 | 首先检查 |
| --- | --- |
| API 401 或连接失败 | DeepSeek key 是否有效、OpenCode 当前提供方是否 DeepSeek；不要把 key 发给任何 AI 代查 |
| AI 说“没有阶段 6 代码” | `git rev-parse --short HEAD` 是否 `818bd74`；若不是，先完成提交包导入 |
| AI 声称测试通过却没有日志 | 要具体命令、退出码、测试数量；本机现可用 Gradle 8.13（wrapper 发行版）＋Android SDK android-36 运行单测（`:app:testDebugUnitTest`，Gradle 用户目录 `D:\focusflow\.gradle-home`），仍不得凭推断称通过 |
| 用量比想象快 | 检查是否选了 Pro、Zen、其他服务，或一次要求全仓扫描和多个代理重复做同一件事 |
| 独立模型不可用 | 已配置 `review`／`review-kimi` 子代理（GLM-5.3／Kimi-K2.7-Code，经硅基流动）；仍不可用时先用 DeepSeek 做单项实现和实际验证，高风险差异再交给这里的 Sol |
| 需要联网搜索 | 已启用 OpenCode 内置 `websearch`（用户环境变量 `OPENCODE_ENABLE_EXA=1`，免 key，走 Exa 托管）；查询内容会经过 Exa 服务，敏感信息不要用它搜 |
| GLM 提示无权限或扣错账 | 核对国内／国际账户、普通 API／Coding Plan 入口、密钥与模型；先停用再对照官方接入文档 |

## 资料与维护

- 仓库交接单：`docs/9.0-ai-handoff.md`；阶段 6 事实：`docs/9.0-stage6-checkpoint.md`；项目规则：`AGENTS.md`。
- [DeepSeek 接入 OpenCode](https://api-docs.deepseek.com/quick_start/agent_integrations/opencode/)；[DeepSeek 接入 Claude Code](https://api-docs.deepseek.com/quick_start/agent_integrations/claude_code/)；[DeepSeek 价格](https://api-docs.deepseek.com/quick_start/pricing/)。
- [OpenCode 使用入门](https://opencode.ai/en/docs)；[智谱 OpenCode 接入](https://docs.bigmodel.cn/cn/coding-plan/tool/opencode)；[智谱 Coding Plan 套餐说明](https://docs.bigmodel.cn/cn/coding-plan/overview)；[OpenCode 国际 Z.AI 说明](https://opencode.ai/docs/providers/)。价格和界面可能变化，首次设置时以对应服务当前页面为准。
- 版本记录：v1.0（2026-09-26）首次创建；v1.1（2026-09-26）曾设定 Google 入口协作流程；v1.2（2026-09-26）排除 Antigravity，改用 OpenCode＋DeepSeek；v1.3（2026-09-26）将 GLM 纳入可选独立只读复核，区分普通 API、国内 Coding Plan 和国际 Z.AI，增加 C 的发现核实步骤；v1.4（2026-09-26）更新本机测试环境事实（Gradle 8.13＋Android SDK android-36 可运行单测）与阶段 6 测试进度；v1.5（2026-09-26）同步工作区归档、交接包重导（tip `4708ce0`）与最新分叉实测；v1.6（2026-09-26）记录多模型协作 L1（硅基流动 provider、只读审查子代理 review／review-kimi、内置 websearch）与 L2 路线；v1.7（2026-09-27）记录 L2 插件（review-gate、bg 后台任务、模型发现）的位置、用法、停用与回滚，并同步烟测前状态。未替用户创建新账户、绑定付款方式或运行外部代理。
