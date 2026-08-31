package com.sakata.focusflow

import org.json.JSONArray
import org.json.JSONObject

/**
 * items 的 JSON 边界：所有字段键名固定于此，UI/持久层重构不得更改（旧存档兼容）。
 * 解码含 id 冲突归一化：重复/非正 id 换新 id 并通过 idsNormalized 告知调用点回存。
 */
object ItemsCodec {
    data class DecodeResult(val items: List<Item>, val idsNormalized: Boolean)

    fun decode(raw: String): DecodeResult = runCatching {
        val values = JSONArray(raw.ifBlank { "[]" })
        val parsed = List(values.length()) { index ->
            val item = values.getJSONObject(index)
            Item(
                id = item.getLong("id"), title = item.getString("title"), detail = item.getString("detail"), kind = item.getString("kind"),
                done = item.optBoolean("done"), scheduledAt = item.optLong("scheduledAt").takeIf { it > 0 }, dayOnly = item.optBoolean("dayOnly"),
                goalId = item.optLong("goalId").takeIf { it > 0 }, completionLevel = item.optString("completionLevel"), completedAt = item.optLong("completedAt").takeIf { it > 0 },
                durationMinutes = item.optInt("durationMinutes", 60).coerceIn(5, 360), windowStartAt = item.optLong("windowStartAt").takeIf { it > 0 }, windowEndAt = item.optLong("windowEndAt").takeIf { it > 0 },
                rescheduleCount = item.optInt("rescheduleCount", 0).coerceAtLeast(0), lastRescheduledAt = item.optLong("lastRescheduledAt").takeIf { it > 0 },
                recoverySourceScheduledAt = item.optLong("recoverySourceScheduledAt").takeIf { it > 0 },
                priority = ItemPriority.fromKey(item.optString("priority")).storageKey
            )
        }
        val firstByOriginalId = mutableMapOf<Long, Item>()
        val assignedIds = mutableSetOf<Long>()
        val normalized = parsed.map { item ->
            val first = firstByOriginalId.putIfAbsent(item.id, item)
            if (first == null && item.id > 0 && assignedIds.add(item.id)) {
                item
            } else {
                var replacementId = newItemId()
                while (!assignedIds.add(replacementId)) replacementId = newItemId()
                val accidentallyCompletedTogether = first?.let {
                    it.done && item.done && it.completedAt != null && it.completedAt == item.completedAt
                } == true
                item.copy(
                    id = replacementId,
                    done = if (accidentallyCompletedTogether) false else item.done,
                    completionLevel = if (accidentallyCompletedTogether) "" else item.completionLevel,
                    completedAt = if (accidentallyCompletedTogether) null else item.completedAt
                )
            }
        }
        DecodeResult(normalized, normalized != parsed)
    }.getOrDefault(DecodeResult(emptyList(), false))

    fun encode(items: List<Item>): String = JSONArray().apply {
        items.forEach { item -> put(JSONObject().apply {
            put("id", item.id); put("title", item.title); put("detail", item.detail); put("kind", item.kind); put("done", item.done); put("scheduledAt", item.scheduledAt ?: 0); put("dayOnly", item.dayOnly); put("goalId", item.goalId ?: 0); put("completionLevel", item.completionLevel); put("completedAt", item.completedAt ?: 0); put("durationMinutes", item.durationMinutes); put("windowStartAt", item.windowStartAt ?: 0); put("windowEndAt", item.windowEndAt ?: 0); put("rescheduleCount", item.rescheduleCount); put("lastRescheduledAt", item.lastRescheduledAt ?: 0); put("recoverySourceScheduledAt", item.recoverySourceScheduledAt ?: 0); put("priority", item.priority)
        }) }
    }.toString()
}
