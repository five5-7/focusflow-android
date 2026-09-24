package com.sakata.focusflow

internal data class WantedPlanResult(
    val items: List<Item>,
    val plans: List<Goal>,
    val events: List<TaskEvent>,
    val created: Goal?
)

internal object WantedPlanActions {
    fun create(plans: List<Goal>, title: String, notes: String = ""): Goal? {
        val name = title.trim()
        if (name.isEmpty()) return null
        val used = plans.mapTo(mutableSetOf()) { it.id }
        var id = newItemId()
        while (id in used) id = newItemId()
        return Goal(id = id, title = name, weeklyTarget = 1, durationMinutes = 30,
            metricTarget = "30 分钟", sourceNotes = notes.trim(), state = PlanState.WANTED)
    }

    /** Selected inbox records become one wanted plan; every original title and note remains readable. */
    fun fromInbox(items: List<Item>, plans: List<Goal>, selectedIds: Set<Long>): WantedPlanResult {
        val unchanged = WantedPlanResult(items, plans, emptyList(), null)
        val selected = items.filter { it.id in selectedIds }
        if (selectedIds.isEmpty() || selected.size != selectedIds.size || selected.any {
                it.done || it.kind != "收集箱" || CaptureRoute.fromKey(it.captureRoute) != CaptureRoute.INBOX ||
                    it.parentCaptureId != null || items.any { child -> child.parentCaptureId == it.id } ||
                    it.title.startsWith("重新安排：")
            }) return unchanged
        val notes = selected.joinToString("\n\n") { item ->
            val note = item.editableNote().trim()
            item.title + if (note.isBlank()) "" else "\n$note"
        }
        val plan = create(plans, selected.first().title, notes) ?: return unchanged
        return WantedPlanResult(
            items.filterNot { it.id in selectedIds },
            plans + plan,
            selected.map { TaskRecorder.event(TaskEventType.TASK_CONVERTED, it.id, it.title, extra = plan.title) },
            plan
        )
    }

    fun changeState(plan: Goal, to: PlanState): Goal? {
        if (plan.state == to || plan.state == PlanState.COMPLETED) return null
        if (plan.state == PlanState.PAUSED && to == PlanState.WANTED) return null
        return plan.copy(state = to)
    }
}
