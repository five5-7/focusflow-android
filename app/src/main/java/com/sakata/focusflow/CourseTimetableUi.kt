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

@Composable
internal fun CoursePeriodTableDialog(
    initial: CoursePeriodTable,
    firstSetup: Boolean,
    minimumPeriods: Int,
    onDismiss: () -> Unit,
    onSave: (CoursePeriodTable) -> Unit
) {
    var periods by remember(initial) { mutableStateOf(initial.periods) }
    val valid = CoursePeriodTable(periods).isValid()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (firstSetup) "先设置节次表" else "节次与时间") },
        text = {
            ScrollableDialogBox(maxHeight = 500.dp, spacing = 8.dp) {
                Text(
                    if (firstSetup) "已根据课程默认规则填入参考时间点。请按学校实际作息确认或修改，保存后进入课表。"
                    else "修改只会调整课程对应的真实时间，不会改变已录入的星期和节次。",
                    style = MaterialTheme.typography.bodySmall
                )
                Text("参考时间不代表学校实际安排；每行只需选择开始和结束时间。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                periods.forEachIndexed { index, period ->
                    PeriodTimeRow(
                        index = index,
                        period = period,
                        onChange = { updated -> periods = periods.toMutableList().also { it[index] = updated } }
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        enabled = periods.size < 20,
                        onClick = {
                            val previousEnd = periods.lastOrNull()?.endMinute ?: 8 * 60
                            val start = (previousEnd + 10).coerceAtMost(23 * 60)
                            periods = periods + CoursePeriodTime(start, (start + 45).coerceAtMost(24 * 60))
                        }
                    ) { Text("＋ 增加一节") }
                    OutlinedButton(enabled = periods.size > minimumPeriods.coerceAtLeast(1), onClick = { periods = periods.dropLast(1) }) { Text("删除末节") }
                }
                if (!valid) Text("节次必须按时间顺序排列，且不能互相重叠。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            Button(enabled = valid, onClick = { onSave(CoursePeriodTable(periods)) }) {
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
    onEditPeriods: () -> Unit,
    onEditCourse: (Course) -> Unit
) {
    var selected by remember { mutableStateOf<Course?>(null) }
    val rowHeight = 70.dp
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text("固定周课表", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("周一至周日 · 按学校节次排列", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = onEditPeriods) { Text("节次设置") }
        }
        if (courses.none { !it.needsConfirmation }) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))) {
                Text("还没有已确认课程。可到计划 → 课程手动新增或导入。", Modifier.fillMaxWidth().padding(14.dp))
            }
        } else {
            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.width(62.dp)) {
                    Box(Modifier.height(44.dp).fillMaxWidth(), contentAlignment = Alignment.Center) { Text("节次", style = MaterialTheme.typography.labelMedium) }
                    table.periods.forEachIndexed { index, period ->
                        Column(
                            Modifier.height(rowHeight).fillMaxWidth().border(BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("${index + 1}", fontWeight = FontWeight.Bold)
                            Text(formatMinute(period.startMinute), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    (1..7).forEach { weekday ->
                        TimetableDayLane(
                            weekday = weekday,
                            periods = table.periods.size,
                            courses = courses.filter { !it.needsConfirmation && it.weekday == weekday },
                            rowHeight = rowHeight,
                            onSelect = { selected = it }
                        )
                    }
                }
            }
            Text("左右滑动查看全部七天；同一课程使用稳定颜色。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    selected?.let { course ->
        AlertDialog(
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
private fun TimetableDayLane(
    weekday: Int,
    periods: Int,
    courses: List<Course>,
    rowHeight: androidx.compose.ui.unit.Dp,
    onSelect: (Course) -> Unit
) {
    val dayWidth = 116.dp
    val scheme = MaterialTheme.colorScheme
    val courseColors = listOf(scheme.primaryContainer, scheme.secondaryContainer, scheme.tertiaryContainer, scheme.surfaceVariant)
    Column(Modifier.width(dayWidth)) {
        Box(
            Modifier.height(44.dp).fillMaxWidth().background(if (weekday >= 6) scheme.surfaceVariant.copy(alpha = 0.45f) else scheme.surface),
            contentAlignment = Alignment.Center
        ) { Text(weekdayName(weekday), fontWeight = FontWeight.SemiBold) }
        Box(Modifier.height(rowHeight * periods.toFloat()).fillMaxWidth()) {
            Column {
                repeat(periods) { Box(Modifier.height(rowHeight).fillMaxWidth().border(BorderStroke(0.5.dp, scheme.outlineVariant))) }
            }
            courses.filter { it.startPeriod in 1..periods }.forEach { course ->
                val span = (course.endPeriod.coerceAtMost(periods) - course.startPeriod + 1).coerceAtLeast(1)
                val color = courseColors[(course.title.hashCode() and Int.MAX_VALUE) % courseColors.size]
                Column(
                    Modifier
                        .padding(horizontal = 3.dp, vertical = 2.dp)
                        .offset(y = rowHeight * (course.startPeriod - 1).toFloat())
                        .height(rowHeight * span.toFloat() - 4.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(9.dp))
                        .background(color)
                        .clickable { onSelect(course) }
                        .padding(7.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(course.title, fontWeight = FontWeight.Bold, maxLines = if (span > 1) 2 else 1, overflow = TextOverflow.Ellipsis)
                    if (span > 1 && course.building.isNotBlank()) Text(course.building, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
