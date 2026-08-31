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
    val desiredOutcome: String = "",
    /** The concrete first action for this goal; optional for 6.1 compatibility. */
    val firstAction: String = ""
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
    fun suggestions(
        goal: Goal,
        courses: List<Course>,
        profile: CommuteProfile,
        occupied: Map<Int, List<IntRange>> = emptyMap(),
        nowMillis: Long = System.currentTimeMillis()
    ): List<GoalSuggestion> {
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
        return (gapSuggestions + freeSuggestions + fallbackSuggestions)
            .distinctBy { it.weekday to it.startMinute }
            // 跨周取「从 now 起未来 7 天内最近一次出现」：周日晚上也能看到下周的空档
            .filter { suggestion ->
                val occurrence = nextOccurrence(suggestion.weekday, suggestion.startMinute, nowMillis)
                occurrence > nowMillis + 15 * 60_000L && occurrence <= nowMillis + 7 * 24 * 60 * 60_000L
            }
            .filterNot { overlapsOccupied(it, goal.durationMinutes, occupied) }
            .sortedWith(
                compareBy<GoalSuggestion> { nextOccurrence(it.weekday, it.startMinute, nowMillis) }
                    .thenBy { it.weekday }.thenBy { it.startMinute }
            )
    }

    /** 建议时段 [start, start+duration) 是否与已有安排重叠。 */
    private fun overlapsOccupied(suggestion: GoalSuggestion, durationMinutes: Int, occupied: Map<Int, List<IntRange>>): Boolean {
        val end = suggestion.startMinute + durationMinutes
        return occupied[suggestion.weekday].orEmpty().any { it.first < end && suggestion.startMinute < it.last + 1 }
    }

    fun nextOccurrence(weekday: Int, minuteOfDay: Int, nowMillis: Long = System.currentTimeMillis()): Long {
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = nowMillis
        val currentDay = when (calendar.get(Calendar.DAY_OF_WEEK)) { Calendar.SUNDAY -> 7 else -> calendar.get(Calendar.DAY_OF_WEEK) - 1 }
        var days = (weekday - currentDay + 7) % 7
        calendar.set(Calendar.HOUR_OF_DAY, minuteOfDay / 60)
        calendar.set(Calendar.MINUTE, minuteOfDay % 60)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        if (days == 0 && calendar.timeInMillis <= nowMillis) days = 7
        calendar.add(Calendar.DAY_OF_YEAR, days)
        return calendar.timeInMillis
    }

    fun displayTime(minuteOfDay: Int) = "%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)

    /** 自动排一周目标任务的纯计算：新任务清单、学习时段（按 星期×开始小时 记录完成率）与提示文案。 */
    data class AutoPlanResult(
        val newItems: List<Item>,
        val learnedSlots: List<Pair<Int, Int>>,
        val message: String
    )

    /**
     * 按剩余周次数把目标排进未来一周空挡（避开课程、通勤与已有安排，同批内也互相避让）。
     * [completionRate] 为完成率查询器（星期×开始小时 → 0..1 或 null），未知时段排最后。
     * 不产生副作用：保存、提醒、学习记录由调用点执行。
     */
    fun autoPlan(
        goals: List<Goal>,
        courses: List<Course>,
        items: List<Item>,
        profile: CommuteProfile,
        completionRate: (weekday: Int, startHour: Int) -> Float?,
        nowMillis: Long = System.currentTimeMillis()
    ): AutoPlanResult {
        val remainingByGoal = goals.mapNotNull { goal ->
            val remaining = goal.weeklyTarget - completedThisWeek(goal)
            if (remaining > 0) goal to remaining else null
        }
        if (remainingByGoal.isEmpty()) {
            return AutoPlanResult(emptyList(), emptyList(), "所有目标本周次数都已排满或完成，无需再排。")
        }
        val newItems = mutableListOf<Item>()
        val learnedSlots = mutableListOf<Pair<Int, Int>>()
        remainingByGoal.forEach { (goal, remaining) ->
            var scheduled = 0
            // 完成率学习：优先历史完成率高的时段（未知时段排最后）。
            val suggestions = GoalPlanner.suggestions(goal, courses, profile, occupiedByWeekday(items), nowMillis)
                .sortedWith(compareByDescending<GoalSuggestion> { completionRate(it.weekday, it.startMinute / 60) ?: -1f }.thenBy { it.startMinute })
            for (suggestion in suggestions) {
                if (scheduled >= remaining) break
                val target = GoalPlanner.nextOccurrence(suggestion.weekday, suggestion.startMinute, nowMillis)
                // 与之前已排的目标任务也避让，防止同一次自动排内重复占用同一时段。
                if (slotFree(target, goal.durationMinutes, courses, items + newItems, profile)) {
                    newItems += Item(title = goal.title, detail = goalTaskDetail(goal, suggestion.weekday, suggestion.startMinute), kind = "任务", scheduledAt = target, goalId = goal.id, durationMinutes = goal.durationMinutes)
                    learnedSlots += suggestion.weekday to suggestion.startMinute / 60
                    scheduled++
                }
            }
        }
        if (newItems.isEmpty()) {
            return AutoPlanResult(emptyList(), emptyList(), "未来一周空挡都被课程或已有安排占用，没有可排的时段；可先确认课程或调整目标时长。")
        }
        val byDay = newItems.groupBy { it.scheduledAt?.let(::weekdayOf) }.mapNotNull { (day, list) -> day?.let { "${weekdayName(it)} ${list.size} 个" } }.joinToString("、")
        return AutoPlanResult(newItems, learnedSlots, "已把 ${newItems.size} 个目标任务排进未来一周空挡（避开课程与已有安排）：$byDay。可在日程里查看或调整。")
    }

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
