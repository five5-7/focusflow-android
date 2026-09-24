package com.sakata.focusflow

internal data class CreatedTodo(val items: List<Item>, val item: Item?, val event: TaskEvent?)

internal object TodoActions {
    fun create(items: List<Item>, title: String, at: Long = System.currentTimeMillis()): CreatedTodo {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return CreatedTodo(items, null, null)
        val item = Item(title = trimmed, detail = "尚未安排具体时间", kind = "任务")
        return CreatedTodo(
            listOf(item) + items,
            item,
            TaskRecorder.event(TaskEventType.TASK_CREATED, item.id, item.title, at = at)
        )
    }
}
