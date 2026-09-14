package com.sakata.focusflow

import org.json.JSONObject

internal sealed interface ZjuTimetableParseResult {
    data class Success(
        val batch: CourseImportBatch,
        val invalidRows: Int,
        val nonWeeklyRows: Int
    ) : ZjuTimetableParseResult

    data class Failure(val message: String) : ZjuTimetableParseResult
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

    fun parse(payload: String): ZjuTimetableParseResult {
        if (payload.isBlank()) return ZjuTimetableParseResult.Failure("教务系统没有返回课表数据，请重新登录后再试。")
        if (payload.length > MAX_PAYLOAD_CHARS) return ZjuTimetableParseResult.Failure("教务课表响应过大，已停止导入。")
        val trimmed = payload.trim()
        if (trimmed.startsWith("<!doctype", ignoreCase = true) || trimmed.startsWith("<html", ignoreCase = true)) {
            return ZjuTimetableParseResult.Failure("教务登录已失效，请重新完成统一身份认证。")
        }

        val root = runCatching { JSONObject(trimmed) }.getOrElse {
            return ZjuTimetableParseResult.Failure("无法解析教务课表响应，请确认当前页面是“学生课表查询”。")
        }
        if (root.optBoolean("captcha_error", false) || root.optString("captcha_error").equals("true", ignoreCase = true)) {
            return ZjuTimetableParseResult.Failure("教务系统要求完成验证码，请在官方页面验证后重试。")
        }
        val rows = root.optJSONArray("kbList")
            ?: return ZjuTimetableParseResult.Failure("教务响应中没有 kbList，当前页面格式可能已变化。")

        val courses = mutableListOf<Course>()
        val places = mutableListOf<String>()
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
            val rawLocation = row.firstText("cdmc", "jxdd").ifBlank { parts.getOrNull(3).orEmpty() }
            val location = normalizeLocation(rawLocation)
            if (title.isBlank() || weekday !in 1..7 || start !in 1..20 || end == null || end < start) {
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
                needsConfirmation = true
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
            nonWeeklyRows = nonWeeklyRows
        )
    }

    private fun JSONObject.firstText(vararg keys: String): String = keys.asSequence()
        .map { optString(it).trim() }
        .firstOrNull { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
        .orEmpty()

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
