package com.sakata.focusflow

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/** 课表识别默认视觉模型：硅基流动在线的免费视觉模型（Qwen3-VL-8B，替代已下线的 Qwen2.5-VL-7B）。 */
const val DEFAULT_COURSE_VISION_MODEL = "Qwen/Qwen3-VL-8B-Instruct"

/** 课表识别可选的预设视觉模型（模型 ID 到展示名），设置页一键选择，不用手打。 */
val VISION_MODEL_PRESETS: List<Pair<String, String>> = listOf(
    "Qwen/Qwen3-VL-8B-Instruct" to "Qwen3-VL-8B（免费）",
    "Qwen/Qwen3-VL-32B-Instruct" to "Qwen3-VL-32B",
    "Qwen/Qwen3-VL-30B-A3B-Instruct" to "Qwen3-VL-30B-A3B",
    "PaddlePaddle/PaddleOCR-VL-1.5" to "PaddleOCR-VL（OCR 专用）"
)

/** 课表截图用硅基流动视觉模型识别的设置。key 与教程搜索共用同一把“硅基流动 API key”。 */
data class CourseVisionSettings(
    val enabled: Boolean = false,
    val model: String = DEFAULT_COURSE_VISION_MODEL
)

/**
 * 硅基流动视觉模型客户端：把课表截图压缩为 JPEG base64 后走 OpenAI 兼容的
 * chat/completions 多模态接口，要求模型返回结构化课程 JSON。
 * key 仅存本机、只发往 api.siliconflow.cn；识别结果仍是待确认课程。
 */
object CourseVisionRecognizer {
    private const val ENDPOINT = "https://api.siliconflow.cn/v1/chat/completions"
    private const val JPEG_QUALITY = 85
    private const val MAX_TOKENS = 4096
    private const val MAX_BYTES = 256 * 1024

    sealed class RecognizeResult {
        class Success(
            val courses: List<Course>,
            val newPlaces: List<String> = emptyList(),
            val warnings: List<String> = emptyList()
        ) : RecognizeResult()
        class Error(val message: String) : RecognizeResult()
    }

    internal data class ParseReport(
        val courses: List<Course>,
        val warnings: List<String> = emptyList(),
        val rejectionReason: String? = null
    )

    fun recognize(
        context: Context,
        uri: Uri,
        apiKey: String,
        model: String,
        places: List<CampusPlace>,
        onSuccess: (CourseImportBatch) -> Unit,
        onFailure: (String) -> Unit
    ) {
        // 网络请求 + 图片压缩耗时，放后台线程避免主线程卡顿。
        Thread {
            val result = runCatching { request(context, uri, apiKey, model, places) }
                .getOrElse { RecognizeResult.Error(it.message ?: "网络不可用") }
            Handler(Looper.getMainLooper()).post {
                when (result) {
                    is RecognizeResult.Success -> {
                        onSuccess(
                            CourseImportBatch(
                                source = CourseImportSource.VISION_SCREENSHOT,
                                courses = result.courses,
                                newPlaces = result.newPlaces,
                                warnings = result.warnings
                            )
                        )
                    }
                    is RecognizeResult.Error -> onFailure(result.message)
                }
            }
        }.start()
    }

