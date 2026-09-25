package com.sakata.focusflow

import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Test

class TodoGroupingTest {
    private val now = Calendar.getInstance().apply {
        set(2026, Calendar.SEPTEMBER, 24, 16, 0, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun task(id: Long, scheduledAt: Long? = null, dayOnly: Boolean = false, done: Boolean = false) =
        Item(id = id, title = "$id", detail = "", kind = "任务", scheduledAt = scheduledAt, dayOnly = dayOnly, done = done)

    @Test fun todayWithoutExactTimeIsNotOverdueAfterMorning() {
        val todayMorning = Calendar.getInstance().apply { timeInMillis = now; set(Calendar.HOUR_OF_DAY, 9) }.timeInMillis
        val yesterday = Calendar.getInstance().apply { timeInMillis = todayMorning; add(Calendar.DAY_OF_YEAR, -1) }.timeInMillis
        val items = listOf(task(1, todayMorning, dayOnly = true), task(2, todayMorning), task(3, yesterday, dayOnly = true))
        val groups = groupTodos(items, now)
        assertEquals(listOf(3L, 2L), groups.overdue.map { it.id })
        assertEquals(listOf(1L), groups.scheduled.map { it.id })
    }

    @Test fun onlyTasksAreListedAndCompletedStaySeparate() {
        val items = listOf(task(1), Item(id = 2, title = "想法", detail = "", kind = "收集箱"),
            task(3, now + 1000), task(4, done = true).copy(completedAt = now))
        val groups = groupTodos(items, now)
        assertEquals(2, groups.pendingCount)
        assertEquals(listOf(1L), groups.unscheduled.map { it.id })
        assertEquals(listOf(3L), groups.scheduled.map { it.id })
        assertEquals(listOf(4L), groups.completed.map { it.id })
    }
}
