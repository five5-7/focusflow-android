package com.sakata.focusflow

import org.json.JSONArray
import org.json.JSONObject

/** meal_records 的 JSON 边界：键名固定；条数限制由调用点处理。 */
object MealRecordsCodec {
    fun decode(raw: String): List<MealRecord> = runCatching {
        val values = JSONArray(raw.ifBlank { "[]" })
        List(values.length()) { index ->
            val value = values.getJSONObject(index)
            MealRecord(
                id = value.getLong("id"),
                mealType = MealType.fromLabel(value.optString("mealType", "")) ?: MealType.LUNCH,
                lifeStage = value.optString("lifeStage", ""),
                startedAt = value.optLong("startedAt", 0),
                endedAt = value.optLong("endedAt").takeIf { it > 0 },
                location = value.optString("location", ""),
                category = value.optString("category", ""),
                merchant = value.optString("merchant", ""),
                amount = value.optInt("amount", -1),
                payMethod = value.optString("payMethod", ""),
                rating = value.optInt("rating", 0).coerceIn(0, 5),
                note = value.optString("note", ""),
                recordedAt = value.optLong("recordedAt", value.optLong("startedAt", 0))
            )
        }
    }.getOrDefault(emptyList())

    fun encode(records: List<MealRecord>): String = JSONArray().apply {
        records.forEach { value -> put(JSONObject().apply {
            put("id", value.id)
            put("mealType", value.mealType.label)
            put("lifeStage", value.lifeStage)
            put("startedAt", value.startedAt)
            put("endedAt", value.endedAt ?: 0)
            put("location", value.location)
            put("category", value.category)
            put("merchant", value.merchant)
            put("amount", value.amount)
            put("payMethod", value.payMethod)
            put("rating", value.rating)
            put("note", value.note)
            put("recordedAt", value.recordedAt)
        }) }
    }.toString()
}
