# 对 Sol ②代码复审（code-review5）的修正回执

日期：2026-09-28。输入：`9.0-stage6-specified-semester-code-review5.md`（Sol 判定**暂不通过**：3 条阻断 + 1 条需核实）。
本回执逐条说明修正内容、可证伪测试与证据。**本地实施者自述**；复核结论以 Sol 再审为准。

## 逐条修正

### 1（阻断）已失效会话被当作可重试 → 已修

**原问题**：`confirmBlocking` 对所有 `ZjuTimetableParseResult.Failure` 都返回 `sessionUsable = true`，句柄被放回；UI 保留旧选项，用户再点只是用同一失效 Cookie 重复同一 POST。

**修法**：给解析失败加**结构化原因**，用原因而不是中文文案决定重试策略。

- `ZjuTimetableParser.kt` 新增 `ZjuParseFailureReason`（`SESSION_LOST` / `CAPTCHA_REQUIRED` / `EMPTY_PAYLOAD` / `TOO_LARGE` / `MALFORMED` / `NO_KB_LIST` / `SEMESTER_MISMATCH`）与 `isRecoverable`（前两者不可恢复）；`ZjuTimetableParseResult.Failure` 携带 `reason`。
- `ZjuTimetableClient.confirmBlocking` 改为 `sessionUsable = parsed.reason.isRecoverable`；不可恢复时**不放回句柄**。
- `ZjuTimetableFetchResult.Failure` 新增 `sessionInvalid`；`ZjuImportViewModel` 收到时清空 `options`/`pendingSession`/选中值/`currentStage`，并在文案后追加"请重新登录后再导入"——按钮不再停在"导入所选学期"。

**可证伪测试**（`ZjuTimetableSessionTest`）：
- `a login page response invalidates the session instead of inviting a retry`：断言 `sessionInvalid == true`、文案为解析器原文，且**第二次确认不再发 POST**（`timetablePosts() == 1`）。
- `a captcha challenge invalidates the session instead of inviting a retry`：同上（验证码分支）。
- `parse failures carry a recoverable or terminal reason`：逐条断言五类 payload 的原因分类与 `isRecoverable`，钉住"不得改成匹配文案"。

### 2（阻断）十分钟过期只在再次调用时清理 → 已修

**原问题**：`purgeExpiredSessions` 只在下一次 `beginSession` 执行；用户把页面放着不动，到点也不清。

**修法**：会话创建时**按句柄安排到期清理**，与句柄绑定、不依赖后续调用。

- `PendingSession` 增加 `id`、`expired: AtomicBoolean` 与 `timetableUrl`（创建时算好），并增加 `isVoid() = canceled || expired`。
- 新增 `expireSession(id)`：锁内先置 `expired`、从 `pendingSessions`/`inFlightSessions` 移除、清账号，锁外 `transport.cancel()`。**不置 `canceled`**——这样在途请求把失败如实报成超时/网络错误，而不是误报成"用户取消了导入"；对已消费/已取消/已到期句柄幂等。
- **回填守卫改为 `!isVoid()`**：只置 `canceled` 不够——在途确认失败时 `restorePendingLocked` 会把已到期条目放回静态表，等于把本条要修的"超时条目滞留"又做回来。这是本地独立复验抓到的**确证缺陷**，已修并加测试。
- 请求 URL 改为创建时算好（不再用账号现拼）⇒ 到期清账号后，在途请求仍带正确的 `su`，不会出现空 `su`（复验提出的另一条）。
- `scheduleExpiry` 用**一条**守护线程的 `ScheduledExecutorService` 服务全部会话（不为每个会话起线程）；到期延迟按 `now` 与当前时间之差扣减，**时钟回拨时 `coerceAtLeast(0)`，寿命只减不增**。
- 取消/消费路径统一走 `disposeEntry`（置标志 + 清账号）。
- 另外把"句柄不存在/已过期"两条失败也标 `sessionInvalid`，`ZjuTimetableImportActivity.applyFetchFailure` 统一处理两条终态路径（此前 ViewModel 直写会清选项、Activity 观察 outcome 不会，行为分叉）。

**可证伪测试**（3 项）：
- `an expired session is cleaned up without another begin or confirm call`：注入记录型调度器（生产默认用真实线程池，测试用假实现以保证确定性）⇒ 断言**恰好安排 1 次**、延迟≈10 分钟；执行到期回调后断言**传输被取消**、句柄失效、且**没有发出任何课表请求**——全程没有第二次 `begin`/`confirm`。
- `an expired session is not restored even if a confirm is in flight`：确认请求在途时触发到期 ⇒ 断言结果仍恰好一次、且**该句柄此后不得再发出任何请求**（钉死回填缺陷）。
- `another confirmation after expiry is reported as an invalid session`：到期后再点 ⇒ 失败必须带 `sessionInvalid`，UI 才会清选项并要求重新登录。

