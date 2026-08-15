package com.sakata.focusflow

import android.Manifest
import android.app.AlarmManager
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.util.UUID

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }
        enableEdgeToEdge()
        setContent { FocusFlowApp() }
    }
}

internal fun newItemId(): Long {
    var id: Long
    do {
        val uuid = UUID.randomUUID()
        id = (uuid.mostSignificantBits xor uuid.leastSignificantBits) and Long.MAX_VALUE
    } while (id == 0L)
    return id
}

data class Item(
    val id: Long = newItemId(),
    val title: String,
    val detail: String,
    val kind: String,
    val done: Boolean = false,
    val scheduledAt: Long? = null,
    val dayOnly: Boolean = false,
    val goalId: Long? = null,
    val completionLevel: String = "",
    val completedAt: Long? = null,
    val durationMinutes: Int = 60
)

data class CommuteProfile(
    val enabled: Boolean = false,
    val oneWayMinutes: Int = 0,
    val campusMode: String = "步行",
    val buildingBufferMinutes: Int = 3,
    val eBikeBattery: String = "未知"
)

@Composable
private fun FocusFlowApp() {
    val context = LocalContext.current
    val store = remember(context) { PrototypeStore(context) }
    var tab by remember { mutableIntStateOf(0) }
    var addOpen by remember { mutableStateOf(false) }
    var activityOpen by remember { mutableStateOf(false) }
    var transitionTarget by remember { mutableStateOf<ActivitySession?>(null) }
    var autoPromptedSessionId by remember { mutableStateOf<Long?>(null) }
    var rescheduleTarget by remember { mutableStateOf<Item?>(null) }
    var inboxEditTarget by remember { mutableStateOf<Item?>(null) }
    var items by remember {
        mutableStateOf(store.recoverMissedGoalTasks().ifEmpty {
            listOf(
                Item(title = "今天做 10 分钟拉伸", detail = "弹性任务 · 可改期", kind = "任务"),
                Item(title = "本周锻炼 3 次", detail = "已完成 0 / 3 次", kind = "计划"),
                Item(title = "睡前减速", detail = "22:30 开始收尾", kind = "习惯")
            )
        })
    }
    var activeSession by remember { mutableStateOf(store.loadLatestActiveSession()) }
    var activityHistory by remember { mutableStateOf(store.loadRecentActivitySessions()) }
    var activitySettings by remember { mutableStateOf(store.loadActivityReminderSettings()) }
    var themeOption by remember { mutableStateOf(store.loadTheme()) }
    var commuteProfile by remember { mutableStateOf(store.loadCommuteProfile()) }
    var courses by remember { mutableStateOf(if (store.hasCourseSetup()) store.loadCourses() else ScreenshotCoursePreview.courses) }
    var courseEditor by remember { mutableStateOf<Course?>(null) }
    var addCourseOpen by remember { mutableStateOf(false) }
    var goals by remember { mutableStateOf(store.loadGoals()) }
    var addGoalOpen by remember { mutableStateOf(false) }
    var resources by remember { mutableStateOf(store.loadResources()) }
    var addResourceOpen by remember { mutableStateOf(false) }
    var completionTarget by remember { mutableStateOf<Item?>(null) }
    var feedbackTarget by remember { mutableStateOf<Pair<Item, String>?>(null) }
    var feedback by remember { mutableStateOf(store.loadFeedback()) }
    var improvementNotes by remember { mutableStateOf(store.loadImprovementNotes()) }
    var improvementOpen by remember { mutableStateOf(false) }
    var roadmapSelections by remember { mutableStateOf(store.loadRoadmapSelections()) }
    var planPage by remember { mutableStateOf<PlanPage?>(null) }
    val suggestedNextStep = items
        .filter { !it.done && it.kind != "收集箱" && it.kind != "暂停" }
        .sortedWith(compareBy<Item> { it.scheduledAt ?: Long.MAX_VALUE }.thenBy { it.title })
        .firstOrNull()
    val upcomingCommitment = nextActivityCommitment(items, courses)
    val suggestedNextStepName = upcomingCommitment?.title ?: suggestedNextStep?.title.orEmpty()
    fun saveItems(updated: List<Item>) { items = updated; store.saveItems(updated) }
    fun selectTab(index: Int) {
        if (index == 2) planPage = null
        tab = index
    }
    BackHandler(enabled = tab == 2 && planPage != null) { planPage = null }

    LaunchedEffect(Unit) {
        ReminderScheduler.restoreActivityReminders(context)
        while (true) {
            val restored = store.loadLatestActiveSession()
            activeSession = restored
            activityHistory = store.loadRecentActivitySessions()
            if (restored == null) {
                transitionTarget = null
            } else if (transitionTarget?.id == restored.id && transitionTarget != restored) {
                transitionTarget = restored
            }
            if (restored?.status == ActivitySession.STATUS_AWAITING_CONFIRMATION && autoPromptedSessionId != restored.id) {
                transitionTarget = restored
                autoPromptedSessionId = restored.id
            } else if (restored?.status != ActivitySession.STATUS_AWAITING_CONFIRMATION) {
                autoPromptedSessionId = null
            }
            delay(1_000)
        }
    }

    val themeSpec = focusFlowThemeSpec(themeOption)
    CompositionLocalProvider(LocalFocusFlowSchedulePalette provides themeSpec.schedulePalette) {
    MaterialTheme(colorScheme = themeSpec.colorScheme) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    NavigationBarItem(selected = tab == 0, onClick = { selectTab(0) }, icon = { Text(if (tab == 0) "●" else "○") }, modifier = Modifier.weight(1f), label = { Text("今日") })
                    NavigationBarItem(selected = tab == 1, onClick = { selectTab(1) }, icon = { Text(if (tab == 1) "●" else "○") }, modifier = Modifier.weight(1f), label = { Text("日程") })
                    Box(Modifier.weight(0.82f), contentAlignment = Alignment.Center) {
                        FloatingActionButton(modifier = Modifier.size(50.dp), onClick = { addOpen = true }) { Text("＋", style = MaterialTheme.typography.headlineSmall) }
                    }
                    NavigationBarItem(
                        selected = tab == 2,
                        onClick = { selectTab(2) },
                        icon = { Text(if (tab != 2) "○" else if (planPage == null) "●" else "◉") },
                        modifier = Modifier.weight(1f),
                        label = { Text(planPage?.let { "计划·${it.title.take(2)}" } ?: "计划") }
                    )
                    NavigationBarItem(selected = tab == 3, onClick = { selectTab(3) }, icon = { Text(if (tab == 3) "●" else "○") }, modifier = Modifier.weight(1f), label = { Text("设置") })
                }
            }
        ) { padding ->
            when (tab) {
                0 -> TodayScreen(
                    Modifier.padding(padding), items,
                    onTaskDone = { item ->
                        if (item.goalId == null) saveItems(items.map { if (it.id == item.id) it.copy(done = true, completionLevel = "完成", completedAt = System.currentTimeMillis()) else it }) else completionTarget = item
                    },
                    activeSession = activeSession,
                    activityHistory = activityHistory,
                    onStartActivity = { activityOpen = true },
                    onReviewActivity = { activeSession?.let { transitionTarget = it } },
                    onPickTime = { item -> rescheduleTarget = item },
                    onEdit = { item -> inboxEditTarget = item },
                    onShrink = { item -> saveItems(items.map { if (it.id == item.id) it.copy(title = item.title.removePrefix("重新安排："), kind = "任务", detail = "短版：先做 10 分钟 · 今天有空时") else it }) },
                    onPause = { item -> saveItems(items.map { if (it.id == item.id) it.copy(kind = "暂停", detail = "已暂停；随时可在计划中恢复") else it }) },
                    onAbandon = { item -> saveItems(items.filterNot { it.id == item.id }) }
                )
                1 -> ScheduleScreen(
                    Modifier.padding(padding), items, courses,
                    onTaskDone = { item ->
                        if (item.goalId == null) saveItems(items.map { if (it.id == item.id) it.copy(done = true, completionLevel = "完成", completedAt = System.currentTimeMillis()) else it }) else completionTarget = item
                    }
                )
                2 -> PlansScreen(
                    Modifier.padding(padding), items, courses, commuteProfile,
                    page = planPage,
                    onPageChange = { planPage = it },
                    onResume = { item -> saveItems(items.map { if (it.id == item.id) it.copy(kind = "任务", detail = "已恢复；今天有空时再做", scheduledAt = null) else it }) },
                    onConfirmCourse = { course ->
                        courses = courses.map { if (it == course) it.copy(needsConfirmation = false) else it }
                        store.saveCourses(courses)
                    },
                    onIgnoreCourse = { course ->
                        courses = courses.filterNot { it == course }
                        store.saveCourses(courses)
                    },
                    onAddCourse = { addCourseOpen = true },
                    onEditCourse = { courseEditor = it },
                    goals = goals,
                    onAddGoal = { addGoalOpen = true },
                    onScheduleGoal = { goal, suggestion ->
                        val scheduled = Item(title = goal.title, detail = "${goal.metricType}：${goal.metricTarget.ifBlank { "本次完成" }} · ${weekdayName(suggestion.weekday)} ${GoalPlanner.displayTime(suggestion.startMinute)}", kind = "任务", scheduledAt = GoalPlanner.nextOccurrence(suggestion.weekday, suggestion.startMinute), goalId = goal.id, durationMinutes = goal.durationMinutes)
                        saveItems(listOf(scheduled) + items)
                        ReminderScheduler.scheduleTaskReminder(context, scheduled)
                    },
                    resources = resources,
                    onAddResource = { addResourceOpen = true },
                    onSelectResource = { resource ->
                        resources = resources.map { it.copy(selected = it.id == resource.id) }
                        store.saveResources(resources)
                    },
                    feedback = feedback
                )
                else -> SettingsScreen(Modifier.padding(padding), themeOption, commuteProfile, improvementNotes, roadmapSelections, activitySettings, onThemeChange = { updated ->
                    themeOption = updated
                    store.saveTheme(updated)
                }, onCommuteChange = { updated ->
                    commuteProfile = updated
                    store.saveCommuteProfile(updated)
                }, onActivitySettingsChange = { updated ->
                    activitySettings = updated
                    store.saveActivityReminderSettings(updated)
                    activeSession?.let { ReminderScheduler.scheduleActivityReminders(context, it, updated) }
                }, onAddImprovement = { improvementOpen = true }, onToggleRoadmap = { feature ->
                    roadmapSelections = if (feature.id in roadmapSelections) roadmapSelections - feature.id else roadmapSelections + feature.id
                    store.saveRoadmapSelections(roadmapSelections)
                })
            }
        }
        if (addOpen) QuickCaptureDialog(onDismiss = { addOpen = false }) { text, tomorrow ->
            val captured = if (tomorrow) {
                Item(title = text, detail = "明天要做 · 尚未安排具体时间", kind = "任务", scheduledAt = dateAt(1, 10), dayOnly = true)
            } else Item(title = text, detail = "刚刚记录 · 稍后决定安排", kind = "收集箱")
            saveItems(listOf(captured) + items)
            if (tomorrow) ReminderScheduler.scheduleTaskReminder(context, captured)
            addOpen = false
        }
        if (activityOpen) ActivityDialog(suggestedNextStepName, onDismiss = { activityOpen = false }) { category, name, endsAt, nextStep ->
            val now = System.currentTimeMillis()
            val session = ActivitySession(name = name, category = category, plannedStartAt = now, actualStartAt = now, endsAt = endsAt, nextStep = nextStep)
            store.saveSession(session)
            activeSession = session
            ReminderScheduler.scheduleActivityReminders(context, session, activitySettings)
            activityOpen = false
        }
        transitionTarget?.let { session -> ActivityTransitionDialog(
            session = session,
            maxExtensions = activitySettings.maxExtensions,
            upcomingCommitment = upcomingCommitment,
            onDismiss = { transitionTarget = null },
            onFinish = { actualEndAt ->
                store.finishSession(session.id, ActivitySession.STATUS_COMPLETED, "finished_now", actualEndAt)
                ReminderScheduler.cancelActivityReminders(context, session.id)
                activeSession = null
                transitionTarget = null
            },
            onStartNext = {
                val now = System.currentTimeMillis()
                store.finishSession(session.id, ActivitySession.STATUS_COMPLETED, "started_next", now)
                ReminderScheduler.cancelActivityReminders(context, session.id)
                val nextName = session.nextStep.ifBlank { suggestedNextStepName }
                if (nextName.isNotBlank()) {
                    val courseDuration = courses.firstOrNull { nextName.startsWith(it.title) }?.let { CourseGapPlanner.periodEnd(it.endPeriod) - CourseGapPlanner.periodStart(it.startPeriod) }
                    val duration = items.firstOrNull { it.title == nextName }?.durationMinutes ?: courseDuration ?: 30
                    val nextSession = ActivitySession(name = nextName, category = "下一步", plannedStartAt = now, actualStartAt = now, endsAt = now + duration * 60_000L)
                    store.saveSession(nextSession)
                    activeSession = nextSession
                    ReminderScheduler.scheduleActivityReminders(context, nextSession, activitySettings)
                } else activeSession = null
                transitionTarget = null
            },
            onExtend = { minutes, reason ->
                store.extendSession(session.id, minutes, reason)?.let { extended ->
                    activeSession = extended
                    ReminderScheduler.scheduleActivityReminders(context, extended, activitySettings)
                }
                transitionTarget = null
            },
            onReplan = {
                store.finishSession(session.id, ActivitySession.STATUS_SKIPPED, "replan")
                ReminderScheduler.cancelActivityReminders(context, session.id)
                store.addReplanItem(session.nextStep.ifBlank { session.name })
                items = store.loadItems()
                activeSession = null
                transitionTarget = null
            }
        ) }
        rescheduleTarget?.let { item -> RescheduleTimeDialog(item, onDismiss = { rescheduleTarget = null }) { scheduledAt, duration, label ->
            val delayed = item.copy(kind = "任务", detail = "已改期至$label；届时会再次出现", scheduledAt = scheduledAt, durationMinutes = duration, dayOnly = false)
            saveItems(items.map { if (it.id == item.id) delayed else it })
            ReminderScheduler.scheduleTaskReminder(context, delayed)
            rescheduleTarget = null
        } }
        inboxEditTarget?.let { item -> InboxEditDialog(item, onDismiss = { inboxEditTarget = null }) { title, detail ->
            saveItems(items.map { if (it.id == item.id) it.copy(title = title, detail = detail) else it })
            inboxEditTarget = null
        } }
        if (addCourseOpen) CourseEditorDialog(null, onDismiss = { addCourseOpen = false }) { course ->
            courses = courses + course.copy(needsConfirmation = false)
            store.saveCourses(courses)
            addCourseOpen = false
        }
        courseEditor?.let { original -> CourseEditorDialog(original, onDismiss = { courseEditor = null }) { edited ->
            courses = courses.map { if (it == original) edited.copy(needsConfirmation = false) else it }
            store.saveCourses(courses)
            courseEditor = null
        } }
        if (addGoalOpen) GoalEditorDialog(resources.firstOrNull { it.selected }, onDismiss = { addGoalOpen = false }) { goal ->
            goals = goals + goal
            store.saveGoals(goals)
            addGoalOpen = false
        }
        if (addResourceOpen) ResourceEditorDialog(onDismiss = { addResourceOpen = false }) { resource ->
            resources = resources + resource
            store.saveResources(resources)
            addResourceOpen = false
        }
        completionTarget?.let { item -> CompletionDialog(item, goals.firstOrNull { it.id == item.goalId }, onDismiss = { completionTarget = null }) { level ->
            saveItems(items.map { if (it.id == item.id) it.copy(done = true, completionLevel = level, completedAt = System.currentTimeMillis()) else it })
            item.goalId?.let { goalId ->
                val key = GoalPlanner.currentWeekKey()
                goals = goals.map { goal -> if (goal.id != goalId) goal else if (goal.completionWeekKey == key) {
                    if (level == "最低版本") goal.copy(minimumCompletionsThisWeek = goal.minimumCompletionsThisWeek + 1) else goal.copy(completedThisWeek = goal.completedThisWeek + 1)
                } else if (level == "最低版本") goal.copy(minimumCompletionsThisWeek = 1, completionWeekKey = key) else goal.copy(completedThisWeek = 1, minimumCompletionsThisWeek = 0, completionWeekKey = key) }
                store.saveGoals(goals)
            }
            completionTarget = null
            feedbackTarget = item to level
        } }
        feedbackTarget?.let { (item, level) -> FeedbackDialog(level, onDismiss = { feedbackTarget = null }) { difficulty, barrier ->
            item.goalId?.let { goalId ->
                val entry = TaskFeedback(goalId = goalId, completionLevel = level, difficulty = difficulty, barrier = barrier)
                store.addFeedback(entry)
                feedback = feedback + entry
            }
            feedbackTarget = null
        } }
        if (improvementOpen) ImprovementDialog(onDismiss = { improvementOpen = false }) { text ->
            improvementNotes = improvementNotes + ImprovementNote(text = text)
            store.saveImprovementNotes(improvementNotes)
            improvementOpen = false
        }
    }
    }
}

