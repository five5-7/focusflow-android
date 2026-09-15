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
import java.net.URI
import java.net.URLEncoder
import java.nio.CharBuffer

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
        val semester: String
    ) : ZjuTimetableFetchResult

    data class Failure(val message: String) : ZjuTimetableFetchResult
}

internal data class ZjuCasFormField(val name: String, val value: String, val type: String)
internal data class ZjuSemesterOption(val value: String, val text: String, val selected: Boolean)

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

    fun fetch(
        username: String,
        password: CharArray,
        onProgress: (ZjuImportStage) -> Unit,
        onComplete: (ZjuTimetableFetchResult) -> Unit
    ) {
        val main = Handler(Looper.getMainLooper())
        Thread {
            var currentStage = ZjuImportStage.CONNECTING
            val result = try {
                fetchBlocking(username.trim(), password) { stage ->
                    currentStage = stage
                    main.post { onProgress(stage) }
                }
            } catch (_: java.net.SocketTimeoutException) {
                ZjuTimetableFetchResult.Failure(timeoutMessage(currentStage))
            } catch (_: java.net.UnknownHostException) {
                ZjuTimetableFetchResult.Failure("无法连接浙江大学教务，请检查网络或稍后重试。")
            } catch (error: Exception) {
                ZjuTimetableFetchResult.Failure(error.message?.take(240) ?: "教务导入失败，请稍后重试。")
            } finally {
                password.fill('\u0000')
            }
            main.post { onComplete(result) }
        }.apply {
            name = "FocusFlow-ZjuImport"
            isDaemon = true
            start()
        }
    }

    private fun fetchBlocking(
        username: String,
        password: CharArray,
        progress: (ZjuImportStage) -> Unit
    ): ZjuTimetableFetchResult {
        if (username.isBlank() || password.isEmpty()) {
            return ZjuTimetableFetchResult.Failure("请填写统一身份认证账号和密码。")
        }
        val session = HttpSession()
        val loginUrl = "$CAS_BASE/cas/login?service=${encode(SERVICE_URL)}"

        progress(ZjuImportStage.CONNECTING)
        val firstPage = session.get(loginUrl)
        if (!firstPage.url.host.equals("zjuam.zju.edu.cn", ignoreCase = true) || firstPage.body.isBlank()) {
            return ZjuTimetableFetchResult.Failure("统一身份认证入口返回异常，请稍后重试。")
        }

        progress(ZjuImportStage.ENCRYPTING)
        val keyResponse = session.get("$CAS_BASE/cas/v2/getPubKey")
        val key = runCatching { JSONObject(keyResponse.body) }.getOrNull()
            ?: return ZjuTimetableFetchResult.Failure("无法读取统一身份认证公钥，请稍后重试。")
        val modulus = key.optString("modulus")
        val exponent = key.optString("exponent")
        if (modulus.isBlank() || exponent.isBlank()) {
            return ZjuTimetableFetchResult.Failure("统一身份认证公钥格式已变化，已停止登录。")
        }
        val encryptedPassword = rsaEncrypt(password, modulus, exponent)

        // execution 为一次性参数；拿到公钥后重新获取，避免慢请求使旧表单失效。
        val freshPage = session.get(loginUrl)
        val fields = parseCasForm(freshPage.body)
        if (fields.isEmpty()) {
            return ZjuTimetableFetchResult.Failure("统一身份认证表单格式已变化，已停止登录。")
        }
        val form = buildCasForm(fields, username, encryptedPassword)

        progress(ZjuImportStage.AUTHENTICATING)
        val login = session.post(
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
            return ZjuTimetableFetchResult.Failure(
                if (wrongCredentials) "统一身份认证账号或密码错误，请检查后重试。"
                else "统一身份认证未完成；若账号需要验证码或解锁，请先在浙大认证网页完成后再试。"
            )
        }

        progress(ZjuImportStage.LOADING_SEMESTER)
        val indexUrl = "$ZDBK_BASE/jwglxt/kbcx/xskbcx_cxXskbcxIndex.html?gnmkdm=N253508&layout=default&su=${encode(username)}"
        val index = session.get(indexUrl)
        if (!index.url.host.equals("zdbk.zju.edu.cn", ignoreCase = true)) {
            return ZjuTimetableFetchResult.Failure("教务会话已失效，请重新导入。")
        }
        val year = parseSemesterOption(index.body, "xnm")
            ?: return ZjuTimetableFetchResult.Failure("无法读取当前学年，教务页面格式可能已变化。")
        val term = parseSemesterOption(index.body, "xqm")
            ?: return ZjuTimetableFetchResult.Failure("无法读取当前学期，教务页面格式可能已变化。")
        val termDisplay = term.value.substringAfter('|', term.text).ifBlank { term.text }

        progress(ZjuImportStage.FETCHING_TIMETABLE)
        val timetableUrl = "$ZDBK_BASE/jwglxt/kbcx/xskbcx_cxXsKb.html?gnmkdm=N253508&su=${encode(username)}"
        val payload = session.post(
            timetableUrl,
            encodeForm(
                listOf(
                    "xnm" to year.value,
                    "xqm" to term.value,
                    "xqmmc" to termDisplay,
                    "xxqf" to "0",
                    "xsfs" to "0",
                    "captcha_value" to ""
                )
            ),
            mapOf(
                "Referer" to indexUrl,
                "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                "X-Requested-With" to "XMLHttpRequest"
            )
        ).body

        progress(ZjuImportStage.PARSING)
        return when (val parsed = ZjuTimetableParser.parse(payload)) {
            is ZjuTimetableParseResult.Success -> {
                progress(ZjuImportStage.DONE)
                ZjuTimetableFetchResult.Success(payload, year.text, termDisplay)
            }
            is ZjuTimetableParseResult.Failure -> ZjuTimetableFetchResult.Failure(parsed.message)
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

    internal fun parseSemesterOption(html: String, id: String): ZjuSemesterOption? {
        val select = Regex("<select\\b([^>]*)>([\\s\\S]*?)</select>", RegexOption.IGNORE_CASE)
            .findAll(html)
            .firstOrNull { match ->
                attribute(match.groupValues[1], "id") == id || attribute(match.groupValues[1], "name") == id
            } ?: return null
        val options = optionTag.findAll(select.groupValues[2]).mapNotNull { match ->
            val value = attribute(match.groupValues[1], "value").orEmpty()
            if (value.isBlank()) return@mapNotNull null
            ZjuSemesterOption(
                value = decodeHtml(value),
                text = decodeHtml(match.groupValues[2].replace(htmlTag, "")).trim(),
                selected = Regex("\\bselected\\b", RegexOption.IGNORE_CASE).containsMatchIn(match.groupValues[1])
            )
        }.toList()
        return options.firstOrNull { it.selected } ?: options.firstOrNull()
    }

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
            "读取学年与学期超时，请稍后重试。"
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

    private data class HttpResponse(val code: Int, val url: URI, val body: String)

    private class HttpSession {
        private val cookies = CookieManager(null, CookiePolicy.ACCEPT_ALL)

        fun get(url: String): HttpResponse = request("GET", url, null, emptyMap())

        fun post(
            url: String,
            body: String,
            headers: Map<String, String>,
            readTimeoutMs: Int = 18_000,
            skipResponseBodyAtHost: String? = null,
            onRedirect: (URI) -> Unit = {}
        ): HttpResponse = request(
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
            onRedirect: (URI) -> Unit = {}
        ): HttpResponse {
            var method = initialMethod
            var uri = URI(initialUrl)
            var body = initialBody
            repeat(10) {
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
                    return@repeat
                }
                if (skipResponseBodyAtHost != null &&
                    uri.host.equals(skipResponseBodyAtHost, ignoreCase = true)
                ) {
                    connection.disconnect()
                    return HttpResponse(code, uri, "")
                }
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val responseBody = stream?.let { input ->
                    BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
                        val result = StringBuilder()
                        val buffer = CharArray(8192)
                        while (result.length < MAX_RESPONSE_CHARS) {
                            val count = reader.read(buffer, 0, minOf(buffer.size, MAX_RESPONSE_CHARS - result.length))
                            if (count < 0) break
                            result.append(buffer, 0, count)
                        }
                        result.toString()
                    }
                }.orEmpty()
                connection.disconnect()
                return HttpResponse(code, uri, responseBody)
            }
            throw IllegalStateException("浙江大学认证跳转次数过多，已停止登录。")
        }

        private fun requireOfficialHttps(uri: URI) {
            val host = uri.host?.lowercase().orEmpty()
            if (uri.scheme != "https" || (host != "zju.edu.cn" && !host.endsWith(".zju.edu.cn"))) {
                throw SecurityException("认证跳转离开浙江大学官方域名，已停止登录。")
            }
        }
    }
}
