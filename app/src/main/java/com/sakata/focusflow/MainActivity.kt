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

class MainActivity : ComponentActivity() {
    private var statusCheckInRequested by mutableStateOf(false)
    private var quickCaptureRequested by mutableStateOf(false)
    private var mealPromptRequested by mutableStateOf<MealType?>(null)
    private var mealFinishRequested by mutableStateOf<MealType?>(null)
    // 首次启动权限一站式进行中标记：置位时门控习惯基线引导，避免两个弹窗叠在一起。
    private var permissionOnboardingPending by mutableStateOf(false)
    // 带回调的通知权限申请器：对话框关闭后（允许/拒绝都算）再跟进精确闹钟申请。
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        permissionOnboardingPending = false
        requestExactAlarmIfNeeded()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashReporter.init(applicationContext)
        statusCheckInRequested = intent.getBooleanExtra(ReminderReceiver.EXTRA_OPEN_STATUS_CHECK_IN, false)
        quickCaptureRequested = intent.getBooleanExtra(ReminderReceiver.EXTRA_OPEN_QUICK_CAPTURE, false)
        mealPromptRequested = intent.getStringExtra(ReminderReceiver.EXTRA_MEAL_TYPE)
            ?.takeIf { intent.getBooleanExtra(ReminderReceiver.EXTRA_OPEN_MEAL_PROMPT, false) }
            ?.let { MealType.fromLabel(it) }
        mealFinishRequested = intent.getStringExtra(ReminderReceiver.EXTRA_MEAL_TYPE)
            ?.takeIf { intent.getBooleanExtra(ReminderReceiver.EXTRA_OPEN_MEAL_FINISH, false) }
            ?.let { MealType.fromLabel(it) }
        // 首次启动一站式权限申请：先标记完成防中断重复打扰，再按系统支持情况依次申请。
        if (!PrototypeStore(this).loadPermissionOnboardingDone()) {
            PrototypeStore(this).savePermissionOnboardingDone(true)
            permissionOnboardingPending = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                permissionOnboardingPending = false
                requestExactAlarmIfNeeded()
            }
        }
        enableEdgeToEdge()
        setContent {
            FocusFlowApp(statusCheckInRequested, mealPromptRequested, mealFinishRequested, quickCaptureRequested, permissionOnboardingPending) {
                statusCheckInRequested = false
                mealPromptRequested = null
                mealFinishRequested = null
                quickCaptureRequested = false
            }
        }
    }

    /** Android 12 的精确闹钟需要用户授权；Android 13+ 由 USE_EXACT_ALARM 按核心日程用途授予。 */
    private fun requestExactAlarmIfNeeded() {
        if (Build.VERSION.SDK_INT in Build.VERSION_CODES.S..Build.VERSION_CODES.S_V2 &&
            !getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
        ) {
            runCatching {
                startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(ReminderReceiver.EXTRA_OPEN_STATUS_CHECK_IN, false)) {
            statusCheckInRequested = true
        }
        intent.getStringExtra(ReminderReceiver.EXTRA_MEAL_TYPE)?.let { label ->
            if (intent.getBooleanExtra(ReminderReceiver.EXTRA_OPEN_MEAL_PROMPT, false)) mealPromptRequested = MealType.fromLabel(label)
            if (intent.getBooleanExtra(ReminderReceiver.EXTRA_OPEN_MEAL_FINISH, false)) mealFinishRequested = MealType.fromLabel(label)
        }
        if (intent.getBooleanExtra(ReminderReceiver.EXTRA_OPEN_QUICK_CAPTURE, false)) quickCaptureRequested = true
    }
}

