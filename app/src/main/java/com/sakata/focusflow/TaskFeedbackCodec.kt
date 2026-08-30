package com.sakata.focusflow

import org.json.JSONArray
import org.json.JSONObject

/** feedback 的 JSON 边界：键名固定；条数限制由调用点处理。 */
object TaskFeedbackCodec {
    fun decode(raw: String): List<TaskFeedback> = runCatching {
        val values = JSONArray(raw.ifBlank { "[]" })
        List(values.length()) { index ->
            val feedback = values.getJSONObject(index)
            TaskFeedback(feedback.getLong("id"), feedback.getLong("goalId"), feedback.getString("completionLevel"), feedback.getString("difficulty"), feedback.getString("barrier"), feedback.getLong("createdAt"))
        }
    }.getOrDefault(emptyList())

    fun encode(records: List<TaskFeedback>): String = JSONArray().apply {
        records.forEach { value -> put(JSONObject().apply {
            put("id", value.id); put("goalId", value.goalId); put("completionLevel", value.completionLevel); put("difficulty", value.difficulty); put("barrier", value.barrier); put("createdAt", value.createdAt)
        }) }
    }.toString()
}
