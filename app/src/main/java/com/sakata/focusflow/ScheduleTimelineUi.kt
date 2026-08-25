package com.sakata.focusflow

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
internal fun scheduleColor(type: ScheduleType): Color {
    val palette = LocalFocusFlowSchedulePalette.current
    return when (type) {
        ScheduleType.COURSE -> palette.course
        ScheduleType.LEARNING -> palette.learning
        ScheduleType.EXERCISE -> palette.exercise
        ScheduleType.ENTERTAINMENT -> palette.entertainment
        ScheduleType.REST -> palette.rest
        ScheduleType.TASK -> palette.task
        ScheduleType.COMPLETED -> palette.completed
    }
}

internal fun Item.scheduleType(): ScheduleType = when {
    title.contains("锻炼") || title.contains("拉伸") -> ScheduleType.EXERCISE
    title.contains("游戏") || title.contains("娱乐") -> ScheduleType.ENTERTAINMENT
    title.contains("睡前") || kind == "习惯" -> ScheduleType.REST
    goalId != null -> ScheduleType.LEARNING
    else -> ScheduleType.TASK
}

private val timelineHourHeight = 64.dp

internal fun Course.asTimelineEvent(index: Int = 0) = TimelineEvent(
    key = "course-$weekday-$startPeriod-$title-$index",
    title = title,
    detail = "课程 · $building",
    weekday = weekday,
    startMinute = CourseGapPlanner.periodStart(startPeriod),
    endMinute = CourseGapPlanner.periodEnd(endPeriod),
    type = ScheduleType.COURSE
)

private fun Item.asTimelineEvent(): TimelineEvent? {
    val time = scheduledAt ?: return null
    val start = minuteOfDay(time)
    return TimelineEvent(
        key = "task-$id",
        title = title,
        detail = detail,
        weekday = todayWeekday(time),
        startMinute = start,
        endMinute = (start + durationMinutes.coerceIn(5, 360)).coerceAtMost(24 * 60),
        type = if (done) ScheduleType.COMPLETED else scheduleType(),
        item = this
    )
}

@Composable
internal fun DailyScheduleTimeline(
    courses: List<Course>,
    tasks: List<Item>,
    onTaskDone: (Item) -> Unit,
    onDeleteItem: (Item) -> Unit
) {
    val events = courses.mapIndexed { index, course -> course.asTimelineEvent(index) } +
        tasks.mapNotNull { it.asTimelineEvent() }
    var selected by remember { mutableStateOf<TimelineEvent?>(null) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 10.dp)) {
            TimelineTimeAxis()
            TimelineDayLane(
                events,
                Modifier.weight(1f),
                showLabels = true,
                compactBlocks = false,
                onSelect = { selected = it }
            )
        }
    }
    TimelineLegend()
    selected?.let {
        TimelineEventDialog(
            it,
            onDismiss = { selected = null },
            onTaskDone = { item -> selected = null; onTaskDone(item) },
            onDeleteItem = { item -> selected = null; onDeleteItem(item) }
        )
    }
}

