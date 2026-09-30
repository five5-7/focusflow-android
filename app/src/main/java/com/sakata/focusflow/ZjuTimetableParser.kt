package com.sakata.focusflow

import org.json.JSONObject

/**
 * 解析失败的**可分类原因**。
 *
 * 存在的理由：调用方需要按"这次失败能不能原地重试"分流，而**不能靠匹配中文文案**——
 * 文案会改，改了就静默失效。会话已失效/需要验证码属于不可恢复，必须作废句柄并让用户重新登录；
 * 其余（空响应、过大、格式变化、缺 kbList、学年/学期不一致）属于本次请求失败，可原地重试。
 */
internal enum class ZjuParseFailureReason {
    /** 返回登录页：教务会话已失效，重试不可能成功。 */
    SESSION_LOST,

    /** 教务要求验证码：需要用户去官方页面处理，重试不可能成功。 */
    CAPTCHA_REQUIRED,

    /** 空响应体。 */
    EMPTY_PAYLOAD,
    /** 响应过大，已主动停止。 */
    TOO_LARGE,

    /** 响应不是可解析的 JSON（可能页面已变化）。 */
    MALFORMED,

    /** JSON 里没有 kbList。 */
    NO_KB_LIST,

    /** 响应回显的学年/学期与请求不一致：不能把别的学期课表贴成所选学期。 */
    SEMESTER_MISMATCH
}

/**
 * 该失败是否"只是这一次请求失败"（句柄仍可原地重试）。
 *
 * 用**白名单**而不是黑名单：将来新增一种失败原因时，默认应落在"不可重试"（fail-safe）一侧——
 * 误判成可重试会让 UI 引导用户去做注定失败的重复请求，误判成不可重试只是多让用户登录一次。
 */
internal val ZjuParseFailureReason.isRecoverable: Boolean
    get() = when (this) {
        ZjuParseFailureReason.SESSION_LOST -> false
        ZjuParseFailureReason.CAPTCHA_REQUIRED -> false
        // 空响应体通常意味着会话已经走到尽头（而非某次请求抖动）：按不可重试处理，让用户重新登录。
        ZjuParseFailureReason.EMPTY_PAYLOAD -> false
        ZjuParseFailureReason.TOO_LARGE -> true
        ZjuParseFailureReason.MALFORMED -> true
        ZjuParseFailureReason.NO_KB_LIST -> true
        // 会话还在，只是教务按别的学期返回了：换一个学期可以重试。
        ZjuParseFailureReason.SEMESTER_MISMATCH -> true
    }

internal sealed interface ZjuTimetableParseResult {
    data class Success(
        val batch: CourseImportBatch,
        val invalidRows: Int,
        val nonWeeklyRows: Int,
        val candidates: List<ZjuTimetableCandidateRow> = emptyList()
    ) : ZjuTimetableParseResult

    data class Failure(
        val message: String,
        val reason: ZjuParseFailureReason
    ) : ZjuTimetableParseResult
}

/**
 * 只读、仅内存的逐行候选元数据，与原始 `kbList` 行序号一一绑定。
 *
 * `externalSelectionKeyCandidate` 只是响应中出现的候选值（真实样本里的 `xkkh`），
 * 尚未证明等同教学班身份，不得用于自动归并、落库或界面展示。
 */
internal data class ZjuTimetableCandidateRow(
    val sourceRowIndex: Int,
    val schoolYearCode: String,
    val termCode: String,
    val externalSelectionKeyCandidate: String,
    val weekday: Int?,
    val startPeriod: Int?,
    val endPeriod: Int?,
    /**
     * 课程标题原文（仅用于候选分组的"同号异标题/缺标题"核对，不参与身份判定）。
     * **必须显式传入**：漏传会削弱核对（空标题在预览里是待核对项，而非"一致"），
     * 因此不给默认值，让新调用点在编译期就被要求作决定。
     */
    val title: String
) {
    val hasRequestCodes: Boolean get() = schoolYearCode.isNotBlank() && termCode.isNotBlank()
}

/**
 * 浙江大学本科教学管理系统“学生课表查询”的 JSON 响应解析器。
 *
 * 网络与认证留在官方 WebView 中；这里只接受课表响应，不接触账号、密码或 Cookie。
 */
internal object ZjuTimetableParser {
    private const val MAX_PAYLOAD_CHARS = 750_000
    private val breakTag = Regex("(?i)<br\\s*/?>")
    private val htmlTag = Regex("<[^>]+>")
    private val integer = Regex("\\d+")

