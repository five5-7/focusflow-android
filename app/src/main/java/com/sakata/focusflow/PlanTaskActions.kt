package com.sakata.focusflow

internal object PlanTaskActions {
    fun move(items: List<Item>, plan: Goal, task: Item, bucket: String): List<Item>? {
        if (plan.state != PlanState.IN_PROGRESS || plan.weeklyTarget != 0 || bucket !in setOf("near", "later") ||
            task.goalId != plan.id || task.kind != "任务" || task.done || items.none { it == task }) return null
        return items.map { if (it.id == task.id) it.copy(planBucket = bucket, planFocus = it.planFocus && bucket == "near") else it }
    }

    fun focus(items: List<Item>, plan: Goal, task: Item?): List<Item>? {
        if (plan.state != PlanState.IN_PROGRESS || plan.weeklyTarget != 0 ||
            (task != null && (task.goalId != plan.id || task.kind != "任务" || task.done || task.planBucket != "near" || items.none { it == task }))) return null
        return items.map { if (it.goalId == plan.id) it.copy(planFocus = it.id == task?.id) else it }
    }
}