    private fun request(context: Context, uri: Uri, apiKey: String, model: String, places: List<CampusPlace>): RecognizeResult {
        val imageBase64 = compressToBase64(context, uri)
            ?: return RecognizeResult.Error("无法读取这张图片，请换一张清晰的课表截图。")
        val prompt = buildPrompt(places)
        val content = JSONArray().apply {
            put(JSONObject().apply {
                put("type", "image_url")
                put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$imageBase64"))
            })
            put(JSONObject().apply { put("type", "text"); put("text", prompt) })
        }
        val request = JSONObject().apply {
            put("model", model)
            put("temperature", 0.0)
            put("max_tokens", MAX_TOKENS)
            put("messages", JSONArray().apply {
                put(JSONObject().apply { put("role", "user"); put("content", content) })
            })
        }
        val connection = try {
            (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 60_000
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer $apiKey")
                doOutput = true
            }
        } catch (e: Exception) {
            return RecognizeResult.Error("网络不可用")
        }
        try {
            connection.outputStream.use { stream: OutputStream ->
                stream.write(request.toString().toByteArray(Charsets.UTF_8))
            }
            val status = connection.responseCode
            if (status !in 200..299) {
                val body = readBody(connection).trim().take(120)
                return RecognizeResult.Error(
                    when (status) {
                        401 -> "API key 无效，请检查设置页的 key"
                        429 -> "请求过于频繁，稍后再试"
                        else -> {
                            val message = runCatching { JSONObject(body).optString("message", "") }.getOrNull() ?: body
                            val text = message.ifBlank { body }.take(120)
                            when {
                                text.isBlank() -> "请求失败（$status）"
                                text.contains("odel", ignoreCase = true) -> "$text（模型可能不可用，去设置页换一个模型名）"
                                else -> text
                            }
                        }
                    }
                )
            }
            val body = readBody(connection)
            val message = runCatching { JSONObject(body).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message") }.getOrNull()
            val contentText = when (val value = message?.opt("content")) {
                is String -> value
                is JSONArray -> buildString {
                    for (index in 0 until value.length()) {
                        value.optJSONObject(index)?.optString("text", "")?.takeIf { it.isNotBlank() }?.let(::append)
                    }
                }
                else -> ""
            }
            if (contentText.isBlank()) return RecognizeResult.Error("没有返回内容，请检查模型名或稍后再试")
            val parsed = parseCourses(contentText, places)
            parsed.rejectionReason?.let { return RecognizeResult.Error(it) }
            val courses = parsed.courses
            if (courses.isEmpty()) return RecognizeResult.Error("模型没有解析出课程，请换一张能看清课程名称、星期和节次的截图")
            // 识别出的新地点（不在已有地点目录里的教室/楼名文字）交给调用方记入“地点待用”。
            val newPlaces = courses.map { it.building }
                .filter { building ->
                    building != "地点待确认" && places.none { place ->
                        val p = CourseScreenshotParser.normalize(place.name)
                        CourseScreenshotParser.normalize(building) == p || CourseScreenshotParser.normalize(building).contains(p)
                    }
                }
                .distinct()
            return RecognizeResult.Success(courses, newPlaces, parsed.warnings)
        } catch (e: Exception) {
            return RecognizeResult.Error(e.message ?: "网络不可用")
        } finally {
            connection.disconnect()
        }
    }

    /** 说明性文字（页脚/备注等）关键词，命中则丢弃，避免把“隐藏课程信息”等当成课程。 */
    private val noiseKeywords = listOf("隐藏课程信息", "课程信息", "学分", "备注", "说明", "教师", "老师", "节次")

    private data class GridEvidence(
        val index: Int,
        val label: String,
        val centerX: Double,
        val centerY: Double
    )

    internal fun buildPrompt(places: List<CampusPlace>): String {
        val placeNames = places.map { it.name }.distinct().take(80).joinToString("、")
        return buildString {
            append("你是课表网格识别助手。图片可能有透视、摩尔纹或很小的文字。只识别课表网格内的课程色块，忽略考试时间、备注、教师名单、按钮和网格外说明。")
            append("必须先逐字读取图片中真实可见的顶部星期表头和最左侧节次标号；被裁掉、遮挡或看不清时不得补全、推测或沿用常见课表布局。")
            append("顶部星期表头确定列：周一到周日分别为 day=1 到 day=7；最左侧节次标号确定行。所有 centerX/centerY 都是相对整张图片左上角的 0 到 1 归一化中心坐标。")
            append("课程色块跨越多行时，startPeriod 是色块覆盖的第一节，endPeriod 是最后一节；同一个合并色块只输出一次。")
            append("day、startPeriod、endPeriod 必须来自课程色块中心与已读取网格坐标的对应关系，不能从课程文字、周次、考试日期或上一门课推测，绝不能默认填 1。")
            append("只返回一个 JSON 对象，不要代码围栏或解释。严格使用：")
            append("{\"grid\":{\"weekdayHeaderRowVisible\":true,\"periodLabelColumnVisible\":true},")
            append("\"weekdayHeaders\":[{\"day\":1,\"label\":\"周一\",\"centerX\":0.20,\"centerY\":0.08}],")
            append("\"periodLabels\":[{\"period\":1,\"label\":\"第一节\",\"centerX\":0.06,\"centerY\":0.20}],")
            append("\"courses\":[{\"name\":\"课程名称\",\"day\":1,\"startPeriod\":1,\"endPeriod\":2,\"location\":\"教室或楼名\",\"columnCenterX\":0.20,\"startRowCenterY\":0.20,\"endRowCenterY\":0.32}]}。")
            append("name 只含课程名；location 只抄图片中明确出现的地点，没有则填空字符串。所有数字必须是 JSON 整数，范围为 day 1-7、节次 1-20，且 endPeriod 不小于 startPeriod。")
            append("weekdayHeaders 必须逐项抄出实际可见的星期文字与坐标，periodLabels 必须逐项抄出实际可见的节次文字与坐标；不要输出被裁掉或看不清的标签。")
            append("如果无法可靠看清星期标题行或节次标签列，把对应 visible 设为 false、对应证据数组与 courses 都返回空数组；不要生成看似完整的猜测结果。")
            if (placeNames.isNotBlank()) append("可用于规范地点的已有名称：$placeNames。")
        }
    }

    /**
     * 只接受带网格证据的对象。模型必须先报告真实可见的星期标题、节次标签及坐标，
     * 每门课程的几何位置再与该网格吸附；旧版无证据数组和异常／转置网格一律拒绝。
     */
    internal fun parseCourses(content: String, places: List<CampusPlace>): ParseReport = runCatching {
        val cleaned = content.trim()
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```")
            .trim()
        if (cleaned.startsWith("[")) {
            return@runCatching ParseReport(
                emptyList(),
                rejectionReason = "识别结果仍是旧版课程数组，缺少可验证的星期表头和节次网格证据，已停止导入。"
            )
        }
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        if (start < 0 || end <= start) {
            ParseReport(emptyList(), rejectionReason = "识别结果缺少可验证的星期表头和节次网格证据，已停止导入。请使用能完整看到顶部星期标题和左侧节次的截图。")
        } else {
            val root = JSONObject(cleaned.substring(start, end + 1))
            val grid = root.optJSONObject("grid")
            val weekdayVisible = grid?.optBoolean("weekdayHeaderRowVisible", false) == true
            val periodVisible = grid?.optBoolean("periodLabelColumnVisible", false) == true
            val weekdayHeaders = parseGridEvidence(root.optJSONArray("weekdayHeaders"), "day")
            val periodLabels = parseGridEvidence(root.optJSONArray("periodLabels"), "period")
            val evidenceFailure = validateGridEvidence(weekdayVisible, periodVisible, weekdayHeaders, periodLabels)
            if (evidenceFailure != null) return@runCatching ParseReport(emptyList(), rejectionReason = evidenceFailure)
            val weekdayEvidence = weekdayHeaders.orEmpty().sortedBy { it.index }
            val periodEvidence = periodLabels.orEmpty().sortedBy { it.index }
            val values = root.optJSONArray("courses")
                ?: return@runCatching ParseReport(emptyList(), rejectionReason = "识别结果缺少 courses 数组，已停止导入。")
            var invalidCoordinates = 0
            var invalidContents = 0
            var geometryMismatches = 0
            val parsed = List(values.length()) { index ->
                val value = values.optJSONObject(index) ?: run {
                    invalidContents += 1
                    return@List null
                }
                val day = value.strictInteger("day")
                val startPeriod = value.strictInteger("startPeriod")
                val endPeriod = value.strictInteger("endPeriod")
                if (day == null || day !in 1..7 || startPeriod == null || startPeriod !in 1..20 ||
                    endPeriod == null || endPeriod !in 1..20 || endPeriod < startPeriod
                ) {
                    invalidCoordinates += 1
                    return@List null
                }
                val title = value.optString("name", "").trim()
                if (title.length < 2 || title.length > 80 || noiseKeywords.any { title.contains(it) }) {
                    invalidContents += 1
                    return@List null
                }
                val columnCenterX = value.strictDouble("columnCenterX")
                val startRowCenterY = value.strictDouble("startRowCenterY")
                val endRowCenterY = value.strictDouble("endRowCenterY")
                if (columnCenterX == null || startRowCenterY == null || endRowCenterY == null ||
                    !snapsTo(columnCenterX, day, weekdayEvidence) ||
                    !snapsTo(startRowCenterY, startPeriod, periodEvidence) ||
                    !snapsTo(endRowCenterY, endPeriod, periodEvidence)
                ) {
                    geometryMismatches += 1
                    return@List null
                }
                val (building, zone) = matchLocation(value.optString("location", ""), places)
                Course(
                    title = title,
                    weekday = day,
                    startPeriod = startPeriod,
                    endPeriod = endPeriod,
                    building = building,
                    zone = zone,
                    needsConfirmation = true
                )
            }.filterNotNull()

            val distinct = parsed
                .distinctBy { listOf(it.weekday, it.startPeriod, it.endPeriod, it.title) }
                .sortedWith(compareBy<Course> { it.weekday }.thenBy { it.startPeriod })
            val collapsed = distinct.groupBy { Triple(it.weekday, it.startPeriod, it.endPeriod) }
                .values
                .firstOrNull { group -> group.map { it.title.trim() }.distinct().size >= 3 }
            val mostlyUnknown = values.length() >= 3 && invalidCoordinates * 2 >= values.length()
            when {
                geometryMismatches > 0 -> ParseReport(
                    emptyList(),
                    rejectionReason = "有 $geometryMismatches 门课程的位置无法对应已识别的星期列或节次行，可能把一条横行误当成了一天，已停止导入。请保留完整的顶部星期标题和左侧节次后重试。"
                )
                collapsed != null -> ParseReport(
                    emptyList(),
                    rejectionReason = "识别结果把 ${collapsed.size} 门不同课程放在同一个星期和节次，坐标明显异常，已停止导入。请换更清晰、能完整看到星期表头和左侧节次的截图，或更换视觉模型。"
                )
                mostlyUnknown -> ParseReport(
                    emptyList(),
                    rejectionReason = "模型返回的 ${values.length()} 条结果中有 $invalidCoordinates 条缺少可靠的星期或节次，已停止导入，避免错误课程进入待确认列表。"
                )
                else -> ParseReport(
                    courses = distinct,
                    warnings = buildList {
                        if (invalidCoordinates > 0) add("$invalidCoordinates 条课程缺少可靠的星期或节次，未导入")
                        if (invalidContents > 0) add("$invalidContents 条非课程或课程名异常的内容，未导入")
                    }
                )
            }
        }
    }.getOrElse { ParseReport(emptyList(), rejectionReason = "无法解析模型返回的课程 JSON，已停止导入。") }

    private fun parseGridEvidence(values: JSONArray?, indexKey: String): List<GridEvidence>? {
        if (values == null) return null
        val evidence = mutableListOf<GridEvidence>()
        for (index in 0 until values.length()) {
            val value = values.optJSONObject(index) ?: return null
            val gridIndex = value.strictInteger(indexKey) ?: return null
            val label = value.optString("label", "").trim()
            val centerX = value.strictDouble("centerX") ?: return null
            val centerY = value.strictDouble("centerY") ?: return null
            evidence += GridEvidence(gridIndex, label, centerX, centerY)
        }
        return evidence
    }

    private fun validateGridEvidence(
        weekdayVisible: Boolean,
        periodVisible: Boolean,
        weekdayHeaders: List<GridEvidence>?,
        periodLabels: List<GridEvidence>?
    ): String? {
        if (!weekdayVisible || !periodVisible) {
            return "图片没有同时显示完整可辨认的星期标题行和节次标签列，已停止导入。请重新截图或拍摄，保留顶部星期栏与左侧节次栏。"
        }
        val weekdays = weekdayHeaders?.sortedBy { it.index }
        val periods = periodLabels?.sortedBy { it.index }
        if (weekdays == null || weekdays.size < 5 || weekdays.map { it.index }.distinct().size != weekdays.size ||
            weekdays.any { it.index !in 1..7 || !weekdayLabelMatches(it.label, it.index) } ||
            !weekdays.isConsecutive() || !weekdays.hasHorizontalAxis()
        ) return "星期标题证据不足或排列异常，已停止导入。请使用至少能连续看清五个星期标题的完整课表图片。"
        if (periods == null || periods.size < 3 || periods.map { it.index }.distinct().size != periods.size ||
            periods.any { it.index !in 1..20 || !periodLabelMatches(it.label, it.index) } ||
            !periods.isConsecutive() || !periods.hasVerticalAxis()
        ) return "节次标签证据不足或排列异常，已停止导入。请保留左侧连续的节次标号后重试。"
        if (weekdays.maxOf { it.centerY } >= periods.minOf { it.centerY } ||
            periods.maxOf { it.centerX } >= weekdays.minOf { it.centerX }
        ) return "课表网格方向异常，星期列与节次行可能被转置或误识别，已停止导入。"
        return null
    }

    private fun List<GridEvidence>.isConsecutive(): Boolean =
        zipWithNext().all { (first, second) -> second.index == first.index + 1 }

    private fun List<GridEvidence>.hasHorizontalAxis(): Boolean {
        val xRange = last().centerX - first().centerX
        val yValues = map { it.centerY }
        val yRange = yValues.maxOrNull()!! - yValues.minOrNull()!!
        return zipWithNext().all { (first, second) -> second.centerX - first.centerX >= 0.02 } &&
            xRange >= 0.20 && yRange <= 0.12 && xRange > yRange * 2
    }

    private fun List<GridEvidence>.hasVerticalAxis(): Boolean {
        val yRange = last().centerY - first().centerY
        val xValues = map { it.centerX }
        val xRange = xValues.maxOrNull()!! - xValues.minOrNull()!!
        return zipWithNext().all { (first, second) -> second.centerY - first.centerY >= 0.015 } &&
            yRange >= 0.12 && xRange <= 0.12 && yRange > xRange * 2
    }

    private fun snapsTo(value: Double, claimedIndex: Int, evidence: List<GridEvidence>): Boolean {
        val expected = evidence.firstOrNull { it.index == claimedIndex } ?: return false
        val nearest = evidence.minByOrNull { kotlin.math.abs(value - axisCoordinate(evidence, it)) } ?: return false
        if (nearest.index != claimedIndex) return false
        val neighbors = evidence.filter { it.index != claimedIndex }
            .map { kotlin.math.abs(axisCoordinate(evidence, it) - axisCoordinate(evidence, expected)) }
        val tolerance = ((neighbors.minOrNull() ?: 0.10) * 0.45).coerceIn(0.025, 0.10)
        return kotlin.math.abs(value - axisCoordinate(evidence, expected)) <= tolerance
    }

    private fun axisCoordinate(evidence: List<GridEvidence>, point: GridEvidence): Double =
        if (evidence.hasHorizontalAxis()) point.centerX else point.centerY

    private fun weekdayLabelMatches(label: String, day: Int): Boolean {
        val normalized = label.lowercase().replace(" ", "")
        val accepted = listOf(
            listOf("周一", "星期一", "礼拜一", "mon", "monday"),
            listOf("周二", "星期二", "礼拜二", "tue", "tues", "tuesday"),
            listOf("周三", "星期三", "礼拜三", "wed", "wednesday"),
            listOf("周四", "星期四", "礼拜四", "thu", "thur", "thurs", "thursday"),
            listOf("周五", "星期五", "礼拜五", "fri", "friday"),
            listOf("周六", "星期六", "礼拜六", "sat", "saturday"),
            listOf("周日", "周天", "星期日", "星期天", "礼拜日", "礼拜天", "sun", "sunday")
        )
        return normalized in accepted[day - 1]
    }

    private fun periodLabelMatches(label: String, period: Int): Boolean {
        val normalized = label.lowercase().replace(" ", "")
        val chinese = chineseNumber(period)
        return normalized in setOf(period.toString(), "${period}节", "第${period}节", chinese, "${chinese}节", "第${chinese}节")
    }

    private fun chineseNumber(value: Int): String = when {
        value <= 10 -> listOf("零", "一", "二", "三", "四", "五", "六", "七", "八", "九", "十")[value]
        value < 20 -> "十" + listOf("零", "一", "二", "三", "四", "五", "六", "七", "八", "九")[value - 10]
        else -> "二十"
    }

    private fun JSONObject.strictInteger(key: String): Int? = when (val value = opt(key)) {
        is Byte, is Short, is Int, is Long -> (value as Number).toLong()
            .takeIf { it >= Int.MIN_VALUE.toLong() && it <= Int.MAX_VALUE.toLong() }
            ?.toInt()
        is Float, is Double -> (value as Number).toDouble().takeIf { it.isFinite() && it % 1.0 == 0.0 }?.toInt()
        is String -> value.trim().toIntOrNull()
        else -> null
    }

    private fun JSONObject.strictDouble(key: String): Double? = when (val value = opt(key)) {
        is Number -> value.toDouble().takeIf { it.isFinite() && it in 0.0..1.0 }
        is String -> value.trim().toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.0..1.0 }
        else -> null
    }

    /**
     * 地点匹配：先剥校区前缀（“紫金港东1A-213”→“东1A-213”），再按楼级归并（“东1A-213”→“东1教学楼”），
     * 目录匹配用双向包含（“化学实验中心”“田径场”也能对上）；都不中则保留楼级文字供记入地点待用。
     * 找教室靠通勤缓冲时间，不记教室号。
     */
    private fun matchLocation(location: String, places: List<CampusPlace>): Pair<String, CampusZone> {
        val compact = CourseScreenshotParser.normalize(location)
        if (compact.isBlank()) return "地点待确认" to CampusZone.WEST_TEACHING
        val stripped = CourseScreenshotParser.normalize(CourseScreenshotParser.stripCampusPrefix(compact))
        if (stripped.isBlank()) return "地点待确认" to CampusZone.WEST_TEACHING
        val building = CourseScreenshotParser.buildingFromRoom(stripped)
        if (building != null) {
            places.firstOrNull { place ->
                val p = CourseScreenshotParser.normalize(place.name)
                CourseScreenshotParser.normalize(building).contains(p) || p.contains(CourseScreenshotParser.normalize(building))
            }?.let { return it.name to it.zone }
            return building to CourseScreenshotParser.zoneByPrefix(building)
        }
        places.firstOrNull { place ->
            val p = CourseScreenshotParser.normalize(place.name)
            stripped.contains(p) || p.contains(stripped)
        }?.let { return it.name to it.zone }
        val (detected, zone) = CourseScreenshotParser.detectBuilding(location, places)
        return (if (detected == "地点待确认") location.trim() else detected) to zone
    }

    /** 解码（含 EXIF 旋转、降采样）后压缩为 JPEG base64，避免大图让接口请求过大。 */
    private fun compressToBase64(context: Context, uri: Uri): String? = runCatching {
        val bitmap = CourseScreenshotParser.decodeRotated(context, uri)
        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
        if (!bitmap.isRecycled) bitmap.recycle()
        Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
    }.getOrNull()

    private fun readBody(connection: HttpURLConnection): String {
        val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
        val reader = BufferedReader(InputStreamReader(stream, "UTF-8"))
        val buffer = StringBuilder()
        val chunk = CharArray(8192)
        var total = 0
        while (total < MAX_BYTES) {
            val count = reader.read(chunk, 0, minOf(8192, MAX_BYTES - total))
            if (count < 0) break
            buffer.append(chunk, 0, count)
            total += count
        }
        reader.close()
        return buffer.toString()
    }
}
