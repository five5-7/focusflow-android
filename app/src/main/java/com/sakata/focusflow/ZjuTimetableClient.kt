package com.sakata.focusflow

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.math.BigInteger
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLEncoder
import java.nio.CharBuffer
import java.util.Timer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal enum class ZjuImportStage(val percent: Int, val label: String) {
    CONNECTING(8, "正在连接浙江大学统一身份认证…"),
    ENCRYPTING(22, "正在获取登录参数并加密密码…"),
    AUTHENTICATING(40, "正在验证账号…"),
    ESTABLISHING_SESSION(58, "账号已验证，正在建立教务会话…"),
    LOADING_SEMESTER(70, "正在读取当前学年与学期…"),
    FETCHING_TIMETABLE(84, "正在下载课表数据…"),
    PARSING(95, "正在解析并核对课程…"),
    DONE(100, "课表获取完成")
}

internal sealed interface ZjuTimetableFetchResult {
    data class Success(
        val payload: String,
        val schoolYear: String,
        val semester: String,
        val schoolYearCode: String,
        val termCode: String
    ) : ZjuTimetableFetchResult

    data class Failure(val message: String) : ZjuTimetableFetchResult
}

internal data class ZjuCasFormField(val name: String, val value: String, val type: String)
internal data class ZjuSemesterOption(val value: String, val text: String, val selected: Boolean)

/** 两阶段流程第一步的结果：全部候选选项与一次性会话句柄。 */
internal sealed interface ZjuSemesterOptionsResult {
    data class Success(
        val handle: ZjuTimetableSessionHandle,
        val yearOptions: List<ZjuSemesterOption>,
        val termOptions: List<ZjuSemesterOption>
    ) : ZjuSemesterOptionsResult

    data class Failure(val message: String) : ZjuSemesterOptionsResult
}

internal data class ZjuTimetableSessionHandle internal constructor(val id: Long)

/** 可注入的只读传输替身入口，仅用于测试与真实 HttpSession 实现。 */
internal interface ZjuHttpClient {
    /** 可选取消：真实 HttpSession 会中断在途请求并断开连接；测试替身默认空实现。 */
    fun cancel() = Unit

    /** 是否已被取消；默认 false，供第一阶段在没有句柄时判断取消态。 */
    fun isCanceled(): Boolean = false

    fun get(url: String, totalTimeoutMs: Long? = null): ZjuHttpResponse

    fun post(
        url: String,
        body: String,
        headers: Map<String, String>,
        readTimeoutMs: Int = 18_000,
        skipResponseBodyAtHost: String? = null,
        onRedirect: (URI) -> Unit = {}
    ): ZjuHttpResponse
}

internal data class ZjuHttpResponse(val code: Int, val url: URI, val body: String)

/**
 * 浙江大学课表原生短链路。账号和密码不写入文件、SharedPreferences 或日志；
 * 密码由调用方以 CharArray 传入，完成（含失败）后立即覆写。
 */
internal object ZjuTimetableClient {
    private const val CAS_BASE = "https://zjuam.zju.edu.cn"
    private const val ZDBK_BASE = "https://zdbk.zju.edu.cn"
    private const val SERVICE_URL = "$ZDBK_BASE/jwglxt/xtgl/login_ssologin.html"
    private const val MAX_RESPONSE_CHARS = 1_000_000
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Mobile Safari/537.36"
    private val inputTag = Regex("<input\\b([^>]*)/?>", RegexOption.IGNORE_CASE)
    private val optionTag = Regex("<option\\b([^>]*)>([\\s\\S]*?)</option>", RegexOption.IGNORE_CASE)
    private val htmlTag = Regex("<[^>]+>")

    private const val SESSION_TTL_MS = 10 * 60_000L
    private val sessionIds = AtomicLong(0)
    private val pendingSessions = ConcurrentHashMap<Long, PendingSession>()

    /** 正在执行课表请求的会话；用于取消在途请求，与 `pendingSessions`（待确认）分开持有。 */
    private val inFlightSessions = ConcurrentHashMap<Long, PendingSession>()

    /** 保护“取消检查 + 放回 pendingSessions”这一对操作的原子性。 */
    private val restoreLock = Any()

    /**
     * 待确认会话。`canceled` 由 [cancelSession] 置位，[ZjuHttpClient.cancel] 负责中断在途网络请求；
     * 取消后即使网络路径返回成功也不再消耗句柄。
     */
    private class PendingSession(
        val transport: ZjuHttpClient,
        val username: String,
        val indexUrl: String,
        val yearOptions: List<ZjuSemesterOption>,
        val termOptions: List<ZjuSemesterOption>,
        val createdAt: Long,
        val canceled: AtomicBoolean = AtomicBoolean(false)
    ) {
        fun requestCancel() {
            canceled.set(true)
            runCatching { transport.cancel() }
        }
    }

