# 对 Sol 第六次复审（增量）的修正回执

日期：2026-09-28。输入：`9.0-stage6-specified-semester-code-review6.md`（Sol 判定：上次四项主路径已修，**仍有一条阻断**——"会话到期与正在下载课表并发"）。
本回执说明该阻断的修法、可证伪测试与证据；另附本地定向复验提出的 A1/A2/A3 三条修正。**复核结论以 Sol 再审为准。**

## 一、Sol 点名的那条阻断：到期与在途确认并发时仍可能返回成功 → 已修

**问题（Sol 原文要点）**：`expireSession` 从 `inFlightSessions` 摘除、置 `expired`、`transport.cancel()`；但 `confirmBlocking` 只检查 `canceled`，POST 返回后与解析成功前都不看 `expired`。`restorePendingLocked` 的 `!isVoid()` 只拦"失败条目回填"，**拦不住"已到期的成功结果返回"**。只靠 `disconnect()` 打断请求不能作为唯一判据。

**修法（三层）**：

1. **发请求前后复查 `expired`**：`confirmBlocking` 在入口、发 POST 前、POST 返回后各查一次；命中即返回 `Failure(EXPIRED_MESSAGE, sessionInvalid = true)`，不再解析、不发请求。
2. **成功/失效的最终定序进同一把锁**：`finalizeAttempt(id, pending, success, restorable)` 在 `synchronized(restoreLock)` 内先 `inFlightSessions.remove(id)`，**再复查 `pending.isVoid()`**；若是成功路径而条目已作废，返回 `false`，调用方改投 `Failure("本次登录已过期，请重新登录后再导入。", sessionInvalid = true)`——**即使传输返回了合法课表也不会 `Success`**。
3. **失败路径的"放回"改由 `restorable` 显式控制**：取消与会话失效（登录失效/验证码）都不放回。这一条是重构中一度丢失、由新用例抓回来的语义（见下"过程中的一次自伤"）。

**同时**：`expireSession` 仍只置 `expired` 不置 `canceled`，因此**在途失败文案仍是超时/网络错误**（不会把到期误写成"用户取消"），但 `sessionInvalid = true` 让 ViewModel 清掉旧选项并要求重新登录。

**可证伪测试（Sol 指定的两个边界，均已补）**：

| 用例 | 构造 | 断言 |
| --- | --- | --- |
| `an expired session must not deliver a successful timetable even if the transport ignores cancel` | 假传输**无视 cancel**（`cancelAction` 不解除阻塞）+ **预置一份合法课表**；POST 进入后在途触发 `fireAll()` 到期，再放行响应 | 结果必须是 `Failure`、文案为"本次登录已过期，请重新登录后再导入。"、`sessionInvalid = true`、句柄不可再用、课表 POST 总数仍为 1 |
| `an expiry before the request is sent prevents any timetable POST` | 用新增的 `beforePost` 测试钩子让工作线程在发请求**之前**停住，触发到期后再放行 | 结果必须是失效失败、`sessionInvalid = true`、**课表 POST 数为 0** |

> 注：Sol 指出"现有到期在途用例给 POST 配的是没有排队响应的假传输，解除阻塞后本来也会抛错，故无法检测'合法响应照常成功'"——这一点确认属实，上面第一个用例正是为它补的（预置合法课表 + 无视 cancel）。

## 二、本地定向复验提出的三条（已一并修）

- **A1 学期显示文字**：`mismatchReason` 原先把学期 `value` 当成显示文字（`1` vs `秋` 会误判），而 `termDisplayOf` 明确支持"value 不含 `|`、显示文字来自 option 文本"。现在解析器新增 `termDisplay` 参数，客户端传入与请求表单 `xqmmc` **同一来源**的 `termDisplayOf(term)`；拿不到才退回从 value 取 `|` 之后部分。
- **A2 HTML 错误页被当成会话失效**：原先"正文以 `<html`/`<!doctype` 开头"即判 `SESSION_LOST`，会把 5xx／维护页／网关页的 HTML 也当成登录失效并吞掉本可原地重试的机会。现在只在**传输层判定登出域名**（`ZjuHttpResponse.loggedOut`：落在 `zjuam.zju.edu.cn` 或事务域根首页）**或正文明确是登录页**（CAS 路径/`authn`/`execution`/统一身份认证/登录 等特征）时才判 `SESSION_LOST`；其它 HTML 归 `MALFORMED`（可重试）。新增两个用例：`a non-login html error page stays retryable instead of forcing a re-login`（502 网关页 ⇒ 不放回失败、句柄保留、恢复后重试成功）、`a logged-out redirect invalidates the session`（登出域名 ⇒ 判失效）。
- **A3 在途到期回"可重试超时"**：`expireSession` 不置 `canceled`（有意为之，避免误报"用户取消"），但三个 catch 现在都会带 `sessionInvalid = pending.isExpired()`，UI 因此清选项。

## 三、过程中的一次自伤（如实登记）

把 `finalizeAttempt` 重构成"返回是否仍有效"时，我**丢了"会话不可用就不放回句柄"这条语义**（失败路径一律放回）——结果"登录失效后再次点击不应再发 POST"的既有用例立刻变红（第二次确实又发了 POST）。已修为显式 `restorable` 参数（`attempt.sessionUsable`），该用例恢复通过。
这说明：**这轮改动是被既有可证伪用例挡住的，不是靠人眼**。

## 四、证据（本地）

- `:app:testDebugUnitTest` 与 `:app:testReleaseUnitTest`：见下方执行结果（本条由运行后补记）
- `ZjuTimetableSessionTest`：36 项（本轮新增 5 项：到期合法响应、POST 前到期、非登录 HTML 可重试、登出域名判失效、以及 A1 相关对照）
- `git diff --check` clean

## 五、仍未闭合 / 边界（Sol 已列，照登）

- **到期后等待选择的界面仍显示旧选项**，直到用户下一次点击才清掉。Sol 明确"只要下次点击不发请求且明确引导重新登录，可暂作体验待改，不单独阻断"。当前实现满足该条件（`taken == null` ⇒ 失效失败 + `sessionInvalid` ⇒ 清选项）。若要"到点 UI 立即失效"，需要 ViewModel 侧的到期事件，尚未做。
- 本地定向复验的 B 类覆盖缺口（`su=` 无用例、`applyFetchFailure` 无 ViewModel 层用例、`inFlight` 摘除无用例等）**未逐条补齐**；其中"`applyFetchFailure` 零引用"属真实测试缺口，记入待办。
- 真机与 CI 未执行；不把双变体单测当真机验收。
