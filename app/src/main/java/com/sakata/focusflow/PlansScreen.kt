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

@Composable internal fun PlansScreen(modifier: Modifier, items: List<Item>, courses: List<Course>, profile: CommuteProfile, lifeStage: LifeStage?, page: PlanPage?, onPageChange: (PlanPage?) -> Unit, onResume: (Item) -> Unit, onConfirmCourse: (Course) -> Unit, onIgnoreCourse: (Course) -> Unit, onClearAwaitingCourses: () -> Unit, onAddCourse: () -> Unit, courseImportRunning: Boolean, courseImportMessage: String?, onImportCourses: () -> Unit, onEditCourse: (Course) -> Unit, goals: List<Goal>, onAddGoal: () -> Unit, onEditGoal: (Goal) -> Unit, onDeleteGoal: (Goal) -> Unit, onScheduleGoal: (Goal, GoalSuggestion) -> Unit, onChooseGoalTime: (Goal) -> Unit, onScheduleFlexible: (Item, Int, Int) -> Unit, resources: List<LearningResource>, onAddResource: () -> Unit, onSelectResource: (LearningResource) -> Unit, onDeleteResource: (LearningResource) -> Unit, onDeselectResource: () -> Unit, onSummarizeResource: (LearningResource) -> Unit, onAutoPlanGoals: () -> Unit, autoPlanMessage: String?, tutorialSearch: TutorialSearchSettings, aiWeeklySummary: AiWeeklySummarySettings, courseVision: CourseVisionSettings, onSearchTutorial: () -> Unit, onVideoAnalysis: () -> Unit, feedback: List<TaskFeedback>, gameSessions: List<GameSessionRecord>, checkIns: List<StatusCheckIn>, taskEvents: List<TaskEvent>, store: PrototypeStore) {
    // AI 周总结生效 key：独立 key 留空时沿用教程搜索的硅基流动 key。
    val weeklySummaryKey = aiWeeklySummary.apiKey.ifBlank { tutorialSearch.apiKey }
    // 假期阶段：空挡与目标建议不把课程当作安排（课程管理页仍用完整列表）。
    val planningCourses = if (lifeStage == LifeStage.HOLIDAY) emptyList<Course>() else courses
    var gapsTableExpanded by remember { mutableStateOf(false) }
    val awaitingCourses = courses.filter { it.needsConfirmation }
    val confirmedCourses = courses.filter { !it.needsConfirmation }
    val conflictingCourses = confirmedCourses.filter { course -> confirmedCourses.any { other -> other != course && coursesOverlap(course, other) } }
    val gaps = CourseGapPlanner.gaps(planningCourses.filter { !it.needsConfirmation }, profile, occupiedByWeekday(items))
    val paused = items.filter { it.kind == "暂停" }
    val historyDays = TaskHistory.lastDays(taskEvents, 7)
    val historyCompletedCount = historyDays.sumOf { it.completedCount }
    val historyRescheduledCount = historyDays.sumOf { it.rescheduledCount }

    val hubScrollState = rememberScrollState()
    Box(modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = page == null,
            enter = slideInHorizontally(animationSpec = tween(260), initialOffsetX = { -it / 4 }) + fadeIn(tween(180)),
            exit = slideOutHorizontally(animationSpec = tween(220), targetOffsetX = { -it / 4 }) + fadeOut(tween(150))
        ) {
        PlanHubScreen(
            modifier = Modifier.fillMaxSize(),
            entries = PlanHubSummary.entries(
                PlanHubSnapshot(
                    confirmedCourseCount = confirmedCourses.size,
                    pendingCourseCount = awaitingCourses.size,
                    conflictingCourseCount = conflictingCourses.size,
                    gapCount = gaps.size,
                    goalCount = goals.size,
                    resourceCount = resources.size,
                    completedThisWeek = goals.sumOf { GoalPlanner.completedThisWeek(it) },
                    weeklyTarget = goals.sumOf { it.weeklyTarget },
                    pausedCount = paused.size,
                    historyCompletedCount = historyCompletedCount,
                    historyRescheduledCount = historyRescheduledCount
                )
            ),
            onOpen = { onPageChange(it) },
            onAddGoal = onAddGoal,
            scrollState = hubScrollState
        )
        }
        SubpageMotion(page) { currentPage ->
            if (currentPage != null) {
                PlanSubpageFrame(Modifier.fillMaxSize(), currentPage.title) {
                    when (currentPage) {
            PlanPage.COURSES -> PlanCoursesSection(
                awaitingCourses = awaitingCourses,
                confirmedCourses = confirmedCourses,
                courseImportRunning = courseImportRunning,
                courseImportMessage = courseImportMessage,
                tutorialSearch = tutorialSearch,
                courseVision = courseVision,
                onImportCourses = onImportCourses,
                onAddCourse = onAddCourse,
                onClearAwaitingCourses = onClearAwaitingCourses,
                onConfirmCourse = onConfirmCourse,
                onEditCourse = onEditCourse,
                onIgnoreCourse = onIgnoreCourse
            )
            PlanPage.GAPS -> PlanGapsSection(
                profile = profile,
                gaps = gaps,
                planningCourses = planningCourses,
                confirmedCourseCount = confirmedCourses.size,
                goals = goals,
                items = items,
                checkIns = checkIns,
                store = store,
                tableExpanded = gapsTableExpanded,
                onTableExpandedChange = { gapsTableExpanded = it },
                onScheduleGoal = onScheduleGoal,
                onScheduleFlexible = onScheduleFlexible
            )
            PlanPage.GOALS -> PlanGoalsSection(
                goals = goals,
                resources = resources,
                planningCourses = planningCourses,
                profile = profile,
                items = items,
                feedback = feedback,
                autoPlanMessage = autoPlanMessage,
                onAddGoal = onAddGoal,
                onEditGoal = onEditGoal,
                onDeleteGoal = onDeleteGoal,
                onScheduleGoal = onScheduleGoal,
                onChooseTime = onChooseGoalTime,
                onAutoPlanGoals = onAutoPlanGoals
            )
            PlanPage.TOOLBOX -> PlanToolboxSection(
                resources = resources,
                tutorialSearch = tutorialSearch,
                onAddResource = onAddResource,
                onVideoAnalysis = onVideoAnalysis,
                onSearchTutorial = onSearchTutorial,
                onSelectResource = onSelectResource,
                onDeselectResource = onDeselectResource,
                onDeleteResource = onDeleteResource,
                onSummarizeResource = onSummarizeResource
            )
            PlanPage.HISTORY -> PlanHistorySection(taskEvents)
            PlanPage.REVIEW -> {
                val executionSummary = RecoveryInsights.weeklySummary(items, System.currentTimeMillis(), taskEvents)
                Card(
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                ) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("本周执行概览", fontWeight = FontWeight.Bold)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(executionSummary.completionPercent?.let { "$it%" } ?: "—", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                Text("计划完成率", style = MaterialTheme.typography.labelSmall)
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("${executionSummary.rescheduledCount}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                Text("改期", style = MaterialTheme.typography.labelSmall)
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("${executionSummary.missedCount}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                Text("待恢复", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        Text(
                            if (executionSummary.plannedCount == 0) "本周尚无定时安排；有计划后再显示完成率。"
                            else "已完成 ${executionSummary.completedCount}/${executionSummary.plannedCount} 项日程。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        executionSummary.frequentReschedulePeriod?.let { period ->
                            Text("本周改期较常发生在$period；下周可尝试缩短该时段任务或预留缓冲。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                if (goals.isEmpty()) Text("创建目标并积累完成记录后，这里会给出调整建议。", style = MaterialTheme.typography.bodySmall)
                else {
                    val totalFull = goals.sumOf { GoalPlanner.completedThisWeek(it) }
                    val totalTarget = goals.sumOf { it.weeklyTarget }
                    Text(if (totalFull >= totalTarget) "本周累计 $totalFull / $totalTarget 次，目标全部达成。" else "本周累计 $totalFull / $totalTarget 次。", fontWeight = FontWeight.Bold)
                    FeedbackInsights.analyze(feedback)?.let { insight ->
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.55f))) {
                            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("长期模式", fontWeight = FontWeight.Bold)
                                Text("${insight.totalCount} 次完成反馈 · 最常见阻碍：${insight.topBarriers.joinToString(" · ") { "${it.first}（${it.second} 次）" }}", style = MaterialTheme.typography.bodySmall)
                                Text("难度：${insight.difficultyCounts.entries.sortedByDescending { it.value }.joinToString(" · ") { "${it.key} ${it.value} 次" }} · 最低版本 ${(insight.minimumRatio * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                                Text(insight.advice, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
                if (feedback.isNotEmpty() && feedback.size < FeedbackInsights.MIN_FEEDBACK) {
                    Text("再积累 ${FeedbackInsights.MIN_FEEDBACK - feedback.size} 次完成反馈后给出长期建议。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                GameStats.summary(gameSessions)?.let { summary ->
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f))) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("活动自律", fontWeight = FontWeight.Bold)
                            Text(summary, style = MaterialTheme.typography.bodySmall)
                            GameStats.advice(gameSessions)?.let { advice -> Text(advice, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                            Text("数据来自“安排空闲活动”中由你确认的结束时间；前台检测只增强游戏／视频的收尾提醒，不会自动写入结束时间。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                HorizontalDivider()
                Text("AI 周总结", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("每个目标的调整建议在其卡片下方（数据式）；这里按本周真实记录（目标完成、常见阻碍、游戏自律）生成一段简短 AI 复盘。", style = MaterialTheme.typography.bodySmall)
                if (!aiWeeklySummary.enabled || weeklySummaryKey.isBlank()) {
                    Text("需在 设置 → 高级工具 → AI 周总结 开启并填写硅基流动 key。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    var summarizing by remember { mutableStateOf(false) }
                    var weeklySummary by remember { mutableStateOf<String?>(null) }
                    var summaryError by remember { mutableStateOf<String?>(null) }
                    val scope = rememberCoroutineScope()
                    Button(enabled = !summarizing, onClick = {
                        summarizing = true
                        summaryError = null
                        weeklySummary = null
                        val dataText = buildString {
                            append("本周目标：\n")
                            goals.forEach { g ->
                                append("- ${g.title}：完成 ${GoalPlanner.completedThisWeek(g)} / ${g.weeklyTarget} 次")
                                val barrier = feedback.filter { it.goalId == g.id && it.barrier != "无" }.groupingBy { it.barrier }.eachCount().maxByOrNull { it.value }?.key
                                if (barrier != null) append("，常见阻碍：$barrier")
                                append("\n")
                            }
                            GameStats.summary(gameSessions)?.let { append("\n活动自律：$it\n") }
                        }
                        scope.launch {
                            val summary = SiliconFlowClient.weeklySummary(weeklySummaryKey, tutorialSearch.model, dataText)
                            summarizing = false
                            if (summary == null) summaryError = "请求失败，请检查网络或模型名" else weeklySummary = summary
                        }
                    }) { Text(if (summarizing) "总结中…" else "生成本周 AI 总结") }
                    summaryError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                    weeklySummary?.let { Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f))) { Text(it, Modifier.fillMaxWidth().padding(12.dp), style = MaterialTheme.typography.bodySmall) } }
                }
                goals.forEach { goal ->
                    val history = WeekReview.history(goal, feedback)
                    ElevatedCard { Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(goal.title, fontWeight = FontWeight.SemiBold)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            val full = GoalPlanner.completedThisWeek(goal)
                            val minimum = GoalPlanner.minimumCompletedThisWeek(goal)
                            Text(if (minimum > 0) "本周 $full / ${goal.weeklyTarget} 次 · 最低版本 $minimum 次" else "本周 $full / ${goal.weeklyTarget} 次", style = MaterialTheme.typography.bodySmall)
                            Text("近 4 周：${history.joinToString(" · ")}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        LinearProgressIndicator(
                            progress = { (GoalPlanner.completedThisWeek(goal).toFloat() / goal.weeklyTarget).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth()
                        )
                        val startLabel = WeekReview.weekLabel(GoalPlanner.currentWeekKey() - 3 * 7 * 24 * 60 * 60_000L)
                        Text("$startLabel 周起每周完成次数（含最低版本）；反馈可跳过，未记录不计入。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        HorizontalDivider()
                        Text(GoalPlanner.weeklyAdvice(goal, feedback.filter { it.createdAt >= GoalPlanner.currentWeekKey() }), style = MaterialTheme.typography.bodySmall)
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
