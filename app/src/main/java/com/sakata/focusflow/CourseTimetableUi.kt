package com.sakata.focusflow

import android.app.TimePickerDialog
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** 节次表编辑草稿：弹窗被误关后重开恢复正在编辑的节次时间（8.1.0 草稿保险箱）。 */
private data class PeriodTableDraft(val periods: List<CoursePeriodTime>)

@Composable
internal fun CoursePeriodTableDialog(
    initial: CoursePeriodTable,
    firstSetup: Boolean,
    minimumPeriods: Int,
    onDismiss: () -> Unit,
    onSave: (CoursePeriodTable) -> Unit
) {
    val vault = LocalDraftVault.current
    val draftKey = "periodTable"
    val saved = vault.load<PeriodTableDraft>(draftKey)
    var periods by remember(initial) { mutableStateOf(saved?.periods ?: initial.periods) }
    fun persist() = vault.save(draftKey, PeriodTableDraft(periods))
    val valid = CoursePeriodTable(periods).isValid()
    AppDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (firstSetup) "先设置节次表" else "节次与时间") },
        text = {
            ScrollableDialogBox(maxHeight = 500.dp, spacing = 8.dp) {
                Text(
                    if (firstSetup) "以下为参考时间，请按学校作息确认；保存后进入课表。"
                    else "修改课程对应的实际时间，不会改变已录入的星期和节次。",
                    style = MaterialTheme.typography.bodySmall
                )
                periods.forEachIndexed { index, period ->
                    PeriodTimeRow(
                        index = index,
                        period = period,
                        onChange = { updated -> periods = periods.toMutableList().also { it[index] = updated }; persist() }
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        enabled = periods.size < 20,
                        onClick = {
                            val previousEnd = periods.lastOrNull()?.endMinute ?: 8 * 60
                            val start = (previousEnd + 10).coerceAtMost(23 * 60)
                            periods = periods + CoursePeriodTime(start, (start + 45).coerceAtMost(24 * 60))
                            persist()
                        }
                    ) { Text("＋ 增加一节") }
                    OutlinedButton(enabled = periods.size > minimumPeriods.coerceAtLeast(1), onClick = { periods = periods.dropLast(1); persist() }) { Text("删除末节") }
                }
                if (!valid) Text("节次必须按时间顺序排列，且不能互相重叠。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            Button(enabled = valid, onClick = { vault.clear(draftKey); onSave(CoursePeriodTable(periods)) }) {
                Text(if (firstSetup) "保存并进入课表" else "保存")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun PeriodTimeRow(index: Int, period: CoursePeriodTime, onChange: (CoursePeriodTime) -> Unit) {
    val context = LocalContext.current
    fun pick(minute: Int, onPicked: (Int) -> Unit) {
        TimePickerDialog(context, { _, hour, value -> onPicked(hour * 60 + value) }, minute / 60, minute % 60, true).show()
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("第 ${index + 1} 节", modifier = Modifier.width(52.dp), fontWeight = FontWeight.SemiBold)
        OutlinedButton(modifier = Modifier.weight(1f), onClick = { pick(period.startMinute) { onChange(period.copy(startMinute = it)) } }) {
            Text(formatMinute(period.startMinute))
        }
        Text("–")
        OutlinedButton(modifier = Modifier.weight(1f), onClick = { pick(period.endMinute) { onChange(period.copy(endMinute = it)) } }) {
            Text(formatMinute(period.endMinute))
        }
    }
}

@Composable
internal fun CourseTimetable(
    courses: List<Course>,
    table: CoursePeriodTable,
    compactView: Boolean,
    onCompactViewChange: (Boolean) -> Unit,
    trailingDaysExpanded: Boolean,
    onTrailingDaysExpandedChange: (Boolean) -> Unit,
    onEditPeriods: () -> Unit,
    onEditCourse: (Course) -> Unit
) {
    var selected by remember { mutableStateOf<Course?>(null) }
    val rowHeight = if (compactView) 42.dp else 70.dp
    val headerHeight = if (compactView) 34.dp else 44.dp
    // 8.2.0「课表底色」：只给课表底板铺一层选定的颜色或图片（跟随主题时这里什么都没加，
    // 逐像素与之前一致）。课程块颜色仍是课程数据，一个字不动。
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .appearanceBackdrop(
                spec = LocalAppearance.current,
                scheme = MaterialTheme.colorScheme,
                bitmap = LocalBackdropBitmap.current,
                role = BackdropRole.Timetable
            )
            .padding(2.dp)
    ) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Spacer(Modifier.weight(1f))
            Row {
                TextButton(onClick = { onCompactViewChange(!compactView) }) {
                    Text(if (compactView) "标准视图" else "缩小视图")
                }
                TextButton(onClick = onEditPeriods) { Text("节次设置") }
            }
        }
        if (courses.none { !it.needsConfirmation }) {
            FocusCard(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)) {
                Text("还没有已确认课程。可到计划 → 课程手动新增或导入。", Modifier.fillMaxWidth().padding(14.dp))
            }
        } else {
            if (compactView) {
                val confirmedCourses = courses.filter { !it.needsConfirmation }
                val trailingCourses = confirmedCourses.filter { it.weekday in 5..7 }
                val trailingDaysCollapsible = trailingCourses.size <= 2
                val trailingDaysCollapsed = trailingDaysCollapsible && !trailingDaysExpanded
                Row(Modifier.fillMaxWidth()) {
                    TimetablePeriodRail(table, rowHeight, headerHeight, compact = true, modifier = Modifier.width(36.dp))
                    val visibleDays = if (trailingDaysCollapsed) 1..4 else 1..7
                    visibleDays.forEach { weekday ->
                        TimetableDayLane(
                            modifier = Modifier.weight(1f),
                            weekday = weekday,
                            periods = table.periods.size,
                            courses = confirmedCourses.filter { it.weekday == weekday },
                            rowHeight = rowHeight,
                            headerHeight = headerHeight,
                            compactView = compactView,
                            onSelect = { selected = it }
                        )
                    }
                    if (trailingDaysCollapsed) {
                        TimetableTrailingDaysLane(
                            courses = trailingCourses,
                            periods = table.periods.size,
                            rowHeight = rowHeight,
                            headerHeight = headerHeight,
                            modifier = Modifier.weight(if (trailingCourses.isEmpty()) 0.72f else 1.18f),
                            onExpand = { onTrailingDaysExpandedChange(true) },
                            onSelect = { selected = it }
                        )
                    }
                }
                if (trailingDaysCollapsible && trailingDaysExpanded) {
                    TextButton(onClick = { onTrailingDaysExpandedChange(false) }, modifier = Modifier.align(Alignment.End)) {
                        Text("收纳周五至周日")
                    }
                }
            } else {
                Row(Modifier.fillMaxWidth()) {
                    TimetablePeriodRail(table, rowHeight, headerHeight, compact = false, modifier = Modifier.width(62.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        (1..7).forEach { weekday ->
                            TimetableDayLane(
                                modifier = Modifier.width(116.dp),
                                weekday = weekday,
                                periods = table.periods.size,
                                courses = courses.filter { !it.needsConfirmation && it.weekday == weekday },
                                rowHeight = rowHeight,
                                headerHeight = headerHeight,
                                compactView = false,
                                onSelect = { selected = it }
                            )
                        }
                    }
                }
            }
        }
    }
    } // 课表底色 Box
    selected?.let { course ->
        AppDialog(
            onDismissRequest = { selected = null },
            title = { Text(course.title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${weekdayName(course.weekday)} · 第 ${course.startPeriod}–${course.endPeriod} 节")
                    Text("${formatMinute(CourseGapPlanner.periodStart(course.startPeriod))}–${formatMinute(CourseGapPlanner.periodEnd(course.endPeriod))}")
                    Text(if (course.building.isBlank()) "地点未填写" else "地点：${course.building}")
                }
            },
            confirmButton = { Button(onClick = { selected = null; onEditCourse(course) }) { Text("编辑课程") } },
            dismissButton = { TextButton(onClick = { selected = null }) { Text("关闭") } }
        )
    }
}

