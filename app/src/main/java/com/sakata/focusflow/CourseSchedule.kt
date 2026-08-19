package com.sakata.focusflow

data class Course(
    val title: String,
    val weekday: Int,
    val startPeriod: Int,
    val endPeriod: Int,
    val building: String,
    val zone: CampusZone,
    val needsConfirmation: Boolean = true
)

data class CourseGap(val from: Course, val to: Course, val minutesFree: Int, val travelMinutes: Int, val suggestedStartMinute: Int)

/** 自由时段：课间空挡之外的可用时间——课后到晚上的整块空闲、没有课的整天。 */
data class FreeWindow(
    val weekday: Int,
    val startMinute: Int,
    val endMinute: Int,
    val minutes: Int,
    /** "课后空闲" 或 "整天空闲" */
    val kind: String
)

/** 课程从课表截图或手动录入，发布版不再内置任何示例课程。 */

object CourseGapPlanner {
    // Each teaching period is modeled as 45 minutes; longer breaks are retained in the timetable start times.
    private val periodStarts = listOf(480, 530, 600, 650, 700, 805, 855, 905, 975, 1025, 1130, 1180, 1230)

    fun periodStart(period: Int): Int = periodStarts[period.coerceIn(1, periodStarts.size) - 1]
    fun periodEnd(period: Int): Int = periodStart(period) + 45

    /** occupied：日程里已有安排（任务/事项）按星期几的占用分钟段；计算空挡时会扣除这些占用。 */
    fun gaps(courses: List<Course>, profile: CommuteProfile, occupied: Map<Int, List<IntRange>> = emptyMap()): List<CourseGap> = courses
        .groupBy { it.weekday }
        .values
        .flatMap { daily ->
            daily.sortedBy { it.startPeriod }.zipWithNext().map { (from, to) ->
                val classEnds = periodStarts[from.endPeriod - 1] + 45
                val nextStarts = periodStarts[to.startPeriod - 1]
                val travel = ZijingangTravel.estimateMinutes(from.zone, to.zone, profile)
                val (start, minutes) = longestFreeRun(classEnds + travel, nextStarts, occupied[from.weekday].orEmpty())
                CourseGap(from, to, minutes, travel, start)
            }
        }

    /** 课间空挡之外的自由时段：最后一节课后到晚上、以及没有课的整天；扣除已有安排后切成剩余子段。课间空挡仍由 gaps() 提供。 */
    fun freeWindows(courses: List<Course>, dayStartMinute: Int = 8 * 60, dayEndMinute: Int = 22 * 60, occupied: Map<Int, List<IntRange>> = emptyMap()): List<FreeWindow> {
        val confirmed = courses.filter { !it.needsConfirmation }
        return (1..7).flatMap { weekday ->
            val daily = confirmed.filter { it.weekday == weekday }.sortedBy { it.startPeriod }
            val base = if (daily.isEmpty()) {
                listOf(FreeWindow(weekday, dayStartMinute, dayEndMinute, dayEndMinute - dayStartMinute, "整天空闲"))
            } else {
                val lastEnd = periodStarts[daily.last().endPeriod - 1] + 45
                listOf(FreeWindow(weekday, lastEnd, dayEndMinute, dayEndMinute - lastEnd, "课后空闲"))
            }
            base.flatMap { subtractOccupied(it, occupied[weekday].orEmpty()) }.filter { it.minutes >= 60 }
        }
    }

    /** 在 [start, end) 区间内扣除占用段，返回最长连续空闲段的起点与长度（全被占用时长度 0）。 */
    private fun longestFreeRun(start: Int, end: Int, occupied: List<IntRange>): Pair<Int, Int> {
        val ranges = occupied
            .map { it.first.coerceIn(start, end) to (it.last + 1).coerceIn(start, end) }
            .filter { it.first < it.second }
            .sortedBy { it.first }
        val merged = mutableListOf<Pair<Int, Int>>()
        ranges.forEach { (s, e) ->
            if (merged.isEmpty() || s > merged.last().second) merged += s to e
            else merged[merged.size - 1] = merged.last().first to maxOf(merged.last().second, e)
        }
        var cursor = start
        var bestStart = start
        var bestLen = 0
        merged.forEach { (s, e) ->
            if (s > cursor && s - cursor > bestLen) { bestStart = cursor; bestLen = s - cursor }
            cursor = maxOf(cursor, e)
        }
        if (end - cursor > bestLen) { bestStart = cursor; bestLen = end - cursor }
        return bestStart to bestLen
    }

    /** 从自由时段中扣除占用段，切成剩余的子段（占用段按 [start, end) 半开区间处理）。 */
    private fun subtractOccupied(window: FreeWindow, occupied: List<IntRange>): List<FreeWindow> {
        var segments = listOf(window.startMinute to window.endMinute)
        occupied.forEach { range ->
            val start = range.first.coerceIn(window.startMinute, window.endMinute)
            val end = (range.last + 1).coerceIn(window.startMinute, window.endMinute)
            if (start < end) {
                segments = segments.flatMap { (s, e) ->
                    if (end <= s || start >= e) listOf(s to e)
                    else listOfNotNull((s to start).takeIf { start > s }, (end to e).takeIf { e > end })
                }
            }
        }
        return segments.map { (s, e) -> FreeWindow(window.weekday, s, e, e - s, window.kind) }
    }
}