@Composable private fun TodayScreen(
    modifier: Modifier,
    items: List<Item>,
    onTaskDone: (Item) -> Unit,
    activeSession: ActivitySession?,
    activityHistory: List<ActivitySession>,
    onStartActivity: () -> Unit,
    onReviewActivity: () -> Unit,
    onPickTime: (Item) -> Unit,
    onEdit: (Item) -> Unit,
    onShrink: (Item) -> Unit,
    onPause: (Item) -> Unit,
    onAbandon: (Item) -> Unit
) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(activeSession?.id, activeSession?.endsAt) {
        while (activeSession != null) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val scheduledToday = items.filter { !it.done && it.scheduledAt?.let(::isToday) == true }.sortedBy { it.scheduledAt }
    val flexibleItems = items.filter { !it.done && it.kind != "暂停" && it.kind != "收集箱" && it.scheduledAt == null }
    val inboxItems = items.filter { !it.done && it.kind == "收集箱" }
    val nextItem = scheduledToday.firstOrNull { (it.scheduledAt ?: Long.MAX_VALUE) >= now } ?: flexibleItems.firstOrNull()
    val completedToday = items.count { it.done && it.completedAt?.let(::isToday) == true }
    val completedThisWeek = items.count { it.done && it.completedAt?.let(::isInCurrentWeek) == true }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("今日概览", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
        Text("先看状态与下一步；具体时间安排已移到“日程”。", style = MaterialTheme.typography.bodyLarge)
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (activeSession == null) {
                    Text("当前状态未设置", fontWeight = FontWeight.Bold)
                    Text("开始前约定结束时间和下一步；到点后由你明确决定。")
                    Button(onClick = onStartActivity) { Text("开始活动") }
                } else {
                    val due = now >= activeSession.endsAt || activeSession.status == ActivitySession.STATUS_AWAITING_CONFIRMATION
                    Text(if (due) "需要确认：${activeSession.name}" else "正在：${activeSession.name}", fontWeight = FontWeight.Bold)
                    Text(if (due) "已到预计结束时间 ${formatTime(activeSession.endsAt)}" else "剩余 ${formatActivityRemaining(activeSession.endsAt - now)} · 预计 ${formatTime(activeSession.endsAt)} 结束")
                    if (activeSession.nextStep.isNotBlank()) Text("下一步：${activeSession.nextStep}")
                    if (activeSession.extensionCount > 0) Text("已延长 ${activeSession.extensionCount} 次${activeSession.extensionReason.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()}", style = MaterialTheme.typography.bodySmall)
                    Button(onClick = onReviewActivity) { Text(if (due) "处理到点" else "结束或调整") }
                }
            }
        }
        val completedActivities = activityHistory.filter { it.actualEndAt?.let(::isToday) == true }
        if (completedActivities.isNotEmpty()) {
            ElevatedCard {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("今日活动记录 · ${completedActivities.size} 次", fontWeight = FontWeight.Bold)
                    completedActivities.take(3).forEach { session ->
                        val minutes = (((session.actualEndAt ?: session.endsAt) - session.actualStartAt).coerceAtLeast(0) / 60_000L).toInt()
                        Text("${session.name} · $minutes 分钟 · ${if (session.status == ActivitySession.STATUS_COMPLETED) "已结束" else "已重新安排"}", style = MaterialTheme.typography.bodySmall)
                    }
                    Text("休息和娱乐只作为时间记录，不会被简单判定为负面。", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        ElevatedCard { Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("$completedToday", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Text("今日完成", style = MaterialTheme.typography.labelMedium) }
            Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("$completedThisWeek", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Text("本周完成", style = MaterialTheme.typography.labelMedium) }
            Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("${inboxItems.size}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Text("待整理", style = MaterialTheme.typography.labelMedium) }
        } }
        Text("下一件合适的事", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        nextItem?.let { item ->
            ElevatedCard {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) { Text(item.title, fontWeight = FontWeight.SemiBold); Text(item.detail) }
                    TextButton(onClick = { onTaskDone(item) }) { Text("完成") }
                }
            }
        } ?: Text("没有必须现在做的事。你可以休息、开始活动，或随手记录一个想法。")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("收集箱", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("${inboxItems.size} 项", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("先记下，再在这里编辑、安排或删除。", style = MaterialTheme.typography.bodySmall)
        if (inboxItems.isEmpty()) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))) {
                Text("暂时没有新想法，点底部 ＋ 随手记录。", Modifier.fillMaxWidth().padding(16.dp))
            }
        } else {
            inboxItems.forEach { item -> InboxItemCard(item, onPickTime, onEdit, onShrink, onPause, onAbandon) }
        }
    }
}

