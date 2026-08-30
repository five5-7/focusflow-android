package com.sakata.focusflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

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

    @Test fun sundayEvening_suggestsNextWeekSlots() {
        // 2026-08-30 是周日；原实现只看本周 → 周日晚「暂未找到足够连续的空档」
        val sundayEvening = Calendar.getInstance().apply {
            clear(); set(2026, Calendar.AUGUST, 30, 20, 45)
        }.timeInMillis
        val goal = Goal(title = "备考", weeklyTarget = 3, durationMinutes = 60)
        val suggestions = GoalPlanner.suggestions(goal, emptyList(), CommuteProfile(), emptyMap(), nowMillis = sundayEvening)
        assertTrue("周日晚上不应暂时找不到空档", suggestions.isNotEmpty())
        assertTrue("应包含次日的周一建议", suggestions.any { it.weekday == 1 })
        assertTrue(
            "所有建议都应落在 (now+15min, now+7d] 内",
            suggestions.all {
                val occurrence = GoalPlanner.nextOccurrence(it.weekday, it.startMinute, sundayEvening)
                occurrence > sundayEvening + 15 * 60_000L && occurrence <= sundayEvening + 7 * 24 * 60 * 60_000L
            }
        )
    }

    @Test fun tuesdayAfternoon_placesNextOccurrenceFirst() {
        // 周二 16:45：今天的 18:00 槽位应先于跨周的周一建议出现（按出现时间排序，不按星期几）
        val tuesday = Calendar.getInstance().apply {
            clear(); set(2026, Calendar.SEPTEMBER, 1, 16, 45)
        }.timeInMillis
        val goal = Goal(title = "备考", weeklyTarget = 3, durationMinutes = 60)
        val suggestions = GoalPlanner.suggestions(goal, emptyList(), CommuteProfile(), emptyMap(), nowMillis = tuesday)
        assertEquals(2, suggestions.first().weekday)
        assertEquals(1080, suggestions.first().startMinute)
    }
}