    /**
     * 一次确认尝试的结果，三种形态互斥：
     * - [result] 非空：成功。
     * - [failureMessage] 非空且 [sessionUsable] 为 true：这次请求失败但会话仍有效（网络/超时/解析），句柄放回以便原地重试。
     * - [failureMessage] 非空且 [sessionUsable] 为 false：取消——不放回句柄。
     */
    private class ConfirmAttempt(
        val result: ZjuTimetableFetchResult.Success?,
        val failureMessage: String?,
        val sessionUsable: Boolean
    )

    /** 第一步：登录并读取首页学年/学期选项；密码在本次调用内清零。 */
    fun beginSession(
        username: String,
        password: CharArray,
        onProgress: (ZjuImportStage) -> Unit,
        onComplete: (ZjuSemesterOptionsResult) -> Unit,
        now: Long = System.currentTimeMillis(),
        transportFactory: () -> ZjuHttpClient = { HttpSession() }
    ) {
        purgeExpiredSessions(now)
        val transport = try {
            transportFactory()
        } catch (error: Throwable) {
            dispatch(onComplete, ZjuSemesterOptionsResult.Failure(failureMessageOf(error)))
            password.fill('\u0000')
            return
        }
        try {
            Thread {
                var currentStage = ZjuImportStage.CONNECTING
                val result = try {
                    beginSessionBlocking(username.trim(), password, transport, now) { stage ->
                        currentStage = stage
                        dispatch(onProgress, stage)
                    }
                } catch (_: SocketTimeoutException) {
                    if (transport.isCanceled()) ZjuSemesterOptionsResult.Failure(CANCELED_MESSAGE)
                    else ZjuSemesterOptionsResult.Failure(timeoutMessage(currentStage))
                } catch (_: java.net.UnknownHostException) {
                    if (transport.isCanceled()) ZjuSemesterOptionsResult.Failure(CANCELED_MESSAGE)
                    else ZjuSemesterOptionsResult.Failure("无法连接浙江大学教务，请检查网络或稍后重试。")
                } catch (error: Throwable) {
                    if (transport.isCanceled()) ZjuSemesterOptionsResult.Failure(CANCELED_MESSAGE)
                    else ZjuSemesterOptionsResult.Failure(failureMessageOf(error))
                } finally {
                    password.fill('\u0000')
                }
                dispatch(onComplete, result)
            }.apply {
                name = "FocusFlow-ZjuImport"
                isDaemon = true
                start()
            }
        } catch (error: Throwable) {
            // 线程创建失败等同步异常：仍保证一次回调，并把密码清零。
            password.fill('\u0000')
            dispatch(onComplete, ZjuSemesterOptionsResult.Failure(failureMessageOf(error)))
        }
    }

