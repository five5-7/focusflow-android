package com.sakata.focusflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar

class LifestyleInsightsTest {
    private fun at(hour: Int, dayOffset: Int = 0): Long = Calendar.getInstance().apply {
        clear(); set(2026, 0, 5, hour, 0, 0); add(Calendar.DAY_OF_YEAR, dayOffset)
    }.timeInMillis

    @Test fun lateNightActiveCount_countsLateNightOnly() {
        val now = at(12, 0)
        val checkIns = listOf(
            StatusCheckIn("正常", "学习", at(23, -1)), // 昨天 23:00（深夜）
            StatusCheckIn("正常", "学习", at(15, -1))  // 昨天 15:00（白天）
        )
        assertEquals(1, LifestyleInsights.lateNightActiveCount(checkIns, emptyList(), now = now))
    }

    @Test fun lateNightActiveCount_ignoresActiveSessions() {
        val now = at(12, 0)
        val history = listOf(
            ActivitySession(name = "游戏", actualStartAt = at(23, -1), endsAt = at(23, -1) + 3600_000L, status = ActivitySession.STATUS_ACTIVE),
            ActivitySession(name = "追剧", actualStartAt = at(22, -1), endsAt = at(23, -1), status = ActivitySession.STATUS_COMPLETED)
        )
        // 只有已完成/非 active 的活动才计入：第一条 active 忽略，第二条 completed 计入
        assertEquals(1, LifestyleInsights.lateNightActiveCount(emptyList(), history, now = now))
    }

    @Test fun typicalEntertainmentPeriod() {
        val sessions = (1..3).map {
            ActivitySession(name = "游戏$it", category = "游戏／娱乐", actualStartAt = at(20, 0), endsAt = at(21, 0))
        }
        assertEquals(20, LifestyleInsights.typicalEntertainmentPeriod(sessions))
    }

    @Test fun typicalEntertainmentPeriod_needsThreeSamples() {
        val sessions = listOf(
            ActivitySession(name = "游戏", category = "游戏／娱乐", actualStartAt = at(20, 0), endsAt = at(21, 0))
        )
        assertNull(LifestyleInsights.typicalEntertainmentPeriod(sessions))
    }
}
