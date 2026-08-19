package com.sakata.focusflow

import java.util.Calendar

data class Goal(
    val id: Long = System.currentTimeMillis(),
    val title: String,
    val weeklyTarget: Int,
    val durationMinutes: Int,
    val metricType: String = "时长",
    val metricTarget: String = "",
    val minimumVersion: String = "",
    val resourceTitle: String = "",
    val resourceUnit: String = "",
    val completedThisWeek: Int = 0,
    val minimumCompletionsThisWeek: Int = 0,
    val completionWeekKey: Long = GoalPlanner.currentWeekKey(),
    val desiredOutcome: String = ""
)

data class LearningResource(
    val id: Long = System.currentTimeMillis(),
    val title: String,
    val url: String,
    val selected: Boolean = false,
    val summary: String = ""
)

data class TaskFeedback(
    val id: Long = System.currentTimeMillis(),
    val goalId: Long,
    val completionLevel: String,
    val difficulty: String,
    val barrier: String,
    val createdAt: Long = System.currentTimeMillis()
)

data class ImprovementNote(
    val id: Long = System.currentTimeMillis(),
    val text: String,
    val createdAt: Long = System.currentTimeMillis()
)

data class GoalSuggestion(val weekday: Int, val startMinute: Int, val freeMinutes: Int)

object GoalPlanner {
    fun suggestedMinimum(metricType: String, metricTarget: String, durationMinutes: Int): String = when (metricType) {
        "时长" -> "先投入 ${(durationMinutes / 3).coerceIn(5, 15)} 分钟"
        "次数" -> "先完成 1 次"
        else -> "先完成成果的最小一步"
    }

    fun currentWeekKey(): Long {
        val calendar = Calendar.getInstance()
        val day = when (calendar.get(Calendar.DAY_OF_WEEK)) { Calendar.SUNDAY -> 7 else -> calendar.get(Calendar.DAY_OF_WEEK) - 1 }
        calendar.add(Calendar.DAY_OF_YEAR, -(day - 1))
        calendar.set(Calendar.HOUR_OF_DAY, 0); calendar.set(Calendar.MINUTE, 0); calendar.set(Calendar.SECOND, 0); calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }

    fun completedThisWeek(goal: Goal): Int = if (goal.completionWeekKey == currentWeekKey()) goal.completedThisWeek else 0
    fun minimumCompletedThisWeek(goal: Goal): Int = if (goal.completionWeekKey == currentWeekKey()) goal.minimumCompletionsThisWeek else 0
    /** occupied：日程里已有安排按星期几的占用分钟段，建议会避开这些时段。 */
    fun suggestions(goal: Goal, courses: List<Course>, profile: CommuteProfile, occupied: Map<Int, List<IntRange>> = emptyMap()): List<GoalSuggestion> {
        val confirmed = courses.filter { !it.needsConfirmation }
        val gapSuggestions = CourseGapPlanner.gaps(confirmed, profile, occupied)
            .filter { it.minutesFree >= goal.durationMinutes }
            .map { GoalSuggestion(it.from.weekday, it.suggestedStartMinute, it.minutesFree) }
        // 自由时段：课后空闲与整天空闲也参与安排（不只课间空挡）；freeWindows 已扣除占用段。
        val freeSuggestions = CourseGapPlanner.freeWindows(confirmed, occupied = occupied)
            .filter { it.minutes >= goal.durationMinutes }
            .map { GoalSuggestion(it.weekday, it.startMinute, it.minutes) }
        val fallbackSuggestions = (1..7).flatMap { weekday ->
            listOf(9 * 60, 14 * 60, 18 * 60, 20 * 60).map { start -> GoalSuggestion(weekday, start, 120) }
        }.filter { suggestion ->
            confirmed.none { course ->
                course.weekday == suggestion.weekday &&
                    CourseGapPlanner.periodStart(course.startPeriod) < suggestion.startMinute + goal.durationMinutes &&
                    suggestion.startMinute < CourseGapPlanner.periodEnd(course.endPeriod)
            }
        }
        val calendar = Calendar.getInstance()
        val currentDay = when (calendar.get(Calendar.DAY_OF_WEEK)) { Calendar.SUNDAY -> 7 else -> calendar.get(Calendar.DAY_OF_WEEK) - 1 }
        val currentMinute = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
        return (gapSuggestions + freeSuggestions + fallbackSuggestions)
            .distinctBy { it.weekday to it.startMinute }
            .filter { it.weekday > currentDay || (it.weekday == currentDay && it.startMinute > currentMinute + 15) }
            .filterNot { overlapsOccupied(it, goal.durationMinutes, occupied) }
            .sortedWith(compareBy<GoalSuggestion> { it.weekday }.thenBy { it.startMinute })
    }

    /** 建议时段 [start, start+duration) 是否与已有安排重叠。 */
    private fun overlapsOccupied(suggestion: GoalSuggestion, durationMinutes: Int, occupied: Map<Int, List<IntRange>>): Boolean {
        val end = suggestion.startMinute + durationMinutes
        return occupied[suggestion.weekday].orEmpty().any { it.first < end && suggestion.startMinute < it.last + 1 }
    }

    fun nextOccurrence(weekday: Int, minuteOfDay: Int): Long {
        val calendar = Calendar.getInstance()
        val currentDay = when (calendar.get(Calendar.DAY_OF_WEEK)) { Calendar.SUNDAY -> 7 else -> calendar.get(Calendar.DAY_OF_WEEK) - 1 }
        var days = (weekday - currentDay + 7) % 7
        calendar.set(Calendar.HOUR_OF_DAY, minuteOfDay / 60)
        calendar.set(Calendar.MINUTE, minuteOfDay % 60)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        if (days == 0 && calendar.timeInMillis <= System.currentTimeMillis()) days = 7
        calendar.add(Calendar.DAY_OF_YEAR, days)
        return calendar.timeInMillis
    }

    fun displayTime(minuteOfDay: Int) = "%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)

    fun weeklyAdvice(goal: Goal, feedback: List<TaskFeedback>): String {
        val full = completedThisWeek(goal)
        val minimum = minimumCompletedThisWeek(goal)
        if (full >= goal.weeklyTarget) return "本周目标已达成。下周先保持当前安排，不急着加量。"
        val commonBarrier = feedback.filter { it.goalId == goal.id && it.barrier != "无" }
            .groupingBy { it.barrier }.eachCount().maxByOrNull { it.value }?.key
        return when (commonBarrier) {
            "时间不够" -> "本周时间不够较常见；下周可优先安排更短的空档，或直接采用最低版本。"
            "精力不足" -> "本周精力不足较常见；下周可把该目标放到更早或更轻松的时段。"
            "地点不合适" -> "地点影响了完成；下周可优先考虑图书馆、宿舍或离下一节课更近的位置。"
            "被娱乐打断" -> "可在娱乐开始前先设结束时间，并把最低版本作为结束后的过渡任务。"
            "方法不清楚" -> "下周可把教程的一个小章节或练习作为更明确的完成标准。"
            else -> if (minimum > 0) "你已用 $minimum 次最低版本维持连接；下周可保留短版安排，再尝试一次完整版本。" else "本周尚未形成足够记录；下周先安排一次小而明确的任务即可。"
        }
    }
}
