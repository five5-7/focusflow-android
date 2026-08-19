package com.sakata.focusflow

import org.junit.Assert.assertEquals
import org.junit.Test

class GoalPlannerTest {
    @Test fun displayTime() {
        assertEquals("10:00", GoalPlanner.displayTime(600))
        assertEquals("00:05", GoalPlanner.displayTime(5))
        assertEquals("23:59", GoalPlanner.displayTime(1439))
    }

    @Test fun suggestedMinimum_byMetric() {
        assertEquals("先投入 10 分钟", GoalPlanner.suggestedMinimum("时长", "30 分钟", 30))
        assertEquals("先完成 1 次", GoalPlanner.suggestedMinimum("次数", "3 次", 20))
        assertEquals("先完成成果的最小一步", GoalPlanner.suggestedMinimum("成果", "一篇", 40))
    }

    @Test fun suggestedMinimum_clampsToRange() {
        // 时长/3 夹在 5..15 之间
        assertEquals("先投入 5 分钟", GoalPlanner.suggestedMinimum("时长", "10 分钟", 10))
        assertEquals("先投入 15 分钟", GoalPlanner.suggestedMinimum("时长", "60 分钟", 60))
    }
}
