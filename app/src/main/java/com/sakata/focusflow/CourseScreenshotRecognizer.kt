package com.sakata.focusflow

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlin.math.abs

data class CourseOcrBlock(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
}

/**
 * Reads a timetable screenshot locally. Recognition only creates unconfirmed
 * courses: the user still decides whether each result belongs in the schedule.
 */
object CourseScreenshotRecognizer {
    fun recognize(
        context: Context,
        uri: Uri,
        places: List<CampusPlace>,
        onSuccess: (List<Course>) -> Unit,
        onFailure: (String) -> Unit
    ) {
        val image = runCatching { InputImage.fromFilePath(context, uri) }
            .getOrElse {
                onFailure("无法读取这张图片，请换一张清晰的课表截图。")
                return
            }
        val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        recognizer.process(image)
            .addOnSuccessListener { result ->
                val blocks = result.textBlocks.mapNotNull { block ->
                    block.boundingBox?.let { box -> CourseOcrBlock(block.text, box.left, box.top, box.right, box.bottom) }
                }
                onSuccess(CourseScreenshotParser.parse(blocks, places))
            }
            .addOnFailureListener { onFailure("文字识别没有完成，请换一张更清晰、包含课程文字的截图。") }
            .addOnCompleteListener { recognizer.close() }
    }
}

object CourseScreenshotParser {
    private val weekdayCharacters = mapOf('一' to 1, '二' to 2, '三' to 3, '四' to 4, '五' to 5, '六' to 6, '日' to 7, '天' to 7)
    private val explicitPeriod = Regex("第?\\s*(1[0-3]|[1-9])\\s*[-—~～至到]\\s*(1[0-3]|[1-9])\\s*节?")
    private val singlePeriod = Regex("第\\s*(1[0-3]|[1-9])\\s*节")
    private val clock = Regex("\\b(?:[01]?\\d|2[0-3])[:：][0-5]\\d\\b")
    private val weekNoise = Regex("第?\\s*\\d+(?:[-—~～至]\\d+)?\\s*周|单周|双周|教师|老师|教室|学分")
    private val headerToken = Regex("^(?:周|星期)?[一二三四五六日天]$")
    private val periodToken = Regex("^(?:第)?(1[0-3]|[1-9])(?:节)?$")

    fun parse(blocks: List<CourseOcrBlock>, places: List<CampusPlace>): List<Course> {
        if (blocks.isEmpty()) return emptyList()
        val headers = blocks.mapNotNull { block ->
            val compact = block.text.replace(" ", "").trim()
            val token = headerToken.matchEntire(compact) ?: return@mapNotNull null
            weekdayCharacters[token.value.last()]?.let { it to block.centerX }
        }
        val headerBottom = blocks.filter { headerToken.matches(it.text.replace(" ", "").trim()) }.maxOfOrNull { it.bottom } ?: Int.MIN_VALUE
        val periodAnchors = blocks.mapNotNull { block ->
            val lines = block.text.lines().map { it.trim() }
            val period = lines.firstNotNullOfOrNull { periodToken.matchEntire(it)?.groupValues?.get(1)?.toIntOrNull() }
            period?.let { it to block.centerY }
        }.distinctBy { it.first }

        return blocks.mapNotNull { block ->
            if (block.centerY <= headerBottom) return@mapNotNull null
            val compact = block.text.replace(" ", "")
            val explicitDay = Regex("(?:周|星期)([一二三四五六日天])").find(compact)
                ?.groupValues?.get(1)?.firstOrNull()?.let(weekdayCharacters::get)
            val weekday = explicitDay ?: headers.minByOrNull { (_, x) -> abs(x - block.centerX) }?.first
                ?: return@mapNotNull null

            val range = explicitPeriod.find(compact)
            val single = singlePeriod.find(compact)
            val inferredStart = periodAnchors.minByOrNull { (_, y) -> abs(y - block.top) }?.first
            val start = range?.groupValues?.get(1)?.toIntOrNull()
                ?: single?.groupValues?.get(1)?.toIntOrNull()
                ?: inferredStart
                ?: return@mapNotNull null
            val inferredEnd = periodAnchors.filter { (_, y) -> y <= block.bottom + blockHeightAllowance(block) }
                .maxByOrNull { it.second }?.first
            val end = (range?.groupValues?.get(2)?.toIntOrNull()
                ?: inferredEnd
                ?: (start + 1)).coerceIn(start, 13)

            val building = detectBuilding(block.text, places)
            val title = detectTitle(block.text, building) ?: return@mapNotNull null
            Course(
                title = title,
                weekday = weekday,
                startPeriod = start.coerceIn(1, 13),
                endPeriod = end,
                building = building.first,
                zone = building.second,
                needsConfirmation = true
            )
        }.filter { it.title.length >= 2 }
            .distinctBy { Triple(it.weekday, it.startPeriod, normalize(it.title)) }
            .sortedWith(compareBy<Course> { it.weekday }.thenBy { it.startPeriod })
    }

    private fun blockHeightAllowance(block: CourseOcrBlock): Int = ((block.bottom - block.top) * 0.35f).toInt().coerceAtLeast(8)

    private fun detectTitle(text: String, building: Pair<String, CampusZone>): String? = text.lines()
        .map { it.trim().trim('•', '·', '-', '|') }
        .firstOrNull { line ->
            line.length >= 2 && line.any { it.code in 0x4E00..0x9FFF } &&
                !headerToken.matches(line.replace(" ", "")) &&
                !explicitPeriod.containsMatchIn(line) && !singlePeriod.containsMatchIn(line) &&
                !clock.containsMatchIn(line) && !weekNoise.containsMatchIn(line) &&
                !normalize(line).contains(normalize(building.first)) &&
                !line.contains("教学楼") && !line.contains("校区")
        }?.take(40)

    private fun detectBuilding(text: String, places: List<CampusPlace>): Pair<String, CampusZone> {
        val compact = normalize(text)
        places.firstOrNull { compact.contains(normalize(it.name)) }?.let { return it.name to it.zone }
        val match = Regex("(?:西|东|北)[一二三四五六七八九十0-9]+(?:教学)?楼|化学实验中心").find(compact)?.value
        if (match != null) {
            val zone = when {
                match.startsWith("东") -> CampusZone.EAST_TEACHING
                match.startsWith("北") -> CampusZone.NORTH_TEACHING
                match.contains("化学") -> CampusZone.CHEMISTRY_LABS
                else -> CampusZone.WEST_TEACHING
            }
            return match to zone
        }
        return "地点待确认" to CampusZone.WEST_TEACHING
    }

    private fun normalize(value: String): String = value.replace(Regex("[\\s　]"), "").replace("１", "1").replace("２", "2")
}
