package com.sakata.focusflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduledActivityPolicyTest {
    private fun session(category: String, title: String = "晚间安排") = GameSessionRecord(
        id = 7,
        title = title,
        category = category,
        packageName = null,
        plannedStartAt = 1_000,
        plannedEndAt = 2_000,
        remindStart = true
    )

    @Test fun selectableCategories_haveOneCentralStableOrder() {
        assertEquals(listOf("游戏", "视频", "学习", "休息", "运动", "自定义"), ScheduledActivityKind.selectableValues)
    }

    @Test fun startCopy_reportsEveryStoredCategoryWithoutInventingEntertainmentState() {
        val expected = mapOf(
            "游戏" to "游戏", "视频" to "视频", "学习" to "学习",
            "休息" to "休息", "运动" to "运动", "自定义" to "活动"
        )
        expected.forEach { (stored, label) ->
            val copy = ScheduledActivityPolicy.startCopy(session(stored))
            assertEquals("活动开始 · 晚间安排", copy.title)
            assertTrue(copy.body.startsWith("${label}安排时间到了"))
            assertFalse(copy.body.contains("自动记录"))
            if (stored != "游戏") assertFalse(copy.body.contains("娱乐"))
        }
    }

    @Test fun detection_isLimitedToGameAndVideo() {
        assertEquals(ForegroundDetection.GAME, ScheduledActivityPolicy.detection("游戏"))
        assertEquals(ForegroundDetection.GAME, ScheduledActivityPolicy.detection("游戏／娱乐"))
        assertEquals(ForegroundDetection.VIDEO, ScheduledActivityPolicy.detection("视频"))
        listOf("学习", "休息", "运动", "自定义", "旧版未知值").forEach {
            assertNull(ScheduledActivityPolicy.detection(it))
        }
    }

    @Test fun plannedTime_rejectsStaleNewBroadcastButKeepsUpgradeCompatibility() {
        assertTrue(ScheduledActivityPolicy.matchesCurrentPlan(20_000L, 20_000L))
        assertFalse(ScheduledActivityPolicy.matchesCurrentPlan(10_000L, 20_000L))
        assertTrue(ScheduledActivityPolicy.matchesCurrentPlan(-1L, 20_000L))
    }

    @Test fun sessions_removeOnlyMatchingActivity() {
        val first = session("运动").copy(id = 1)
        val second = session("学习").copy(id = 2)
        assertEquals(listOf(second), ScheduledActivitySessions.remove(listOf(first, second), 1))
    }

    @Test fun sessions_rescheduleOnlyOpenMatchingActivity() {
        val open = session("运动").copy(id = 1)
        val closed = session("学习").copy(id = 2, actualEndAt = 1_500)
        val updated = ScheduledActivitySessions.reschedule(listOf(open, closed), 1, 10_000, 45)
        assertEquals(10_000L, updated[0].plannedStartAt)
        assertEquals(10_000 + 45 * 60_000L, updated[0].plannedEndAt)
        assertEquals(closed, updated[1])
    }
}
