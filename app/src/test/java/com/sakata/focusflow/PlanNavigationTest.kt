package com.sakata.focusflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanNavigationTest {
    @Test
    fun `empty plan hub explains the next setup step`() {
        val entries = PlanHubSummary.entries(PlanHubSnapshot())

        assertEquals(PlanPage.entries.toList(), entries.map { it.first })
        assertEquals("0 门已确认 · 0 门待确认", entries.summaryFor(PlanPage.COURSES))
        assertEquals("暂无可用空挡", entries.summaryFor(PlanPage.GAPS))
        assertEquals("尚未创建目标 · 0 项教程资料", entries.summaryFor(PlanPage.GOALS))
        assertEquals("有目标后生成建议", entries.summaryFor(PlanPage.REVIEW))
        assertEquals("暂无", entries.summaryFor(PlanPage.PAUSED))
    }

    @Test
    fun `active plan hub reports conflicts progress and paused work`() {
        val entries = PlanHubSummary.entries(
            PlanHubSnapshot(
                confirmedCourseCount = 6,
                pendingCourseCount = 2,
                conflictingCourseCount = 1,
                gapCount = 4,
                goalCount = 3,
                resourceCount = 2,
                completedThisWeek = 5,
                weeklyTarget = 8,
                pausedCount = 2
            )
        )

        assertTrue(entries.summaryFor(PlanPage.COURSES).startsWith("⚠ 1 门冲突"))
        assertEquals("4 段可用空挡", entries.summaryFor(PlanPage.GAPS))
        assertEquals("3 个目标 · 2 项教程资料", entries.summaryFor(PlanPage.GOALS))
        assertEquals("本周 5 / 8 次 · 低压力建议", entries.summaryFor(PlanPage.REVIEW))
        assertEquals("2 项", entries.summaryFor(PlanPage.PAUSED))
    }

    private fun List<Pair<PlanPage, String>>.summaryFor(page: PlanPage): String =
        first { it.first == page }.second
}
