package com.sakata.focusflow

import android.content.Context
import com.sakata.focusflow.data.CoreDataReadResult
import com.sakata.focusflow.data.CoreDataRuntimeAccess
import com.sakata.focusflow.data.CoreDataRuntimeResolution
import java.util.Calendar

internal data class RepeatResult(val items: List<Item>, val events: List<TaskEvent>)

/** A template holds the rule; individual dated tasks keep independent completion and history. */
internal object RepeatActions {
    fun refreshRepository(context: Context): Boolean {
        val runtime = CoreDataRuntimeAccess.resolve(context) as? CoreDataRuntimeResolution.Ready ?: return false
        val snapshot = (runtime.repository.read() as? CoreDataReadResult.Ready)?.snapshot ?: return false
        val result = refresh(snapshot.items, courses = snapshot.courses)
        if (result.events.isEmpty()) return true
        if (!runtime.repository.replaceTasksAndAppendEvents(result.items, result.events, snapshot.items).applied)
            return false
        ReminderScheduler.syncTaskReminders(context, snapshot.items, result.items)
        return true
    }
    fun create(items: List<Item>, title: String, frequency: String, startDay: Long,
               minute: Int = -1, at: Long = System.currentTimeMillis(), courses: List<Course> = emptyList()): RepeatResult {
        val name = title.trim()
        if (name.isBlank() || name.length > 200 || frequency !in setOf("daily", "weekly", "class_day") ||
            startDay <= 0 || minute !in -1..1439) return RepeatResult(items, emptyList())
        val used = items.mapTo(mutableSetOf()) { it.id }
        var id = newItemId()
        while (!used.add(id)) id = newItemId()
        val template = Item(id = id, title = name, kind = "重复模板",
            detail = when (frequency) { "daily" -> "每天重复"; "weekly" -> "每周重复"; else -> "有课日重复" },
            repeatFrequency = frequency, repeatStartDay = TaskHistory.dayStartOf(startDay), repeatMinute = minute)
        val added = refresh(listOf(template) + items, at, courses)
        return RepeatResult(added.items, listOf(TaskRecorder.event(TaskEventType.TASK_CREATED, id, name, at = at)) + added.events)
    }

