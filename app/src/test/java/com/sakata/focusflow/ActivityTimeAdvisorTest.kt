package com.sakata.focusflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityTimeAdvisorTest {
    private fun completed(id: Long, name: String, category: String, durationMin: Int) = ActivitySession(
        id = id, name = name, category = category,
        actualStartAt = 1000L, actualEndAt = 1000L + durationMin * 60_000L, endsAt = 1000L + durationMin * 60_000L,
        status = ActivitySession.STATUS_COMPLETED
    )

    @Test fun noHistory_usesDefault() {
        val s = ActivityTimeAdvisor.suggest("学习", "复习", emptyList(), null, "正常")
        assertEquals(45, s.minutes)
        assertTrue(s.reason.contains("保守起始值"))
    }

    @Test fun history_usesMedianDuration() {
        val history = listOf(completed(1, "复习", "学习", 20), completed(2, "复习", "学习", 30))
        val s = ActivityTimeAdvisor.suggest("学习", "复习", history, null, "正常")
        assertEquals(25, s.minutes) // median(20,30)
        assertEquals(2, s.sampleCount)
    }

    @Test fun lowEnergy_reducesDefault() {
        val s = ActivityTimeAdvisor.suggest("学习", "复习", emptyList(), null, "偏低")
        assertEquals(35, s.minutes) // 45 × 0.75 → 34 → roundToFive → 35
        assertTrue(s.reason.contains("精力偏低"))
    }

    @Test fun commitment_capsMinutes() {
        val commitment = ActivityCommitment("下一节课", 1000L + 30 * 60_000L)
        val s = ActivityTimeAdvisor.suggest("学习", "复习", emptyList(), commitment, "正常", now = 1000L)
        // 距下一项 30 分钟，保留 15 分钟缓冲 → 上限 15
        assertEquals(15, s.minutes)
        assertTrue(s.cappedByCommitment)
    }
}
