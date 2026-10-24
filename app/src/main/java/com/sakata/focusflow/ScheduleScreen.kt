package com.sakata.focusflow

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
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
    courseTimetableTrailingDaysExpanded: Boolean,
    profile: CommuteProfile,
    campusLifeEnabled: Boolean,
    onCampusLifeRequired: () -> Unit,
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
    onCourseTimetableTrailingDaysExpandedChange: (Boolean) -> Unit,
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
        // 回收站条目必须排除：它们 scheduledAt 已被清空，否则会漏进弹性安排，
        // 对其执行"放回"会改写回收站墓碑并触发 TrashJournal 校验失败（表现为"保存失败"）。
        !it.done && it.kind != "暂停" && it.kind != "收集箱" && it.kind != "回收站" && it.scheduledAt == null
    }
    var scheduleMode by remember { mutableStateOf("日") }
    var periodTableOpen by remember { mutableStateOf(false) }
    var firstPeriodSetup by remember { mutableStateOf(false) }

    ScrollableWithBar(modifier = modifier, scrollState = rememberScrollState()) {
        FocusPageHeader(
            title = "日程",
            subtitle = "看清今天、未来一周和固定课表",
            action = { HelpToggleButton(onClick = { helpOpen = true }) }
        )
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
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
                        if (!campusLifeEnabled) onCampusLifeRequired()
                        else if (coursePeriodTableConfigured) scheduleMode = "课表"
                        else {
                            firstPeriodSetup = true
                            periodTableOpen = true
                        }
                    },
                    label = { Text("课表") }
                )
            }
            Text(
                when (scheduleMode) {
                    "日" -> "今天"
                    "周" -> "未来 7 天"
                    else -> "固定课表"
                },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }
        AnimatedContent(
            targetState = scheduleMode,
            transitionSpec = {
                (fadeIn(MotionSpec.quick()) togetherWith fadeOut(MotionSpec.quick()))
                    .using(SizeTransform { _, _ -> MotionSpec.quick() })
            },
            label = "scheduleModeContent"
        ) { mode ->
            when (mode) {
                "日" -> {
                    Column(
                        Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (todayUnslotted.isNotEmpty()) {
                            FocusCard(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                            ) {
                                Column(
                                    Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text("今日待办 · 未定时间", fontWeight = FontWeight.SemiBold)
                                    todayUnslotted.forEach {
                                        Text("• ${it.title}", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                        DailyScheduleTimeline(
                            todayCourses,
                            todaySchedule,
                            profile,
                            onStartTask,
                            onRescheduleTask,
                            onReturnToInbox,
                            onTaskDone,
                            onDeleteItem
                        )
                    }
                }
                "周" -> Column(
                    Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
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
                }
                else -> Column(
                    Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CourseTimetable(
                        courses = courses,
                        table = coursePeriodTable,
                        compactView = courseTimetableCompact,
                        onCompactViewChange = onCourseTimetableCompactChange,
                        trailingDaysExpanded = courseTimetableTrailingDaysExpanded,
                        onTrailingDaysExpandedChange = onCourseTimetableTrailingDaysExpandedChange,
                        onEditPeriods = {
                            firstPeriodSetup = false
                            periodTableOpen = true
                        },
                        onEditCourse = onEditCourse
                    )
                }
            }
        }
        if (scheduleMode != "课表" && flexibleItems.isNotEmpty()) {
            FocusSectionHeader("弹性安排")
            flexibleItems.take(4).forEach { item ->
                FlexibleScheduleRow(
                    item,
                    energyLevel,
                    onPlan = { onPlanFlexible(item) },
                    onAdjust = { onAdjustFlexible(item) },
                    onReturn = { onReturnToInbox(item) },
                    onDelete = { onDeleteItem(item) }
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

/**
 * 弹性安排行：标题/时长/范围在上，四个动作（初步安排、调整范围、放回收集箱、删除）收进卡片底部的一行。
 * 动作横排而不是继续纵向堆叠，是为了 360dp 宽下仍有足够正文宽度，且卡片不会因第四个按钮再长 48dp；
 * 大字号下 FlowRow 自动折行，不挤爆行。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlexibleScheduleRow(
    item: Item,
    energyLevel: String,
    onPlan: () -> Unit,
    onAdjust: () -> Unit,
    onReturn: () -> Unit,
    onDelete: () -> Unit
) {
    val type = item.scheduleType()
    val typeColor = scheduleColor(type)
    FocusCard(containerColor = typeColor.copy(alpha = 0.10f)) {
        Column(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
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
            }
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                TextButton(onClick = onPlan) { Text("初步安排") }
                TextButton(onClick = onAdjust) { Text("调整范围") }
                // 放回收集箱：复用 TaskActions.returnToInbox 既有语义（清时间与范围、保留调度日记忆），
                // 不是删除，也不进回收站；行即时从弹性安排区消失，任务出现在收集箱。
                TextButton(onClick = onReturn) { Text("放回") }
                // 删除：走 onDeleteItem 既有语义——普通任务进回收站（30 天可恢复），
                // 活动/游戏沿用原有硬删除约定。与时间轴行的删除入口保持一致。
                TextButton(onClick = onDelete) { Text("删除") }
            }
        }
    }
}
