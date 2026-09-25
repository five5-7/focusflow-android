package com.sakata.focusflow

import java.util.Calendar

internal enum class TodoBatchAction(val label: String) {
    COMPLETE("完成所选"),
    MOVE_DATE("批量改期"),
    CLEAR_TIME("改为未安排"),
    DELETE("删除所选")
}

internal data class TodoBatchResult(
    val items: List<Item>,
    val events: List<TaskEvent>,
    val before: List<Item>,
    val affectedBefore: List<Item>,
    val affectedAfter: List<Item>,
    val action: TodoBatchAction
)

internal object TodoBatchActions {
    fun apply(items: List<Item>, ids: Set<Long>, action: TodoBatchAction, at: Long = System.currentTimeMillis(),
              targetDay: Long? = null, keepTime: Boolean = true): TodoBatchResult {
        val none = TodoBatchResult(items, emptyList(), items, emptyList(), emptyList(), action)
        val selected = items.filter { it.id in ids }
        // Goal-linked tasks need their completion-level and weekly-count path; keep that path intact.
        if ((action == TodoBatchAction.MOVE_DATE && (targetDay == null || targetDay <= 0L)) ||
            ids.isEmpty() || selected.size != ids.size || selected.any {
                it.kind != "任务" || it.done || it.goalId != null || it.parentCaptureId != null ||
                    items.any { child -> child.parentCaptureId == it.id } ||
                    (action == TodoBatchAction.CLEAR_TIME && it.scheduledAt == null)
            }) return none
        val changed = when (action) {
            TodoBatchAction.COMPLETE -> items.map { if (it.id in ids) it.copy(done = true, completionLevel = "完成", completedAt = at) else it }
            TodoBatchAction.MOVE_DATE -> items.map { item -> if (item.id in ids) {
                val oldAt = item.scheduledAt
                val date = TaskHistory.dayStartOf(requireNotNull(targetDay))
                val wasTimed = keepTime && oldAt != null && !item.dayOnly
                val scheduled = if (wasTimed) {
                    val from = Calendar.getInstance().apply { timeInMillis = requireNotNull(oldAt) }
                    Calendar.getInstance().apply {
                        timeInMillis = date
                        set(Calendar.HOUR_OF_DAY, from.get(Calendar.HOUR_OF_DAY))
                        set(Calendar.MINUTE, from.get(Calendar.MINUTE))
                        set(Calendar.SECOND, from.get(Calendar.SECOND))
                        set(Calendar.MILLISECOND, 0)
                    }.timeInMillis
                } else date
                item.preservingNote().copy(scheduledAt = scheduled, dayOnly = !wasTimed,
                    windowStartAt = null, windowEndAt = null,
                    detail = if (wasTimed) TaskScheduleText.scheduledDetail(scheduled, item.durationMinutes)
                        else TaskScheduleText.dayOnlyDetail(scheduled),
                    rescheduleCount = item.rescheduleCount + 1, lastRescheduledAt = at)
            } else item }
            TodoBatchAction.CLEAR_TIME -> items.map { if (it.id in ids) it.preservingNote().copy(
                scheduledAt = null, dayOnly = false, windowStartAt = null, windowEndAt = null,
                recoverySourceScheduledAt = it.recoverySourceScheduledAt ?: it.scheduledAt,
                detail = "尚未安排具体时间"
            ) else it }
            TodoBatchAction.DELETE -> items.filterNot { it.id in ids }
        }
        val type = when (action) {
            TodoBatchAction.COMPLETE -> TaskEventType.TASK_COMPLETED
            TodoBatchAction.MOVE_DATE -> TaskEventType.TASK_RESCHEDULED
            TodoBatchAction.CLEAR_TIME -> TaskEventType.TASK_UNSCHEDULED
            TodoBatchAction.DELETE -> TaskEventType.TASK_DELETED
        }
        return TodoBatchResult(changed, selected.map {
            TaskRecorder.event(type, it.id, it.title, extra = action.label, at = at)
        }, items, selected, changed.filter { it.id in ids }, action)
    }

    /** Undo only while selected records still match the exact result of this batch. */
    fun undo(current: List<Item>, result: TodoBatchResult, at: Long = System.currentTimeMillis()): Pair<List<Item>, List<TaskEvent>> {
        if (result.events.isEmpty()) return current to emptyList()
        val ids = result.affectedBefore.mapTo(mutableSetOf()) { it.id }
        if (result.action == TodoBatchAction.DELETE) {
            if (current.any { it.id in ids }) return current to emptyList()
            val restored = InboxBatchActions.undoDelete(current, result.before, result.affectedBefore, at, "撤回批量删除")
            return restored.items to restored.events
        }
        if (result.affectedAfter.any { after -> current.firstOrNull { it.id == after.id } != after }) {
            return current to emptyList()
        }
        val originals = result.affectedBefore.associateBy { it.id }
        val restored = current.map { originals[it.id] ?: it }
        val events = result.affectedBefore.map { before ->
            if (result.action == TodoBatchAction.COMPLETE)
                TaskRecorder.event(TaskEventType.TASK_UNCOMPLETED, before.id, before.title, extra = "撤回批量完成", at = at)
            else TaskRecorder.event(if (before.scheduledAt == null) TaskEventType.TASK_UNSCHEDULED else TaskEventType.TASK_SCHEDULED,
                before.id, before.title, scheduledAt = before.scheduledAt ?: 0L,
                extra = if (result.action == TodoBatchAction.MOVE_DATE) "撤回批量改期" else "撤回改为未安排", at = at)
        }
        return restored to events
    }
}
