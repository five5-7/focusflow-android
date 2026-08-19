package com.sakata.focusflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class FlexiblePlannerTest {
    private val now = Calendar.getInstance().apply { clear(); set(2026, 0, 5, 8, 0, 0) }.timeInMillis // 周一 08:00
    private val item = Item(title = "复习", detail = "", kind = "任务", durationMinutes = 30)

    private fun minuteOf(time: Long): Int = Calendar.getInstance().apply { timeInMillis = time }.let {
        it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE)
    }

    @Test fun suggestions_noOccupied_returnsThree() {
        val s = FlexiblePlanner.suggestions(item, emptyList(), emptyList(), "正常", now)
        assertEquals(3, s.size)
        assertTrue(s.all { it.durationMinutes == 30 })
        assertTrue(s.all { it.startsAt >= now })
        assertTrue(s[0].reason.contains("缓冲"))
    }

    @Test fun suggestions_prefersEnergyWindow() {
        val s = FlexiblePlanner.suggestions(item, emptyList(), emptyList(), "正常", now)
        // “正常”偏好 9:30 / 14:30 / 19:00，第一条应落在 9:30
        assertEquals(9 * 60 + 30, minuteOf(s[0].startsAt))
    }

    @Test fun suggestions_avoidsBusySlot() {
        // 覆盖 9:30 的课程：第 1–2 节（periodStart(1)=480, periodEnd(2)=570，恰覆盖 9:30）
        val course = Course("高数", 1, 1, 2, "西1教学楼", CampusZone.WEST_TEACHING, needsConfirmation = false)
        val s = FlexiblePlanner.suggestions(item, emptyList(), listOf(course), "正常", now)
        // 避开 9:30（课程 480–570）后，第一条不会落在被占用的 9:30
        assertTrue(s.isEmpty() || minuteOf(s[0].startsAt) != 9 * 60 + 30)
    }
}
