package com.sakata.focusflow

import org.json.JSONArray
import org.json.JSONObject

/** improvement_notes 的 JSON 边界：键名固定；条数限制由调用点处理。 */
object ImprovementNoteCodec {
    fun decode(raw: String): List<ImprovementNote> = runCatching {
        val values = JSONArray(raw.ifBlank { "[]" })
        List(values.length()) { index ->
            val note = values.getJSONObject(index)
            ImprovementNote(note.getLong("id"), note.getString("text"), note.getLong("createdAt"))
        }
    }.getOrDefault(emptyList())

    fun encode(notes: List<ImprovementNote>): String = JSONArray().apply {
        notes.forEach { note -> put(JSONObject().apply {
            put("id", note.id); put("text", note.text); put("createdAt", note.createdAt)
        }) }
    }.toString()
}