@Composable private fun ScheduleScreen(modifier: Modifier, items: List<Item>, courses: List<Course>, onTaskDone: (Item) -> Unit) {
    val weekday = todayWeekday()
    val todaySchedule = items.filter { !it.dayOnly && it.scheduledAt?.let(::isToday) == true }.sortedBy { it.scheduledAt }
    val todayUnslotted = items.filter { !it.done && it.dayOnly && it.scheduledAt?.let(::isToday) == true }
    val todayCourses = courses.filter { !it.needsConfirmation && it.weekday == weekday }.sortedBy { it.startPeriod }
    val flexibleItems = items.filter { !it.done && it.kind != "暂停" && it.kind != "收集箱" && it.scheduledAt == null }
    var scheduleMode by remember { mutableStateOf("日") }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("日程", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(if (scheduleMode == "日") "今天" else "本周", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = scheduleMode == "日", onClick = { scheduleMode = "日" }, label = { Text("日") })
                FilterChip(selected = scheduleMode == "周", onClick = { scheduleMode = "周" }, label = { Text("周") })
            }
        }
        Text(if (scheduleMode == "日") "按真实时间连续排布；点击色块查看起止时间。" else "周一至周日同屏显示；点击色块查看详情。", style = MaterialTheme.typography.bodySmall)
        if (scheduleMode == "日") {
            if (todayUnslotted.isNotEmpty()) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("今日待办（尚未指定时段）", fontWeight = FontWeight.SemiBold)
                        todayUnslotted.forEach { Text("• ${it.title}", style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
            DailyScheduleTimeline(todayCourses, todaySchedule, onTaskDone)
        } else {
            WeeklyScheduleTimeline(courses.filter { !it.needsConfirmation }, items, onTaskDone)
        }
        if (flexibleItems.isNotEmpty()) {
            Text("弹性安排", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            flexibleItems.take(4).forEach { item -> ScheduleTableRow(item.title, item.detail, item.scheduleType()) }
        }
        Text("已完成的任务继续保留在时间轴上，并以灰色显示。", style = MaterialTheme.typography.bodySmall)
    }
}

private enum class ScheduleType(val label: String) {
    COURSE("课程"),
    LEARNING("学习／目标"),
    EXERCISE("锻炼"),
    ENTERTAINMENT("娱乐"),
    REST("休息"),
    TASK("弹性任务"),
    COMPLETED("已完成")
}

@Composable private fun scheduleColor(type: ScheduleType): Color {
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

private fun Item.scheduleType(): ScheduleType = when {
    title.contains("锻炼") || title.contains("拉伸") -> ScheduleType.EXERCISE
    title.contains("游戏") || title.contains("娱乐") -> ScheduleType.ENTERTAINMENT
    title.contains("睡前") || kind == "习惯" -> ScheduleType.REST
    goalId != null -> ScheduleType.LEARNING
    else -> ScheduleType.TASK
}

@Composable private fun ScheduleTableRow(title: String, detail: String, type: ScheduleType) {
    val typeColor = scheduleColor(type)
    Card(
        colors = CardDefaults.cardColors(containerColor = typeColor.copy(alpha = 0.10f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.width(5.dp).height(48.dp).background(typeColor, MaterialTheme.shapes.small))
            Text(type.label, Modifier.width(88.dp), fontWeight = FontWeight.Bold, color = typeColor)
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(detail, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private const val TIMELINE_START_MINUTE = 6 * 60
private const val TIMELINE_END_MINUTE = 24 * 60
private val timelineHourHeight = 64.dp

private data class TimelineEvent(
    val key: String,
    val title: String,
    val detail: String,
    val weekday: Int,
    val startMinute: Int,
    val endMinute: Int,
    val type: ScheduleType,
    val item: Item? = null
)

private data class TimelineEventLayout(val event: TimelineEvent, val lane: Int, val laneCount: Int)

private fun Course.asTimelineEvent(index: Int = 0) = TimelineEvent(
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

private fun layoutTimelineEvents(events: List<TimelineEvent>): List<TimelineEventLayout> {
    val visible = events.filter { it.endMinute > TIMELINE_START_MINUTE && it.startMinute < TIMELINE_END_MINUTE }.sortedWith(compareBy<TimelineEvent> { it.startMinute }.thenByDescending { it.endMinute })
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

@Composable private fun DailyScheduleTimeline(courses: List<Course>, tasks: List<Item>, onTaskDone: (Item) -> Unit) {
    val events = courses.mapIndexed { index, course -> course.asTimelineEvent(index) } + tasks.mapNotNull { it.asTimelineEvent() }
    var selected by remember { mutableStateOf<TimelineEvent?>(null) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 10.dp)) {
            TimelineTimeAxis()
            TimelineDayLane(events, Modifier.weight(1f), showLabels = true, compactBlocks = false, onSelect = { selected = it })
        }
    }
    TimelineLegend()
    selected?.let { TimelineEventDialog(it, onDismiss = { selected = null }, onTaskDone = { item -> selected = null; onTaskDone(item) }) }
}

@Composable private fun WeeklyScheduleTimeline(courses: List<Course>, items: List<Item>, onTaskDone: (Item) -> Unit) {
    val courseEvents = courses.mapIndexed { index, course -> course.asTimelineEvent(index) }
    val taskEvents = items.filter { !it.dayOnly && it.scheduledAt?.let(::isInCurrentWeek) == true }.mapNotNull { it.asTimelineEvent() }
    var selected by remember { mutableStateOf<TimelineEvent?>(null) }
    var showCourseInfo by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("显示课程信息", fontWeight = FontWeight.SemiBold)
            Text("色块保持简洁；打开后在表格下方显示课程名称与地点。", style = MaterialTheme.typography.bodySmall)
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
                        color = if (day == todayWeekday()) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                        shape = RoundedCornerShape(10.dp)
                    ) { Text(weekdayName(day), Modifier.padding(vertical = 8.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center, fontWeight = FontWeight.SemiBold) }
                }
            }
            Row(Modifier.fillMaxWidth()) {
                TimelineTimeAxis(40.dp)
                (1..7).forEach { day ->
                    TimelineDayLane((courseEvents + taskEvents).filter { it.weekday == day }, Modifier.weight(1f), showLabels = false, compactBlocks = true, onSelect = { selected = it })
                }
            }
        }
    }
    TimelineLegend()
    if (showCourseInfo) {
        ElevatedCard {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("本周课程", fontWeight = FontWeight.Bold)
                courses.sortedWith(compareBy<Course> { it.weekday }.thenBy { it.startPeriod }).forEach { course ->
                    Text("${weekdayName(course.weekday)}  ${formatMinute(CourseGapPlanner.periodStart(course.startPeriod))}–${formatMinute(CourseGapPlanner.periodEnd(course.endPeriod))}  ${course.title} · ${course.building}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    selected?.let { TimelineEventDialog(it, onDismiss = { selected = null }, onTaskDone = { item -> selected = null; onTaskDone(item) }) }
}

@Composable private fun TimelineTimeAxis(width: androidx.compose.ui.unit.Dp = 50.dp) {
    val totalHeight = timelineHourHeight * ((TIMELINE_END_MINUTE - TIMELINE_START_MINUTE) / 60).toFloat()
    Box(Modifier.width(width).height(totalHeight)) {
        (TIMELINE_START_MINUTE / 60..TIMELINE_END_MINUTE / 60).forEach { hour ->
            Text("%02d:00".format(hour), Modifier.offset(y = timelineHourHeight * (hour - TIMELINE_START_MINUTE / 60).toFloat() - 7.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable private fun TimelineDayLane(events: List<TimelineEvent>, modifier: Modifier, showLabels: Boolean, compactBlocks: Boolean, onSelect: (TimelineEvent) -> Unit) {
    val totalHours = (TIMELINE_END_MINUTE - TIMELINE_START_MINUTE) / 60
    val totalHeight = timelineHourHeight * totalHours.toFloat()
    val layouts = layoutTimelineEvents(events)
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
    BoxWithConstraints(modifier.height(totalHeight).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f))) {
        Canvas(Modifier.matchParentSize()) {
            val step = size.height / totalHours
            for (hour in 0..totalHours) drawLine(gridColor, Offset(0f, hour * step), Offset(size.width, hour * step), strokeWidth = 1f)
        }
        layouts.forEach { layout ->
            val event = layout.event
            val eventColor = scheduleColor(event.type)
            val topMinutes = event.startMinute.coerceAtLeast(TIMELINE_START_MINUTE) - TIMELINE_START_MINUTE
            val bottomMinutes = event.endMinute.coerceAtMost(TIMELINE_END_MINUTE) - TIMELINE_START_MINUTE
            val top = timelineHourHeight * (topMinutes / 60f)
            val height = (timelineHourHeight * ((bottomMinutes - topMinutes) / 60f)).coerceAtLeast(18.dp)
            val laneWidth = maxWidth / layout.laneCount.toFloat()
            val blockWidth = if (compactBlocks) laneWidth * 0.9f else laneWidth
            val blockOffset = (laneWidth - blockWidth) / 2f
            Surface(
                modifier = Modifier.offset(x = laneWidth * layout.lane.toFloat() + blockOffset, y = top).width(blockWidth).height(height).padding(vertical = 1.dp).clickable { onSelect(event) },
                color = eventColor.copy(alpha = 0.88f),
                contentColor = Color.White,
                shape = RoundedCornerShape(7.dp),
                tonalElevation = 1.dp
            ) {
                if (showLabels) Column(Modifier.padding(horizontal = 5.dp, vertical = 3.dp)) {
                    Text(event.title, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (height >= 34.dp) Text("${formatMinute(event.startMinute)}–${formatMinute(event.endMinute)}", style = MaterialTheme.typography.labelSmall, maxLines = 1)
                } else Box(Modifier.fillMaxSize())
            }
        }
    }
}

@Composable private fun TimelineLegend() {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
        ScheduleType.entries.forEach { type -> Text("● ${type.label}", color = scheduleColor(type), style = MaterialTheme.typography.labelSmall) }
    }
}

@Composable private fun TimelineEventDialog(event: TimelineEvent, onDismiss: () -> Unit, onTaskDone: (Item) -> Unit) {
    val eventColor = scheduleColor(event.type)
    val completedColor = scheduleColor(ScheduleType.COMPLETED)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(event.title) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${weekdayName(event.weekday)}  ${formatMinute(event.startMinute)}–${formatMinute(event.endMinute)}", fontWeight = FontWeight.SemiBold)
            Text(event.type.label, color = eventColor)
            Text(event.detail)
            event.item?.takeIf { it.done }?.let { Text("已完成${it.completionLevel.takeIf { level -> level.isNotBlank() }?.let { level -> " · $level" } ?: ""}", color = completedColor, fontWeight = FontWeight.SemiBold) }
        } },
        confirmButton = { event.item?.takeIf { !it.done }?.let { item -> Button(onClick = { onTaskDone(item) }) { Text("完成") } } ?: TextButton(onClick = onDismiss) { Text("关闭") } },
        dismissButton = { if (event.item?.done == false) TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

@Composable private fun InboxItemCard(item: Item, onPickTime: (Item) -> Unit, onEdit: (Item) -> Unit, onShrink: (Item) -> Unit, onPause: (Item) -> Unit, onAbandon: (Item) -> Unit) {
    ElevatedCard { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(item.title, fontWeight = FontWeight.SemiBold)
        Text(item.detail)
        if (!item.title.startsWith("重新安排：")) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onPickTime(item) }) { Text("安排时间") }
                OutlinedButton(onClick = { onEdit(item) }) { Text("编辑") }
                TextButton(onClick = { onAbandon(item) }) { Text("删除") }
            }
            Text("安排后会从收集箱移到日程。", style = MaterialTheme.typography.bodySmall)
        } else {
            Text("这次不做也没关系。请选择下一步：", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { onPickTime(item) }) { Text("改期") }
                TextButton(onClick = { onShrink(item) }) { Text("缩短") }
                TextButton(onClick = { onPause(item) }) { Text("暂停") }
                TextButton(onClick = { onAbandon(item) }) { Text("放弃") }
            }
        }
    } }
}

@Composable private fun InboxEditDialog(item: Item, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var title by remember(item.id) { mutableStateOf(item.title) }
    var detail by remember(item.id) { mutableStateOf(item.detail.removePrefix("刚刚记录 · ")) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑收集箱项目") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("事情") }, singleLine = true)
            OutlinedTextField(value = detail, onValueChange = { detail = it }, label = { Text("备注（可选）") }, minLines = 2)
        } },
        confirmButton = { Button(enabled = title.isNotBlank(), onClick = { onSave(title.trim(), detail.trim().ifBlank { "稍后决定安排" }) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

private fun dateAt(dayOffset: Int, hour: Int): Long {
    val calendar = java.util.Calendar.getInstance()
    calendar.add(java.util.Calendar.DAY_OF_YEAR, dayOffset)
    calendar.set(java.util.Calendar.HOUR_OF_DAY, hour)
    calendar.set(java.util.Calendar.MINUTE, 0)
    calendar.set(java.util.Calendar.SECOND, 0)
    calendar.set(java.util.Calendar.MILLISECOND, 0)
    return calendar.timeInMillis
}

@Composable private fun RescheduleTimeDialog(item: Item, onDismiss: () -> Unit, onSave: (Long, Int, String) -> Unit) {
    val context = LocalContext.current
    var selected by remember { mutableStateOf(1) }
    var customTime by remember { mutableStateOf<Long?>(null) }
    var duration by remember { mutableIntStateOf(item.durationMinutes.coerceIn(15, 180)) }
    val options = listOf(
        Triple("明早 9:00", dateAt(1, 9), "明早 9:00"),
        Triple("明晚 18:00", dateAt(1, 18), "明晚 18:00"),
        Triple("后天 18:00", dateAt(2, 18), "后天 18:00")
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("什么时候再提醒？") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(item.title.removePrefix("重新安排："))
            options.forEachIndexed { index, option -> FilterChip(selected = selected == index && customTime == null, onClick = { selected = index; customTime = null }, label = { Text(option.first) }) }
            TextButton(onClick = {
                val calendar = java.util.Calendar.getInstance()
                DatePickerDialog(context, { _, year, month, day ->
                    TimePickerDialog(context, { _, hour, minute ->
                        val chosen = java.util.Calendar.getInstance()
                        chosen.set(year, month, day, hour, minute, 0)
                        chosen.set(java.util.Calendar.MILLISECOND, 0)
                        customTime = chosen.timeInMillis
                    }, calendar.get(java.util.Calendar.HOUR_OF_DAY), calendar.get(java.util.Calendar.MINUTE), true).show()
                }, calendar.get(java.util.Calendar.YEAR), calendar.get(java.util.Calendar.MONTH), calendar.get(java.util.Calendar.DAY_OF_MONTH)).show()
            }) { Text(customTime?.let { "已选：${formatDateTime(it)}" } ?: "自选日期与时间") }
            Text("预计用时", fontWeight = FontWeight.SemiBold)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(15, 30, 60, 90).forEach { minutes -> FilterChip(selected = duration == minutes, onClick = { duration = minutes }, label = { Text("${minutes}分钟") }) }
            }
        } },
        confirmButton = { Button(onClick = { customTime?.let { onSave(it, duration, "${formatDateTime(it)} · ${duration}分钟") } ?: onSave(options[selected].second, duration, "${options[selected].third} · ${duration}分钟") }) { Text("确认安排") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

private fun formatDateTime(time: Long): String = java.text.SimpleDateFormat("M月d日 HH:mm", java.util.Locale.CHINA).format(java.util.Date(time))
private fun formatTime(time: Long): String = java.text.SimpleDateFormat("HH:mm", java.util.Locale.CHINA).format(java.util.Date(time))
private fun formatActivityRemaining(milliseconds: Long): String {
    val totalSeconds = (milliseconds.coerceAtLeast(0) / 1_000L).toInt()
    val hours = totalSeconds / 3_600
    val minutes = (totalSeconds % 3_600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%02d:%02d".format(minutes, seconds)
}
private fun nextActivityCommitment(items: List<Item>, courses: List<Course>, now: Long = System.currentTimeMillis()): ActivityCommitment? {
    val taskCommitments = items.mapNotNull { item -> item.scheduledAt?.takeIf { !item.done && it > now }?.let { ActivityCommitment(item.title, it) } }
    val courseCommitments = courses.filter { !it.needsConfirmation && it.weekday == todayWeekday() }.mapNotNull { course ->
        val startsAt = todayAtMinute(CourseGapPlanner.periodStart(course.startPeriod))
        startsAt.takeIf { it > now }?.let { ActivityCommitment("${course.title}（${course.building}）", it) }
    }
    return (taskCommitments + courseCommitments).minByOrNull(ActivityCommitment::startsAt)
}
private fun todayAtMinute(minute: Int): Long = java.util.Calendar.getInstance().apply {
    set(java.util.Calendar.HOUR_OF_DAY, minute / 60)
    set(java.util.Calendar.MINUTE, minute % 60)
    set(java.util.Calendar.SECOND, 0)
    set(java.util.Calendar.MILLISECOND, 0)
}.timeInMillis
private fun formatMinute(minute: Int): String = "%02d:%02d".format(minute / 60, minute % 60)
private fun todayWeekday(): Int = when (java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_WEEK)) {
    java.util.Calendar.SUNDAY -> 7
    else -> java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_WEEK) - 1
}
private fun todayWeekday(time: Long): Int {
    val calendar = java.util.Calendar.getInstance().apply { timeInMillis = time }
    return when (calendar.get(java.util.Calendar.DAY_OF_WEEK)) {
        java.util.Calendar.SUNDAY -> 7
        else -> calendar.get(java.util.Calendar.DAY_OF_WEEK) - 1
    }
}
private fun minuteOfDay(time: Long): Int {
    val calendar = java.util.Calendar.getInstance().apply { timeInMillis = time }
    return calendar.get(java.util.Calendar.HOUR_OF_DAY) * 60 + calendar.get(java.util.Calendar.MINUTE)
}
private fun periodForMinute(minute: Int): Int = (1..13).lastOrNull { CourseGapPlanner.periodStart(it) <= minute } ?: 1
private fun isInCurrentWeek(time: Long): Boolean {
    val weekStart = GoalPlanner.currentWeekKey()
    val weekEnd = weekStart + 7 * 24 * 60 * 60_000L
    return time >= weekStart && time < weekEnd
}
private fun isToday(time: Long): Boolean {
    val target = java.util.Calendar.getInstance().apply { timeInMillis = time }
    val today = java.util.Calendar.getInstance()
    return target.get(java.util.Calendar.YEAR) == today.get(java.util.Calendar.YEAR) && target.get(java.util.Calendar.DAY_OF_YEAR) == today.get(java.util.Calendar.DAY_OF_YEAR)
}

private enum class PlanPage(val title: String) {
    COURSES("课程"), GAPS("空挡建议"), GOALS("目标与执行"), REVIEW("本周回顾"), PAUSED("暂停项目")
}

@Composable private fun PlansScreen(modifier: Modifier, items: List<Item>, courses: List<Course>, profile: CommuteProfile, page: PlanPage?, onPageChange: (PlanPage?) -> Unit, onResume: (Item) -> Unit, onConfirmCourse: (Course) -> Unit, onIgnoreCourse: (Course) -> Unit, onAddCourse: () -> Unit, onEditCourse: (Course) -> Unit, goals: List<Goal>, onAddGoal: () -> Unit, onScheduleGoal: (Goal, GoalSuggestion) -> Unit, resources: List<LearningResource>, onAddResource: () -> Unit, onSelectResource: (LearningResource) -> Unit, feedback: List<TaskFeedback>) {
    val awaitingCourses = courses.filter { it.needsConfirmation }
    val confirmedCourses = courses.filter { !it.needsConfirmation }
    val gaps = CourseGapPlanner.gaps(confirmedCourses, profile)
    val paused = items.filter { it.kind == "暂停" }

    Box(modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = page == null,
            enter = slideInHorizontally(animationSpec = tween(260), initialOffsetX = { -it / 4 }) + fadeIn(tween(180)),
            exit = slideOutHorizontally(animationSpec = tween(220), targetOffsetX = { -it / 4 }) + fadeOut(tween(150))
        ) {
        PlanHubScreen(
            modifier = Modifier.fillMaxSize(),
            entries = listOf(
                PlanPage.COURSES to "${confirmedCourses.size} 门已确认 · ${awaitingCourses.size} 门待确认",
                PlanPage.GAPS to if (gaps.isEmpty()) "暂无可用空挡" else "${gaps.size} 段可用空挡",
                PlanPage.GOALS to if (goals.isEmpty()) "尚未创建目标 · ${resources.size} 项教程资料" else "${goals.size} 个目标 · ${resources.size} 项教程资料",
                PlanPage.REVIEW to if (goals.isEmpty()) "有目标后生成建议" else "${goals.size} 项低压力建议",
                PlanPage.PAUSED to if (paused.isEmpty()) "暂无" else "${paused.size} 项"
            ),
            onOpen = { onPageChange(it) },
            onAddGoal = onAddGoal
        )
        }
        AnimatedVisibility(
            visible = page != null,
            enter = slideInHorizontally(animationSpec = tween(280), initialOffsetX = { it / 3 }) + fadeIn(tween(190)),
            exit = slideOutHorizontally(animationSpec = tween(230), targetOffsetX = { it / 3 }) + fadeOut(tween(150))
        ) {
            val currentPage = page
            if (currentPage != null) {
                PlanSubpageFrame(Modifier.fillMaxSize(), currentPage.title) {
                    when (currentPage) {
            PlanPage.COURSES -> {
                TextButton(onClick = onAddCourse) { Text("＋ 手动新增课程") }
                if (awaitingCourses.isNotEmpty()) {
                    Text("待确认课程", fontWeight = FontWeight.Bold)
                    awaitingCourses.forEach { course ->
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f))) {
                            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("${weekdayName(course.weekday)} · ${course.title}", fontWeight = FontWeight.SemiBold)
                                Text("第 ${course.startPeriod}–${course.endPeriod} 节 · ${course.building}", style = MaterialTheme.typography.bodySmall)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(onClick = { onConfirmCourse(course) }) { Text("确认") }
                                    TextButton(onClick = { onIgnoreCourse(course) }) { Text("忽略") }
                                }
                            }
                        }
                    }
                } else Text("没有待确认课程。", style = MaterialTheme.typography.bodySmall)
                HorizontalDivider()
                Text("已确认课程", fontWeight = FontWeight.Bold)
                if (confirmedCourses.isEmpty()) Text("确认课程后，它们会用于周日程和空挡计算。", style = MaterialTheme.typography.bodySmall)
                confirmedCourses.sortedWith(compareBy<Course> { it.weekday }.thenBy { it.startPeriod }).forEach { course ->
                    ElevatedCard {
                        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("${weekdayName(course.weekday)} · ${course.title}", fontWeight = FontWeight.SemiBold)
                                Text("第 ${course.startPeriod}–${course.endPeriod} 节 · ${course.building}", style = MaterialTheme.typography.bodySmall)
                            }
                            TextButton(onClick = { onEditCourse(course) }) { Text("编辑") }
                        }
                    }
                }
            }
            PlanPage.GAPS -> {
                Text("根据已确认课程、校内路程与缓冲时间计算，不与课程列表混放。", style = MaterialTheme.typography.bodySmall)
                if (gaps.isEmpty()) Text(if (confirmedCourses.isEmpty()) "先确认课程后再计算空挡。" else "目前没有可显示的同日课程间空挡。")
                gaps.forEach { gap ->
                    ElevatedCard {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text("${weekdayName(gap.from.weekday)}：${gap.from.title} → ${gap.to.title}", fontWeight = FontWeight.SemiBold)
                            Text("路程约 ${gap.travelMinutes} 分钟")
                            Text(if (gap.minutesFree >= 15) "可用约 ${gap.minutesFree} 分钟，可用于弹性安排。" else "仅够通行与缓冲，暂不建议安排任务。", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            PlanPage.GOALS -> {
                Text("教程资料", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("资料作为目标的执行依据，不再单独占一个计划条目。", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onAddResource) { Text("＋ 收集教程／链接") }
                if (resources.isEmpty()) Text("尚未收集教程。", style = MaterialTheme.typography.bodySmall)
                resources.forEach { resource ->
                    ElevatedCard {
                        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) { Text(resource.title, fontWeight = FontWeight.SemiBold); Text(resource.url, style = MaterialTheme.typography.bodySmall) }
                            if (resource.selected) Text("当前标准", color = MaterialTheme.colorScheme.primary) else TextButton(onClick = { onSelectResource(resource) }) { Text("选择") }
                        }
                    }
                }
                HorizontalDivider()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("目标与执行", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    TextButton(onClick = onAddGoal) { Text("＋ 新增目标") }
                }
                if (goals.isEmpty()) Text("从预期结果、每周次数和单次时长开始。")
                goals.forEach { goal ->
                    val suggestions = GoalPlanner.suggestions(goal, courses, profile)
                    ElevatedCard { Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(goal.title, fontWeight = FontWeight.SemiBold)
                        val completed = GoalPlanner.completedThisWeek(goal)
                        val pending = items.count { it.goalId == goal.id && it.kind == "任务" && !it.done }
                        val remaining = (goal.weeklyTarget - completed - pending).coerceAtLeast(0)
                        if (goal.desiredOutcome.isNotBlank()) Text("预期结果：${goal.desiredOutcome}")
                        Text("本周 $completed / ${goal.weeklyTarget} 次 · 已安排 $pending · 待安排 $remaining")
                        Text("每次 ${goal.durationMinutes} 分钟 · ${goal.metricType}：${goal.metricTarget.ifBlank { "完成本次" }}", style = MaterialTheme.typography.bodySmall)
                        if (goal.minimumVersion.isNotBlank()) Text("最低版本：${goal.minimumVersion}", style = MaterialTheme.typography.bodySmall)
                        if (goal.resourceTitle.isNotBlank()) Text("依据：${goal.resourceTitle}${goal.resourceUnit.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""}", style = MaterialTheme.typography.bodySmall)
                        feedback.filter { it.goalId == goal.id && it.barrier != "无" }.groupingBy { it.barrier }.eachCount().maxByOrNull { it.value }?.let { (barrier, count) -> Text("最近常见阻碍：$barrier（$count 次）", style = MaterialTheme.typography.bodySmall) }
                        if (completed >= goal.weeklyTarget) Text("本周目标已达成。", color = MaterialTheme.colorScheme.primary)
                        else if (remaining == 0) Text("剩余次数均已安排，可在日程中逐次完成或改期。")
                        else suggestions.firstOrNull { suggestion -> items.none { item -> item.goalId == goal.id && !item.done && item.scheduledAt?.let { todayWeekday(it) == suggestion.weekday && minuteOfDay(it) == suggestion.startMinute } == true } }?.let { suggestion ->
                            Text("建议：${weekdayName(suggestion.weekday)} ${GoalPlanner.displayTime(suggestion.startMinute)}，可用 ${suggestion.freeMinutes} 分钟")
                            Button(onClick = { onScheduleGoal(goal, suggestion) }) { Text("安排第 ${completed + pending + 1} / ${goal.weeklyTarget} 次") }
                        } ?: Text("暂未找到足够连续的空档。")
                    } }
                }
            }
            PlanPage.REVIEW -> {
                if (goals.isEmpty()) Text("创建目标并积累完成记录后，这里会给出调整建议。", style = MaterialTheme.typography.bodySmall)
                goals.forEach { goal ->
                    ElevatedCard { Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(goal.title, fontWeight = FontWeight.SemiBold)
                        Text(GoalPlanner.weeklyAdvice(goal, feedback.filter { it.createdAt >= GoalPlanner.currentWeekKey() }))
                    } }
                }
            }
            PlanPage.PAUSED -> {
                if (paused.isEmpty()) Text("暂停的任务会集中放在这里，不占用日程。", style = MaterialTheme.typography.bodySmall)
                paused.forEach { item ->
                    ElevatedCard {
                        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) { Text(item.title.removePrefix("重新安排："), fontWeight = FontWeight.SemiBold); Text(item.detail, style = MaterialTheme.typography.bodySmall) }
                            TextButton(onClick = { onResume(item) }) { Text("恢复") }
                        }
                    }
                }
            }
                    }
                }
            }
        }
    }
}