    /** Never backfill unattended days. Preserve one history row per missed date and generate one next instance. */
    fun refresh(items: List<Item>, at: Long = System.currentTimeMillis(), courses: List<Course> = emptyList()): RepeatResult {
        val today = TaskHistory.dayStartOf(at)
        var current = items
        val events = mutableListOf<TaskEvent>()
        val usedIds = items.mapTo(mutableSetOf()) { it.id }
        items.filter { it.kind == "重复模板" && !it.repeatPaused && it.repeatStartDay != null &&
            it.repeatFrequency in setOf("daily", "weekly", "class_day") }
            .forEach { template ->
                if (template.repeatFrequency == "class_day") {
                    val inactive = current.filter { it.repeatTemplateId == template.id && it.kind == "任务" && !it.done &&
                        it.repeatOccurrenceDay != null && it.repeatOccurrenceDay >= today &&
                        it.scheduledAt?.let(TaskHistory::dayStartOf) == it.repeatOccurrenceDay &&
                        coursesOn(it.repeatOccurrenceDay, courses).isEmpty() }
                    if (inactive.isNotEmpty()) {
                        val ids = inactive.mapTo(mutableSetOf()) { it.id }
                        current = current.map { item -> if (item.id in ids) item.preservingNote().copy(
                            kind = "重复历史", detail = "当天没有生效课程，条件未满足", scheduledAt = null,
                            dayOnly = false, windowStartAt = null, windowEndAt = null) else item }
                        inactive.forEach { events += TaskRecorder.event(TaskEventType.TASK_UNSCHEDULED,
                            it.id, it.title, extra = "有课日条件不满足", scheduledAt = it.scheduledAt ?: 0L, at = at) }
                    }
                }
                val expired = current.filter { it.repeatTemplateId == template.id && it.kind == "任务" && !it.done &&
                    (it.scheduledAt?.let(TaskHistory::dayStartOf) ?: it.repeatOccurrenceDay ?: today) < today }
                if (expired.isNotEmpty()) {
                    current = current.map { item -> if (item.id in expired.map { it.id })
                        item.preservingNote().copy(kind = "重复历史", detail = "本次未处理", scheduledAt = null,
                            dayOnly = false, windowStartAt = null, windowEndAt = null) else item }
                    expired.forEach { events += TaskRecorder.event(TaskEventType.REPEAT_MISSED,
                        it.id, it.title, at = at) }
                }
                if (current.any { it.repeatTemplateId == template.id && it.kind == "任务" && !it.done &&
                    it.scheduledAt?.let(TaskHistory::dayStartOf) == it.repeatOccurrenceDay })
                    return@forEach
                // Today's completed or skipped occurrence is final; tomorrow's instance appears on its date.
                if (current.any { it.repeatTemplateId == template.id && it.repeatOccurrenceDay == today })
                    return@forEach
                val date = (0..8).asSequence().map { plusDays(today, it) }.firstOrNull { day ->
                    day >= (template.repeatStartDay ?: today) &&
                        (when (template.repeatFrequency) {
                            "daily" -> true
                            "weekly" -> weekday(day) == weekday(requireNotNull(template.repeatStartDay))
                            else -> coursesOn(day, courses).isNotEmpty()
                        }) &&
                        (template.repeatMinute < 0 || occurrenceAt(day, template.repeatMinute) > at) &&
                        current.none { it.repeatTemplateId == template.id && it.repeatOccurrenceDay == day }
                } ?: return@forEach
                var id = newItemId()
                while (!usedIds.add(id)) id = newItemId()
                val scheduled = occurrenceAt(date, template.repeatMinute)
                val courseNames = if (template.repeatFrequency == "class_day")
                    coursesOn(date, courses).map(Course::title).distinct() else emptyList()
                val scheduleDetail = if (template.repeatMinute >= 0) TaskScheduleText.scheduledDetail(scheduled, template.durationMinutes)
                    else TaskScheduleText.dayOnlyDetail(scheduled)
                val occurrence = Item(id = id, title = template.title,
                    detail = if (courseNames.isEmpty()) scheduleDetail else
                        "$scheduleDetail · 当天课程：${courseNames.take(3).joinToString("、")}${if (courseNames.size > 3) "等${courseNames.size}门" else ""}",
                    kind = "任务", scheduledAt = scheduled, dayOnly = template.repeatMinute < 0,
                    goalId = template.goalId, durationMinutes = template.durationMinutes,
                    repeatTemplateId = template.id, repeatOccurrenceDay = date)
                current = listOf(occurrence) + current
                events += TaskRecorder.event(TaskEventType.TASK_CREATED, id, occurrence.title,
                    scheduledAt = scheduled, at = at)
            }
        return RepeatResult(current, events)
    }

    fun pause(items: List<Item>, template: Item, paused: Boolean): RepeatResult {
        if (template.kind != "重复模板" || template.repeatPaused == paused ||
            items.none { it == template }) return RepeatResult(items, emptyList())
        val pending = if (paused) items.filter { it.kind == "任务" && !it.done && it.repeatTemplateId == template.id }
            else emptyList()
        return RepeatResult(items.map { item -> when {
            item.id == template.id -> item.copy(repeatPaused = paused)
            item.id in pending.map { it.id } -> item.preservingNote().copy(kind = "重复历史",
                detail = "暂停时未处理", scheduledAt = null, dayOnly = false)
            else -> item
        } }, listOf(TaskRecorder.event(TaskEventType.REPEAT_RULE_CHANGED, template.id, template.title,
            extra = if (paused) "暂停" else "继续")) + pending.map {
            TaskRecorder.event(TaskEventType.TASK_UNSCHEDULED, it.id, it.title,
                extra = "暂停重复", scheduledAt = it.scheduledAt ?: 0L)
        })
    }

