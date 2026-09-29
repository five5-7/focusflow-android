package com.sakata.focusflow

import org.json.JSONArray
import org.json.JSONObject

internal object ChecklistCodec {
    fun encodeArray(entries: List<ChecklistEntry>): JSONArray = JSONArray().apply {
        entries.forEach { put(JSONObject().put("id", it.id).put("title", it.title).put("done", it.done)) }
    }

    fun decode(array: JSONArray?): List<ChecklistEntry> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val value = array.optJSONObject(index) ?: return@mapNotNull null
            val id = value.optLong("id")
            val title = value.optString("title").trim()
            if (id <= 0 || title.isBlank()) null else ChecklistEntry(id, title, value.optBoolean("done"))
        }.distinctBy { it.id }
    }

    fun decode(raw: String): List<ChecklistEntry> = runCatching { decode(JSONArray(raw)) }.getOrDefault(emptyList())
}

internal object ChecklistActions {
    fun add(item: Item, lines: String): Item? {
        if (item.kind != "任务" || item.done) return null
        val titles = lines.lines().map(String::trim).filter(String::isNotBlank)
        if (titles.isEmpty() || item.checklist.size + titles.size > 50 || titles.any { it.length > 200 }) return null
        val used = item.checklist.mapTo(mutableSetOf()) { it.id }
        return item.copy(checklist = item.checklist + titles.map { title ->
            var id = newItemId()
            while (!used.add(id)) id = newItemId()
            ChecklistEntry(id, title)
        })
    }

    fun toggle(item: Item, id: Long): Item? {
        if (item.kind != "任务" || item.done || item.checklist.none { it.id == id }) return null
        return item.copy(checklist = item.checklist.map { if (it.id == id) it.copy(done = !it.done) else it })
    }
}
