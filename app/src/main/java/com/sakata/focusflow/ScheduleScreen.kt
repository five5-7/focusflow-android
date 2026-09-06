package com.sakata.focusflow

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun ScheduleScreen(
    modifier: Modifier,
    items: List<Item>,
    courses: List<Course>,
    coursePeriodTable: CoursePeriodTable,
    coursePeriodTableConfigured: Boolean,
    courseTimetableCompact: Boolean,
    profile: CommuteProfile,
    energyLevel: String,
    onPlanFlexible: (Item) -> Unit,
    onAdjustFlexible: (Item) -> Unit,
    onStartTask: (Item) -> Unit,
    onRescheduleTask: (Item) -> Unit,
    onReturnToInbox: (Item) -> Unit,
    onTaskDone: (Item) -> Unit,
    onDeleteItem: (Item) -> Unit,
    onSaveCoursePeriodTable: (CoursePeriodTable) -> Unit,
    onCourseTimetableCompactChange: (Boolean) -> Unit,
    onEditCourse: (Course) -> Unit
) {
    var helpOpen by remember { mutableStateOf(false) }
    val weekday = todayWeekday()
    val todaySchedule = items
        .filter { !it.dayOnly && it.scheduledAt?.let(::isToday) == true }
        .sortedBy { it.scheduledAt }
    val todayUnslotted = items.filter {
        !it.done && it.dayOnly && it.scheduledAt?.let(::isToday) == true
    }
    val todayCourses = courses
        .filter { !it.needsConfirmation && it.weekday == weekday }
        .sortedBy { it.startPeriod }
    val flexibleItems = items.filter {
        !it.done && it.kind != "暂停" && it.kind != "收集箱" && it.scheduledAt == null
    }
    var scheduleMode by remember { mutableStateOf("日") }
    var periodTableOpen by remember { mutableStateOf(false) }
    var firstPeriodSetup by remember { mutableStateOf(false) }

    ScrollableWithBar(modifier = modifier, scrollState = rememberScrollState()) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("日程", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
            HelpToggleButton(onClick = { helpOpen = true })
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                when (scheduleMode) {
                    "日" -> "今天"
                    "周" -> "未来 7 天"
                    else -> "固定课表"
                },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(
                    selected = scheduleMode == "日",
                    onClick = { scheduleMode = "日" },
                    label = { Text("日") }
                )
                FilterChip(
                    selected = scheduleMode == "周",
                    onClick = { scheduleMode = "周" },
                    label = { Text("周") }
                )
                FilterChip(
                    selected = scheduleMode == "课表",
                    onClick = {
                        if (coursePeriodTableConfigured) scheduleMode = "课表"
                        else {
                            firstPeriodSetup = true
                            periodTableOpen = true
                        }
                    },
                    label = { Text("课表") }
                )
            }
        }
        if (scheduleMode == "日") {
            if (todayUnslotted.isNotEmpty()) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                    )
                ) {
                    Column(
                        Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text("今日待办（尚未指定时段）", fontWeight = FontWeight.SemiBold)
                        todayUnslotted.forEach {
                            Text("• ${it.title}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            DailyScheduleTimeline(todayCourses, todaySchedule, profile, onStartTask, onRescheduleTask, onReturnToInbox, onTaskDone, onDeleteItem)
        } else if (scheduleMode == "周") {
            WeeklyScheduleTimeline(
                courses.filter { !it.needsConfirmation },
                items,
                profile,
                onStartTask,
                onRescheduleTask,
                onReturnToInbox,
                onTaskDone,
                onDeleteItem
            )
        } else {
            CourseTimetable(
                courses = courses,
                table = coursePeriodTable,
                compactView = courseTimetableCompact,
                onCompactViewChange = onCourseTimetableCompactChange,
                onEditPeriods = {
                    firstPeriodSetup = false
                    periodTableOpen = true
                },
                onEditCourse = onEditCourse
            )
        }
        if (scheduleMode != "课表" && flexibleItems.isNotEmpty()) {
            Text("弹性安排", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            flexibleItems.take(4).forEach { item ->
                FlexibleScheduleRow(
                    item,
                    energyLevel,
                    onPlan = { onPlanFlexible(item) },
                    onAdjust = { onAdjustFlexible(item) }
                )
            }
        }
        if (helpOpen) {
            HelpDialog(
                title = HelpCatalog.schedule.title,
                sections = HelpCatalog.schedule.sections,
                onDismiss = { helpOpen = false }
            )
        }
    }
    if (periodTableOpen) {
        CoursePeriodTableDialog(
            initial = coursePeriodTable,
            firstSetup = firstPeriodSetup,
            minimumPeriods = courses.maxOfOrNull { it.endPeriod } ?: 1,
            onDismiss = { periodTableOpen = false },
            onSave = { table ->
                onSaveCoursePeriodTable(table)
                periodTableOpen = false
                scheduleMode = "课表"
            }
        )
    }
}

@Composable
private fun FlexibleScheduleRow(
    item: Item,
    energyLevel: String,
    onPlan: () -> Unit,
    onAdjust: () -> Unit
) {
    val type = item.scheduleType()
    val typeColor = scheduleColor(type)
    Card(colors = CardDefaults.cardColors(containerColor = typeColor.copy(alpha = 0.10f))) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                Modifier.width(5.dp).height(46.dp)
                    .background(typeColor, MaterialTheme.shapes.small)
            )
            Column(Modifier.weight(1f)) {
                Text(item.title, fontWeight = FontWeight.SemiBold)
                Text(
                    "预计 ${item.durationMinutes} 分钟 · 当前精力$energyLevel",
                    style = MaterialTheme.typography.bodySmall
                )
                if (item.windowStartAt != null && item.windowEndAt != null) {
                    Text(
                        "${formatDateTime(item.windowStartAt)} 至 ${formatDateTime(item.windowEndAt)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                TextButton(onClick = onPlan) { Text("初步安排") }
                TextButton(onClick = onAdjust) { Text("调整范围") }
            }
        }
    }
}