### 3（阻断）密码数组留到首页读取结束 → 已修

**原问题**：`CharArray` 直到 `beginSessionBlocking()` 整体返回才清零，而成功登录后还要 GET 首页并读全部 option（40 秒总超时）。

**修法**：CAS 登录 POST 返回后**立即** `password.fill('\u0000')`；外层 `finally` 的兜底保留。

**可证伪测试**：
- `the password array is cleared as soon as the login request returns`：给假传输装 GET 拦截器，在**首页请求进行中**检查调用方持有的数组是否已全为 `\u0000`（用 latch 确认首页确实被请求过）。在只检查"整个 `beginSession` 回调之后"的旧实现下，本用例会失败。

### 4（需核实）响应学年/学期与请求不一致 → 已补失败关闭

**核实结论**：**Sol 的怀疑成立**。`ZjuTimetableParser` 把请求的 `xnm/xqm` 直接写进候选元数据（`schoolYearCode = requestYearCode`），从未核对响应回显。若教务对不支持的组合静默返回当前课表，会把别的学期课表贴成所选学期。

**修法**：新增 `mismatchReason` 校验，规则按"只能证实证伪的部分"设计：

- 响应未回显该字段 ⇒ 不判（无法核对）。
- `xnm`（完整学年区间）⇒ 直接比对，不一致即失败。
- `xqm` 响应只回显**单个汉字** ⇒ 只比对可证实的**显示部分**；两个「短」共享显示文字、无法用响应区分 `1|短` 与 `2|短`，这种情况**不声称已核对完整代码**，仅在显示文字明确不同时失败。
- 不一致时返回 `SEMESTER_MISMATCH`（不可恢复）并给出双方取值。

**可证伪测试**：
- `a server year that differs from the request is a mismatch`：请求 `2026-2027 / 1|秋`、合成响应回显 `2025-2026 / 秋` ⇒ 必须 `Failure` + `SEMESTER_MISMATCH`；**对照组**：回显一致时必须 `Success`（证明不是"永远失败"）。
- `a term that differs only by display text is a mismatch`：学期分支此前零覆盖（复验点名）。回显"夏" vs 所选"秋" ⇒ 必须失败；回显单字"短"（真实形态，与 `2|短` 显示部分一致）⇒ **不得误判**；响应未回显学期 ⇒ 不判失败。

**另外**：`isRecoverable` 改为 allowlist（`when(this)` 穷尽七个分支）——将来新增 reason 默认落在"不可重试"（fail-safe）一侧，而不是默认可重试（复验提出的设计脆弱点）。

## 证据（本地）

- `:app:testDebugUnitTest` 与 `:app:testReleaseUnitTest` → **各 150 类 942 项，0 失败/错误/跳过**（debug 用 `--rerun-tasks` 真跑）
- `ZjuTimetableSessionTest` 由 23 项增至 **32 项**（本轮新增 **9** 项，覆盖上述四条的每一项）
- `git diff --check` clean；增量 diff 见 `9.0-stage6-specified-semester-commit3.diff`（4 文件，45881 B）
- 未做：真机与 CI 验收（按要求不把双变体单测当真机验收）

## 本轮修改的独立复验（如实登记）

- 第 1 轮复验判 **DEFECTS**：抓出"到期条目被回填"（确证，已修并补测试）、"清账号后可能发空 `su` 请求"（已修：URL 创建时算好）、"`isRecoverable` 黑名单脆弱"（已改为 allowlist）。
- 第 2 轮复验（针对上述修复）结果见下（本回执更新时仍在进行；若其判定为 DEFECTS，应先修完再交 Sol）。

## 仍未闭合（如实登记）

- 若第 2 轮复验未回 CLEAN，本批修改不应视为"已验证"。
- `pendingSessions` 仍留有一条"取消恰在登记之后到达"的窄窗口（前几轮记录在案）。
- 到期清理不撤销已安排的任务（靠 `expireSession` 幂等兜底）；若某传输的 post 既不返回也不响应取消，条目仍会存活到进程结束（真实 `HttpSession` 有 connect/read 超时兜底）。
- 到期发生在"没有在途请求"时，UI 的选项不会立即失效（下一次点击才被引导重新登录）——当前选择是 fail-closed 而非主动清理；若 Sol 要求"到点即清 UI"，需要给 ViewModel 加到期回调。