    /** 第二步：用同一次会话按用户确认的原始 value 请求课表；每个句柄只接受一次有效确认。 */
    fun confirmSelectedSemester(
        handle: ZjuTimetableSessionHandle,
        yearValue: String,
        termValue: String,
        onProgress: (ZjuImportStage) -> Unit,
        onComplete: (ZjuTimetableFetchResult) -> Unit,
        now: Long = System.currentTimeMillis()
    ) {
        // 取走句柄、校验、登记在途必须与 cancelSession 互斥：否则落在“取走”与“登记”之间的取消
        // 会在两张表里都找不到句柄而被静默丢弃。
        val pending: PendingSession
        val year: ZjuSemesterOption
        val term: ZjuSemesterOption
        synchronized(restoreLock) {
            val taken = pendingSessions.remove(handle.id)
            if (taken == null) {
                dispatch(onComplete, ZjuTimetableFetchResult.Failure("学期选项已使用或已过期，请重新登录并读取学期选项。"))
                return
            }
            if (now - taken.createdAt > SESSION_TTL_MS) {
                dispatch(onComplete, ZjuTimetableFetchResult.Failure("学期选项已过期，请重新登录并读取学期选项。"))
                return
            }
            val chosenYear = findOptionByValue(taken.yearOptions, yearValue)
            val chosenTerm = findOptionByValue(taken.termOptions, termValue)
            if (chosenYear == null || chosenTerm == null) {
                restorePendingLocked(handle.id, taken)
                dispatch(onComplete, ZjuTimetableFetchResult.Failure("所选学年或学期不在本次读取的选项中，请重新选择。"))
                return
            }
            if (taken.canceled.get()) {
                dispatch(onComplete, ZjuTimetableFetchResult.Failure(CANCELED_MESSAGE))
                return
            }
            inFlightSessions[handle.id] = taken
            pending = taken
            year = chosenYear
            term = chosenTerm
        }
        try {
            Thread {
                try {
                    val attempt = try {
                        confirmBlocking(pending, year, term) { stage -> dispatch(onProgress, stage) }
                    } catch (_: SocketTimeoutException) {
                        if (pending.canceled.get()) ConfirmAttempt(null, CANCELED_MESSAGE, false)
                        else ConfirmAttempt(null, timeoutMessage(ZjuImportStage.FETCHING_TIMETABLE), true)
                    } catch (_: java.net.UnknownHostException) {
                        if (pending.canceled.get()) ConfirmAttempt(null, CANCELED_MESSAGE, false)
                        else ConfirmAttempt(null, "无法连接浙江大学教务，请检查网络或稍后重试。", true)
                    } catch (error: Throwable) {
                        if (pending.canceled.get()) ConfirmAttempt(null, CANCELED_MESSAGE, false)
                        else ConfirmAttempt(null, failureMessageOf(error), true)
                    }
                    // 收尾只有这一处：摘除在途登记与按需放回句柄都在 finalizeAttempt 的同一临界区内完成。
                    // 不要再在别处（尤其是不持锁的 finally）摘除——那会在“摘除”与“放回”之间留出
                    // 两张表都查不到的窗口，落在其中的取消会被静默丢弃、句柄还会被复活。
                    if (attempt.failureMessage == null) {
                        // 成功：句柄已消耗，不放回。
                        finalizeAttempt(handle.id, pending, restore = false)
                    } else {
                        finalizeAttempt(handle.id, pending, restore = attempt.sessionUsable)
                    }
                    val outcome = when {
                        attempt.result != null -> attempt.result
                        attempt.failureMessage != null ->
                            ZjuTimetableFetchResult.Failure(requireNotNull(attempt.failureMessage))
                        else -> ZjuTimetableFetchResult.Failure("课表导入失败，请稍后重试。")
                    }
                    // 回调本身不允许把异常带回这里，否则收尾就白做了。
                    runCatching { dispatch(onComplete, outcome) }
                } catch (error: Throwable) {
                    // 兜底：上面的 catch 体或收尾本身抛错（例如 OOM）时，仍要摘掉在途登记，
                    // 否则 inFlightSessions 没有别的清扫路径，条目会带着账号与会话驻留到进程结束。
                    // finalizeAttempt 自身持锁且幂等（restore=false 时不碰两张表），重复调用无害。
                    runCatching { finalizeAttempt(handle.id, pending, restore = false) }
                    runCatching { dispatch(onComplete, ZjuTimetableFetchResult.Failure(failureMessageOf(error))) }
                }
            }.apply {
                name = "FocusFlow-ZjuImport"
                isDaemon = true
                start()
            }
        } catch (error: Throwable) {
            // 线程创建等同步异常：同样走统一的收尾，保证恰好一次回调与完整清理。
            finalizeAttempt(handle.id, pending, restore = true)
            dispatch(onComplete, ZjuTimetableFetchResult.Failure(failureMessageOf(error)))
        }
    }

    /** 取消未确认会话或中断已在途的课表请求；已过期的句柄为空操作。 */
    fun cancelSession(handle: ZjuTimetableSessionHandle) {
        // 与 confirmSelectedSemester 的“取走 + 登记在途”互斥，取消才不会在两张表之间被丢弃。
        // 真实传输的 cancel() 会 disconnect（阻塞 I/O），因此只取“标志位 + 表项”后出锁再断开，
        // 避免主线程持全局锁做网络操作。
        val canceled = synchronized(restoreLock) { cancelSessionLocked(handle.id) }
        canceled.forEach { runCatching { it.transport.cancel() } }
    }

    /**
     * 中断尚未产生句柄的第一阶段请求（按调用方自己的传输实例取消，不影响其他实例）。
     * 若该传输已经产生句柄（在途确认或等待确认），这里按同一取消语义把它一并作废——
     * 只 cancel 传输而不置 [PendingSession.canceled] 会让在途请求把“取消”误报成“超时”，
     * 并把句柄放回去，留下无人可达却持有账号与已认证会话的条目。
     */
    fun cancelTransport(transport: ZjuHttpClient) {
        val canceled = synchronized(restoreLock) { cancelTransportLocked(transport) }
        runCatching { transport.cancel() }
        canceled.forEach { runCatching { it.transport.cancel() } }
    }

    /** 持锁取消：按句柄从两张表移除并置取消标志；返回需要 disconnect 的会话。 */
    private fun cancelSessionLocked(id: Long): List<PendingSession> {
        val removed = listOfNotNull(pendingSessions.remove(id), inFlightSessions.remove(id))
        removed.forEach { it.canceled.set(true) }
        return removed
    }

