package com.sakata.focusflow

import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecoveryInsightsTest {
    private val now = Calendar.getInstance().apply { clear(); set(2026, Calendar.AUGUST, 27, 18, 0) }.timeInMillis

    @Test fun `missed and repeatedly rescheduled tasks become recovery candidates`() {
        val missed = Item(title = "错过", detail = "", kind = "任务", scheduledAt = now - 2 * 60 * 60_000L, durationMinutes = 30)
        val repeated = Item(title = "反复改期", detail = "", kind = "任务", scheduledAt = now + 60_000L, rescheduleCount = 2)
        val ordinary = Item(title = "普通", detail = "", kind = "任务", scheduledAt = now + 60_000L)

        val result = RecoveryInsights.candidates(listOf(ordinary, repeated, missed), now)

        assertEquals(listOf("错过", "反复改期"), result.map { it.item.title })
        assertEquals(RecoveryReason.MISSED, result.first().reason)
    }

    @Test fun `done inbox and paused tasks never become recovery candidates`() {
        val items = listOf(
            Item(title = "完成", detail = "", kind = "任务", scheduledAt = now - 100_000L, done = true),
            Item(title = "收集", detail = "", kind = "收集箱", rescheduleCount = 3),
            Item(title = "暂停", detail = "", kind = "暂停", rescheduleCount = 3)
        )
        assertEquals(emptyList<RecoveryCandidate>(), RecoveryInsights.candidates(items, now))
    }

    @Test fun `weekly summary reports completion reschedules misses and repeated period`() {
        val monday = WeekReview.weekStartOf(now)
        fun at(day: Int, hour: Int) = monday + day * 24 * 60 * 60_000L + hour * 60 * 60_000L
        val items = listOf(
            Item(title = "完成", detail = "", kind = "任务", scheduledAt = at(0, 9), done = true),
            Item(title = "错过", detail = "", kind = "任务", scheduledAt = at(1, 9), durationMinutes = 30),
            Item(title = "未来", detail = "", kind = "任务", scheduledAt = at(5, 9)),
            Item(title = "改期一", detail = "", kind = "任务", scheduledAt = at(5, 10), lastRescheduledAt = at(1, 14)),
            Item(title = "改期二", detail = "", kind = "任务", scheduledAt = at(5, 11), lastRescheduledAt = at(2, 16))
        )

        val result = RecoveryInsights.weeklySummary(items, now)

        assertEquals(5, result.plannedCount)
        assertEquals(1, result.completedCount)
        assertEquals(2, result.rescheduledCount)
        assertEquals(1, result.missedCount)
        assertEquals(20, result.completionPercent)
        assertEquals("下午", result.frequentReschedulePeriod)
    }

    @Test fun `completion percent and period stay absent without enough data`() {
        val result = RecoveryInsights.weeklySummary(listOf(Item(title = "想法", detail = "", kind = "收集箱")), now)
        assertNull(result.completionPercent)
        assertNull(result.frequentReschedulePeriod)
    }

    @Test fun `weekly summary keeps an inbox task that came from this weeks plan`() {
        val source = WeekReview.weekStartOf(now) + 9 * 60 * 60_000L
        val result = RecoveryInsights.weeklySummary(
            listOf(Item(title = "放回收集箱", detail = "", kind = "收集箱", recoverySourceScheduledAt = source, durationMinutes = 15)),
            now
        )

        assertEquals(1, result.plannedCount)
        assertEquals(0, result.completedCount)
        assertEquals(1, result.missedCount)
        assertEquals(0, result.completionPercent)
    }
}
