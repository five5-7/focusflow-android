package com.sakata.focusflow

import java.util.Calendar

/** 每周目标回顾：本周进度 + 近几周完成趋势（历史来自完成反馈记录）。 */
object WeekReview {
    private const val WEEK_MILLIS = 7L * 24 * 60 * 60 * 1000

    /** 所在周（周一 00:00）的起始毫秒，与 GoalPlanner.currentWeekKey 同一定义。 */
    fun weekStartOf(millis: Long): Long {
        val calendar = Calendar.getInstance().apply { timeInMillis = millis }
        val day = when (calendar.get(Calendar.DAY_OF_WEEK)) { Calendar.SUNDAY -> 7 else -> calendar.get(Calendar.DAY_OF_WEEK) - 1 }
        calendar.add(Calendar.DAY_OF_YEAR, -(day - 1))
        calendar.set(Calendar.HOUR_OF_DAY, 0); calendar.set(Calendar.MINUTE, 0); calendar.set(Calendar.SECOND, 0); calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }

    /**
     * 近 weeks 周每周完成次数（含最低版本），从最远到本周。
     * 本周用目标自带的本周计数（与卡片一致），更早周从反馈记录聚合
     * （反馈可跳过，历史数字表示“有记录的完成”，不假装精确）。
     */
    fun history(goal: Goal, feedback: List<TaskFeedback>, weeks: Int = 4): List<Int> {
        val current = GoalPlanner.currentWeekKey()
        val start = current - (weeks - 1) * WEEK_MILLIS
        val byWeek = feedback.filter { it.goalId == goal.id && it.createdAt >= start }
            .groupingBy { weekStartOf(it.createdAt) }.eachCount()
        return (0 until weeks).map { offset ->
            val key = current - offset * WEEK_MILLIS
            if (key == current) GoalPlanner.completedThisWeek(goal) + GoalPlanner.minimumCompletedThisWeek(goal)
            else byWeek[key] ?: 0
        }.reversed()
    }

    /** 周起点简写（如 "8/11"），用于趋势图例。 */
    fun weekLabel(weekStart: Long): String {
        val calendar = Calendar.getInstance().apply { timeInMillis = weekStart }
        return "${calendar.get(Calendar.MONTH) + 1}/${calendar.get(Calendar.DAY_OF_MONTH)}"
    }
}
