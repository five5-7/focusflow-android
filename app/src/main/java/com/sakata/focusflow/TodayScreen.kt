package com.sakata.focusflow

import android.Manifest
import android.app.AlarmManager
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.pow
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable internal fun TodayScreen(
    modifier: Modifier,
    items: List<Item>,
    inboxOpen: Boolean,
    onInboxOpenChange: (Boolean) -> Unit,
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
    activeSession: ActivitySession?,
    activityHistory: List<ActivitySession>,
    nextCommitment: ActivityCommitment?,
    onStartActivity: () -> Unit,
    onStartSuggestion: (NextActionSuggestion, Boolean) -> Unit,
    onReplanSuggestion: (Item) -> Unit,
    onReviewActivity: () -> Unit,
    onPickTime: (Item) -> Unit,
    onEdit: (Item) -> Unit,
    onConvertToGoal: (Item) -> Unit,
    onAttachToPlan: (Item) -> Unit,
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
    windDownEnabled: Boolean,
    baselineProfile: BaselineProfile,
    courses: List<Course>,
    mealSkipDays: Set<String>,
    onMealPrompt: (MealType) -> Unit,
    onMealFinish: (MealType) -> Unit
) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var helpOpen by remember { mutableStateOf(false) }
    LaunchedEffect(activeSession?.id, activeSession?.endsAt) {
        while (true) {
            now = System.currentTimeMillis()
            delay(if (activeSession == null) 30_000 else 1_000)
        }
    }
    val inboxItems = items.filter { !it.done && it.kind == "收集箱" }
    val energyIsCurrent = StatusFreshnessPolicy.isCurrent(energyRecordedAt, now)
    val planningEnergy = if (energyIsCurrent) energyLevel else "正常"
    val nextSuggestion = NextActionPlanner.recommend(items, nextCommitment, planningEnergy, goals, feedback, now, courses, commuteProfile)
    val dailySummary = DailyLoopStats.summarize(items, now, taskEvents)
    // 6.9：已推荐去执行的任务不再重复出现在「需要恢复的安排」——推荐/恢复双入口去重（只影响 UI 展示）。
    val recoveryCandidates = RecoveryInsights.candidates(items, now).filter { it.item.id != nextSuggestion?.item?.id }
    val completedTodayItems = if (taskEvents.isEmpty()) {
        items.filter { it.done && it.completedAt?.let(::isToday) == true }.sortedByDescending { it.completedAt }.map {
            TaskRecorder.event(TaskEventType.TASK_COMPLETED, it.id, it.title, extra = it.completionLevel, at = it.completedAt ?: 0)
        }
    } else TaskHistory.completedOn(taskEvents, TaskHistory.dayStartOf(now))
    val completedThisWeek = items.count { it.done && it.completedAt?.let(::isInCurrentWeek) == true }
    val visibility = FeatureVisibilityPolicy.daily(
        FeatureUsageSnapshot(
            baselineComplete = baselineProfile.isComplete,
            mealRecordCount = mealRecords.size,
            mealReminderEnabled = mealReminderEnabled,
            goalCount = goals.size + if (items.any { !it.done && it.goalId != null }) 1 else 0,
            confirmedCourseCount = courses.count { !it.needsConfirmation },
            lifeStage = baselineProfile.lifeStage,
            campusLifeEnabled = campusLifeEnabled,
            statusCheckInEnabled = statusCheckInEnabled,
            statusCheckInCount = checkIns.size,
            windDownEnabled = windDownEnabled
        )
    )
    val overviewScrollState = rememberScrollState()
    Box(modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = !inboxOpen,
            enter = slideInHorizontally(animationSpec = tween(260), initialOffsetX = { -it / 4 }) + fadeIn(tween(180)),
            exit = slideOutHorizontally(animationSpec = tween(220), targetOffsetX = { -it / 4 }) + fadeOut(tween(150))
        ) {
    ScrollableWithBar(scrollState = overviewScrollState) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("今日概览", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
            HelpToggleButton(onClick = { helpOpen = true })
        }
        if (baselineProfile.lifeStage != null) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("当前：${baselineProfile.lifeStage.label}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LifeStage.entries.forEach { stage ->
                    FilterChip(selected = baselineProfile.lifeStage == stage, onClick = { onSwitchLifeStage(stage) }, label = { Text(stage.label) })
                }
            }
        }
        val agenda = todayAgenda(courses, items, now)
        val nowCal = java.util.Calendar.getInstance().apply { timeInMillis = now }
        val currentMinute = nowCal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + nowCal.get(java.util.Calendar.MINUTE)
        val inClass = agenda.firstOrNull { it.isCourse && currentMinute in it.startMinute until (it.startMinute + 45) }
        val upcoming = agenda.filter { it.startMinute >= currentMinute - 5 }.take(3)
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
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
                    Text("记录正在进行的活动；选择娱乐类可顺手设置收尾提醒。", style = MaterialTheme.typography.bodySmall)
                    CheckInInsights.currentSlotAdvice(checkIns)?.let { advice -> Text(advice, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary) }
                    HorizontalDivider()
                    nextSuggestion?.let { suggestion ->
                        val item = suggestion.item
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
        Card(Modifier.fillMaxWidth().clickable(onClick = onOpenSchedule), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))) {
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
            ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("需要恢复的安排", fontWeight = FontWeight.Bold)
                    Text("错过或反复改期不等于失败；选一个更容易继续的下一步。", style = MaterialTheme.typography.bodySmall)
                    recoveryCandidates.take(3).forEach { candidate ->
                        val adjustment = ScheduleAdjuster.suggest(candidate, items, courses, commuteProfile)
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(candidate.item.title.removePrefix("重新安排："), fontWeight = FontWeight.SemiBold)
                            Text(
                                if (candidate.reason == RecoveryReason.MISSED) "原安排已错过" else "已改期 ${candidate.item.rescheduleCount} 次",
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
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("收集箱", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            TextButton(onClick = { onInboxOpenChange(true) }) { Text("${inboxItems.size} 项  ›") }
        }
        if (inboxItems.isEmpty()) {
            Text("暂时没有新想法，点底部 ＋ 随手记录。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            inboxItems.take(2).forEach { item -> InboxItemCard(item, onPickTime, onEdit, onConvertToGoal, onAttachToPlan, onShrink, onPause, onAbandon) }
            if (inboxItems.size > 2) Text("还有 ${inboxItems.size - 2} 项，进入收集箱继续整理。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (visibility.energy) Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(if (energyIsCurrent) "当前精力" else "精力（尚未更新）", fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("偏低", "正常", "充足").forEach { level ->
                        FilterChip(selected = energyIsCurrent && energyLevel == level, onClick = { onEnergyLevelChange(level) }, label = { Text(level) })
                    }
                }
                if (!energyIsCurrent && energyRecordedAt > 0L) Text("上次记录：${formatDateTime(energyRecordedAt)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("只影响弹性任务的推荐顺序，不会移动固定日程。", style = MaterialTheme.typography.bodySmall)
            }
        }
        val todayGoalTasks = items.filter { !it.done && it.goalId != null && it.scheduledAt != null && weekdayOf(it.scheduledAt!!) == weekdayOf(now) }
        val goalsRemaining = goals.count { it.weeklyTarget > GoalPlanner.completedThisWeek(it) }
        if (visibility.goals && (todayGoalTasks.isNotEmpty() || goalsRemaining > 0)) {
            ElevatedCard {
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
        Card(
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        ) {
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
            ElevatedCard {
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
        if (visibility.windDown) WindDownInsights.advice(baselineProfile, courses, items, checkIns, activityHistory, now)?.let { advice ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f))) {
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
        if (visibility.campus) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("校园生活 ${if (campusLifeEnabled) "开" else "关"} · 校内地点、空挡与路程估算", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Switch(checked = campusLifeEnabled, onCheckedChange = onCampusLifeEnabledChange)
        }
    }
        }
        SubpageMotion(inboxOpen.takeIf { it }) {
            PlanSubpageFrame(Modifier.fillMaxSize(), "收集箱") {
                Text("集中处理尚未安排的想法；通过系统返回键或再次点击底栏“今日”回到概览。", style = MaterialTheme.typography.bodySmall)
                if (inboxItems.isEmpty()) {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))) {
                        Text("暂时没有新想法，点底部 ＋ 随手记录。", Modifier.fillMaxWidth().padding(16.dp))
                    }
                } else {
                    inboxItems.forEach { item -> InboxItemCard(item, onPickTime, onEdit, onConvertToGoal, onAttachToPlan, onShrink, onPause, onAbandon) }
                }
            }
        }
        if (helpOpen) HelpDialog(title = HelpCatalog.today.title, sections = HelpCatalog.today.sections, onDismiss = { helpOpen = false })
    }
}