    fun parse(
        payload: String,
        schoolYearCode: String? = null,
        termCode: String? = null,
        /** 请求表单里 `xqmmc` 实际送出的学期显示文字；用于比对响应回显（与请求保持同一来源）。 */
        termDisplay: String? = null,
        /** 响应是否来自"已登出"域名（由传输层判定）：只有这种情况才把 HTML 体当成会话失效。 */
        loggedOut: Boolean = false
    ): ZjuTimetableParseResult {
        // review7 阻断：`loggedOut` 是传输层给出的**已知登出**信号（最终跳转域名已证明不是课表页），
        // 必须优先于一切正文判断——空正文与合法 JSON 都不例外，否则登出会被正文格式推翻。
        if (loggedOut) {
            return ZjuTimetableParseResult.Failure(
                "教务登录已失效，请重新完成统一身份认证。",
                ZjuParseFailureReason.SESSION_LOST
            )
        }
        if (payload.isBlank()) {
            return ZjuTimetableParseResult.Failure(
                "教务系统没有返回课表数据，请重新登录后再试。",
                ZjuParseFailureReason.EMPTY_PAYLOAD
            )
        }
        if (payload.length > MAX_PAYLOAD_CHARS) {
            return ZjuTimetableParseResult.Failure(
                "教务课表响应过大，已停止导入。",
                ZjuParseFailureReason.TOO_LARGE
            )
        }
        val trimmed = payload.trim()
        val looksLikeHtml = trimmed.startsWith("<!doctype", ignoreCase = true) || trimmed.startsWith("<html", ignoreCase = true)
        // 只有"明确是统一身份认证登录页"的正文才判会话失效；
        // 其它 HTML（5xx／维护页／网关页）只是这次请求失败，句柄应保留以便重试。
        if (looksLikeHtml && looksLikeLoginPage(trimmed)) {
            return ZjuTimetableParseResult.Failure(
                "教务登录已失效，请重新完成统一身份认证。",
                ZjuParseFailureReason.SESSION_LOST
            )
        }

        val root = runCatching { JSONObject(trimmed) }.getOrElse {
            return ZjuTimetableParseResult.Failure(
                "无法解析教务课表响应，请确认当前页面是“学生课表查询”。",
                ZjuParseFailureReason.MALFORMED
            )
        }
        if (root.optBoolean("captcha_error", false) || root.optString("captcha_error").equals("true", ignoreCase = true)) {
            return ZjuTimetableParseResult.Failure(
                "教务系统要求完成验证码，请在官方页面验证后重试。",
                ZjuParseFailureReason.CAPTCHA_REQUIRED
            )
        }
        val rows = root.optJSONArray("kbList")
            ?: return ZjuTimetableParseResult.Failure(
                "教务响应中没有 kbList，当前页面格式可能已变化。",
                ZjuParseFailureReason.NO_KB_LIST
            )

        val requestYearCode = schoolYearCode.orEmpty().trim()
        val requestTermCode = termCode.orEmpty().trim()
        // 请求送出去的是**用户所选**的学年/学期；如果响应回显的是另一组值，说明教务没按请求返回
        // （例如不支持该组合时静默给当前学期）。此时必须失败关闭：把别的学期课表贴成所选学期，
        // 比直接报错危险得多。
        mismatchReason(root, requestYearCode, requestTermCode, termDisplay.orEmpty())?.let { return it }

        val courses = mutableListOf<Course>()
        val places = mutableListOf<String>()
        val candidates = mutableListOf<ZjuTimetableCandidateRow>()
        var invalidRows = 0
        var nonWeeklyRows = 0
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index)
            if (row == null) {
                invalidRows += 1
                continue
            }
            val parts = courseBlockParts(row.firstText("kcb"))
            val weekday = row.firstText("xqj").firstInteger()
            val sectionNumbers = integer.findAll(row.firstText("jcs")).map { it.value.toInt() }.toList()
            val start = row.firstText("djj").firstInteger() ?: sectionNumbers.firstOrNull()
            val count = row.firstText("skcd").firstInteger()
            val end = when {
                start == null -> null
                count != null && count > 0 -> start + count - 1
                sectionNumbers.size >= 2 -> sectionNumbers.last()
                else -> start
            }
            val title = row.firstText("kcmc", "kcm").ifBlank { parts.getOrNull(0).orEmpty() }
            candidates += ZjuTimetableCandidateRow(
                sourceRowIndex = index,
                schoolYearCode = requestYearCode,
                termCode = requestTermCode,
                externalSelectionKeyCandidate = row.firstText("xkkh"),
                weekday = weekday,
                startPeriod = start,
                endPeriod = end,
                title = title
            )
            val rawLocation = row.firstText("cdmc", "jxdd").ifBlank { parts.getOrNull(3).orEmpty() }
            val location = normalizeLocation(rawLocation)
            if (title.isBlank() || weekday == null || weekday !in 1..7 || start == null || start !in 1..20 || end == null || end < start) {
                invalidRows += 1
                continue
            }

            val weekText = parts.getOrNull(1).orEmpty()
            val parity = row.firstText("dsz")
            if (parity == "0" || parity == "1" || weekText.contains("单周") || weekText.contains("双周") ||
                weekText.contains(",") || weekText.contains("，")
            ) {
                nonWeeklyRows += 1
            }

