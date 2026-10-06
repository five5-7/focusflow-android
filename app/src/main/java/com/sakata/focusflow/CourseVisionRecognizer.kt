package com.sakata.focusflow

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/** 原硅基流动默认模型 ID；实际能力与可用性由用户发起的测试确认。 */
const val DEFAULT_COURSE_VISION_MODEL = "Qwen/Qwen3-VL-8B-Instruct"

/** 课表识别可选的预设视觉模型（模型 ID 到展示名），设置页一键选择，不用手打。 */
val VISION_MODEL_PRESETS: List<Pair<String, String>> = listOf(
    "Qwen/Qwen3-VL-8B-Instruct" to "Qwen3-VL-8B",
    "Qwen/Qwen3-VL-32B-Instruct" to "Qwen3-VL-32B",
    "Qwen/Qwen3-VL-30B-A3B-Instruct" to "Qwen3-VL-30B-A3B",
    "PaddlePaddle/PaddleOCR-VL-1.5" to "PaddleOCR-VL（OCR 专用）"
)

/** 保留旧用户的视觉启用状态；服务与凭据由独立配置管理。 */
data class CourseVisionSettings(
    val enabled: Boolean = false,
    val model: String = DEFAULT_COURSE_VISION_MODEL
)

internal data class ParseReport(
    val courses: List<Course>,
    val warnings: List<String> = emptyList(),
    val rejectionReason: String? = null
)

data class VisionRecognitionPreview(
    val preview: VisionGridPreview,
    val candidates: List<VisionCandidate>,
    val warnings: List<String> = emptyList()
)

/** 可替换的视觉服务识别入口；批次 A 沿用待确认课程解析。 */
object CourseVisionRecognizer {
    fun recognize(
        context: Context, uri: Uri, profile: VisionServiceProfile, session: VisionSession,
        places: List<CampusPlace>, onSuccess: (CourseImportBatch) -> Unit, onFailure: (String) -> Unit
    ) {
        Thread {
            val store = VisionServiceStore(context)
            val vault = VisionCredentialStore(context)
            val result = runCatching {
                check(store.currentAndReviewable(profile, vault) && !session.cancelled())
                val key = vault.read(profile.credentialRef) as? VisionCredentialRead.Ready ?: error("key unavailable")
                val image = compressImage(context, uri) ?: error("image unavailable")
                check(!session.cancelled())
                when (val response = VisionServiceClient().chat(profile, key.secret, buildPrompt(places), image, session)) {
                    is VisionClientResult.Failure -> response
                    is VisionClientResult.Text -> {
                        if (VisionResponseParser.parse(response.value) != null) {
                            VisionClientResult.Failure("结构化视觉结果需要先在审核页确认，未直接写入课程")
                        } else {
                            val parsed = parseCourses(response.value, places)
                            if (parsed.rejectionReason != null) VisionClientResult.Failure(parsed.rejectionReason)
                            else if (parsed.courses.isEmpty() || parsed.courses.size > VisionLimits.MAX_CANDIDATES) {
                                VisionClientResult.Failure("没有可导入课程或超过 200 条上限")
                            } else {
                                val newPlaces = parsed.courses.map { it.building }.filter { building ->
                                    building != "地点待确认" && places.none { place ->
                                        val p = CourseScreenshotParser.normalize(place.name)
                                        CourseScreenshotParser.normalize(building) == p ||
                                            CourseScreenshotParser.normalize(building).contains(p)
                                    }
                                }.distinct()
                                CourseImportBatch(
                                    CourseImportSource.VISION_SCREENSHOT,
                                    parsed.courses,
                                    newPlaces = newPlaces,
                                    warnings = parsed.warnings
                                )
                            }
                        }
                    }
                }
            }.getOrElse { VisionClientResult.Failure("图片、凭据或服务不可用，请检查配置后重试") }
            Handler(Looper.getMainLooper()).post {
                if (!session.cancelled() && store.currentAndReviewable(profile, vault)) {
                    when (result) {
                        is CourseImportBatch -> onSuccess(result)
                        is VisionClientResult.Failure -> onFailure(result.message)
                    }
                } else onFailure("已取消、超时或服务配置发生变化，本次未导入")
            }
        }.apply { name = "course-vision" }.start()
    }

