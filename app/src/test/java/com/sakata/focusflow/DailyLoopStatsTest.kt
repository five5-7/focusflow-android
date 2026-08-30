package com.sakata.focusflow

import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Test

class DailyLoopStatsTest {
    private val now = Calendar.getInstance().apply {
        set(2026, Calendar.AUGUST, 30, 12, 0, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    @Test
    fun `summary separates planned completion reschedules and inbox`() {
        val items = listOf(
            Item(title = "完成日程", detail = "", kind = "任务", scheduledAt = now - 60_000, done = true, completedAt = now),
            Item(title = "未完成日程", detail = "", kind = "任务", scheduledAt = now + 60_000),
            Item(title = "今日改期", detail = "", kind = "任务", scheduledAt = tomorrow(), rescheduleCount = 1, lastRescheduledAt = now),
            Item(title = "随手记录", detail = "", kind = "收集箱")
        )

        val result = DailyLoopStats.summarize(items, now)

        assertEquals(2, result.plannedCount)
        assertEquals(1, result.completedPlannedCount)
        assertEquals(1, result.completedCount)
        assertEquals(1, result.rescheduledCount)
        assertEquals(1, result.inboxCount)
        assertEquals(50, result.completionPercent)
    }

    @Test
    fun `completion percent is absent when nothing was planned`() {
        val result = DailyLoopStats.summarize(listOf(Item(title = "想法", detail = "", kind = "收集箱")), now)

        assertEquals(null, result.completionPercent)
    }

    @Test
    fun `returning a missed task to inbox keeps the original plan in completion rate`() {
        val items = listOf(
            Item(title = "完成日程", detail = "", kind = "任务", scheduledAt = now - 60_000, done = true, completedAt = now),
            Item(title = "放回收集箱", detail = "", kind = "收集箱", recoverySourceScheduledAt = now - 120_000)
        )

        val result = DailyLoopStats.summarize(items, now)

        assertEquals(2, result.plannedCount)
        assertEquals(1, result.completedPlannedCount)
        assertEquals(50, result.completionPercent)
        assertEquals(1, result.inboxCount)
    }

    private fun tomorrow(): Long = Calendar.getInstance().apply {
        timeInMillis = now
        add(Calendar.DAY_OF_YEAR, 1)
    }.timeInMillis
}
