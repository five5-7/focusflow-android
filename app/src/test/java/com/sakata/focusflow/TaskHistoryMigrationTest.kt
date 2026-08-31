package com.sakata.focusflow

import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskHistoryMigrationTest {
    private val now = Calendar.getInstance().apply {
        set(2026, Calendar.AUGUST, 30, 12, 0, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    @Test
    fun `completed item produces completed event with its level`() {
        val item = Item(title = "数学", detail = "", kind = "任务", done = true, completedAt = now, completionLevel = "最低版本")
        val events = TaskHistoryMigration.buildEvents(listOf(item))
        assertEquals(1, events.size)
        assertEquals(TaskEventType.TASK_COMPLETED, events[0].type)
        assertEquals(now, events[0].recordedAt)
        assertEquals("最低版本", events[0].extra)
        assertEquals("数学", events[0].title)
    }

    @Test
    fun `last reschedule produces single rescheduled event with current scheduled time`() {
        val item = Item(title = "英语", detail = "", kind = "任务", scheduledAt = now + 60_000, rescheduleCount = 3, lastRescheduledAt = now)
        val events = TaskHistoryMigration.buildEvents(listOf(item))
        val rescheduled = events.filter { it.type == TaskEventType.TASK_RESCHEDULED }
        assertEquals(1, rescheduled.size)
        assertEquals(now + 60_000, rescheduled[0].scheduledAt)
    }

    @Test
    fun `recovery source produces one scheduled event and no to-inbox pair`() {
        val item = Item(title = "高数", detail = "", kind = "收集箱", recoverySourceScheduledAt = now - 3 * 60 * 60_000L)
        val events = TaskHistoryMigration.buildEvents(listOf(item))
        assertEquals(1, events.size)
        assertEquals(TaskEventType.TASK_SCHEDULED, events[0].type)
        assertEquals(now - 3 * 60 * 60_000L, events[0].scheduledAt)
    }

    @Test
    fun `current schedule produces scheduled event at that time`() {
        val item = Item(title = "运动", detail = "", kind = "任务", scheduledAt = now - 60_000)
        val events = TaskHistoryMigration.buildEvents(listOf(item))
        assertEquals(1, events.size)
        assertEquals(TaskEventType.TASK_SCHEDULED, events[0].type)
        assertEquals(now - 60_000, events[0].scheduledAt)
    }

    @Test
    fun `empty items produce no events`() {
        assertTrue(TaskHistoryMigration.buildEvents(emptyList()).isEmpty())
    }

    @Test
    fun `idle item produces nothing`() {
        val item = Item(title = "想法", detail = "", kind = "收集箱")
        assertTrue(TaskHistoryMigration.buildEvents(listOf(item)).isEmpty())
    }
}
