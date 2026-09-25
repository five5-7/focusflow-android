package com.sakata.focusflow

internal data class CreatedTodo(val items: List<Item>, val item: Item?, val event: TaskEvent?)
internal data class CreatedTodos(val items: List<Item>, val created: List<Item>, val events: List<TaskEvent>)

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

    /** Each nonblank pasted line is a separate one-off task. No partial save on invalid input. */
    fun createLines(
        items: List<Item>, text: String, dateOnlyAt: Long? = null,
        at: Long = System.currentTimeMillis()
    ): CreatedTodos {
        val titles = text.lines().map(String::trim).filter(String::isNotBlank)
        if (titles.isEmpty() || titles.size > 50 || titles.any { it.length > 200 } ||
            (dateOnlyAt != null && dateOnlyAt <= 0L)) return CreatedTodos(items, emptyList(), emptyList())
        val used = items.mapTo(mutableSetOf()) { it.id }
        val date = dateOnlyAt?.let(TaskHistory::dayStartOf)
        val created = titles.map { title ->
            var id = newItemId()
            while (!used.add(id)) id = newItemId()
            Item(id = id, title = title, kind = "任务", scheduledAt = date, dayOnly = date != null,
                detail = date?.let(TaskScheduleText::dayOnlyDetail) ?: "尚未安排具体时间")
        }
        return CreatedTodos(created + items, created, created.map {
            TaskRecorder.event(TaskEventType.TASK_CREATED, it.id, it.title,
                scheduledAt = it.scheduledAt ?: 0, at = at)
        })
    }

    /** First line is the task title; the rest become steps on that same task. */
    fun createChecklist(items: List<Item>, text: String, dateOnlyAt: Long? = null,
                        at: Long = System.currentTimeMillis()): CreatedTodos {
        val titles = text.lines().map(String::trim).filter(String::isNotBlank)
        if (titles.size < 2 || titles.size > 51 || titles.any { it.length > 200 })
            return CreatedTodos(items, emptyList(), emptyList())
        val created = createLines(items, titles.first(), dateOnlyAt, at)
        val task = created.created.singleOrNull() ?: return CreatedTodos(items, emptyList(), emptyList())
        val withSteps = ChecklistActions.add(task, titles.drop(1).joinToString("\n"))
            ?: return CreatedTodos(items, emptyList(), emptyList())
        return CreatedTodos(created.items.map { if (it.id == task.id) withSteps else it }, listOf(withSteps), created.events)
    }
}