@Composable
private fun FocusFlowApp(statusCheckInRequested: Boolean, mealPromptRequested: MealType?, mealFinishRequested: MealType?, quickCaptureRequested: Boolean, permissionOnboardingPending: Boolean, onRequestHandled: () -> Unit) {
    val context = LocalContext.current
    val store = remember(context) { PrototypeStore(context) }
    var tab by remember { mutableIntStateOf(0) }
    var todayInboxOpen by remember { mutableStateOf(false) }
    var addOpen by remember { mutableStateOf(false) }
    var addMenuOpen by remember { mutableStateOf(false) }
    var gamePlanOpen by remember { mutableStateOf(false) }
    var activityOpen by remember { mutableStateOf(false) }
    var activityPreset by remember { mutableStateOf<ActivityLaunchPreset?>(null) }
    var transitionTarget by remember { mutableStateOf<ActivitySession?>(null) }
    var autoPromptedSessionId by remember { mutableStateOf<Long?>(null) }
    var rescheduleTarget by remember { mutableStateOf<Item?>(null) }
    var inboxScheduleTarget by remember { mutableStateOf<Item?>(null) }
    var goalScheduleTarget by remember { mutableStateOf<Goal?>(null) }
    var flexiblePlanTarget by remember { mutableStateOf<Item?>(null) }
    var inboxEditTarget by remember { mutableStateOf<Item?>(null) }
    var convertTarget by remember { mutableStateOf<Item?>(null) }
    var attachTarget by remember { mutableStateOf<Item?>(null) }
    var schedulePresetExact by remember { mutableStateOf<Long?>(null) }
    var gameSessions by remember { mutableStateOf(store.loadGameSessions()) }
    var gameDetectionEnabled by remember { mutableStateOf(store.loadGameDetectionEnabled()) }
    var appCategories by remember { mutableStateOf(store.loadAppCategories()) }
    var hiddenApps by remember { mutableStateOf(store.loadHiddenApps()) }
    var items by remember {
        mutableStateOf(store.recoverMissedGoalTasks())
    }
    var taskEvents by remember { mutableStateOf(store.loadTaskEvents()) }
    var activeSession by remember { mutableStateOf(store.loadLatestActiveSession()) }
    var activityHistory by remember { mutableStateOf(store.loadRecentActivitySessions()) }
    var activitySettings by remember { mutableStateOf(store.loadActivityReminderSettings()) }
    var statusCheckInSettings by remember { mutableStateOf(store.loadStatusCheckInSettings()) }
    var quietHours by remember { mutableStateOf(store.loadQuietHoursSettings()) }
    var quickCaptureEnabled by remember { mutableStateOf(store.loadQuickCaptureEnabled()) }
    var windDownEnabled by remember { mutableStateOf(store.loadWindDownEnabled()) }
    var latestStatusCheckIn by remember { mutableStateOf(store.loadLatestStatusCheckIn()) }
    var statusCheckIns by remember { mutableStateOf(store.loadStatusCheckIns(365)) }
    var statusCheckInOpen by remember { mutableStateOf(false) }
    var activityStatusOpen by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val appLifecycleOwner = LocalLifecycleOwner.current
    // 初始值保证冷启动也检查；后续每次回到前台再递增。
    var notificationForegroundCheck by remember { mutableIntStateOf(1) }
    DisposableEffect(appLifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) {
                notificationForegroundCheck++
                // 通知栏里完成/最低版本/延后/跳过是后台 Receiver 写的，回到前台时重读事件，让今日统计与记录卡同步。
                taskEvents = store.loadTaskEvents()
            }
        }
        appLifecycleOwner.lifecycle.addObserver(observer)
        onDispose { appLifecycleOwner.lifecycle.removeObserver(observer) }
    }
    var globalLoading by remember { mutableStateOf(false) }
    var themeOption by remember { mutableStateOf(store.loadTheme()) }
    var darkMode by remember { mutableStateOf(store.loadDarkMode()) }
    var customThemeColors by remember { mutableStateOf(store.loadCustomThemeColors() ?: FocusFlowThemeOption.CUSTOM.colors) }
    var themePresets by remember { mutableStateOf(store.loadThemePresets()) }
    // 自定义主题的"恢复默认"目标：最近一次选过的内置主题。
    var lastBuiltInTheme by remember {
        mutableStateOf(store.loadTheme().takeIf { it != FocusFlowThemeOption.CUSTOM } ?: FocusFlowThemeOption.OCEAN)
    }
    var energyLevel by remember { mutableStateOf(store.loadEnergyLevel()) }
    var commuteProfile by remember { mutableStateOf(store.loadCommuteProfile()) }
    var campusLifeEnabled by remember { mutableStateOf(store.loadCampusLifeEnabled()) }
    var hiddenPlaces by remember { mutableStateOf(store.loadHiddenPlaces()) }
    var campusMapPackage by remember { mutableStateOf(store.loadCampusMapPackage()) }
    var currentCampusPlace by remember { mutableStateOf(store.loadCurrentCampusPlace()) }
    var customPlaces by remember { mutableStateOf(store.loadCustomPlaces()) }
    var amapKey by remember { mutableStateOf(store.loadAmapKey()) }
    var campusCenter by remember { mutableStateOf(store.loadCampusCenter()) }
    var tutorialSearch by remember { mutableStateOf(store.loadTutorialSearchSettings()) }
    var tutorialSearchOpen by remember { mutableStateOf(false) }
    var tutorialFinderOpen by remember { mutableStateOf(false) }
    var finderContext by remember { mutableStateOf("") }
    var videoAnalysisOpen by remember { mutableStateOf(false) }
    var videoAnalysisModel by remember { mutableStateOf(store.loadVideoAnalysisModel()) }
    var courseVision by remember { mutableStateOf(store.loadCourseVisionSettings()) }
    var pendingPlaces by remember { mutableStateOf(store.loadPendingPlaces()) }
    var courseVisionGuideOpen by remember { mutableStateOf(false) }
    var featureIntroOpen by remember { mutableStateOf(false) }
    var baselineWhereToFindOpen by remember { mutableStateOf(false) }
    // 首次开启课表视觉模型且未填 key 时自动弹出申请引导（只弹一次）。
    LaunchedEffect(courseVision.enabled) {
        if (courseVision.enabled && tutorialSearch.apiKey.isBlank() && !store.loadCourseVisionGuideShown()) {
            store.saveCourseVisionGuideShown(true)
            courseVisionGuideOpen = true
        }
    }
    // 询问时刻自动决策：签到数据充分（≥3 次）且未手动调整过时，自动采纳建议询问时刻并重排提醒。
    LaunchedEffect(statusCheckInSettings.enabled, statusCheckInSettings.promptHour, statusCheckIns.size) {
        if (!statusCheckInSettings.enabled || statusCheckInSettings.promptHourAutoAdjusted) return@LaunchedEffect
        val suggested = CheckInInsights.suggestedPromptHour(statusCheckIns) ?: return@LaunchedEffect
        if (suggested != statusCheckInSettings.promptHour) {
            val updated = statusCheckInSettings.copy(promptHour = suggested, promptHourAutoAdjusted = true)
            statusCheckInSettings = updated
            store.saveStatusCheckInSettings(updated)
            ReminderScheduler.scheduleDailyStatusCheckIn(context, updated)
        }
    }
    var courses by remember { mutableStateOf(if (store.hasCourseSetup()) store.loadCourses() else emptyList()) }
    var courseEditor by remember { mutableStateOf<Course?>(null) }
    var addCourseOpen by remember { mutableStateOf(false) }
    var courseImportRunning by remember { mutableStateOf(false) }
    var courseImportMessage by remember { mutableStateOf<String?>(null) }
    var autoPlanMessage by remember { mutableStateOf<String?>(null) }
    var goals by remember { mutableStateOf(store.loadGoals()) }
    var addGoalOpen by remember { mutableStateOf(false) }
    var editGoalTarget by remember { mutableStateOf<Goal?>(null) }
    var goalFinderSuggestion by remember { mutableStateOf("") }
    var resources by remember { mutableStateOf(store.loadResources()) }
    var addResourceOpen by remember { mutableStateOf(false) }
    var summaryTarget by remember { mutableStateOf<LearningResource?>(null) }
    var completionTarget by remember { mutableStateOf<Item?>(null) }
    var feedbackTarget by remember { mutableStateOf<Pair<Item, String>?>(null) }
    var feedback by remember { mutableStateOf(store.loadFeedback()) }
    var improvementNotes by remember { mutableStateOf(store.loadImprovementNotes()) }
    var improvementOpen by remember { mutableStateOf(false) }
    var baselineProfile by remember { mutableStateOf(store.loadBaselineProfile()) }
    var baselineVariants by remember { mutableStateOf(store.loadBaselineVariants()) }
    var baselineVariantNameOpen by remember { mutableStateOf(false) }
    // 权限一站式进行中时先不弹习惯基线引导，避免两个对话框叠在一起；权限流程结束后补弹。
    var baselineOnboardingOpen by remember { mutableStateOf(!store.loadOnboardingDone() && !permissionOnboardingPending) }
    LaunchedEffect(permissionOnboardingPending) {
        if (!permissionOnboardingPending && !store.loadOnboardingDone()) baselineOnboardingOpen = true
    }
    // 首次启动快速入门：等权限申请、习惯基线引导与基线提示结束后再弹（只弹一次），之后可从 设置 → 快速入门 再次打开。
    LaunchedEffect(permissionOnboardingPending, baselineOnboardingOpen, baselineWhereToFindOpen) {
        if (!permissionOnboardingPending && !baselineOnboardingOpen && !baselineWhereToFindOpen && !store.loadFeatureIntroShown()) {
            store.saveFeatureIntroShown(true)
            featureIntroOpen = true
        }
    }
    var baselineEventsOpen by remember { mutableStateOf(false) }
    var baselineResetConfirmOpen by remember { mutableStateOf(false) }
    var mealRecords by remember { mutableStateOf(store.loadMealRecords()) }
    var mealReminderEnabled by remember { mutableStateOf(store.loadMealReminderEnabled()) }
    var mealSkipDays by remember { mutableStateOf(store.loadMealSkipDays()) }
    var mealPromptOpen by remember { mutableStateOf<MealType?>(null) }
    var mealFinishOpen by remember { mutableStateOf<MealType?>(null) }
    var mealRecordsOpen by remember { mutableStateOf(false) }
    var planPage by remember { mutableStateOf<PlanPage?>(null) }
    var settingsSubPage by remember { mutableStateOf<SettingsSubPage?>(null) }
    var settingsParentPage by remember { mutableStateOf<SettingsSubPage?>(null) }
    LaunchedEffect(
        notificationForegroundCheck,
        permissionOnboardingPending,
        baselineOnboardingOpen,
        baselineWhereToFindOpen,
        featureIntroOpen
    ) {
        if (notificationForegroundCheck == 0 || permissionOnboardingPending || baselineOnboardingOpen || baselineWhereToFindOpen || featureIntroOpen) return@LaunchedEffect
        delay(500)
        val message = NotificationHealthPolicy.startupMessage(NotificationChannelSettings.health(context)) ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(message = message, actionLabel = "查看说明", withDismissAction = true)
        if (result == SnackbarResult.ActionPerformed) {
            tab = 3
            todayInboxOpen = false
            planPage = null
            settingsParentPage = null
            settingsSubPage = SettingsSubPage.ACTIVITY_REMINDERS
        }
    }
    // 提升到 app 层：设置页主列表在子页面往返/切 tab 时保持滚动位置。
    val settingsScrollState = remember { ScrollState(0) }
    val suggestedNextStep = items
        .filter { !it.done && it.kind != "收集箱" && it.kind != "暂停" }
        .sortedWith(compareBy<Item> { it.scheduledAt ?: Long.MAX_VALUE }.thenBy { it.title })
        .firstOrNull()
    val upcomingCommitment = NextActionPlanner.nextCommitment(items, courses)
    val suggestedNextStepName = upcomingCommitment?.title ?: suggestedNextStep?.title.orEmpty()
    // 全部地点：内置目录或地点包为基底，自定义地点按名去重合并（同名自定义胜出）。
    val basePlaces = campusMapPackage?.places?.takeIf { it.isNotEmpty() } ?: ZijingangTravel.places
    val campusPlaces = if (campusLifeEnabled) basePlaces.filterNot { b -> customPlaces.any { it.name.lowercase() == b.name.lowercase() } || b.name.lowercase() in hiddenPlaces } + customPlaces else ZijingangTravel.places
    /** 统一处理识别结果：去重、保留冲突为待确认课程并生成提示。 */
    fun applyRecognizedCourses(recognized: List<Course>) {
        val existing = courses.map { listOf(it.weekday, it.startPeriod, it.endPeriod, it.title.trim()) }.toSet()
        val added = recognized.filterNot { listOf(it.weekday, it.startPeriod, it.endPeriod, it.title.trim()) in existing }
        val confirmed = courses.filter { !it.needsConfirmation }
        val conflicts = added.filter { new -> confirmed.any { coursesOverlap(new, it) } }
        // 与已确认课程冲突的识别结果也保留为待确认：应用已有冲突警示机制，由用户决定确认/编辑/忽略。
        if (added.isNotEmpty()) {
            val updated = courses + added
            courses = updated
            store.saveCourses(updated)
        }
        val innerConflicts = added.count { new -> added.any { other -> other != new && coursesOverlap(new, other) } }
        courseImportMessage = when {
            recognized.isEmpty() -> "没有找到可解析的课程。请使用能看到课程名称、星期和节次的截图。"
            added.isEmpty() -> "识别到的课程都已存在，没有重复添加。"
            else -> {
                val parts = mutableListOf<String>()
                if (conflicts.isNotEmpty()) {
                    val details = conflicts.take(3).joinToString("；") { "${weekdayName(it.weekday)} ${it.startPeriod}–${it.endPeriod} 节 ${it.title}" }
                    parts += "有 ${conflicts.size} 门与已确认课程时间冲突，已保留为待确认，请核对后再确认：$details${if (conflicts.size > 3) " 等" else ""}"
                }
                parts += "已生成 ${added.size} 门待确认课程，请逐项编辑、确认或忽略"
                if (innerConflicts > 0) parts += "其中 $innerConflicts 门互相时间重叠，确认前请核对"
                parts.joinToString("。") + "。"
            }
        }
        courseImportRunning = false
        globalLoading = false
    }

    val courseScreenshotLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            courseImportRunning = true
            globalLoading = true
            courseImportMessage = "正在用硅基流动视觉模型识别课程…"
            CourseVisionRecognizer.recognize(context, uri, tutorialSearch.apiKey, courseVision.model, campusPlaces,
                onSuccess = { applyRecognizedCourses(it) },
                onFailure = { visionError ->
                    // 4.0.1 起不再回退本地 OCR（效果差）：直接说明失败原因，可检查 key/模型名/网络后重试。
                    courseImportMessage = "视觉模型识别失败（$visionError）。可检查设置里的 key、模型名或网络后重试。"
                    courseImportRunning = false
                    globalLoading = false
                },
                onNewPlaces = { newPlaces ->
                    if (newPlaces.isNotEmpty()) {
                        pendingPlaces = (newPlaces + pendingPlaces).distinct().take(50)
                        store.savePendingPlaces(pendingPlaces)
                    }
                })
        }
    }
    fun saveItems(updated: List<Item>) {
        val previous = items
        items = updated
        store.saveItems(updated)
        ReminderScheduler.syncTaskReminders(context, previous, updated)
        taskEvents = store.loadTaskEvents()
    }

    /** 追加任务事件并同步 UI：很多操作是 saveItems() 之后再记事件，只靠 saveItems 刷新会漏读最后一条。 */
    fun recordTaskEvent(event: TaskEvent) {
        store.appendTaskEvent(event)
        taskEvents = store.loadTaskEvents()
    }

    /** 放回收集箱：清掉时间与范围，保留原调度日记忆；三处共用（回收卡 / 快速改期建议 / 时间轴弹窗）。 */
    fun returnToInbox(item: Item) {
        saveItems(items.map { if (it.id == item.id) it.copy(kind = "收集箱", recoverySourceScheduledAt = item.recoverySourceScheduledAt ?: item.scheduledAt, scheduledAt = null, dayOnly = false, windowStartAt = null, windowEndAt = null, detail = "已放回收集箱；准备好后再安排") else it })
        recordTaskEvent(TaskRecorder.event(TaskEventType.TASK_TO_INBOX, item.id, item.title.removePrefix("重新安排：")))
    }
    /** 目标任务安排（算法建议点击与自定义时间共用）：写入日程、记事件、建提醒。 */
    val scheduleGoalItem = { goal: Goal, at: Long ->
        val weekday = todayWeekday(at)
        val startMinute = minuteOfDay(at)
        val scheduled = Item(title = goal.title, detail = goalTaskDetail(goal, weekday, startMinute), kind = "任务", scheduledAt = at, goalId = goal.id, durationMinutes = goal.durationMinutes)
        saveItems(listOf(scheduled) + items)
        store.appendBaselineEvent(BaselineRecorder.event(BaselineEventType.TASK_SCHEDULED, "${goal.title} · ${weekdayName(weekday)} ${GoalPlanner.displayTime(startMinute)}"))
        recordTaskEvent(TaskRecorder.event(TaskEventType.TASK_SCHEDULED, scheduled.id, scheduled.title, scheduledAt = at))
        ReminderScheduler.scheduleTaskReminder(context, scheduled)
        scope.launch { snackbarHostState.showSnackbar("已把《${goal.title}》排到 ${weekdayName(weekday)} ${GoalPlanner.displayTime(startMinute)}") }
    }
    fun selectTab(index: Int) {
        todayInboxOpen = false
        planPage = null
        settingsSubPage = null
        settingsParentPage = null
        tab = index
    }
    BackHandler(enabled = tab == 0 && todayInboxOpen) { todayInboxOpen = false }
    BackHandler(enabled = tab == 2 && planPage != null) { planPage = null }
    BackHandler(enabled = tab == 3 && settingsSubPage != null) {
        settingsSubPage = settingsParentPage
        settingsParentPage = null
    }

    LaunchedEffect(statusCheckInRequested) {
        if (statusCheckInRequested) {
            tab = 0
            todayInboxOpen = false
            statusCheckInOpen = true
            onRequestHandled()
        }
    }

    LaunchedEffect(quickCaptureRequested) {
        if (quickCaptureRequested) {
            tab = 0
            todayInboxOpen = true
            onRequestHandled()
        }
    }

    LaunchedEffect(mealPromptRequested, mealFinishRequested) {
        if (mealPromptRequested != null || mealFinishRequested != null) {
            tab = 0
            todayInboxOpen = false
            mealPromptRequested?.let { mealPromptOpen = it }
            mealFinishRequested?.let { mealFinishOpen = it }
            onRequestHandled()
        }
    }

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

    val themeSpec = focusFlowThemeSpec(themeOption, customThemeColors, darkMode)
    CompositionLocalProvider(LocalFocusFlowSchedulePalette provides themeSpec.schedulePalette) {
    MaterialTheme(colorScheme = themeSpec.colorScheme) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = { if (globalLoading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) },
            bottomBar = {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    NavigationBarItem(
                        selected = tab == 0,
                        onClick = { selectTab(0) },
                        icon = { Text(if (tab != 0) "○" else if (!todayInboxOpen) "●" else "◉") },
                        modifier = Modifier.weight(1f),
                        label = { Text(if (todayInboxOpen) "收集箱" else "今日") }
                    )
                    NavigationBarItem(selected = tab == 1, onClick = { selectTab(1) }, icon = { Text(if (tab == 1) "●" else "○") }, modifier = Modifier.weight(1f), label = { Text("日程") })
                    Box(Modifier.weight(0.82f), contentAlignment = Alignment.Center) {
                        FloatingActionButton(modifier = Modifier.size(50.dp), onClick = { addMenuOpen = true }) { Text("＋", style = MaterialTheme.typography.headlineSmall) }
                    }
                    NavigationBarItem(
                        selected = tab == 2,
                        onClick = { selectTab(2) },
                        icon = { Text(if (tab != 2) "○" else if (planPage == null) "●" else "◉") },
                        modifier = Modifier.weight(1f),
                        label = { Text(planPage?.let { "计划·${it.title.take(2)}" } ?: "计划", maxLines = 1) }
                    )
                    NavigationBarItem(
                        selected = tab == 3,
                        onClick = { selectTab(3) },
                        icon = { Text(if (tab != 3) "○" else if (settingsSubPage == null) "●" else "◉") },
                        modifier = Modifier.weight(1f),
                        label = { Text(settingsSubPage?.let { "设置·${it.title.take(2)}" } ?: "设置", maxLines = 1) }
                    )
                }
            }
        ) { padding ->
            // 假期阶段不把课程当作日程：日程/今日摘要/空挡/目标建议均不显示课程（课程管理页仍保留）。
            val scheduleCourses = if (baselineProfile.lifeStage == LifeStage.HOLIDAY) emptyList<Course>() else courses
            when (tab) {
                0 -> TodayScreen(
                    Modifier.padding(padding), items,
                    inboxOpen = todayInboxOpen,
                    onInboxOpenChange = { todayInboxOpen = it },
                    energyLevel = energyLevel,
                    onEnergyLevelChange = { updated -> energyLevel = updated; store.saveEnergyLevel(updated) },
                    campusLifeEnabled = campusLifeEnabled,
                    onCampusLifeEnabledChange = { enabled ->
                        campusLifeEnabled = enabled
                        store.saveCampusLifeEnabled(enabled)
                    },
                    onSwitchLifeStage = { stage ->
                        val previous = baselineProfile
                        if (previous.lifeStage != stage) {
                            baselineProfile = previous.copy(lifeStage = stage, variantName = "")
                            store.saveBaselineProfile(baselineProfile)
                            ReminderScheduler.scheduleDailyWindDown(context, baselineProfile)
                            ReminderScheduler.scheduleDailyMealReminders(context, baselineProfile)
                            // 假期不按校园作息：自动关闭校园生活；切回上学/考试周自动重新开启。
                            if (stage == LifeStage.HOLIDAY && campusLifeEnabled) {
                                campusLifeEnabled = false
                                store.saveCampusLifeEnabled(false)
                            } else if (previous.lifeStage == LifeStage.HOLIDAY && !campusLifeEnabled) {
                                campusLifeEnabled = true
                                store.saveCampusLifeEnabled(true)
                            }
                            store.appendBaselineEvent(BaselineRecorder.event(BaselineEventType.LIFE_STAGE_SET, stage.label))
                        }
                    },
                    onOpenSchedule = { selectTab(1) },
                    onOpenGoals = { selectTab(2); planPage = PlanPage.GOALS },
                    onStartGoalTask = { task ->
                        activityPreset = ActivityLaunchPreset(name = task.title, category = "学习", minutes = task.durationMinutes.coerceIn(5, 360), nextStep = upcomingCommitment?.title.orEmpty(), minimumVersion = false)
                        activityOpen = true
                    },
                    latestStatusCheckIn = latestStatusCheckIn,
                    checkIns = statusCheckIns,
                    onRecordActivity = { activityStatusOpen = true },
                    onTaskDone = { item ->
                        if (item.kind == "游戏" || item.kind == "活动") recordGameItemEnd(context, store, item.id)
                        if (item.goalId == null) {
                            saveItems(items.map { if (it.id == item.id) it.copy(done = true, completionLevel = "完成", completedAt = System.currentTimeMillis()) else it })
                            recordTaskEvent(TaskRecorder.event(TaskEventType.TASK_COMPLETED, item.id, item.title, extra = "完成"))
                        } else completionTarget = item
                    },
                    goals = goals,
                    feedback = feedback,
                    activeSession = activeSession,
                    activityHistory = activityHistory,
                    nextCommitment = upcomingCommitment,
                    commuteProfile = commuteProfile,
                    onStartActivity = { activityPreset = null; activityOpen = true },
                    onStartSuggestion = { suggestion, minimumVersion ->
                        val minutes = if (minimumVersion) suggestion.minimumMinutes else suggestion.item.durationMinutes.coerceIn(5, 360)
                        activityPreset = ActivityLaunchPreset(
                            name = if (minimumVersion) "${suggestion.item.title} · 最低版本" else suggestion.item.title,
                            category = if (suggestion.item.goalId != null) "学习" else "自定义",
                            minutes = minutes,
                            nextStep = upcomingCommitment?.title.orEmpty(),
                            minimumVersion = minimumVersion
                        )
                        activityOpen = true
                    },
                    onReplanSuggestion = { item -> rescheduleTarget = item },
                    onReviewActivity = { activeSession?.let { transitionTarget = it } },
                    onPickTime = { item -> inboxScheduleTarget = item },
                    onEdit = { item -> inboxEditTarget = item },
                    onConvertToGoal = { item -> convertTarget = item },
                    onAttachToPlan = { item -> attachTarget = item },
                    onShrink = { item ->
                        saveItems(items.map { if (it.id == item.id) it.copy(title = item.title.removePrefix("重新安排："), kind = "收集箱", detail = "短版：先做 15 分钟；准备好后再安排", recoverySourceScheduledAt = item.recoverySourceScheduledAt ?: item.scheduledAt, scheduledAt = null, dayOnly = false, durationMinutes = 15, windowStartAt = null, windowEndAt = null) else it })
                        recordTaskEvent(TaskRecorder.event(TaskEventType.TASK_TO_INBOX, item.id, item.title.removePrefix("重新安排："), extra = "缩为 15 分钟"))
                    },
                    onReturnToInbox = { item -> returnToInbox(item) },
                    onApplyAdjustment = { item, adjustment ->
                        when (adjustment.action) {
                            AdjustAction.BACK_TO_INBOX -> returnToInbox(item)
                            AdjustAction.REARRANGE -> rescheduleTarget = item
                            else -> adjustment.targetTime?.let { at ->
                                saveDelayedItem(
                                    context, store, items, item, at, adjustment.durationMinutes,
                                    "${formatDateTime(at)} · ${adjustment.durationMinutes}分钟",
                                    item.priority,
                                    save = { saveItems(it) }, record = { recordTaskEvent(it) }
                                )
                            }
                        }
                    },
                    onPause = { item -> saveItems(items.map { if (it.id == item.id) it.copy(kind = "暂停", detail = "已暂停；随时可在计划中恢复") else it }) },
                    onAbandon = { item ->
                        saveItems(items.filterNot { it.id == item.id })
                        recordTaskEvent(TaskRecorder.event(TaskEventType.TASK_DELETED, item.id, item.title, extra = "放弃"))
                    },
                    baselineEvents = store.loadBaselineEvents(500),
                    taskEvents = taskEvents,
                    mealRecords = mealRecords,
                    mealReminderEnabled = mealReminderEnabled,
                    statusCheckInEnabled = statusCheckInSettings.enabled,
                    windDownEnabled = windDownEnabled,
                    baselineProfile = baselineProfile,
                    courses = scheduleCourses,
                    mealSkipDays = mealSkipDays,
                    onMealPrompt = { mealPromptOpen = it },
                    onMealFinish = { mealFinishOpen = it }
                )
                1 -> ScheduleScreen(
                    Modifier.padding(padding), items, scheduleCourses, commuteProfile,
                    energyLevel = energyLevel,
                    onPlanFlexible = { flexiblePlanTarget = it },
                    onAdjustFlexible = { inboxScheduleTarget = it },
                    onStartTask = { item ->
                        activityPreset = ActivityLaunchPreset(
                            name = item.title,
                            category = if (item.goalId != null) "学习" else "自定义",
                            minutes = item.durationMinutes.coerceIn(5, 360),
                            nextStep = upcomingCommitment?.title.orEmpty(),
                            minimumVersion = false
                        )
                        activityOpen = true
                    },
                    onReturnToInbox = { item -> returnToInbox(item) },
                    onRescheduleTask = { item -> rescheduleTarget = item },
                    onTaskDone = { item ->
                        if (item.kind == "游戏" || item.kind == "活动") recordGameItemEnd(context, store, item.id)
                        if (item.goalId == null) {
                            saveItems(items.map { if (it.id == item.id) it.copy(done = true, completionLevel = "完成", completedAt = System.currentTimeMillis()) else it })
                            recordTaskEvent(TaskRecorder.event(TaskEventType.TASK_COMPLETED, item.id, item.title, extra = "完成"))
                        } else completionTarget = item
                    },
                    onDeleteItem = { item ->
                        saveItems(items.filterNot { it.id == item.id })
                        recordTaskEvent(TaskRecorder.event(TaskEventType.TASK_DELETED, item.id, item.title))
                        if (item.kind == "游戏" || item.kind == "活动") {
                            store.saveGameSessions(store.loadGameSessions().filterNot { it.id == item.id })
                            ReminderScheduler.cancelGameReminders(context, item.id)
                        }
                    }
                )
                2 -> PlansScreen(
                    Modifier.padding(padding), items, courses, commuteProfile, baselineProfile.lifeStage,
                    page = planPage,
                                    onPageChange = { planPage = it; if (it == PlanPage.REVIEW) gameSessions = store.loadGameSessions(); if (it == PlanPage.HISTORY) taskEvents = store.loadTaskEvents() },
                    onResume = { item ->
                        saveItems(items.map { if (it.id == item.id) it.copy(kind = "任务", detail = "已恢复；今天有空时再做", scheduledAt = null) else it })
                        recordTaskEvent(TaskRecorder.event(TaskEventType.TASK_RESTORED, item.id, item.title.removePrefix("重新安排：")))
                    },
                    onConfirmCourse = { course ->
                        courseImportMessage = null
                        courses = courses.map { if (it == course) it.copy(needsConfirmation = false) else it }
                        store.saveCourses(courses)
                    },
                    onIgnoreCourse = { course ->
                        courseImportMessage = null
                        courses = courses.filterNot { it == course }
                        store.saveCourses(courses)
                    },
                    onAddCourse = { addCourseOpen = true },
                    onClearAwaitingCourses = {
                        val count = courses.count { it.needsConfirmation }
                        if (count > 0) {
                            courses = courses.filterNot { it.needsConfirmation }
                            store.saveCourses(courses)
                            courseImportMessage = "已忽略全部 $count 门待确认课程。"
                        }
                    },
                    courseImportRunning = courseImportRunning,
                    courseImportMessage = courseImportMessage,
                    onImportCourses = {
                        if (!courseVision.enabled || tutorialSearch.apiKey.isBlank()) {
                            courseImportMessage = "请先在 设置 → 课表识别（视觉模型）开启并填写硅基流动 key，再导入课表截图。"
                        } else {
                            courseScreenshotLauncher.launch(arrayOf("image/*"))
                        }
                    },
                    onEditCourse = { courseEditor = it },
                    goals = goals,
                    onAddGoal = { goalFinderSuggestion = ""; addGoalOpen = true },
                    onEditGoal = { goal -> goalFinderSuggestion = ""; editGoalTarget = goal },
                    onDeleteGoal = { goal ->
                        goals = goals.filterNot { it.id == goal.id }
                        store.saveGoals(goals)
                        scope.launch { snackbarHostState.showSnackbar("已删除目标《${goal.title}》") }
                    },
                    onScheduleGoal = { goal, suggestion ->
                        scheduleGoalItem(goal, GoalPlanner.nextOccurrence(suggestion.weekday, suggestion.startMinute))
                    },
                    onChooseGoalTime = { goalScheduleTarget = it },
                    onScheduleFlexible = { item, weekday, startMinute ->
                        val target = GoalPlanner.nextOccurrence(weekday, startMinute)
                        val scheduled = item.copy(kind = "任务", scheduledAt = target, dayOnly = false, windowStartAt = null, windowEndAt = null, detail = "已安排到 ${weekdayName(weekday)} ${GoalPlanner.displayTime(startMinute)}")
                        saveItems(items.map { if (it.id == item.id) scheduled else it })
                        recordTaskEvent(TaskRecorder.event(TaskEventType.TASK_SCHEDULED, scheduled.id, scheduled.title, scheduledAt = target))
                        ReminderScheduler.scheduleTaskReminder(context, scheduled)
                        scope.launch { snackbarHostState.showSnackbar("已把《${item.title}》排到 ${weekdayName(weekday)} ${GoalPlanner.displayTime(startMinute)}") }
                    },
                    resources = resources,
                    onAddResource = { addResourceOpen = true },
                    onSelectResource = { resource ->
                        resources = resources.map { it.copy(selected = it.id == resource.id) }
                        store.saveResources(resources)
                        scope.launch { snackbarHostState.showSnackbar("已标记常用资料：《${resource.title}》") }
                    },
                    onDeleteResource = { resource ->
                        resources = resources.filterNot { it.id == resource.id }
                        store.saveResources(resources)
                    },
                    onDeselectResource = {
                        resources = resources.map { it.copy(selected = false) }
                        store.saveResources(resources)
                        scope.launch { snackbarHostState.showSnackbar("已取消常用标记") }
                    },
                    onSummarizeResource = { summaryTarget = it },
                    onAutoPlanGoals = {
                        val remainingByGoal = goals.mapNotNull { goal ->
                            val remaining = goal.weeklyTarget - GoalPlanner.completedThisWeek(goal)
                            if (remaining > 0) goal to remaining else null
                        }
                        if (remainingByGoal.isEmpty()) {
                            autoPlanMessage = "所有目标本周次数都已排满或完成，无需再排。"
                        } else {
                            val newItems = mutableListOf<Item>()
                            remainingByGoal.forEach { (goal, remaining) ->
                                var scheduled = 0
                                // 完成率学习：优先历史完成率高的时段（未知时段排最后）。
                                val suggestions = GoalPlanner.suggestions(goal, scheduleCourses, commuteProfile, occupiedByWeekday(items))
                                    .sortedWith(compareByDescending<GoalSuggestion> { PlanLearning.completionRate(store, it.weekday, it.startMinute / 60) ?: -1f }.thenBy { it.startMinute })
                                for (suggestion in suggestions) {
                                    if (scheduled >= remaining) break
                                    val target = GoalPlanner.nextOccurrence(suggestion.weekday, suggestion.startMinute)
                                    // 与之前已排的目标任务也避让，防止同一次自动排内重复占用同一时段。
                                    if (slotFree(target, goal.durationMinutes, scheduleCourses, items + newItems, commuteProfile)) {
                                        newItems += Item(title = goal.title, detail = goalTaskDetail(goal, suggestion.weekday, suggestion.startMinute), kind = "任务", scheduledAt = target, goalId = goal.id, durationMinutes = goal.durationMinutes)
                                        PlanLearning.recordScheduled(store, suggestion.weekday, suggestion.startMinute / 60)
                                        scheduled++
                                    }
                                }
                            }
                            if (newItems.isEmpty()) {
                                autoPlanMessage = "未来一周空挡都被课程或已有安排占用，没有可排的时段；可先确认课程或调整目标时长。"
                            } else {
                                saveItems(newItems + items)
                                newItems.forEach { ReminderScheduler.scheduleTaskReminder(context, it) }
                                newItems.forEach { recordTaskEvent(TaskRecorder.event(TaskEventType.TASK_SCHEDULED, it.id, it.title, scheduledAt = it.scheduledAt ?: 0)) }
                                val byDay = newItems.groupBy { it.scheduledAt?.let(::weekdayOf) }.mapNotNull { (day, list) -> day?.let { "${weekdayName(it)} ${list.size} 个" } }.joinToString("、")
                                autoPlanMessage = "已把 ${newItems.size} 个目标任务排进未来一周空挡（避开课程与已有安排）：$byDay。可在日程里查看或调整。"
                            }
                        }
                    },
                    autoPlanMessage = autoPlanMessage,
                    tutorialSearch = tutorialSearch,
                    courseVision = courseVision,
                    onSearchTutorial = { tutorialSearchOpen = true },
                    onVideoAnalysis = { videoAnalysisOpen = true },
                    feedback = feedback,
                    gameSessions = gameSessions,
                    checkIns = statusCheckIns,
                    taskEvents = taskEvents,
                    store = store
                )
                else -> SettingsScreen(Modifier.padding(padding), settingsScrollState, themeOption, commuteProfile, campusLifeEnabled, campusMapPackage, currentCampusPlace, improvementNotes, activitySettings, statusCheckInSettings, windDownEnabled = windDownEnabled, checkIns = statusCheckIns, baselineProfile, mealRecords, mealReminderEnabled, subPage = settingsSubPage, onSubPageChange = { target ->
                    if (target == null) {
                        settingsSubPage = null
                        settingsParentPage = null
                    } else {
                        if (settingsSubPage == SettingsSubPage.ADVANCED) settingsParentPage = SettingsSubPage.ADVANCED
                        settingsSubPage = target
                    }
                }, onThemeChange = { updated ->
                    if (updated != FocusFlowThemeOption.CUSTOM) lastBuiltInTheme = updated
                    themeOption = updated
                    store.saveTheme(updated)
                }, customThemeColors = customThemeColors, onCustomThemeColorsChange = { colors ->
                    customThemeColors = colors
                    store.saveCustomThemeColors(colors)
                }, themePresets = themePresets, onThemePresetsChange = { presets ->
                    themePresets = presets
                    store.saveThemePresets(presets)
                }, onRestoreDefaultTheme = {
                    themeOption = lastBuiltInTheme
                    store.saveTheme(lastBuiltInTheme)
                }, onCommuteChange = { updated ->
                    commuteProfile = updated
                    store.saveCommuteProfile(updated)
                }, onCampusLifeEnabledChange = { enabled ->
                    campusLifeEnabled = enabled
                    store.saveCampusLifeEnabled(enabled)
                }, onCampusMapPackageChange = { updated ->
                    campusMapPackage = updated
                    store.saveCampusMapPackage(updated)
                    if (currentCampusPlace !in (updated?.places ?: ZijingangTravel.places).map { it.name }) {
                        currentCampusPlace = null
                        store.saveCurrentCampusPlace(null)
                    }
                }, onCurrentCampusPlaceChange = { updated ->
                    currentCampusPlace = updated
                    store.saveCurrentCampusPlace(updated)
                }, allPlaces = campusPlaces, customPlaces = customPlaces, onCustomPlacesChange = { updated ->
                    customPlaces = updated
                    store.saveCustomPlaces(updated)
                    if (currentCampusPlace != null && updated.none { it.name == currentCampusPlace } && basePlaces.none { it.name == currentCampusPlace }) {
                        currentCampusPlace = null
                        store.saveCurrentCampusPlace(null)
                    }
                }, hiddenPlaces = hiddenPlaces, onToggleHiddenPlace = { name ->
                    val updated = if (name.lowercase() in hiddenPlaces) hiddenPlaces - name else hiddenPlaces + name
                    hiddenPlaces = updated
                    store.saveHiddenPlaces(updated)
                    val current = currentCampusPlace
                    if (current != null && current.lowercase() in updated) {
                        currentCampusPlace = null
                        store.saveCurrentCampusPlace(null)
                    }
                }, amapKey = amapKey, onAmapKeyChange = { updated ->
                    amapKey = updated
                    store.saveAmapKey(updated)
                }, campusCenter = campusCenter, onCampusCenterChange = { updated ->
                    campusCenter = updated
                    store.saveCampusCenter(updated)
                }, tutorialSearch = tutorialSearch, onTutorialSearchSettingsChange = { updated ->
                    tutorialSearch = updated
                    store.saveTutorialSearchSettings(updated)
                }, courseVision = courseVision, onCourseVisionSettingsChange = { updated ->
                    courseVision = updated
                    store.saveCourseVisionSettings(updated)
                }, courseVisionGuideOpen = courseVisionGuideOpen, onCourseVisionGuideOpenChange = { courseVisionGuideOpen = it }, pendingPlaces = pendingPlaces, onAddPendingPlace = { place ->
                    val zone = when {
                        place.contains("田径场") -> CampusZone.EAST_STADIUM
                        place.contains("图书馆") -> CampusZone.LIBRARY
                        place.contains("化学") -> CampusZone.CHEMISTRY_LABS
                        place.startsWith("东") -> CampusZone.EAST_TEACHING
                        place.startsWith("北") -> CampusZone.NORTH_TEACHING
                        else -> CampusZone.WEST_TEACHING
                    }
                    val updated = customPlaces.filterNot { it.name.lowercase() == place.lowercase() } + CampusPlace(name = place, zone = zone, kind = "教学楼")
                    customPlaces = updated
                    store.saveCustomPlaces(updated)
                    pendingPlaces = pendingPlaces.filterNot { it == place }
                    store.savePendingPlaces(pendingPlaces)
                }, onRemovePendingPlace = { place ->
                    pendingPlaces = pendingPlaces.filterNot { it == place }
                    store.savePendingPlaces(pendingPlaces)
                }, onActivitySettingsChange = { updated ->
                    activitySettings = updated
                    store.saveActivityReminderSettings(updated)
                    activeSession?.let { ReminderScheduler.scheduleActivityReminders(context, it, updated) }
                    ReminderScheduler.restoreTaskReminders(context)
                }, onStatusCheckInSettingsChange = { updated ->
                    statusCheckInSettings = updated
                    store.saveStatusCheckInSettings(updated)
                    ReminderScheduler.scheduleDailyStatusCheckIn(context, updated)
                }, quietHours = quietHours, onQuietHoursChange = { updated ->
                    quietHours = updated
                    store.saveQuietHoursSettings(updated)
                }, quickCaptureEnabled = quickCaptureEnabled, onQuickCaptureEnabledChange = { enabled ->
                    quickCaptureEnabled = enabled
                    store.saveQuickCaptureEnabled(enabled)
                    if (enabled) QuickCaptureService.start(context) else QuickCaptureService.stop(context)
                }, onWindDownEnabledChange = { enabled ->
                    windDownEnabled = enabled
                    store.saveWindDownEnabled(enabled)
                    if (enabled) ReminderScheduler.scheduleDailyWindDown(context, baselineProfile) else ReminderScheduler.cancelWindDown(context)
                }, onAddImprovement = { improvementOpen = true }, onOpenBaselineEditor = { baselineOnboardingOpen = true }, onOpenBaselineEvents = { baselineEventsOpen = true }, onResetBaseline = {
                    baselineResetConfirmOpen = true
                }, onOpenFeatureIntro = { featureIntroOpen = true }, baselineVariants = baselineVariants, onSaveBaselineVariant = { name ->
                    val variant = baselineProfile.copy(variantName = name)
                    baselineVariants = (baselineVariants.filterNot { it.variantName == name } + variant).take(8)
                    store.saveBaselineVariants(baselineVariants)
                }, onSwitchBaselineVariant = { variant ->
                    val previous = baselineProfile
                    // 作息分组随方案切换（方案保存时即携带自己的分组）；保留方案名用于显示“当前”。
                    baselineProfile = variant.copy(variantName = variant.variantName)
                    store.saveBaselineProfile(baselineProfile)
                    ReminderScheduler.scheduleDailyWindDown(context, baselineProfile)
                    ReminderScheduler.scheduleDailyMealReminders(context, baselineProfile)
                    if (variant.lifeStage != previous.lifeStage) {
                        store.appendBaselineEvent(BaselineRecorder.event(BaselineEventType.LIFE_STAGE_SET, variant.lifeStage?.label ?: ""))
                    }
                }, onDeleteBaselineVariant = { variant ->
                    baselineVariants = baselineVariants.filterNot { it.variantName == variant.variantName }
                    store.saveBaselineVariants(baselineVariants)
                }, onDayGroupsChange = { groups ->
                    val updated = baselineProfile.copy(dayGroups = groups)
                    baselineProfile = updated
                    store.saveBaselineProfile(updated)
                    // 正在使用某个方案时，分组编辑同步回该方案，避免切走再切回时丢失。
                    if (updated.variantName.isNotBlank()) {
                        baselineVariants = baselineVariants.map { if (it.variantName == updated.variantName) updated else it }
                        store.saveBaselineVariants(baselineVariants)
                    }
                    ReminderScheduler.scheduleDailyMealReminders(context, baselineProfile)
                    ReminderScheduler.scheduleDailyWindDown(context, baselineProfile)
                }, baselineVariantNameOpen = baselineVariantNameOpen, onBaselineVariantNameOpenChange = { baselineVariantNameOpen = it }, onMealReminderEnabledChange = { enabled ->
                    mealReminderEnabled = enabled
                    store.saveMealReminderEnabled(enabled)
                    if (enabled) ReminderScheduler.scheduleDailyMealReminders(context, baselineProfile) else ReminderScheduler.cancelAllMealReminders(context)
                }, onOpenMealRecords = { mealRecordsOpen = true }, recordBaselineEvent = { type, payload ->
                    store.appendBaselineEvent(BaselineRecorder.event(type, payload))
                }, gameDetectionEnabled = gameDetectionEnabled, onGameDetectionEnabledChange = { enabled ->
                    gameDetectionEnabled = enabled
                    store.saveGameDetectionEnabled(enabled)
                    // 开启相应功能时申请相应权限：跳转系统“使用情况访问”授权页。
                    if (enabled && !AppLibrary.hasUsageAccess(context)) {
                        AppLibrary.openUsageAccessSettings(context)
                        scope.launch { snackbarHostState.showSnackbar("请在系统设置中允许“使用情况访问”，返回后即可检测前台应用") }
                    }
                }, appCategories = appCategories, onAppCategoriesChange = { updated ->
                    appCategories = updated
                    store.saveAppCategories(updated)
                }, hiddenApps = hiddenApps, onToggleHiddenApp = { pkg ->
                    hiddenApps = if (pkg in hiddenApps) hiddenApps - pkg else hiddenApps + pkg
                    store.saveHiddenApps(hiddenApps)
                }, videoAnalysisModel = videoAnalysisModel, onVideoAnalysisModelChange = { model ->
                    videoAnalysisModel = model
                    store.saveVideoAnalysisModel(model)
                }, darkMode = darkMode, onDarkModeChange = { enabled ->
                    darkMode = enabled
                    store.saveDarkMode(enabled)
                }, onGlobalLoadingChange = { globalLoading = it })
            }
        }
        if (addMenuOpen) AddMenuDialog(
            onDismiss = { addMenuOpen = false },
            onQuickCapture = { addMenuOpen = false; addOpen = true },
            onGamePlan = { addMenuOpen = false; gamePlanOpen = true }
        )
        if (gamePlanOpen) GamePlanDialog(
            courses = if (baselineProfile.lifeStage == LifeStage.HOLIDAY) emptyList() else courses,
            profile = commuteProfile,
            items = items,
            onDismiss = { gamePlanOpen = false },
            onSave = { item, session ->
                // 个性化频率提醒：同一活动在计划当天已安排的次数超过你历史单日习惯时温和提示（有历史才提示，不写死）。
                val sameDayCount = gameSessions.count { it.title == session.title && it.plannedStartAt in dayRange(session.plannedStartAt) }
                val histMax = GameStats.historicalDailyMax(gameSessions, session.title)
                saveItems(listOf(item) + items)
                gameSessions = store.loadGameSessions() + session
                store.saveGameSessions(gameSessions)
                ReminderScheduler.scheduleGameReminders(context, session)
                gamePlanOpen = false
                val startCal = java.util.Calendar.getInstance().apply { timeInMillis = session.plannedStartAt }
                val startMinute = startCal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + startCal.get(java.util.Calendar.MINUTE)
                val frequencyNote = if (histMax != null && sameDayCount + 1 > histMax) " · 提醒：这天《${item.title}》已安排 ${sameDayCount + 1} 次，你通常每天最多 $histMax 次" else ""
                scope.launch { snackbarHostState.showSnackbar("已安排《${item.title}》${weekdayName(weekdayOf(session.plannedStartAt))} ${GoalPlanner.displayTime(startMinute)} 开始，到点提醒收尾$frequencyNote") }
            }
        )
        if (addOpen) QuickCaptureDialog(
            onDismiss = { addOpen = false },
            onSave = { draft, tomorrow ->
                val captured = if (tomorrow) {
                    Item(title = draft.title, detail = "明天要做 · 尚未安排具体时间", kind = "任务", scheduledAt = dateAt(1, 10), dayOnly = true)
                } else Item(
                    title = draft.title,
                    detail = quickCaptureDetail(draft),
                    kind = "收集箱",
                    durationMinutes = draft.durationMinutes ?: 60,
                    windowStartAt = draft.windowStartAt,
                    windowEndAt = draft.windowEndAt
                )
                saveItems(listOf(captured) + items)
                recordTaskEvent(TaskRecorder.event(TaskEventType.TASK_CREATED, captured.id, captured.title, scheduledAt = captured.scheduledAt ?: 0))
                if (tomorrow) ReminderScheduler.scheduleTaskReminder(context, captured)
                addOpen = false
            },
            onDirectSchedule = { draft, exactAt ->
                // 不落盘：先造收集箱项并预置精确时间模式，确认时走 onSchedule 正常保存（含 TASK_CREATED）。
                val direct = Item(
                    title = draft.title,
                    detail = quickCaptureDetail(draft),
                    kind = "收集箱",
                    durationMinutes = draft.durationMinutes ?: 60,
                    windowStartAt = draft.windowStartAt,
                    windowEndAt = draft.windowEndAt
                )
                addOpen = false
                inboxScheduleTarget = direct
                schedulePresetExact = exactAt
            }
        )
        if (activityOpen) ActivityDialog(suggestedNextStepName, activityPreset, activityHistory, upcomingCommitment, energyLevel, onDismiss = { activityOpen = false; activityPreset = null }) { category, name, endsAt, nextStep ->
            val now = System.currentTimeMillis()
            val session = ActivitySession(name = name, category = category, plannedStartAt = now, actualStartAt = now, endsAt = endsAt, nextStep = nextStep)
            store.saveSession(session)
            activeSession = session
            store.appendBaselineEvent(BaselineRecorder.event(BaselineEventType.ACTIVITY_STARTED, name))
            ReminderScheduler.scheduleActivityReminders(context, session, activitySettings)
            activityOpen = false
            activityPreset = null
        }
        if (statusCheckInOpen) StatusCheckInDialog(
            initialEnergy = energyLevel,
            initialActivity = activeSession?.name ?: latestStatusCheckIn?.activity.orEmpty(),
            onDismiss = { statusCheckInOpen = false },
            onSave = { selectedEnergy, selectedActivity ->
                val checkIn = StatusCheckIn(selectedEnergy, selectedActivity)
                store.saveStatusCheckIn(checkIn)
                store.appendBaselineEvent(BaselineRecorder.event(BaselineEventType.CHECK_IN_RECORDED, "精力$selectedEnergy · $selectedActivity"))
                energyLevel = selectedEnergy
                latestStatusCheckIn = checkIn
                statusCheckIns = store.loadStatusCheckIns(365)
                statusCheckInOpen = false
            }
        )
        if (activityStatusOpen) ActivityStatusDialog(
            initialActivity = latestStatusCheckIn?.activity.orEmpty(),
            onDismiss = { activityStatusOpen = false },
            onSave = { selectedActivity, remindMinutes ->
                val checkIn = StatusCheckIn(energyLevel, selectedActivity)
                store.saveStatusCheckIn(checkIn)
                store.appendBaselineEvent(BaselineRecorder.event(BaselineEventType.CHECK_IN_RECORDED, "精力$energyLevel · $selectedActivity"))
                latestStatusCheckIn = checkIn
                statusCheckIns = store.loadStatusCheckIns(365)
                activityStatusOpen = false
                // 娱乐类活动：记录后顺手建立活动会话并安排收尾提醒（辅助结束游戏等活动的提醒行为）。
                if (remindMinutes != null) {
                    val now = System.currentTimeMillis()
                    val session = ActivitySession(name = "游戏／娱乐", category = "游戏／娱乐", plannedStartAt = now, actualStartAt = now, endsAt = now + remindMinutes * 60_000L, nextStep = "")
                    store.saveSession(session)
                    activeSession = session
                    store.appendBaselineEvent(BaselineRecorder.event(BaselineEventType.ACTIVITY_STARTED, "游戏／娱乐"))
                    ReminderScheduler.scheduleActivityReminders(context, session, activitySettings)
                }
            }
        )
        transitionTarget?.let { session -> ActivityTransitionDialog(
            session = session,
            maxExtensions = activitySettings.maxExtensions,
            upcomingCommitment = upcomingCommitment,
            onDismiss = { transitionTarget = null },
            onFinish = { actualEndAt ->
                store.finishSession(session.id, ActivitySession.STATUS_COMPLETED, "finished_now", actualEndAt)
                store.appendBaselineEvent(BaselineRecorder.event(BaselineEventType.ACTIVITY_ENDED, session.name))
                ReminderScheduler.cancelActivityReminders(context, session.id)
                activeSession = null
                transitionTarget = null
            },
            onStartNext = {
                val now = System.currentTimeMillis()
                store.finishSession(session.id, ActivitySession.STATUS_COMPLETED, "started_next", now)
                val nextName = session.nextStep.ifBlank { suggestedNextStepName }
                store.appendBaselineEvent(BaselineRecorder.event(BaselineEventType.ACTIVITY_ENDED, nextName.takeIf { it.isNotBlank() }?.let { "${session.name} → $it" } ?: session.name))
                ReminderScheduler.cancelActivityReminders(context, session.id)
                if (nextName.isNotBlank()) {
                    val courseDuration = courses.firstOrNull { nextName.startsWith(it.title) }?.let { CourseGapPlanner.periodEnd(it.endPeriod) - CourseGapPlanner.periodStart(it.startPeriod) }
                    val duration = items.firstOrNull { it.title == nextName }?.durationMinutes ?: courseDuration ?: 30
                    val nextSession = ActivitySession(name = nextName, category = "下一步", plannedStartAt = now, actualStartAt = now, endsAt = now + duration * 60_000L)
                    store.saveSession(nextSession)
                    activeSession = nextSession
                    store.appendBaselineEvent(BaselineRecorder.event(BaselineEventType.ACTIVITY_STARTED, nextName))
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
                store.appendBaselineEvent(BaselineRecorder.event(BaselineEventType.ACTIVITY_SKIPPED, session.name))
                ReminderScheduler.cancelActivityReminders(context, session.id)
                store.addReplanItem(session.nextStep.ifBlank { session.name })
                items = store.loadItems()
                activeSession = null
                transitionTarget = null
            }
        ) }
        rescheduleTarget?.let { item -> RescheduleTimeDialog(item, items, courses, commuteProfile, onDismiss = { rescheduleTarget = null }) { scheduledAt, duration, label, priority ->
            saveDelayedItem(context, store, items, item, scheduledAt, duration, label, priority, save = { saveItems(it) }, record = { recordTaskEvent(it) })
            rescheduleTarget = null
        } }
        goalScheduleTarget?.let { goal -> GoalScheduleDialog(
            goal = goal,
            items = items,
            courses = courses,
            profile = commuteProfile,
            onDismiss = { goalScheduleTarget = null },
            onScheduleAt = { at ->
                scheduleGoalItem(goal, at)
                goalScheduleTarget = null
            }
        ) }
        inboxScheduleTarget?.let { item -> InboxScheduleDialog(
            item = item,
            items = items,
            courses = courses,
            profile = commuteProfile,
            energyLevel = energyLevel,
            onDismiss = { inboxScheduleTarget = null; schedulePresetExact = null },
            onSchedule = { startsAt, duration, label, priority ->
                val isNew = items.none { it.id == item.id } // 「直接安排」流：新项未落盘，确认时才创建
                val scheduled = item.copy(
                    title = item.title.removePrefix("重新安排："),
                    kind = "任务",
                    detail = "已安排：$label · $duration 分钟；可随时改期",
                    scheduledAt = startsAt,
                    durationMinutes = duration,
                    dayOnly = false,
                    windowStartAt = null,
                    windowEndAt = null,
                    priority = priority
                )
                saveItems(if (isNew) listOf(scheduled) + items else items.map { if (it.id == item.id) scheduled else it })
                if (isNew) recordTaskEvent(TaskRecorder.event(TaskEventType.TASK_CREATED, scheduled.id, scheduled.title, scheduledAt = 0))
                store.appendBaselineEvent(BaselineRecorder.event(BaselineEventType.TASK_SCHEDULED, "${item.title.removePrefix("重新安排：")} · $label"))
                recordTaskEvent(TaskRecorder.event(TaskEventType.TASK_SCHEDULED, scheduled.id, scheduled.title, scheduledAt = startsAt, extra = label))
                ReminderScheduler.scheduleTaskReminder(context, scheduled)
                inboxScheduleTarget = null
                schedulePresetExact = null
            },
            onKeepWindow = { start, end, duration, label, priority ->
                val isNew = items.none { it.id == item.id }
                val flexible = item.copy(
                    title = item.title.removePrefix("重新安排："),
                    kind = "任务",
                    detail = "弹性范围：$label · 预计 $duration 分钟；尚未锁定具体时刻",
                    scheduledAt = null,
                    durationMinutes = duration,
                    dayOnly = false,
                    windowStartAt = start,
                    windowEndAt = end,
                    priority = priority
                )
                saveItems(if (isNew) listOf(flexible) + items else items.map { if (it.id == item.id) flexible else it })
                if (isNew) recordTaskEvent(TaskRecorder.event(TaskEventType.TASK_CREATED, flexible.id, flexible.title, scheduledAt = 0))
                store.appendBaselineEvent(BaselineRecorder.event(BaselineEventType.TASK_SCHEDULED, "${item.title.removePrefix("重新安排：")} · 弹性范围 $label"))
                recordTaskEvent(TaskRecorder.event(TaskEventType.TASK_SCHEDULED, flexible.id, flexible.title, scheduledAt = start, extra = "弹性范围 $label"))
                inboxScheduleTarget = null
                schedulePresetExact = null
            },
            initialExactTime = schedulePresetExact
        ) }
        flexiblePlanTarget?.let { item -> FlexiblePlanDialog(
            item = item,
            items = items,
            courses = courses,
            energyLevel = energyLevel,
            profile = commuteProfile,
            onDismiss = { flexiblePlanTarget = null },
            onSelect = { suggestion ->
                val scheduled = item.copy(
                    scheduledAt = suggestion.startsAt,
                    durationMinutes = suggestion.durationMinutes,
                    dayOnly = false,
                    windowStartAt = null,
                    windowEndAt = null,
                    detail = "初步安排：${formatDateTime(suggestion.startsAt)} · ${suggestion.durationMinutes} 分钟；可随时改期"
                )
                saveItems(items.map { if (it.id == item.id) scheduled else it })
                store.appendBaselineEvent(BaselineRecorder.event(BaselineEventType.TASK_SCHEDULED, "${item.title} · 初步安排 ${formatDateTime(suggestion.startsAt)}"))
                recordTaskEvent(TaskRecorder.event(TaskEventType.TASK_SCHEDULED, scheduled.id, scheduled.title, scheduledAt = suggestion.startsAt, extra = "初步安排"))
                ReminderScheduler.scheduleTaskReminder(context, scheduled)
                flexiblePlanTarget = null
            }
        ) }
        inboxEditTarget?.let { item -> InboxEditDialog(item, onDismiss = { inboxEditTarget = null }) { title, detail, durationMinutes, priority ->
            // 编辑不记录事件：统计基于发生的事件，编辑不应污染计划/完成率。
            saveItems(items.map { if (it.id == item.id) it.copy(title = title, detail = detail, durationMinutes = durationMinutes, priority = priority) else it })
            inboxEditTarget = null
        } }
        // 自填教学楼自动进入地点库：地点库独立于课程，之后可在地点管理里修改分区/用途。
        fun ensurePlaceForCourse(course: Course) {
            val name = course.building.trim()
            if (name.isBlank() || name == "地点待确认") return
            val known = campusPlaces.any { CourseScreenshotParser.normalize(it.name) == CourseScreenshotParser.normalize(name) }
            if (!known) {
                val zone = CourseScreenshotParser.zoneByPrefix(name)
                val updated = customPlaces.filterNot { it.name.lowercase() == name.lowercase() } + CampusPlace(name = name, zone = zone, kind = "教学楼")
                customPlaces = updated
                store.saveCustomPlaces(updated)
            }
        }
        if (addCourseOpen) CourseEditorDialog(null, campusPlaces, onDismiss = { addCourseOpen = false }) { course ->
            courses = courses + course.copy(needsConfirmation = false)
            store.saveCourses(courses)
            ensurePlaceForCourse(course)
            addCourseOpen = false
        }
        courseEditor?.let { original -> CourseEditorDialog(original, campusPlaces, onDismiss = { courseEditor = null }) { edited ->
            courses = courses.map { if (it == original) edited.copy(needsConfirmation = false) else it }
            store.saveCourses(courses)
            ensurePlaceForCourse(edited)
            courseEditor = null
        } }
        if (addGoalOpen || editGoalTarget != null) GoalEditorDialog(
            initialGoal = editGoalTarget,
            resources = resources,
            suggestedFirstAction = goalFinderSuggestion,
            courses = if (baselineProfile.lifeStage == LifeStage.HOLIDAY) emptyList() else courses,
            profile = commuteProfile,
            items = items,
            onDismiss = { addGoalOpen = false; editGoalTarget = null; goalFinderSuggestion = "" },
            onOpenFinder = { goalTitle, goalOutcome ->
                finderContext = listOf(goalTitle, goalOutcome).filter { it.isNotBlank() }.joinToString(" ")
                tutorialFinderOpen = true
            }
        ) { goal ->
            goals = if (editGoalTarget == null) goals + goal else goals.map { if (it.id == goal.id) goal else it }
            store.saveGoals(goals)
            addGoalOpen = false
            editGoalTarget = null
            goalFinderSuggestion = ""
        }
        convertTarget?.let { item -> GoalEditorDialog(
            initialGoal = null,
            initialTitle = item.title,
            initialDurationMinutes = item.durationMinutes,
            resources = resources,
            suggestedFirstAction = goalFinderSuggestion,
            courses = if (baselineProfile.lifeStage == LifeStage.HOLIDAY) emptyList() else courses,
            profile = commuteProfile,
            items = items,
            onDismiss = { convertTarget = null; goalFinderSuggestion = "" },
            onOpenFinder = { goalTitle, goalOutcome ->
                finderContext = listOf(goalTitle, goalOutcome).filter { it.isNotBlank() }.joinToString(" ")
                tutorialFinderOpen = true
            }
        ) { goal ->
            goals = goals + goal
            store.saveGoals(goals)
            // 转换后条目不再作为任务保留：目标将自行派生效任务（GoalPlanner）。
            saveItems(items.filterNot { it.id == item.id })
            recordTaskEvent(TaskRecorder.event(TaskEventType.TASK_CONVERTED, item.id, item.title, extra = goal.title))
            convertTarget = null
        } }
        attachTarget?.let { item -> AttachToPlanDialog(
            goals = goals,
            onDismiss = { attachTarget = null },
            onAttach = { goal ->
                val attached = item.copy(
                    title = item.title.removePrefix("重新安排："),
                    kind = "任务",
                    detail = "属于目标：${goal.title} · 尚未安排具体时间",
                    goalId = goal.id,
                    scheduledAt = null,
                    dayOnly = false,
                    windowStartAt = null,
                    windowEndAt = null
                )
                saveItems(items.map { if (it.id == item.id) attached else it })
                recordTaskEvent(TaskRecorder.event(TaskEventType.TASK_ATTACHED_TO_PLAN, item.id, item.title.removePrefix("重新安排："), scheduledAt = 0, extra = goal.title))
                attachTarget = null
            }
        ) }
        if (addResourceOpen) ResourceEditorDialog(onDismiss = { addResourceOpen = false }) { resource ->
            resources = resources + resource
            store.saveResources(resources)
            addResourceOpen = false
        }
        if (tutorialSearchOpen) TutorialSearchDialog(
            settings = tutorialSearch,
            onDismiss = { tutorialSearchOpen = false },
            initialTitle = "",
            onLoadingChange = { globalLoading = it }
        )
        if (tutorialFinderOpen) TutorialFinderDialog(
            settings = tutorialSearch,
            initialContext = finderContext,
            onDismiss = { tutorialFinderOpen = false },
            onLoadingChange = { globalLoading = it },
            onUseSuggestion = { action ->
                goalFinderSuggestion = action
                tutorialFinderOpen = false
                scope.launch { snackbarHostState.showSnackbar("已填入候选第一步，请确认后保存目标") }
            }
        )
        if (videoAnalysisOpen) VideoAnalysisDialog(
            settings = tutorialSearch,
            model = videoAnalysisModel,
            onDismiss = { videoAnalysisOpen = false },
            onLoadingChange = { globalLoading = it },
            onSave = { title, url, summary ->
                val resource = LearningResource(title = title, url = url, summary = summary)
                resources = resources + resource
                store.saveResources(resources)
                videoAnalysisOpen = false
                scope.launch { snackbarHostState.showSnackbar("已保存教程《$title》") }
            }
        )
        summaryTarget?.let { resource -> ResourceSummaryDialog(
            settings = tutorialSearch,
            resource = resource,
            onDismiss = { summaryTarget = null },
            onLoadingChange = { globalLoading = it },
            onSave = { summary ->
                resources = resources.map { if (it.id == resource.id) it.copy(summary = summary) else it }
                store.saveResources(resources)
                summaryTarget = null
            }
        ) }
        completionTarget?.let { item -> CompletionDialog(item, goals.firstOrNull { it.id == item.goalId }, onDismiss = { completionTarget = null }) { level ->
            saveItems(items.map { if (it.id == item.id) it.copy(done = true, completionLevel = level, completedAt = System.currentTimeMillis()) else it })
            store.appendBaselineEvent(BaselineRecorder.event(BaselineEventType.TASK_COMPLETED, "${item.title} · $level"))
            recordTaskEvent(TaskRecorder.event(TaskEventType.TASK_COMPLETED, item.id, item.title, extra = level))
            // 完成率学习：记录该目标任务所在时段完成一次。
            item.scheduledAt?.let { time ->
                val cal = java.util.Calendar.getInstance().apply { timeInMillis = time }
                PlanLearning.recordCompleted(store, weekdayOf(time), cal.get(java.util.Calendar.HOUR_OF_DAY))
            }
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
        if (baselineOnboardingOpen) BaselineOnboardingDialog(
            initial = baselineProfile,
            onDismiss = {
                baselineOnboardingOpen = false
                store.saveOnboardingDone(true)
            },
            onSave = { profile ->
                val previous = baselineProfile
                baselineProfile = profile
                store.saveBaselineProfile(profile)
                store.saveOnboardingDone(true)
                ReminderScheduler.scheduleDailyWindDown(context, profile)
                profile.lifeStage?.takeIf { it != previous.lifeStage }?.let { stage ->
                    store.appendBaselineEvent(BaselineRecorder.event(BaselineEventType.LIFE_STAGE_SET, stage.label))
                }
                if (profile.wakeMinute != previous.wakeMinute || profile.sleepMinute != previous.sleepMinute || profile.entertainmentWindow != previous.entertainmentWindow) {
                    store.appendBaselineEvent(BaselineRecorder.event(BaselineEventType.SCHEDULE_ANCHOR_SET, "起床 ${formatMinute(profile.wakeMinute)} · 睡觉 ${formatMinute(profile.sleepMinute)}${profile.entertainmentWindow.takeIf { it.isNotBlank() }?.let { " · 娱乐 $it" } ?: ""}"))
                }
                profile.meals.forEach { meal ->
                    if (previous.meals.none { it.type == meal.type && it.typicalStartMinute == meal.typicalStartMinute && it.typicalMinutes == meal.typicalMinutes }) {
                        store.appendBaselineEvent(BaselineRecorder.event(BaselineEventType.MEAL_TIMELINE_SET, "${meal.type.label} ${formatMinute(meal.typicalStartMinute)} · 约 ${meal.typicalMinutes} 分钟"))
                    }
                }
                ReminderScheduler.scheduleDailyMealReminders(context, profile)
                baselineOnboardingOpen = false
                // 首次完成基线后提示“后续在哪找”（只弹一次）。
                if (!store.loadBaselineWhereToFindShown()) {
                    store.saveBaselineWhereToFindShown(true)
                    baselineWhereToFindOpen = true
                }
            }
        )
        if (baselineWhereToFindOpen) BaselineWhereToFindDialog(onDismiss = { baselineWhereToFindOpen = false })
        if (featureIntroOpen) WelcomeIntroDialog(onDismiss = { featureIntroOpen = false })
        if (baselineEventsOpen) BaselineEventsDialog(
            events = store.loadBaselineEvents(),
            onDismiss = { baselineEventsOpen = false },
            onClear = {
                store.clearBaselineEvents()
                baselineEventsOpen = false
            }
        )
        if (baselineResetConfirmOpen) AlertDialog(
            onDismissRequest = { baselineResetConfirmOpen = false },
            title = { Text("重建习惯基线？") },
            text = { Text("会清空当前基线资料和全部原始事件记录，并重新开始引导。这个操作不可撤销。") },
            confirmButton = {
                Button(onClick = {
                    baselineProfile = BaselineProfile()
                    store.resetBaseline()
                    baselineResetConfirmOpen = false
                    baselineOnboardingOpen = true
                }) { Text("重建") }
            },
            dismissButton = { TextButton(onClick = { baselineResetConfirmOpen = false }) { Text("取消") } }
        )
        mealPromptOpen?.let { type ->
            val weekday = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_WEEK)
            val plan = MealLearning.todayPlan(mealRecords, baselineProfile, weekday, type)
            MealPromptDialog(
                type, plan,
                onDismiss = { mealPromptOpen = null },
                onStarted = {
                    val now = System.currentTimeMillis()
                    val record = MealRecord(mealType = type, lifeStage = baselineProfile.lifeStage?.storageKey.orEmpty(), startedAt = now)
                    store.appendMealRecord(record)
                    mealRecords = store.loadMealRecords()
                    val minuteNow = java.util.Calendar.getInstance().apply { timeInMillis = now }.let { it.get(java.util.Calendar.HOUR_OF_DAY) * 60 + it.get(java.util.Calendar.MINUTE) }
                    store.appendBaselineEvent(BaselineRecorder.event(BaselineEventType.MEAL_STARTED, "${type.label} ${formatMinute(minuteNow)}"))
                    ReminderScheduler.cancelMealReminder(context, type)
                    ReminderScheduler.scheduleMealEndReminder(context, record, plan.minutes)
                    mealPromptOpen = null
                },
                onSnooze = {
                    ReminderScheduler.snoozeMealReminder(context, type)
                    mealPromptOpen = null
                },
                onSkip = {
                    val key = "${MealLearning.dayKey(System.currentTimeMillis())}:${type.label}"
                    mealSkipDays = mealSkipDays + key
                    store.saveMealSkipDays(mealSkipDays)
                    store.appendBaselineEvent(BaselineRecorder.event(BaselineEventType.MEAL_SKIPPED, "${type.label} 今天不需要"))
                    ReminderScheduler.cancelMealReminder(context, type)
                    mealPromptOpen = null
                }
            )
        }
        mealFinishOpen?.let { type ->
            val record = MealLearning.latestOpen(mealRecords, type)
            if (record != null) {
                val weekday = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_WEEK)
                val plan = MealLearning.todayPlan(mealRecords, baselineProfile, weekday, type)
                MealFinishDialog(
                    record, type,
                    onDismiss = { mealFinishOpen = null },
                    onFinished = { draft ->
                        val now = System.currentTimeMillis()
                        store.updateMealRecordEnd(record.id, now, draft)
                        mealRecords = store.loadMealRecords()
                        val minutes = ((now - record.startedAt) / 60_000L).toInt().coerceIn(1, 240)
                        val payload = buildString {
                            append(type.label).append(" · ").append(minutes).append(" 分钟")
                            if (draft.location.isNotBlank()) append(" · ").append(draft.location)
                            if (draft.category.isNotBlank()) append(" · ").append(draft.category)
                            if (draft.merchant.isNotBlank()) append(" · ").append(draft.merchant)
                            if (draft.amount >= 0) append(" · ").append(draft.amount).append(" 元")
                            if (draft.payMethod.isNotBlank()) append(" · ").append(draft.payMethod)
                            if (draft.rating > 0) append(" · ").append(draft.rating).append(" 星")
                            if (draft.note.isNotBlank()) append(" · ").append(draft.note)
                        }
                        store.appendBaselineEvent(BaselineRecorder.event(BaselineEventType.MEAL_ENDED, payload))
                        ReminderScheduler.cancelMealReminder(context, type)
                        mealFinishOpen = null
                    },
                    onStillEating = {
                        ReminderScheduler.scheduleMealEndReminder(context, record.copy(startedAt = System.currentTimeMillis()), plan.minutes)
                        mealFinishOpen = null
                    },
                    onNoRecord = {
                        val now = System.currentTimeMillis()
                        store.updateMealRecordEnd(record.id, now)
                        mealRecords = store.loadMealRecords()
                        store.appendBaselineEvent(BaselineRecorder.event(BaselineEventType.MEAL_ENDED, "${type.label} · ${((now - record.startedAt) / 60_000L).toInt().coerceIn(1, 240)} 分钟 · 未记录消费"))
                        ReminderScheduler.cancelMealReminder(context, type)
                        mealFinishOpen = null
                    }
                )
            } else mealFinishOpen = null
        }
        if (mealRecordsOpen) MealRecordsDialog(
            records = mealRecords,
            onDismiss = { mealRecordsOpen = false },
            onDelete = { id ->
                store.deleteMealRecord(id)
                mealRecords = store.loadMealRecords()
            }
        )
    }
    }
}


