package com.sakata.focusflow

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun TodayScreen(
    modifier: Modifier,
    items: List<Item>,
    inboxOpen: Boolean,
    onInboxOpenChange: (Boolean) -> Unit,
    onCaptureToInbox: (String) -> Boolean,
    energyLevel: String,
    energyRecordedAt: Long,
    onEnergyLevelChange: (String) -> Unit,
    campusLifeEnabled: Boolean,
    onCampusLifeEnabledChange: (Boolean) -> Unit,
    onSwitchLifeStage: (LifeStage) -> Unit,
    onOpenSchedule: () -> Unit,
    onOpenGoals: () -> Unit,
    onStartGoalTask: (Item) -> Unit,
    latestStatusCheckIn: StatusCheckIn?,
    checkIns: List<StatusCheckIn>,
    onRecordActivity: () -> Unit,
    onTaskDone: (Item) -> Unit,
    goals: List<Goal>,
    feedback: List<TaskFeedback>,
    commuteProfile: CommuteProfile,
    onCommuteProfileChange: (CommuteProfile) -> Unit,
    activeSession: ActivitySession?,
    activityHistory: List<ActivitySession>,
    nextCommitment: ActivityCommitment?,
    onStartActivity: () -> Unit,
    onStartSuggestion: (NextActionSuggestion, Boolean) -> Unit,
    onReplanSuggestion: (Item) -> Unit,
    onReviewActivity: () -> Unit,
    onPickTime: (Item) -> Unit,
    onEdit: (Item) -> Unit,
    onOrganize: (Item) -> Unit,
    onInboxToTodo: (Item) -> Unit,
    onInboxToWanted: (Item) -> Unit,
    onBatchOrganize: (Set<Long>, InboxBatchAction) -> Boolean,
    onCreateNextAction: (Item) -> Unit,
    onRestoreCapture: (Item) -> Unit,
    onShrink: (Item) -> Unit,
    onReturnToInbox: (Item) -> Unit,
    onApplyAdjustment: (Item, DayAdjustment) -> Unit,
    onPause: (Item) -> Unit,
    onAbandon: (Item) -> Unit,
    baselineEvents: List<BaselineEvent>,
    taskEvents: List<TaskEvent>,
    mealRecords: List<MealRecord>,
    mealReminderEnabled: Boolean,
    statusCheckInEnabled: Boolean,
    onEnableStatusCheckIn: () -> Unit,
    windDownEnabled: Boolean,
    baselineProfile: BaselineProfile,
    courses: List<Course>,
    mealSkipDays: Set<String>,
    onMealPrompt: (MealType) -> Unit,
    onMealFinish: (MealType) -> Unit
) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var helpOpen by remember { mutableStateOf(false) }
    var statusPanelOpen by remember { mutableStateOf(false) }
    var captureText by remember { mutableStateOf("") }
    LaunchedEffect(activeSession?.id, activeSession?.endsAt) {
        while (true) {
            now = System.currentTimeMillis()
            delay(if (activeSession == null) 30_000 else 1_000)
        }
    }
    // 8.1.0 第三轮：以下派生值原先每次重组都重算（items 几百条、taskEvents 几千条），
    // 切换页签/返回前台时会在动画开始前掉一帧；这里按输入缓存，输入不变就不再计算。
    val inboxItems = remember(items) { items.filter { !it.done && it.kind == "收集箱" } }
    val pendingInboxItems = remember(inboxItems) { inboxItems.filter { CaptureRoute.fromKey(it.captureRoute) == CaptureRoute.INBOX } }
    val capturedAt = remember(taskEvents) {
        taskEvents.filter { it.type == TaskEventType.TASK_CREATED }
            .groupBy { it.itemId }.mapValues { (_, events) -> events.minOf { it.recordedAt } }
    }
    val lastReviewedAt = remember(taskEvents) {
        taskEvents.filter { it.type == TaskEventType.CAPTURE_ROUTED && it.extra == InboxBatchAction.KEEP.label }
            .groupBy { it.itemId }.mapValues { (_, events) -> events.maxOf { it.recordedAt } }
    }
    val progressItems = remember(inboxItems) { inboxItems.filter { CaptureRoute.fromKey(it.captureRoute) == CaptureRoute.PROGRESS } }
    val referenceItems = remember(inboxItems) { inboxItems.filter { CaptureRoute.fromKey(it.captureRoute) == CaptureRoute.REFERENCE } }
    val energyIsCurrent = StatusFreshnessPolicy.isCurrent(energyRecordedAt, now)
    val planningEnergy = if (energyIsCurrent) energyLevel else "正常"
    val nextSuggestion = remember(items, nextCommitment, planningEnergy, goals, feedback, now, courses, commuteProfile) {
        NextActionPlanner.recommend(items, nextCommitment, planningEnergy, goals, feedback, now, courses, commuteProfile)
    }
    val dailySummary = remember(items, now, taskEvents) { DailyLoopStats.summarize(items, now, taskEvents) }
    // 6.9：已推荐去执行的任务不再重复出现在「需要恢复的安排」——推荐/恢复双入口去重（只影响 UI 展示）。
    val recoveryCandidates = remember(items, now, nextSuggestion) {
        RecoveryInsights.candidates(items, now).filter { it.item.id != nextSuggestion?.item?.id }
    }
    val completedTodayItems = remember(taskEvents, items, now) {
        if (taskEvents.isEmpty()) {
            items.filter { it.done && it.completedAt?.let(::isToday) == true }.sortedByDescending { it.completedAt }.map {
                TaskRecorder.event(TaskEventType.TASK_COMPLETED, it.id, it.title, extra = it.completionLevel, at = it.completedAt ?: 0)
            }
        } else TaskHistory.completedOn(taskEvents, TaskHistory.dayStartOf(now))
    }
    val completedThisWeek = remember(items) { items.count { it.done && it.completedAt?.let(::isInCurrentWeek) == true } }
    val visibility = remember(
        baselineProfile, mealRecords, mealReminderEnabled, goals, items, courses,
        campusLifeEnabled, statusCheckInEnabled, checkIns, windDownEnabled
    ) {
        FeatureVisibilityPolicy.daily(
        FeatureUsageSnapshot(
            baselineComplete = baselineProfile.isComplete,
            mealRecordCount = mealRecords.size,
            mealReminderEnabled = mealReminderEnabled,
            goalCount = goals.count { it.state == PlanState.IN_PROGRESS } + if (items.any { !it.done && it.goalId != null }) 1 else 0,
            confirmedCourseCount = courses.count { !it.needsConfirmation },
            lifeStage = baselineProfile.lifeStage,
            campusLifeEnabled = campusLifeEnabled,
            statusCheckInEnabled = statusCheckInEnabled,
            statusCheckInCount = checkIns.size,
            windDownEnabled = windDownEnabled
        )
    )
    }
    val overviewScrollState = rememberScrollState()
    var inboxFilter by remember { mutableStateOf("全部") }
    var expandedInboxId by remember { mutableStateOf<Long?>(null) }
    var inboxSelecting by remember { mutableStateOf(false) }
    var selectedInboxIds by remember { mutableStateOf(emptySet<Long>()) }
    val selectableInboxItems = remember(pendingInboxItems) {
        pendingInboxItems.filterNot { it.title.startsWith("重新安排：") }
    }
    val selectableIds = remember(selectableInboxItems) { selectableInboxItems.mapTo(mutableSetOf()) { it.id } }
    val activeSelection = selectedInboxIds.intersect(selectableIds)
    val pendingAgeGroups = remember(pendingInboxItems, capturedAt, now / 60_000L) {
        groupInboxByAge(pendingInboxItems, capturedAt, now)
    }
    Box(modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = !inboxOpen,
            // 8.1.0 第三轮：主页直接出现在副页下层（不放大、不淡入）；进入子页时它退到 0.96。
            enter = hubEnter(),
            exit = hubExit()
        ) {
    ScrollableWithBar(scrollState = overviewScrollState) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("今日概览", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
            HelpToggleButton(onClick = { helpOpen = true })
        }
        TodayStatusPanel(
            expanded = statusPanelOpen,
            onExpandedChange = {
                if (it) FrameTimingRecorder.recordExpansion("today_status")
                statusPanelOpen = it
            },
            lifeStage = baselineProfile.lifeStage,
            onSwitchLifeStage = onSwitchLifeStage,
            energyLevel = energyLevel,
            energyIsCurrent = energyIsCurrent,
            energyRecordedAt = energyRecordedAt,
            onEnergyLevelChange = onEnergyLevelChange,
            campusLifeEnabled = campusLifeEnabled,
            onCampusLifeEnabledChange = onCampusLifeEnabledChange,
            commuteProfile = commuteProfile,
            onCommuteProfileChange = onCommuteProfileChange
        )
        val agenda = todayAgenda(courses, items, now)
        val nowCal = java.util.Calendar.getInstance().apply { timeInMillis = now }
        val currentMinute = nowCal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + nowCal.get(java.util.Calendar.MINUTE)
        val inClass = agenda.firstOrNull { it.isCourse && currentMinute in it.startMinute until (it.startMinute + 45) }
        val upcoming = agenda.filter { it.startMinute >= currentMinute - 5 }.take(3)
        val personalEnergyNotes = remember(now / 60_000L, checkIns) {
            PersonalEnergyModel.display(PersonalEnergyModel.analyze(now, checkIns))
        }
        FocusCard(containerColor = MaterialTheme.colorScheme.primaryContainer) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (activeSession != null) {
                    val due = now >= activeSession.endsAt || activeSession.status == ActivitySession.STATUS_AWAITING_CONFIRMATION
                    Text(if (due) "需要确认：${activeSession.name}" else "正在：${activeSession.name}", fontWeight = FontWeight.Bold)
                    // 活动到点属于警示语义：使用固定警示色。
                    Text(if (due) "已到预计结束时间 ${formatTime(activeSession.endsAt)}" else "剩余 ${formatActivityRemaining(activeSession.endsAt - now)} · 预计 ${formatTime(activeSession.endsAt)} 结束", color = if (due) MaterialTheme.colorScheme.error else Color.Unspecified)
                    if (activeSession.nextStep.isNotBlank()) Text("下一步：${activeSession.nextStep}")
                    if (activeSession.extensionCount > 0) Text("已延长 ${activeSession.extensionCount} 次${activeSession.extensionReason.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()}", style = MaterialTheme.typography.bodySmall)
                    Button(onClick = onReviewActivity) { Text(if (due) "处理到点" else "结束或调整") }
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("现在做什么", fontWeight = FontWeight.Bold)
                            Text(latestStatusCheckIn?.let { "上次记录：${it.activity} · ${formatDateTime(it.recordedAt)}" } ?: "还没有记录正在进行的活动", style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = onRecordActivity) { Text("记录") }
                    }
                    personalEnergyNotes.forEach { advice -> Text(advice, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary) }
                    HorizontalDivider()
                    nextSuggestion?.let { suggestion ->
                        val item = suggestion.item
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                RecoveryInsights.overdueLabel(item, now)?.let { label ->
                                    Text(
                                        "$label · 请选择完成、改时间或重新安排",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.error,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                                Text(item.title, fontWeight = FontWeight.SemiBold)
                                Text(item.detail)
                                Text(suggestion.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Button(onClick = { onStartSuggestion(suggestion, false) }) { Text("开始") }
                                suggestion.minimumVersion?.let { OutlinedButton(onClick = { onStartSuggestion(suggestion, true) }) { Text("最低版本") } }
                                OutlinedButton(onClick = onStartActivity) { Text("自由开始") }
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                TextButton(onClick = { onReplanSuggestion(item) }) { Text("改时间") }
                                TextButton(onClick = { onTaskDone(item) }) { Text("完成") }
                            }
                        }
                    } ?: Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("没有必须现在做的事。你可以休息、随手记录一个想法，或开始一个活动。", style = MaterialTheme.typography.bodySmall)
                        Button(onClick = onStartActivity) { Text("开始活动") }
                    }
                }
            }
        }
        TodayPermissionReminder(now)
        FocusCard(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
            modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenSchedule)
        ) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("接下来", fontWeight = FontWeight.Bold)
                    Text("日程 ›", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
                if (inClass != null) Text("现在：${inClass.title}（${inClass.subtitle}）", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                if (upcoming.isEmpty()) Text("今天没有其他安排了。", style = MaterialTheme.typography.bodySmall)
                else upcoming.forEach { entry -> Text("${formatMinute(entry.startMinute)} · ${entry.title} — ${entry.subtitle}", style = MaterialTheme.typography.bodySmall) }
            }
        }
        if (recoveryCandidates.isNotEmpty()) {
            // 收编：ElevatedCard(colors = surfaceVariant) → FocusCard，底色与 1dp 默认阴影逐项保留。
            FocusCard(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                elevation = 1.dp
            ) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("需要恢复的安排", fontWeight = FontWeight.Bold)
                    Text("选择一个更容易继续的下一步。", style = MaterialTheme.typography.bodySmall)
                    recoveryCandidates.take(3).forEach { candidate ->
                        val adjustment = ScheduleAdjuster.suggest(candidate, items, courses, commuteProfile)
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(candidate.item.title.removePrefix("重新安排："), fontWeight = FontWeight.SemiBold)
                            Text(
                                if (candidate.reason == RecoveryReason.MISSED) {
                                    RecoveryInsights.overdueLabel(candidate.item, now) ?: "原安排已错过"
                                } else "已改期 ${candidate.item.rescheduleCount} 次",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            adjustment?.let {
                                Text("建议：${it.reason}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                adjustment?.let {
                                    TextButton(onClick = { onApplyAdjustment(candidate.item, it) }) { Text("执行建议") }
                                }
                                TextButton(onClick = { onShrink(candidate.item) }) { Text("缩为 15 分钟") }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = { onReplanSuggestion(candidate.item) }) { Text("重新安排") }
                                TextButton(onClick = { onReturnToInbox(candidate.item) }) { Text("放回收集箱") }
                            }
                        }
                    }
                    if (recoveryCandidates.size > 3) Text("还有 ${recoveryCandidates.size - 3} 项可到日程继续处理。", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        FocusCard(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (inboxItems.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("收集箱 · ${inboxItems.size}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        TextButton(onClick = { onInboxOpenChange(true) }) { Text("查看全部 ›") }
                    }
                }
                fun saveCapture() {
                    val title = captureText.trim()
                    if (title.isNotEmpty() && onCaptureToInbox(title)) captureText = ""
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = captureText,
                        onValueChange = { captureText = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("随手记一件事") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { saveCapture() })
                    )
                    Button(onClick = { saveCapture() }, enabled = captureText.isNotBlank()) { Text("保存") }
                }
                if (inboxItems.isNotEmpty()) {
                    pendingInboxItems.sortedWith(compareByDescending<Item> { capturedAt[it.id] ?: Long.MIN_VALUE })
                        .take(2).forEach { item ->
                            Row(
                                Modifier.fillMaxWidth().clickable { onInboxOpenChange(true) }.padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(item.title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                capturedAt[item.id]?.let { recordedAt ->
                                    Text(captureAgeLabel(recordedAt, now), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                }
            }
        }
        if (visibility.energy && !statusCheckInEnabled) {
            TextButton(onClick = onEnableStatusCheckIn) { Text("开启每日精力询问") }
        }
        val todayGoalTasks = items.filter {
            !it.done && it.goalId != null && it.scheduledAt?.let { at -> ScheduleOccupation.sameDate(at, now) } == true
        }
        val goalsRemaining = goals.count { it.weeklyTarget > GoalPlanner.completedThisWeek(it) }
        if (visibility.goals && (todayGoalTasks.isNotEmpty() || goalsRemaining > 0)) {
            // 收编：ElevatedCard → FocusCard，显式保留 surfaceContainerLow 底色与 1dp 默认阴影。
            FocusCard(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                elevation = 1.dp
            ) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("今天的目标", fontWeight = FontWeight.Bold)
                        TextButton(onClick = onOpenGoals) { Text("目标与执行 ›") }
                    }
                    if (todayGoalTasks.isNotEmpty()) {
                        todayGoalTasks.take(3).forEach { task ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(task.title, fontWeight = FontWeight.SemiBold)
                                    Text(if (task.detail.length > 46) task.detail.take(46) + "…" else task.detail, style = MaterialTheme.typography.bodySmall)
                                }
                                Button(onClick = { onStartGoalTask(task) }) { Text("开始") }
                            }
                        }
                        if (todayGoalTasks.size > 3) Text("还有 ${todayGoalTasks.size - 3} 项，见日程。", style = MaterialTheme.typography.labelSmall)
                    } else {
                        Text("本周还有 $goalsRemaining 个目标未完成，今天还没安排执行时段；可以一键按空挡排入。", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = onOpenGoals) { Text("去安排 ›") }
                    }
                }
            }
        }
        // 与周回顾「本周执行概览」统一的摘要卡风格：实色 primaryContainer + 零 elevation。
        FocusCard(containerColor = MaterialTheme.colorScheme.primaryContainer) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(dailySummary.completionPercent?.let { "$it%" } ?: "—", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text("计划完成率", style = MaterialTheme.typography.labelMedium)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("${dailySummary.completedCount}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text("今日完成", style = MaterialTheme.typography.labelMedium)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("${dailySummary.rescheduledCount}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text("今日改期", style = MaterialTheme.typography.labelMedium)
                    }
                }
                Text(
                    if (dailySummary.plannedCount == 0) "今天尚未安排定时任务；完成率会在安排后开始计算。"
                    else "已完成 ${dailySummary.completedPlannedCount}/${dailySummary.plannedCount} 项日程 · 本周共完成 $completedThisWeek 项 · 收集箱 ${dailySummary.inboxCount} 项",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (completedTodayItems.isNotEmpty()) {
            // 收编：ElevatedCard → FocusCard，显式保留 surfaceContainerLow 底色与 1dp 默认阴影。
            FocusCard(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                elevation = 1.dp
            ) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("今日完成记录", fontWeight = FontWeight.Bold)
                    completedTodayItems.take(4).forEach { event ->
                        Text(
                            "${formatTime(event.recordedAt)} · ${event.title}" +
                                event.extra.takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (completedTodayItems.size > 4) Text("还有 ${completedTodayItems.size - 4} 项已完成，日程中仍会灰色保留。", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        if (visibility.meals) MealTodayCard(records = mealRecords, profile = baselineProfile, skipDays = mealSkipDays, now = now, onPrompt = onMealPrompt, onFinish = onMealFinish)
        val completedActivities = activityHistory.filter { it.actualEndAt?.let(::isToday) == true }
        if (completedActivities.isNotEmpty()) {
            // 收编：ElevatedCard → FocusCard，显式保留 surfaceContainerLow 底色与 1dp 默认阴影。
            FocusCard(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                elevation = 1.dp
            ) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("今日活动记录 · ${completedActivities.size} 次", fontWeight = FontWeight.Bold)
                    completedActivities.take(3).forEach { session ->
                        val minutes = (((session.actualEndAt ?: session.endsAt) - session.actualStartAt).coerceAtLeast(0) / 60_000L).toInt()
                        Text("${session.name} · $minutes 分钟 · ${if (session.status == ActivitySession.STATUS_COMPLETED) "已结束" else "已重新安排"}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        if (visibility.windDown) WindDownInsights.advice(baselineProfile, courses, items, checkIns, activityHistory, now)?.let { advice ->
            FocusCard(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("睡前减速", fontWeight = FontWeight.Bold)
                    Text(advice.message, style = MaterialTheme.typography.bodySmall)
                    // "注意休息"是警示语义（明早有早课）：用警示色；"可稍晚收尾"保持主色。
                    advice.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = if (advice.alert) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) }
                    advice.tomorrowText?.let { text ->
                        HorizontalDivider()
                        Text("明日准备", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelMedium)
                        Text(text, style = MaterialTheme.typography.bodySmall)
                        Text("趁收尾时间看一眼明天的安排，把要事记进收集箱。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
        }
        SubpageMotion(inboxOpen.takeIf { it }) {
            PlanSubpageFrame(Modifier.fillMaxSize(), "收集箱") {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(
                        "全部" to inboxItems.size,
                        "待整理" to pendingInboxItems.size,
                        "推进" to progressItems.size,
                        "参考" to referenceItems.size
                    ).forEach { (label, count) ->
                        FilterChip(
                            selected = inboxFilter == label,
                            onClick = { inboxFilter = label; inboxSelecting = false; selectedInboxIds = emptySet() },
                            label = { Text("$label $count") }
                        )
                    }
                }
                if (inboxItems.isEmpty()) {
                    FocusCard(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)) {
                        Text("暂时没有新想法，点底部 ＋ 随手记录。", Modifier.fillMaxWidth().padding(16.dp))
                    }
                } else {
                    if ((inboxFilter == "全部" || inboxFilter == "待整理") && pendingInboxItems.isNotEmpty()) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("待整理 · ${pendingInboxItems.size}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            if (selectableInboxItems.isNotEmpty()) TextButton(onClick = {
                                inboxSelecting = !inboxSelecting
                                expandedInboxId = null
                                selectedInboxIds = emptySet()
                            }) { Text(if (inboxSelecting) "完成" else "整理多项") }
                        }
                        AnimatedVisibility(inboxSelecting) {
                            FocusCard(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                        Text("已选 ${activeSelection.size} 项", style = MaterialTheme.typography.titleSmall)
                                        TextButton(onClick = {
                                            selectedInboxIds = if (activeSelection.size == selectableIds.size) emptySet() else selectableIds
                                        }) { Text(if (activeSelection.size == selectableIds.size) "清空" else "全选") }
                                    }
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp), maxItemsInEachRow = 2) {
                                        InboxBatchAction.entries.forEach { action ->
                                            OutlinedButton(onClick = {
                                                if (onBatchOrganize(activeSelection, action)) {
                                                    selectedInboxIds = emptySet()
                                                    inboxSelecting = false
                                                }
                                            }, enabled = activeSelection.isNotEmpty()) { Text(action.label) }
                                        }
                                    }
                                }
                            }
                        }
                        val displayGroups = if (inboxSelecting) listOf(
                            "之前记录" to pendingAgeGroups.earlier.asReversed(),
                            "最近记录" to pendingAgeGroups.recent.asReversed(),
                            "记录时间未标记" to pendingAgeGroups.undated
                        ) else listOf(
                            "最近记录" to pendingAgeGroups.recent,
                            "之前记录" to pendingAgeGroups.earlier,
                            "记录时间未标记" to pendingAgeGroups.undated
                        )
                        displayGroups.forEach { (label, group) ->
                            if (group.isNotEmpty()) {
                                Text("$label · ${group.size}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                group.forEach { item ->
                                    InboxItemCard(
                                        item = item,
                                        recordedAt = capturedAt[item.id],
                                        reviewedAt = lastReviewedAt[item.id],
                                        now = now,
                                        expanded = !inboxSelecting && expandedInboxId == item.id,
                                        selecting = inboxSelecting && item.id in selectableIds,
                                        selected = item.id in activeSelection,
                                        canQuickConvert = item.id in selectableIds,
                                        onToggle = {
                                            if (inboxSelecting && item.id in selectableIds) selectedInboxIds =
                                                if (item.id in activeSelection) activeSelection - item.id else activeSelection + item.id
                                            else if (!inboxSelecting) expandedInboxId = if (expandedInboxId == item.id) null else item.id
                                        },
                                        onPickTime = onPickTime,
                                        onEdit = onEdit,
                                        onOrganize = onOrganize,
                                        onToTodo = onInboxToTodo,
                                        onToWanted = onInboxToWanted,
                                        onShrink = onShrink,
                                        onPause = onPause,
                                        onAbandon = onAbandon
                                    )
                                }
                            }
                        }
                    }
                    if ((inboxFilter == "全部" || inboxFilter == "推进") && progressItems.isNotEmpty()) {
                        Text("逐步推进 · ${progressItems.size}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        progressItems.forEach { item ->
                            val activeChild = items.firstOrNull { !it.done && it.parentCaptureId == item.id }
                            ProgressCaptureCard(item, activeChild, onOrganize, onCreateNextAction, onRestoreCapture, onAbandon, onTaskDone)
                        }
                    }
                    if ((inboxFilter == "全部" || inboxFilter == "参考") && referenceItems.isNotEmpty()) {
                        Text("参考 · ${referenceItems.size}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        referenceItems.forEach { item -> ReferenceCaptureCard(item, onRestoreCapture, onAbandon) }
                    }
                    val selectedCount = when (inboxFilter) {
                        "待整理" -> pendingInboxItems.size
                        "推进" -> progressItems.size
                        "参考" -> referenceItems.size
                        else -> inboxItems.size
                    }
                    if (selectedCount == 0) {
                        Text("当前分类没有内容。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if (helpOpen) HelpDialog(title = HelpCatalog.today.title, sections = HelpCatalog.today.sections, onDismiss = { helpOpen = false })
    }
}

/** 只对有创建事件的条目显示记录年龄；旧条目不猜测创建时间。 */
internal fun captureAgeLabel(recordedAt: Long, now: Long): String {
    val minutes = ((now - recordedAt).coerceAtLeast(0) / 60_000L)
    return when {
        minutes < 1 -> "刚刚"
        minutes < 60 -> "${minutes}分钟前"
        minutes < 1_440 -> "${minutes / 60}小时前"
        else -> "${minutes / 1_440}天前"
    }
}

@Composable
private fun TodayStatusPanel(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    lifeStage: LifeStage?,
    onSwitchLifeStage: (LifeStage) -> Unit,
    energyLevel: String,
    energyIsCurrent: Boolean,
    energyRecordedAt: Long,
    onEnergyLevelChange: (String) -> Unit,
    campusLifeEnabled: Boolean,
    onCampusLifeEnabledChange: (Boolean) -> Unit,
    commuteProfile: CommuteProfile,
    onCommuteProfileChange: (CommuteProfile) -> Unit
) {
    val summary = buildList {
        lifeStage?.label?.let(::add)
        add(if (energyIsCurrent) "精力$energyLevel" else "精力待更新")
        add(
            if (!campusLifeEnabled) "校园生活关"
            else if (commuteProfile.campusMode == "电动车") "电动车／电量${commuteProfile.eBikeBattery}"
            else commuteProfile.campusMode
        )
    }.joinToString(" · ")
    // 收编：OutlinedCard → FocusCard。显式保留 Material3 OutlinedCard 的默认底色
    // （OutlinedCardTokens.ContainerColor = surface）与默认描边（1dp outlineVariant）。
    FocusCard(
        containerColor = MaterialTheme.colorScheme.surface,
        border = CardDefaults.outlinedCardBorder()
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(
                Modifier.fillMaxWidth().clickable { onExpandedChange(!expanded) },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("今日状态", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(summary, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(if (expanded) "收起" else "调整", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(animationSpec = MotionSpec.move(), expandFrom = Alignment.Top) + fadeIn(MotionSpec.enter()),
                exit = shrinkVertically(animationSpec = MotionSpec.exit(), shrinkTowards = Alignment.Top) + fadeOut(MotionSpec.exit())
            ) {
                Column(Modifier.fillMaxWidth().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    HorizontalDivider()
                    lifeStage?.let { current ->
                        StatusChoiceRow("生活阶段", LifeStage.entries.map { it.label }, current.label) { label ->
                            LifeStage.entries.firstOrNull { it.label == label }?.let(onSwitchLifeStage)
                        }
                    }
                    StatusChoiceRow("精力", listOf("偏低", "正常", "充足"), energyLevel.takeIf { energyIsCurrent }.orEmpty(), onEnergyLevelChange)
                    if (!energyIsCurrent && energyRecordedAt > 0L) {
                        Text("上次记录：${formatDateTime(energyRecordedAt)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text("校园生活", fontWeight = FontWeight.SemiBold)
                            Text("关闭不会删除已有数据", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = campusLifeEnabled, onCheckedChange = onCampusLifeEnabledChange)
                    }
                    if (campusLifeEnabled) {
                        StatusChoiceRow("出行方式", listOf("步行", "自行车", "电动车"), commuteProfile.campusMode) { mode ->
                            onCommuteProfileChange(commuteProfile.copy(campusMode = mode))
                        }
                        if (commuteProfile.campusMode == "电动车") {
                            StatusChoiceRow("电动车电量", listOf("充足", "一般", "偏低", "未知"), commuteProfile.eBikeBattery) { battery ->
                                onCommuteProfileChange(commuteProfile.copy(eBikeBattery = battery))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusChoiceRow(label: String, options: List<String>, selected: String, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { option ->
                FilterChip(selected = selected == option, onClick = { onSelect(option) }, label = { Text(option) })
            }
        }
    }
}

@Composable internal fun MealTodayCard(records: List<MealRecord>, profile: BaselineProfile, skipDays: Set<String>, now: Long, onPrompt: (MealType) -> Unit, onFinish: (MealType) -> Unit) {
    val todayKey = MealLearning.dayKey(now)
    val weekday = java.util.Calendar.getInstance().apply { timeInMillis = now }.get(java.util.Calendar.DAY_OF_WEEK)
    val nowMinute = java.util.Calendar.getInstance().apply { timeInMillis = now }.let { it.get(java.util.Calendar.HOUR_OF_DAY) * 60 + it.get(java.util.Calendar.MINUTE) }
    // 收编：ElevatedCard → FocusCard，显式保留 surfaceContainerLow 底色与 1dp 默认阴影。
    FocusCard(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        elevation = 1.dp
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("今日餐点", fontWeight = FontWeight.Bold)
            if (profile.lifeStage == null) {
                Text("饭点提醒已开启；完成“习惯基线”后才能按你的餐点节奏安排提醒。", style = MaterialTheme.typography.bodySmall)
            } else {
                MealType.entries.forEach { type ->
                    val plan = MealLearning.todayPlan(records, profile, weekday, type)
                    val started = MealLearning.startedToday(records, now, type)
                    val open = MealLearning.latestOpen(records, type)?.takeIf { MealLearning.sameDay(it.startedAt, now) && it.endedAt == null }
                    val skipped = "$todayKey:${type.label}" in skipDays
                    val due = !started && !skipped && nowMinute >= plan.startMinute - 5
                    val learnedLabel = if (plan.learned) "最近 ${plan.sampleCount} 次 · 中位数" else "暂按你填写"
                    val recent = MealLearning.recentLocation(records, type)
                    if (open != null) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text("${type.label} 进行中", fontWeight = FontWeight.SemiBold)
                                Text("预计 ${formatMinute(plan.startMinute + plan.minutes)} 吃完 · 开始于 ${formatMinute(plan.startMinute)}" + (recent?.let { " · 上次在 $it" } ?: ""), style = MaterialTheme.typography.bodySmall)
                            }
                            OutlinedButton(onClick = { onFinish(type) }) { Text("吃完了吗？") }
                        }
                    } else {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(when {
                                    started -> "${type.label} 已记录"
                                    skipped -> "${type.label} 今天不需要"
                                    else -> "${type.label} 预计 ${formatMinute(plan.startMinute)}"
                                }, fontWeight = if (due) FontWeight.SemiBold else FontWeight.Normal)
                                if (started || skipped) Text(if (started) "已确认的开始时间，会用于后续学习。" else "今天不提醒这一餐。", style = MaterialTheme.typography.bodySmall)
                                else Text("$learnedLabel · 约 ${plan.minutes} 分钟" + (recent?.let { " · 常去 $it" } ?: ""), style = MaterialTheme.typography.bodySmall)
                            }
                            if (!started && !skipped) {
                                Button(onClick = { onPrompt(type) }) { Text(if (due) "准备吃饭？" else "现在吃") }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable internal fun InboxItemCard(
    item: Item,
    recordedAt: Long?,
    reviewedAt: Long?,
    now: Long,
    expanded: Boolean,
    selecting: Boolean,
    selected: Boolean,
    canQuickConvert: Boolean,
    onToggle: () -> Unit,
    onPickTime: (Item) -> Unit,
    onEdit: (Item) -> Unit,
    onOrganize: (Item) -> Unit,
    onToTodo: (Item) -> Unit,
    onToWanted: (Item) -> Unit,
    onShrink: (Item) -> Unit,
    onPause: (Item) -> Unit,
    onAbandon: (Item) -> Unit
) {
    var moreOpen by remember(item.id) { mutableStateOf(false) }
    FocusCard(
        modifier = Modifier.fillMaxWidth(),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) { Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (selecting) Checkbox(checked = selected, onCheckedChange = { onToggle() })
            Text(item.title, Modifier.weight(1f), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            recordedAt?.takeIf { it > 0 }?.let {
                Text(captureAgeLabel(it, now), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!selecting) Text(if (expanded) "收起" else "展开", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
        AnimatedVisibility(visible = expanded, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (item.detail.isNotBlank()) Text(item.detail)
                if (item.userNote != null && item.userNote.isNotBlank() && item.userNote != item.detail) {
                    Text("备注：${item.userNote}", style = MaterialTheme.typography.bodySmall)
                }
                Text("预计 ${item.durationMinutes} 分钟 · 优先级 ${ItemPriority.fromKey(item.priority).label}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                reviewedAt?.let { Text("上次回顾 ${captureAgeLabel(it, now)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (!item.title.startsWith("重新安排：")) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (canQuickConvert) {
                            TextButton(onClick = { onToTodo(item) }) { Text("转待办") }
                            TextButton(onClick = { onToWanted(item) }) { Text("放入想做") }
                        }
                        Box {
                            TextButton(onClick = { moreOpen = true }) { Text("更多") }
                            DropdownMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false }) {
                                DropdownMenuItem(text = { Text("安排时间") }, onClick = { moreOpen = false; onPickTime(item) })
                                DropdownMenuItem(text = { Text("整理到其他位置") }, onClick = { moreOpen = false; onOrganize(item) })
                                DropdownMenuItem(text = { Text("编辑") }, onClick = { moreOpen = false; onEdit(item) })
                                DropdownMenuItem(text = { Text("删除") }, onClick = { moreOpen = false; onAbandon(item) })
                            }
                        }
                    }
                } else {
                    Text("接下来", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { onPickTime(item) }) { Text("改期") }
                        TextButton(onClick = { onShrink(item) }) { Text("缩短") }
                        TextButton(onClick = { onPause(item) }) { Text("暂停") }
                        TextButton(onClick = { onAbandon(item) }) { Text("放弃") }
                    }
                }
            }
        }
    } }
}

@Composable private fun ProgressCaptureCard(item: Item, activeChild: Item?, onOrganize: (Item) -> Unit, onCreateNextAction: (Item) -> Unit, onRestore: (Item) -> Unit, onDelete: (Item) -> Unit, onComplete: (Item) -> Unit) {
    // 同上：收编进 FocusCard，让"逐步推进"的卡片也吃材质。
    FocusCard(
        modifier = Modifier.fillMaxWidth(),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) { Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(item.title, fontWeight = FontWeight.SemiBold)
        Text(item.editableNote(), style = MaterialTheme.typography.bodySmall)
        Text(activeChild?.let { "当前步骤：${it.title}" } ?: item.nextAction.takeIf { it.isNotBlank() }?.let { "下一步：$it" } ?: "等待补充下一步，不必立即安排。", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
        if (activeChild != null) {
            Button(onClick = { onComplete(activeChild) }) { Text("这一步已完成") }
        }
        if (activeChild != null) Text("当前状态：${when {
            activeChild.kind == "暂停" -> "已暂停"
            activeChild.scheduledAt != null -> "已安排日程"
            activeChild.windowStartAt != null -> "保留弹性时间"
            activeChild.kind == "收集箱" -> "待安排（也可直接完成）"
            else -> "未安排具体时间"
        }}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = { onCreateNextAction(item) }, enabled = activeChild == null && item.nextAction.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
            Text(if (activeChild == null) "将下一步放入收集箱" else "已有未完成的下一步")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton(onClick = { onOrganize(item) }, enabled = activeChild == null) { Text("修改") }
            TextButton(onClick = { onRestore(item) }) { Text("退回待整理") }
            TextButton(onClick = { onDelete(item) }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("删除方向") }
        }
        Text("退回或删除方向时，已生成的步骤仍会保留。", style = MaterialTheme.typography.labelSmall)
    } }
}

@Composable private fun ReferenceCaptureCard(item: Item, onRestore: (Item) -> Unit, onDelete: (Item) -> Unit) {
    FocusCard(
        modifier = Modifier.fillMaxWidth(),
        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(item.title, fontWeight = FontWeight.SemiBold)
            Text(item.editableNote())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { onRestore(item) }) { Text("退回待整理") }
                TextButton(onClick = { onDelete(item) }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("删除") }
            }
        }
    }
}

internal fun formatActivityRemaining(milliseconds: Long): String {
    val totalSeconds = (milliseconds.coerceAtLeast(0) / 1_000L).toInt()
    val hours = totalSeconds / 3_600
    val minutes = (totalSeconds % 3_600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%02d:%02d".format(minutes, seconds)
}

/** 今日安排摘要条目：课程或任务，按开始分钟排序。 */
internal data class AgendaEntry(val startMinute: Int, val title: String, val subtitle: String, val isCourse: Boolean)

internal fun todayAgenda(courses: List<Course>, items: List<Item>, now: Long = System.currentTimeMillis()): List<AgendaEntry> {
    val weekday = weekdayOf(now)
    val todayCourses = courses.filter { !it.needsConfirmation && it.weekday == weekday }
        .map { AgendaEntry(CourseGapPlanner.periodStart(it.startPeriod), it.title, "第${it.startPeriod}–${it.endPeriod}节 · ${it.building}", true) }
    val todayTasks = items.filter { !it.done && it.scheduledAt?.let { at -> ScheduleOccupation.sameDate(at, now) } == true }
        .mapNotNull { item -> item.scheduledAt?.let { s ->
            val calendar = java.util.Calendar.getInstance().apply { timeInMillis = s }
            AgendaEntry(calendar.get(java.util.Calendar.HOUR_OF_DAY) * 60 + calendar.get(java.util.Calendar.MINUTE), item.title, "任务 · ${item.detail.ifBlank { "已安排" }}", false)
        } }
    return (todayCourses + todayTasks).sortedBy { it.startMinute }
}

@Composable
private fun TodayPermissionReminder(now: Long) {
    val context = LocalContext.current
    val store = remember(context) { PrototypeStore(context) }
    var dismissed by remember { mutableStateOf(store.loadPermissionReminderDismissed()) }
    var detailsOpen by remember { mutableStateOf(false) }
    var confirmDismissOpen by remember { mutableStateOf(false) }
    val permissionEntries = remember(now / 30_000L) { permissionCenterEntries(context) }
    if (dismissed) return
    FocusCard(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.78f)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("权限与提醒", fontWeight = FontWeight.SemiBold)
            Text(
                PermissionCenterPolicy.summary(permissionEntries),
                style = MaterialTheme.typography.bodySmall
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { detailsOpen = true }) { Text("查看") }
                TextButton(onClick = { confirmDismissOpen = true }) { Text("不再提示") }
            }
        }
    }
    if (detailsOpen) PermissionRequirementsDialog(
        todayReminderDismissed = false,
        onDismiss = { detailsOpen = false },
        onRestoreTodayReminder = {}
    )
    if (confirmDismissOpen) AppDialog(
        onDismissRequest = { confirmDismissOpen = false },
        title = { Text("不再在今日页提示？") },
        text = { Text("之后可在 设置 → 检查更新 上方的“权限与提醒”查看和恢复。") },
        confirmButton = {
            Button(onClick = {
                    dismissed = true
                    store.savePermissionReminderDismissed(true)
                    confirmDismissOpen = false
                }) { Text("确认不再提示") }
        },
        dismissButton = { TextButton(onClick = { confirmDismissOpen = false }) { Text("取消") } }
    )
}