    /** Stop preserves the rule and dated history, while pending instances leave the schedule. */
    fun stop(items: List<Item>, template: Item, at: Long = System.currentTimeMillis()): RepeatResult {
        if (template.kind != "重复模板" || items.none { it == template }) return RepeatResult(items, emptyList())
        val pending = items.filter { it.repeatTemplateId == template.id && it.kind == "任务" && !it.done }
        return RepeatResult(items.map { item -> when {
            item.id == template.id -> item.copy(kind = "已停止重复", repeatPaused = true)
            item.id in pending.map { it.id } -> item.preservingNote().copy(kind = "重复历史",
                detail = "规则停止，取消本次", scheduledAt = null, dayOnly = false,
                windowStartAt = null, windowEndAt = null)
            else -> item
        } }, listOf(TaskRecorder.event(TaskEventType.REPEAT_RULE_CHANGED, template.id, template.title,
            extra = "停止", at = at)))
    }

    /** Deletion is recoverable; prior occurrences and events remain accessible. */
    fun deleteRule(items: List<Item>, template: Item, at: Long = System.currentTimeMillis()): RepeatResult {
        val stopped = stop(items, template, at)
        if (stopped.events.isEmpty()) return stopped
        return RepeatResult(stopped.items.map { item -> if (item.id == template.id) {
            val original = stopped.items.first { it.id == template.id }
            original.copy(kind = "回收站", detail = "重复规则已删除", trashedAt = at,
                trashSnapshot = ItemsCodec.encode(listOf(template)), repeatFrequency = "")
        } else item }, stopped.events + TaskRecorder.event(TaskEventType.TASK_DELETED,
            template.id, template.title, at = at))
    }

    fun skip(items: List<Item>, instance: Item, at: Long = System.currentTimeMillis(),
             courses: List<Course> = emptyList()): RepeatResult {
        if (instance.kind != "任务" || instance.done || instance.repeatTemplateId == null ||
            items.none { it == instance }) return RepeatResult(items, emptyList())
        val changed = items.map { if (it.id == instance.id) it.preservingNote().copy(
            kind = "重复历史", detail = "主动跳过本次", scheduledAt = null, dayOnly = false,
            windowStartAt = null, windowEndAt = null) else it }
        val next = refresh(changed, at, courses)
        return RepeatResult(next.items,
            listOf(TaskRecorder.event(TaskEventType.REPEAT_SKIPPED, instance.id, instance.title, at = at)) + next.events)
    }

    fun cancelInstance(items: List<Item>, instance: Item, at: Long = System.currentTimeMillis()): RepeatResult {
        if (instance.kind != "任务" || instance.repeatTemplateId == null || items.none { it == instance })
            return RepeatResult(items, emptyList())
        val changed = items.map { if (it.id == instance.id) it.preservingNote().copy(
            kind = "重复历史", detail = "本次已取消", scheduledAt = null, dayOnly = false,
            windowStartAt = null, windowEndAt = null) else it }
        return RepeatResult(changed, listOf(TaskRecorder.event(TaskEventType.TASK_DELETED,
            instance.id, instance.title, at = at)))
    }

    private fun plusDays(day: Long, days: Int): Long = Calendar.getInstance().apply {
        timeInMillis = day; add(Calendar.DAY_OF_YEAR, days)
    }.timeInMillis.let(TaskHistory::dayStartOf)

    private fun weekday(day: Long): Int = Calendar.getInstance().apply { timeInMillis = day }.get(Calendar.DAY_OF_WEEK)

    private fun coursesOn(day: Long, courses: List<Course>): List<Course> {
        val epochDay = java.time.Instant.ofEpochMilli(day).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toEpochDay()
        val weekday = java.time.LocalDate.ofEpochDay(epochDay).dayOfWeek.value
        return courses.filter { !it.needsConfirmation && it.weekday == weekday &&
            CourseActivationPolicy.isActiveOn(it, epochDay) }
    }

    private fun occurrenceAt(day: Long, minute: Int): Long = if (minute < 0) day else Calendar.getInstance().apply {
        timeInMillis = day; set(Calendar.HOUR_OF_DAY, minute / 60)
        set(Calendar.MINUTE, minute % 60); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}