    /** 持锁取消：按传输身份从两张表移除并置取消标志；返回需要 disconnect 的会话。 */
    private fun cancelTransportLocked(transport: ZjuHttpClient): List<PendingSession> {
        val removed = mutableListOf<PendingSession>()
        pendingSessions.entries.removeIf { entry ->
            (entry.value.transport === transport).also { if (it) removed += entry.value }
        }
        inFlightSessions.entries.removeIf { entry ->
            (entry.value.transport === transport).also { if (it) removed += entry.value }
        }
        removed.forEach { it.canceled.set(true) }
        return removed
    }

    /**
     * 一次确认尝试的统一收尾：摘除在途登记，并在请求本身失败（非取消）时把句柄放回待确认。
     * 两件事必须在同一个临界区内完成——否则 cancelSession 可能正好落在“已摘除、未放回”的窗口里，
     * 两张表都查不到句柄，取消被静默丢弃，随后句柄还会被原样放回（留下无人可达的孤儿会话）。
     */
    private fun finalizeAttempt(id: Long, pending: PendingSession, restore: Boolean) {
        synchronized(restoreLock) {
            inFlightSessions.remove(id)
            if (restore) restorePendingLocked(id, pending)
        }
    }

    /** 需要在持有 [restoreLock] 时调用：取消检查与放回必须原子，已持锁时直接调用。 */
    private fun restorePendingLocked(id: Long, pending: PendingSession) {
        if (!pending.canceled.get()) pendingSessions.putIfAbsent(id, pending)
    }

    private fun purgeExpiredSessions(now: Long) {
        val expired = pendingSessions.entries.filter { now - it.value.createdAt > SESSION_TTL_MS }
        expired.forEach { entry ->
            if (pendingSessions.remove(entry.key, entry.value)) entry.value.requestCancel()
        }
    }

    private fun failureMessageOf(error: Throwable): String {
        val detail = error.message?.take(240).orEmpty()
        val label = error.javaClass.simpleName.ifBlank { "Throwable" }
        return if (detail.isBlank()) "教务导入失败（$label），请稍后重试。"
        else "$detail（$label）".take(280)
    }

    /** 默认传输工厂（真实 HttpSession）。ViewModel 需要显式注入工厂时复用它。 */
    internal fun defaultTransportFactory(): ZjuHttpClient = HttpSession()

    /**
     * 把回调投递到主线程；调用方线程不是主线程且投递失败时直接回调，
     * 保证任何路径都恰好一次 [onComplete]。
     */
    private fun <T> dispatch(callback: (T) -> Unit, value: T) {
        // 投递失败（例如主 looper 正在退出）时直接回调；两个分支互斥，因此 [onComplete]
        // 在任何线程、任何路径下都恰好一次，调用方不会停在 running。
        val posted = runCatching { mainHandler()?.post { callback(value) } == true }.getOrDefault(false)
        if (!posted) callback(value)
    }

    /** 主线程 Handler；取不到时返回 null，由 [dispatch] 走直接回调分支。 */
    private fun mainHandler(): Handler? = runCatching { Handler(Looper.getMainLooper()) }.getOrNull()

    private const val CANCELED_MESSAGE = "导入已取消。"

    /** 登录前确认未被取消，避免取消与线程启动之间的竞态让已取消的会话继续发出请求。 */
    private fun beginSessionBlocking(
        username: String,
        password: CharArray,
        transport: ZjuHttpClient,
        now: Long,
        progress: (ZjuImportStage) -> Unit
    ): ZjuSemesterOptionsResult {
        // 取消与登录线程启动之间存在竞态：这里再确认一次，避免已取消的句柄仍发出新请求。
        if (transport.isCanceled()) return ZjuSemesterOptionsResult.Failure(CANCELED_MESSAGE)
        val result = beginSessionRequest(username, password, transport, now, progress)
        if (!transport.isCanceled()) return result
        // 取消发生时请求可能已登记句柄；清掉这条无人可达的会话，避免它占着账号信息等到 TTL。
        pendingSessions.entries.removeIf { it.value.transport === transport }
        return ZjuSemesterOptionsResult.Failure(CANCELED_MESSAGE)
    }