/**
 * 把一项已排任务改期保存：事件记录 + 任务提醒重排 + 游戏项同步。
 * 改期弹窗与动态调整"执行建议"共用；保存器由调用方注入（FocusFlowApp 本地作用域）。
 */
private fun saveDelayedItem(
    context: Context,
    store: PrototypeStore,
    items: List<Item>,
    item: Item,
    scheduledAt: Long,
    duration: Int,
    label: String,
    priority: String,
    save: (List<Item>) -> Unit,
    record: (TaskEvent) -> Unit
) {
    val delayed = item.copy(
        kind = "任务",
        detail = "已改期至$label；届时会再次出现",
        scheduledAt = scheduledAt,
        durationMinutes = duration,
        dayOnly = false,
        windowStartAt = null,
        windowEndAt = null,
        priority = priority,
        rescheduleCount = item.rescheduleCount + 1,
        lastRescheduledAt = System.currentTimeMillis()
    )
    save(items.map { if (it.id == item.id) delayed else it })
    store.appendBaselineEvent(BaselineRecorder.event(BaselineEventType.TASK_RESCHEDULED, "${item.title.removePrefix("重新安排：")} → $label"))
    record(TaskRecorder.event(TaskEventType.TASK_RESCHEDULED, item.id, item.title.removePrefix("重新安排："), scheduledAt = scheduledAt, extra = label))
    ReminderScheduler.scheduleTaskReminder(context, delayed)
    if (item.kind == "游戏" || item.kind == "活动") {
        store.loadGameSessions().firstOrNull { it.id == item.id && it.isOpen() }?.let { session ->
            val updated = session.copy(plannedStartAt = scheduledAt, plannedEndAt = scheduledAt + duration * 60_000L)
            store.updateGameSession(item.id) { updated }
            ReminderScheduler.scheduleGameReminders(context, updated)
        }
    }
}


