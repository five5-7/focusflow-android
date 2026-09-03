package com.sakata.focusflow

import org.json.JSONArray
import org.json.JSONObject

object SleepSummaryCodec {
    fun decode(raw: String): List<SleepSummary> = runCatching {
        val values = JSONArray(raw.ifBlank { "[]" })
        List(values.length()) { index ->
            val value = values.getJSONObject(index)
            SleepSummary(
                startAt = value.getLong("startAt"),
                endAt = value.getLong("endAt"),
                durationMinutes = value.getInt("durationMinutes"),
                sourcePackage = value.optString("sourcePackage", ""),
                syncedAt = value.optLong("syncedAt", 0L)
            )
        }.filter { it.startAt > 0L && it.endAt > it.startAt && it.durationMinutes > 0 }
    }.getOrDefault(emptyList())

    fun encode(values: List<SleepSummary>): String = JSONArray().apply {
        values.forEach { value -> put(JSONObject().apply {
            put("startAt", value.startAt)
            put("endAt", value.endAt)
            put("durationMinutes", value.durationMinutes)
            put("sourcePackage", value.sourcePackage)
            put("syncedAt", value.syncedAt)
        }) }
    }.toString()
}
