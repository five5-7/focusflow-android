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

    @Test fun autoPlan_filledWeekAcknowledgesNothingToDo() {
        // completionWeekKey 默认即当前周键，completedThisWeek 达到目标 → 无需再排
        val finished = Goal(title = "备考", weeklyTarget = 1, durationMinutes = 60, completedThisWeek = 1)
        val result = GoalPlanner.autoPlan(
            listOf(finished), emptyList(), emptyList(), CommuteProfile(), { _, _ -> null },
            nowMillis = Calendar.getInstance().apply { clear(); set(2026, Calendar.SEPTEMBER, 1, 16, 45) }.timeInMillis
        )
        assertEquals(0, result.newItems.size)
        assertEquals("所有目标本周次数都已排满或完成，无需再排。", result.message)
    }

    @Test fun autoPlan_noFreeSlotsReportsBusyWeek() {
        // 每天一门 1–13 节连堂课占满课堂时段 → 无 ≥60 分钟自由时段 → 无可排时段
        val tuesday = Calendar.getInstance().apply { clear(); set(2026, Calendar.SEPTEMBER, 1, 16, 45) }.timeInMillis
        val allDayCourses = (1..7).map { w ->
            Course("连堂课", w, 1, 13, "西1教学楼", CampusZone.WEST_TEACHING, needsConfirmation = false)
        }
        val goal = Goal(title = "备考", weeklyTarget = 1, durationMinutes = 60)
        val result = GoalPlanner.autoPlan(
            listOf(goal), allDayCourses, emptyList(), CommuteProfile(), { _, _ -> null }, nowMillis = tuesday
        )
        assertEquals(0, result.newItems.size)
        assertEquals("未来一周空挡都被课程或已有安排占用，没有可排的时段；可先确认课程或调整目标时长。", result.message)
    }

    @Test fun autoPlan_schedulesIntoNearestSlot() {
        // 完成率未知（全 null）→ 按开始分钟升序；空课表整天从 08:00 起，三天后周三最早
        val tuesday = Calendar.getInstance().apply { clear(); set(2026, Calendar.SEPTEMBER, 1, 16, 45) }.timeInMillis
        val goal = Goal(title = "备考", weeklyTarget = 1, durationMinutes = 60)
        val result = GoalPlanner.autoPlan(
            listOf(goal), emptyList(), emptyList(), CommuteProfile(), { _, _ -> null }, nowMillis = tuesday
        )
        val planned = result.newItems.first()
        assertEquals(1, result.newItems.size)
        assertEquals("备考", planned.title)
        assertEquals("任务", planned.kind)
        assertEquals(goal.id, planned.goalId)
        assertEquals(60, planned.durationMinutes)
        assertEquals(
            Calendar.getInstance().apply { clear(); set(2026, Calendar.SEPTEMBER, 2, 8, 0) }.timeInMillis,
            planned.scheduledAt
        )
        assertEquals(listOf(3 to 8), result.learnedSlots)
        assertTrue(result.message.contains("已把 1 个"))
    }

    @Test fun autoPlan_batchAvoidsOwnSchedule() {
        // 一周两次：按开始分钟连续排入两天 08:00，且与同批已排条目互相避让
        val tuesday = Calendar.getInstance().apply { clear(); set(2026, Calendar.SEPTEMBER, 1, 16, 45) }.timeInMillis
        val goal = Goal(title = "备考", weeklyTarget = 2, durationMinutes = 60)
        val result = GoalPlanner.autoPlan(
            listOf(goal), emptyList(), emptyList(), CommuteProfile(), { _, _ -> null }, nowMillis = tuesday
        )
        assertEquals(2, result.newItems.size)
        assertEquals(listOf(3 to 8, 4 to 8), result.learnedSlots)
        // 第一排结束时间不晚于第二排开始 → 不重叠
        assertTrue(result.newItems[0].scheduledAt!! + 60 * 60_000L <= result.newItems[1].scheduledAt!!)
        assertTrue(result.message.contains("已把 2 个"))
    }
}