private enum class SettingsSubPage(val title: String) {
    ADVANCED("高级工具"),
    ROADMAP("版本路线图"), CAMPUS_PLACES("校园地点"), COMMUTE_PLACES("通勤与地点"), TUTORIAL_SEARCH("学习路径建议"),
    COURSE_VISION("课表识别（视觉模型）"), APP_DETECTION("前台应用检测"), STABILITY("稳定性与崩溃"),
    APPEARANCE("外观"), ACTIVITY_REMINDERS("日程与活动提醒"), QUIET_HOURS("提醒打扰控制"), CUSTOM_THEME("自定义主题")
}

/** 空挡内容建议：这段空挡适合做什么（目标优先，其次弹性任务）。 */
/** 按空挡匹配内容：未完成目标（时长能放下）优先，其次可安排的空闲弹性任务；目标按该时段历史完成率降序。 */


/** 某天的空挡标记：相邻课程之间的间隙（净分钟数 ≥10 才标记）。 */


/** 本地判断：某时间点安排 durationMinutes 是否与课程/通勤/已有安排冲突（自动排计划用）。 */
private fun slotFree(
    target: Long,
    durationMinutes: Int,
    courses: List<Course>,
    items: List<Item>,
    profile: CommuteProfile? = null
): Boolean {
    val weekday = ScheduleOccupation.weekdayOf(target)
    val minute = ScheduleOccupation.minuteOfDay(target)
    val end = minute + durationMinutes.coerceIn(5, 360)
    return !ScheduleOccupation.overlaps(
        minute, end,
        ScheduleOccupation.dayOccupied(weekday, courses, items, profile)
    )
}

