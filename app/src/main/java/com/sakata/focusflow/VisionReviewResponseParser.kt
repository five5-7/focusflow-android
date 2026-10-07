package com.sakata.focusflow

import org.json.JSONArray
import org.json.JSONObject

internal enum class VisionReviewFailureStage(val label: String) {
    BUDGET("响应或图片超过限制"),
    FORMAT("无法提取单个完整对象"),
    SYNTAX("清理后的 JSON 语法无效"),
    CANDIDATES("缺少候选数组"),
    IDENTITY("课程名称或候选标识无效"),
    GEOMETRY("模型不能声明人工锚点"),
    CANONICAL("归一化字段仍不合法")
}

internal sealed class VisionReviewParseResult {
    data class Success(val value: VisionRecognitionPreview) : VisionReviewParseResult()
    data class Failure(val stage: VisionReviewFailureStage) : VisionReviewParseResult()
}

/** Model output is review input; invalid spatial evidence becomes manual work, never a default slot. */
internal object VisionReviewResponseParser {
    private val rootKeys = setOf("geometry", "candidates")
    private val geometryKeys = setOf("width", "height", "originalToWorking", "weekdays", "periods", "evidence")
    private val axisKeys = setOf("index", "start", "end", "source")
    private val candidateKeys = setOf(
        "id", "title", "day", "startPeriod", "endPeriod", "box", "evidence",
        "rawLocation", "weeks", "note", "textConfidence", "geometryConfidence"
    )
    private val boxKeys = setOf("left", "top", "right", "bottom")

    fun parse(content: String, imageWidth: Int, imageHeight: Int): VisionRecognitionPreview? =
        (parseDetailed(content, imageWidth, imageHeight) as? VisionReviewParseResult.Success)?.value

