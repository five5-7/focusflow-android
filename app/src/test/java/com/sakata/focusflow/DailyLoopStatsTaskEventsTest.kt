package com.sakata.focusflow

import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DailyLoopStatsTaskEventsTest {
    private val now = Calendar.getInstance().apply {
        set(2026, Calendar.AUGUST, 30, 12, 0, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    @Test
    fun `delete does not erase todays completion or reschedule`() {
        val events = listOf(
            TaskRecorder.event(TaskEventType.TASK_SCHEDULED, itemId = 1, title = "a", scheduledAt = now - 60_000, at = now - 3_600_000L),
            TaskRecorder.event(TaskEventType.TASK_COMPLETED, itemId = 1, title = "a", extra = "完成", at = now - 30_000),
            // item2 改期到今晚：改期事件也参与今日计划分母（按 scheduledAt 归日），因此计划数为 2。
            TaskRecorder.event(TaskEventType.TASK_RESCHEDULED, itemId = 2, title = "b", scheduledAt = now + 3_600_000L, at = now - 20_000),
            TaskRecorder.event(TaskEventType.TASK_DELETED, itemId = 1, title = "a", at = now - 10_000)
        )

        val result = DailyLoopStats.summarize(emptyList(), now, events)

        assertEquals(2, result.plannedCount)
        assertEquals(1, result.completedPlannedCount)
        assertEquals(1, result.completedCount)
        assertEquals(1, result.rescheduledCount)
        assertEquals(50, result.completionPercent)
    }

    @Test
    fun `returned to inbox keeps denom but does not count as completion`() {
        val events = listOf(
            TaskRecorder.event(TaskEventType.TASK_SCHEDULED, itemId = 1, title = "高数", scheduledAt = now - 60_000, at = now - 3_600_000L),
            TaskRecorder.event(TaskEventType.TASK_TO_INBOX, itemId = 1, title = "高数", at = now)
        )

        val result = DailyLoopStats.summarize(emptyList(), now, events)

        assertEquals(1, result.plannedCount)
        assertEquals(0, result.completedPlannedCount)
        assertEquals(0, result.completedCount)
        assertEquals(0, result.completionPercent)
    }

    @Test
    fun `no events falls back to items derivation`() {
        val items = listOf(
            Item(title = "日程", detail = "", kind = "任务", scheduledAt = now - 60_000, done = true, completedAt = now)
        )
        val result = DailyLoopStats.summarize(items, now, emptyList<TaskEvent>())
        assertEquals(1, result.plannedCount)
        assertEquals(1, result.completedCount)
    }

    @Test
    fun `nothing planned shows null percent`() {
        val events = listOf(TaskRecorder.event(TaskEventType.TASK_TO_INBOX, itemId = 1, at = now))
        val result = DailyLoopStats.summarize(emptyList(), now, events)
        assertNull(result.completionPercent)
    }
}
