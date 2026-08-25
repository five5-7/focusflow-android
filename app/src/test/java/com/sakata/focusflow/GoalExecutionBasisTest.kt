package com.sakata.focusflow

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoalExecutionBasisTest {
    @Test
    fun `scheduled detail carries only this goals execution basis`() {
        val goal = Goal(
            title = "复习科目一",
            weeklyTarget = 3,
            durationMinutes = 25,
            metricType = "成果",
            metricTarget = "完成一套题并订正",
            minimumVersion = "先做 5 题",
            resourceTitle = "科目一题库",
            resourceUnit = "错题章节",
            firstAction = "打开题库，从上次错题开始"
        )

        val detail = goalTaskDetail(goal, weekday = 2, startMinute = 18 * 60)

        assertTrue(detail.contains("第一步：打开题库，从上次错题开始"))
        assertTrue(detail.contains("教程：科目一题库（错题章节）"))
        assertTrue(detail.contains("成果：完成一套题并订正"))
        assertFalse(detail.contains("当前标准"))
    }
}
