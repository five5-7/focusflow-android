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
        class Success(val courses: List<Course>, val newPlaces: List<String> = emptyList()) : RecognizeResult()
        class Error(val message: String) : RecognizeResult()
    }

    fun recognize(
        context: Context,
        uri: Uri,
        apiKey: String,
        model: String,
        places: List<CampusPlace>,
        onSuccess: (List<Course>) -> Unit,
        onFailure: (String) -> Unit,
        onNewPlaces: (List<String>) -> Unit = {}
    ) {
        // 网络请求 + 图片压缩耗时，放后台线程避免主线程卡顿。
        Thread {
            val result = runCatching { request(context, uri, apiKey, model, places) }
                .getOrElse { RecognizeResult.Error(it.message ?: "网络不可用") }
            Handler(Looper.getMainLooper()).post {
                when (result) {
                    is RecognizeResult.Success -> {
                        onSuccess(result.courses)
                        if (result.newPlaces.isNotEmpty()) onNewPlaces(result.newPlaces)
                    }
                    is RecognizeResult.Error -> onFailure(result.message)
                }
            }
        }.start()
    }

    private fun request(context: Context, uri: Uri, apiKey: String, model: String, places: List<CampusPlace>): RecognizeResult {
        val imageBase64 = compressToBase64(context, uri)
            ?: return RecognizeResult.Error("无法读取这张图片，请换一张清晰的课表截图。")
        val placeNames = places.map { it.name }.distinct().take(80).joinToString("、")
        val prompt = buildString {
            append("你是课表识别助手。这张图片是课程表截图。只识别课表网格里的课程格子，忽略页面其他文字（页脚、备注、提示、按钮文字等，例如“隐藏课程信息”“学分”“教师名单”）。")
            append("只返回一个 JSON 数组，不要输出其他任何内容（不要代码围栏、不要解释）。数组每个元素格式：{\"name\":\"课程名称\",\"day\":1,\"startPeriod\":1,\"endPeriod\":2,\"location\":\"教室或楼名\"}。")
            append("规则：name 只填课程名称文字（如“高等数学”），不要包含教室、楼名、教师、时间；day 用 1-7 表示周一至周日；")
            append("startPeriod、endPeriod 用数字表示第几节，截图里只给一个节次时 endPeriod 与 startPeriod 相同；")
            append("location 只填教室或楼名（如“东1B-213”“西2教学楼”），照抄截图文字，没有就留空字符串；只写截图里明确出现的信息，不要推测、不要补全。")
            if (placeNames.isNotBlank()) append("location 可优先使用以下地点名：$placeNames。")
        }
        val content = JSONArray().apply {
            put(JSONObject().apply {
                put("type", "image_url")
                put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$imageBase64"))
            })
            put(JSONObject().apply { put("type", "text"); put("text", prompt) })
        }
        val request = JSONObject().apply {
            put("model", model)
            put("temperature", 0.1)
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
            val courses = parseCourses(contentText, places)
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
            return RecognizeResult.Success(courses, newPlaces)
        } catch (e: Exception) {
            return RecognizeResult.Error(e.message ?: "网络不可用")
        } finally {
            connection.disconnect()
        }
    }

    /** 说明性文字（页脚/备注等）关键词，命中则丢弃，避免把“隐藏课程信息”等当成课程。 */
    private val noiseKeywords = listOf("隐藏课程信息", "课程信息", "学分", "备注", "说明", "教师", "老师", "节次")

    /** 剥 ```json 围栏后取首个 [ 到末个 ] 再解析为待确认课程。 */
    private fun parseCourses(content: String, places: List<CampusPlace>): List<Course> = runCatching {
        val cleaned = content.trim()
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```")
            .trim()
        val start = cleaned.indexOf('[')
        val end = cleaned.lastIndexOf(']')
        if (start < 0 || end <= start) emptyList()
        else {
            val values = JSONArray(cleaned.substring(start, end + 1))
            List(values.length()) { index ->
                val value = values.optJSONObject(index) ?: return@List null
                val day = value.optInt("day", 0)
                if (day !in 1..7) return@List null
                val title = value.optString("name", "").trim()
                if (title.length < 2) return@List null
                if (noiseKeywords.any { title.contains(it) }) return@List null
                val startPeriod = value.optInt("startPeriod", 0).coerceIn(1, 13)
                val endPeriod = value.optInt("endPeriod", startPeriod).coerceIn(startPeriod, 13)
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
                .distinctBy { Triple(it.weekday, it.startPeriod, it.title) }
                .sortedWith(compareBy<Course> { it.weekday }.thenBy { it.startPeriod })
        }
    }.getOrDefault(emptyList())

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
