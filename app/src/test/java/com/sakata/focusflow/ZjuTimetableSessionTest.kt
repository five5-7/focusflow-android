package com.sakata.focusflow

import android.app.Application
import android.os.Looper
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLEncoder
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ZjuTimetableSessionTest {
    private class FakeTransport : ZjuHttpClient {
        private val queues = mutableMapOf<String, ArrayDeque<ZjuHttpResponse>>()
        val posts = mutableListOf<Pair<String, String>>()
        private var postInterceptor: (() -> Unit)? = null
        private var getInterceptor: ((String) -> Unit)? = null
        private var cancelAction: (() -> Unit)? = null
        private val cancels = java.util.concurrent.atomic.AtomicInteger(0)

        fun enqueue(method: String, urlPart: String, response: ZjuHttpResponse) {
            queues.getOrPut("$method $urlPart") { ArrayDeque() }.addLast(response)
        }

        fun setPostInterceptor(interceptor: () -> Unit) {
            postInterceptor = interceptor
        }

        /** 按 URL 生效的 GET 拦截器：用于在下游请求"在途"时检查外部状态（如密码数组是否已清零）。 */
        fun setGetInterceptor(interceptor: (String) -> Unit) {
            getInterceptor = interceptor
        }

        fun setCancelAction(action: () -> Unit) {
            cancelAction = action
        }

        fun canceledCount(): Int = cancels.get()

        override fun cancel() {
            cancels.incrementAndGet()
            cancelAction?.invoke()
        }

        override fun isCanceled(): Boolean = cancels.get() > 0

        private fun next(method: String, url: String): ZjuHttpResponse {
            val key = queues.keys.firstOrNull { it.startsWith("$method ") && url.contains(it.substringAfter("$method ")) }
                ?: error("no $method stub for $url")
            return queues.getValue(key).removeFirst()
        }

        override fun get(url: String, totalTimeoutMs: Long?): ZjuHttpResponse {
            getInterceptor?.invoke(url)
            return next("GET", url)
        }

        override fun post(
            url: String,
            body: String,
            headers: Map<String, String>,
            readTimeoutMs: Int,
            skipResponseBodyAtHost: String?,
            onRedirect: (URI) -> Unit
        ): ZjuHttpResponse {
            posts += url to body
            postInterceptor?.invoke()
            return next("POST", url)
        }

        fun timetablePosts(): List<Pair<String, String>> = posts.filter { it.first.contains("xskbcx_cxXsKb") }
    }

    private fun FakeTransport.stubLoginAndIndex(indexHtml: String) {
        enqueue("GET", "cas/login", ZjuHttpResponse(200, URI("https://zjuam.zju.edu.cn/cas/login"), CAS_FORM))
        enqueue("GET", "cas/login", ZjuHttpResponse(200, URI("https://zjuam.zju.edu.cn/cas/login"), CAS_FORM))
        enqueue(
            "GET", "getPubKey",
            ZjuHttpResponse(200, URI("https://zjuam.zju.edu.cn/cas/v2/getPubKey"), """{"modulus":"e1","exponent":"3"}""")
        )
        enqueue("POST", "cas/login", ZjuHttpResponse(200, URI("https://zdbk.zju.edu.cn/jwglxt/xtgl/index"), ""))
        enqueue(
            "GET", "xskbcx_cxXskbcxIndex",
            ZjuHttpResponse(200, URI("https://zdbk.zju.edu.cn/jwglxt/kbcx/xskbcx_cxXskbcxIndex.html"), indexHtml)
        )
    }

    private fun FakeTransport.stubTimetable() {
        enqueue(
            "POST", "xskbcx_cxXsKb",
            ZjuHttpResponse(200, URI("https://zdbk.zju.edu.cn/jwglxt/kbcx/xskbcx_cxXsKb.html"), TIMETABLE_JSON)
        )
    }

    private fun awaitLatch(latch: CountDownLatch) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline && latch.count > 0) {
            Thread.sleep(10)
            Shadows.shadowOf(Looper.getMainLooper()).idle()
        }
        assertTrue("completion callback not delivered", latch.await(1, TimeUnit.SECONDS))
    }

    /** 固定窗口内持续推进主线程队列，用于确认没有第二次回调（卡死/双回调都会暴露）。 */
    private fun settle(millis: Long = 400) {
        val deadline = System.currentTimeMillis() + millis
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
            Shadows.shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun awaitOptions(start: ((ZjuSemesterOptionsResult) -> Unit) -> Unit): ZjuSemesterOptionsResult {
        var result: ZjuSemesterOptionsResult? = null
        val latch = CountDownLatch(1)
        start { value ->
            result = value
            latch.countDown()
        }
        awaitLatch(latch)
        return requireNotNull(result)
    }

    private fun awaitFetch(start: ((ZjuTimetableFetchResult) -> Unit) -> Unit): ZjuTimetableFetchResult {
        var result: ZjuTimetableFetchResult? = null
        val latch = CountDownLatch(1)
        start { value ->
            result = value
            latch.countDown()
        }
        awaitLatch(latch)
        return requireNotNull(result)
    }

    /** 记录到期安排但不真正等待 10 分钟：让 TTL 相关断言保持确定性。 */
    private class RecordingExpiryScheduler : ZjuTimetableClient.ZjuExpiryScheduler {
        val delays = mutableListOf<Long>()
        val actions = mutableListOf<() -> Unit>()

        override fun schedule(delayMillis: Long, action: () -> Unit) {
            delays += delayMillis
            actions += action
        }

        /** 模拟"时间到了"：把已安排的回调执行一遍。 */
        fun fireAll() {
            actions.toList().forEach { runCatching { it() } }
        }
    }

    private fun begin(
        transport: FakeTransport,
        password: CharArray = "secret".toCharArray(),
        now: Long = System.currentTimeMillis(),
        expiryScheduler: ZjuTimetableClient.ZjuExpiryScheduler? = null
    ): ZjuSemesterOptionsResult = awaitOptions { complete ->
        ZjuTimetableClient.beginSession(
            username = "student",
            password = password,
            onProgress = {},
            onComplete = complete,
            now = now,
            transportFactory = { transport },
            expiryScheduler = expiryScheduler ?: ZjuTimetableClient.ZjuExpiryScheduler { _, _ -> }
        )
    }

    private fun confirm(
        handle: ZjuTimetableSessionHandle,
        yearValue: String,
        termValue: String,
        now: Long = System.currentTimeMillis()
    ): ZjuTimetableFetchResult = awaitFetch { complete ->
        ZjuTimetableClient.confirmSelectedSemester(
            handle = handle,
            yearValue = yearValue,
            termValue = termValue,
            onProgress = {},
            onComplete = complete,
            now = now
        )
    }

    /**
     * 延迟返回的假传输：即使被 cancel 也照样把排队的合法课表交出去。
     * 用来证伪"到期后靠 transport.cancel() 打断请求"这条唯一判据。
     */
    @Test
    fun `logged-out host detection only fires on the identity provider or domain root`() {
        // 复验指出的覆盖缺口：这条启发式原来只有静态依据。用纯函数直接钉住判定边界。
        fun loggedOut(url: String) = isLoggedOutRedirect(URI(url))

        assertTrue("CAS host must count as logged out", loggedOut("https://zjuam.zju.edu.cn/cas/login"))
        assertTrue("domain root must count as logged out", loggedOut("https://zju.edu.cn/"))
        assertFalse("timetable page is not a logout", loggedOut("https://zdbk.zju.edu.cn/jwglxt/kbcx/xskbcx_cxXsKb.html"))
        assertFalse("a gateway error on the same host is not a logout", loggedOut("https://zdbk.zju.edu.cn/502.html"))
        assertFalse("domain subpage is not a logout", loggedOut("https://zju.edu.cn/some/page"))
    }

    @Test
    fun `an expired session must not deliver a successful timetable even if the transport ignores cancel`() {
        // Sol review6 的阻断项：到期与"正在下载课表"并发时，成功收尾必须和到期移除在同一锁下定序；
        // 不能只靠 transport.cancel() 打断请求（真实 disconnect 也不该是唯一判据）。
        val scheduler = RecordingExpiryScheduler()
        val transport = FakeTransport().apply { stubLoginAndIndex(INDEX_HTML_A) }
        val options = begin(transport, expiryScheduler = scheduler) as ZjuSemesterOptionsResult.Success

        val postEntered = CountDownLatch(1)
        val release = CountDownLatch(1)
        // 关键：先排一份**合法课表**，并让假传输无视 cancel（cancelAction 不解除阻塞）。
        transport.stubTimetable()
        transport.setPostInterceptor { postEntered.countDown(); release.await(5, TimeUnit.SECONDS) }

        val outcomes = java.util.concurrent.atomic.AtomicInteger(0)
        var result: ZjuTimetableFetchResult? = null
        val latch = CountDownLatch(1)
        Thread {
            ZjuTimetableClient.confirmSelectedSemester(
                handle = options.handle,
                yearValue = "2026-2027",
                termValue = "2|短",
                onProgress = {},
                onComplete = { value -> outcomes.incrementAndGet(); result = value; latch.countDown() }
            )
        }.start()
        assertTrue("POST must be in flight", postEntered.await(3, TimeUnit.SECONDS))

        scheduler.fireAll() // 在途期间到期
        release.countDown()
        awaitLatch(latch)
        settle()

        assertEquals(1, outcomes.get())
        val failure = requireNotNull(result) as? ZjuTimetableFetchResult.Failure
        assertTrue("expired attempt must fail, got $result", failure != null)
        assertEquals("本次登录已过期，请重新登录后再导入。", failure!!.message)
        assertTrue("expired failure must invalidate the session", failure.sessionInvalid)
        // 句柄不可再用，且不会再发出请求。
        assertTrue(confirm(options.handle, "2026-2027", "2|短") is ZjuTimetableFetchResult.Failure)
        assertEquals(1, transport.timetablePosts().size)
    }

    @Test
    fun `an expiry before the request is sent prevents any timetable POST`() {
        // Sol review6 的第二个边界：到期发生在"确认线程已启动、POST 尚未发出"之间。
        // 注意不要在这里嵌套 awaitLatch：worker 会卡在 beforePost 上，而 awaitLatch 需要主线程泵 looper。
        val scheduler = RecordingExpiryScheduler()
        val transport = FakeTransport().apply {
            stubLoginAndIndex(INDEX_HTML_A)
            stubTimetable()
        }
        val options = begin(transport, expiryScheduler = scheduler) as ZjuSemesterOptionsResult.Success

        val workerPaused = CountDownLatch(1)
        val resume = CountDownLatch(1)
        var result: ZjuTimetableFetchResult? = null
        val done = CountDownLatch(1)
        Thread {
            ZjuTimetableClient.confirmSelectedSemester(
                handle = options.handle,
                yearValue = "2026-2027",
                termValue = "2|短",
                onProgress = {},
                onComplete = { value -> result = value; done.countDown() },
                beforePost = {
                    workerPaused.countDown()
                    resume.await(5, TimeUnit.SECONDS)
                }
            )
        }.start()

        assertTrue("worker must pause before POST", workerPaused.await(3, TimeUnit.SECONDS))
        scheduler.fireAll() // POST 之前到期
        resume.countDown()
        awaitLatch(done)

        val failure = requireNotNull(result) as? ZjuTimetableFetchResult.Failure
        assertTrue("expired attempt must fail, got $result", failure != null)
        assertTrue(failure!!.sessionInvalid)
        assertEquals("no timetable POST may be sent after expiry", 0, transport.timetablePosts().size)
    }

    @Test
    fun `a non-login html error page stays retryable instead of forcing a re-login`() {
        // review5 #4 的边界修正：只有"明确是登录页"或"登出域名"才判会话失效；
        // 5xx／维护页这类 HTML 只是这次请求失败，句柄应保留以便原地重试。
        val transport = FakeTransport().apply {
            stubLoginAndIndex(INDEX_HTML_A)
            enqueue(
                "POST", "xskbcx_cxXsKb",
                ZjuHttpResponse(
                    502,
                    URI("https://zdbk.zju.edu.cn/jwglxt/kbcx/xskbcx_cxXsKb.html"),
                    "<html><head><title>502 Bad Gateway</title></head><body>nginx</body></html>"
                )
            )
        }
        val options = begin(transport) as ZjuSemesterOptionsResult.Success

        val first = confirm(options.handle, "2026-2027", "2|短")
        assertTrue(first is ZjuTimetableFetchResult.Failure)
        val failure = first as ZjuTimetableFetchResult.Failure
        assertEquals("无法解析教务课表响应，请确认当前页面是“学生课表查询”。", failure.message)
        assertFalse("a gateway error page must not force a re-login", failure.sessionInvalid)

        // 句柄仍在：恢复后可直接重试成功。
        transport.stubTimetable()
        assertTrue(confirm(options.handle, "2026-2027", "2|短") is ZjuTimetableFetchResult.Success)
        assertEquals(2, transport.timetablePosts().size)
    }

    @Test
    fun `a logged-out response with a valid timetable body is still a session loss`() {
        // review7 阻断：传输层已证明最终跳转不是课表页时，合法 JSON 也不能把它推翻成成功。
        val transport = FakeTransport().apply {
            stubLoginAndIndex(INDEX_HTML_A)
            enqueue(
                "POST", "xskbcx_cxXsKb",
                ZjuHttpResponse(
                    200,
                    URI("https://zjuam.zju.edu.cn/cas/login"),
                    TIMETABLE_JSON,
                    loggedOut = true
                )
            )
        }
        val options = begin(transport) as ZjuSemesterOptionsResult.Success

        val first = confirm(options.handle, "2026-2027", "2|短")
        val failure = first as? ZjuTimetableFetchResult.Failure
        assertTrue("a logged-out response must never be parsed as success, got $first", failure != null)
        assertEquals("教务登录已失效，请重新完成统一身份认证。", failure!!.message)
        assertTrue(failure.sessionInvalid)

        // 原句柄不可复用，也不会再发请求。
        assertTrue(confirm(options.handle, "2026-2027", "2|短") is ZjuTimetableFetchResult.Failure)
        assertEquals(1, transport.timetablePosts().size)
    }

    @Test
    fun `a logged-out response with an empty body reports a session loss`() {
        // review7：空正文原先落到 EMPTY_PAYLOAD（"没有返回课表数据"），登出时该给明确的重新认证提示。
        val transport = FakeTransport().apply {
            stubLoginAndIndex(INDEX_HTML_A)
            enqueue(
                "POST", "xskbcx_cxXsKb",
                ZjuHttpResponse(
                    200,
                    URI("https://zjuam.zju.edu.cn/cas/login"),
                    "",
                    loggedOut = true
                )
            )
        }
        val options = begin(transport) as ZjuSemesterOptionsResult.Success

        val failure = confirm(options.handle, "2026-2027", "2|短") as ZjuTimetableFetchResult.Failure
        assertEquals("教务登录已失效，请重新完成统一身份认证。", failure.message)
        assertTrue(failure.sessionInvalid)
    }

    @Test
    fun `a logged-out redirect invalidates the session`() {
        // 传输层判定"登出域名"时，即使正文没有登录页特征也要判会话失效。
        val transport = FakeTransport().apply {
            stubLoginAndIndex(INDEX_HTML_A)
            enqueue(
                "POST", "xskbcx_cxXsKb",
                ZjuHttpResponse(
                    200,
                    URI("https://zjuam.zju.edu.cn/cas/login"),
                    "<html><body>redirected</body></html>",
                    loggedOut = true
                )
            )
        }
        val options = begin(transport) as ZjuSemesterOptionsResult.Success

        val failure = confirm(options.handle, "2026-2027", "2|短") as ZjuTimetableFetchResult.Failure
        assertEquals("教务登录已失效，请重新完成统一身份认证。", failure.message)
        assertTrue(failure.sessionInvalid)
    }

    @Test
    fun `confirmed option values are used verbatim and the password is cleared`() {
        val transport = FakeTransport().apply {
            stubLoginAndIndex(INDEX_HTML_A)
            stubTimetable()
        }
        val password = "secret".toCharArray()

        val options = begin(transport, password) as ZjuSemesterOptionsResult.Success
        assertTrue("password must be cleared after login", password.all { it == '\u0000' })
        assertEquals(listOf("2026-2027", "2025-2026"), options.yearOptions.map { it.value })
        assertEquals(listOf("1|秋", "1|短", "2|短"), options.termOptions.map { it.value })

        val result = confirm(options.handle, "2026-2027", "2|短") as ZjuTimetableFetchResult.Success
        val body = transport.timetablePosts().single().second
        assertEquals(1, transport.timetablePosts().size)
        assertTrue(body.contains("xnm=" + URLEncoder.encode("2026-2027", "UTF-8")))
        assertTrue(body.contains("xqm=" + URLEncoder.encode("2|短", "UTF-8")))
        assertTrue(body.contains("xqmmc=" + URLEncoder.encode("短", "UTF-8")))
        assertEquals("2026-2027", result.schoolYearCode)
        assertEquals("2|短", result.termCode)
        assertEquals("短", result.semester)
    }

    @Test
    fun `selection outside the session options fails without a timetable request`() {
        val transport = FakeTransport().apply {
            stubLoginAndIndex(INDEX_HTML_A)
            stubTimetable()
        }
        val options = begin(transport) as ZjuSemesterOptionsResult.Success

        val rejected = confirm(options.handle, "1999-2000", "2|短")
        assertTrue(rejected is ZjuTimetableFetchResult.Failure)
        assertTrue(transport.timetablePosts().isEmpty())

        val accepted = confirm(options.handle, "2026-2027", "2|短")
        assertTrue(accepted is ZjuTimetableFetchResult.Success)
        assertEquals(1, transport.timetablePosts().size)
    }

    @Test
    fun `timetable failure keeps the session available for a retry`() {
        val transport = FakeTransport().apply { stubLoginAndIndex(INDEX_HTML_A) }
        val options = begin(transport) as ZjuSemesterOptionsResult.Success

        assertTrue(confirm(options.handle, "2026-2027", "2|短") is ZjuTimetableFetchResult.Failure)
        assertEquals(1, transport.timetablePosts().size)

        transport.stubTimetable()
        assertTrue(confirm(options.handle, "2026-2027", "2|短") is ZjuTimetableFetchResult.Success)
        assertEquals(2, transport.timetablePosts().size)
    }

    // ── Sol code-review5 的四项修正：对应测试 ─────────────────────────────────

    @Test
    fun `a login page response invalidates the session instead of inviting a retry`() {
        // Sol review5 #1：登录页返回 ⇒ 会话已失效，原地重试不可能成功。
        val transport = FakeTransport().apply {
            stubLoginAndIndex(INDEX_HTML_A)
            enqueue(
                "POST", "xskbcx_cxXsKb",
                ZjuHttpResponse(
                    200,
                    URI("https://zdbk.zju.edu.cn/jwglxt/kbcx/xskbcx_cxXsKb.html"),
                    "<html><body>统一身份认证登录</body></html>"
                )
            )
        }
        val options = begin(transport) as ZjuSemesterOptionsResult.Success

        val first = confirm(options.handle, "2026-2027", "2|短")
        assertTrue(first is ZjuTimetableFetchResult.Failure)
        val failure = first as ZjuTimetableFetchResult.Failure
        assertEquals("教务登录已失效，请重新完成统一身份认证。", failure.message)
        // 关键：必须标记会话不可用，UI 才能清掉旧选项并要求重新登录。
        assertTrue("session must be invalidated", failure.sessionInvalid)

        // 可证伪点：会话已作废 ⇒ 再次确认不得再发 POST，而是直接报"已使用或已过期"。
        val second = confirm(options.handle, "2026-2027", "2|短")
        assertTrue(second is ZjuTimetableFetchResult.Failure)
        assertEquals("学期选项已使用或已过期，请重新登录并读取学期选项。", (second as ZjuTimetableFetchResult.Failure).message)
        assertEquals("expired session must not issue another POST", 1, transport.timetablePosts().size)
    }

    @Test
    fun `a captcha challenge invalidates the session instead of inviting a retry`() {
        // Sol review5 #1：验证码同样需要重新认证，重试不可能成功。
        val transport = FakeTransport().apply {
            stubLoginAndIndex(INDEX_HTML_A)
            enqueue(
                "POST", "xskbcx_cxXsKb",
                ZjuHttpResponse(
                    200,
                    URI("https://zdbk.zju.edu.cn/jwglxt/kbcx/xskbcx_cxXsKb.html"),
                    """{"captcha_error":"true"}"""
                )
            )
        }
        val options = begin(transport) as ZjuSemesterOptionsResult.Success

        val failure = confirm(options.handle, "2026-2027", "2|短") as ZjuTimetableFetchResult.Failure
        assertTrue(failure.sessionInvalid)
        assertTrue(failure.message.contains("验证码"))
        assertTrue(confirm(options.handle, "2026-2027", "2|短") is ZjuTimetableFetchResult.Failure)
        assertEquals(1, transport.timetablePosts().size)
    }

    @Test
    fun `an expired session is cleaned up without another begin or confirm call`() {
        // Sol review5 #2：TTL 不能只在"下一次调用"时清理——用户把页面放着不动也要作废。
        val scheduler = RecordingExpiryScheduler()
        val transport = FakeTransport().apply { stubLoginAndIndex(INDEX_HTML_A) }
        val options = begin(transport, expiryScheduler = scheduler) as ZjuSemesterOptionsResult.Success

        assertEquals("exactly one expiry must be scheduled", 1, scheduler.delays.size)
        // 生产按"已过时间"扣减，所以允许几毫秒误差；关键是**接近 10 分钟且不超过它**。
        val delay = scheduler.delays.single()
        assertTrue("expiry must be bound to the 10 minute TTL, was $delay", delay in (10 * 60_000L - 5_000L)..(10 * 60_000L))

        // 时间到（没有第二次 begin/confirm）：传输必须被取消，句柄必须失效。
        scheduler.fireAll()

        assertTrue("expired session must cancel its transport", transport.canceledCount() >= 1)
        val reuse = confirm(options.handle, "2026-2027", "2|短")
        assertTrue(reuse is ZjuTimetableFetchResult.Failure)
        assertEquals("学期选项已使用或已过期，请重新登录并读取学期选项。", (reuse as ZjuTimetableFetchResult.Failure).message)
        assertEquals("expired session must not issue a request", 0, transport.timetablePosts().size)
    }

    @Test
    fun `the password array is cleared as soon as the login request returns`() {
        // Sol review5 #3：明文密码在认证请求返回后必须立即清零，而不是等读完首页 option（最长 40 秒）。
        val homePageSeen = CountDownLatch(1)
        var passwordStateAtHomePage: Boolean? = null
        val password = "secret".toCharArray()
        val transport = FakeTransport().apply {
            stubLoginAndIndex(INDEX_HTML_A)
            // 首页 GET 期间（认证已完成、option 还没读完）检查调用方持有的数组。
            setGetInterceptor { url ->
                if (url.contains("xskbcx_cxXskbcxIndex")) {
                    passwordStateAtHomePage = password.all { it == '\u0000' }
                    homePageSeen.countDown()
                }
            }
        }

        begin(transport, password = password)

        assertTrue("home page must have been requested", homePageSeen.await(1, TimeUnit.SECONDS))
        assertEquals(
            "password must already be cleared while the home page is still being read",
            true,
            passwordStateAtHomePage
        )
        assertTrue(password.all { it == '\u0000' })
    }

    @Test
    fun `an expired session is not restored even if a confirm is in flight`() {
        // 复验指出的回填缺陷：到期发生在确认请求在途时，若失败路径把条目放回静态表，
        // 就等于把"超时条目滞留"又做回来（Sol review5 #2 要消灭的正是它）。
        val scheduler = RecordingExpiryScheduler()
        val transport = FakeTransport().apply { stubLoginAndIndex(INDEX_HTML_A) }
        val options = begin(transport, expiryScheduler = scheduler) as ZjuSemesterOptionsResult.Success

        val postEntered = CountDownLatch(1)
        val release = CountDownLatch(1)
        transport.setPostInterceptor { postEntered.countDown(); release.await(5, TimeUnit.SECONDS) }

        val outcomes = java.util.concurrent.atomic.AtomicInteger(0)
        var result: ZjuTimetableFetchResult? = null
        val latch = CountDownLatch(1)
        ZjuTimetableClient.confirmSelectedSemester(
            handle = options.handle,
            yearValue = "2026-2027",
            termValue = "2|短",
            onProgress = {},
            onComplete = { value -> outcomes.incrementAndGet(); result = value; latch.countDown() }
        )
        assertTrue("confirm must be in flight", postEntered.await(3, TimeUnit.SECONDS))

        // 确认在途时到期。
        scheduler.fireAll()
        release.countDown()
        awaitLatch(latch)
        settle()

        assertEquals(1, outcomes.get())
        assertTrue(requireNotNull(result) is ZjuTimetableFetchResult.Failure)
        // 关键断言：不得被回填 —— 再次确认不允许发出任何请求。
        val reuse = confirm(options.handle, "2026-2027", "2|短")
        assertTrue(reuse is ZjuTimetableFetchResult.Failure)
        assertEquals("学期选项已使用或已过期，请重新登录并读取学期选项。", (reuse as ZjuTimetableFetchResult.Failure).message)
        assertEquals("expired session must never be restored", 1, transport.timetablePosts().size)
    }

    @Test
    fun `a term that differs only by display text is a mismatch`() {
        // 复验的覆盖缺口：学期分支此前无用例。回显文字与所选学期不同 ⇒ 必须失败关闭。
        val mismatch = ZjuTimetableParser.parse(
            """{"xnm":"2026-2027","xqm":"夏","kbList":[{"xqj":"1","djj":"1","skcd":"2","kcb":"课程<br>1-16周"}]}""",
            "2026-2027",
            "1|秋"
        )
        assertTrue(mismatch is ZjuTimetableParseResult.Failure)
        assertEquals(ZjuParseFailureReason.SEMESTER_MISMATCH, (mismatch as ZjuTimetableParseResult.Failure).reason)

        // 单字回显但文字一致（真实形态）⇒ 不得误判；两个「短」无法区分，因此也不判失败。
        val shortTerm = ZjuTimetableParser.parse(
            """{"xnm":"2026-2027","xqm":"短","kbList":[{"xqj":"1","djj":"1","skcd":"2","kcb":"课程<br>1-16周<br>老师<br>东1zwf"}]}""",
            "2026-2027",
            "2|短"
        )
        assertTrue("single-character echo must not be misjudged", shortTerm is ZjuTimetableParseResult.Success)

        // 响应未回显学期 ⇒ 无法核对，不判失败。
        val noEcho = ZjuTimetableParser.parse(
            """{"xnm":"2026-2027","kbList":[{"xqj":"1","djj":"1","skcd":"2","kcb":"课程<br>1-16周<br>老师<br>东1zwf"}]}""",
            "2026-2027",
            "1|秋"
        )
        assertTrue(noEcho is ZjuTimetableParseResult.Success)
    }

    @Test
    fun `another confirmation after expiry is reported as an invalid session`() {
        // Sol review5 #2 的 UI 失效要求：到期后再点必须被告知"无效会话"，UI 才会清掉旧选项并要求重新登录。
        val scheduler = RecordingExpiryScheduler()
        val transport = FakeTransport().apply { stubLoginAndIndex(INDEX_HTML_A) }
        val options = begin(transport, expiryScheduler = scheduler) as ZjuSemesterOptionsResult.Success

        scheduler.fireAll()

        val failure = confirm(options.handle, "2026-2027", "2|短") as ZjuTimetableFetchResult.Failure
        // 到期条目已被移除，因此走"已使用或已过期"分支；关键是它必须标记为无效会话。
        assertEquals("学期选项已使用或已过期，请重新登录并读取学期选项。", failure.message)
        assertTrue("expired handle must report an invalid session", failure.sessionInvalid)
    }

    @Test
    fun `a server year that differs from the request is a mismatch`() {
        // Sol review5 #4 的合成反例：请求 2026-2027，响应回显 2025-2026 ⇒ 必须失败关闭。
        val mismatch = ZjuTimetableParser.parse(
            """{"xnm":"2025-2026","xqm":"秋","kbList":[{"xqj":"1","djj":"1","skcd":"2","kcb":"课程<br>1-16周"}]}""",
            "2026-2027",
            "1|秋"
        )
        assertTrue("mismatched year must not produce Success", mismatch is ZjuTimetableParseResult.Failure)
        val failure = mismatch as ZjuTimetableParseResult.Failure
        assertEquals(ZjuParseFailureReason.SEMESTER_MISMATCH, failure.reason)
        assertTrue(failure.message.contains("2025-2026"))

        // 对照：回显与请求一致 ⇒ 正常成功（证明上一条不是"永远失败"）。
        val consistent = ZjuTimetableParser.parse(
            """{"xnm":"2026-2027","xqm":"秋","kbList":[{"xqj":"1","djj":"1","skcd":"2","kcb":"课程<br>1-16周<br>老师<br>东1zwf"}]}""",
            "2026-2027",
            "1|秋"
        )
        assertTrue("consistent echo must succeed", consistent is ZjuTimetableParseResult.Success)
    }

    @Test
    fun `parse failures carry a recoverable or terminal reason`() {
        // Sol review5 #1 的实现前提：重试策略必须由**结构化原因**决定，而不是匹配中文文案。
        fun reasonOf(payload: String): ZjuParseFailureReason =
            (ZjuTimetableParser.parse(payload, "2026-2027", "1|秋") as ZjuTimetableParseResult.Failure).reason

        assertEquals(ZjuParseFailureReason.SESSION_LOST, reasonOf("<html><body>登录</body></html>"))
        assertEquals(ZjuParseFailureReason.CAPTCHA_REQUIRED, reasonOf("""{"captcha_error":"true"}"""))
        assertEquals(ZjuParseFailureReason.MALFORMED, reasonOf("""{"kbList":"""))
        assertEquals(ZjuParseFailureReason.NO_KB_LIST, reasonOf("""{"xnm":"2026-2027"}"""))
        assertEquals(ZjuParseFailureReason.EMPTY_PAYLOAD, reasonOf("   "))
        assertFalse(ZjuParseFailureReason.SESSION_LOST.isRecoverable)
        assertFalse(ZjuParseFailureReason.CAPTCHA_REQUIRED.isRecoverable)
        assertFalse(ZjuParseFailureReason.EMPTY_PAYLOAD.isRecoverable)
        assertTrue(ZjuParseFailureReason.MALFORMED.isRecoverable)
        assertTrue(ZjuParseFailureReason.NO_KB_LIST.isRecoverable)
        assertTrue(ZjuParseFailureReason.TOO_LARGE.isRecoverable)
        // 学期不一致可重试（换一个学期），但必须仍然失败关闭。
        assertTrue(ZjuParseFailureReason.SEMESTER_MISMATCH.isRecoverable)
    }

    @Test
    fun `an unparseable payload keeps the session available for a retry`() {
        val transport = FakeTransport().apply {
            stubLoginAndIndex(INDEX_HTML_A)
            enqueue(
                "POST", "xskbcx_cxXsKb",
                ZjuHttpResponse(
                    200,
                    URI("https://zdbk.zju.edu.cn/jwglxt/kbcx/xskbcx_cxXsKb.html"),
                    """{"kbList":"""
                )
            )
        }
        val options = begin(transport) as ZjuSemesterOptionsResult.Success

        val first = confirm(options.handle, "2026-2027", "2|短")
        assertTrue(first is ZjuTimetableFetchResult.Failure)
        // 解析层文案必须原样带回：这里断言是解析器的原文，而不是“非空”这种恒真条件。
        assertEquals(
            "无法解析教务课表响应，请确认当前页面是“学生课表查询”。",
            (first as ZjuTimetableFetchResult.Failure).message
        )

        // 解析失败不消耗句柄：用户可以直接再点一次导入，而不是被要求重新登录。
        transport.stubTimetable()
        assertTrue(confirm(options.handle, "2026-2027", "2|短") is ZjuTimetableFetchResult.Success)
        assertEquals(2, transport.timetablePosts().size)
    }

    @Test
    fun `an expired timetable session reports the parser message`() {
        val transport = FakeTransport().apply {
            stubLoginAndIndex(INDEX_HTML_A)
            // 会话失效时教务通常返回登录页而不是 JSON。
            enqueue(
                "POST", "xskbcx_cxXsKb",
                ZjuHttpResponse(
                    200,
                    URI("https://zdbk.zju.edu.cn/jwglxt/kbcx/xskbcx_cxXsKb.html"),
                    "<html><body>统一身份认证登录</body></html>"
                )
            )
        }
        val options = begin(transport) as ZjuSemesterOptionsResult.Success

        val first = confirm(options.handle, "2026-2027", "2|短")
        assertTrue(first is ZjuTimetableFetchResult.Failure)
        // 用户看到的是解析层给出的可执行原因，而不是被替换成一句无关的“请重试”。
        // 断言精确文案：换成任何非空文案都必须让本用例变红（此前这里只断言 isNotBlank，等于恒真）。
        assertEquals(
            "教务登录已失效，请重新完成统一身份认证。",
            (first as ZjuTimetableFetchResult.Failure).message
        )
    }

    @Test
    fun `a timeout keeps the session available for a retry`() {
        val transport = FakeTransport().apply { stubLoginAndIndex(INDEX_HTML_A) }
        val options = begin(transport) as ZjuSemesterOptionsResult.Success
        // 超时走的是与通用异常不同的分支；第 3 轮缺陷正是“超时静默消耗句柄”，必须有测试钉住。
        transport.setPostInterceptor { throw SocketTimeoutException("read timed out") }

        val first = confirm(options.handle, "2026-2027", "2|短")
        assertTrue(first is ZjuTimetableFetchResult.Failure)
        assertTrue((first as ZjuTimetableFetchResult.Failure).message.contains("超时"))

        transport.setPostInterceptor { }
        transport.stubTimetable()
        assertTrue(confirm(options.handle, "2026-2027", "2|短") is ZjuTimetableFetchResult.Success)
        assertEquals(2, transport.timetablePosts().size)
    }

    @Test
    fun `an unknown host keeps the session available for a retry`() {
        val transport = FakeTransport().apply { stubLoginAndIndex(INDEX_HTML_A) }
        val options = begin(transport) as ZjuSemesterOptionsResult.Success
        transport.setPostInterceptor { throw java.net.UnknownHostException("zdbk.zju.edu.cn") }

        assertTrue(confirm(options.handle, "2026-2027", "2|短") is ZjuTimetableFetchResult.Failure)

        transport.setPostInterceptor { }
        transport.stubTimetable()
        assertTrue(confirm(options.handle, "2026-2027", "2|短") is ZjuTimetableFetchResult.Success)
        assertEquals(2, transport.timetablePosts().size)
    }

    @Test
    fun `canceling a transport drops the session it just registered`() {
        val transport = FakeTransport().apply { stubLoginAndIndex(INDEX_HTML_A) }
        // 第一阶段刚登记句柄、回调还在投递队列里时离开页面：cancelTransport 必须把这条例会话一起清掉，
        // 否则它会成为无人可达、却持有账号与已认证会话的孤儿条目（第 3 轮修的就是这条）。
        val options = begin(transport) as ZjuSemesterOptionsResult.Success
        ZjuTimetableClient.cancelTransport(transport)

        val rejected = confirm(options.handle, "2026-2027", "2|短")
        assertTrue(rejected is ZjuTimetableFetchResult.Failure)
        assertEquals(
            "学期选项已使用或已过期，请重新登录并读取学期选项。",
            (rejected as ZjuTimetableFetchResult.Failure).message
        )
    }

    @Test
    fun `canceling a transport leaves other waiting sessions untouched`() {
        val target = FakeTransport().apply { stubLoginAndIndex(INDEX_HTML_A) }
        val other = FakeTransport().apply { stubLoginAndIndex(INDEX_HTML_B) }
        val targetOptions = begin(target) as ZjuSemesterOptionsResult.Success
        val otherOptions = begin(other) as ZjuSemesterOptionsResult.Success

        ZjuTimetableClient.cancelTransport(target)
        target.stubTimetable()
        other.stubTimetable()

        assertTrue(confirm(targetOptions.handle, "2026-2027", "2|短") is ZjuTimetableFetchResult.Failure)
        assertTrue(confirm(otherOptions.handle, "2030-2031", "2|春") is ZjuTimetableFetchResult.Success)
    }

    @Test
    fun `a transport failure keeps the session available for a retry`() {
        val transport = FakeTransport().apply { stubLoginAndIndex(INDEX_HTML_A) }
        val options = begin(transport) as ZjuSemesterOptionsResult.Success
        transport.setPostInterceptor { throw IllegalStateException("connection reset") }

        val first = confirm(options.handle, "2026-2027", "2|短")
        assertTrue("first attempt should fail", first is ZjuTimetableFetchResult.Failure)

        transport.setPostInterceptor { }
        transport.stubTimetable()
        assertTrue(confirm(options.handle, "2026-2027", "2|短") is ZjuTimetableFetchResult.Success)
        assertEquals(2, transport.timetablePosts().size)
    }

    @Test
    fun `a session handle accepts only one confirmation`() {
        val transport = FakeTransport().apply {
            stubLoginAndIndex(INDEX_HTML_A)
            stubTimetable()
        }
        val options = begin(transport) as ZjuSemesterOptionsResult.Success

        assertTrue(confirm(options.handle, "2026-2027", "2|短") is ZjuTimetableFetchResult.Success)
        assertTrue(confirm(options.handle, "2026-2027", "2|短") is ZjuTimetableFetchResult.Failure)
        assertEquals(1, transport.timetablePosts().size)
    }

    @Test
    fun `confirm racing with cancel never leaves a resurrected session`() {
        // 入口方向（取走句柄 + 登记在途）与出口方向（摘除在途 + 放回句柄）现在都必须在同一把
        // restoreLock 里完成。任一方向敞开，落在窗口里的取消都会被静默丢弃，句柄随后被“复活”放回
        // ——那时本用例的复用会真的发出请求并失败，从而变红。
        //
        // 关键：确认线程的这次尝试必须**失败**（这里让 POST 直接抛超时），否则它走成功分支、
        // 句柄被正常消耗，根本不存在“复活”状态，用例就成了摆设（这正是上一轮的问题）。
        repeat(60) { round ->
            val transport = FakeTransport().apply { stubLoginAndIndex(INDEX_HTML_A) }
            // 注意：拦截器必须装在 begin() **之后**——登录本身也走 POST，装早了会让登录直接失败。
            val options = begin(transport) as ZjuSemesterOptionsResult.Success
            transport.setPostInterceptor { throw SocketTimeoutException("synthetic timeout for race window") }
            val confirmStarted = CountDownLatch(1)
            val cancelDone = CountDownLatch(1)
            val confirmDone = CountDownLatch(1)

            Thread {
                confirmStarted.countDown()
                ZjuTimetableClient.confirmSelectedSemester(
                    handle = options.handle,
                    yearValue = "2026-2027",
                    termValue = "2|短",
                    onProgress = {},
                    onComplete = { /* 并发语义由下面的“取消后不可用”断言覆盖 */ }
                )
                confirmDone.countDown()
            }.start()

            Thread {
                confirmStarted.await()
                ZjuTimetableClient.cancelSession(options.handle)
                cancelDone.countDown()
            }.start()

            awaitLatch(cancelDone)
            awaitLatch(confirmDone)

            // 取消已发生：句柄必须彻底不可再用——若被“复活”放回，这里会走到真实请求并拿到别的失败。
            val reuse = confirm(options.handle, "2026-2027", "2|短")
            assertTrue(
                "round $round: canceled session must be rejected, got $reuse",
                reuse is ZjuTimetableFetchResult.Failure
            )
            val rejected = reuse as ZjuTimetableFetchResult.Failure
            assertTrue(
                "round $round: canceled session must not be resurrected (got: ${rejected.message})",
                rejected.message.contains("已使用或已过期")
            )
        }
    }

    @Test
    fun `concurrent confirmations issue at most one timetable request`() {
        val transport = FakeTransport().apply {
            stubLoginAndIndex(INDEX_HTML_A)
            stubTimetable()
        }
        val options = begin(transport) as ZjuSemesterOptionsResult.Success
        val done = CountDownLatch(2)
        val outcomes = java.util.concurrent.ConcurrentLinkedQueue<ZjuTimetableFetchResult>()

        repeat(2) {
            Thread {
                ZjuTimetableClient.confirmSelectedSemester(
                    handle = options.handle,
                    yearValue = "2026-2027",
                    termValue = "2|短",
                    onProgress = {},
                    onComplete = { outcome ->
                        outcomes += outcome
                        done.countDown()
                    }
                )
            }.start()
        }
        awaitLatch(done)

        assertEquals(1, transport.timetablePosts().size)
        assertEquals(1, outcomes.count { it is ZjuTimetableFetchResult.Success })
    }

    @Test
    fun `canceled and expired sessions never issue a timetable request`() {
        val canceledTransport = FakeTransport().apply {
            stubLoginAndIndex(INDEX_HTML_A)
            stubTimetable()
        }
        val canceled = begin(canceledTransport) as ZjuSemesterOptionsResult.Success
        ZjuTimetableClient.cancelSession(canceled.handle)
        assertTrue(confirm(canceled.handle, "2026-2027", "2|短") is ZjuTimetableFetchResult.Failure)
        assertTrue(canceledTransport.timetablePosts().isEmpty())

        val expiredTransport = FakeTransport().apply {
            stubLoginAndIndex(INDEX_HTML_A)
            stubTimetable()
        }
        val startedAt = 1_000L
        val expired = begin(expiredTransport, now = startedAt) as ZjuSemesterOptionsResult.Success
        val afterTtl = startedAt + 10 * 60_000L + 1
        assertTrue(confirm(expired.handle, "2026-2027", "2|短", now = afterTtl) is ZjuTimetableFetchResult.Failure)
        assertTrue(expiredTransport.timetablePosts().isEmpty())
    }

    @Test
    fun `re-authentication does not reuse the previous options`() {
        val firstTransport = FakeTransport().apply {
            stubLoginAndIndex(INDEX_HTML_A)
            stubTimetable()
        }
        val first = begin(firstTransport) as ZjuSemesterOptionsResult.Success
        ZjuTimetableClient.cancelSession(first.handle)

        val secondTransport = FakeTransport().apply {
            stubLoginAndIndex(INDEX_HTML_B)
            stubTimetable()
        }
        val second = begin(secondTransport) as ZjuSemesterOptionsResult.Success
        assertEquals(listOf("2030-2031"), second.yearOptions.map { it.value })

        assertTrue(confirm(first.handle, "2030-2031", "2|春") is ZjuTimetableFetchResult.Failure)
        assertTrue(confirm(second.handle, "2030-2031", "2|春") is ZjuTimetableFetchResult.Success)
        assertTrue(secondTransport.timetablePosts().single().second.contains("xnm=" + URLEncoder.encode("2030-2031", "UTF-8")))
    }

    @Test
    fun `view model exits the running state after the original activity is gone`() {
        val transport = FakeTransport().apply { stubLoginAndIndex(INDEX_HTML_A) }
        val model = ZjuImportViewModel { transport }
        val password = "secret".toCharArray()

        model.startLogin("student", password)

        val deadline = System.currentTimeMillis() + 5_000
        while (model.running && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
            Shadows.shadowOf(Looper.getMainLooper()).idle()
        }
        // 模拟旧 Activity 已销毁：ViewModel 仍须自行复位并留下成功结果供新实例观察。
        assertTrue("view model stayed busy after completion", !model.running)
        assertTrue(requireNotNull(model.options) is ZjuSemesterOptionsResult.Success)
        assertTrue(model.outcome is ZjuSemesterOptionsResult.Success)
        assertTrue(model.pendingSession != null)
        assertTrue(password.all { it == '\u0000' })
        model.cancelAllWaiting()
        assertTrue(model.pendingSession == null)
    }

    @Test
    fun `view model type has a public no-arg constructor for the default provider`() {
        val constructor = ZjuImportViewModel::class.java.getDeclaredConstructor()
        assertTrue(
            "ViewModelProvider needs a public no-arg constructor",
            java.lang.reflect.Modifier.isPublic(constructor.modifiers)
        )
        assertTrue(constructor.newInstance() is ZjuImportViewModel)
    }

    @Test
    fun `a canceled real http session fails an in-flight request immediately`() {
        val session = ZjuTimetableClient.HttpSession()
        session.cancel()
        assertTrue(session.isCanceled())

        val attempt = runCatching {
            session.get("https://zdbk.zju.edu.cn/jwglxt/kbcx/xskbcx_cxXskbcxIndex.html", totalTimeoutMs = 30_000)
        }

        // 不再发起任何网络 I/O（无 DNS、无连接），且失败必须来自取消而不是超时。
        val error = attempt.exceptionOrNull()
        assertTrue("canceled session must fail instead of returning", error is SocketTimeoutException)
        assertEquals("教务导入已取消", error?.message)
    }

    @Test
    fun `homepage without usable options fails before any timetable request`() {
        val transport = FakeTransport().apply { stubLoginAndIndex("<div>没有下拉</div>") }
        val password = "secret".toCharArray()

        val result = begin(transport, password)

        assertTrue(result is ZjuSemesterOptionsResult.Failure)
        assertTrue(transport.timetablePosts().isEmpty())
        assertTrue(password.all { it == '\u0000' })
    }

    @Test
    fun `a throwable on the login thread still delivers exactly one result`() {
        val transport = object : ZjuHttpClient {
            override fun get(url: String, totalTimeoutMs: Long?): ZjuHttpResponse =
                throw OutOfMemoryError("synthetic transport failure")

            override fun post(
                url: String,
                body: String,
                headers: Map<String, String>,
                readTimeoutMs: Int,
                skipResponseBodyAtHost: String?,
                onRedirect: (URI) -> Unit
            ): ZjuHttpResponse = error("login must fail before any POST")
        }
        val outcomes = java.util.concurrent.atomic.AtomicInteger(0)
        var result: ZjuSemesterOptionsResult? = null
        val latch = CountDownLatch(1)

        ZjuTimetableClient.beginSession(
            username = "student",
            password = "secret".toCharArray(),
            onProgress = {},
            onComplete = { value ->
                outcomes.incrementAndGet()
                result = value
                latch.countDown()
            },
            transportFactory = { transport }
        )
        awaitLatch(latch)
        settle()
        val failure = requireNotNull(result)
        assertTrue(failure is ZjuSemesterOptionsResult.Failure)
        assertTrue((failure as ZjuSemesterOptionsResult.Failure).message.isNotBlank())
        assertEquals(1, outcomes.get())
    }

    @Test
    fun `canceling during the first phase releases a blocked transport exactly once`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val canceled = java.util.concurrent.atomic.AtomicInteger(0)
        val blocked = object : ZjuHttpClient {
            override fun cancel() {
                canceled.incrementAndGet()
                release.countDown()
            }

            override fun isCanceled(): Boolean = canceled.get() > 0

            override fun get(url: String, totalTimeoutMs: Long?): ZjuHttpResponse {
                entered.countDown()
                release.await(10, TimeUnit.SECONDS)
                throw IllegalStateException("cancelled transport released")
            }

            override fun post(
                url: String,
                body: String,
                headers: Map<String, String>,
                readTimeoutMs: Int,
                skipResponseBodyAtHost: String?,
                onRedirect: (URI) -> Unit
            ): ZjuHttpResponse = error("cancelled login must not POST")
        }
        val outcomes = java.util.concurrent.atomic.AtomicInteger(0)
        var result: ZjuSemesterOptionsResult? = null
        val latch = CountDownLatch(1)

        ZjuTimetableClient.beginSession(
            username = "student",
            password = "secret".toCharArray(),
            onProgress = {},
            onComplete = { value ->
                outcomes.incrementAndGet()
                result = value
                latch.countDown()
            },
            transportFactory = { blocked }
        )
        assertTrue("login request never started", entered.await(3, TimeUnit.SECONDS))

        ZjuTimetableClient.cancelTransport(blocked)

        awaitLatch(latch)
        settle()
        val failure = requireNotNull(result)
        assertTrue(failure is ZjuSemesterOptionsResult.Failure)
        // 传输层确认自己已被取消 ⇒ 结果必须是「取消」而不是「超时/检查网络」。
        assertEquals("导入已取消。", (failure as ZjuSemesterOptionsResult.Failure).message)
        assertEquals(1, outcomes.get())
        assertEquals(1, canceled.get())
    }

    @Test
    fun `canceling a waiting session interrupts the in-flight timetable request`() {
        val transport = FakeTransport().apply { stubLoginAndIndex(INDEX_HTML_A) }
        val options = begin(transport) as ZjuSemesterOptionsResult.Success
        val postEntered = CountDownLatch(1)
        val cancelAcked = CountDownLatch(1)
        // 传输层的 cancel 直接中断阻塞中的 POST 并抛出，等价于真实连接被断开。
        transport.setPostInterceptor { postEntered.countDown(); cancelAcked.await(5, TimeUnit.SECONDS) }
        transport.setCancelAction { cancelAcked.countDown() }

        val outcomes = java.util.concurrent.atomic.AtomicInteger(0)
        var result: ZjuTimetableFetchResult? = null
        val latch = CountDownLatch(1)
        ZjuTimetableClient.confirmSelectedSemester(
            handle = options.handle,
            yearValue = "2026-2027",
            termValue = "2|短",
            onProgress = {},
            onComplete = { value ->
                outcomes.incrementAndGet()
                result = value
                latch.countDown()
            }
        )
        // 确认请求真正进入在途（POST 已发出）后再取消：没有这个信号就不能证明取消打断的是它。
        assertTrue("timetable POST never started", postEntered.await(3, TimeUnit.SECONDS))
        ZjuTimetableClient.cancelSession(options.handle)

        awaitLatch(latch)
        settle()
        val failure = requireNotNull(result)
        assertTrue(failure is ZjuTimetableFetchResult.Failure)
        assertEquals("导入已取消。", (failure as ZjuTimetableFetchResult.Failure).message)
        assertEquals(1, outcomes.get())
        assertTrue("client must cancel the in-flight transport", transport.canceledCount() >= 1)
        // 取消后句柄不得被放回：再确认必须失败，且不得再发出课表请求。
        assertEquals(1, transport.timetablePosts().size)
        assertTrue(confirm(options.handle, "2026-2027", "2|短") is ZjuTimetableFetchResult.Failure)
        assertEquals(1, transport.timetablePosts().size)
    }

    @Test
    fun `view model leaving the page cancels a blocked first phase request`() {
        val entered = CountDownLatch(1)
        val released = CountDownLatch(1)
        val cancelCount = java.util.concurrent.atomic.AtomicInteger(0)
        val blocked = object : ZjuHttpClient {
            override fun cancel() {
                cancelCount.incrementAndGet()
                released.countDown()
            }

            override fun isCanceled(): Boolean = cancelCount.get() > 0

            override fun get(url: String, totalTimeoutMs: Long?): ZjuHttpResponse {
                entered.countDown()
                released.await(10, TimeUnit.SECONDS)
                throw IllegalStateException("cancelled transport released")
            }

            override fun post(
                url: String,
                body: String,
                headers: Map<String, String>,
                readTimeoutMs: Int,
                skipResponseBodyAtHost: String?,
                onRedirect: (URI) -> Unit
            ): ZjuHttpResponse = error("cancelled login must not POST")
        }
        val model = ZjuImportViewModel { blocked }
        model.startLogin("student", "secret".toCharArray())
        assertTrue("login request never started", entered.await(3, TimeUnit.SECONDS))
        assertTrue(model.running)

        model.cancelAllWaiting()

        assertEquals(1, cancelCount.get())
        // 结果仍会恰好投递一次，且 VM 不会停在 running。
        val deadline = System.currentTimeMillis() + 5_000
        while (model.running && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
            Shadows.shadowOf(Looper.getMainLooper()).idle()
        }
        assertTrue("view model stayed busy after cancellation", !model.running)
        assertTrue((model.failure ?: "").contains("导入已取消"))
        // 取消路径不应留下可供继续使用的会话句柄。
        assertTrue(model.pendingSession == null)
    }

    private companion object {
        /** 一份合法的课表响应：用于"已知登出 + 合法正文"这类反例，证明登出信号不被正文推翻。 */
        const val TIMETABLE_JSON =
            """{"kbList":[{"xqj":"1","djj":"1","skcd":"2","kcb":"课程<br>1-16周<br>老师<br>东1zwf","xkkh":"KEY-1"}]}"""

        val CAS_FORM = """
            <form>
              <input type="hidden" name="execution" value="e1s1">
              <input name="username" value="">
              <input type="password" name="password">
            </form>
        """.trimIndent()

        val INDEX_HTML_A = """
            <select id="xnm">
              <option value="2026-2027" selected>2026-2027</option>
              <option value="2025-2026">2025-2026</option>
            </select>
            <select id="xqm">
              <option value="1|秋" selected>秋</option>
              <option value="1|短">短</option>
              <option value="2|短">短</option>
            </select>
        """.trimIndent()

        val INDEX_HTML_B = """
            <select id="xnm">
              <option value="2030-2031" selected>2030-2031</option>
            </select>
            <select id="xqm">
              <option value="2|春" selected>春</option>
            </select>
        """.trimIndent()
    }
}