    fun recognizePreview(
        context: Context, uri: Uri, profile: VisionServiceProfile, session: VisionSession,
        onSuccess: (VisionRecognitionPreview) -> Unit, onFailure: (String) -> Unit
    ) {
        Thread {
            val store = VisionServiceStore(context)
            val vault = VisionCredentialStore(context)
            val result = runCatching<PreviewResult> {
                check(store.currentAndReviewable(profile, vault) && !session.cancelled())
                val key = vault.read(profile.credentialRef) as? VisionCredentialRead.Ready ?: error("key unavailable")
                val image = compressImage(context, uri) ?: error("image unavailable")
                when (val response = VisionServiceClient().chat(profile, key.secret, buildStructuredPrompt(), image, session)) {
                    is VisionClientResult.Failure -> PreviewResult.Failure(response.message)
                    is VisionClientResult.Text -> {
                        val parsed = VisionResponseParser.parse(response.value)
                            ?: return@runCatching PreviewResult.Failure("模型没有返回严格的结构化视觉结果（${VisionResponseDiagnostics.summarize(response.value)}）")
                        val preview = VisionGridPipeline.preview(parsed.geometry, parsed.candidates, null)
                        PreviewResult.Success(VisionRecognitionPreview(preview, parsed.candidates, preview.warnings))
                    }
                }
            }.getOrElse { PreviewResult.Failure("图片、凭据或服务不可用，请检查配置后重试") }
            Handler(Looper.getMainLooper()).post {
                if (!session.cancelled() && store.currentAndReviewable(profile, vault)) {
                    when (result) {
                        is PreviewResult.Success -> onSuccess(result.value)
                        is PreviewResult.Failure -> onFailure(result.message)
                    }
                } else onFailure("已取消、超时或服务配置发生变化，本次未导入")
            }
        }.apply { name = "course-vision-preview" }.start()
    }

    private sealed class PreviewResult {
        data class Success(val value: VisionRecognitionPreview) : PreviewResult()
        data class Failure(val message: String) : PreviewResult()
    }

    internal fun buildStructuredPrompt(): String = """
        你是课表网格识别助手，只识别课表网格内的课程色块。
        严格只返回一个 JSON 对象，不要代码围栏、解释或额外文字。
        对象键必须是 geometry 和 candidates。geometry 键必须是 width,height,originalToWorking,weekdays,periods,evidence；
        weekdays/periods 的每项键必须是 index,start,end,source，source 只能是 detected。
        candidates 每项键必须是 id,title,day,startPeriod,endPeriod,box,evidence,rawLocation,weeks,note,textConfidence,geometryConfidence；
        看不清的 day/startPeriod/endPeriod 使用 null，box 使用归一化坐标或 null，不能猜测坐标。
        geometry 的 originalToWorking 必须是 9 个数字，所有坐标在 0 到 1 之间，候选最多 ${VisionLimits.MAX_CANDIDATES} 条。
    """.trimIndent()

    /** 说明性文字（页脚/备注等）关键词，命中则丢弃，避免把“隐藏课程信息”等当成课程。 */
    private val noiseKeywords = listOf("隐藏课程信息", "课程信息", "学分", "备注", "说明", "教师", "老师", "节次")

    internal fun buildPrompt(places: List<CampusPlace>): String {
        val placeNames = places.map { it.name }.distinct().take(80).joinToString("、")
        return buildString {
            append("你是课表网格识别助手。图片可能有透视、摩尔纹或很小的文字。只识别课表网格内的课程色块，忽略考试时间、备注、教师名单、按钮和网格外说明。")
            append("先根据顶部的星期表头确定列：周一到周日分别输出 day=1 到 day=7；再根据最左侧的节次标号确定行。")
            append("课程色块跨越多行时，startPeriod 是色块覆盖的第一节，endPeriod 是最后一节；同一个合并色块只输出一次。")
            append("day、startPeriod、endPeriod 必须来自色块在网格中的位置，不能从课程文字、周次、考试日期或上一门课推测。看不清表头或节次的课程直接省略，绝不能默认填 1。")
            append("只返回一个 JSON 数组，不要代码围栏、解释或额外对象。每项严格使用：{\"name\":\"课程名称\",\"day\":1,\"startPeriod\":1,\"endPeriod\":2,\"location\":\"教室或楼名\"}。")
            append("name 只含课程名；location 只抄图片中明确出现的地点，没有则填空字符串。所有数字必须是 JSON 整数，范围为 day 1-7、节次 1-20，且 endPeriod 不小于 startPeriod。")
            append("如果无法可靠看清星期列和节次行，返回 []，不要生成看似完整的猜测结果。")
            if (placeNames.isNotBlank()) append("可用于规范地点的已有名称：$placeNames。")
        }
    }

