package com.sakata.focusflow

import org.json.JSONArray
import org.json.JSONObject

data class VisionStructuredResponse(
    val geometry: VisionImageGeometry,
    val candidates: List<VisionCandidate>
)

object VisionResponseParser {
    private val geometryKeys = setOf("width", "height", "originalToWorking", "weekdays", "periods", "evidence")
    private val axisKeys = setOf("index", "start", "end", "source")
    private val candidateKeys = setOf(
        "id", "title", "day", "startPeriod", "endPeriod", "box", "evidence",
        "rawLocation", "weeks", "note", "textConfidence", "geometryConfidence"
    )
    private val boxKeys = setOf("left", "top", "right", "bottom")

    fun parse(content: String): VisionStructuredResponse? = runCatching {
        val fence = Char(96).toString().repeat(3)
        val cleaned = content.trim().removePrefix(fence + "json").removePrefix(fence).removeSuffix(fence).trim()
        if (!cleaned.startsWith("{")) return null
        require(cleaned.toByteArray(Charsets.UTF_8).size <= VisionLimits.MAX_RESPONSE_BYTES)
        require(VisionJsonSyntax.valid(cleaned))
        val root = JSONObject(cleaned)
        require(root.keys().asSequence().toSet() == setOf("geometry", "candidates"))
        val geometryObject = root.getJSONObject("geometry")
        require(geometryObject.keys().asSequence().toSet() == geometryKeys)
        val geometry = VisionImageGeometry(
            width = strictInt(geometryObject, "width") ?: error("width"),
            height = strictInt(geometryObject, "height") ?: error("height"),
            originalToWorking = doubles(geometryObject.getJSONArray("originalToWorking")),
            weekdays = axes(geometryObject.getJSONArray("weekdays")),
            periods = axes(geometryObject.getJSONArray("periods")),
            evidence = strings(geometryObject.getJSONArray("evidence"))
        )
        require(geometry.valid())
        val values = root.getJSONArray("candidates")
        require(values.length() <= VisionLimits.MAX_CANDIDATES)
        val candidates = (0 until values.length()).map {
            val objectValue = values.getJSONObject(it)
            require(objectValue.keys().asSequence().toSet() == candidateKeys)
            val boxValue = objectValue.get("box")
            val box = if (boxValue == JSONObject.NULL) null else {
                val boxObject = boxValue as JSONObject
                require(boxObject.keys().asSequence().toSet() == boxKeys)
                VisionBox(
                    number(boxObject, "left") ?: error("left"),
                    number(boxObject, "top") ?: error("top"),
                    number(boxObject, "right") ?: error("right"),
                    number(boxObject, "bottom") ?: error("bottom")
                ).also { require(it.valid()) }
            }
            VisionCandidate(
                id = strictString(objectValue, "id"),
                title = strictString(objectValue, "title"),
                day = nullableInt(objectValue, "day"),
                startPeriod = nullableInt(objectValue, "startPeriod"),
                endPeriod = nullableInt(objectValue, "endPeriod"),
                box = box,
                evidence = strings(objectValue.getJSONArray("evidence")),
                rawLocation = nullableString(objectValue, "rawLocation"),
                weeks = nullableInts(objectValue, "weeks"),
                note = nullableString(objectValue, "note"),
                textConfidence = nullableNumber(objectValue, "textConfidence"),
                geometryConfidence = nullableNumber(objectValue, "geometryConfidence")
            ).also { require(it.valid()) }
        }.also { require(it.map { candidate -> candidate.id }.distinct().size == it.size) }
        VisionStructuredResponse(geometry, candidates)
    }.getOrNull()

    private fun axes(array: JSONArray): List<VisionGridAxis> {
        require(array.length() <= 20)
        return (0 until array.length()).map {
        val value = array.getJSONObject(it)
        require(value.keys().asSequence().toSet() == axisKeys)
        VisionGridAxis(
            index = strictInt(value, "index") ?: error("index"),
            start = number(value, "start") ?: error("start"),
            end = number(value, "end") ?: error("end"),
            source = strictString(value, "source")
        )
    }

    }