@Composable
internal fun WeeklyScheduleTimeline(
    courses: List<Course>,
    items: List<Item>,
    onTaskDone: (Item) -> Unit,
    onDeleteItem: (Item) -> Unit
) {
    val courseEvents = courses.mapIndexed { index, course -> course.asTimelineEvent(index) }
    val taskEvents = items
        .filter { !it.dayOnly && it.scheduledAt?.let(::isInCurrentWeek) == true }
        .mapNotNull { it.asTimelineEvent() }
    var selected by remember { mutableStateOf<TimelineEvent?>(null) }
    var showCourseInfo by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text("显示课程信息", fontWeight = FontWeight.SemiBold)
            Text(
                "色块保持简洁；打开后在表格下方显示课程名称与地点。",
                style = MaterialTheme.typography.bodySmall
            )
        }
        Switch(checked = showCourseInfo, onCheckedChange = { showCourseInfo = it })
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 10.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Spacer(Modifier.width(40.dp))
                (1..7).forEach { day ->
                    Surface(
                        modifier = Modifier.weight(1f).padding(horizontal = 0.5.dp),
                        color = if (day == todayWeekday()) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            Color.Transparent
                        },
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(
                            weekdayName(day),
                            Modifier.padding(vertical = 8.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
            Row(Modifier.fillMaxWidth()) {
                TimelineTimeAxis(40.dp)
                (1..7).forEach { day ->
                    TimelineDayLane(
                        (courseEvents + taskEvents).filter { it.weekday == day },
                        Modifier.weight(1f),
                        showLabels = false,
                        compactBlocks = true,
                        onSelect = { selected = it }
                    )
                }
            }
        }
    }
    TimelineLegend()
    if (showCourseInfo) {
        ElevatedCard {
            Column(
                Modifier.fillMaxWidth().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                Text("本周课程", fontWeight = FontWeight.Bold)
                courses.sortedWith(compareBy<Course> { it.weekday }.thenBy { it.startPeriod })
                    .forEach { course ->
                        Text(
                            "${weekdayName(course.weekday)}  " +
                                "${formatMinute(CourseGapPlanner.periodStart(course.startPeriod))}–" +
                                "${formatMinute(CourseGapPlanner.periodEnd(course.endPeriod))}  " +
                                "${course.title} · ${course.building}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
            }
        }
    }
    selected?.let {
        TimelineEventDialog(
            it,
            onDismiss = { selected = null },
            onTaskDone = { item -> selected = null; onTaskDone(item) },
            onDeleteItem = { item -> selected = null; onDeleteItem(item) }
        )
    }
}

@Composable
internal fun TimelineTimeAxis(width: Dp = 50.dp) {
    val totalHeight = timelineHourHeight *
        ((TIMELINE_END_MINUTE - TIMELINE_START_MINUTE) / 60).toFloat()
    Box(Modifier.width(width).height(totalHeight)) {
        (TIMELINE_START_MINUTE / 60..TIMELINE_END_MINUTE / 60).forEach { hour ->
            Text(
                "%02d:00".format(hour),
                Modifier.offset(
                    y = timelineHourHeight *
                        (hour - TIMELINE_START_MINUTE / 60).toFloat() - 7.dp
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
internal fun TimelineDayLane(
    events: List<TimelineEvent>,
    modifier: Modifier,
    showLabels: Boolean,
    compactBlocks: Boolean,
    onSelect: (TimelineEvent) -> Unit,
    gapMarkers: List<GapMarker> = emptyList()
) {
    val mergedEvents = mergeConflictingCourses(events)
    val totalHours = (TIMELINE_END_MINUTE - TIMELINE_START_MINUTE) / 60
    val totalHeight = timelineHourHeight * totalHours.toFloat()
    val layouts = layoutTimelineEvents(mergedEvents)
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
    BoxWithConstraints(
        modifier.height(totalHeight).clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f))
    ) {
        Canvas(Modifier.matchParentSize()) {
            val step = size.height / totalHours
            for (hour in 0..totalHours) {
                drawLine(
                    gridColor,
                    Offset(0f, hour * step),
                    Offset(size.width, hour * step),
                    strokeWidth = 1f
                )
            }
        }
        layouts.forEach { layout ->
            val event = layout.event
            val eventColor = scheduleColor(event.type)
            val conflict = event.isConflict
            val topMinutes = event.startMinute.coerceAtLeast(TIMELINE_START_MINUTE) -
                TIMELINE_START_MINUTE
            val bottomMinutes = event.endMinute.coerceAtMost(TIMELINE_END_MINUTE) -
                TIMELINE_START_MINUTE
            val top = timelineHourHeight * (topMinutes / 60f)
            val height = (timelineHourHeight * ((bottomMinutes - topMinutes) / 60f))
                .coerceAtLeast(10.dp)
            val laneWidth = maxWidth / layout.laneCount.toFloat()
            val blockWidth = if (compactBlocks) laneWidth * 0.9f else laneWidth
            val blockOffset = (laneWidth - blockWidth) / 2f
            Surface(
                modifier = Modifier
                    .offset(x = laneWidth * layout.lane.toFloat() + blockOffset, y = top)
                    .width(blockWidth)
                    .height(height)
                    .clickable { onSelect(event) },
                color = if (conflict) CONFLICT_RED_BG else eventColor.copy(alpha = 0.88f),
                contentColor = Color.White,
                shape = RoundedCornerShape(7.dp),
                tonalElevation = if (conflict) 0.dp else 1.dp
            ) {
                Box(Modifier.fillMaxSize()) {
                    if (conflict) {
                        Canvas(Modifier.matchParentSize()) {
                            val step = 24.dp.toPx()
                            var x = -size.height
                            while (x < size.width) {
                                drawLine(
                                    CONFLICT_STRIPE_COLOR,
                                    Offset(x, 0f),
                                    Offset(x + size.height, size.height),
                                    strokeWidth = 3.dp.toPx()
                                )
                                x += step
                            }
                        }
                    }
                    if (showLabels) {
                        Column(Modifier.padding(horizontal = 5.dp, vertical = 3.dp)) {
                            Text(
                                event.title,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (height >= 34.dp) {
                                Text(
                                    "${formatMinute(event.startMinute)}–${formatMinute(event.endMinute)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }
        }
        gapMarkers.forEach { marker ->
            val top = timelineHourHeight *
                ((marker.startMinute.coerceAtLeast(TIMELINE_START_MINUTE) -
                    TIMELINE_START_MINUTE) / 60f)
            val height = (timelineHourHeight *
                ((marker.endMinute - marker.startMinute) / 60f)).coerceAtLeast(20.dp)
            Box(
                Modifier.offset(y = top).fillMaxWidth().height(height),
                contentAlignment = Alignment.Center
            ) {
                val label = "${marker.minutes} 分"
                if (marker.minutes >= 60) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.tertiaryContainer
                    ) {
                        Text(
                            label,
                            Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                } else {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/** 冲突课程警示配色：日程表为红色块内斜条纹，课程列表为黄底红框红字。 */
internal val CONFLICT_BLOCK_COLOR = Color(0xFFFFF3CD)
internal val CONFLICT_TEXT_COLOR = Color(0xFFB3261E)
private val CONFLICT_RED_BG = Color(0xFFB3261E)
private val CONFLICT_STRIPE_COLOR = Color(0x99FFF3CD)

@Composable
private fun TimelineLegend() {
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.horizontalScroll(rememberScrollState())
    ) {
        ScheduleType.entries.forEach { type ->
            Text(
                "● ${type.label}",
                color = scheduleColor(type),
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@Composable
private fun TimelineEventDialog(
    event: TimelineEvent,
    onDismiss: () -> Unit,
    onTaskDone: (Item) -> Unit,
    onDeleteItem: (Item) -> Unit
) {
    val eventColor = scheduleColor(event.type)
    val completedColor = scheduleColor(ScheduleType.COMPLETED)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(event.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "${weekdayName(event.weekday)}  " +
                        "${formatMinute(event.startMinute)}–${formatMinute(event.endMinute)}",
                    fontWeight = FontWeight.SemiBold
                )
                Text(event.type.label, color = eventColor)
                Text(event.detail)
                event.item?.takeIf { it.done }?.let {
                    Text(
                        "已完成" +
                            (it.completionLevel.takeIf(String::isNotBlank)?.let { level ->
                                " · $level"
                            } ?: ""),
                        color = completedColor,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        },
        confirmButton = {
            event.item?.takeIf { !it.done }?.let { item ->
                Button(onClick = { onTaskDone(item) }) { Text("完成") }
            } ?: TextButton(onClick = onDismiss) { Text("关闭") }
        },
        dismissButton = {
            if (event.item?.done == false) {
                Row {
                    TextButton(onClick = { event.item?.let(onDeleteItem); onDismiss() }) {
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }
            }
        }
    )
}
