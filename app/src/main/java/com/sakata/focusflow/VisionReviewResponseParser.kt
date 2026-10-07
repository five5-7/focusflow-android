package com.sakata.focusflow

import org.json.JSONArray
import org.json.JSONObject

/**
 * Normalize incomplete provider output for an in-memory review only.
 * Syntax, types, limits and the canonical parser stay strict. Missing spatial
 * evidence never becomes a default weekday/period or a confirmed course.
 */
internal object VisionReviewResponseParser {
    private val rootKeys = setOf("geometry", "candidates")
    private val geometryKeys = setOf("width", "height", "originalToWorking", "weekdays", "periods", "evidence")
    private val candidateKeys = setOf(
        "id", "title", "day", "startPeriod", "endPeriod", "box", "evidence",
        "rawLocation", "weeks", "note", "textConfidence", "geometryConfidence"
    )
    private val nullableKeys = setOf(
        "day", "startPeriod", "endPeriod", "box", "rawLocation", "weeks",
        "note", "textConfidence", "geometryConfidence"
    )

    fun parse(content: String, imageWidth: Int, imageHeight: Int): VisionRecognitionPreview? = runCatching {
        require(content.toByteArray(Charsets.UTF_8).size <= VisionLimits.MAX_RESPONSE_BYTES)
        require(imageWidth in 1..8192 && imageHeight in 1..8192)
        val cleaned = unwrapFence(content)
        require(VisionJsonSyntax.valid(cleaned) && cleaned.startsWith("{"))
        val root = JSONObject(cleaned)
        require(root.keys().asSequence().all { it in rootKeys })
        val values = root.getJSONArray("candidates")
        require(values.length() <= VisionLimits.MAX_CANDIDATES)
        val warnings = mutableListOf<String>()
        val geometry = when (val value = root.opt("geometry")) {
            null, JSONObject.NULL -> JSONObject().also { warnings += "未返回网格信息，需人工填写星期和节次" }
            is JSONObject -> value
            else -> error("geometry type")
        }
        require(geometry.keys().asSequence().all { it in geometryKeys })
        // These describe the uploaded, already rotated/scaled image; the client owns them.
        geometry.put("width", imageWidth).put("height", imageHeight)
        if (!geometry.has("originalToWorking")) {
            geometry.put("originalToWorking", JSONArray(listOf(1, 0, 0, 0, 1, 0, 0, 0, 1)))
        }
        for (key in listOf("weekdays", "periods", "evidence")) {
            if (!geometry.has(key)) geometry.put(key, JSONArray())
        }
        for (key in listOf("weekdays", "periods")) {
            val axes = geometry.getJSONArray(key)
            require(axes.length() <= 20)
            for (i in 0 until axes.length()) {
                // Never accept model claims that a user supplied manual anchors.
                require(axes.getJSONObject(i).get("source") == "detected")
            }
        }
        root.put("geometry", geometry)
        var suppliedDefaults = false
        for (i in 0 until values.length()) {
            val candidate = values.getJSONObject(i)
            require(candidate.keys().asSequence().all { it in candidateKeys })
            if (!candidate.has("id")) {
                candidate.put("id", "review-" + (i + 1))
                suppliedDefaults = true
            }
            for (key in nullableKeys) {
                if (!candidate.has(key)) {
                    candidate.put(key, JSONObject.NULL)
                    suppliedDefaults = true
                }
            }
            if (!candidate.has("evidence")) {
                candidate.put("evidence", JSONArray())
                suppliedDefaults = true
            }
        }
        val parsed = VisionResponseParser.parse(root.toString()) ?: error("canonical schema")
        if (suppliedDefaults) warnings += "部分可选信息未返回，已保留为空；请逐条核对"
        val cells = parsed.candidates.associate { candidate ->
            candidate.id to candidate.box?.let { VisionGridPipeline.locateBox(it, parsed.geometry) }
        }
        val candidates = parsed.candidates.map { candidate ->
            val cell = cells[candidate.id]
            val hasBox = candidate.box != null
            val inconsistent = hasBox && (cell == null ||
                (candidate.day != null && candidate.day != cell.day) ||
                (candidate.startPeriod != null && candidate.startPeriod != cell.startPeriod) ||
                (candidate.endPeriod != null && candidate.endPeriod != cell.endPeriod))
            if (inconsistent) candidate.copy(day = null, startPeriod = null, endPeriod = null) else candidate
        }
        if (candidates != parsed.candidates) warnings += "部分星期或节次缺少一致的网格依据，已清空；请人工核对"
        val preview = VisionGridPipeline.preview(parsed.geometry, candidates, null)
        val visibleCells = candidates.associate { candidate ->
            candidate.id to (cells[candidate.id] ?: if (
                candidate.day != null && candidate.startPeriod != null && candidate.endPeriod != null
            ) VisionGridCell(candidate.day, candidate.startPeriod, candidate.endPeriod) else null)
        }
        VisionRecognitionPreview(preview.copy(cells = visibleCells), candidates, (warnings + preview.warnings).distinct())
    }.getOrNull()

    private fun unwrapFence(content: String): String {
        val trimmed = content.trim()
        val lines = trimmed.lines()
        return if (lines.size >= 3 && lines.first().trim() in setOf("```", "```json", "```JSON") &&
            lines.last().trim() == "```") {
            lines.subList(1, lines.lastIndex).joinToString("\n").trim()
        } else trimmed
    }
}
