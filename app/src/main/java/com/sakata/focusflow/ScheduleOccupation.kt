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
    val title: String = "",
    val weekday: Int = 0
)

/**
 * 共享占用判定：课程、课间通勤、已排任务统一成可查询的占用区间，
 * 供冲突校验、建议引擎（FlexiblePlanner/GoalPlanner 思路）、时间轴标注复用。
 * 纯 Kotlin，无 Android 依赖，可单测。
 */
object ScheduleOccupation {
    /** 全应用唯一的缓冲常量：与固定安排建议保留的分钟数。 */
    const val BUFFER_MINUTES = 15
    /** 只在短课间显示通勤；更长间隔由用户自由安排，不默认占用。 */
    const val MAX_COMMUTE_GAP_MINUTES = 90

    fun courseBlocks(courses: List<Course>, weekday: Int): List<OccupiedBlock> =
        courses.filter { !it.needsConfirmation && it.enabled && it.weekday == weekday }.map {
            OccupiedBlock(
                CourseGapPlanner.periodStart(it.startPeriod),
                CourseGapPlanner.periodEnd(it.endPeriod),
                "course", it.title, weekday
            )
        }

    /**
     * 同一天相邻已确认课程之间的通勤占用：只处理 90 分钟内的短课间，
     * 从下课时刻起算并截断到下一课程开始；更长间隔不默认占用。profile.enabled 时才算。
     */
    fun commuteBlocks(courses: List<Course>, profile: CommuteProfile?): List<OccupiedBlock> {
        if (profile?.enabled != true) return emptyList()
        return courses.filter { !it.needsConfirmation }
            .groupBy { it.weekday }
            .values
            .flatMap { daily ->
                val ordered = daily.filter { it.enabled }.sortedBy { it.startPeriod }
                if (ordered.size < 2) return@flatMap emptyList()
                var boundary = ordered.first()
                ordered.drop(1).mapNotNull { to ->
                    val from = boundary
                    val classEnds = CourseGapPlanner.periodEnd(from.endPeriod)
                    val nextStarts = CourseGapPlanner.periodStart(to.startPeriod)
                    val gap = nextStarts - classEnds
                    val block = if (gap <= 0 || gap > MAX_COMMUTE_GAP_MINUTES) null else {
                        val travel = ZijingangTravel.estimateMinutes(from.zone, to.zone, profile)
                        val end = minOf(classEnds + travel, nextStarts)
                        if (end > classEnds) OccupiedBlock(classEnds, end, "commute", "", from.weekday) else null
                    }
                    if (CourseGapPlanner.periodEnd(to.endPeriod) > classEnds) boundary = to
                    block
                }
            }
    }

    /** 当天已经安排的未完成任务占用（排除 excludeId，空 scheduledAt/已完成不占）。 */
    fun taskBlocks(items: List<Item>, weekday: Int, excludeId: Long = 0L, targetDay: Long? = null): List<OccupiedBlock> =
        items.filter { item ->
            item.id != excludeId && !item.done && !item.dayOnly &&
                item.kind !in setOf("收集箱", "暂停") && item.scheduledAt != null
        }.flatMap { item ->
            segments(requireNotNull(item.scheduledAt), item.durationMinutes)
                .filter { (day, _) -> if (targetDay != null) sameDate(day, targetDay) else weekdayOf(day) == weekday }
                .map { (day, range) -> OccupiedBlock(range.first, range.last + 1, "task", item.title, weekdayOf(day)) }
        }

    /** 共享占用的原始块（未加缓冲），用于「与什么重叠」的说明。 */
    fun dayOccupiedBlocks(
        weekday: Int,
        courses: List<Course>,
        items: List<Item>,
        profile: CommuteProfile?,
        excludeId: Long = 0L,
        targetDay: Long? = null
    ): List<OccupiedBlock> {
        val active = if (targetDay == null) courses else courses.filter {
            CourseActivationPolicy.isActiveOn(it, java.time.Instant.ofEpochMilli(targetDay)
                .atZone(java.time.ZoneId.systemDefault()).toLocalDate().toEpochDay())
        }
        return courseBlocks(active, weekday) + commuteBlocks(active, profile).filter { it.weekday == weekday } +
            taskBlocks(items, weekday, excludeId, targetDay)
    }