/** 已安装的可启动应用（包名、应用名、分类：用户设置 → 内置清单 → 应用名自动识别）。 */
private fun categorizedInstalledApps(context: Context, userCategories: Map<String, String>): List<Triple<String, String, AppCategory>> = runCatching {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    pm.queryIntentActivities(intent, 0)
        .map { it.activityInfo.packageName }
        .distinct()
        .filter { it != context.packageName }
        .map { pkg -> Triple(pkg, AppLibrary.appLabel(context, pkg), AppLibrary.categoryOf(context, pkg, userCategories)) }
        .sortedBy { it.second }
}.getOrDefault(emptyList())

/** 手动结束一个游戏安排时，记录实际结束（与通知“结束”动作同逻辑）。 */
private fun recordGameItemEnd(context: Context, store: PrototypeStore, sessionId: Long) {
    store.loadGameSessions().firstOrNull { it.id == sessionId && it.isOpen() }?.let { session ->
        val now = System.currentTimeMillis()
        val overrun = ((now - session.plannedEndAt) / 60_000L).toInt().coerceAtLeast(0)
        store.updateGameSession(sessionId) { it.copy(actualEndAt = now, endedOnTime = overrun == 0, overrunMinutes = overrun) }
        ReminderScheduler.cancelGameReminders(context, sessionId)
    }
}


