package com.sakata.focusflow

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「现在做什么」推荐引擎：从临近日程和未定时任务中给出低压力建议。
 * 回答“我现在最适合干什么”——只给建议，不自动开始/改期（文档 2.1「可解释推荐」原则）。
 * 纯 Kotlin、无 Android 依赖、可单测；6.9 起从 MainActivity 抽出。
 */
internal data class NextActionSuggestion(
    val item: Item,
    val reason: String,
    val minimumVersion: String? = null,
    val minimumMinutes: Int = 10
)

internal object NextActionPlanner {
    /**
     * 推荐顺序：错过任务 → 过期弹性窗口 → 90 分钟内固定安排 → 精力+缓冲过滤弹性任务 → 兜底。
     * 只读；每种选择都给出可解释理由（文档 2.1）。
     */
    fun recommend(items: List<Item>, nextCommitment: ActivityCommitment?, energyLevel: String = "正常", goals: List<Goal> = emptyList(), feedback: List<TaskFeedback> = emptyList(), now: Long = System.currentTimeMillis(), courses: List<Course> = emptyList(), profile: CommuteProfile? = null): NextActionSuggestion? {
        val candidates = items.filter { !it.done && it.kind !in setOf("收集箱", "暂停", "计划") }
        fun recommendation(item: Item, baseReason: String): NextActionSuggestion {
            val goal = item.goalId?.let { id -> goals.firstOrNull { it.id == id } }
            val minimum = goal?.minimumVersion?.takeIf { it.isNotBlank() }
            val commonBarrier = goal?.let { current ->
                feedback.filter { it.goalId == current.id && it.barrier != "无" }.takeLast(12)
                    .groupingBy(TaskFeedback::barrier).eachCount().maxByOrNull { it.value }?.key
            }
            val recommendMinimum = minimum != null && (energyLevel == "偏低" || commonBarrier in setOf("时间不够", "精力不足"))
            val reason = if (recommendMinimum) "$baseReason 结合当前精力或近期反馈，也可以先做“$minimum”。" else baseReason
            return NextActionSuggestion(item, reason, minimum, (goal?.durationMinutes?.div(3) ?: 10).coerceIn(5, 15))
        }
        val scheduled = candidates.filter { it.scheduledAt != null }
        val overdue = scheduled.filter { (it.scheduledAt ?: Long.MAX_VALUE) < now }.maxByOrNull { it.scheduledAt ?: Long.MIN_VALUE }
        if (overdue != null) {
            return recommendation(overdue, "原定 ${formatDateTime(overdue.scheduledAt ?: now)}，尚未确认完成；现在不合适时可以重新安排。")
        }
        val expiredWindow = candidates.filter { it.scheduledAt == null && (it.windowEndAt ?: Long.MAX_VALUE) < now }.maxByOrNull { it.windowEndAt ?: Long.MIN_VALUE }
        if (expiredWindow != null) {
            return recommendation(expiredWindow, "原先保留到 ${formatDateTime(expiredWindow.windowEndAt ?: now)} 的弹性范围已经过去；可以重新选择范围或直接开始。")
        }

        val upcoming = scheduled.filter { (it.scheduledAt ?: Long.MIN_VALUE) >= now }.minByOrNull { it.scheduledAt ?: Long.MAX_VALUE }
        val minutesUntilUpcoming = upcoming?.scheduledAt?.let { ((it - now) / 60_000L).toInt().coerceAtLeast(0) }
        if (upcoming != null && minutesUntilUpcoming != null && minutesUntilUpcoming <= 90) {
            return recommendation(upcoming, "${formatTime(upcoming.scheduledAt ?: now)} 开始，是最近的固定安排（约 $minutesUntilUpcoming 分钟后）。")
        }

        val flexible = candidates.filter {
            it.scheduledAt == null && (it.windowStartAt == null || it.windowStartAt <= now) && (it.windowEndAt == null || it.windowEndAt >= now)
        }
        val minutesBeforeCommitment = nextCommitment?.let { ((it.startsAt - now) / 60_000L).toInt().coerceAtLeast(0) }
        val usableMinutes = minutesBeforeCommitment?.minus(15)?.coerceAtLeast(0)
        // 6.9 兜底：今日剩余空挡放不下的不推（只过滤明确不可行，不改变现有主序）。
        val remainingMinutes = remainingFreeMinutes(now, courses, items, profile)
        val fittingCandidates = flexible.filter { candidate ->
            (usableMinutes == null || candidate.durationMinutes <= usableMinutes) &&
                (remainingMinutes == null || candidate.durationMinutes <= remainingMinutes)
        }
        // 6.9：弹性候选同分按优先级降序（HIGH 先）——仅次级排序，不改主序；低精/正常/充足三分支统一。
        val priorityRank: (Item) -> Int = { when (ItemPriority.fromKey(it.priority)) {
            ItemPriority.HIGH -> 2; ItemPriority.MID -> 1; ItemPriority.LOW -> 0
        } }
        val fitting = when (energyLevel) {
            "偏低" -> fittingCandidates.sortedWith(compareBy<Item> { if (it.durationMinutes <= 30) 0 else 1 }.thenByDescending(priorityRank).thenBy { it.durationMinutes }).firstOrNull()
            "充足" -> fittingCandidates.sortedWith(compareByDescending<Item> { it.goalId != null }.thenByDescending(priorityRank).thenByDescending { it.durationMinutes }).firstOrNull()
            else -> fittingCandidates.sortedWith(compareByDescending<Item> { it.goalId != null }.thenByDescending(priorityRank).thenBy { it.durationMinutes }).firstOrNull()
        }
        if (fitting != null) {
            val priorityNote = if (ItemPriority.fromKey(fitting.priority) == ItemPriority.HIGH) "（标注了高优先级）" else ""
            val reason = if (minutesBeforeCommitment == null) {
                when (energyLevel) {
                    "偏低" -> "当前精力偏低$priorityNote；优先选择预计 ${fitting.durationMinutes} 分钟、较容易启动的一项。"
                    "充足" -> "当前精力充足$priorityNote；优先推进较完整或与目标相关的一项。"
                    else -> "当前没有临近的固定安排$priorityNote；这项任务可以直接开始。"
                }
            } else {
                "距离 ${nextCommitment.title} 约 $minutesBeforeCommitment 分钟$priorityNote；按当前精力选择本项，预计 ${fitting.durationMinutes} 分钟并保留 15 分钟缓冲。"
            }
            return recommendation(fitting, reason)
        }

        if (upcoming != null) {
            return recommendation(upcoming, "今天下一项固定安排在 ${formatTime(upcoming.scheduledAt ?: now)}；当前空档不足以稳妥放入其他任务。")
        }
        val fallback = flexible.minByOrNull(Item::durationMinutes)
            ?: return null
        // 6.9：全天剩余空挡连最短任务都放不下时不硬推（UI 显示「没有必须现在做的事」）。
        if (remainingMinutes != null && remainingMinutes < fallback.durationMinutes) return null
        return recommendation(fallback, "当前没有临近固定安排；先从预计用时较短的一项开始。")
    }