    /** 占用判定区间：原始块两边各膨胀 BUFFER 后归并（返回的 IntRange 视为 [first, last+1) 半开）。 */
    fun dayOccupied(
        weekday: Int,
        courses: List<Course>,
        items: List<Item>,
        profile: CommuteProfile?,
        excludeId: Long = 0L,
        targetDay: Long? = null
    ): List<IntRange> {
        val ranges = dayOccupiedBlocks(weekday, courses, items, profile, excludeId, targetDay)
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
        excludeId: Long = 0L,
        targetDay: Long? = null
    ): Int? {
        val occupied = dayOccupied(weekday, courses, items, profile, excludeId, targetDay)
        var start = maxOf(TIMELINE_START_MINUTE, fromMinute)
        while (start + duration <= TIMELINE_END_MINUTE) {
            val blocker = occupied.firstOrNull { start < it.last + 1 && start + duration > it.first }
            if (blocker == null) return start
            start = maxOf(start + 5, blocker.last + 1)
        }
        return null
    }

    fun sameDate(a: Long, b: Long): Boolean {
        val zone = java.time.ZoneId.systemDefault()
        return java.time.Instant.ofEpochMilli(a).atZone(zone).toLocalDate() ==
            java.time.Instant.ofEpochMilli(b).atZone(zone).toLocalDate()
    }

    /** Split an absolute interval at local midnight; never equate dates by weekday. */
    fun segments(at: Long, durationMinutes: Int): List<Pair<Long, IntRange>> {
        val end = at + durationMinutes.coerceIn(5, 360) * 60_000L
        val zone = java.time.ZoneId.systemDefault()
        var cursor = at
        return buildList {
            while (cursor < end) {
                val date = java.time.Instant.ofEpochMilli(cursor).atZone(zone).toLocalDate()
                val midnight = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                val stop = minOf(end, midnight)
                val startMinute = minuteOfDay(cursor)
                val endMinute = if (stop == midnight) 1440 else minuteOfDay(stop)
                if (endMinute > startMinute) add(cursor to (startMinute until endMinute))
                cursor = stop
            }
        }
    }

    /** 某时刻落在哪个星期几（周一=1 … 周日=7），与 FlexiblePlanner 的 weekday 约定一致。 */
    fun weekdayOf(time: Long): Int = Calendar.getInstance().apply { timeInMillis = time }
        .get(Calendar.DAY_OF_WEEK).let { if (it == Calendar.SUNDAY) 7 else it - 1 }

    /** 某时刻当天的分钟数（0..1439）。 */
    fun minuteOfDay(time: Long): Int = Calendar.getInstance().apply { timeInMillis = time }
        .let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }
}

/** 本周日程里已有安排（有固定时间的任务/事项）按星期几的占用分钟段；dayOnly 与仅时间范围的任务不算固定占用。 */
internal fun occupiedByWeekday(items: List<Item>, weekKey: Long = GoalPlanner.currentWeekKey()): Map<Int, List<IntRange>> {
    val weekEnd = Calendar.getInstance().apply { timeInMillis = weekKey; add(Calendar.DAY_OF_YEAR, 7) }.timeInMillis
    return items.filter { !it.done && !it.dayOnly && it.kind !in setOf("收集箱", "暂停") }.flatMap { item ->
        item.scheduledAt?.let { ScheduleOccupation.segments(it, item.durationMinutes) }.orEmpty()
    }.filter { (day, _) -> day >= weekKey && day < weekEnd }
        .map { (day, range) -> ScheduleOccupation.weekdayOf(day) to range
    }.groupBy({ it.first }, { it.second })
}

/** Exact-date task occupation without course or buffer; used to build date-correct gap suggestions. */
internal fun occupiedOnDate(items: List<Item>, targetDay: Long): List<IntRange> =
    ScheduleOccupation.taskBlocks(items, ScheduleOccupation.weekdayOf(targetDay), targetDay = targetDay)
        .map {
            (it.startMinute - ScheduleOccupation.BUFFER_MINUTES).coerceAtLeast(0) until
                (it.endMinute + ScheduleOccupation.BUFFER_MINUTES).coerceAtMost(24 * 60)
        }

internal fun coursesOverlap(a: Course, b: Course): Boolean =
    a.weekday == b.weekday && a.startPeriod <= b.endPeriod && b.startPeriod <= a.endPeriod

/** 本地判断：某时间点安排 durationMinutes 是否与课程/通勤/已有安排冲突（自动排计划用）。 */
internal fun slotFree(
    target: Long,
    durationMinutes: Int,
    courses: List<Course>,
    items: List<Item>,
    profile: CommuteProfile? = null
): Boolean {
    return ScheduleOccupation.segments(target, durationMinutes).all { (day, range) ->
        !ScheduleOccupation.overlaps(range.first, range.last + 1,
            ScheduleOccupation.dayOccupied(ScheduleOccupation.weekdayOf(day), courses, items, profile, targetDay = day))
    }
}
