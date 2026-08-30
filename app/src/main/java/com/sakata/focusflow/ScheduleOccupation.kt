package com.sakata.focusflow

import java.util.Calendar

/**
 * 一周某一天的共享占用块（分钟制，[startMinute, endMinute) 半开区间）。
 * kind：course / commute / task。
 */
data class OccupiedBlock(
    val startMinute: Int,
    val endMinute: Int,
    val kind: String,
    val title: String = ""
)

/**
 * 共享占用判定：课程、课间通勤、已排任务统一成可查询的占用区间，
 * 供冲突校验、建议引擎（FlexiblePlanner/GoalPlanner 思路）、时间轴标注复用。
 * 纯 Kotlin，无 Android 依赖，可单测。
 */
object ScheduleOccupation {
    /** 全应用唯一的缓冲常量：与固定安排建议保留的分钟数。 */
    const val BUFFER_MINUTES = 15

    fun courseBlocks(courses: List<Course>, weekday: Int): List<OccupiedBlock> =
        courses.filter { !it.needsConfirmation && it.weekday == weekday }.map {
            OccupiedBlock(
                CourseGapPlanner.periodStart(it.startPeriod),
                CourseGapPlanner.periodEnd(it.endPeriod),
                "course", it.title
            )
        }

    /**
     * 同一天相邻已确认课程之间的通勤占用：从下课时刻起算，截断到下一课程开始
     * （赶不上的情况不产生与课程视觉重叠的块）。profile.enabled 时才算。
     */
    fun commuteBlocks(courses: List<Course>, profile: CommuteProfile?): List<OccupiedBlock> {
        if (profile?.enabled != true) return emptyList()
        return courses.filter { !it.needsConfirmation }
            .groupBy { it.weekday }
            .values
            .flatMap { daily ->
                daily.sortedBy { it.startPeriod }.zipWithNext().mapNotNull { (from, to) ->
                    val classEnds = CourseGapPlanner.periodEnd(from.endPeriod)
                    val nextStarts = CourseGapPlanner.periodStart(to.startPeriod)
                    val travel = ZijingangTravel.estimateMinutes(from.zone, to.zone, profile)
                    val end = minOf(classEnds + travel, nextStarts)
                    if (end > classEnds) OccupiedBlock(classEnds, end, "commute", "通勤") else null
                }
            }
    }

    /** 当天已经安排的未完成任务占用（排除 excludeId，空 scheduledAt/已完成不占）。 */
    fun taskBlocks(items: List<Item>, weekday: Int, excludeId: Long = 0L): List<OccupiedBlock> =
        items.filter { other ->
            other.id != excludeId && !other.done && other.scheduledAt != null && weekdayOf(other.scheduledAt) == weekday
        }.map {
            val start = minuteOfDay(requireNotNull(it.scheduledAt))
            OccupiedBlock(start, start + it.durationMinutes.coerceIn(5, 360), "task", it.title)
        }

    /** 共享占用的原始块（未加缓冲），用于「与什么重叠」的说明。 */
    fun dayOccupiedBlocks(
        weekday: Int,
        courses: List<Course>,
        items: List<Item>,
        profile: CommuteProfile?,
        excludeId: Long = 0L
    ): List<OccupiedBlock> =
        courseBlocks(courses, weekday) + commuteBlocks(courses, profile) +
            taskBlocks(items, weekday, excludeId)

    /** 占用判定区间：原始块两边各膨胀 BUFFER 后归并（返回的 IntRange 视为 [first, last+1) 半开）。 */
    fun dayOccupied(
        weekday: Int,
        courses: List<Course>,
        items: List<Item>,
        profile: CommuteProfile?,
        excludeId: Long = 0L
    ): List<IntRange> {
        val ranges = dayOccupiedBlocks(weekday, courses, items, profile, excludeId)
            .map { (it.startMinute - BUFFER_MINUTES) until (it.endMinute + BUFFER_MINUTES) }
            .filter { it.last >= it.first }
            .sortedBy { it.first }
        val merged = mutableListOf<IntRange>()
        ranges.forEach { range ->
            if (merged.isEmpty() || range.first > merged.last().last + 1) merged += range
            else merged[merged.size - 1] = merged.last().first..maxOf(merged.last().last, range.last)
        }
        return merged
    }

    /** [start, end) 与任一占用判定区间重叠（相接【同一分钟】不算重叠）。 */
    fun overlaps(start: Int, end: Int, occupied: List<IntRange>): Boolean =
        occupied.any { start < it.last + 1 && end > it.first }

    /** 与任一占用块（未加缓冲）真实重叠。用于给出「与什么重叠」的说明。 */
    fun conflictingBlock(blocks: List<OccupiedBlock>, start: Int, end: Int): OccupiedBlock? =
        blocks.firstOrNull { start < it.endMinute && end > it.startMinute }

    /**
     * 从 fromMinute 起找第一个能放下 duration 分钟（含 BUFFER）的空档，限当天
     * [TIMELINE_START_MINUTE, TIMELINE_END_MINUTE)；找不到返回 null。
     */
    fun nextFreeSlot(
        weekday: Int,
        fromMinute: Int,
        duration: Int,
        courses: List<Course>,
        items: List<Item>,
        profile: CommuteProfile?,
        excludeId: Long = 0L
    ): Int? {
        val occupied = dayOccupied(weekday, courses, items, profile, excludeId)
        var start = maxOf(TIMELINE_START_MINUTE, fromMinute)
        while (start + duration <= TIMELINE_END_MINUTE) {
            val blocker = occupied.firstOrNull { start < it.last + 1 && start + duration > it.first }
            if (blocker == null) return start
            start = maxOf(start + 5, blocker.last + 1)
        }
        return null
    }

    /** 某时刻落在哪个星期几（周一=1 … 周日=7），与 FlexiblePlanner 的 weekday 约定一致。 */
    fun weekdayOf(time: Long): Int = Calendar.getInstance().apply { timeInMillis = time }
        .get(Calendar.DAY_OF_WEEK).let { if (it == Calendar.SUNDAY) 7 else it - 1 }

    /** 某时刻当天的分钟数（0..1439）。 */
    fun minuteOfDay(time: Long): Int = Calendar.getInstance().apply { timeInMillis = time }
        .let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }
}