@Composable
private fun TimetableTrailingDaysLane(
    courses: List<Course>,
    periods: Int,
    rowHeight: androidx.compose.ui.unit.Dp,
    headerHeight: androidx.compose.ui.unit.Dp,
    modifier: Modifier,
    onExpand: () -> Unit,
    onSelect: (Course) -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier) {
        Box(
            Modifier.height(headerHeight).fillMaxWidth().background(scheme.surfaceVariant.copy(alpha = 0.45f)).clickable(onClick = onExpand),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("周五–日", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                Text(if (courses.isEmpty()) "无课 ›" else "${courses.size} 门 ›", style = MaterialTheme.typography.labelSmall)
            }
        }
        BoxWithConstraints(
            Modifier
                .height(rowHeight * periods.toFloat())
                .fillMaxWidth()
                .border(BorderStroke(0.5.dp, scheme.outlineVariant)),
            contentAlignment = Alignment.Center
        ) {
            Column(Modifier.matchParentSize()) {
                repeat(periods) { Box(Modifier.height(rowHeight).fillMaxWidth().border(BorderStroke(0.5.dp, scheme.outlineVariant))) }
            }
            if (courses.isEmpty()) {
                Text("无课", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
            } else {
                courses.filter { it.startPeriod in 1..periods }.forEach { course ->
                    val overlapping = courses.filter { other ->
                        course.startPeriod <= other.endPeriod && other.startPeriod <= course.endPeriod
                    }.sortedBy { it.weekday }
                    val laneIndex = overlapping.indexOf(course).coerceAtLeast(0)
                    val laneWidth = maxWidth / overlapping.size.coerceAtLeast(1)
                    val span = (course.endPeriod.coerceAtMost(periods) - course.startPeriod + 1).coerceAtLeast(1)
                    val color = listOf(scheme.primaryContainer, scheme.secondaryContainer, scheme.tertiaryContainer)[course.weekday % 3]
                    Column(
                        Modifier
                            .offset(x = laneWidth * laneIndex, y = rowHeight * (course.startPeriod - 1).toFloat())
                            .width(laneWidth)
                            .height(rowHeight * span.toFloat())
                            .padding(2.dp)
                            .clip(RoundedCornerShape(7.dp))
                            .background(color)
                            .clickable { onSelect(course) }
                            .padding(3.dp),
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(weekdayName(course.weekday), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, maxLines = 1)
                        Text(course.title, style = MaterialTheme.typography.labelSmall, maxLines = span.coerceAtMost(2), overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun TimetablePeriodRail(
    table: CoursePeriodTable,
    rowHeight: androidx.compose.ui.unit.Dp,
    headerHeight: androidx.compose.ui.unit.Dp,
    compact: Boolean,
    modifier: Modifier
) {
    Column(modifier) {
        Box(Modifier.height(headerHeight).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(if (compact) "节" else "节次", style = MaterialTheme.typography.labelSmall)
        }
        table.periods.forEachIndexed { index, period ->
            Column(
                Modifier.height(rowHeight).fillMaxWidth().border(BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("${index + 1}", style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                if (!compact) Text(formatMinute(period.startMinute), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun TimetableDayLane(
    modifier: Modifier,
    weekday: Int,
    periods: Int,
    courses: List<Course>,
    rowHeight: androidx.compose.ui.unit.Dp,
    headerHeight: androidx.compose.ui.unit.Dp,
    compactView: Boolean,
    onSelect: (Course) -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val courseColors = listOf(scheme.primaryContainer, scheme.secondaryContainer, scheme.tertiaryContainer, scheme.surfaceVariant)
    Column(modifier) {
        Box(
            Modifier.height(headerHeight).fillMaxWidth().background(if (weekday >= 6) scheme.surfaceVariant.copy(alpha = 0.45f) else scheme.surface),
            contentAlignment = Alignment.Center
        ) { Text(weekdayName(weekday), style = if (compactView) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold) }
        Box(Modifier.height(rowHeight * periods.toFloat()).fillMaxWidth()) {
            Column {
                repeat(periods) { Box(Modifier.height(rowHeight).fillMaxWidth().border(BorderStroke(0.5.dp, scheme.outlineVariant))) }
            }
            courses.filter { it.startPeriod in 1..periods }.forEach { course ->
                val span = (course.endPeriod.coerceAtMost(periods) - course.startPeriod + 1).coerceAtLeast(1)
                val color = courseColors[(course.title.hashCode() and Int.MAX_VALUE) % courseColors.size]
                Column(
                    Modifier
                        .padding(horizontal = if (compactView) 2.dp else 3.dp, vertical = 2.dp)
                        .offset(y = rowHeight * (course.startPeriod - 1).toFloat())
                        .height(rowHeight * span.toFloat() - 4.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(9.dp))
                        .background(color)
                        .clickable { onSelect(course) }
                        .padding(if (compactView) 2.dp else 7.dp),
                    verticalArrangement = if (compactView) Arrangement.Center else Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        course.title,
                        style = if (compactView) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = if (compactView) span.coerceAtMost(2) else (span * 3).coerceAtLeast(2),
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!compactView && course.building.isNotBlank()) Text(course.building, style = MaterialTheme.typography.labelSmall, maxLines = if (span > 1) 2 else 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