    private fun strictString(value: JSONObject, key: String): String {
        val item = value.get(key)
        require(item is String)
        return item
    }

    private fun strings(array: JSONArray): List<String> = (0 until array.length()).map {
        array.get(it).also { value -> require(value is String) }.toString()
    }

    private fun doubles(array: JSONArray): List<Double> = (0 until array.length()).map {
        val value = array.get(it)
        require(value is Number)
        value.toDouble()
    }

    private fun nullableInts(value: JSONObject, key: String): List<Int>? {
        if (value.isNull(key)) return null
        val array = value.getJSONArray(key)
        return (0 until array.length()).map {
            val item = array.get(it)
            require(item is Number)
            val number = item.toDouble()
            require(number.isFinite() && number in 1.0..60.0 && number % 1.0 == 0.0)
            number.toInt()
        }
    }

    private fun nullableString(value: JSONObject, key: String): String? =
        if (value.isNull(key)) null else value.get(key).also { require(it is String) }.toString()

    private fun nullableInt(value: JSONObject, key: String): Int? =
        if (value.isNull(key)) null else strictInt(value, key)

    private fun strictInt(value: JSONObject, key: String): Int? {
        val item = value.get(key)
        if (item !is Number) return null
        val double = item.toDouble()
        return double.takeIf { it.isFinite() && it in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble() && it % 1.0 == 0.0 }?.toInt()
    }

    private fun nullableNumber(value: JSONObject, key: String): Double? =
        if (value.isNull(key)) null else number(value, key)

    private fun number(value: JSONObject, key: String): Double? {
        val item = value.get(key)
        if (item !is Number) return null
        return item.toDouble().takeIf { it.isFinite() }
    }
}


/**
 * 失败诊断只保留形状信息，不保留模型原文、课程名、地点、key 或图片内容。
 * 用于真机把“模型没有遵守 schema”和“客户端解析形状不匹配”区分开。
 */
internal object VisionResponseDiagnostics {
    fun summarize(content: String): String {
        val bytes = content.toByteArray(Charsets.UTF_8)
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            .take(12)
        val cleaned = unwrapFence(content)
        val syntax = VisionJsonSyntax.valid(cleaned)
        val root = runCatching { JSONObject(cleaned) }.getOrNull()
        val shape = when {
            cleaned.isBlank() -> "empty"
            !syntax -> "invalid-json"
            root == null -> "not-object"
            else -> {
                val keys = safeKeys(root.keys().asSequence().toList())
                val geometry = root.optJSONObject("geometry")
                val geometryKeys = geometry?.let { safeKeys(it.keys().asSequence().toList()) } ?: "-"
                val candidates = when (val value = root.opt("candidates")) {
                    is JSONArray -> "array:${value.length()}"
                    is JSONObject -> "object"
                    JSONObject.NULL, null -> "missing"
                    else -> value.javaClass.simpleName.lowercase()
                }
                "object rootKeys=[$keys] geometryKeys=[$geometryKeys] candidates=$candidates"
            }
        }
        return "bytes=${bytes.size} sha256=$digest syntax=$syntax $shape"
    }

    private fun unwrapFence(content: String): String {
        val trimmed = content.trim()
        if (!trimmed.startsWith("```")) return trimmed
        val lines = trimmed.lines()
        if (lines.size < 2) return trimmed
        val end = if (lines.last().trim() == "```") lines.lastIndex else lines.size
        return lines.subList(1, end).joinToString("\n").trim()
    }

    private fun safeKeys(keys: List<String>): String = keys.take(12).joinToString(",") { key ->
        if (key.length <= 40 && key.all { it.isLetterOrDigit() || it in "_.-" }) key else "[key]"
    }
}