    /** 真正的登录与选项读取；入口是 [beginSessionBlocking]，由它负责取消态检查。 */
    private fun beginSessionRequest(
        username: String,
        password: CharArray,
        transport: ZjuHttpClient,
        now: Long,
        progress: (ZjuImportStage) -> Unit
    ): ZjuSemesterOptionsResult {
        if (username.isBlank() || password.isEmpty()) {
            return ZjuSemesterOptionsResult.Failure("请填写统一身份认证账号和密码。")
        }
        val loginUrl = "$CAS_BASE/cas/login?service=${encode(SERVICE_URL)}"

        progress(ZjuImportStage.CONNECTING)
        val firstPage = transport.get(loginUrl)
        if (!firstPage.url.host.equals("zjuam.zju.edu.cn", ignoreCase = true) || firstPage.body.isBlank()) {
            return ZjuSemesterOptionsResult.Failure("统一身份认证入口返回异常，请稍后重试。")
        }

        progress(ZjuImportStage.ENCRYPTING)
        val keyResponse = transport.get("$CAS_BASE/cas/v2/getPubKey")
        val key = runCatching { JSONObject(keyResponse.body) }.getOrNull()
            ?: return ZjuSemesterOptionsResult.Failure("无法读取统一身份认证公钥，请稍后重试。")
        val modulus = key.optString("modulus")
        val exponent = key.optString("exponent")
        if (modulus.isBlank() || exponent.isBlank()) {
            return ZjuSemesterOptionsResult.Failure("统一身份认证公钥格式已变化，已停止登录。")
        }
        val encryptedPassword = rsaEncrypt(password, modulus, exponent)

        // execution 为一次性参数；拿到公钥后重新获取，避免慢请求使旧表单失效。
        val freshPage = transport.get(loginUrl)
        val fields = parseCasForm(freshPage.body)
        if (fields.isEmpty()) {
            return ZjuSemesterOptionsResult.Failure("统一身份认证表单格式已变化，已停止登录。")
        }
        val form = buildCasForm(fields, username, encryptedPassword)

        progress(ZjuImportStage.AUTHENTICATING)
        val login = transport.post(
            loginUrl,
            encodeForm(form),
            mapOf(
                "Referer" to loginUrl,
                "Origin" to CAS_BASE,
                "Content-Type" to "application/x-www-form-urlencoded",
                "Sec-Fetch-Dest" to "document",
                "Sec-Fetch-Mode" to "navigate",
                "Sec-Fetch-Site" to "same-origin",
                "Sec-Fetch-User" to "?1",
                "Upgrade-Insecure-Requests" to "1"
            ),
            readTimeoutMs = 35_000,
            skipResponseBodyAtHost = "zdbk.zju.edu.cn",
            onRedirect = { target ->
                if (target.host.equals("zdbk.zju.edu.cn", ignoreCase = true)) {
                    progress(ZjuImportStage.ESTABLISHING_SESSION)
                }
            }
        )
        val finalHost = login.url.host.orEmpty()
        if (!finalHost.equals("zdbk.zju.edu.cn", ignoreCase = true)) {
            val wrongCredentials = listOf(
                "authenticationFailure", "登录失败", "密码不正确", "密码错误", "账号不存在", "errormsg"
            ).any { login.body.contains(it, ignoreCase = true) }
            return ZjuSemesterOptionsResult.Failure(
                if (wrongCredentials) "统一身份认证账号或密码错误，请检查后重试。"
                else "统一身份认证未完成；若账号需要验证码或解锁，请先在浙大认证网页完成后再试。"
            )
        }

        progress(ZjuImportStage.LOADING_SEMESTER)
        val indexUrl = "$ZDBK_BASE/jwglxt/kbcx/xskbcx_cxXskbcxIndex.html?gnmkdm=N253508&layout=default&su=${encode(username)}"
        val index = transport.get(indexUrl, totalTimeoutMs = 40_000)
        if (!index.url.host.equals("zdbk.zju.edu.cn", ignoreCase = true)) {
            return ZjuSemesterOptionsResult.Failure("教务会话已失效，请重新登录。")
        }
        if (index.code !in 200..299) {
            return ZjuSemesterOptionsResult.Failure("读取学期选项失败（HTTP ${index.code}），请稍后重试。")
        }
        val yearOptions = parseSemesterOptions(index.body, "xnm")
        val termOptions = parseSemesterOptions(index.body, "xqm")
        if (yearOptions.isEmpty() || termOptions.isEmpty()) {
            return ZjuSemesterOptionsResult.Failure("无法读取学年或学期选项，请重新登录后重试。")
        }
        if (hasDuplicateValues(yearOptions) || hasDuplicateValues(termOptions)) {
            return ZjuSemesterOptionsResult.Failure("学年或学期选项存在重复，无法安全选择，请稍后重试。")
        }
        val handle = ZjuTimetableSessionHandle(sessionIds.incrementAndGet())
        pendingSessions[handle.id] = PendingSession(
            transport = transport,
            username = username,
            indexUrl = indexUrl,
            yearOptions = yearOptions,
            termOptions = termOptions,
            createdAt = now
        )
        return ZjuSemesterOptionsResult.Success(handle, yearOptions, termOptions)
    }

