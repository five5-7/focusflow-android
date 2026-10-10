package com.sakata.focusflow

import android.app.DatePickerDialog
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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
    onBatchToday: (Set<Long>, TodoBatchAction, Long?, Boolean) -> Boolean,
    goals: List<Goal>,
    feedback: List<TaskFeedback>,
    commuteProfile: CommuteProfile,
    onCommuteProfileChange: (CommuteProfile) -> Unit,
    activeSession: ActivitySession?,
    activityHistory: List<ActivitySession>,
    nextCommitment: ActivityCommitment?,
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
    var pendingInboxDeleteIds by remember { mutableStateOf(emptySet<Long>()) }
    val reviewContext = LocalContext.current
    val reviewStore = remember(reviewContext) { PrototypeStore(reviewContext) }
    var inboxReviewEnabled by remember { mutableStateOf(reviewStore.loadInboxReviewEnabled()) }
    var inboxReviewLastAt by remember { mutableLongStateOf(reviewStore.loadInboxReviewLastAt()) }
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
    val recoveryCandidates = remember(items, now) { RecoveryInsights.candidates(items, now) }
    val completedTodayItems = remember(taskEvents, items, now) {
        if (taskEvents.isEmpty()) {
            items.filter { it.done && it.completedAt?.let(::isToday) == true }.sortedByDescending { it.completedAt }.map {
                TaskRecorder.event(TaskEventType.TASK_COMPLETED, it.id, it.title, extra = it.completionLevel, at = it.completedAt ?: 0)
            }
        } else TaskHistory.completedOn(taskEvents, TaskHistory.dayStartOf(now))
    }
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
    var tomorrowOpen by remember { mutableStateOf(false) }
    var todaySelecting by remember { mutableStateOf(false) }
    var selectedTodayIds by remember { mutableStateOf(emptySet<Long>()) }
    var keepTodayBatchTime by remember { mutableStateOf(true) }
    val todayContext = LocalContext.current
    val todaySelectableIds = remember(items, now / 60_000L) { TodayBatchSelection.eligibleIds(items, now) }
    LaunchedEffect(todaySelectableIds) { selectedTodayIds = selectedTodayIds.intersect(todaySelectableIds) }
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
            visible = !inboxOpen && !tomorrowOpen,
            // 8.1.0 第三轮：主页直接出现在副页下层（不放大、不淡入）；进入子页时它退到 0.96。
            enter = hubEnter(),
            exit = hubExit()
        ) {
    ScrollableWithBar(scrollState = overviewScrollState) {
        FocusPageHeader(
            title = "今日概览",
            subtitle = "先看状态，再决定现在最合适的一步",
            action = { HelpToggleButton(onClick = { helpOpen = true }) }
        )
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
        val fixed = agenda.filter(AgendaEntry::isCourse)
        val tasks = agenda.filterNot(AgendaEntry::isCourse)
        FocusCard(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
            modifier = Modifier.fillMaxWidth(),
            onClick = onOpenSchedule,
            navigationClick = true
        ) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                FocusSectionHeader("课程与固定安排", fixed.size, action = { Text("›", color = MaterialTheme.colorScheme.primary) })
                fixed.forEach { entry ->
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Surface(
                            modifier = Modifier.widthIn(min = 56.dp),
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                        ) {
                            Text(
                                if (entry.isAllDay) "全天" else formatMinute(entry.startMinute),
                                Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelMedium,
                                softWrap = false
                            )
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(entry.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            Text(entry.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (fixed.isEmpty()) Text("今天没有课程或固定安排", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (tasks.isNotEmpty()) FocusCard(containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth()) {
            Column(
                Modifier.fillMaxWidth().animateContentSize(MotionSpec.quick()).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FocusSectionHeader("今天已安排", tasks.size, action = if (todaySelectableIds.isNotEmpty()) ({
                    TextButton(onClick = {
                        todaySelecting = !todaySelecting; selectedTodayIds = emptySet()
                    }) { Text(if (todaySelecting) "完成整理" else "整理多项") }
                }) else ({ TextButton(onClick = onOpenSchedule) { Text("去日程 ›") } }))
                AnimatedContent(
                    targetState = todaySelecting,
                    transitionSpec = {
                        (fadeIn(MotionSpec.quick()) togetherWith fadeOut(MotionSpec.quick()))
                            .using(SizeTransform { _, _ -> MotionSpec.quick() })
                    },
                    label = "todayBatchMode"
                ) { selecting ->
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!selecting) {
                        tasks.forEach { entry ->
                            Row(
                                Modifier.fillMaxWidth().clickable(onClick = onOpenSchedule),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Surface(
                                    modifier = Modifier.widthIn(min = 56.dp),
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                                ) {
                                    Text(
                                        if (entry.isAllDay) "全天" else formatMinute(entry.startMinute),
                                        Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        style = MaterialTheme.typography.labelMedium,
                                        softWrap = false
                                    )
                                }
                                Text(entry.title, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    } else {
                        Text("已选 ${selectedTodayIds.size} 项 · 计划、重复和关联子任务请单独处理", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { selectedTodayIds = if (selectedTodayIds == todaySelectableIds) emptySet() else todaySelectableIds }) {
                            Text(if (selectedTodayIds == todaySelectableIds) "取消全选" else "全选可整理任务")
                        }
                        items.filter { it.id in todaySelectableIds }.sortedBy { it.scheduledAt }.forEach { item ->
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .toggleable(value = item.id in selectedTodayIds, role = Role.Checkbox) { checked ->
                                    selectedTodayIds = if (checked) selectedTodayIds + item.id else selectedTodayIds - item.id
                                }
                            ) {
                                Checkbox(checked = item.id in selectedTodayIds, onCheckedChange = null)
                                Text(item.title, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        if (selectedTodayIds.isNotEmpty()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = keepTodayBatchTime, onCheckedChange = { keepTodayBatchTime = it })
                                Text("改期保留原时刻；关闭则仅指定日期", style = MaterialTheme.typography.bodySmall)
                            }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(onClick = {
                                    val target = java.util.Calendar.getInstance().apply {
                                        timeInMillis = now; add(java.util.Calendar.DAY_OF_YEAR, 1)
                                    }.timeInMillis
                                    if (onBatchToday(selectedTodayIds, TodoBatchAction.MOVE_DATE, target, keepTodayBatchTime)) {
                                        todaySelecting = false; selectedTodayIds = emptySet()
                                    }
                                }) { Text("移到明天") }
                                TextButton(onClick = {
                                    val calendar = java.util.Calendar.getInstance().apply { timeInMillis = now }
                                    DatePickerDialog(todayContext, { _, year, month, day ->
                                        val target = java.util.Calendar.getInstance().apply {
                                            set(year, month, day, 12, 0, 0); set(java.util.Calendar.MILLISECOND, 0)
                                        }.timeInMillis
                                        if (onBatchToday(selectedTodayIds, TodoBatchAction.MOVE_DATE, target, keepTodayBatchTime)) {
                                            todaySelecting = false; selectedTodayIds = emptySet()
                                        }
                                    }, calendar.get(java.util.Calendar.YEAR), calendar.get(java.util.Calendar.MONTH),
                                        calendar.get(java.util.Calendar.DAY_OF_MONTH)).show()
                                }) { Text("选择日期") }
                            }
                            TextButton(onClick = {
                                if (onBatchToday(selectedTodayIds, TodoBatchAction.CLEAR_TIME, null, false)) {
                                    todaySelecting = false; selectedTodayIds = emptySet()
                                }
                            }) { Text("改为未安排") }
                        }
                    }
                    }
                }
            }
        }
        if (recoveryCandidates.isNotEmpty()) FocusCard(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            onClick = onOpenSchedule,
            navigationClick = true
        ) {
            Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("需要处理 · ${recoveryCandidates.size} 项", fontWeight = FontWeight.Bold)
                Text("去日程 ›", color = MaterialTheme.colorScheme.primary)
            }
        }
        FocusCard(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (inboxItems.isNotEmpty()) {
                    FocusSectionHeader(
                        "收集箱",
                        inboxItems.size,
                        keepActionInline = true,
                        action = { TextButton(onClick = { onInboxOpenChange(true) }) { Text("查看全部 ›") } }
                    )
                }
                fun saveCapture() {
                    val title = captureText.trim()
                    if (title.isNotEmpty() && onCaptureToInbox(title)) captureText = ""
                }
                    FlowRow(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        maxItemsInEachRow = 2
                    ) {
                    OutlinedTextField(
                        value = captureText,
                        onValueChange = { captureText = it },
                            modifier = Modifier.weight(1f).widthIn(min = 180.dp),
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
            !it.done && it.goalId != null && !RecoveryInsights.missedWindow(it, now) &&
                it.scheduledAt?.let { at -> ScheduleOccupation.sameDate(at, now) } == true
        }
        val goalsRemaining = goals.count { it.weeklyTarget > GoalPlanner.completedThisWeek(it) }
        if (visibility.goals && (todayGoalTasks.isNotEmpty() || goalsRemaining > 0)) {
            // 收编：ElevatedCard → FocusCard，显式保留 surfaceContainerLow 底色与 1dp 默认阴影。
            FocusCard(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                elevation = 1.dp
            ) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    FocusSectionHeader("今天的目标", action = { TextButton(onClick = onOpenGoals) { Text("目标与执行 ›") } })
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
        if (completedTodayItems.isNotEmpty()) {
            // 收编：ElevatedCard → FocusCard，显式保留 surfaceContainerLow 底色与 1dp 默认阴影。
            FocusCard(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                elevation = 1.dp
            ) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    FocusSectionHeader("今日完成记录")
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
        if (visibility.windDown) WindDownInsights.advice(baselineProfile, courses, items, checkIns, activityHistory, now)?.let { advice ->
            FocusCard(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    FocusSectionHeader("睡前减速")
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
        val tomorrow = java.util.Calendar.getInstance().apply { timeInMillis = now; add(java.util.Calendar.DAY_OF_YEAR, 1) }.timeInMillis
        val tomorrowAgenda = todayAgenda(courses, items, tomorrow, hideMissed = false)
        FocusCard(containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth(),
            onClick = { tomorrowOpen = true },
            navigationClick = true) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("明天预览 · ${tomorrowAgenda.size} 项  ›", fontWeight = FontWeight.Bold)
                tomorrowAgenda.take(2).forEach { Text("${if (it.isAllDay) "全天" else formatMinute(it.startMinute)} · ${it.title}", style = MaterialTheme.typography.bodySmall) }
                if (tomorrowAgenda.size > 2) Text("还有 ${tomorrowAgenda.size - 2} 项", style = MaterialTheme.typography.labelSmall)
                if (tomorrowAgenda.isEmpty()) Text("明天暂未安排", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
        }
        BackHandler(enabled = tomorrowOpen) { tomorrowOpen = false }
        SubpageMotion(tomorrowOpen.takeIf { it }) {
            val tomorrow = java.util.Calendar.getInstance().apply { timeInMillis = now; add(java.util.Calendar.DAY_OF_YEAR, 1) }.timeInMillis
            val entries = todayAgenda(courses, items, tomorrow, hideMissed = false)
            PlanSubpageFrame(Modifier.fillMaxSize(), "明天的安排", titleAction = {
                TextButton(onClick = { tomorrowOpen = false }) { Text("返回今日") }
            }) {
                if (entries.isEmpty()) Text("明天暂未安排")
                entries.forEach { entry ->
                    FocusCard(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("${if (entry.isAllDay) "全天" else formatMinute(entry.startMinute)} · ${entry.title}", fontWeight = FontWeight.SemiBold)
                            Text(entry.subtitle, style = MaterialTheme.typography.bodySmall)
                            val task = entry.itemId?.let { id -> items.firstOrNull { it.id == id && !it.done } }
                            if (task != null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { onPickTime(task) }) { Text("调整时间") }
                                TextButton(onClick = { onTaskDone(task) }) { Text("完成") }
                            } else if (entry.isCourse) TextButton(onClick = {
                                tomorrowOpen = false; onOpenSchedule()
                            }) { Text("查看课程日程") }
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
                        val monthOldCount = pendingInboxItems.count { item ->
                            capturedAt[item.id]?.let { it > 0L && it <= now - 30L * 24 * 60 * 60_000 } == true
                        }
                        if (inboxReviewEnabled && monthOldCount > 0 && now - inboxReviewLastAt >= 30L * 24 * 60 * 60_000) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically) {
                                Text("$monthOldCount 条记录超过一个月尚未整理", Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = {
                                    if (reviewStore.saveInboxReviewLastAt(now)) inboxReviewLastAt = now
                                }) { Text("知道了") }
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("待整理 · ${pendingInboxItems.size}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            if (selectableInboxItems.isNotEmpty()) TextButton(onClick = {
                                inboxSelecting = !inboxSelecting
                                expandedInboxId = null
                                selectedInboxIds = emptySet()
                            }) { Text(if (inboxSelecting) "完成" else "整理多项") }
                        }
                        AnimatedVisibility(
                            visible = inboxSelecting,
                            // 批量操作卡含有多行按钮；避免 expandVertically 在玻璃背景上逐帧重排。
                            enter = fadeIn(MotionSpec.quick()),
                            exit = fadeOut(MotionSpec.exit())
                        ) {
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
                                                if (action == InboxBatchAction.DELETE) {
                                                    pendingInboxDeleteIds = activeSelection
                                                } else if (onBatchOrganize(activeSelection, action)) {
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
        if (pendingInboxDeleteIds.isNotEmpty()) AlertDialog(
            onDismissRequest = { pendingInboxDeleteIds = emptySet() },
            title = { Text("删除所选记录？") },
            text = { Text("将 ${pendingInboxDeleteIds.size} 条移入最近删除（保留 30 天），可从设置的数据与恢复中找回。") },
            confirmButton = { TextButton(onClick = {
                val ids = pendingInboxDeleteIds
                pendingInboxDeleteIds = emptySet()
                if (onBatchOrganize(ids, InboxBatchAction.DELETE)) {
                    selectedInboxIds = emptySet()
                    inboxSelecting = false
                }
            }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { pendingInboxDeleteIds = emptySet() }) { Text("取消") } }
        )
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
    val statusArrowRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = MotionSpec.quick(),
        label = "todayStatusArrow"
    )
    // 收编：OutlinedCard → FocusCard。显式保留 Material3 OutlinedCard 的默认底色
    // （OutlinedCardTokens.ContainerColor = surface）与默认描边（1dp outlineVariant）。
    FocusCard(
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = Modifier.semantics {
            stateDescription = if (expanded) "已展开；双击收起" else "已收起；双击展开"
        },
        border = CardDefaults.outlinedCardBorder(),
        elevation = 1.dp,
        onClick = { onExpandedChange(!expanded) }
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(
                Modifier.fillMaxWidth()
                    .minimumInteractiveComponentSize(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("今日状态", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(summary, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(if (expanded) "收起" else "调整", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Icon(
                        Icons.Filled.KeyboardArrowDown,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp).graphicsLayer { rotationZ = statusArrowRotation },
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            AnimatedVisibility(
                visible = expanded,
                // 今日状态包含多个横向选择器；淡入保留反馈，避免展开动画逐帧重排整页。
                enter = fadeIn(MotionSpec.quick()),
                exit = fadeOut(MotionSpec.exit())
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

@OptIn(ExperimentalLayoutApi::class)
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
            Modifier.fillMaxWidth()
                .heightIn(min = 48.dp)
                .then(
                    if (selecting) Modifier.toggleable(
                        value = selected, role = Role.Checkbox, onValueChange = { onToggle() }
                    ) else Modifier.clickable(role = Role.Button, onClick = onToggle)
                        .semantics { stateDescription = if (expanded) "已展开" else "已收起" }
                )
                .padding(vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (selecting) Checkbox(checked = selected, onCheckedChange = null)
            Text(item.title, Modifier.weight(1f), fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            recordedAt?.takeIf { it > 0 }?.let {
                Text(captureAgeLabel(it, now), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!selecting) Text(if (expanded) "收起" else "展开", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
        AnimatedVisibility(
            visible = expanded,
            // 先完成一次尺寸布局，再淡入内容，避免展开期间反复触发卡片背景捕获。
            enter = fadeIn(MotionSpec.quick()),
            exit = fadeOut(MotionSpec.exit())
        ) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (item.detail.isNotBlank()) Text(item.detail)
                if (item.userNote != null && item.userNote.isNotBlank() && item.userNote != item.detail) {
                    Text("备注：${item.userNote}", style = MaterialTheme.typography.bodySmall)
                }
                Text("预计 ${item.durationMinutes} 分钟 · 优先级 ${ItemPriority.fromKey(item.priority).label}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                reviewedAt?.let { Text("上次回顾 ${captureAgeLabel(it, now)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (!item.title.startsWith("重新安排：")) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
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

@OptIn(ExperimentalLayoutApi::class)
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
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
internal data class AgendaEntry(val startMinute: Int, val title: String, val subtitle: String,
                                val isCourse: Boolean, val itemId: Long? = null, val isAllDay: Boolean = false)

internal fun todayAgenda(courses: List<Course>, items: List<Item>, now: Long = System.currentTimeMillis(),
                         hideMissed: Boolean = true): List<AgendaEntry> {
    val weekday = weekdayOf(now)
    val todayCourses = courses.filter { !it.needsConfirmation && it.weekday == weekday }
        .map { AgendaEntry(CourseGapPlanner.periodStart(it.startPeriod), it.title, "第${it.startPeriod}–${it.endPeriod}节 · ${it.building}", true) }
    val todayTasks = items.filter { !it.done && (!hideMissed || !RecoveryInsights.missedWindow(it, now)) &&
        it.scheduledAt?.let { at -> ScheduleOccupation.sameDate(at, now) } == true }
        .mapNotNull { item -> item.scheduledAt?.let { s ->
            val calendar = java.util.Calendar.getInstance().apply { timeInMillis = s }
            AgendaEntry(calendar.get(java.util.Calendar.HOUR_OF_DAY) * 60 + calendar.get(java.util.Calendar.MINUTE), item.title,
                "任务 · ${item.detail.ifBlank { "已安排" }}", false, item.id, item.dayOnly)
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
    if (dismissed || permissionEntries.none { it.status == PermissionCenterStatus.ACTION_NEEDED }) return
    FocusCard(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.78f)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("后台与提醒状态", fontWeight = FontWeight.SemiBold)
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