@Composable private fun PlanHubScreen(modifier: Modifier, entries: List<Pair<PlanPage, String>>, onOpen: (PlanPage) -> Unit, onAddGoal: () -> Unit) {
    Column(modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("计划", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
        Text("选择一个模块进入；滚动只发生在各副页面内。", style = MaterialTheme.typography.bodyMedium)
        ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text("从结果开始", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("填写预期结果、每周次数与单次时长。", style = MaterialTheme.typography.bodySmall)
                }
                Button(onClick = onAddGoal) { Text("新增目标") }
            }
        }
        entries.forEach { (page, summary) -> PlanHubItem(page.title, summary) { onOpen(page) } }
    }
}

@Composable private fun PlanHubItem(title: String, summary: String, onClick: () -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text("›", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable private fun PlanSubpageFrame(modifier: Modifier, title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxSize().padding(20.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(top = 12.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

private fun weekdayName(day: Int) = listOf("", "周一", "周二", "周三", "周四", "周五", "周六", "周日")[day]

@Composable private fun CourseEditorDialog(existing: Course?, onDismiss: () -> Unit, onSave: (Course) -> Unit) {
    var title by remember { mutableStateOf(existing?.title ?: "") }
    var weekday by remember { mutableIntStateOf(existing?.weekday ?: 1) }
    var startPeriod by remember { mutableStateOf(existing?.startPeriod?.toString() ?: "1") }
    var endPeriod by remember { mutableStateOf(existing?.endPeriod?.toString() ?: "1") }
    var place by remember { mutableStateOf(ZijingangTravel.places.firstOrNull { it.name == existing?.building } ?: ZijingangTravel.places.first()) }
    val parsedStart = startPeriod.toIntOrNull()
    val parsedEnd = endPeriod.toIntOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "新增课程" else "编辑课程") },
        text = { Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("课程名称") }, singleLine = true)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { (1..5).forEach { day -> FilterChip(selected = weekday == day, onClick = { weekday = day }, label = { Text(weekdayName(day)) }) } }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(modifier = Modifier.weight(1f), value = startPeriod, onValueChange = { startPeriod = it.filter(Char::isDigit) }, label = { Text("开始节次") }, singleLine = true)
                OutlinedTextField(modifier = Modifier.weight(1f), value = endPeriod, onValueChange = { endPeriod = it.filter(Char::isDigit) }, label = { Text("结束节次") }, singleLine = true)
            }
            Text("教学楼")
            ZijingangTravel.places.chunked(3).forEach { row -> Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { row.forEach { candidate -> FilterChip(selected = place == candidate, onClick = { place = candidate }, label = { Text(candidate.name.removeSuffix("教学楼")) }) } } }
        } },
        confirmButton = { Button(enabled = title.isNotBlank() && parsedStart != null && parsedEnd != null && parsedStart in 1..13 && parsedEnd in parsedStart..13, onClick = { onSave(Course(title, weekday, parsedStart ?: 1, parsedEnd ?: 1, place.name, place.zone, false)) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable private fun GoalEditorDialog(selectedResource: LearningResource?, onDismiss: () -> Unit, onSave: (Goal) -> Unit) {
    var title by remember { mutableStateOf("") }
    var weekly by remember { mutableStateOf("3") }
    var duration by remember { mutableStateOf("30") }
    var metricType by remember { mutableStateOf("时长") }
    var metricTarget by remember { mutableStateOf("30 分钟") }
    var desiredOutcome by remember { mutableStateOf("") }
    var resourceUnit by remember { mutableStateOf("") }
    val weeklyNumber = weekly.toIntOrNull()
    val durationNumber = duration.toIntOrNull()
    val suggestedMinimum = GoalPlanner.suggestedMinimum(metricType, metricTarget, durationNumber ?: 30)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新增目标") },
        text = { Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("先描述你希望得到的结果；详细计划可以稍后由应用根据空档生成。")
            OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("目标名称") }, singleLine = true)
            OutlinedTextField(value = desiredOutcome, onValueChange = { desiredOutcome = it }, label = { Text("预期结果（例如：能稳定完成每周锻炼）") }, minLines = 2)
            OutlinedTextField(value = weekly, onValueChange = { weekly = it.filter(Char::isDigit) }, label = { Text("每周次数") }, singleLine = true)
            OutlinedTextField(value = duration, onValueChange = { duration = it.filter(Char::isDigit) }, label = { Text("预计占用分钟") }, singleLine = true)
            Text("完成标准")
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) { listOf("时长", "次数", "成果").forEach { type -> FilterChip(selected = metricType == type, onClick = { metricType = type }, label = { Text(type) }) } }
            OutlinedTextField(value = metricTarget, onValueChange = { metricTarget = it }, label = { Text("例如：20 道题／读完一节／30 分钟") }, singleLine = true)
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) { Column(Modifier.padding(12.dp)) {
                Text("建议最低版本", fontWeight = FontWeight.SemiBold)
                Text(suggestedMinimum)
                Text("这是应用按目标类型与预计时长给出的保守起点；之后会结合教程和你的反馈调整。", style = MaterialTheme.typography.bodySmall)
            } }
            selectedResource?.let { resource ->
                Text("当前教程：${resource.title}", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value = resourceUnit, onValueChange = { resourceUnit = it }, label = { Text("教程章节／练习（可选）") }, singleLine = true)
            }
        } },
        confirmButton = { Button(enabled = title.isNotBlank() && desiredOutcome.isNotBlank() && metricTarget.isNotBlank() && weeklyNumber != null && durationNumber != null && weeklyNumber in 1..7 && durationNumber in 5..240, onClick = { onSave(Goal(title = title, weeklyTarget = weeklyNumber ?: 1, durationMinutes = durationNumber ?: 5, metricType = metricType, metricTarget = metricTarget, minimumVersion = suggestedMinimum, resourceTitle = selectedResource?.title ?: "", resourceUnit = resourceUnit, desiredOutcome = desiredOutcome)) }) { Text("创建") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable private fun ResourceEditorDialog(onDismiss: () -> Unit, onSave: (LearningResource) -> Unit) {
    var title by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("收集教程") }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("先收集，再决定哪一份作为标准。")
        OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("教程名称") }, singleLine = true)
        OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("链接（可选）") }, singleLine = true)
    } }, confirmButton = { Button(enabled = title.isNotBlank(), onClick = { onSave(LearningResource(title = title, url = url)) }) { Text("保存") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable private fun CompletionDialog(item: Item, goal: Goal?, onDismiss: () -> Unit, onComplete: (String) -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("如何完成了这项任务？") }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(item.title)
        Text("完整标准：${goal?.metricTarget ?: "完成本次"}")
        goal?.minimumVersion?.takeIf { it.isNotBlank() }?.let { Text("最低版本：$it") }
    } }, confirmButton = { Button(onClick = { onComplete("完整完成") }) { Text("完整完成") } }, dismissButton = { Row { goal?.minimumVersion?.takeIf { it.isNotBlank() }?.let { TextButton(onClick = { onComplete("最低版本") }) { Text("完成最低版本") } }; TextButton(onClick = onDismiss) { Text("取消") } } })
}