    /**
     * 第二阶段的核心：失败时区分“这次请求失败（可原地重试）”与“教务会话已不可用（必须重新登录）”，
     * 并把解析层的可执行文案原样带回 UI（登录失效 / 验证码 / 响应格式变化都不能被替换成一句“请重试”）。
     * 抛异常表示可归类的传输失败（超时/网络），由调用方决定是否放回句柄。
     */
    private fun confirmBlocking(
        pending: PendingSession,
        year: ZjuSemesterOption,
        term: ZjuSemesterOption,
        progress: (ZjuImportStage) -> Unit
    ): ConfirmAttempt {
        // 取消与确认线程启动之间存在竞态：这里再确认一次，避免已取消的句柄仍发出课表请求。
        if (pending.canceled.get()) return ConfirmAttempt(null, CANCELED_MESSAGE, false)
        progress(ZjuImportStage.FETCHING_TIMETABLE)
        val timetableUrl = "$ZDBK_BASE/jwglxt/kbcx/xskbcx_cxXsKb.html?gnmkdm=N253508&su=${encode(pending.username)}"
        if (pending.canceled.get()) return ConfirmAttempt(null, CANCELED_MESSAGE, false)
        val payload = pending.transport.post(
            timetableUrl,
            encodeForm(timetableRequestForm(year, term)),
            mapOf(
                "Referer" to pending.indexUrl,
                "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                "X-Requested-With" to "XMLHttpRequest"
            )
        ).body
        if (pending.canceled.get()) return ConfirmAttempt(null, CANCELED_MESSAGE, false)

        progress(ZjuImportStage.PARSING)
        return when (val parsed = ZjuTimetableParser.parse(payload, year.value, term.value)) {
            is ZjuTimetableParseResult.Success -> {
                progress(ZjuImportStage.DONE)
                ConfirmAttempt(
                    ZjuTimetableFetchResult.Success(payload, year.text, termDisplayOf(term), year.value, term.value),
                    null,
                    true
                )
            }
            is ZjuTimetableParseResult.Failure -> ConfirmAttempt(
                result = null,
                // 解析层的可执行文案（登录失效 / 验证码 / 格式变化）原样带回 UI，不替换成“请重试”。
                failureMessage = parsed.message,
                // 句柄保持有效：再点一次会重新请求教务，若会话真的失效会再次得到同一条可执行文案，
                // 用户据此去重新登录；句柄本身由 10 分钟 TTL 兜底，不会永久滞留。
                sessionUsable = true
            )
        }
    }

    internal fun parseCasForm(html: String): List<ZjuCasFormField> = inputTag.findAll(html).mapNotNull { match ->
        val attrs = match.groupValues[1]
        val name = attribute(attrs, "name") ?: return@mapNotNull null
        val type = (attribute(attrs, "type") ?: "text").lowercase()
        if (type in setOf("submit", "button", "image")) return@mapNotNull null
        ZjuCasFormField(name, decodeHtml(attribute(attrs, "value").orEmpty()), type)
    }.toList()

    internal fun buildCasForm(
        fields: List<ZjuCasFormField>,
        username: String,
        encryptedPassword: String
    ): List<Pair<String, String>> {
        val output = mutableListOf<Pair<String, String>>()
        var hasUsername = false
        var hasPassword = false
        fields.forEach { field ->
            when {
                field.name.equals("username", ignoreCase = true) -> {
                    output += field.name to username
                    hasUsername = true
                }
                field.type == "password" || Regex("pwd|pass|credential|encrypt", RegexOption.IGNORE_CASE).containsMatchIn(field.name) -> {
                    output += field.name to encryptedPassword
                    hasPassword = true
                }
                field.type == "checkbox" -> {
                    if (field.name.contains("remember", ignoreCase = true)) {
                        output += field.name to field.value.ifBlank { "true" }
                    }
                }
                else -> output += field.name to field.value
            }
        }
        if (!hasUsername) output += "username" to username
        if (!hasPassword) output += "password" to encryptedPassword
        if (output.none { it.first == "_eventId" }) output += "_eventId" to "submit"
        return output
    }

    /** 解析下拉中的全部有效选项（value 与解码、去空白后仍非空的项）。 */
    internal fun parseSemesterOptions(html: String, id: String): List<ZjuSemesterOption> {
        val select = Regex("<select\\b([^>]*)>([\\s\\S]*?)</select>", RegexOption.IGNORE_CASE)
            .findAll(html)
            .firstOrNull { match ->
                attribute(match.groupValues[1], "id") == id || attribute(match.groupValues[1], "name") == id
            } ?: return emptyList()
        return optionTag.findAll(select.groupValues[2]).mapNotNull { match ->
            val value = decodeHtml(attribute(match.groupValues[1], "value").orEmpty()).trim()
            if (value.isBlank()) return@mapNotNull null
            ZjuSemesterOption(
                value = value,
                text = decodeHtml(match.groupValues[2].replace(htmlTag, "")).trim().ifBlank { value },
                selected = Regex("\\bselected\\b", RegexOption.IGNORE_CASE).containsMatchIn(match.groupValues[1])
            )
        }.toList()
    }

