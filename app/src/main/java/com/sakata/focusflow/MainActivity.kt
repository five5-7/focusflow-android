package com.sakata.focusflow

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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

data class ActivitySession(
    val id: Long = System.currentTimeMillis(),
    val name: String,
    val endsAt: Long,
    val status: String = "active"
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
    fun saveItems(updated: List<Item>) { items = updated; store.saveItems(updated) }
    fun selectTab(index: Int) {
        if (index == 2) planPage = null
        tab = index
    }
    BackHandler(enabled = tab == 2 && planPage != null) { planPage = null }

    MaterialTheme(colorScheme = lightColorScheme(
        primary = androidx.compose.ui.graphics.Color(0xFF155E75),
        onPrimary = androidx.compose.ui.graphics.Color.White,
        primaryContainer = androidx.compose.ui.graphics.Color(0xFFCFFAFE),
        onPrimaryContainer = androidx.compose.ui.graphics.Color(0xFF164E63),
        secondary = androidx.compose.ui.graphics.Color(0xFF6D28D9),
        secondaryContainer = androidx.compose.ui.graphics.Color(0xFFEDE9FE),
        background = androidx.compose.ui.graphics.Color(0xFFF8FAFC),
        surface = androidx.compose.ui.graphics.Color.White,
        surfaceVariant = androidx.compose.ui.graphics.Color(0xFFE2E8F0),
        outline = androidx.compose.ui.graphics.Color(0xFF94A3B8)
    )) {
        Scaffold(
            bottomBar = {
                NavigationBar {
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
                    onStartActivity = { activityOpen = true },
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
                else -> SettingsScreen(Modifier.padding(padding), commuteProfile, improvementNotes, roadmapSelections, onCommuteChange = { updated ->
                    commuteProfile = updated
                    store.saveCommuteProfile(updated)
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
        if (activityOpen) ActivityDialog(onDismiss = { activityOpen = false }) { name, minutes ->
            val session = ActivitySession(name = name, endsAt = System.currentTimeMillis() + minutes * 60_000L)
            store.saveSession(session)
            activeSession = session
            ReminderScheduler.scheduleActivityEnd(context, session)
            activityOpen = false
        }
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

@Composable private fun TodayScreen(
    modifier: Modifier,
    items: List<Item>,
    onTaskDone: (Item) -> Unit,
    activeSession: ActivitySession?,
    onStartActivity: () -> Unit,
    onPickTime: (Item) -> Unit,
    onEdit: (Item) -> Unit,
    onShrink: (Item) -> Unit,
    onPause: (Item) -> Unit,
    onAbandon: (Item) -> Unit
) {
    val now = System.currentTimeMillis()
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
            Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text(activeSession?.let { "正在：${it.name}" } ?: "当前状态未设置", fontWeight = FontWeight.Bold)
                    Text(activeSession?.let { "结束后会询问你下一步。" } ?: "开始活动后，提醒会按你当前状态变得更合适。")
                }
                Button(onClick = onStartActivity) { Text("开始活动") }
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

private enum class ScheduleType(val label: String, val color: androidx.compose.ui.graphics.Color) {
    COURSE("课程", androidx.compose.ui.graphics.Color(0xFF2474B5)),
    LEARNING("学习／目标", androidx.compose.ui.graphics.Color(0xFF7654A8)),
    EXERCISE("锻炼", androidx.compose.ui.graphics.Color(0xFF2F8F5B)),
    ENTERTAINMENT("娱乐", androidx.compose.ui.graphics.Color(0xFFD65B7A)),
    REST("休息", androidx.compose.ui.graphics.Color(0xFF6B7280)),
    TASK("弹性任务", androidx.compose.ui.graphics.Color(0xFFBE6A18)),
    COMPLETED("已完成", androidx.compose.ui.graphics.Color(0xFF94A3B8))
}

private fun Item.scheduleType(): ScheduleType = when {
    title.contains("锻炼") || title.contains("拉伸") -> ScheduleType.EXERCISE
    title.contains("游戏") || title.contains("娱乐") -> ScheduleType.ENTERTAINMENT
    title.contains("睡前") || kind == "习惯" -> ScheduleType.REST
    goalId != null -> ScheduleType.LEARNING
    else -> ScheduleType.TASK
}

@Composable private fun ScheduleTableRow(title: String, detail: String, type: ScheduleType) {
    Card(
        colors = CardDefaults.cardColors(containerColor = type.color.copy(alpha = 0.07f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.width(5.dp).height(48.dp).background(type.color, MaterialTheme.shapes.small))
            Text(type.label, Modifier.width(88.dp), fontWeight = FontWeight.Bold, color = type.color)
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
            val topMinutes = event.startMinute.coerceAtLeast(TIMELINE_START_MINUTE) - TIMELINE_START_MINUTE
            val bottomMinutes = event.endMinute.coerceAtMost(TIMELINE_END_MINUTE) - TIMELINE_START_MINUTE
            val top = timelineHourHeight * (topMinutes / 60f)
            val height = (timelineHourHeight * ((bottomMinutes - topMinutes) / 60f)).coerceAtLeast(18.dp)
            val laneWidth = maxWidth / layout.laneCount.toFloat()
            val blockWidth = if (compactBlocks) laneWidth * 0.9f else laneWidth
            val blockOffset = (laneWidth - blockWidth) / 2f
            Surface(
                modifier = Modifier.offset(x = laneWidth * layout.lane.toFloat() + blockOffset, y = top).width(blockWidth).height(height).padding(vertical = 1.dp).clickable { onSelect(event) },
                color = event.type.color.copy(alpha = 0.88f),
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
        ScheduleType.entries.forEach { type -> Text("● ${type.label}", color = type.color, style = MaterialTheme.typography.labelSmall) }
    }
}

@Composable private fun TimelineEventDialog(event: TimelineEvent, onDismiss: () -> Unit, onTaskDone: (Item) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(event.title) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${weekdayName(event.weekday)}  ${formatMinute(event.startMinute)}–${formatMinute(event.endMinute)}", fontWeight = FontWeight.SemiBold)
            Text(event.type.label, color = event.type.color)
            Text(event.detail)
            event.item?.takeIf { it.done }?.let { Text("已完成${it.completionLevel.takeIf { level -> level.isNotBlank() }?.let { level -> " · $level" } ?: ""}", color = ScheduleType.COMPLETED.color, fontWeight = FontWeight.SemiBold) }
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
                PlanSubpageFrame(Modifier.fillMaxSize(), currentPage.title, onBack = { onPageChange(null) }) {
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

@Composable private fun PlanSubpageFrame(modifier: Modifier, title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxSize().padding(20.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)) {
                Text("‹", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(4.dp))
                Text("返回", style = MaterialTheme.typography.titleMedium)
            }
            Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        }
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

@Composable private fun SettingsScreen(modifier: Modifier, commuteProfile: CommuteProfile, improvementNotes: List<ImprovementNote>, roadmapSelections: Set<String>, onCommuteChange: (CommuteProfile) -> Unit, onAddImprovement: () -> Unit, onToggleRoadmap: (RoadmapFeature) -> Unit) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("设置", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
        var persistent by remember { mutableStateOf(false) }
        var preview by remember { mutableStateOf(true) }
        SettingSwitch("常驻快速记录通知", "在通知栏提供一键记录", persistent) { persistent = it }
        SettingSwitch("结束前温和预告", "活动结束前 10 分钟提醒", preview) { preview = it }
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

@Composable private fun ActivityDialog(onDismiss: () -> Unit, onStart: (String, Int) -> Unit) { var selected by remember { mutableStateOf("游戏／娱乐") }; var minutes by remember { mutableStateOf("60") }; AlertDialog(onDismissRequest = onDismiss, title = { Text("开始活动") }, text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { listOf("游戏／娱乐", "学习", "休息", "其他").forEach { label -> FilterChip(selected = selected == label, onClick = { selected = label }, label = { Text(label) }) }; OutlinedTextField(value = minutes, onValueChange = { minutes = it.filter(Char::isDigit) }, label = { Text("预计分钟") }, singleLine = true); Text("结束前 10 分钟会温和提醒；到点后再决定开始下一项、延长或改期。") } }, confirmButton = { Button(onClick = { onStart(selected, minutes.toIntOrNull()?.coerceIn(1, 600) ?: 60) }) { Text("开始计时") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }) }

