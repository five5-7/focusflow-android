package com.sakata.focusflow

import org.json.JSONArray
import org.json.JSONObject

/** game_sessions 的 JSON 边界：键名固定（旧存档兼容）；条数限制由调用点处理。 */
object GameSessionsCodec {
    fun decode(raw: String): List<GameSessionRecord> = runCatching {
        val values = JSONArray(raw.ifBlank { "[]" })
        List(values.length()) { index ->
            val session = values.getJSONObject(index)
            GameSessionRecord(
                id = session.getLong("id"),
                title = session.getString("title"),
                category = session.optString("category", "游戏").takeIf { it.isNotBlank() } ?: "游戏",
                packageName = session.optString("packageName", "").takeIf { it.isNotBlank() },
                plannedStartAt = session.getLong("plannedStartAt"),
                plannedEndAt = session.getLong("plannedEndAt"),
                actualEndAt = if (session.has("actualEndAt") && !session.isNull("actualEndAt")) session.getLong("actualEndAt") else null,
                endedOnTime = session.optBoolean("endedOnTime", false),
                overrunMinutes = session.optInt("overrunMinutes", 0),
                remindStart = session.optBoolean("remindStart", false)
            )
        }
    }.getOrDefault(emptyList())

    fun encode(sessions: List<GameSessionRecord>): String = JSONArray().apply {
        sessions.forEach { session -> put(JSONObject().apply {
            put("id", session.id); put("title", session.title); put("category", session.category); put("packageName", session.packageName ?: ""); put("plannedStartAt", session.plannedStartAt); put("plannedEndAt", session.plannedEndAt)
            session.actualEndAt?.let { put("actualEndAt", it) }
            put("endedOnTime", session.endedOnTime); put("overrunMinutes", session.overrunMinutes); put("remindStart", session.remindStart)
        }) }
    }.toString()
}