@Composable internal fun MealTodayCard(records: List<MealRecord>, profile: BaselineProfile, skipDays: Set<String>, now: Long, onPrompt: (MealType) -> Unit, onFinish: (MealType) -> Unit) {
    val todayKey = MealLearning.dayKey(now)
    val weekday = java.util.Calendar.getInstance().apply { timeInMillis = now }.get(java.util.Calendar.DAY_OF_WEEK)
    val nowMinute = java.util.Calendar.getInstance().apply { timeInMillis = now }.let { it.get(java.util.Calendar.HOUR_OF_DAY) * 60 + it.get(java.util.Calendar.MINUTE) }
    ElevatedCard {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("今日餐点", fontWeight = FontWeight.Bold)
            if (profile.lifeStage == null) {
                Text("完成“习惯基线”引导后，这里会按你的饭点节奏给出提醒；现在只按你填写的餐点显示。", style = MaterialTheme.typography.bodySmall)
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

@Composable internal fun InboxItemCard(item: Item, onPickTime: (Item) -> Unit, onEdit: (Item) -> Unit, onConvertToGoal: (Item) -> Unit, onAttachToPlan: (Item) -> Unit, onShrink: (Item) -> Unit, onPause: (Item) -> Unit, onAbandon: (Item) -> Unit) {
    ElevatedCard { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(item.title, fontWeight = FontWeight.SemiBold)
        Text(item.detail)
        Text("预计 ${item.durationMinutes} 分钟 · 优先级 ${ItemPriority.fromKey(item.priority).label}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!item.title.startsWith("重新安排：")) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onPickTime(item) }) { Text("安排时间") }
                OutlinedButton(onClick = { onEdit(item) }) { Text("编辑") }
                OutlinedButton(onClick = { onConvertToGoal(item) }) { Text("转成目标") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { onAttachToPlan(item) }) { Text("转为计划的一部分") }
                TextButton(onClick = { onAbandon(item) }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("删除") }
            }
            Text("安排后会从收集箱移到日程，转为计划的一部分后进入弹性安排。", style = MaterialTheme.typography.bodySmall)
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
    val todayTasks = items.filter { !it.done && it.scheduledAt != null && weekdayOf(it.scheduledAt!!) == weekday }
        .mapNotNull { item -> item.scheduledAt?.let { s ->
            val calendar = java.util.Calendar.getInstance().apply { timeInMillis = s }
            AgendaEntry(calendar.get(java.util.Calendar.HOUR_OF_DAY) * 60 + calendar.get(java.util.Calendar.MINUTE), item.title, "任务 · ${item.detail.ifBlank { "已安排" }}", false)
        } }
    return (todayCourses + todayTasks).sortedBy { it.startMinute }
}
