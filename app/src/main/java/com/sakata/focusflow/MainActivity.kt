package com.sakata.focusflow

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

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

data class Item(
    val id: Long = System.currentTimeMillis(),
    val title: String,
    val detail: String,
    val kind: String,
    val done: Boolean = false,
    val scheduledAt: Long? = null,
    val dayOnly: Boolean = false,
    val goalId: Long? = null,
    val completionLevel: String = ""
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
    fun saveItems(updated: List<Item>) { items = updated; store.saveItems(updated) }

    MaterialTheme(colorScheme = lightColorScheme(primary = androidx.compose.ui.graphics.Color(0xFF355C7D))) {
        Scaffold(
            floatingActionButton = {
                FloatingActionButton(onClick = { addOpen = true }) { Text("＋", style = MaterialTheme.typography.headlineMedium) }
            },
            bottomBar = {
                NavigationBar {
                    listOf("现在", "收集箱", "计划", "地点", "设置").forEachIndexed { index, label ->
                        NavigationBarItem(selected = tab == index, onClick = { tab = index }, icon = { Text(if (tab == index) "●" else "○") }, label = { Text(label) })
                    }
                }
            }
        ) { padding ->
            when (tab) {
                0 -> TodayScreen(
                    Modifier.padding(padding), items,
                    onTaskDone = { item ->
                        if (item.goalId == null) saveItems(items.map { if (it.id == item.id) it.copy(done = true, completionLevel = "完成") else it }) else completionTarget = item
                    },
                    activeSession = activeSession,
                    onStartActivity = { activityOpen = true }
                )
                1 -> InboxScreen(
                    Modifier.padding(padding), items,
                    onPickTime = { item -> rescheduleTarget = item },
                    onShrink = { item -> saveItems(items.map { if (it.id == item.id) it.copy(title = item.title.removePrefix("重新安排："), kind = "任务", detail = "短版：先做 10 分钟 · 今天有空时") else it }) },
                    onPause = { item -> saveItems(items.map { if (it.id == item.id) it.copy(kind = "暂停", detail = "已暂停；随时可在计划中恢复") else it }) },
                    onAbandon = { item -> saveItems(items.filterNot { it.id == item.id }) }
                )
                2 -> PlansScreen(
                    Modifier.padding(padding), items, courses, commuteProfile,
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
                        val scheduled = Item(title = goal.title, detail = "${goal.metricType}：${goal.metricTarget.ifBlank { "本次完成" }} · ${weekdayName(suggestion.weekday)} ${GoalPlanner.displayTime(suggestion.startMinute)}", kind = "任务", scheduledAt = GoalPlanner.nextOccurrence(suggestion.weekday, suggestion.startMinute), goalId = goal.id)
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
                3 -> CampusPlacesScreen(Modifier.padding(padding), commuteProfile)
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
        rescheduleTarget?.let { item -> RescheduleTimeDialog(item, onDismiss = { rescheduleTarget = null }) { scheduledAt, label ->
            val delayed = item.copy(kind = "任务", detail = "已改期至$label；届时会再次出现", scheduledAt = scheduledAt)
            saveItems(items.map { if (it.id == item.id) delayed else it })
            ReminderScheduler.scheduleTaskReminder(context, delayed)
            rescheduleTarget = null
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
            saveItems(items.map { if (it.id == item.id) it.copy(done = true, completionLevel = level) else it })
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

@Composable private fun TodayScreen(modifier: Modifier, items: List<Item>, onTaskDone: (Item) -> Unit, activeSession: ActivitySession?, onStartActivity: () -> Unit) {
    Column(modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("现在", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
        Text("不必安排完一天。先选择下一件合适的事。", style = MaterialTheme.typography.bodyLarge)
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text(activeSession?.let { "正在：${it.name}" } ?: "当前状态未设置", fontWeight = FontWeight.Bold)
                    Text(activeSession?.let { "结束后会询问你下一步。" } ?: "告诉我你现在在做什么，提醒会更合适。")
                }
                Button(onClick = onStartActivity) { Text("开始活动") }
            }
        }
        Text("今天可以做", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        items.filter { !it.done && it.kind != "暂停" && (it.scheduledAt == null || it.scheduledAt <= System.currentTimeMillis()) }.take(4).forEach { item ->
            ElevatedCard {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) { Text(item.title, fontWeight = FontWeight.SemiBold); Text(item.detail) }
                    TextButton(onClick = { onTaskDone(item) }) { Text("完成") }
                }
            }
        }
        Text("错过不等于失败；未完成的任务会在合适时机重新出现。", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable private fun InboxScreen(modifier: Modifier, items: List<Item>, onPickTime: (Item) -> Unit, onShrink: (Item) -> Unit, onPause: (Item) -> Unit, onAbandon: (Item) -> Unit) {
    Column(modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("收集箱", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
        Text("先记下，不必现在决定。")
        items.filter { it.kind == "收集箱" }.ifEmpty { listOf(Item(title = "暂时没有新想法", detail = "想到事情时点右下角 ＋", kind = "提示")) }.forEach { item ->
            ElevatedCard { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(item.title, fontWeight = FontWeight.SemiBold); Text(item.detail)
                if (item.title.startsWith("重新安排：")) {
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
    }
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

@Composable private fun RescheduleTimeDialog(item: Item, onDismiss: () -> Unit, onSave: (Long, String) -> Unit) {
    val context = LocalContext.current
    var selected by remember { mutableStateOf(1) }
    var customTime by remember { mutableStateOf<Long?>(null) }
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
        } },
        confirmButton = { Button(onClick = { customTime?.let { onSave(it, formatDateTime(it)) } ?: onSave(options[selected].second, options[selected].third) }) { Text("确认改期") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

private fun formatDateTime(time: Long): String = java.text.SimpleDateFormat("M月d日 HH:mm", java.util.Locale.CHINA).format(java.util.Date(time))

@Composable private fun PlansScreen(modifier: Modifier, items: List<Item>, courses: List<Course>, profile: CommuteProfile, onResume: (Item) -> Unit, onConfirmCourse: (Course) -> Unit, onIgnoreCourse: (Course) -> Unit, onAddCourse: () -> Unit, onEditCourse: (Course) -> Unit, goals: List<Goal>, onAddGoal: () -> Unit, onScheduleGoal: (Goal, GoalSuggestion) -> Unit, resources: List<LearningResource>, onAddResource: () -> Unit, onSelectResource: (LearningResource) -> Unit, feedback: List<TaskFeedback>) {
    Column(modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("计划", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
        Text("长期目标会随着上学后的课表动态安排。")
        Text("课表识别预览", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        TextButton(onClick = onAddCourse) { Text("＋ 手动新增课程") }
        Text("已读取截图中教学楼清楚的课程；地点截断或不清楚的课程没有自动加入。确认后才会作为正式课表。", style = MaterialTheme.typography.bodySmall)
        courses.filter { it.needsConfirmation }.forEach { course -> ElevatedCard { Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) { Text("${weekdayName(course.weekday)} · ${course.title}", fontWeight = FontWeight.SemiBold); Text("第 ${course.startPeriod}–${course.endPeriod} 节 · ${course.building}") }
            Row { TextButton(onClick = { onConfirmCourse(course) }) { Text("确认") }; TextButton(onClick = { onIgnoreCourse(course) }) { Text("忽略") } }
        } } }
        val confirmed = courses.filter { !it.needsConfirmation }
        if (confirmed.isNotEmpty()) {
            Text("已确认课程 ${confirmed.size} 门", style = MaterialTheme.typography.bodyMedium)
            confirmed.take(3).forEach { course -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("${weekdayName(course.weekday)} ${course.title} · ${course.building}")
                TextButton(onClick = { onEditCourse(course) }) { Text("编辑") }
            } }
        }
        val gaps = CourseGapPlanner.gaps(confirmed, profile)
        if (gaps.isNotEmpty()) {
            gaps.take(3).forEach { gap -> ElevatedCard { Column(Modifier.padding(12.dp)) {
                Text("${weekdayName(gap.from.weekday)}：${gap.from.title} → ${gap.to.title}", fontWeight = FontWeight.SemiBold)
                Text("${gap.from.building} → ${gap.to.building}，预计路程 ${gap.travelMinutes} 分钟")
                Text(if (gap.minutesFree >= 15) "可用空档约 ${gap.minutesFree} 分钟" else "仅够通行与缓冲，不建议安排任务")
            } } }
        } else if (confirmed.isEmpty()) Text("确认至少两门同一天的课程后，这里会显示扣除通行时间的真实空档。", style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        Text("目标", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("教程资料", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        TextButton(onClick = onAddResource) { Text("＋ 收集教程／链接") }
        resources.forEach { resource -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text(resource.title, fontWeight = FontWeight.SemiBold); Text(resource.url, style = MaterialTheme.typography.bodySmall) }
            if (resource.selected) Text("当前标准", color = MaterialTheme.colorScheme.primary) else TextButton(onClick = { onSelectResource(resource) }) { Text("选择") }
        } }
        TextButton(onClick = onAddGoal) { Text("＋ 新增目标") }
        goals.forEach { goal ->
            val suggestions = GoalPlanner.suggestions(goal, courses, profile)
            ElevatedCard { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(goal.title, fontWeight = FontWeight.SemiBold)
                val completed = GoalPlanner.completedThisWeek(goal)
                val pending = items.any { it.goalId == goal.id && it.kind == "任务" && !it.done }
                Text("本周完整 $completed / ${goal.weeklyTarget} 次 · 最低版本 ${GoalPlanner.minimumCompletedThisWeek(goal)} 次")
                Text("完成标准：${goal.metricType} · ${goal.metricTarget.ifBlank { "完成本次" }}")
                if (goal.resourceTitle.isNotBlank()) Text("依据：${goal.resourceTitle}${goal.resourceUnit.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""}", style = MaterialTheme.typography.bodySmall)
                if (goal.minimumVersion.isNotBlank()) Text("最低版本：${goal.minimumVersion}", style = MaterialTheme.typography.bodySmall)
                feedback.filter { it.goalId == goal.id && it.barrier != "无" }.groupingBy { it.barrier }.eachCount().maxByOrNull { it.value }?.let { (barrier, count) -> Text("最近常见阻碍：$barrier（$count 次）", style = MaterialTheme.typography.bodySmall) }
                if (completed >= goal.weeklyTarget) Text("本周目标已达成。", color = MaterialTheme.colorScheme.primary)
                else if (pending) Text("已有一次待执行安排；完成、改期或跳过后再推荐下一次。")
                else suggestions.firstOrNull()?.let { suggestion ->
                    Text("建议：${weekdayName(suggestion.weekday)} ${GoalPlanner.displayTime(suggestion.startMinute)}，可用 ${suggestion.freeMinutes} 分钟")
                    Button(onClick = { onScheduleGoal(goal, suggestion) }) { Text("安排下一次") }
                } ?: Text("课表中暂未找到足够连续的空档。")
            } }
        }
        if (goals.isNotEmpty()) {
            HorizontalDivider()
            Text("本周低压力回顾", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            goals.forEach { goal -> ElevatedCard { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(goal.title, fontWeight = FontWeight.SemiBold)
                Text(GoalPlanner.weeklyAdvice(goal, feedback.filter { it.createdAt >= GoalPlanner.currentWeekKey() }))
            } } }
        }
        val paused = items.filter { it.kind == "暂停" }
        if (paused.isNotEmpty()) {
            Text("已暂停", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            paused.forEach { item -> ElevatedCard { Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text(item.title.removePrefix("重新安排："), fontWeight = FontWeight.SemiBold); Text(item.detail) }
                TextButton(onClick = { onResume(item) }) { Text("恢复") }
            } } }
        } else Text("目前没有暂停项目。锻炼、学习和睡前减速计划会显示在这里。")
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
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
    var minimumVersion by remember { mutableStateOf("") }
    var resourceUnit by remember { mutableStateOf("") }
    val weeklyNumber = weekly.toIntOrNull()
    val durationNumber = duration.toIntOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新增目标") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("预计时长用于找空档；完成标准则由你定义，不必只看时间。")
            OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("目标名称") }, singleLine = true)
            OutlinedTextField(value = weekly, onValueChange = { weekly = it.filter(Char::isDigit) }, label = { Text("每周次数") }, singleLine = true)
            OutlinedTextField(value = duration, onValueChange = { duration = it.filter(Char::isDigit) }, label = { Text("预计占用分钟") }, singleLine = true)
            Text("完成标准")
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) { listOf("时长", "次数", "成果").forEach { type -> FilterChip(selected = metricType == type, onClick = { metricType = type }, label = { Text(type) }) } }
            OutlinedTextField(value = metricTarget, onValueChange = { metricTarget = it }, label = { Text("例如：20 道题／读完一节／30 分钟") }, singleLine = true)
            OutlinedTextField(value = minimumVersion, onValueChange = { minimumVersion = it }, label = { Text("最低版本（可选，例如：5 道题）") }, singleLine = true)
            selectedResource?.let { resource ->
                Text("当前教程：${resource.title}", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value = resourceUnit, onValueChange = { resourceUnit = it }, label = { Text("教程章节／练习（可选）") }, singleLine = true)
            }
        } },
        confirmButton = { Button(enabled = title.isNotBlank() && metricTarget.isNotBlank() && weeklyNumber != null && durationNumber != null && weeklyNumber in 1..7 && durationNumber in 5..240, onClick = { onSave(Goal(title = title, weeklyTarget = weeklyNumber ?: 1, durationMinutes = durationNumber ?: 5, metricType = metricType, metricTarget = metricTarget, minimumVersion = minimumVersion, resourceTitle = selectedResource?.title ?: "", resourceUnit = resourceUnit)) }) { Text("创建") } },
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
    Column(modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
    Column(modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("设置", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
        var persistent by remember { mutableStateOf(false) }
        var preview by remember { mutableStateOf(true) }
        SettingSwitch("常驻快速记录通知", "在通知栏提供一键记录", persistent) { persistent = it }
        SettingSwitch("结束前温和预告", "活动结束前 10 分钟提醒", preview) { preview = it }
        HorizontalDivider()
        Text("上学与通勤", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
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
