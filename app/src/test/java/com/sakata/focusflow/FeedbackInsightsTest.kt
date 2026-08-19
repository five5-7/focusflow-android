package com.sakata.focusflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FeedbackInsightsTest {
    private fun fb(id: Long, barrier: String, level: String = "完整完成", difficulty: String = "正常", createdAt: Long = 0) =
        TaskFeedback(id = id, goalId = 1, completionLevel = level, difficulty = difficulty, barrier = barrier, createdAt = createdAt)

    @Test fun analyze_needsMinFeedback() {
        assertNull(FeedbackInsights.analyze((1..4L).map { fb(it, "时间不够") }))
    }

    @Test fun analyze_topBarriers() {
        val many = (1..8L).map { fb(it, if (it <= 5) "时间不够" else "精力不足") }
        val insight = FeedbackInsights.analyze(many)!!
        assertEquals("时间不够", insight.topBarriers.first().first)
        assertEquals(5, insight.topBarriers.first().second)
        assertEquals(8, insight.totalCount)
    }

    @Test fun analyze_allNoBarrier_returnsNull() {
        assertNull(FeedbackInsights.analyze((1..6L).map { fb(it, "无") }))
    }
}
