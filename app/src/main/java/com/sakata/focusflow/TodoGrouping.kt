package com.sakata.focusflow

internal data class TodoGroups(
    val overdue: List<Item>,
    val scheduled: List<Item>,
    val unscheduled: List<Item>,
    val completed: List<Item>
) {
    val pendingCount: Int get() = overdue.size + scheduled.size + unscheduled.size
}

internal fun groupTodos(items: List<Item>, now: Long): TodoGroups {
    val today = TaskHistory.dayStartOf(now)
    val overdue = mutableListOf<Item>()
    val scheduled = mutableListOf<Item>()
    val unscheduled = mutableListOf<Item>()
    val completed = mutableListOf<Item>()
    items.filter { it.kind == "任务" }.forEach { item ->
        when {
            item.done -> completed += item
            item.dueAt != null && TaskHistory.dayStartOf(item.dueAt) < today -> overdue += item
            item.scheduledAt == null -> unscheduled += item
            item.dayOnly && TaskHistory.dayStartOf(item.scheduledAt) < today -> overdue += item
            !item.dayOnly && item.scheduledAt < now -> overdue += item
            else -> scheduled += item
        }
    }
    return TodoGroups(
        overdue.sortedBy { it.dueAt?.takeIf { due -> due < today } ?: it.scheduledAt },
        scheduled.sortedBy { it.scheduledAt },
        unscheduled.sortedWith(compareBy<Item> { it.dueAt ?: Long.MAX_VALUE }),
        completed.sortedByDescending { it.completedAt ?: 0L }
    )
}
