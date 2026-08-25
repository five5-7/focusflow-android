package com.sakata.focusflow

internal const val TIMELINE_START_MINUTE = 6 * 60
internal const val TIMELINE_END_MINUTE = 24 * 60

internal enum class ScheduleType(val label: String) {
    COURSE("课程"),
    LEARNING("学习／目标"),
    EXERCISE("锻炼"),
    ENTERTAINMENT("娱乐"),
    REST("休息"),
    TASK("弹性任务"),
    COMPLETED("已完成")
}

internal data class TimelineEvent(
    val key: String,
    val title: String,
    val detail: String,
    val weekday: Int,
    val startMinute: Int,
    val endMinute: Int,
    val type: ScheduleType,
    val item: Item? = null,
    val isConflict: Boolean = false
)

internal data class TimelineEventLayout(
    val event: TimelineEvent,
    val lane: Int,
    val laneCount: Int
)

internal data class GapMarker(
    val startMinute: Int,
    val endMinute: Int,
    val minutes: Int
)

/** 把同一时间段互相重叠的课程合并为单一事件（覆盖整个冲突区间），其余事件原样保留。 */
internal fun mergeConflictingCourses(events: List<TimelineEvent>): List<TimelineEvent> {
    val others = events.filter { it.type != ScheduleType.COURSE }
    val courseEvents = events.filter { it.type == ScheduleType.COURSE }
        .sortedWith(compareBy<TimelineEvent> { it.startMinute }.thenByDescending { it.endMinute })
    if (courseEvents.size < 2) return events
    val merged = mutableListOf<TimelineEvent>()
    var index = 0
    while (index < courseEvents.size) {
        val group = mutableListOf(courseEvents[index])
        var groupStart = courseEvents[index].startMinute
        var groupEnd = courseEvents[index].endMinute
        var next = index + 1
        while (next < courseEvents.size && courseEvents[next].startMinute < groupEnd) {
            group += courseEvents[next]
            groupStart = minOf(groupStart, courseEvents[next].startMinute)
            groupEnd = maxOf(groupEnd, courseEvents[next].endMinute)
            next++
        }
        merged += if (group.size > 1) {
            TimelineEvent(
                key = group.joinToString("+") { it.key },
                title = group.joinToString(" ／ ") { it.title },
                detail = group.joinToString("；") {
                    "${formatScheduleMinute(it.startMinute)}–${formatScheduleMinute(it.endMinute)} ${it.detail}"
                },
                weekday = group.first().weekday,
                startMinute = groupStart,
                endMinute = groupEnd,
                type = ScheduleType.COURSE,
                isConflict = true
            )
        } else {
            group.first()
        }
        index = next
    }
    return merged + others
}

internal fun layoutTimelineEvents(events: List<TimelineEvent>): List<TimelineEventLayout> {
    val visible = events
        .filter { it.endMinute > TIMELINE_START_MINUTE && it.startMinute < TIMELINE_END_MINUTE }
        .sortedWith(compareBy<TimelineEvent> { it.startMinute }.thenByDescending { it.endMinute })
    val result = mutableListOf<TimelineEventLayout>()
    var index = 0
    while (index < visible.size) {
        val group = mutableListOf(visible[index])
        var groupEnd = visible[index].endMinute
        var next = index + 1
        while (next < visible.size && visible[next].startMinute < groupEnd) {
            group += visible[next]
            groupEnd = maxOf(groupEnd, visible[next].endMinute)
            next++
        }
        val laneEnds = mutableListOf<Int>()
        val assigned = group.map { event ->
            val freeLane = laneEnds.indexOfFirst { it <= event.startMinute }
            val lane = if (freeLane >= 0) freeLane else {
                laneEnds.add(event.endMinute)
                laneEnds.lastIndex
            }
            laneEnds[lane] = event.endMinute
            event to lane
        }
        val laneCount = laneEnds.size.coerceAtLeast(1)
        result += assigned.map { (event, lane) -> TimelineEventLayout(event, lane, laneCount) }
        index = next
    }
    return result
}

private fun formatScheduleMinute(minute: Int): String =
    "%02d:%02d".format(minute / 60, minute % 60)