@Composable private fun FeedbackDialog(level: String, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var difficulty by remember { mutableStateOf("正常") }
    var barrier by remember { mutableStateOf("无") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("用几秒记录一下？") }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("$level。反馈用于调整下次安排，不用于评判。")
        Text("难度")
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { listOf("轻松", "正常", "吃力").forEach { value -> FilterChip(selected = difficulty == value, onClick = { difficulty = value }, label = { Text(value) }) } }
        Text("主要阻碍")
        listOf("无", "精力不足", "时间不够", "地点不合适", "被娱乐打断", "方法不清楚").chunked(3).forEach { row -> Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { row.forEach { value -> FilterChip(selected = barrier == value, onClick = { barrier = value }, label = { Text(value) }) } } }
    } }, confirmButton = { Button(onClick = { onSave(difficulty, barrier) }) { Text("保存反馈") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("跳过") } })
}

@Composable private fun ImprovementDialog(onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("记录改进想法") }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("例如：希望睡前模式在连续延期后自动提前减速提醒。")
        OutlinedTextField(value = text, onValueChange = { text = it }, placeholder = { Text("想深化、修复或新增什么？") }, minLines = 3)
    } }, confirmButton = { Button(enabled = text.isNotBlank(), onClick = { onSave(text.trim()) }) { Text("保存") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable private fun CampusPlacesScreen(modifier: Modifier, profile: CommuteProfile) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("紫金港地点", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
        Text("只按教学楼与区域估计，不要求精确到教室。初始数值会在你实际使用后校正。")
        val sample = ZijingangTravel.estimateMinutes(CampusZone.WEST_TEACHING, CampusZone.LIBRARY, profile)
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) { Column(Modifier.padding(16.dp)) {
            Text("示例：西教学区 → 图书馆", fontWeight = FontWeight.Bold)
            Text("按${profile.campusMode}估计约 $sample 分钟（已含楼内缓冲）。")
        } }
        ZijingangTravel.places.groupBy { it.kind }.forEach { (kind, places) ->
            Text(kind, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(places.joinToString(" · ") { it.name })
        }
    }
}

@Composable private fun SettingsScreen(modifier: Modifier, themeOption: FocusFlowThemeOption, commuteProfile: CommuteProfile, improvementNotes: List<ImprovementNote>, roadmapSelections: Set<String>, activitySettings: ActivityReminderSettings, onThemeChange: (FocusFlowThemeOption) -> Unit, onCommuteChange: (CommuteProfile) -> Unit, onActivitySettingsChange: (ActivityReminderSettings) -> Unit, onAddImprovement: () -> Unit, onToggleRoadmap: (RoadmapFeature) -> Unit) {
    val context = LocalContext.current
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("设置", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
        Text("外观", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("选择后立即应用到页面、导航、卡片、控件与日程色块。", style = MaterialTheme.typography.bodySmall)
        FocusFlowThemeOption.entries.forEach { option ->
            val preview = focusFlowThemeSpec(option)
            Card(
                modifier = Modifier.fillMaxWidth().clickable { onThemeChange(option) },
                colors = CardDefaults.cardColors(
                    containerColor = if (themeOption == option) preview.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f)
                )
            ) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        listOf(preview.colorScheme.primary, preview.colorScheme.secondary, preview.schedulePalette.course).forEach { color ->
                            Box(Modifier.size(18.dp).clip(RoundedCornerShape(9.dp)).background(color))
                        }
                    }
                    Column(Modifier.weight(1f)) {
                        Text(option.label, fontWeight = FontWeight.SemiBold)
                        Text(option.description, style = MaterialTheme.typography.bodySmall)
                    }
                    Text(if (themeOption == option) "已选择" else "选择", color = if (themeOption == option) preview.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        HorizontalDivider()
        Text("活动提醒", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        SettingSwitch("活动提醒", "关闭后仍会保留活动记录和手动转场", activitySettings.notificationsEnabled) { onActivitySettingsChange(activitySettings.copy(notificationsEnabled = it)) }
        SettingSwitch("明确的到点提醒", "到达约定时间时使用更醒目的提醒", activitySettings.strongerEndReminder) { onActivitySettingsChange(activitySettings.copy(strongerEndReminder = it)) }
        Text("提前预告：${activitySettings.previewMinutes} 分钟")
        Slider(
            value = activitySettings.previewMinutes.toFloat(),
            onValueChange = { onActivitySettingsChange(activitySettings.copy(previewMinutes = (it / 5).toInt() * 5)) },
            valueRange = 0f..30f,
            steps = 5
        )
        Text("连续延长提示上限：${activitySettings.maxExtensions} 次")
        Slider(
            value = activitySettings.maxExtensions.toFloat(),
            onValueChange = { onActivitySettingsChange(activitySettings.copy(maxExtensions = it.toInt())) },
            valueRange = 0f..6f,
            steps = 5
        )
        Text("如果 ColorOS 延迟到点提醒，可在系统的“闹钟和提醒”及电池设置中允许 FocusFlow；未授权精确提醒时仍会自动使用普通后台提醒。", style = MaterialTheme.typography.bodySmall)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val exactAllowed = context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
            OutlinedButton(onClick = {
                context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")))
            }) { Text(if (exactAllowed) "管理精确提醒权限" else "允许精确提醒") }
        }
        HorizontalDivider()
        Text("通勤与地点", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("地点不再单独占一个页面；它只在安排课程空档时用于估计去图书馆、操场或下一栋教学楼是否来得及。", style = MaterialTheme.typography.bodySmall)
        SettingSwitch("为通勤预留时间", "只保存大致时长，不读取定位", commuteProfile.enabled) { onCommuteChange(commuteProfile.copy(enabled = it)) }
        if (commuteProfile.enabled) {
            Text("单程约 ${commuteProfile.oneWayMinutes} 分钟")
            Slider(value = commuteProfile.oneWayMinutes.toFloat(), onValueChange = { onCommuteChange(commuteProfile.copy(oneWayMinutes = (it / 5).toInt() * 5)) }, valueRange = 5f..120f, steps = 22)
            Text("校内主要方式")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("步行", "自行车", "电动车").forEach { mode -> FilterChip(selected = commuteProfile.campusMode == mode, onClick = { onCommuteChange(commuteProfile.copy(campusMode = mode)) }, label = { Text(mode) }) }
            }
            Text("教学楼进出与找教室缓冲：${commuteProfile.buildingBufferMinutes} 分钟")
            Slider(value = commuteProfile.buildingBufferMinutes.toFloat(), onValueChange = { onCommuteChange(commuteProfile.copy(buildingBufferMinutes = it.toInt())) }, valueRange = 1f..10f, steps = 8)
            if (commuteProfile.campusMode == "电动车") {
                Text("电动车电量")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("充足", "一般", "偏低", "未知").forEach { level -> FilterChip(selected = commuteProfile.eBikeBattery == level, onClick = { onCommuteChange(commuteProfile.copy(eBikeBattery = level)) }, label = { Text(level) }) }
                }
                if (commuteProfile.eBikeBattery == "偏低") Text("后续排程会避免安排需要骑车的远距离连续行程，并建议在合适时段充电。", style = MaterialTheme.typography.bodySmall)
            }
            Text("这些都是初始估计；以后可按实际体验随时改，不需要一开始就准确。", style = MaterialTheme.typography.bodySmall)
        } else Text("开始上学后再设置即可。软件不会默认追踪你的位置。", style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        Text("改进清单", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("记录希望深化或修改的功能；之后把条目发给我即可继续开发。", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = onAddImprovement) { Text("＋ 记录改进想法") }
        improvementNotes.takeLast(3).reversed().forEach { note -> ElevatedCard { Text(note.text, Modifier.padding(10.dp)) } }
        HorizontalDivider()
        Text("版本路线图", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("以下是当前计划的候选功能；勾选你希望优先保留的项，路线图可随时调整。", style = MaterialTheme.typography.bodySmall)
        RoadmapCatalog.features.groupBy { it.version }.forEach { (version, features) ->
            Text(version, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            features.forEach { feature -> FilterChip(selected = feature.id in roadmapSelections, onClick = { onToggleRoadmap(feature) }, label = { Text(feature.title) }) }
        }
        Text("更多权限会在真正需要时单独请求。")
    }
}

@Composable private fun SettingSwitch(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) { Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.SemiBold); Text(detail) }; Switch(checked = checked, onCheckedChange = onChange) } }

@Composable private fun QuickCaptureDialog(onDismiss: () -> Unit, onSave: (String, Boolean) -> Unit) { var text by remember { mutableStateOf("") }; var tomorrow by remember { mutableStateOf(false) }; AlertDialog(onDismissRequest = onDismiss, title = { Text("快速记录") }, text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { Text("先保存想法，安排可以以后再说。"); OutlinedTextField(value = text, onValueChange = { text = it }, placeholder = { Text("例如：购买教材") }, singleLine = false); FilterChip(selected = tomorrow, onClick = { tomorrow = !tomorrow }, label = { Text("明天要做（不定时间）") }); if (tomorrow) Text("明天上午会温和提醒；你再决定具体什么时候做。", style = MaterialTheme.typography.bodySmall) } }, confirmButton = { Button(enabled = text.isNotBlank(), onClick = { onSave(text.trim(), tomorrow) }) { Text("保存") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }) }

@Composable private fun ActivityDialog(
    suggestedNextStep: String,
    onDismiss: () -> Unit,
    onStart: (category: String, name: String, endsAt: Long, nextStep: String) -> Unit
) {
    val context = LocalContext.current
    var category by remember { mutableStateOf("游戏／娱乐") }
    var customName by remember { mutableStateOf("") }
    var timeMode by remember { mutableStateOf("时长") }
    var minutes by remember { mutableStateOf("60") }
    var untilAt by remember { mutableLongStateOf(System.currentTimeMillis() + 60 * 60_000L) }
    var nextStep by remember { mutableStateOf(suggestedNextStep) }
    val activityName = if (category == "自定义") customName.trim() else category
    val calculatedEnd = if (timeMode == "时长") System.currentTimeMillis() + (minutes.toIntOrNull()?.coerceIn(1, 600) ?: 60) * 60_000L else untilAt
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("开始活动") },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("先约定什么时候收尾，以及收尾后要去哪里。")
                listOf("游戏／娱乐", "学习", "休息", "通勤", "自定义").forEach { label ->
                    FilterChip(selected = category == label, onClick = { category = label }, label = { Text(label) })
                }
                if (category == "自定义") OutlinedTextField(value = customName, onValueChange = { customName = it }, label = { Text("活动名称") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = timeMode == "时长", onClick = { timeMode = "时长" }, label = { Text("预计时长") })
                    FilterChip(selected = timeMode == "截至", onClick = { timeMode = "截至" }, label = { Text("直到时间") })
                }
                if (timeMode == "时长") {
                    OutlinedTextField(value = minutes, onValueChange = { minutes = it.filter(Char::isDigit).take(3) }, label = { Text("分钟") }, singleLine = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(15, 30, 60).forEach { value -> FilterChip(selected = minutes == value.toString(), onClick = { minutes = value.toString() }, label = { Text("$value 分") }) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(90, 120).forEach { value -> FilterChip(selected = minutes == value.toString(), onClick = { minutes = value.toString() }, label = { Text("$value 分") }) }
                    }
                } else {
                    OutlinedButton(onClick = {
                        val calendar = java.util.Calendar.getInstance().apply { timeInMillis = untilAt }
                        TimePickerDialog(context, { _, hour, minute ->
                            val chosen = java.util.Calendar.getInstance().apply {
                                set(java.util.Calendar.HOUR_OF_DAY, hour)
                                set(java.util.Calendar.MINUTE, minute)
                                set(java.util.Calendar.SECOND, 0)
                                set(java.util.Calendar.MILLISECOND, 0)
                                if (timeInMillis <= System.currentTimeMillis()) add(java.util.Calendar.DAY_OF_YEAR, 1)
                            }
                            untilAt = chosen.timeInMillis
                        }, calendar.get(java.util.Calendar.HOUR_OF_DAY), calendar.get(java.util.Calendar.MINUTE), true).show()
                    }) { Text("选择结束时间：${formatDateTime(untilAt)}") }
                }
                OutlinedTextField(value = nextStep, onValueChange = { nextStep = it }, label = { Text("结束后的下一步（可选）") }, placeholder = { Text("例如：洗漱，或开始复习") })
                if (suggestedNextStep.isNotBlank() && nextStep == suggestedNextStep) Text("已根据最近的固定安排或待办预填，可直接修改。", style = MaterialTheme.typography.labelSmall)
                Text("预计 ${formatDateTime(calculatedEnd)} 结束；到点不会自动判定失败，而是进入转场确认。", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { Button(enabled = activityName.isNotBlank() && calculatedEnd > System.currentTimeMillis(), onClick = { onStart(category, activityName, calculatedEnd, nextStep.trim()) }) { Text("开始活动") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable private fun ActivityTransitionDialog(
    session: ActivitySession,
    maxExtensions: Int,
    upcomingCommitment: ActivityCommitment?,
    onDismiss: () -> Unit,
    onFinish: (actualEndAt: Long) -> Unit,
    onStartNext: () -> Unit,
    onExtend: (minutes: Int, reason: String) -> Unit,
    onReplan: () -> Unit
) {
    var extensionMinutes by remember { mutableIntStateOf(10) }
    var reason by remember { mutableStateOf("") }
    var endTimeChoice by remember { mutableStateOf("现在") }
    val canExtend = session.extensionCount < maxExtensions
    val extensionEnd = System.currentTimeMillis() + extensionMinutes * 60_000L
    val conflict = upcomingCommitment?.takeIf { it.startsAt < extensionEnd }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (System.currentTimeMillis() >= session.endsAt) "活动时间到了" else "结束或调整活动") },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("${session.name} · 原定 ${formatTime(session.endsAt)} 结束", fontWeight = FontWeight.SemiBold)
                if (System.currentTimeMillis() > session.endsAt + 60_000L) {
                    Text("实际什么时候结束？")
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = endTimeChoice == "现在", onClick = { endTimeChoice = "现在" }, label = { Text("刚刚") })
                        FilterChip(selected = endTimeChoice == "预计", onClick = { endTimeChoice = "预计" }, label = { Text("按预计时间") })
                    }
                }
                if (session.nextStep.isNotBlank()) {
                    Text("下一步：${session.nextStep}")
                    Button(onClick = onStartNext, modifier = Modifier.fillMaxWidth()) { Text("结束并开始下一步") }
                }
                HorizontalDivider()
                Text("需要更多时间")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(10, 20, 30).forEach { value -> FilterChip(selected = extensionMinutes == value, onClick = { extensionMinutes = value }, label = { Text("$value 分钟") }) }
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("还没结束", "不想停", "临时被打断").forEach { label -> FilterChip(selected = reason == label, onClick = { reason = label }, label = { Text(label) }) }
                }
                conflict?.let { Text("延长到 ${formatTime(extensionEnd)} 会碰到 ${formatTime(it.startsAt)} 的 ${it.title}；FocusFlow 不会自动改动它。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (canExtend) OutlinedButton(onClick = { onExtend(extensionMinutes, reason) }, modifier = Modifier.fillMaxWidth()) { Text("确认延长") }
                else Text("已达到设置中的连续延长提示上限。你仍可结束后重新开始，并重新作出约定。", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onReplan, modifier = Modifier.fillMaxWidth()) { Text("现在结束，但把下一步放回收集箱") }
            }
        },
        confirmButton = { Button(onClick = { onFinish(if (endTimeChoice == "预计") session.endsAt else System.currentTimeMillis()) }) { Text("确认结束") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("继续当前活动") } }
    )
}