    /**
     * 自动路径选择：唯一 selected 优先；无 selected 时仅接受唯一候选；
     * 多选、重复 value 或空候选均返回 null（明确失败，不猜第一项）。
     */
    internal fun selectAutoOption(options: List<ZjuSemesterOption>): ZjuSemesterOption? {
        if (options.isEmpty() || hasDuplicateValues(options)) return null
        val selected = options.filter { it.selected }
        return when (selected.size) {
            1 -> selected.single()
            0 -> options.singleOrNull()
            else -> null
        }
    }

    /** 按原始 value 定位选项；value 空白、不存在或选项集含重复 value 时返回 null。 */
    internal fun findOptionByValue(options: List<ZjuSemesterOption>, value: String?): ZjuSemesterOption? {
        val wanted = value.orEmpty().trim()
        if (wanted.isBlank() || hasDuplicateValues(options)) return null
        return options.firstOrNull { it.value == wanted }
    }

    /** 请求展示值：value 中 `|` 之后的部分，否则 option 文本（保持既有规则）。 */
    internal fun termDisplayOf(term: ZjuSemesterOption): String =
        term.value.substringAfter('|', term.text).ifBlank { term.text }

    /** 课表查询表单：学年/学期必须逐字使用所选 option 的原始 value。 */
    internal fun timetableRequestForm(
        year: ZjuSemesterOption,
        term: ZjuSemesterOption
    ): List<Pair<String, String>> = listOf(
        "xnm" to year.value,
        "xqm" to term.value,
        "xqmmc" to termDisplayOf(term),
        "xxqf" to "0",
        "xsfs" to "0",
        "captcha_value" to ""
    )

    private fun hasDuplicateValues(options: List<ZjuSemesterOption>): Boolean =
        options.groupingBy { it.value }.eachCount().any { it.value > 1 }

    internal fun parseSemesterOption(html: String, id: String): ZjuSemesterOption? =
        selectAutoOption(parseSemesterOptions(html, id))

    private fun rsaEncrypt(password: CharArray, modulusHex: String, exponentHex: String): String {
        val encoded = Charsets.UTF_8.encode(CharBuffer.wrap(password))
        val bytes = ByteArray(encoded.remaining())
        encoded.get(bytes)
        return try {
            BigInteger(1, bytes)
                .modPow(BigInteger(exponentHex, 16), BigInteger(modulusHex, 16))
                .toString(16)
                .padStart(modulusHex.length, '0')
        } finally {
            bytes.fill(0)
        }
    }

    private fun attribute(attrs: String, name: String): String? {
        val escaped = Regex.escape(name)
        val match = Regex(
            "\\b$escaped\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>/\"']+))",
            RegexOption.IGNORE_CASE
        ).find(attrs) ?: return null
        return match.groupValues.drop(1).firstOrNull { it.isNotEmpty() }.orEmpty()
    }

    internal fun timeoutMessage(stage: ZjuImportStage): String = when (stage) {
        ZjuImportStage.AUTHENTICATING ->
            "验证账号阶段响应超时。请稍后重试；FocusFlow 不会自动重复提交密码。"
        ZjuImportStage.ESTABLISHING_SESSION ->
            "账号已验证，但建立教务会话超时。请切换校园网或移动数据后重试。"
        ZjuImportStage.LOADING_SEMESTER ->
            "读取学期选项超时，请稍后重试。"
        ZjuImportStage.FETCHING_TIMETABLE ->
            "下载课表数据超时，请稍后重试。"
        else ->
            "连接浙江大学统一身份认证超时，请检查网络后重试。"
    }

    private fun encodeForm(values: List<Pair<String, String>>): String =
        values.joinToString("&") { (key, value) -> "${encode(key)}=${encode(value)}" }

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    private fun decodeHtml(value: String): String = value
        .replace("&amp;", "&", ignoreCase = true)
        .replace("&quot;", "\"", ignoreCase = true)
        .replace("&#39;", "'", ignoreCase = true)
        .replace("&lt;", "<", ignoreCase = true)
        .replace("&gt;", ">", ignoreCase = true)
        .replace("&nbsp;", " ", ignoreCase = true)

    /** 真实 HTTP 传输。internal 以便测试直接验证取消会中断在途请求。 */
    internal class HttpSession : ZjuHttpClient {
        private val cookies = CookieManager(null, CookiePolicy.ACCEPT_ALL)
        private val canceled = AtomicBoolean(false)
        private val activeConnection = AtomicReference<HttpURLConnection?>()

        override fun cancel() {
            canceled.set(true)
            activeConnection.getAndSet(null)?.disconnect()
        }

        override fun isCanceled(): Boolean = canceled.get()

        override fun get(url: String, totalTimeoutMs: Long?): ZjuHttpResponse =
            request("GET", url, null, emptyMap(), totalTimeoutMs = totalTimeoutMs)

