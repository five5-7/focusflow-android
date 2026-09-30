package com.sakata.focusflow

import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskMissedDigestPolicyTest {
    private fun at(year: Int, month: Int, date: Int, hour: Int): Long = Calendar.getInstance().apply {
        set(year, month - 1, date, hour, 0, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    @Test fun `summary counts yesterday unresolved slots and archived repeat misses only once`() {
        val now = at(2026, 9, 26, 9)
        val yesterday = at(2026, 9, 25, 10)
        val items = listOf(
            Item(id = 1, title = "普通", detail = "", kind = "任务", scheduledAt = yesterday, durationMinutes = 30),
            Item(id = 2, title = "已完成", detail = "", kind = "任务", scheduledAt = yesterday, done = true),
            Item(id = 3, title = "今天", detail = "", kind = "任务", scheduledAt = now + 60_000L),
            Item(id = 4, title = "已取消", detail = "", kind = "重复历史", scheduledAt = null),
            Item(id = 5, title = "整天", detail = "", kind = "任务", scheduledAt = yesterday, dayOnly = true),
            Item(id = 8, title = "已改期", detail = "", kind = "任务", scheduledAt = now + 60_000L,
                recoverySourceScheduledAt = yesterday)
        )
        val missed = TaskRecorder.event(TaskEventType.REPEAT_MISSED, 6, "重复", scheduledAt = yesterday,
            at = now - 8 * 60 * 60_000L)
        val older = TaskRecorder.event(TaskEventType.REPEAT_MISSED, 7, "更早", scheduledAt = at(2026, 9, 24, 10), at = now)
        assertEquals(listOf("普通", "整天", "重复"), TaskMissedDigestPolicy.missedTitles(
            items, listOf(missed, missed, older), TaskMissedDigestPolicy.todayStart(now), now))
    }

    @Test fun `only scheduled local morning can send once and DST uses calendar date`() {
        val old = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
            val today = at(2026, 3, 8, 9)
            assertEquals(at(2026, 3, 7, 0), TaskMissedDigestPolicy.yesterdayStart(
                TaskMissedDigestPolicy.todayStart(today)))
            assertTrue(TaskMissedDigestPolicy.isFresh(TaskMissedDigestPolicy.todayStart(today), 0, today))
            assertFalse(TaskMissedDigestPolicy.isFresh(TaskMissedDigestPolicy.todayStart(today),
                TaskMissedDigestPolicy.localDayKey(today), today))
            assertFalse(TaskMissedDigestPolicy.isFresh(TaskMissedDigestPolicy.yesterdayStart(
                TaskMissedDigestPolicy.todayStart(today)), 0, today))
            TimeZone.setDefault(TimeZone.getTimeZone("America/Chicago"))
            val sameDate = at(2026, 3, 8, 9)
            assertFalse(TaskMissedDigestPolicy.isFresh(TaskMissedDigestPolicy.todayStart(sameDate),
                TaskMissedDigestPolicy.localDayKey(today), sameDate))
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
            assertFalse(TaskMissedDigestPolicy.isFresh(TaskMissedDigestPolicy.todayStart(today), 0,
                at(2026, 3, 8, 12)))
            assertEquals(at(2026, 3, 9, 9), TaskMissedDigestPolicy.triggerAfter(today))
        } finally {
            TimeZone.setDefault(old)
        }
    }
}
