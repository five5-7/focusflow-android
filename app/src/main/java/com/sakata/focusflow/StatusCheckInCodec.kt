package com.sakata.focusflow

import org.json.JSONArray
import org.json.JSONObject

/** status_checkins 的 JSON 边界：键名与取值范围固定（旧存档兼容）；条数限制由调用点处理。 */
object StatusCheckInCodec {
    fun decode(raw: String): List<StatusCheckIn> = runCatching {
        val values = JSONArray(raw.ifBlank { "[]" })
        List(values.length()) { index ->
            val value = values.getJSONObject(index)
            StatusCheckIn(
                energy = value.optString("energy", "正常").takeIf { it in StatusCheckInCatalog.energies } ?: "正常",
                activity = value.optString("activity", "其他").takeIf { it in StatusCheckInCatalog.activities } ?: "其他",
                recordedAt = value.optLong("recordedAt", System.currentTimeMillis())
            )
        }
    }.getOrDefault(emptyList())

    fun encode(checkIns: List<StatusCheckIn>): String = JSONArray().apply {
        checkIns.forEach { value -> put(JSONObject().apply {
            put("energy", value.energy)
            put("activity", value.activity)
            put("recordedAt", value.recordedAt)
        }) }
    }.toString()
}
