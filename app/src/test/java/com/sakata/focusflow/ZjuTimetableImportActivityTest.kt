package com.sakata.focusflow

import android.app.Application
import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import java.net.URI
import java.util.ArrayDeque
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * 覆盖 ZjuTimetableImportActivity 的真实创建路径（此前没有任何测试构造过它）：
 * Compose 内容能否建立、ViewModelProvider 能否取到无参构造的 ViewModel，以及配置变更后
 * 同一个 ViewModel 的进行中状态/待确认会话是否保留（缺陷 1 依赖这一保留语义）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ZjuTimetableImportActivityTest {
    /** 最小可注入传输：登录与选项读取全部离线完成。 */
    private class StubTransport : ZjuHttpClient {
        private val queues = mutableMapOf<String, ArrayDeque<ZjuHttpResponse>>()

        fun enqueue(method: String, urlPart: String, response: ZjuHttpResponse) {
            queues.getOrPut("$method $urlPart") { ArrayDeque() }.addLast(response)
        }

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
            onRedirect: (URI) -> Unit,
            totalTimeoutMs: Long?
        ): ZjuHttpResponse = next("POST", url)
    }

    private fun StubTransport.stubLoginAndIndex() {
        enqueue("GET", "cas/login", ZjuHttpResponse(200, URI("https://zjuam.zju.edu.cn/cas/login"), CAS_FORM))
        enqueue("GET", "cas/login", ZjuHttpResponse(200, URI("https://zjuam.zju.edu.cn/cas/login"), CAS_FORM))
        enqueue(
            "GET", "getPubKey",
            ZjuHttpResponse(200, URI("https://zjuam.zju.edu.cn/cas/v2/getPubKey"), """{"modulus":"e1","exponent":"3"}""")
        )
        enqueue("POST", "cas/login", ZjuHttpResponse(200, URI("https://zdbk.zju.edu.cn/jwglxt/xtgl/index"), ""))
        enqueue(
            "GET", "xskbcx_cxXskbcxIndex",
            ZjuHttpResponse(
                200,
                URI("https://zdbk.zju.edu.cn/jwglxt/kbcx/xskbcx_cxXskbcxIndex.html"),
                """
                    <select id="xnm"><option value="2026-2027" selected>2026-2027</option></select>
                    <select id="xqm"><option value="2|短" selected>短</option></select>
                """.trimIndent()
            )
        )
    }

    private fun driveUntil(timeoutMs: Long, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
            Shadows.shadowOf(Looper.getMainLooper()).idle()
        }
    }

    /** 离线跑完第一步登录，得到一个持有待确认会话的 ViewModel。 */
    private fun loggedInModel(): ZjuImportViewModel {
        val transport = StubTransport().apply { stubLoginAndIndex() }
        val model = ZjuImportViewModel { transport }
        model.startLogin("student", "secret".toCharArray())
        driveUntil(timeoutMs = 5_000) {
            val options = model.options
            options != null || !model.running
        }
        return model
    }

    @Test
    fun `apply fetch failure clears the stale semester options and never repeats the suffix`() {
        // applyFetchFailure 此前零引用（复验点名），而它决定"会话失效后按钮还能不能点"。
        val model = loggedInModel()
        assertNotNull("precondition: options must be loaded", model.options)
        assertNotNull("precondition: a pending session must exist", model.pendingSession)
        // 设一个非空阶段，否则下面的 assertNull 是空断言（登录成功时它本来就是 null）。
        model.currentStage = ZjuImportStage.PARSING

        // 1) 会话失效（到期文案）：必须清掉旧选项并把按钮退回重新登录。
        model.applyFetchFailure(
            ZjuTimetableFetchResult.Failure(
                "本次登录已过期，请重新登录后再导入。",
                sessionInvalid = true
            )
        )
        assertNull("stale options must be cleared", model.options)
        assertNull("stale session must be cleared", model.pendingSession)
        assertNull(model.selectedYearValue)
        assertNull(model.selectedTermValue)
        assertNull("failed stage must be reset", model.currentStage)
        // 文案自带"重新登录"时不得再追加一次（否则出现重复句）。
        assertEquals("本次登录已过期，请重新登录后再导入。", model.failure)

        // 2) 验证码文案：同样清选项，且只追加一次后缀（它自己不含"重新登录"）。
        //    文案取解析器原文，避免"手写变体"掩盖真实行为。
        val captcha = loggedInModel()
        captcha.applyFetchFailure(
            ZjuTimetableFetchResult.Failure(
                "教务系统要求完成验证码，请在官方页面验证后重试。",
                sessionInvalid = true
            )
        )
        assertNull(captcha.options)
        assertEquals(
            "教务系统要求完成验证码，请在官方页面验证后重试。请重新登录后再导入。",
            captcha.failure
        )

        // 2b) 登录失效文案（SESSION_LOST）：它自带"重新完成统一身份认证"，不得再追加后缀。
        val sessionLost = loggedInModel()
        sessionLost.applyFetchFailure(
            ZjuTimetableFetchResult.Failure(
                "教务登录已失效，请重新完成统一身份认证。",
                sessionInvalid = true
            )
        )
        assertNull(sessionLost.options)
        assertEquals("教务登录已失效，请重新完成统一身份认证。", sessionLost.failure)

        // 3) 普通可重试失败：保留选项，只改文案（不得清掉用户已读到的学期列表）。
        val retryable = loggedInModel()
        retryable.applyFetchFailure(ZjuTimetableFetchResult.Failure("下载课表数据超时，请稍后重试。"))
        assertNotNull("a retryable failure must keep the options", retryable.options)
        assertEquals("下载课表数据超时，请稍后重试。", retryable.failure)
    }

    @Test
    fun `activity creates its content and resolves the import view model`() {
        val controller = Robolectric.buildActivity(ZjuTimetableImportActivity::class.java).setup()
        val activity = controller.get()
        val model = ViewModelProvider(activity)[ZjuImportViewModel::class.java]
        assertNotNull(model)
        controller.pause().stop().destroy()
    }

    @Test
    fun `the import view model survives a configuration change`() {
        val controller = Robolectric.buildActivity(ZjuTimetableImportActivity::class.java).setup()
        val activity = controller.get()
        val model = ViewModelProvider(activity)[ZjuImportViewModel::class.java]
        model.currentStage = ZjuImportStage.AUTHENTICATING
        model.running = true

        // 必须真的改变一个配置限定符：Robolectric 只在配置发生变化时才走销毁重建，
        // 不改限定符时 configurationChange() 只是 onConfigurationChanged，断言会退化成恒真。
        RuntimeEnvironment.setQualifiers("+night")
        val recreated = controller.configurationChange().get()

        // 先证明“重建确实发生了”，否则后面的“ViewModel 保留”没有任何意义。
        assertNotSame("activity must be recreated by the configuration change", activity, recreated)
        assertSame(model, ViewModelProvider(recreated)[ZjuImportViewModel::class.java])
        assertSame(ZjuImportStage.AUTHENTICATING, model.currentStage)
        assertTrue(model.running)
        runCatching { controller.pause().stop().destroy() }
    }

    @Test
    fun `leaving the page stops a login that completed too late`() {
        val model = loggedInModel()
        val handle = requireNotNull(model.pendingSession)
        assertNotNull(model.options)

        // 用户已经离开页面（ViewModel 仍存活但界面不再消费结果）：待确认会话必须被作废，
        // 不能留在引擎里等 TTL（否则页面文案“账号、密码与会话均不保存”不成立）。
        model.cancelAllWaiting()

        assertNull("pending session must be dropped on leave", model.pendingSession)
        assertFalse(model.running)
        // 该句柄随即不可再用，必须重新登录。
        var outcome: ZjuTimetableFetchResult? = null
        ZjuTimetableClient.confirmSelectedSemester(
            handle = handle,
            yearValue = "2026-2027",
            termValue = "2|短",
            onProgress = {},
            onComplete = { value -> outcome = value }
        )
        driveUntil(timeoutMs = 5_000) { outcome != null }
        assertTrue(outcome is ZjuTimetableFetchResult.Failure)
        val failure = outcome as ZjuTimetableFetchResult.Failure
        assertEquals("学期选项已使用或已过期，请重新登录并读取学期选项。", failure.message)
    }

    private companion object {
        val CAS_FORM = """
            <form>
              <input type="hidden" name="execution" value="e1s1">
              <input name="username" value="">
              <input type="password" name="password">
            </form>
        """.trimIndent()
    }
}