    fun parseDetailed(content: String, imageWidth: Int, imageHeight: Int): VisionReviewParseResult {
        var stage = VisionReviewFailureStage.BUDGET
        return try {
            require(content.toByteArray(Charsets.UTF_8).size <= VisionLimits.MAX_RESPONSE_BYTES)
            require(imageWidth in 1..8192 && imageHeight in 1..8192)
            stage = VisionReviewFailureStage.FORMAT
            val cleaned = unwrapFence(content)
            stage = VisionReviewFailureStage.SYNTAX
            require(VisionJsonSyntax.valid(cleaned) && cleaned.startsWith("{"))
            val raw = JSONObject(cleaned)
            stage = VisionReviewFailureStage.CANDIDATES
            val values = raw.getJSONArray("candidates")
            stage = VisionReviewFailureStage.BUDGET
            require(values.length() <= VisionLimits.MAX_CANDIDATES)
            val warnings = mutableListOf<String>()
            val geometryRaw = raw.opt("geometry")
            val geometry = onlyKeys(geometryRaw as? JSONObject ?: JSONObject(), geometryKeys, warnings)
            var manualGeometry = geometryRaw != null && geometryRaw != JSONObject.NULL && geometryRaw !is JSONObject
            if (geometryRaw == null || geometryRaw == JSONObject.NULL) {
                warnings += "未返回网格信息，需人工填写星期和节次"
            }
            // Uploaded image dimensions and omitted transforms are owned by the client.
            geometry.put("width", imageWidth).put("height", imageHeight)
            if (!geometry.has("originalToWorking") || geometry.isNull("originalToWorking")) {
                geometry.put("originalToWorking", identity())
            }
            for (key in listOf("weekdays", "periods", "evidence")) {
                if (!geometry.has(key) || geometry.isNull(key)) geometry.put(key, JSONArray())
            }
            stage = VisionReviewFailureStage.GEOMETRY
            for (key in listOf("weekdays", "periods")) {
                val axes = geometry.optJSONArray(key)
                if (axes == null) {
                    manualGeometry = true
                    continue
                }
                stage = VisionReviewFailureStage.BUDGET
                require(axes.length() <= 20)
                stage = VisionReviewFailureStage.GEOMETRY
                val normalized = JSONArray()
                for (i in 0 until axes.length()) {
                    val rawAxis = axes.optJSONObject(i)
                    if (rawAxis == null) {
                        manualGeometry = true
                        continue
                    }
                    require(rawAxis.opt("source") != "manual")
                    val axis = onlyKeys(rawAxis, axisKeys, warnings)
                    val suppliedSource = axis.opt("source")
                    if (suppliedSource != "detected") warnings += "网格轴来源已按模型检测处理"
                    axis.put("source", "detected")
                    val convertedIndex = integerLike(axis.opt("index"))
                    var convertedStart = numberLike(axis.opt("start"))
                    var convertedEnd = numberLike(axis.opt("end"))
                    val horizontal = key == "weekdays"
                    val dimension = if (horizontal) imageWidth else imageHeight
                    if (convertedStart != null && convertedEnd != null &&
                        (convertedStart !in 0.0..1.0 || convertedEnd !in 0.0..1.0)
                    ) {
                        convertedStart /= dimension.toDouble()
                        convertedEnd /= dimension.toDouble()
                        warnings += "网格轴坐标已从像素值换算为归一化坐标"
                    }
                    axis.put("index", convertedIndex ?: JSONObject.NULL)
                        .put("start", convertedStart ?: JSONObject.NULL)
                        .put("end", convertedEnd ?: JSONObject.NULL)
                    normalized.put(axis)
                }
                geometry.put(key, normalized)
            }
            if (raw.keys().asSequence().any { it !in rootKeys }) warnings += "已忽略模型附加字段"
            // A malformed grid must not prevent course text from reaching manual review.
            val geometryProbe = JSONObject().put("geometry", geometry).put("candidates", JSONArray())
            if (manualGeometry || VisionResponseParser.parse(geometryProbe.toString()) == null) {
                geometry.put("originalToWorking", identity())
                    .put("weekdays", JSONArray()).put("periods", JSONArray()).put("evidence", JSONArray())
                manualGeometry = true
                warnings += "网格信息不可靠，已清空定位信息；请人工填写星期和节次"
            }
            val candidatesJson = JSONArray()
            val manualIds = mutableSetOf<String>()
            for (i in 0 until values.length()) {
                stage = VisionReviewFailureStage.IDENTITY
                val rawCandidate = values.getJSONObject(i)
                val candidate = onlyKeys(rawCandidate, candidateKeys, warnings)
                if (!candidate.has("id") || candidate.isNull("id")) candidate.put("id", "review-" + (i + 1))
                if (!candidate.has("title") || candidate.isNull("title")) {
                    val alias = rawCandidate.opt("name").takeUnless { it == JSONObject.NULL }
                        ?: rawCandidate.opt("courseName").takeUnless { it == JSONObject.NULL }
                    candidate.put("title", alias ?: "课程名称待确认")
                    warnings += "课程名使用了模型别名或待确认占位"
                }
                if (!candidate.has("rawLocation") && rawCandidate.has("location")) {
                    candidate.put("rawLocation", rawCandidate.opt("location"))
                }
                require(candidate.opt("id") is String && candidate.getString("id").isNotBlank())
                require(candidate.opt("title") is String && candidate.getString("title").isNotBlank() &&
                    candidate.getString("title").length <= 200)
                var manualCandidate = manualGeometry
                stage = VisionReviewFailureStage.CANONICAL
                for ((key, maximum) in listOf("day" to 7, "startPeriod" to 20, "endPeriod" to 20)) {
                    val original = candidate.opt(key)
                    val number = integerLike(original)?.takeIf { it in 1..maximum }
                    if (hasValue(original) && number == null) manualCandidate = true
                    candidate.put(key, number ?: JSONObject.NULL)
                }
                val start = integerLike(candidate.opt("startPeriod"))
                val end = integerLike(candidate.opt("endPeriod"))
                if (start != null && end != null && end < start) manualCandidate = true
                val suppliedBox = candidate.opt("box")
                val box = normalizeBox(suppliedBox, imageWidth, imageHeight, warnings)
                if (hasValue(suppliedBox) && box == null) manualCandidate = true
                candidate.put("box", box ?: JSONObject.NULL)
                for (key in listOf("evidence")) {
                    val value = candidate.opt(key)
                    if (value !is JSONArray || (0 until value.length()).any { value.opt(it) !is String }) {
                        candidate.put(key, JSONArray())
                        if (hasValue(value)) warnings += "部分辅助证据无法读取，已留空"
                    }
                }
                for (key in listOf("rawLocation", "note")) {
                    val value = candidate.opt(key)
                    candidate.put(key, if (value is String) value else JSONObject.NULL)
                    if (hasValue(value) && value !is String) warnings += "部分可选文字无法读取，已留空"
                }
                val weeksRaw = candidate.opt("weeks")
                val weeks = weeksRaw as? JSONArray
                val normalizedWeeks = weeks?.let { list ->
                    (0 until list.length()).map { integerLike(list.opt(it)) }
                        .takeIf { it.all { week -> week != null && week in 1..60 } }
                        ?.filterNotNull()?.distinct()
                }
                candidate.put("weeks", normalizedWeeks?.let { JSONArray(it) } ?: JSONObject.NULL)
                if (hasValue(weeksRaw) && normalizedWeeks == null) warnings += "周次格式无法读取，需人工核对"
                for (key in listOf("textConfidence", "geometryConfidence")) {
                    val value = numberLike(candidate.opt(key))?.takeIf { it in 0.0..1.0 }
                    candidate.put(key, value ?: JSONObject.NULL)
                }
                if (candidateKeys.any { !rawCandidate.has(it) }) warnings += "部分可选信息未返回，已留空；请逐条核对"
                if (manualCandidate) {
                    candidate.put("day", JSONObject.NULL).put("startPeriod", JSONObject.NULL)
                        .put("endPeriod", JSONObject.NULL).put("box", JSONObject.NULL)
                    manualIds += candidate.getString("id")
                    warnings += "部分坐标格式或范围不可靠，已清空；请人工填写星期和节次"
                }
                candidatesJson.put(candidate)
            }
            val normalized = JSONObject().put("geometry", geometry).put("candidates", candidatesJson)
            stage = VisionReviewFailureStage.CANONICAL
            val parsed = VisionResponseParser.parse(normalized.toString()) ?: error("schema")
            val cells = parsed.candidates.associate { candidate ->
                candidate.id to candidate.box?.let { VisionGridPipeline.locateBox(it, parsed.geometry) }
            }
            val candidates = parsed.candidates.map { candidate ->
                val cell = cells[candidate.id]
                val inconsistent = candidate.box != null && (cell == null ||
                    (candidate.day != null && candidate.day != cell.day) ||
                    (candidate.startPeriod != null && candidate.startPeriod != cell.startPeriod) ||
                    (candidate.endPeriod != null && candidate.endPeriod != cell.endPeriod))
                if (inconsistent) candidate.copy(day = null, startPeriod = null, endPeriod = null) else candidate
            }
            if (candidates != parsed.candidates) warnings += "部分星期或节次缺少一致的网格依据，已清空；请人工核对"
            val preview = VisionGridPipeline.preview(parsed.geometry, candidates, null)
            val visibleCells = candidates.associate { candidate ->
                candidate.id to if (candidate.id in manualIds) null else (cells[candidate.id] ?: if (
                    candidate.day != null && candidate.startPeriod != null && candidate.endPeriod != null
                ) VisionGridCell(candidate.day, candidate.startPeriod, candidate.endPeriod) else null)
            }
            VisionReviewParseResult.Success(
                VisionRecognitionPreview(preview.copy(cells = visibleCells), candidates, (warnings + preview.warnings).distinct())
            )
        } catch (_: Exception) {
            VisionReviewParseResult.Failure(stage)
        }
    }

