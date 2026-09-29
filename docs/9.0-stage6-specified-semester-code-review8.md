# 阶段 6：指定学期修正增量复审（第八次）

日期：2026-09-28。输入：`9.0-stage6-specified-semester-commit5.diff`（SHA-256 `8837445530b96f5029895729256f38b83f55b6becf7f011d7e7ba3f610cc2364`）、后续 `9.0-stage6-specified-semester-commit6.diff`（SHA-256 `370bf06f89c99fe380512f207b3336f8931409065d6149ded5c6953f5b9cfc50`）、第七次复审回执及原始小提交②。增量按顺序审读。用户 Windows 分支报告为 `handoff/stage6-local @ 5dd6e0c`，未推送；本审查工作区仅有旧仓库快照 `818bd74`，无法独立读取新提交的 Git 对象或运行新代码的 Gradle 测试。

## 结论

**小提交②“指定学期两阶段导入”及收到的后续增量，在本次静态复审的范围内通过，可以结束这条代码复审循环，移交下一道验收。**这不表示阶段 6 全部完成，也不表示真机、CI 或发布已经验收。

| 核对点 | 静态复审结果 |
| --- | --- |
| 第七次阻断：已知登出被正文覆盖 | `ZjuTimetableParser.parse` 开头先判 `loggedOut`，直接返回 `SESSION_LOST`；合法 JSON 和空正文均不会绕过。两个新测试核对失败、`sessionInvalid`，合法 JSON 用例还核对句柄不可复用及 POST 只发一次。未登出的普通 502 HTML 仍可重试。**已闭合。** |
| 第六次阻断：到期与在途成功竞争 | 请求前后有 `expired` 复查；成功收尾与 `expireSession` 共用锁进行最终定序。后续 `prepareConfirm` 只把准备步骤移入锁，未改动最终定序。**保持闭合。** |
| 取消文案 | 成功收尾时遇作废，先看 `pending.canceled`，按取消／到期分流。新增在途取消测试覆盖 POST 返回后的取消检查；回执如实承认未单独触发收尾处的取消分支。**行为修正成立，单一分支仍缺确定性测试，不阻断。** |
| 早退时的锁范围 | `prepareConfirm` 在锁内移除/恢复句柄和登记在途；到期早退返回传输，外层再 `dispatch`、`cancel()`，避免断开连接拖住其它会话。**已修。** |
| 学期显示同源 | 导入完成 Intent 中已有 `EXTRA_SEMESTER`；MainActivity 二次解析直接取这一字段，与导入页 `termDisplayOf(term)` 同源。**已修。** |

## 一处需要更正的证据表述

回执把 `a logged-out response with a valid timetable body is still a session loss` 的样本说成“`xnm`/`xqm`/`kbList` 齐全”，但 `ZjuTimetableSessionTest` 的 `TIMETABLE_JSON` 实际**只有 `kbList`**。该样本对当前解析器仍是可成功解析的 JSON，因此足以证伪“登出被解析成成功”；错误的是材料对样本字段的描述，不影响本次通过结论。请在本地回执或测试注释中改为“含可解析 `kbList` 的 JSON”，或者补齐 `xnm/xqm` 后再称“完整回显”。无需为此重开设计复审。

## 验证边界与交接

随附材料报告本地 `:app:testDebugUnitTest` 和 `:app:testReleaseUnitTest` 各 150 类、951 项、0 失败，并报告 `git diff --check` clean；**这是实施者本机执行结果，本审查未独立复跑或查看测试 XML**。本次仅静态审阅收到的增量，不宣称当前 Windows 工作区与附件逐字节一致，也不宣称真实教务服务器、OPPO 真机或 CI 已通过。

下一步：本地实施者核对实际分支、HEAD、工作区干净程度，修正文档样本字段描述；按计划将该子任务标为“代码复审通过／待真实环境验收”，继续阶段 6 的“稳定课程身份与多课次导入”设计与独立小提交。需要将本地提交同步云端或运行 CI 时另按既有授权边界操作，不把本次静态通过当作推送、合并或发布许可。

REVIEW-VERDICT: 本范围通过；指定学期两阶段导入及所收增量的静态复审结束，剩余真机与 CI 验收和阶段 6 其他子任务。
