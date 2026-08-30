package com.sakata.focusflow

import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Test

class RecoveryInsightsTaskEventsTest {
    private val now = Calendar.getInstance().apply {
        set(2026, Calendar.AUGUST, 30, 12, 0, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun dayAt(offset: Int): Long = Calendar.getInstance().apply {
        timeInMillis = now
        add(Calendar.DAY_OF_YEAR, offset)
        set(Calendar.HOUR_OF_DAY, 12); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    @Test
    fun `weekly planned deduplicates item moved across days within week`() {
        val events = listOf(
            TaskRecorder.event(TaskEventType.TASK_SCHEDULED, itemId = 1, title = "a", scheduledAt = dayAt(-3), at = dayAt(-4)),
            TaskRecorder.event(TaskEventType.TASK_RESCHEDULED, itemId = 1, title = "a", scheduledAt = dayAt(-1), at = dayAt(-2))
        )
        val result = RecoveryInsights.weeklySummary(emptyList(), now, events)
        assertEquals(1, result.plannedCount)
        assertEquals(0, result.completedCount)
    }

    @Test
    fun `weekly completion counts deleted task events`() {
        val events = listOf(
            TaskRecorder.event(TaskEventType.TASK_SCHEDULED, itemId = 1, title = "a", scheduledAt = dayAt(-1), at = dayAt(-2)),
            TaskRecorder.event(TaskEventType.TASK_COMPLETED, itemId = 1, title = "a", at = dayAt(-1)),
            TaskRecorder.event(TaskEventType.TASK_DELETED, itemId = 1, title = "a", at = dayAt(0))
        )
        val result = RecoveryInsights.weeklySummary(emptyList(), now, events)
        assertEquals(1, result.plannedCount)
        assertEquals(1, result.completedCount)
        assertEquals(100, result.completionPercent)
    }

    @Test
    fun `weekly reschedule counts snooze events`() {
        val events = listOf(
            TaskRecorder.event(TaskEventType.TASK_SCHEDULED, itemId = 1, title = "a", scheduledAt = dayAt(-1), at = dayAt(-2)),
            TaskRecorder.event(TaskEventType.TASK_RESCHEDULED, itemId = 1, title = "a", scheduledAt = dayAt(-1) + 1, extra = "延后一小时", at = dayAt(-1))
        )
        val result = RecoveryInsights.weeklySummary(emptyList(), now, events)
        assertEquals(1, result.rescheduledCount)
    }

    @Test
    fun `task events empty falls back to items derivation`() {
        val items = listOf(
            Item(title = "完成", detail = "", kind = "任务", scheduledAt = now - 60_000, done = true, completedAt = now)
        )
        val result = RecoveryInsights.weeklySummary(items, now, emptyList<TaskEvent>())
        assertEquals(1, result.plannedCount)
        assertEquals(1, result.completedCount)
    }
}