    private fun normalizeBox(
        value: Any?,
        imageWidth: Int,
        imageHeight: Int,
        warnings: MutableList<String>
    ): JSONObject? {
        val raw = when (value) {
            is JSONObject -> value
            is JSONArray -> if (value.length() == 4) JSONObject()
                .put("left", value.opt(0)).put("top", value.opt(1))
                .put("right", value.opt(2)).put("bottom", value.opt(3)) else return null
            else -> return null
        }
        fun rawCoordinate(name: String, fallback: String? = null): Double? =
            numberLike(raw.opt(name)) ?: fallback?.let { numberLike(raw.opt(it)) }
        val left = rawCoordinate("left", "x")
        val top = rawCoordinate("top", "y")
        val right = rawCoordinate("right") ?: rawCoordinate("x")?.let { x ->
            rawCoordinate("width")?.plus(x)
        }
        val bottom = rawCoordinate("bottom") ?: rawCoordinate("y")?.let { y ->
            rawCoordinate("height")?.plus(y)
        }
        fun scale(value: Double?, dimension: Int): Double? = value?.let {
            if (it in 0.0..1.0) it else it / dimension.toDouble()
        }
        val normalized = VisionBox(scale(left, imageWidth) ?: return null,
            scale(top, imageHeight) ?: return null,
            scale(right, imageWidth) ?: return null,
            scale(bottom, imageHeight) ?: return null)
        if (!normalized.valid()) return null
        if (listOf(left, top, right, bottom).any { it != null && it !in 0.0..1.0 }) {
            warnings += "课程框坐标已从像素值换算为归一化坐标"
        }
        return JSONObject().put("left", normalized.left).put("top", normalized.top)
            .put("right", normalized.right).put("bottom", normalized.bottom)
    }

    private fun onlyKeys(raw: JSONObject, allowed: Set<String>, warnings: MutableList<String>): JSONObject {
        if (raw.keys().asSequence().any { it !in allowed }) warnings += "已忽略模型附加字段"
        return JSONObject().apply { allowed.filter { raw.has(it) }.forEach { put(it, raw.get(it)) } }
    }

