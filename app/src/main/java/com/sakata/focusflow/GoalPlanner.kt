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
    val selected: Boolean = false
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

data class RoadmapFeature(val id: String, val version: String, val title: String)

object RoadmapCatalog {
    val features = listOf(
        RoadmapFeature("v2_activity_transition", "V2", "活动计时、分级预告与结束后的下一步转场"),
        RoadmapFeature("v2_custom_time", "V2", "任意日期与时间的改期选择"),
        RoadmapFeature("v2_course_ocr", "V2", "课表截图 OCR 识别与逐项确认"),
        RoadmapFeature("v2_route_calibration", "V2", "紫金港路线耗时的实际校正"),
        RoadmapFeature("v2_goal_review", "V2", "每周目标回顾与低压力调整建议"),
        RoadmapFeature("v3_resource_search", "V3", "可选联网搜集教程并比较候选来源"),
        RoadmapFeature("v3_activity_time_suggestion", "V3", "开始活动时按历史与下一项日程建议预计时长或结束时间"),
        RoadmapFeature("v3_context_learning", "V3", "经用户确认后学习实际通勤耗时、常用地点与时段"),
        RoadmapFeature("v3_campus_poi_discovery", "V3", "自动搜索校区 POI 并推断教学、学习、运动与生活用途"),
        RoadmapFeature("v3_map_pin_places", "V3", "在地图上点选缺失地点并用逆地理编码建议名称"),
        RoadmapFeature("v3_sleep_protection", "V3", "睡前减速、娱乐延长与恢复机制"),
        RoadmapFeature("v3_ebike", "V3", "电动车充电与远距离出行联动"),
        RoadmapFeature("v3_feedback_analysis", "V3", "基于反馈的长期方法与心态调整建议")
    )
}

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
    fun suggestions(goal: Goal, courses: List<Course>, profile: CommuteProfile): List<GoalSuggestion> {
        val confirmed = courses.filter { !it.needsConfirmation }
        val gapSuggestions = CourseGapPlanner.gaps(confirmed, profile)
            .filter { it.minutesFree >= goal.durationMinutes }
            .map { GoalSuggestion(it.from.weekday, it.suggestedStartMinute, it.minutesFree) }
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
        return (gapSuggestions + fallbackSuggestions)
            .distinctBy { it.weekday to it.startMinute }
            .filter { it.weekday > currentDay || (it.weekday == currentDay && it.startMinute > currentMinute + 15) }
            .sortedWith(compareBy<GoalSuggestion> { it.weekday }.thenBy { it.startMinute })
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
