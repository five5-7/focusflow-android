package com.sakata.focusflow

import org.json.JSONArray
import org.json.JSONObject

/** baseline_events 的 JSON 边界：键名固定；条数限制由调用点处理。 */
object BaselineEventsCodec {
    fun decode(raw: String): List<BaselineEvent> = runCatching {
        val values = JSONArray(raw.ifBlank { "[]" })
        List(values.length()) { index ->
            val value = values.getJSONObject(index)
            BaselineEvent(
                id = value.getLong("id"),
                type = BaselineEventType.entries.firstOrNull { it.storageKey == value.optString("type") } ?: BaselineEventType.LIFE_STAGE_SET,
                recordedAt = value.optLong("recordedAt", 0),
                payload = value.optString("payload", "")
            )
        }
    }.getOrDefault(emptyList())

    fun encode(events: List<BaselineEvent>): String = JSONArray().apply {
        events.forEach { value -> put(JSONObject().apply {
            put("id", value.id)
            put("type", value.type.storageKey)
            put("recordedAt", value.recordedAt)
            put("payload", value.payload)
        }) }
    }.toString()
}