    private fun identity() = JSONArray(listOf(1, 0, 0, 0, 1, 0, 0, 0, 1))

    private fun hasValue(value: Any?) = value != null && value != JSONObject.NULL

    private fun numberLike(value: Any?): Double? = when (value) {
        is Number -> value.toDouble().takeIf { it.isFinite() }
        is String -> value.trim().toDoubleOrNull()?.takeIf { it.isFinite() }
        else -> null
    }

    private fun integerLike(value: Any?): Int? = numberLike(value)?.takeIf {
        it % 1.0 == 0.0 && it in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble()
    }?.toInt()

    private fun unwrapFence(content: String): String {
        var trimmed = content.trim().removePrefix("\uFEFF").trim()
        trimmed = stripReasoning(trimmed)
        val fenceToken = "\u0060\u0060\u0060"
        val fenceStart = trimmed.indexOf(fenceToken)
        val firstStructure = trimmed.indexOfFirst { it == '{' || it == '[' }
        val body = if (fenceStart >= 0 && (firstStructure < 0 || fenceStart < firstStructure)) {
            require(trimmed.substring(0, fenceStart).none { it == '{' || it == '[' })
            val lineEnd = trimmed.indexOf('\n', fenceStart)
            require(lineEnd >= 0)
            val fenceEnd = trimmed.lastIndexOf(fenceToken)
            require(fenceEnd > lineEnd)
            val suffix = trimmed.substring(fenceEnd + fenceToken.length)
            require(!suffix.contains(fenceToken) && suffix.none { it == '{' || it == '[' })
            trimmed.substring(lineEnd + 1, fenceEnd).trim().removePrefix("\uFEFF").trim()
        } else trimmed
        val start = body.indexOf('{')
        require(start >= 0)
        val end = findObjectEnd(body, start)
        require(end > start)
        require(body.substring(end + 1).none { it == '{' || it == '}' || it == '[' || it == ']' })
        return repairJson(body.substring(start, end + 1))
    }

    private fun stripReasoning(raw: String): String {
        var value = raw
        while (true) {
            val start = value.indexOf("<think>", ignoreCase = true)
            if (start < 0) return value
            val end = value.indexOf("</think>", startIndex = start + 7, ignoreCase = true)
            if (end < 0) return value.substring(0, start)
            value = value.removeRange(start, end + 8)
        }
    }

    /** Remove comments and trailing commas only outside JSON strings; escape raw control characters in strings. */
    private fun repairJson(raw: String): String {
        val output = StringBuilder(raw.length)
        var quoted = false
        var escaped = false
        var index = 0
        while (index < raw.length) {
            val char = raw[index]
            if (quoted) {
                if (escaped) {
                    output.append(char)
                    escaped = false
                } else when {
                    char == '\\' -> {
                        output.append(char)
                        escaped = true
                    }
                    char == '"' -> {
                        output.append(char)
                        quoted = false
                    }
                    char.code < 32 -> when (char) {
                        '\n' -> output.append("\\n")
                        '\r' -> output.append("\\r")
                        '\t' -> output.append("\\t")
                        '\b' -> output.append("\\b")
                        '\u000C' -> output.append("\\f")
                        else -> output.append(' ')
                    }
                    else -> output.append(char)
                }
                index++
                continue
            }
            when {
                char == '"' -> {
                    quoted = true
                    output.append(char)
                    index++
                }
                char == '/' && index + 1 < raw.length && raw[index + 1] == '/' -> {
                    index += 2
                    while (index < raw.length && raw[index] !in "\r\n") index++
                }
                char == '/' && index + 1 < raw.length && raw[index + 1] == '*' -> {
                    index += 2
                    while (index + 1 < raw.length && !(raw[index] == '*' && raw[index + 1] == '/')) index++
                    require(index + 1 < raw.length)
                    index += 2
                }
                char == ',' -> {
                    var next = index + 1
                    while (next < raw.length && raw[next].isWhitespace()) next++
                    if (next < raw.length && (raw[next] == '}' || raw[next] == ']')) index++
                    else {
                        output.append(char)
                        index++
                    }
                }
                else -> {
                    output.append(char)
                    index++
                }
            }
        }
        return output.toString().trim()
    }

    private fun findObjectEnd(raw: String, start: Int): Int {
        var depth = 0
        var quoted = false
        var escaped = false
        for (index in start until raw.length) {
            val char = raw[index]
            if (quoted) {
                if (escaped) escaped = false
                else if (char == '\\') escaped = true
                else if (char == '"') quoted = false
                continue
            }
            when (char) {
                '"' -> quoted = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return index
                }
            }
        }
        return -1
    }
}