            courses += Course(
                title = cleanText(title),
                weekday = weekday,
                startPeriod = start,
                endPeriod = end.coerceAtMost(20),
                building = location,
                zone = CourseScreenshotParser.zoneByPrefix(location),
                needsConfirmation = true,
                externalSchoolYearCode = requestYearCode,
                externalTermCode = requestTermCode,
                externalSelectionKeyCandidate = row.firstText("xkkh")
            )
            placeFor(rawLocation)?.let(places::add)
        }

        return ZjuTimetableParseResult.Success(
            batch = CourseImportBatch(
                source = CourseImportSource.ZJU_TIMETABLE,
                courses = courses,
                newPlaces = places.distinct().take(50)
            ),
            invalidRows = invalidRows,
            nonWeeklyRows = nonWeeklyRows,
            candidates = candidates
        )
    }

    private fun JSONObject.firstText(vararg keys: String): String = keys.asSequence()
        .map { optString(it).trim() }
        .firstOrNull { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
        .orEmpty()

    /**
     * 校验响应回显的学年/学期与请求是否一致；只在**能证实不一致**时返回失败。
     *
     * 规则：
     * - 响应没有回显该字段（`null`/空）⇒ 不判（无法核对，交给其它判据）。
     * - 学年 `xnm`（完整学年区间）⇒ 直接比对。
     * - 学期 `xqm`：响应只回显**单个汉字**（脱敏样本证实为「秋/冬/短/暑/春/夏」）。请求值可能是
     *   `1|短`，也可能是不带 `|` 的 `1`（此时显示文字来自 option 文本）。因此这里**优先用调用方
     *   传入的 [termDisplay]**（与请求表单 `xqmmc` 同一来源，见 `termDisplayOf`），拿不到才退回从
     *   value 里取 `|` 之后的部分。这样"value 不含 `|`、text 才是显示文字"的形态不会被误判。
     * - 两个「短」共享同一显示文字，无法用响应区分 `1|短` 与 `2|短`——这种情况**不声称已核对完整
     *   代码**，只在显示文字明确不同时判失败。
     */
    private fun mismatchReason(
        root: JSONObject,
        requestYearCode: String,
        requestTermCode: String,
        requestedTermDisplay: String
    ): ZjuTimetableParseResult.Failure? {
        val echoedYear = root.optString("xnm").trim().takeIf { it.isNotBlank() && !it.equals("null", true) }
        if (requestYearCode.isNotBlank() && echoedYear != null && !echoedYear.equals(requestYearCode, ignoreCase = true)) {
            return ZjuTimetableParseResult.Failure(
                "教务返回的是「${echoedYear}」学年，与所选「$requestYearCode」不一致，已停止导入。请在官方页面确认后重试。",
                ZjuParseFailureReason.SEMESTER_MISMATCH
            )
        }
        val echoedTerm = root.optString("xqm").trim().takeIf { it.isNotBlank() && !it.equals("null", true) }
        val echoedDisplay = echoedTerm?.substringAfter('|', echoedTerm)?.trim().orEmpty()
        val expectedDisplay = requestedTermDisplay.trim()
            .ifBlank { requestTermCode.substringAfter('|', requestTermCode).trim() }
        if (
            requestTermCode.isNotBlank() &&
            echoedDisplay.isNotBlank() &&
            expectedDisplay.isNotBlank() &&
            !echoedDisplay.equals(expectedDisplay, ignoreCase = true)
        ) {
            return ZjuTimetableParseResult.Failure(
                "教务返回的是「$echoedDisplay」学期，与所选「$expectedDisplay」不一致，已停止导入。请在官方页面确认后重试。",
                ZjuParseFailureReason.SEMESTER_MISMATCH
            )
        }
        return null
    }

    /**
     * 正文是否**明确**是统一身份认证登录页（而不是任意 HTML 错误页）。
     * 依据真实脱敏样本与常见登录页特征：CAS 登录路径、`execution` 隐藏字段、`authn`、登录文案。
     * 特征不足时**不**判会话失效（宁可让用户重试一次，也不要逼他重新登录）。
     */
    private fun looksLikeLoginPage(html: String): Boolean =
        listOf(
            "cas/login",
            "authn",
            "execution",
            "统一身份认证",
            "登录",
            "id=\"login\"",
            "name=\"username\""
        ).any { html.contains(it, ignoreCase = true) }

    private fun String.firstInteger(): Int? = integer.find(this)?.value?.toIntOrNull()

    private fun courseBlockParts(value: String): List<String> = value
        .substringBefore("zwf")
        .split(breakTag)
        .map(::cleanText)
        .filter(String::isNotBlank)

    private fun normalizeLocation(value: String): String =
        cleanText(CourseScreenshotParser.stripCampusPrefix(value)).ifBlank { "地点待确认" }

    private fun placeFor(value: String): String? {
        val stripped = CourseScreenshotParser.stripCampusPrefix(cleanText(value))
        if (stripped.isBlank()) return null
        val compact = CourseScreenshotParser.normalize(stripped)
        return CourseScreenshotParser.buildingFromRoom(compact) ?: stripped
    }

    private fun cleanText(value: String): String = value
        .replace("&nbsp;", " ", ignoreCase = true)
        .replace("&amp;", "&", ignoreCase = true)
        .replace("&lt;", "<", ignoreCase = true)
        .replace("&gt;", ">", ignoreCase = true)
        .replace("&quot;", "\"", ignoreCase = true)
        .replace("&#39;", "'", ignoreCase = true)
        .replace(htmlTag, "")
        .trim()
}
