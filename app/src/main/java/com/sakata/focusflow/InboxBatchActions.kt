package com.sakata.focusflow

internal enum class InboxBatchAction(val label: String) {
    TO_TASK("转待办"),
    REFERENCE("留作参考"),
    KEEP("暂时保留"),
    DELETE("删除")
}

internal data class InboxBatchResult(
    val items: List<Item>,
    val events: List<TaskEvent>,
    val affected: List<Item>
)

internal object InboxBatchActions {
    fun apply(
        items: List<Item>,
        selectedIds: Set<Long>,
        action: InboxBatchAction,
        at: Long = System.currentTimeMillis()
    ): InboxBatchResult {
        val targets = items.filter { it.id in selectedIds }
        if (selectedIds.isEmpty() || targets.size != selectedIds.size || targets.any {
                it.done || it.kind != "收集箱" || CaptureRoute.fromKey(it.captureRoute) != CaptureRoute.INBOX ||
                    it.title.startsWith("重新安排：")
            }) return InboxBatchResult(items, emptyList(), emptyList())

        val changed = when (action) {
            InboxBatchAction.TO_TASK -> items.map { item ->
                if (item.id in selectedIds) item.preservingNote().copy(
                    kind = "任务", detail = "尚未安排具体时间", scheduledAt = null,
                    dayOnly = false, windowStartAt = null, windowEndAt = null
                ) else item
            }
            InboxBatchAction.REFERENCE -> items.map { item ->
                if (item.id in selectedIds) item.preservingNote().copy(
                    kind = "收集箱", captureRoute = CaptureRoute.REFERENCE.storageKey,
                    sourceDetail = item.sourceDetail.ifBlank { item.detail },
                    nextAction = "", parentCaptureId = null, scheduledAt = null,
                    dayOnly = false, windowStartAt = null, windowEndAt = null, goalId = null
                ) else if (item.parentCaptureId in selectedIds) item.copy(parentCaptureId = null) else item
            }
            InboxBatchAction.KEEP -> items
            InboxBatchAction.DELETE -> targets.fold(items) { current, item ->
                TaskActions.deleteItem(current, item).items
            }
        }
        val events = targets.map { item ->
            val type = if (action == InboxBatchAction.DELETE) TaskEventType.TASK_DELETED else TaskEventType.CAPTURE_ROUTED
            TaskRecorder.event(type, item.id, item.title, extra = action.label, at = at)
        }
        return InboxBatchResult(changed, events, targets)
    }

    fun undoDelete(current: List<Item>, original: List<Item>, deleted: List<Item>, at: Long = System.currentTimeMillis(), extra: String = "撤回批量删除"): InboxBatchResult {
        val deletedIds = deleted.mapTo(mutableSetOf()) { it.id }
        val restored = current.toMutableList()
        original.withIndex().filter { it.value.id in deletedIds }.asReversed().forEach { (originalIndex, item) ->
            if (restored.any { it.id == item.id }) return@forEach
            val nextId = original.drop(originalIndex + 1).firstOrNull { next -> restored.any { it.id == next.id } }?.id
            val insertAt = nextId?.let { id -> restored.indexOfFirst { it.id == id } } ?: restored.size
            restored.add(insertAt, item)
        }
        val actuallyRestored = deleted.filter { item -> current.none { it.id == item.id } }
        val originalChildren = original.filter { it.parentCaptureId in deletedIds && it.id !in deletedIds }
        originalChildren.forEach { child ->
            val index = restored.indexOfFirst { it.id == child.id }
            if (index >= 0 && restored[index] == child.copy(parentCaptureId = null)) restored[index] = child
        }
        return InboxBatchResult(restored, actuallyRestored.map {
            TaskRecorder.event(TaskEventType.TASK_RESTORED, it.id, it.title, extra = extra, at = at)
        }, actuallyRestored)
    }
}
