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
        private var cancelAction: (() -> Unit)? = null
        private val cancels = java.util.concurrent.atomic.AtomicInteger(0)

        fun enqueue(method: String, urlPart: String, response: ZjuHttpResponse) {
            queues.getOrPut("$method $urlPart") { ArrayDeque() }.addLast(response)
        }

        fun setPostInterceptor(interceptor: () -> Unit) {
            postInterceptor = interceptor
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

        override fun get(url: String, totalTimeoutMs: Long?): ZjuHttpResponse = next("GET", url)

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
            ZjuHttpResponse(
                200,
                URI("https://zdbk.zju.edu.cn/jwglxt/kbcx/xskbcx_cxXsKb.html"),
                """{"kbList":[{"xqj":"1","djj":"1","skcd":"2","kcb":"课程<br>1-16周<br>老师<br>东1zwf","xkkh":"KEY-1"}]}"""
            )
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

    private fun begin(
        transport: FakeTransport,
        password: CharArray = "secret".toCharArray(),
        now: Long = System.currentTimeMillis()
    ): ZjuSemesterOptionsResult = awaitOptions { complete ->
        ZjuTimetableClient.beginSession(
            username = "student",
            password = password,
            onProgress = {},
            onComplete = complete,
            now = now,
            transportFactory = { transport }
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
