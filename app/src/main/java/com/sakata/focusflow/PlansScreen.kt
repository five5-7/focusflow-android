package com.sakata.focusflow

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import com.sakata.focusflow.data.CourseRecoveryRestoreCandidate

@Composable internal fun PlansScreen(modifier: Modifier, items: List<Item>, courses: List<Course>, profile: CommuteProfile, lifeStage: LifeStage?, campusLifeEnabled: Boolean, onCampusLifeRequired: () -> Unit, page: PlanPage?, onPageChange: (PlanPage?) -> Unit, onResume: (Item) -> Unit, onAddTodo: () -> Unit, onCompleteTodo: (Item) -> Unit, onTodoDetail: (Item) -> Unit, onBatchTodo: (Set<Long>, TodoBatchAction, Long?, Boolean) -> Boolean, onPauseRepeat: (Item, Boolean) -> Unit, onRepeatRuleAction: (Item, String) -> Unit, onConfirmCourse: (Course) -> Unit, onConfirmSafeCourses: () -> Unit, onIgnoreCourse: (Set<Course>) -> Unit, onClearAwaitingCourses: () -> Unit, onAddCourse: () -> Unit, courseImportRunning: Boolean, courseImportMessage: String?, onImportCourses: () -> Unit, onImportZju: () -> Unit, onEditCourse: (Course) -> Unit, onToggleCourse: (Course) -> Unit, onDeleteCourses: (Set<Course>) -> Unit, onMergeCourses: (Set<Long>, Long, CourseEditPlans.CourseMergePlan.Applied) -> Boolean, onConfirmImportedGroup: (Set<Long>) -> Boolean, onLinkCourses: (Set<Long>) -> Boolean, onSeparateCourse: (Long) -> Boolean, onRenameCourse: (Long, String) -> Boolean, goals: List<Goal>, onAddGoal: () -> Unit, onEditGoal: (Goal) -> Unit, onDeleteGoal: (Goal) -> Unit, onScheduleGoal: (Goal, GoalSuggestion) -> Unit, onChooseGoalTime: (Goal) -> Unit, onScheduleFlexible: (Item, Int, Int) -> Unit, resources: List<LearningResource>, onAddResource: () -> Unit, onSelectResource: (LearningResource) -> Unit, onDeleteResource: (LearningResource) -> Unit, onDeselectResource: () -> Unit, onSummarizeResource: (LearningResource) -> Unit, onAutoPlanGoals: () -> Unit, onCreateWanted: (String) -> Boolean, onEditPlan: (Goal, String, String, String, Long?) -> Boolean, onStartWanted: (Goal, String) -> Boolean, onAddPlanTask: (Goal, String) -> Boolean, onMovePlanTask: (Goal, Item, String) -> Boolean, onFocusPlanTask: (Goal, Item?) -> Boolean, onChangeGoalState: (Goal, PlanState) -> Unit, autoPlanMessage: String?, tutorialSearch: TutorialSearchSettings, courseVision: CourseVisionSettings, onSearchTutorial: () -> Unit, onVideoAnalysis: () -> Unit, feedback: List<TaskFeedback>, checkIns: List<StatusCheckIn>, taskEvents: List<TaskEvent>, onReplaceTaskEvents: (List<TaskEvent>) -> Boolean, store: PrototypeStore, courseReminderSettings: CourseReminderSettings, courseReminderPeriodTable: CoursePeriodTable, onCourseReminderGlobalChange: (Boolean) -> Unit, onCourseReminderOverrideChange: (Course, Boolean?) -> Unit, restorableCourses: List<CourseRecoveryRestoreCandidate>, onRestoreCourses: (String) -> Unit, onPurgeExpiredCourseGroups: () -> Unit) {
    // 假期阶段：空挡与目标建议不把课程当作安排（课程管理页仍用完整列表）。
    // 8.1.0 第三轮：以下都是每次重组重算的派生值，按输入缓存，避免导航时多花一帧。
    val planningCourses = remember(courses, lifeStage, campusLifeEnabled) {
        if (lifeStage == LifeStage.HOLIDAY || !campusLifeEnabled) emptyList<Course>()
        else CourseActivationPolicy.activeInUpcomingWeek(courses.filter { !it.needsConfirmation })
    }
    var gapsTableExpanded by remember { mutableStateOf(false) }
    val awaitingCourses = remember(courses) { courses.filter { it.needsConfirmation } }
    val confirmedCourses = remember(courses) { courses.filter { !it.needsConfirmation } }
    val conflictingCourses = remember(confirmedCourses) {
        confirmedCourses.filter { course -> course.enabled && confirmedCourses.any { other -> other.enabled && other != course && coursesOverlap(course, other) } }
    }
    val gaps = remember(planningCourses, profile, items) {
        CourseGapPlanner.gaps(planningCourses.filter { !it.needsConfirmation }, profile, occupiedByWeekday(items))
    }
    val paused = remember(items) { items.filter { it.kind == "暂停" } }
    val activeGoals = remember(goals) { goals.filter { it.state == PlanState.IN_PROGRESS } }
    val weeklyGoals = remember(activeGoals) { activeGoals.filter { it.weeklyTarget > 0 } }
    val historyDays = remember(taskEvents) { TaskHistory.lastDays(taskEvents, 7) }
    val historyCompletedCount = historyDays.sumOf { it.completedCount }
    val historyRescheduledCount = historyDays.sumOf { it.rescheduledCount }

    val hubScrollState = rememberScrollState()
    Box(modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = page == null,
            // 8.1.0 第三轮：主页直接出现在副页下层（不放大、不淡入）；进入子页时它退到 0.96。
            enter = hubEnter(),
            exit = hubExit()
        ) {
        PlanHubScreen(
            modifier = Modifier.fillMaxSize(),
            entries = PlanHubSummary.entries(
                PlanHubSnapshot(
                    pendingTodoCount = items.count { it.kind == "任务" && !it.done },
                    confirmedCourseCount = confirmedCourses.size,
                    pendingCourseCount = awaitingCourses.size,
                    conflictingCourseCount = conflictingCourses.size,
                    gapCount = gaps.count { it.minutesFree >= 10 },
                    goalCount = activeGoals.size,
                    wantedCount = goals.count { it.state == PlanState.WANTED },
                    resourceCount = resources.size,
                    completedThisWeek = weeklyGoals.sumOf { GoalPlanner.completedThisWeek(it) },
                    weeklyTarget = weeklyGoals.sumOf { it.weeklyTarget },
                    pausedCount = paused.size,
                    historyCompletedCount = historyCompletedCount,
                    historyRescheduledCount = historyRescheduledCount
                )
            ).map { (target, summary) ->
                if (target == PlanPage.COURSES && !campusLifeEnabled) target to "校园生活关闭 · 点击查看开启方法"
                else target to summary
            }.filterNot { (target, _) ->
                target == PlanPage.GAPS && CampusLifePolicy.access(campusLifeEnabled, CampusFeature.GAP_SUGGESTIONS) == CampusFeatureAccess.HIDE
            },
            onOpen = { target ->
                if (target == PlanPage.COURSES && CampusLifePolicy.access(campusLifeEnabled, CampusFeature.COURSE_ENTRY) == CampusFeatureAccess.PROMPT_TO_ENABLE) {
                    onCampusLifeRequired()
                } else onPageChange(target)
            },
            onAddGoal = onAddGoal,
            scrollState = hubScrollState
        )
        }
        SubpageMotion(page) { currentPage ->
            if (currentPage != null) {
                PlanSubpageFrame(Modifier.fillMaxSize(), currentPage.title) {
                    when (currentPage) {
            PlanPage.TODOS -> TodoListSection(items, onAddTodo, onCompleteTodo, onTodoDetail, onBatchTodo, onPauseRepeat, onRepeatRuleAction)
            PlanPage.COURSES -> PlanCoursesSection(
                awaitingCourses = awaitingCourses,
                confirmedCourses = confirmedCourses,
                courseImportRunning = courseImportRunning,
                courseImportMessage = courseImportMessage,
                tutorialSearch = tutorialSearch,
                courseVision = courseVision,
                onImportCourses = onImportCourses,
                onImportZju = onImportZju,
                onAddCourse = onAddCourse,
                onClearAwaitingCourses = onClearAwaitingCourses,
                onConfirmSafeCourses = onConfirmSafeCourses,
                onConfirmCourse = onConfirmCourse,
                onEditCourse = onEditCourse,
                onIgnoreCourse = onIgnoreCourse,
                onToggleCourse = onToggleCourse,
                onDeleteCourses = onDeleteCourses,
                onMergeCourses = onMergeCourses,
                onConfirmImportedGroup = onConfirmImportedGroup,
                onLinkCourses = onLinkCourses,
                onSeparateCourse = onSeparateCourse,
                onRenameCourse = onRenameCourse,
                reminderSettings = courseReminderSettings,
                reminderPeriodTable = courseReminderPeriodTable,
                onReminderGlobalChange = onCourseReminderGlobalChange,
                onReminderOverrideChange = onCourseReminderOverrideChange,
                restorableCourses = restorableCourses,
                onRestoreCourses = onRestoreCourses,
                onPurgeExpiredCourseGroups = onPurgeExpiredCourseGroups
            )
            PlanPage.GAPS -> PlanGapsSection(
                profile = profile,
                gaps = gaps,
                planningCourses = planningCourses,
                confirmedCourseCount = confirmedCourses.size,
                goals = weeklyGoals,
                items = items,
                checkIns = checkIns,
                store = store,
                tableExpanded = gapsTableExpanded,
                onTableExpandedChange = {
                    if (it) FrameTimingRecorder.recordExpansion("gap_table")
                    gapsTableExpanded = it
                },
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
                store = store,
                onAddGoal = onAddGoal,
                onEditGoal = onEditGoal,
                onDeleteGoal = onDeleteGoal,
                onScheduleGoal = onScheduleGoal,
                onChooseTime = onChooseGoalTime,
                onAutoPlanGoals = onAutoPlanGoals,
                onCreateWanted = onCreateWanted,
                onEditPlan = onEditPlan,
                onStartWanted = onStartWanted,
                onAddPlanTask = onAddPlanTask,
                onMovePlanTask = onMovePlanTask,
                onFocusPlanTask = onFocusPlanTask,
                onChangeState = onChangeGoalState
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
            PlanPage.HISTORY -> PlanHistorySection(taskEvents, onReplaceTaskEvents)
            PlanPage.PAUSED -> {
                if (paused.isEmpty()) Text("暂停的任务会集中放在这里，不占用日程。", style = MaterialTheme.typography.bodySmall)
                paused.forEach { item ->
                    // 收编：ElevatedCard → FocusCard，显式保留 surfaceContainerLow 底色与 1dp 默认阴影。
                    FocusCard(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        elevation = 1.dp
                    ) {
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