@Composable private fun SettingsScreen(modifier: Modifier, settingsScrollState: ScrollState, themeOption: FocusFlowThemeOption, commuteProfile: CommuteProfile, campusLifeEnabled: Boolean, campusMapPackage: CampusMapPackage?, currentCampusPlace: String?, improvementNotes: List<ImprovementNote>, activitySettings: ActivityReminderSettings, statusCheckInSettings: StatusCheckInSettings, windDownEnabled: Boolean, checkIns: List<StatusCheckIn>, baselineProfile: BaselineProfile, mealRecords: List<MealRecord>, mealReminderEnabled: Boolean, subPage: SettingsSubPage?, onSubPageChange: (SettingsSubPage?) -> Unit, onThemeChange: (FocusFlowThemeOption) -> Unit, customThemeColors: FocusFlowThemeColors, onCustomThemeColorsChange: (FocusFlowThemeColors) -> Unit, themePresets: List<ThemePreset>, onThemePresetsChange: (List<ThemePreset>) -> Unit, onRestoreDefaultTheme: () -> Unit, onCommuteChange: (CommuteProfile) -> Unit, onCampusLifeEnabledChange: (Boolean) -> Unit, onCampusMapPackageChange: (CampusMapPackage?) -> Unit, onCurrentCampusPlaceChange: (String?) -> Unit, allPlaces: List<CampusPlace>, customPlaces: List<CampusPlace>, onCustomPlacesChange: (List<CampusPlace>) -> Unit, hiddenPlaces: Set<String>, onToggleHiddenPlace: (String) -> Unit, amapKey: String, onAmapKeyChange: (String) -> Unit, campusCenter: CampusCenter, onCampusCenterChange: (CampusCenter) -> Unit, tutorialSearch: TutorialSearchSettings, onTutorialSearchSettingsChange: (TutorialSearchSettings) -> Unit, courseVision: CourseVisionSettings, onCourseVisionSettingsChange: (CourseVisionSettings) -> Unit, courseVisionGuideOpen: Boolean, onCourseVisionGuideOpenChange: (Boolean) -> Unit, pendingPlaces: List<String>, onAddPendingPlace: (String) -> Unit, onRemovePendingPlace: (String) -> Unit, onActivitySettingsChange: (ActivityReminderSettings) -> Unit, quietHours: QuietHoursSettings, onQuietHoursChange: (QuietHoursSettings) -> Unit, quickCaptureEnabled: Boolean, onQuickCaptureEnabledChange: (Boolean) -> Unit, onStatusCheckInSettingsChange: (StatusCheckInSettings) -> Unit, onWindDownEnabledChange: (Boolean) -> Unit, onAddImprovement: () -> Unit, onOpenBaselineEditor: () -> Unit, onOpenBaselineEvents: () -> Unit, onResetBaseline: () -> Unit, onOpenFeatureIntro: () -> Unit, baselineVariants: List<BaselineProfile>, onSaveBaselineVariant: (String) -> Unit, onSwitchBaselineVariant: (BaselineProfile) -> Unit, onDeleteBaselineVariant: (BaselineProfile) -> Unit, onDayGroupsChange: (List<DayGroup>) -> Unit, baselineVariantNameOpen: Boolean, onBaselineVariantNameOpenChange: (Boolean) -> Unit, onMealReminderEnabledChange: (Boolean) -> Unit, onOpenMealRecords: () -> Unit, recordBaselineEvent: (BaselineEventType, String) -> Unit, gameDetectionEnabled: Boolean, onGameDetectionEnabledChange: (Boolean) -> Unit, appCategories: Map<String, String>, onAppCategoriesChange: (Map<String, String>) -> Unit, hiddenApps: Set<String>, onToggleHiddenApp: (String) -> Unit, videoAnalysisModel: String, onVideoAnalysisModelChange: (String) -> Unit, darkMode: Boolean, onDarkModeChange: (Boolean) -> Unit, onGlobalLoadingChange: (Boolean) -> Unit) {
    val context = LocalContext.current
    val settingsStore = remember(context) { PrototypeStore(context) }
    val settingsLifecycleOwner = LocalLifecycleOwner.current
    var settingsNotificationHealth by remember { mutableStateOf(NotificationChannelSettings.health(context)) }
    DisposableEffect(settingsLifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) settingsNotificationHealth = NotificationChannelSettings.health(context)
        }
        settingsLifecycleOwner.lifecycle.addObserver(observer)
        onDispose { settingsLifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val campusPlaces = allPlaces
    var importStatus by remember { mutableStateOf<String?>(null) }
    var campusMapHelpOpen by remember { mutableStateOf(false) }
    var advancedCampusImportOpen by remember { mutableStateOf(false) }
    var routeCalibrationTarget by remember { mutableStateOf<Pair<CampusPlace, CampusPlace>?>(null) }
    var choosingCurrentPlace by remember { mutableStateOf(false) }
    var choosingDestination by remember { mutableStateOf(false) }
    var helpBlock by remember { mutableStateOf<SettingsBlock?>(null) }
    var baselineVariantsExpanded by remember { mutableStateOf(false) }
    var dayGroupWizardOpen by remember { mutableStateOf(false) }
    var previewDestination by remember(campusPlaces, currentCampusPlace) {
        mutableStateOf(campusPlaces.firstOrNull { it.name != currentCampusPlace }?.name)
    }
    val importCampusMap = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    ?: throw IllegalArgumentException("无法读取文件")
                CampusMapPackageCodec.parse(text)
            }.onSuccess { imported ->
                onCampusMapPackageChange(imported)
                importStatus = "已导入 ${imported.name}，共 ${imported.places.size} 个地点"
            }.onFailure { error ->
                importStatus = "导入失败：${error.message ?: "文件格式不正确"}"
            }
        }
    }
    Box(modifier.fillMaxSize()) {
    AnimatedVisibility(
        visible = subPage == null,
        enter = slideInHorizontally(animationSpec = tween(260), initialOffsetX = { -it / 4 }) + fadeIn(tween(180)),
        exit = slideOutHorizontally(animationSpec = tween(220), targetOffsetX = { -it / 4 }) + fadeOut(tween(150))
    ) {
    ScrollableWithBar(scrollState = settingsScrollState) {
        Text("设置", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
        PlanHubItem("外观", "当前主题：${themeOption.label}") { onSubPageChange(SettingsSubPage.APPEARANCE) }
        HorizontalDivider()
        PlanHubItem(
            "日程与活动提醒",
            when {
                !settingsNotificationHealth.allReadableSettingsReady -> "通知或横幅待检查"
                !activitySettings.notificationsEnabled -> "活动提醒已关闭"
                else -> "日程提前 ${activitySettings.scheduleAdvanceMinutes} 分钟"
            }
        ) { onSubPageChange(SettingsSubPage.ACTIVITY_REMINDERS) }
        HorizontalDivider()
        PlanHubItem("提醒打扰控制", if (quietHours.enabled) "免打扰 ${formatMinute(quietHours.startMinute)}–${formatMinute(quietHours.endMinute)}" else if (quietHours.isMuted()) "已静音" else "未开启") { onSubPageChange(SettingsSubPage.QUIET_HOURS) }
        HorizontalDivider()
        SettingSwitch("常驻快速记录通知", "通知栏常驻一条通知，随时一键快速记录到收集箱；关闭后通知消失", quickCaptureEnabled, onQuickCaptureEnabledChange)
        HorizontalDivider()
        SettingsSectionHeader("状态询问", onHelp = { helpBlock = SettingsBlock.STATUS_CHECK_IN })
        SettingSwitch("每日低打扰询问", "询问精力与当前活动；关闭后不会删除已有记录", statusCheckInSettings.enabled) {
            onStatusCheckInSettingsChange(statusCheckInSettings.copy(enabled = it))
        }
        if (statusCheckInSettings.enabled) {
            Text("每天约 ${statusCheckInSettings.promptHour}:00 询问")
            Slider(
                value = statusCheckInSettings.promptHour.toFloat(),
                onValueChange = { onStatusCheckInSettingsChange(statusCheckInSettings.copy(promptHour = it.toInt(), promptHourAutoAdjusted = false)) },
                valueRange = 8f..22f,
                steps = 13
            )
            if (statusCheckInSettings.promptHourAutoAdjusted) {
                Text("已自动调整：根据你的 ${checkIns.size} 次签到设为 ${statusCheckInSettings.promptHour}:00（手动调整后不再自动）", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
            Text("主动选择稍后时，推迟 ${statusCheckInSettings.snoozeMinutes} 分钟")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(30, 60, 120).forEach { minutes ->
                    FilterChip(
                        selected = statusCheckInSettings.snoozeMinutes == minutes,
                        onClick = { onStatusCheckInSettingsChange(statusCheckInSettings.copy(snoozeMinutes = minutes)) },
                        label = { Text("$minutes 分钟") }
                    )
                }
            }
            CheckInInsights.suggestedPromptHour(checkIns)?.let { hour ->
                if (hour != statusCheckInSettings.promptHour) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("根据你的 ${checkIns.size} 次签到，建议询问时间设为 ${hour}:00", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = { onStatusCheckInSettingsChange(statusCheckInSettings.copy(promptHour = hour, promptHourAutoAdjusted = false)) }) { Text("采纳") }
                    }
                }
            }
        }
        HorizontalDivider()
        SettingsSectionHeader("睡前减速", onHelp = { helpBlock = SettingsBlock.WIND_DOWN })
        SettingSwitch("睡前减速提醒", "每晚按你填写的睡觉时间提前 40 分钟提醒开始收尾；关闭后不会删除已有记录", windDownEnabled, onWindDownEnabledChange)
        if (baselineProfile.isComplete) {
            WindDownInsights.windDownMinute(baselineProfile)?.let { minute ->
                Text("减速 ${WindDownInsights.formatMinute(minute)} · 睡觉 ${formatMinute(baselineProfile.sleepMinute)}", style = MaterialTheme.typography.bodySmall)
            }
            Text("明早第 1–2 节有课时会在今日页提示注意休息；没有早课则可稍晚收尾。", style = MaterialTheme.typography.bodySmall)
        } else {
            Text("完成习惯基线引导后，按睡觉锚点提醒。", style = MaterialTheme.typography.bodySmall)
        }
        HorizontalDivider()
        SettingsSectionHeader("习惯基线", onHelp = { helpBlock = SettingsBlock.BASELINE })
        ElevatedCard {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (baselineProfile.isComplete) {
                    Text("当前生活阶段：${baselineProfile.lifeStage?.label}", fontWeight = FontWeight.SemiBold)
                    Text("起床 ${formatMinute(baselineProfile.wakeMinute)} · 睡觉 ${formatMinute(baselineProfile.sleepMinute)}")
                    baselineProfile.meals.forEach { meal -> Text("${meal.type.label} 约 ${formatMinute(meal.typicalStartMinute)} · 约 ${meal.typicalMinutes} 分钟", style = MaterialTheme.typography.bodySmall) }
                    if (baselineProfile.entertainmentWindow.isNotBlank()) Text("常见娱乐时段：${baselineProfile.entertainmentWindow}", style = MaterialTheme.typography.bodySmall)
                } else {
                    Text("尚未完成引导；可以现在补上。", fontWeight = FontWeight.SemiBold)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = onOpenBaselineEditor) { Text("编辑") }
                    TextButton(onClick = onResetBaseline) { Text("重建基线") }
                }
                if (baselineProfile.isComplete) {
                    TextButton(onClick = { onBaselineVariantNameOpenChange(true) }) { Text("另存当前方案") }
                } else {
                    Text("完成习惯基线引导（起床、睡觉、餐次）后，可在此把当前作息“另存为方案”，同一生活阶段可存多套并一键切换。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (baselineVariants.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth().clickable { baselineVariantsExpanded = !baselineVariantsExpanded }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("已存方案（${baselineVariants.size}）", fontWeight = FontWeight.SemiBold)
                        Text(if (baselineVariantsExpanded) "收起 ▴" else "展开 ▾", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                    if (baselineVariantsExpanded) {
                        baselineVariants.forEach { variant ->
                            val isCurrent = baselineProfile.variantName.isNotBlank() && baselineProfile.variantName == variant.variantName
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(variant.variantName, fontWeight = FontWeight.SemiBold)
                                        if (isCurrent) {
                                            Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.primary) {
                                                Text("当前", Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimary)
                                            }
                                        }
                                    }
                                    Text("${variant.lifeStage?.label ?: "未定"} · 起床 ${formatMinute(variant.wakeMinute)} · 睡觉 ${formatMinute(variant.sleepMinute)} · ${variant.dayGroups.size} 个作息分组", style = MaterialTheme.typography.bodySmall)
                                }
                                if (isCurrent) {
                                    Text("已切换", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                } else {
                                    TextButton(onClick = { onSwitchBaselineVariant(variant) }) { Text("切换") }
                                }
                                TextButton(onClick = { onDeleteBaselineVariant(variant) }) { Text("删除") }
                            }
                        }
                    }
                }
                HorizontalDivider()
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("作息分组（按星期）", fontWeight = FontWeight.SemiBold)
                    TextButton(onClick = { dayGroupWizardOpen = true }) { Text("＋ 添加/调整") }
                }
                if (baselineProfile.dayGroups.isEmpty()) {
                    Text("每天作息不同可用“添加/调整”按星期设置独立起床/睡觉/餐次（如周五课少≈周末、周末晚起）。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    baselineProfile.dayGroups.forEach { group ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(group.label, fontWeight = FontWeight.SemiBold)
                                Text("${group.days.sorted().joinToString("、") { weekdayName(it) }} · 起床 ${formatMinute(group.wakeMinute)} · 睡觉 ${formatMinute(group.sleepMinute)}", style = MaterialTheme.typography.bodySmall)
                            }
                            TextButton(onClick = { onDayGroupsChange(baselineProfile.dayGroups.filterNot { it.label == group.label }) }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            }
        }
        HorizontalDivider()
        SettingsSectionHeader("饭点学习", onHelp = { helpBlock = SettingsBlock.MEAL_LEARNING })
        SettingSwitch("饭点提醒", "接近预测饭点时询问是否开始吃饭；只有你确认的时间才会用于学习", mealReminderEnabled, onMealReminderEnabledChange)
        if (baselineProfile.lifeStage == null) {
            Text("完成习惯基线引导后，这里会按“生活阶段 × 星期 × 餐次”展示学到的饭点；数据不足时只用宽松提醒，不会假装精确预测。", style = MaterialTheme.typography.bodySmall)
        } else {
            val mealWeekday = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_WEEK)
            MealType.entries.forEach { type ->
                val plan = MealLearning.todayPlan(mealRecords, baselineProfile, mealWeekday, type)
                val stageLabel = baselineProfile.lifeStage?.label.orEmpty()
                Text(
                    if (plan.learned) "${type.label} · $stageLabel 最近 ${plan.sampleCount} 次：${formatMinute(plan.startMinute)} 开始 · 约 ${plan.minutes} 分钟"
                    else "${type.label} · $stageLabel 数据不足（${plan.sampleCount} 次），暂用你填写的 ${formatMinute(plan.startMinute)} · 约 ${plan.minutes} 分钟",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            TextButton(onClick = onOpenMealRecords) { Text("就餐记录") }
        }
        HorizontalDivider()
        PlanHubItem("高级工具", "习惯数据、地点、AI、识别、应用检测与稳定性") { onSubPageChange(SettingsSubPage.ADVANCED) }
        HorizontalDivider()
        SettingsSectionHeader("改进清单", onHelp = { helpBlock = SettingsBlock.IMPROVEMENTS })
        TextButton(onClick = onAddImprovement) { Text("＋ 记录改进想法") }
        improvementNotes.takeLast(3).reversed().forEach { note -> ElevatedCard { Text(note.text, Modifier.padding(10.dp)) } }
        HorizontalDivider()
        PlanHubItem("快速入门", "几步上手的核心流程介绍") { onOpenFeatureIntro() }
        HorizontalDivider()
        PlanHubItem("版本路线图", "当前 ${BuildConfig.VERSION_NAME} · 构建 #${BuildConfig.CI_RUN_NUMBER} · 版本演进") { onSubPageChange(SettingsSubPage.ROADMAP) }
        Text("通知异常时请到“日程与活动提醒”查看检测结果和当前设备的手动路径；精确闹钟按设备支持情况自动处理。")
    }
    }
    AnimatedVisibility(
        visible = subPage != null,
        enter = slideInHorizontally(animationSpec = tween(280), initialOffsetX = { it / 3 }) + fadeIn(tween(190)),
        exit = slideOutHorizontally(animationSpec = tween(230), targetOffsetX = { it / 3 }) + fadeOut(tween(150))
    ) {
        val current = subPage
        if (current != null) {
            PlanSubpageFrame(
                Modifier.fillMaxSize(), current.title,
                // 外观与自定义主题页的问号放在标题行右侧，避免单独一行悬在内容上方。
                titleAction = when (current) {
                    SettingsSubPage.APPEARANCE -> { { HelpToggleButton(onClick = { helpBlock = SettingsBlock.APPEARANCE }) } }
                    SettingsSubPage.CUSTOM_THEME -> { { HelpToggleButton(onClick = { helpBlock = SettingsBlock.CUSTOM_THEME }) } }
                    else -> null
                }
            ) {
                when (current) {
                    SettingsSubPage.ADVANCED -> {
                        Text("这些能力只在配置后参与日常流程；关闭或不配置时不会占用今日页空间。", style = MaterialTheme.typography.bodySmall)
                        PlanHubItem("习惯原始事件", "查看用于形成作息建议的本地记录") { onOpenBaselineEvents() }
                        val expense = ExpenseInsights.summarize(mealRecords)
                        ElevatedCard {
                            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("可选消费记录", fontWeight = FontWeight.Bold)
                                if (expense.withAmountCount == 0) {
                                    Text("就餐结束时可选填金额；没有记录时不会出现在日常流程。", style = MaterialTheme.typography.bodySmall)
                                } else {
                                    Text("${expense.withAmountCount} 笔草稿 · 合计 ¥${expense.totalAmount}；本月 ¥${expense.monthAmount}", style = MaterialTheme.typography.bodySmall)
                                    if (expense.topCategories.isNotEmpty()) Text("分类：${expense.topCategories.joinToString(" · ") { "${it.first} ¥${it.second}" }}", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        PlanHubItem("通勤与地点", if (campusLifeEnabled) "校园生活 开" else "校园生活 关") { onSubPageChange(SettingsSubPage.COMMUTE_PLACES) }
                        PlanHubItem("学习路径建议", if (tutorialSearch.enabled) "已开启${if (tutorialSearch.apiKey.isNotBlank()) " · 已填 key" else ""}" else "未开启") { onSubPageChange(SettingsSubPage.TUTORIAL_SEARCH) }
                        PlanHubItem("课表识别（视觉模型）", if (courseVision.enabled) "已开启${if (tutorialSearch.apiKey.isNotBlank()) " · 已填 key" else " · 未填 key"}" else "未开启") { onSubPageChange(SettingsSubPage.COURSE_VISION) }
                        PlanHubItem("前台应用检测", if (gameDetectionEnabled) "已开启 · 应用分类" else "未开启") { onSubPageChange(SettingsSubPage.APP_DETECTION) }
                        PlanHubItem("稳定性与崩溃", "本地记录崩溃栈 · 可复制反馈") { onSubPageChange(SettingsSubPage.STABILITY) }
                    }
                    SettingsSubPage.ROADMAP -> RoadmapSubpageContent()
                    SettingsSubPage.CAMPUS_PLACES -> CampusPlacesEditorContent(
                        allPlaces = campusPlaces,
                        customPlaces = customPlaces,
                        hasPackage = campusMapPackage != null,
                        amapKey = amapKey,
                        campusCenter = campusCenter,
                        currentCampusPlace = currentCampusPlace,
                        hiddenPlaces = hiddenPlaces,
                        onToggleHiddenPlace = onToggleHiddenPlace,
                        onAmapKeyChange = { onAmapKeyChange(it) },
                        onSavePlace = { originalName, place ->
                            val updated = if (originalName == null) {
                                customPlaces.filterNot { it.name.lowercase() == place.name.lowercase() } + place
                            } else {
                                customPlaces.map { if (it.name == originalName) place else it }
                            }
                            onCustomPlacesChange(updated)
                        },
                        onDeletePlace = { name ->
                            onCustomPlacesChange(customPlaces.filterNot { it.name == name })
                        }
                    )
                    SettingsSubPage.APPEARANCE -> {
                        SettingSwitch(
                            "深色模式",
                            "在当前主题基础上调暗背景与文字，保留主/副/强调色",
                            darkMode,
                            onDarkModeChange
                        )
                        HorizontalDivider()
                        FocusFlowThemeOption.builtInEntries().forEach { option ->
                            val preview = focusFlowThemeSpec(option)
                            Card(
                                modifier = Modifier.fillMaxWidth().clickable { onThemeChange(option) },
                                colors = CardDefaults.cardColors(
                                    containerColor = if (themeOption == option) preview.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f)
                                )
                            ) {
                                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                        previewColors(preview).forEach { color ->
                                            Box(Modifier.size(18.dp).clip(RoundedCornerShape(9.dp)).background(color))
                                        }
                                    }
                                    Column(Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Text(option.label, fontWeight = FontWeight.SemiBold)
                                            if (themeOption == option) {
                                                Text("已选择", color = preview.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                                            }
                                        }
                                        Text(option.description, style = MaterialTheme.typography.bodySmall)
                                    }
                                    // 以此改色：以内置主题的全局色为基底进入自定义编辑器（暂不切换主题，
                                    // 确认由编辑器内"应用此配色"完成，避免界面提前变色造成违和）。
                                    // 轻量文字按钮与预设卡"应用"一致，避免喧宾夺主。
                                    TextButton(
                                        onClick = {
                                            onCustomThemeColorsChange(option.colors)
                                            onSubPageChange(SettingsSubPage.CUSTOM_THEME)
                                        },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                                    ) { Text("以此改色", style = MaterialTheme.typography.bodySmall) }
                                }
                            }
                        }
                        // 自定义主题：点选即应用，点"编辑"进入 5 槽位调色页。
                        val customPreview = focusFlowThemeSpec(FocusFlowThemeOption.CUSTOM, customThemeColors)
                        Card(
                            modifier = Modifier.fillMaxWidth().clickable {
                                onThemeChange(FocusFlowThemeOption.CUSTOM)
                                onSubPageChange(SettingsSubPage.CUSTOM_THEME)
                            },
                            colors = CardDefaults.cardColors(
                                containerColor = if (themeOption == FocusFlowThemeOption.CUSTOM) customPreview.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f)
                            )
                        ) {
                            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                    previewColors(customPreview).forEach { color ->
                                        Box(Modifier.size(18.dp).clip(RoundedCornerShape(9.dp)).background(color))
                                    }
                                }
                                Column(Modifier.weight(1f)) {
                                    Text(FocusFlowThemeOption.CUSTOM.label, fontWeight = FontWeight.SemiBold)
                                    Text("从预设色板自由搭配 · 点此编辑", style = MaterialTheme.typography.bodySmall)
                                }
                                Text("编辑", color = if (themeOption == FocusFlowThemeOption.CUSTOM) customPreview.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                    SettingsSubPage.CUSTOM_THEME -> {
                        CustomThemeEditorContent(
                            colors = customThemeColors,
                            onColorsChange = onCustomThemeColorsChange,
                            presets = themePresets,
                            onPresetsChange = onThemePresetsChange,
                            onRestoreDefault = onRestoreDefaultTheme,
                            customActive = themeOption == FocusFlowThemeOption.CUSTOM,
                            onApplyCustom = { onThemeChange(FocusFlowThemeOption.CUSTOM) }
                        )
                    }
                    SettingsSubPage.ACTIVITY_REMINDERS -> {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            HelpToggleButton(onClick = { helpBlock = SettingsBlock.ACTIVITY_REMINDERS })
                        }
                        // 权限状态随前台恢复刷新：从系统设置页返回后立即更新文案。
                        val lifecycleOwner = LocalLifecycleOwner.current
                        var notificationHealth by remember { mutableStateOf(NotificationChannelSettings.health(context)) }
                        val notificationGuidance = remember { NotificationGuidancePolicy.forDevice(Build.MANUFACTURER, Build.BRAND) }
                        var notifGranted by remember { mutableStateOf(context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) }
                        var exactAllowed by remember { mutableStateOf(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()) }
                        var batteryUnrestricted by remember { mutableStateOf(context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)) }
                        var reminderTestProbe by remember { mutableStateOf(settingsStore.loadReminderTestProbe()) }
                        var reminderDiagnosticsRevision by remember { mutableIntStateOf(0) }
                        var taskTestMessage by remember { mutableStateOf<String?>(null) }
                        val pendingTaskReminders = remember(activitySettings, reminderDiagnosticsRevision) {
                            TaskReminderPolicy.pendingReminders(settingsStore.loadItems(), activitySettings)
                        }
                        val notificationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
                            notifGranted = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                            notificationHealth = NotificationChannelSettings.health(context)
                            ReminderScheduler.restoreTaskReminders(context)
                            reminderDiagnosticsRevision += 1
                        }
                        LaunchedEffect(reminderTestProbe?.expectedAt) {
                            repeat(120) {
                                delay(1_000L)
                                val latest = settingsStore.loadReminderTestProbe()
                                if (latest != reminderTestProbe) reminderTestProbe = latest
                                if (latest?.deliveredAt != null) return@LaunchedEffect
                            }
                        }
                        DisposableEffect(lifecycleOwner) {
                            val observer = LifecycleEventObserver { _, event ->
                                if (event == Lifecycle.Event.ON_RESUME) {
                                    notifGranted = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                                    notificationHealth = NotificationChannelSettings.health(context)
                                    exactAllowed = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
                                    batteryUnrestricted = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
                                    reminderTestProbe = settingsStore.loadReminderTestProbe()
                                    ReminderScheduler.restoreTaskReminders(context)
                                    reminderDiagnosticsRevision += 1
                                }
                            }
                            lifecycleOwner.lifecycle.addObserver(observer)
                            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                        }
                        if (!notifGranted) {
                            Text("通知权限未开启，提醒无法弹出。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            OutlinedButton(onClick = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                else context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS, Uri.parse("package:${context.packageName}")))
                            }) { Text("申请通知权限") }
                            TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS, Uri.parse("package:${context.packageName}"))) }) { Text("已拒绝？去系统设置开启") }
                        } else {
                            Text(
                                if (notificationHealth.allReadableSettingsReady) "Android 可读取的通知与两个渠道均已开启。"
                                else NotificationHealthPolicy.startupMessage(notificationHealth) ?: "通知设置需要检查。",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (notificationHealth.allReadableSettingsReady) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                            )
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(notificationGuidance.title, fontWeight = FontWeight.SemiBold)
                                    notificationGuidance.steps.forEachIndexed { index, step ->
                                        Text("${index + 1}. $step", style = MaterialTheme.typography.bodySmall)
                                    }
                                    Text(notificationGuidance.limitation, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        Text("活动结束提醒", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        SettingSwitch("活动结束提醒", "关闭后仍会保留活动记录和手动转场", activitySettings.notificationsEnabled) { onActivitySettingsChange(activitySettings.copy(notificationsEnabled = it)) }
                        SettingSwitch("明确的到点提醒", "到达约定时间时使用更醒目的提醒", activitySettings.strongerEndReminder) { onActivitySettingsChange(activitySettings.copy(strongerEndReminder = it)) }
                        Text("活动结束前预告：${activitySettings.previewMinutes} 分钟")
                        Slider(
                            value = activitySettings.previewMinutes.toFloat(),
                            onValueChange = { onActivitySettingsChange(activitySettings.copy(previewMinutes = (it / 5).toInt() * 5)) },
                            valueRange = 0f..30f,
                            steps = 5
                        )
                        HorizontalDivider()
                        Text("日程开始提醒", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        SettingSwitch("日程开始提醒", "课程以外的定时任务、目标安排会在开始前预告并在到点时再次提醒；重启后自动恢复", activitySettings.scheduleRemindersEnabled) {
                            onActivitySettingsChange(activitySettings.copy(scheduleRemindersEnabled = it))
                        }
                        Text("日程开始前预告：${activitySettings.scheduleAdvanceMinutes} 分钟")
                        Slider(
                            value = activitySettings.scheduleAdvanceMinutes.toFloat(),
                            onValueChange = { onActivitySettingsChange(activitySettings.copy(scheduleAdvanceMinutes = (it / 5).toInt() * 5)) },
                            valueRange = 0f..30f,
                            steps = 5
                        )
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("日程提醒诊断", fontWeight = FontWeight.SemiBold)
                                Text(
                                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || exactAllowed) "当前使用精确提醒。"
                                    else "当前使用普通后台提醒，系统省电策略可能造成延迟。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || exactAllowed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                                )
                                Text(
                                    if (batteryUnrestricted) "电池后台：Android 检测为不受电池优化限制。" else "电池后台：仍受系统电池优化，后台提醒可能延迟。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (batteryUnrestricted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                                )
                                Text(
                                    "厂商后台／自启动：Android 没有统一的可读取接口，不能直接判断开关；FocusFlow 以下方实测结果判断是否能准时唤醒。",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (!activitySettings.scheduleRemindersEnabled) {
                                    Text("日程提醒已关闭。", style = MaterialTheme.typography.bodySmall)
                                } else if (pendingTaskReminders.isEmpty()) {
                                    Text("目前没有未来的定时任务提醒。", style = MaterialTheme.typography.bodySmall)
                                } else {
                                    pendingTaskReminders.firstOrNull { it.stage == TaskReminderStage.ADVANCE }?.let {
                                        Text("下一次提前提醒：${it.title} · ${formatDateTime(it.triggerAt)}", style = MaterialTheme.typography.bodySmall)
                                    }
                                    pendingTaskReminders.firstOrNull { it.stage == TaskReminderStage.DUE }?.let {
                                        Text("下一次到点提醒：${it.title} · ${formatDateTime(it.triggerAt)}", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                                OutlinedButton(
                                    enabled = notifGranted,
                                    onClick = {
                                        val mode = ReminderScheduler.scheduleTaskReminderTest(context)
                                        reminderTestProbe = settingsStore.loadReminderTestProbe()
                                        taskTestMessage = when (mode) {
                                            AlarmDeliveryMode.ALARM_CLOCK -> "已安排强唤醒测试，1 分钟后应出现；系统可能显示闹钟标识。"
                                            AlarmDeliveryMode.EXACT -> "强唤醒被系统拒绝，已回退到精确测试提醒。"
                                            AlarmDeliveryMode.INEXACT -> "强唤醒与精确提醒均不可用，已使用普通后台测试，可能延迟。"
                                        }
                                    }
                                ) { Text("1 分钟后测试通知") }
                                taskTestMessage?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }
                                val testResult = TaskReminderPolicy.testResult(reminderTestProbe)
                                val testResultText = when (testResult) {
                                    ReminderTestResult.NONE -> "后台实测：尚未测试。"
                                    ReminderTestResult.PENDING -> "后台实测：等待测试提醒送达，请退回桌面。"
                                    ReminderTestResult.ON_TIME -> "后台实测：最近一次按时送达。"
                                    ReminderTestResult.DELAYED -> {
                                        val probe = reminderTestProbe
                                        val delaySeconds = if (probe?.deliveredAt != null) ((probe.deliveredAt - probe.expectedAt) / 1_000L).coerceAtLeast(1L) else 0L
                                        "后台实测：最近一次延迟约 $delaySeconds 秒；不能视为后台正常。"
                                    }
                                    ReminderTestResult.OVERDUE -> "后台实测：已超过预期 30 秒仍未送达；后台唤醒可能受限。"
                                }
                                Text(
                                    testResultText,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (testResult == ReminderTestResult.ON_TIME) MaterialTheme.colorScheme.primary else if (testResult in setOf(ReminderTestResult.DELAYED, ReminderTestResult.OVERDUE)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Text("连续延长提示上限：${activitySettings.maxExtensions} 次")
                        Slider(
                            value = activitySettings.maxExtensions.toFloat(),
                            onValueChange = { onActivitySettingsChange(activitySettings.copy(maxExtensions = it.toInt())) },
                            valueRange = 0f..6f,
                            steps = 5
                        )
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            when {
                                exactAllowed -> Text("精确提醒已由系统启用，无需额外设置。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                                Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2 -> OutlinedButton(onClick = {
                                    runCatching { context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))) }
                                }) { Text("允许精确提醒") }
                                else -> Text("系统未授予精确提醒，已使用普通后台提醒。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                    SettingsSubPage.QUIET_HOURS -> {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            HelpToggleButton(onClick = { helpBlock = SettingsBlock.QUIET_HOURS })
                        }
                        SettingSwitch(
                            "免打扰时段",
                            "时段内静音状态询问、饭点提醒与睡前减速；活动到点和任务提醒保持时间敏感，不静音",
                            quietHours.enabled,
                            { enabled -> onQuietHoursChange(quietHours.copy(enabled = enabled)) }
                        )
                        if (quietHours.enabled) {
                            Text("免打扰 ${formatMinute(quietHours.startMinute)} – ${formatMinute(quietHours.endMinute)}（跨天按到次日处理）")
                            Text("开始时间", style = MaterialTheme.typography.labelSmall)
                            Slider(value = quietHours.startMinute.toFloat(), onValueChange = { onQuietHoursChange(quietHours.copy(startMinute = ((it / 30).toInt() * 30).coerceIn(0, 1439))) }, valueRange = 0f..1439f, steps = 47)
                            Text("结束时间", style = MaterialTheme.typography.labelSmall)
                            Slider(value = quietHours.endMinute.toFloat(), onValueChange = { onQuietHoursChange(quietHours.copy(endMinute = ((it / 30).toInt() * 30).coerceIn(0, 1439))) }, valueRange = 0f..1439f, steps = 47)
                            SettingSwitch("静音状态询问", "免打扰时段内不弹低打扰状态询问", quietHours.suppressStatusCheckIn) { v -> onQuietHoursChange(quietHours.copy(suppressStatusCheckIn = v)) }
                            SettingSwitch("静音饭点提醒", "“准备吃饭／吃完了吗”提醒不弹出", quietHours.suppressMeal) { v -> onQuietHoursChange(quietHours.copy(suppressMeal = v)) }
                            SettingSwitch("静音睡前减速", "睡前减速提醒不弹出", quietHours.suppressWindDown) { v -> onQuietHoursChange(quietHours.copy(suppressWindDown = v)) }
                        }
                        Text("一次性静音", fontWeight = FontWeight.SemiBold)
                        Text("立即静音所有提醒一段时间，适合睡觉、上课或开会。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilledTonalButton(onClick = { onQuietHoursChange(quietHours.copy(muteUntil = System.currentTimeMillis() + 3_600_000L)) }) { Text("1 小时") }
                            FilledTonalButton(onClick = { onQuietHoursChange(quietHours.copy(muteUntil = System.currentTimeMillis() + 3 * 3_600_000L)) }) { Text("3 小时") }
                            FilledTonalButton(onClick = { onQuietHoursChange(quietHours.copy(muteUntil = nextMorning())) }) { Text("到明早 7 点") }
                        }
                        if (quietHours.isMuted()) {
                            Text("已静音至 ${formatDateTime(quietHours.muteUntil)}（所有提醒静音）", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            TextButton(onClick = { onQuietHoursChange(quietHours.copy(muteUntil = 0L)) }) { Text("取消静音") }
                        }
                    }
                    SettingsSubPage.COMMUTE_PLACES -> {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            HelpToggleButton(onClick = { helpBlock = SettingsBlock.COMMUTE_PLACES })
                        }
                        SettingSwitch("校园生活", "控制校内出行、地点包和手动位置工具；关闭不会删除已有数据", campusLifeEnabled, onCampusLifeEnabledChange)
                        if (campusLifeEnabled) {
                            ElevatedCard(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("出行参数", fontWeight = FontWeight.SemiBold)
                                    SettingSwitch("为通勤预留时间", "只保存大致时长，不读取定位", commuteProfile.enabled) { onCommuteChange(commuteProfile.copy(enabled = it)) }
                                    if (commuteProfile.enabled) {
                                        Text("单程约 ${commuteProfile.oneWayMinutes} 分钟")
                                        Slider(value = commuteProfile.oneWayMinutes.toFloat(), onValueChange = { onCommuteChange(commuteProfile.copy(oneWayMinutes = (it / 5).toInt() * 5)) }, valueRange = 5f..120f, steps = 22)
                                        Text("校内主要方式", fontWeight = FontWeight.SemiBold)
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            listOf("步行", "自行车", "电动车").forEach { mode -> FilterChip(selected = commuteProfile.campusMode == mode, onClick = { onCommuteChange(commuteProfile.copy(campusMode = mode)) }, label = { Text(mode) }) }
                                        }
                                        Text("教学楼进出与找教室缓冲：${commuteProfile.buildingBufferMinutes} 分钟", fontWeight = FontWeight.SemiBold)
                                        Slider(value = commuteProfile.buildingBufferMinutes.toFloat(), onValueChange = { onCommuteChange(commuteProfile.copy(buildingBufferMinutes = it.toInt())) }, valueRange = 1f..10f, steps = 8)
                                        if (commuteProfile.campusMode == "电动车") {
                                            Text("电动车电量", fontWeight = FontWeight.SemiBold)
                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                listOf("充足", "一般", "偏低", "未知").forEach { level -> FilterChip(selected = commuteProfile.eBikeBattery == level, onClick = { onCommuteChange(commuteProfile.copy(eBikeBattery = level)) }, label = { Text(level) }) }
                                            }
                                            if (commuteProfile.eBikeBattery == "偏低") {
                                                Text("电量偏低：排程会避免安排需要骑车的远距离连续行程；建议在长空档充电（见 计划→空挡建议）。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                        }
                                    }
                                }
                            }
                            ElevatedCard(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                        Text("校园地点来源", fontWeight = FontWeight.SemiBold)
                                        OutlinedButton(
                                            onClick = { campusMapHelpOpen = true },
                                            modifier = Modifier.size(30.dp),
                                            shape = CircleShape,
                                            contentPadding = PaddingValues(0.dp)
                                        ) { Text("?", fontWeight = FontWeight.Bold) }
                                    }
                                    Text(
                                        campusMapPackage?.let { "高级地点包：${it.name} · ${it.places.size} 个地点" } ?: "已自动使用内置紫金港目录 · ${ZijingangTravel.places.size} 个地点",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    TextButton(onClick = { onSubPageChange(SettingsSubPage.CAMPUS_PLACES) }) { Text("管理校园地点（增删改 / 搜索）") }
                                    TextButton(onClick = { advancedCampusImportOpen = !advancedCampusImportOpen }) { Text(if (advancedCampusImportOpen) "收起高级导入" else "高级：导入已有地点数据") }
                                    if (advancedCampusImportOpen) {
                                        OutlinedButton(onClick = { importCampusMap.launch(arrayOf("application/json", "text/plain")) }) { Text("导入地点包（JSON）") }
                                        if (campusMapPackage != null) TextButton(onClick = {
                                            onCampusMapPackageChange(null)
                                            importStatus = "已恢复内置紫金港地点"
                                        }) { Text("恢复内置地点") }
                                        importStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = if (it.startsWith("导入失败")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) }
                                        Text("圆形问号中保留完整格式示例，供迁移或批量维护时使用。", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                            if (pendingPlaces.isNotEmpty()) {
                                ElevatedCard(Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text("课表识别发现的新地点", fontWeight = FontWeight.SemiBold)
                                        Text("识别结果中出现、还没加入地点目录的教室/楼名；加入后可用于课程空档与路程估算，也可在“管理校园地点”里改分区。", style = MaterialTheme.typography.bodySmall)
                                        pendingPlaces.forEach { place ->
                                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                                Text(place, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                                TextButton(onClick = { onAddPendingPlace(place) }) { Text("加入") }
                                                TextButton(onClick = { onRemovePendingPlace(place) }) { Text("忽略") }
                                            }
                                        }
                                    }
                                }
                            }
                            ElevatedCard(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("校区中心与手动位置", fontWeight = FontWeight.SemiBold)
                                    Text("校区中心（POI 搜索范围）", fontWeight = FontWeight.SemiBold)
                                    var centerLatText by remember(campusCenter) { mutableStateOf(campusCenter.lat.toString()) }
                                    var centerLngText by remember(campusCenter) { mutableStateOf(campusCenter.lng.toString()) }
                                    var centerCityText by remember(campusCenter) { mutableStateOf(campusCenter.city) }
                                    fun commitCenter() {
                                        val lat = centerLatText.trim().toDoubleOrNull()
                                        val lng = centerLngText.trim().toDoubleOrNull()
                                        if (lat != null && lng != null && lat in -90.0..90.0 && lng in -180.0..180.0 && centerCityText.isNotBlank()) {
                                            onCampusCenterChange(CampusCenter(lat, lng, centerCityText.trim()))
                                        }
                                    }
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedTextField(value = centerLatText, onValueChange = { centerLatText = it; commitCenter() }, label = { Text("纬度") }, singleLine = true, modifier = Modifier.weight(1f))
                                        OutlinedTextField(value = centerLngText, onValueChange = { centerLngText = it; commitCenter() }, label = { Text("经度") }, singleLine = true, modifier = Modifier.weight(1f))
                                    }
                                    OutlinedTextField(value = centerCityText, onValueChange = { centerCityText = it; commitCenter() }, label = { Text("校区所在城市") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                                    Text("POI 搜索以这里为中心（3 公里内），空结果再按城市全城搜索；默认浙大紫金港 · 杭州，其他学校请改成自己学校。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    HorizontalDivider()
                                    Text("手动当前位置", fontWeight = FontWeight.SemiBold)
                                    OutlinedButton(onClick = { choosingCurrentPlace = true }, modifier = Modifier.fillMaxWidth()) {
                                        Text(currentCampusPlace?.let { "当前位置：$it" } ?: "选择当前位置")
                                    }
                                    if (currentCampusPlace != null) {
                                        OutlinedButton(onClick = { choosingDestination = true }, modifier = Modifier.fillMaxWidth()) {
                                            Text(previewDestination?.let { "预览目的地：$it" } ?: "选择预览目的地")
                                        }
                                        val from = campusPlaces.firstOrNull { it.name == currentCampusPlace }
                                        val to = campusPlaces.firstOrNull { it.name == previewDestination }
                                        if (from != null && to != null) {
                                            val key = ZijingangTravel.routeKey(from.zone, to.zone, commuteProfile.campusMode)
                                            val minutes = ZijingangTravel.estimateMinutes(from.zone, to.zone, commuteProfile)
                                            val calibrated = ZijingangTravel.calibratedMinutes(from.zone, to.zone, commuteProfile)
                                            val observations = commuteProfile.routeObservations[key].orEmpty()
                                            Text("${from.name} → ${to.name}：按${commuteProfile.campusMode}${if (calibrated == null) "估计" else "学习后"}约 $minutes 分钟（含楼内缓冲）。", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                                            if (observations.isNotEmpty()) Text("基于 ${observations.size} 次确认记录：${observations.takeLast(5).joinToString("、")} 分钟${if (observations.size > 5) "（显示最近 5 次）" else ""}。", style = MaterialTheme.typography.labelSmall)
                                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                TextButton(onClick = { routeCalibrationTarget = Pair(from, to) }) { Text("记录本次耗时") }
                                                if (observations.isNotEmpty()) TextButton(onClick = { onCommuteChange(CommuteLearning.undoLatest(commuteProfile, key)) }) { Text("撤销最近记录") }
                                            }
                                            if (calibrated != null) TextButton(onClick = { onCommuteChange(CommuteLearning.clear(commuteProfile, key)) }) { Text("清除该路线学习") }
                                        }
                                        TextButton(onClick = { onCurrentCampusPlaceChange(null) }) { Text("清除当前位置") }
                                    }
                                }
                            }
                        }
                    }
                    SettingsSubPage.TUTORIAL_SEARCH -> {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            HelpToggleButton(onClick = { helpBlock = SettingsBlock.TUTORIAL_SEARCH })
                        }
                        SettingSwitch(
                            "教程联网搜索",
                            "为学习目标从网上搜集候选教程并比较来源；使用你填写的硅基流动 key，仅发往 api.siliconflow.cn",
                            tutorialSearch.enabled,
                            { enabled -> onTutorialSearchSettingsChange(tutorialSearch.copy(enabled = enabled)) }
                        )
                        if (tutorialSearch.enabled) {
                            OutlinedTextField(
                                value = tutorialSearch.apiKey,
                                onValueChange = { onTutorialSearchSettingsChange(tutorialSearch.copy(apiKey = it)) },
                                label = { Text("硅基流动 API key") },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text("常用模型（点选即切换，也可手填）", style = MaterialTheme.typography.labelMedium)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                                TUTORIAL_MODEL_PRESETS.forEach { (id, label) ->
                                    FilterChip(
                                        selected = tutorialSearch.model == id,
                                        onClick = { onTutorialSearchSettingsChange(tutorialSearch.copy(model = id)) },
                                        label = { Text(label) }
                                    )
                                }
                            }
                            OutlinedTextField(
                                value = tutorialSearch.model,
                                onValueChange = { onTutorialSearchSettingsChange(tutorialSearch.copy(model = it)) },
                                label = { Text("模型名（硅基流动可用模型 ID）") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(if (tutorialSearch.apiKey.isBlank()) "填写 key 后，在“目标与执行”里可为学习目标生成学习路径建议。" else "key 已保存本机；在“目标与执行”里可生成学习路径建议。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            HorizontalDivider()
                            Text("视频分析模型（一站式整理视频教程）", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text("在“计划 → 资料工具箱 → 视频分析”里确认视频链接或材料后生成候选要点并保存；模型模式同前（免费/推荐/自定义）。", style = MaterialTheme.typography.bodySmall)
                            Text("常用模型（点选即切换，也可手填）", style = MaterialTheme.typography.labelMedium)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                                VIDEO_ANALYSIS_MODEL_PRESETS.forEach { (id, label) ->
                                    FilterChip(
                                        selected = videoAnalysisModel == id,
                                        onClick = { onVideoAnalysisModelChange(id) },
                                        label = { Text(label) }
                                    )
                                }
                            }
                            OutlinedTextField(
                                value = videoAnalysisModel,
                                onValueChange = onVideoAnalysisModelChange,
                                label = { Text("视频分析模型名（硅基流动可用模型 ID）") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                    SettingsSubPage.COURSE_VISION -> {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            HelpToggleButton(onClick = { helpBlock = SettingsBlock.COURSE_VISION })
                        }
                        SettingSwitch(
                            "课表识别用硅基流动视觉模型",
                            "开启后，导入课表截图时改用视觉模型识别，识别失败自动回退本机；图片只发往 api.siliconflow.cn",
                            courseVision.enabled,
                            { enabled -> onCourseVisionSettingsChange(courseVision.copy(enabled = enabled)) }
                        )
                        if (courseVision.enabled) {
                            OutlinedTextField(
                                value = tutorialSearch.apiKey,
                                onValueChange = { onTutorialSearchSettingsChange(tutorialSearch.copy(apiKey = it)) },
                                label = { Text("硅基流动 API key（与教程搜索共用）") },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                modifier = Modifier.fillMaxWidth()
                            )
                            TextButton(onClick = { onCourseVisionGuideOpenChange(true) }) { Text("如何获取 key（新用户 2 分钟）") }
                            Text("常用模型（点选即切换，也可在下面手填）", style = MaterialTheme.typography.labelMedium)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                                VISION_MODEL_PRESETS.forEach { (id, label) ->
                                    FilterChip(
                                        selected = courseVision.model == id,
                                        onClick = { onCourseVisionSettingsChange(courseVision.copy(model = id)) },
                                        label = { Text(label) }
                                    )
                                }
                            }
                            OutlinedTextField(
                                value = courseVision.model,
                                onValueChange = { onCourseVisionSettingsChange(courseVision.copy(model = it)) },
                                label = { Text("视觉模型名（硅基流动模型 ID）") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text("默认 Qwen/Qwen3-VL-8B-Instruct（在线免费，识别课表足够）；识别率不满意可换 Qwen/Qwen3-VL-32B-Instruct（是否计费以硅基流动为准）。旧版 Qwen2.5-VL 系列已下线，保存过旧模型名会自动迁移。key 仅存本机，只发往 api.siliconflow.cn，关闭开关后导入课表不再联网。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    SettingsSubPage.APP_DETECTION -> {
                        val context = LocalContext.current
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            HelpToggleButton(onClick = { helpBlock = SettingsBlock.APP_DETECTION })
                        }
                        // 从系统“使用情况访问”设置页返回后刷新状态。
                        val lifecycleOwner = LocalLifecycleOwner.current
                        var usageGranted by remember { mutableStateOf(AppLibrary.hasUsageAccess(context)) }
                        DisposableEffect(lifecycleOwner) {
                            val observer = LifecycleEventObserver { _, event ->
                                if (event == Lifecycle.Event.ON_RESUME) usageGranted = AppLibrary.hasUsageAccess(context)
                            }
                            lifecycleOwner.lifecycle.addObserver(observer)
                            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                        }
                        SettingSwitch(
                            "前台应用检测",
                            "开启后，游戏安排到点时识别前台应用：还在玩游戏类应用就提醒收尾；未授权时只提醒不检测",
                            gameDetectionEnabled,
                            onGameDetectionEnabledChange
                        )
                        if (gameDetectionEnabled) {
                            if (!usageGranted) {
                                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f))) {
                                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text("未授予“使用情况访问”", fontWeight = FontWeight.SemiBold)
                                        Text("到系统设置开启后，才能识别当前前台应用（判断只在本机完成，不上传）。", style = MaterialTheme.typography.bodySmall)
                                        Button(onClick = { AppLibrary.openUsageAccessSettings(context) }) { Text("去系统开启") }
                                    }
                                }
                            } else {
                                Text("已授予使用情况访问；到点会识别前台应用是否属于游戏类。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                        HorizontalDivider()
                        Text("应用分类", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("按本机已安装应用生成清单：内置常见应用归类 + 应用名自动识别；识别不对的可手动归类。分类只用于收尾提醒判断。", style = MaterialTheme.typography.bodySmall)
                        var addAppOpen by remember { mutableStateOf(false) }
                        TextButton(onClick = { addAppOpen = true }) { Text("＋ 添加本机应用") }
                        if (addAppOpen) AddInstalledAppDialog(
                            onDismiss = { addAppOpen = false },
                            onSave = { pkg, category -> onAppCategoriesChange(appCategories + (pkg to category)) }
                        )
                        var installedApps by remember { mutableStateOf(emptyList<Triple<String, String, AppCategory>>()) }
                        LaunchedEffect(context, appCategories, hiddenApps) {
                            onGlobalLoadingChange(true)
                            installedApps = withContext(Dispatchers.IO) { categorizedInstalledApps(context, appCategories).filterNot { it.first in hiddenApps } }
                            onGlobalLoadingChange(false)
                        }
                        Text("本机共扫描到 ${installedApps.size} 个应用；内置常见应用归类 + 应用名自动识别，识别不对或未识别的可手动归类。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        var categorizedExpanded by remember { mutableStateOf(true) }
                        var uncategorizedExpanded by remember { mutableStateOf(false) }
                        var hiddenExpanded by remember { mutableStateOf(false) }
                        val categorizedApps = installedApps.filter { it.third != AppCategory.UNKNOWN }
                        Row(Modifier.fillMaxWidth().clickable { categorizedExpanded = !categorizedExpanded }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("已分类应用（${categorizedApps.size}）", fontWeight = FontWeight.SemiBold)
                            Text(if (categorizedExpanded) "收起 ▴" else "展开 ▾", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        }
                        if (categorizedExpanded) {
                            AppCategory.entries.filter { it != AppCategory.UNKNOWN }.forEach { category ->
                                val apps = categorizedApps.filter { it.third == category }
                                if (apps.isNotEmpty()) {
                                    Text("${category.label}（${apps.size}）", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                                    apps.forEach { (pkg, label, _) ->
                                        Card {
                                            Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                                    Column(Modifier.weight(1f)) {
                                                        Text(label, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
                                                        Text(pkg, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                    }
                                                    if (appCategories.containsKey(pkg)) TextButton(onClick = { onAppCategoriesChange(appCategories - pkg) }) { Text("恢复自动") }
                                                    TextButton(onClick = { onToggleHiddenApp(pkg) }) { Text("忽略", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                                }
                                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                                                    AppCategory.entries.filter { it != AppCategory.UNKNOWN }.forEach { c ->
                                                        FilterChip(
                                                            selected = appCategories[pkg] == c.name || (!appCategories.containsKey(pkg) && category == c),
                                                            onClick = { onAppCategoriesChange(appCategories + (pkg to c.name)) },
                                                            label = { Text(c.label) }
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        val unknownApps = installedApps.filter { it.third == AppCategory.UNKNOWN }
                        if (unknownApps.isNotEmpty()) {
                            Row(Modifier.fillMaxWidth().clickable { uncategorizedExpanded = !uncategorizedExpanded }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("待分类应用（${unknownApps.size}）", fontWeight = FontWeight.SemiBold)
                                Text(if (uncategorizedExpanded) "收起 ▴" else "展开 ▾", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            }
                            if (uncategorizedExpanded) {
                                Text("没有自动识别出分类；给它们归类后，到点检测才会把它们算作游戏/视频等。", style = MaterialTheme.typography.bodySmall)
                                unknownApps.forEach { (pkg, label, _) ->
                                    Card {
                                        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                                Column(Modifier.weight(1f)) {
                                                    Text(label, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
                                                    Text(pkg, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                }
                                                TextButton(onClick = { onToggleHiddenApp(pkg) }) { Text("忽略", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                            }
                                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                                                listOf(AppCategory.GAME, AppCategory.VIDEO, AppCategory.SOCIAL, AppCategory.STUDY, AppCategory.OTHER).forEach { c ->
                                                    FilterChip(selected = false, onClick = { onAppCategoriesChange(appCategories + (pkg to c.name)) }, label = { Text(c.label) })
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        if (hiddenApps.isNotEmpty()) {
                            Row(Modifier.fillMaxWidth().clickable { hiddenExpanded = !hiddenExpanded }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("已忽略应用（${hiddenApps.size}）", fontWeight = FontWeight.SemiBold)
                                Text(if (hiddenExpanded) "收起 ▴" else "展开 ▾", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            }
                            if (hiddenExpanded) {
                                hiddenApps.sortedBy { AppLibrary.appLabel(context, it) }.forEach { pkg ->
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                        Column(Modifier.weight(1f)) {
                                            Text(AppLibrary.appLabel(context, pkg), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
                                            Text(pkg, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        TextButton(onClick = { onToggleHiddenApp(pkg) }) { Text("恢复") }
                                    }
                                }
                            }
                        }
                        Text("分类会记住你的选择；未设置的应用按内置清单或应用名自动识别。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    SettingsSubPage.STABILITY -> {
                        val context = LocalContext.current
                        var crashText by remember { mutableStateOf(CrashReporter.read(context)) }
                        Text("崩溃只记录在本机（filesDir/crash.log），不上传、不引第三方 SDK；把崩溃栈发给我即可定位。", style = MaterialTheme.typography.bodySmall)
                        if (crashText.isBlank()) {
                            Text("暂无崩溃记录。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = {
                                    val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("focusflow_crash", crashText))
                                    android.widget.Toast.makeText(context, "已复制崩溃记录", android.widget.Toast.LENGTH_SHORT).show()
                                }) { Text("复制") }
                                OutlinedButton(onClick = {
                                    CrashReporter.clear(context)
                                    crashText = CrashReporter.read(context)
                                }) { Text("清空", color = MaterialTheme.colorScheme.error) }
                            }
                            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))) {
                                Text(crashText.takeLast(4000), Modifier.fillMaxWidth().padding(10.dp), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
    helpBlock?.let { block ->
        HelpDialog(
            title = block.title,
            sections = listOfNotNull(HelpCatalog.settings[block]),
            onDismiss = { helpBlock = null },
            dismissButton = if (block == SettingsBlock.COURSE_VISION) {
                { TextButton(onClick = { helpBlock = null; onCourseVisionGuideOpenChange(true) }) { Text("如何获取 key") } }
            } else null
        )
    }
    if (courseVisionGuideOpen) CourseVisionKeyGuideDialog(onDismiss = { onCourseVisionGuideOpenChange(false) })
    if (baselineVariantNameOpen) {
        var variantName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { onBaselineVariantNameOpenChange(false) },
            title = { Text("另存当前方案") },
            text = { OutlinedTextField(value = variantName, onValueChange = { variantName = it }, label = { Text("方案名称，如“假期·早睡版”") }, singleLine = true) },
            confirmButton = {
                Button(enabled = variantName.isNotBlank(), onClick = { onSaveBaselineVariant(variantName.trim()); onBaselineVariantNameOpenChange(false) }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { onBaselineVariantNameOpenChange(false) }) { Text("取消") } }
        )
    }
    if (dayGroupWizardOpen) DayGroupWizardDialog(
        existingGroups = baselineProfile.dayGroups,
        defaultWake = baselineProfile.wakeMinute,
        defaultSleep = baselineProfile.sleepMinute,
        defaultMeals = baselineProfile.meals,
        onDismiss = { dayGroupWizardOpen = false },
        onSave = { groups ->
            onDayGroupsChange(groups)
            dayGroupWizardOpen = false
        }
    )
    if (choosingCurrentPlace) CampusPlacePickerDialog(
        title = "选择当前位置",
        places = campusPlaces,
        selectedName = currentCampusPlace,
        onDismiss = { choosingCurrentPlace = false },
        onSelect = { selected -> onCurrentCampusPlaceChange(selected.name); choosingCurrentPlace = false }
    )
    if (choosingDestination) CampusPlacePickerDialog(
        title = "选择预览目的地",
        places = campusPlaces,
        selectedName = previewDestination,
        onDismiss = { choosingDestination = false },
        onSelect = { selected -> previewDestination = selected.name; choosingDestination = false }
    )
    if (campusMapHelpOpen) CampusMapHelpDialog(onDismiss = { campusMapHelpOpen = false })
    routeCalibrationTarget?.let { (from, to) ->
        RouteCalibrationDialog(
            from = from,
            to = to,
            mode = commuteProfile.campusMode,
            currentMinutes = ZijingangTravel.estimateMinutes(from.zone, to.zone, commuteProfile),
            history = commuteProfile.routeObservations[ZijingangTravel.routeKey(from.zone, to.zone, commuteProfile.campusMode)].orEmpty(),
            onDismiss = { routeCalibrationTarget = null },
            onSave = { minutes ->
                val key = ZijingangTravel.routeKey(from.zone, to.zone, commuteProfile.campusMode)
                onCommuteChange(CommuteLearning.record(commuteProfile, key, minutes))
                recordBaselineEvent(BaselineEventType.COMMUTE_CONFIRMED, "${from.name} → ${to.name} · ${commuteProfile.campusMode} · $minutes 分钟")
                routeCalibrationTarget = null
            }
        )
    }
    }
}


@Composable private fun SettingSwitch(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) { Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.SemiBold); Text(detail) }; Switch(checked = checked, onCheckedChange = onChange) } }

/** 添加本机应用：搜索已安装应用 → 选择分类（自动识别提示，可改）。 */