        override fun post(
            url: String,
            body: String,
            headers: Map<String, String>,
            readTimeoutMs: Int,
            skipResponseBodyAtHost: String?,
            onRedirect: (URI) -> Unit
        ): ZjuHttpResponse = request(
            "POST",
            url,
            body,
            headers,
            readTimeoutMs,
            skipResponseBodyAtHost,
            onRedirect
        )

        private fun request(
            initialMethod: String,
            initialUrl: String,
            initialBody: String?,
            headers: Map<String, String>,
            readTimeoutMs: Int = 18_000,
            skipResponseBodyAtHost: String? = null,
            onRedirect: (URI) -> Unit = {},
            totalTimeoutMs: Long? = null
        ): ZjuHttpResponse {
            var method = initialMethod
            var uri = URI(initialUrl)
            var body = initialBody
            val deadlineReached = AtomicBoolean(false)
            val timer = totalTimeoutMs?.let { limit ->
                Timer("FocusFlow-ZjuRequestDeadline", true).apply {
                    schedule(object : java.util.TimerTask() {
                        override fun run() {
                            deadlineReached.set(true)
                            activeConnection.getAndSet(null)?.disconnect()
                        }
                    }, limit)
                }
            }
            try {
            repeat(10) {
                if (canceled.get()) throw SocketTimeoutException("教务导入已取消")
                if (deadlineReached.get()) throw SocketTimeoutException("教务请求已超过总时限")
                requireOfficialHttps(uri)
                val connection = (uri.toURL().openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 15_000
                    readTimeout = readTimeoutMs
                    useCaches = false
                    requestMethod = method
                    setRequestProperty("User-Agent", USER_AGENT)
                    setRequestProperty("Accept", "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8")
                    setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
                    headers.forEach { (key, value) -> setRequestProperty(key, value) }
                    cookies.get(uri, emptyMap()).forEach { (key, values) ->
                        setRequestProperty(key, values.joinToString("; "))
                    }
                    if (body != null && method == "POST") {
                        doOutput = true
                        outputStream.use { it.write(body!!.toByteArray(Charsets.UTF_8)) }
                    }
                }
                activeConnection.set(connection)
                if (canceled.get()) {
                    connection.disconnect()
                    activeConnection.compareAndSet(connection, null)
                    throw SocketTimeoutException("教务导入已取消")
                }
                if (deadlineReached.get()) throw SocketTimeoutException("教务请求已超过总时限")
                val code = connection.responseCode
                val responseHeaders = connection.headerFields.entries
                    .filter { it.key != null }
                    .associate { it.key!! to it.value }
                cookies.put(uri, responseHeaders)
                val location = connection.getHeaderField("Location")
                if (code in setOf(301, 302, 303, 307, 308) && !location.isNullOrBlank()) {
                    uri = uri.resolve(location)
                    onRedirect(uri)
                    if (code in setOf(301, 302, 303)) {
                        method = "GET"
                        body = null
                    }
                    connection.disconnect()
                    activeConnection.compareAndSet(connection, null)
                    return@repeat
                }
                if (skipResponseBodyAtHost != null &&
                    uri.host.equals(skipResponseBodyAtHost, ignoreCase = true)
                ) {
                    connection.disconnect()
                    activeConnection.compareAndSet(connection, null)
                    return ZjuHttpResponse(code, uri, "")
                }
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val responseBody = stream?.let { input ->
                    BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
                        val result = StringBuilder()
                        val buffer = CharArray(8192)
                        while (result.length < MAX_RESPONSE_CHARS) {
                            if (canceled.get()) throw SocketTimeoutException("教务导入已取消")
                            val count = reader.read(buffer, 0, minOf(buffer.size, MAX_RESPONSE_CHARS - result.length))
                            if (count < 0) break
                            result.append(buffer, 0, count)
                        }
                        result.toString()
                    }
                }.orEmpty()
                connection.disconnect()
                activeConnection.compareAndSet(connection, null)
                if (canceled.get()) throw SocketTimeoutException("教务导入已取消")
                if (deadlineReached.get()) throw SocketTimeoutException("教务请求已超过总时限")
                return ZjuHttpResponse(code, uri, responseBody)
            }
            throw IllegalStateException("浙江大学认证跳转次数过多，已停止登录。")
            } catch (error: java.io.IOException) {
                if (canceled.get()) throw SocketTimeoutException("教务导入已取消")
                if (deadlineReached.get()) throw SocketTimeoutException("教务请求已超过总时限")
                throw error
            } finally {
                timer?.cancel()
                activeConnection.getAndSet(null)?.disconnect()
            }
        }

        private fun requireOfficialHttps(uri: URI) {
            val host = uri.host?.lowercase().orEmpty()
            if (uri.scheme != "https" || (host != "zju.edu.cn" && !host.endsWith(".zju.edu.cn"))) {
                throw SecurityException("认证跳转离开浙江大学官方域名，已停止登录。")
            }
        }
    }
}