    /**
     * 今日剩余空挡（分钟）：当前分钟 → 日结束，按占用判定（课程+任务+通勤，含 BUFFER）求空闲合计。
     * 全天已无空隙返回 0；不作限制时返回 null。仅用于「放不下不推」的兜底过滤。
     */
    fun remainingFreeMinutes(now: Long, courses: List<Course>, items: List<Item>, profile: CommuteProfile?): Int? {
        val weekday = ScheduleOccupation.weekdayOf(now)
        val fromMinute = ScheduleOccupation.minuteOfDay(now)
        val endOfDay = 24 * 60
        if (fromMinute >= endOfDay) return null
        val occupied = ScheduleOccupation.dayOccupied(weekday, courses, items, profile)
        var free = 0
        var cursor = fromMinute
        while (cursor < endOfDay) {
            val blocker = occupied.firstOrNull { cursor >= it.first && cursor < it.last + 1 }
            if (blocker == null) {
                val nextBlockStart = occupied.firstOrNull { it.first >= cursor }?.first ?: endOfDay
                free += nextBlockStart - cursor
                cursor = nextBlockStart
            } else {
                cursor = blocker.last + 1
            }
        }
        return free
    }

    /** 下一条固定安排（任务+课程），「距离下一个承诺」的判断输入。 */
    fun nextCommitment(items: List<Item>, courses: List<Course>, now: Long = System.currentTimeMillis()): ActivityCommitment? {
        val taskCommitments = items.mapNotNull { item -> item.scheduledAt?.takeIf { !item.done && it > now }?.let { ActivityCommitment(item.title, it) } }
        val courseCommitments = courses.filter { !it.needsConfirmation && it.weekday == todayWeekday() }.mapNotNull { course ->
            val startsAt = todayAtMinute(CourseGapPlanner.periodStart(course.startPeriod))
            startsAt.takeIf { it > now }?.let { ActivityCommitment("${course.title}（${course.building}）", it) }
        }
        return (taskCommitments + courseCommitments).minByOrNull(ActivityCommitment::startsAt)
    }

    private fun todayAtMinute(minute: Int): Long = java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.HOUR_OF_DAY, minute / 60)
        set(java.util.Calendar.MINUTE, minute % 60)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun formatTime(time: Long): String = SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(time))
}
