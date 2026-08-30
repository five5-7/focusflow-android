package com.sakata.focusflow

import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskHistoryTest {
    private val now = Calendar.getInstance().apply {
        set(2026, Calendar.AUGUST, 30, 12, 0, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun dayStartOf(millis: Long): Long = TaskHistory.dayStartOf(millis)

    private fun dayAt(offset: Int): Long = Calendar.getInstance().apply {
        timeInMillis = now
        add(Calendar.DAY_OF_YEAR, offset)
        set(Calendar.HOUR_OF_DAY, 12); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    @Test
    fun `scheduled events count as planned by scheduled day deduplicated by item`() {
        val events = listOf(
            TaskRecorder.event(TaskEventType.TASK_SCHEDULED, itemId = 1, title = "a", scheduledAt = dayAt(0), at = dayAt(0)),
            // 同一天内同任务再次移动：计划数只计 1
            TaskRecorder.event(TaskEventType.TASK_RESCHEDULED, itemId = 1, title = "a", scheduledAt = dayAt(0) + 3 * 60 * 60_000L, at = dayAt(0)),
            TaskRecorder.event(TaskEventType.TASK_SCHEDULED, itemId = 2, title = "b", scheduledAt = dayAt(0), at = dayAt(0))
        )

        val summary = TaskHistory.daySummary(events, dayStartOf(now))

        assertEquals(2, summary.scheduledCount)
        assertEquals(1, summary.rescheduledCount)
    }

    @Test
    fun `reschedule counts events including multiple moves on same day`() {
        val events = listOf(
            TaskRecorder.event(TaskEventType.TASK_RESCHEDULED, itemId = 1, title = "a", scheduledAt = dayAt(0), at = dayAt(0)),
            TaskRecorder.event(TaskEventType.TASK_RESCHEDULED, itemId = 1, title = "a", scheduledAt = dayAt(1), at = dayAt(0))
        )
        val summary = TaskHistory.daySummary(events, dayStartOf(now))
        assertEquals(2, summary.rescheduledCount)
    }

    @Test
    fun `moving to another day keeps original day planned and adds to new day`() {
        val events = listOf(
            TaskRecorder.event(TaskEventType.TASK_SCHEDULED, itemId = 1, title = "a", scheduledAt = dayAt(-1), at = dayAt(-2)),
            TaskRecorder.event(TaskEventType.TASK_RESCHEDULED, itemId = 1, title = "a", scheduledAt = dayAt(0), at = dayAt(0))
        )
        val yesterday = TaskHistory.daySummary(events, dayStartOf(dayAt(-1)))
        val today = TaskHistory.daySummary(events, dayStartOf(now))
        assertEquals(1, yesterday.scheduledCount)
        assertEquals(1, today.scheduledCount)
    }

    @Test
    fun `to inbox and delete do not remove original planned day`() {
        val events = listOf(
            TaskRecorder.event(TaskEventType.TASK_SCHEDULED, itemId = 1, title = "a", scheduledAt = dayAt(0), at = dayAt(-1)),
            TaskRecorder.event(TaskEventType.TASK_TO_INBOX, itemId = 1, title = "a", at = dayAt(0)),
            TaskRecorder.event(TaskEventType.TASK_DELETED, itemId = 1, title = "a", at = dayAt(0))
        )
        val summary = TaskHistory.daySummary(events, dayStartOf(now))
        assertEquals(1, summary.scheduledCount)
        assertEquals(2, summary.scheduleChangesCount) // to-inbox + deleted
    }

    @Test
    fun `completion of deleted task still counts`() {
        val events = listOf(
            TaskRecorder.event(TaskEventType.TASK_SCHEDULED, itemId = 1, title = "a", scheduledAt = dayAt(0), at = dayAt(-1)),
            TaskRecorder.event(TaskEventType.TASK_COMPLETED, itemId = 1, title = "a", extra = "完成", at = dayAt(0)),
            TaskRecorder.event(TaskEventType.TASK_DELETED, itemId = 1, title = "a", at = dayAt(0))
        )
        val summary = TaskHistory.daySummary(events, dayStartOf(now))
        assertEquals(1, summary.completedCount)
        assertEquals(1, summary.completedPlannedCount)
        assertEquals(100, summary.completionPercent)
    }

    @Test
    fun `completion percent absent when nothing planned`() {
        val events = listOf(TaskRecorder.event(TaskEventType.TASK_COMPLETED, itemId = 1, title = "a", at = dayAt(0)))
        val summary = TaskHistory.daySummary(events, dayStartOf(now))
        assertNull(summary.completionPercent)
    }

    @Test
    fun `restore does not add planned count`() {
        val events = listOf(TaskRecorder.event(TaskEventType.TASK_RESTORED, itemId = 1, title = "a", at = dayAt(0)))
        val summary = TaskHistory.daySummary(events, dayStartOf(now))
        assertEquals(0, summary.scheduledCount)
    }

    @Test
    fun `created with scheduled time counts as planned`() {
        val events = listOf(TaskRecorder.event(TaskEventType.TASK_CREATED, itemId = 1, title = "今晚", scheduledAt = dayAt(0), at = dayAt(-1)))
        val summary = TaskHistory.daySummary(events, dayStartOf(now))
        assertEquals(1, summary.scheduledCount)
    }

    @Test
    fun `convert does not add planned count but counts as schedule change`() {
        val events = listOf(TaskRecorder.event(TaskEventType.TASK_CONVERTED, itemId = 1, title = "a", at = dayAt(0)))
        val summary = TaskHistory.daySummary(events, dayStartOf(now))
        assertEquals(0, summary.scheduledCount)
        assertEquals(1, summary.scheduleChangesCount)
        assertEquals(0, summary.rescheduledCount)
    }

    @Test
    fun `attached to plan does not add planned count but counts as schedule change`() {
        val events = listOf(TaskRecorder.event(TaskEventType.TASK_ATTACHED_TO_PLAN, itemId = 1, title = "a", at = dayAt(0)))
        val summary = TaskHistory.daySummary(events, dayStartOf(now))
        assertEquals(0, summary.scheduledCount)
        assertEquals(1, summary.scheduleChangesCount)
        assertEquals(0, summary.rescheduledCount)
    }

    @Test
    fun `converted after scheduled keeps original planned day`() {
        val events = listOf(
            TaskRecorder.event(TaskEventType.TASK_SCHEDULED, itemId = 1, title = "a", scheduledAt = dayAt(0), at = dayAt(-1)),
            TaskRecorder.event(TaskEventType.TASK_CONVERTED, itemId = 1, title = "a", at = dayAt(0))
        )
        val summary = TaskHistory.daySummary(events, dayStartOf(now))
        assertEquals(1, summary.scheduledCount)
        assertEquals(1, summary.scheduleChangesCount)
    }

    @Test
    fun `lastDays returns seven entries oldest to newest ending today`() {
        val events = listOf(TaskRecorder.event(TaskEventType.TASK_COMPLETED, itemId = 1, title = "a", at = now))
        val days = TaskHistory.lastDays(events, days = 7, now = now)
        assertEquals(7, days.size)
        assertEquals(TaskHistory.dayStartOf(now), days.last().dayStart)
        assertEquals(1, days.last().completedCount)
        assertTrue(days.first().dayStart <= days[1].dayStart)
    }

    @Test
    fun `completedOn lists completed events descending`() {
        val early = TaskRecorder.event(TaskEventType.TASK_COMPLETED, itemId = 1, title = "a", at = dayAt(0) - 3_600_000L)
        val late = TaskRecorder.event(TaskEventType.TASK_COMPLETED, itemId = 2, title = "b", at = dayAt(0))
        val listed = TaskHistory.completedOn(listOf(late, early), dayStartOf(now))
        assertEquals(listOf(late.id, early.id), listed.map { it.id })
    }

    @Test
    fun `recentEvents sorts descending and caps`() {
        val events = (1..10).map { TaskRecorder.event(TaskEventType.TASK_CREATED, itemId = it.toLong(), at = now + it) }
        assertEquals(3, TaskHistory.recentEvents(events, limit = 3).size)
        assertEquals(10L, TaskHistory.recentEvents(events, limit = 3).first().itemId)
    }
}