    /** 剥 JSON 围栏后解析；未知坐标不再钳制成第 1 节，并拒绝明显塌缩到同一格的整批结果。 */
    internal fun parseCourses(content: String, places: List<CampusPlace>): ParseReport = runCatching {
        val cleaned = content.trim()
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```")
            .trim()
        val start = cleaned.indexOf('[')
        val end = cleaned.lastIndexOf(']')
        if (start < 0 || end <= start) ParseReport(emptyList(), rejectionReason = "模型返回的不是课程数组，已停止导入。")
        else {
            val values = JSONArray(cleaned.substring(start, end + 1))
            var invalidCoordinates = 0
            var invalidContents = 0
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

    private fun JSONObject.strictInteger(key: String): Int? = when (val value = opt(key)) {
        is Byte, is Short, is Int, is Long -> (value as Number).toLong()
            .takeIf { it >= Int.MIN_VALUE.toLong() && it <= Int.MAX_VALUE.toLong() }
            ?.toInt()
        is Float, is Double -> (value as Number).toDouble().takeIf { it.isFinite() && it % 1.0 == 0.0 }?.toInt()
        is String -> value.trim().toIntOrNull()
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

    /**
     * Converts only explicitly confirmed candidates to the existing import batch.
     * Policy.prepare remains the final normalization and persistence boundary.
     */
    internal fun importReviewedCandidates(
        result: VisionReviewResult,
        places: List<CampusPlace>
    ): CourseImportBatch {
        val courses = result.candidates
            .filter { it.state == VisionCandidateState.CONFIRMED_BY_USER }
            .mapNotNull { candidate ->
                val day = candidate.day ?: return@mapNotNull null
                val start = candidate.startPeriod ?: return@mapNotNull null
                val end = candidate.endPeriod ?: return@mapNotNull null
                val (building, zone) = matchLocation(candidate.rawLocation.orEmpty(), places)
                Course(
                    title = candidate.title.trim(),
                    weekday = day,
                    startPeriod = start,
                    endPeriod = end,
                    building = building,
                    zone = zone,
                    needsConfirmation = true
                )
            }
        val distinct = courses.distinctBy { listOf(it.weekday, it.startPeriod, it.endPeriod, it.title) }
        val newPlaces = distinct.map { it.building }.filter { building ->
            building != "地点待确认" && places.none { place ->
                CourseScreenshotParser.normalize(building).contains(CourseScreenshotParser.normalize(place.name))
            }
        }.distinct()
        return CourseImportPolicy.prepare(CourseImportBatch(
            source = CourseImportSource.VISION_SCREENSHOT,
            courses = distinct,
            newPlaces = newPlaces,
            warnings = result.warnings + result.unresolvedIds.map { "候选 $it 仍需确认，未导入" }
        ))
    }

    /** 解码（含 EXIF 旋转、降采样）后压缩为 JPEG base64，避免大图让接口请求过大。 */
    private fun compressImage(context: Context, uri: Uri): ByteArray? = runCatching {
        val bitmap = CourseScreenshotParser.decodeRotated(context, uri)
        try {
            val output = ByteArrayOutputStream()
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 85, output))
            output.toByteArray().also { require(it.size <= VisionLimits.MAX_IMAGE_BYTES) }
        } finally { if (!bitmap.isRecycled) bitmap.recycle() }
    }.getOrNull()
}
