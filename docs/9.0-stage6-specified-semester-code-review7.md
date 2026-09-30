# 阶段 6：指定学期修正增量复审（第七次）

日期：2026-09-28。范围：附件 `9.0-stage6-specified-semester-commit4.diff`（SHA-256 `acfc57fafc8d4882dc8bb3de393d31625a945f91b7d75e68f69a4d25187a0ceb`）及修正回执；交叉核对原提交②和第六次复审。当前审查工作区 `handoff/stage6-local @ 818bd74` 是旧快照，无法读取用户 Windows 最新 Git 对象或单测 XML。随附 README 称本地 debug/release 各 946 项通过，**本审查没有独立运行这些测试**。用户的新分支尚未推送。

## 审定

**第六次复审指出的“到期后在途合法课表仍返回成功”已修；整个增量暂不放行，另有一处登出信号被响应正文覆盖的真实漏洞。**

- `ZjuTimetableClient.kt` 增量中的 `confirmBlocking` 在请求前、请求返回后检查 `expired`；`finalizeAttempt` 在与 `expireSession` 共用的 `restoreLock` 下决定成功是否仍有效。到期先拿锁时会拒绝成功并返回 `sessionInvalid=true`；成功先拿锁时此次尝试已经有效结束。此前的成功竞态在这两个时序下均闭合。
- `ZjuTimetableSessionTest` 新增的“传输无视 cancel 仍返回合法课表”用例预先排入合法响应，并通过 latch 保证到期发生在 POST 期间；“POST 前到期”用例有独立的暂停点并断言 POST 为零。这两条对所指缺陷可证伪。受限于当前工作区只收到差异，不能把用例存在等同于在此运行通过。
- 客户端失败回填现显式受 `restorable=attempt.sessionUsable` 约束，登录失效和验证码不再回填句柄。

## 待修的阻断：已知登出仍可被解析为课表成功

位置：`ZjuTimetableParser.kt` 的 `parse(..., loggedOut)`（commit4 diff 约第 668–696 行）；`ZjuTimetableClient.kt` 的 `response.loggedOut` 传入处（commit4 diff 约第 474–490 行）。

`ZjuHttpResponse.loggedOut` 是传输层根据最终跳转域名得出的**已知登出**信号；解析器却先以空正文返回 `EMPTY_PAYLOAD`，再仅在 `looksLikeHtml && (loggedOut || looksLikeLoginPage(...))` 时认定 `SESSION_LOST`。因此只要 `loggedOut=true` 且正文是能通过 `kbList` 解析的 JSON（例如带合法 `xnm`／`xqm`／`kbList` 的 JSON），解析器会忽略登出信号并返回 `Success`；空正文则显示“没有返回课表数据”，不是明确的重新认证提示。真实 CAS 页面通常是 HTML，但此处传输层已经明确证明最终地址不是课表页，不应再由正文格式推翻该结论。

**最小修正**：在 `parse` 的空正文、大小和 JSON 分支之前优先处理 `loggedOut`，直接返回 `Failure(SESSION_LOST)`。保留对未登出且无登录特征的 502 HTML 的可重试行为。增加两条反例：`loggedOut=true` + 可解析的完整 `kbList` 必须失败且 `sessionInvalid=true`、原句柄不可复用；`loggedOut=true` + 空正文必须给出登录失效文案。现有登出测试只传 HTML，不能抓到这个问题。

## 非阻断的文案边界

`finalizeAttempt` 对成功尝试遇到 `pending.isVoid()` 一律返回 `false`；外层随后统一生成“本次登录已过期”。若**用户主动取消**恰好发生在传输已取得合法课表且无视取消的时刻，也会报“过期”，而非“导入已取消”。成功不会错误投递，句柄也不会复活，因此这属于错误提示。下一次修改可在锁内返回 `expired`／`canceled`／`valid` 的细分结果，并在假传输忽略取消的测试里固定文案；无需为此重新设计流程。

## 发回本地实施者的最小任务

> 在实际 `handoff/stage6-local` 最新 HEAD 上，先报告分支、HEAD、`git status --short`。只修 `loggedOut` 优先于正文解析的问题，增加上面的 JSON 与空正文反例，保留非登录 502 HTML 可原地重试的测试。可顺手修正主动取消成功竞争时的文案，但不得改动到期与成功的锁内定序。运行聚焦测试、debug/release 全量单测、`git diff --check`，返回小型增量 diff 与准确执行结果。保持课程身份、归并、Room、提醒、版本号不动；不推送、不合并、不发布。真实手机和 CI 仍需单独验收。

REVIEW-VERDICT: 暂不通过；第六次复审的到期竞态已闭合，需先让 `loggedOut=true` 对任意响应正文都判会话失效。
