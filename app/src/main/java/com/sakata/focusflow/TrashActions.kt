package com.sakata.focusflow

internal data class TrashResult(val items: List<Item>, val events: List<TaskEvent>)

/** Tombstones remain in the selected core-data source, so process death cannot erase recovery. */
internal object TrashActions {
    fun trash(items: List<Item>, ids: Set<Long>, at: Long = System.currentTimeMillis()): TrashResult {
        val targets = items.filter { it.id in ids }
        if (ids.isEmpty() || targets.size != ids.size || at <= 0 || targets.any { item ->
                item.trashedAt != null || item.kind !in setOf("任务", "收集箱", "暂停") ||
                    item.parentCaptureId != null || items.any { it.parentCaptureId == item.id }
            }) return TrashResult(items, emptyList())
        return TrashResult(items.map { item -> if (item.id in ids) {
            item.copy(kind = "回收站", detail = "已删除", scheduledAt = null, dayOnly = false,
                windowStartAt = null, windowEndAt = null, goalId = null,
                parentCaptureId = null, repeatFrequency = "",
                trashedAt = at, trashSnapshot = ItemsCodec.encode(listOf(item)))
        } else item }, targets.map { TaskRecorder.event(TaskEventType.TASK_DELETED,
            it.id, it.title, at = at) })
    }

    fun restore(items: List<Item>, ids: Set<Long>, at: Long = System.currentTimeMillis()): TrashResult {
        val targets = items.filter { it.id in ids }
        if (ids.isEmpty() || targets.size != ids.size || targets.any { it.kind != "回收站" ||
                it.trashedAt == null || it.trashSnapshot.isNullOrBlank() }) return TrashResult(items, emptyList())
        val originals = targets.map { tombstone ->
            ItemsCodec.decode(requireNotNull(tombstone.trashSnapshot)).items.singleOrNull()
                ?.takeIf { it.id == tombstone.id && it.trashedAt == null && it.kind != "回收站" }
                ?: return TrashResult(items, emptyList())
        }.associateBy { it.id }
        return TrashResult(items.map { originals[it.id] ?: it }, targets.map {
            TaskRecorder.event(TaskEventType.TASK_RESTORED, it.id, it.title, at = at)
        })
    }

    fun originalKind(item: Item): String? = item.trashSnapshot?.let { raw ->
        ItemsCodec.decode(raw).items.singleOrNull()?.takeIf { it.id == item.id }?.kind
    }
}
