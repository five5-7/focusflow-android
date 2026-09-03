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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.luminance
import androidx.core.view.WindowCompat
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
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
        statusCheckInRequested = intent.getBooleanExtra(ReminderReceiver.EXTRA_OPEN_STATUS_CHECK_IN, false) &&
            PrototypeStore(this).loadStatusCheckInSettings().enabled
        quickCaptureRequested = intent.getBooleanExtra(ReminderReceiver.EXTRA_OPEN_QUICK_CAPTURE, false)
        mealPromptRequested = validMealPrompt(intent)
        mealFinishRequested = validMealFinish(intent)
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
            statusCheckInRequested = PrototypeStore(this).loadStatusCheckInSettings().enabled
        }
        if (intent.getBooleanExtra(ReminderReceiver.EXTRA_OPEN_MEAL_PROMPT, false)) mealPromptRequested = validMealPrompt(intent)
        if (intent.getBooleanExtra(ReminderReceiver.EXTRA_OPEN_MEAL_FINISH, false)) mealFinishRequested = validMealFinish(intent)
        if (intent.getBooleanExtra(ReminderReceiver.EXTRA_OPEN_QUICK_CAPTURE, false)) quickCaptureRequested = true
    }

    private fun validMealPrompt(intent: Intent): MealType? {
        if (!intent.getBooleanExtra(ReminderReceiver.EXTRA_OPEN_MEAL_PROMPT, false)) return null
        val type = MealType.fromLabel(intent.getStringExtra(ReminderReceiver.EXTRA_MEAL_TYPE).orEmpty()) ?: return null
        val expectedAt = intent.getLongExtra(ReminderReceiver.EXTRA_MEAL_PLANNED_AT, -1L)
        val store = PrototypeStore(this)
        val now = System.currentTimeMillis()
        return type.takeIf {
            MealReminderFreshness.promptAllowed(
                store.loadMealReminderEnabled(), store.loadBaselineProfile().lifeStage != null, expectedAt, now,
                MealLearning.startedToday(store.loadMealRecords(), now, it),
                "${MealLearning.dayKey(now)}:${it.label}" in store.loadMealSkipDays()
            )
        }
    }

    private fun validMealFinish(intent: Intent): MealType? {
        if (!intent.getBooleanExtra(ReminderReceiver.EXTRA_OPEN_MEAL_FINISH, false)) return null
        val type = MealType.fromLabel(intent.getStringExtra(ReminderReceiver.EXTRA_MEAL_TYPE).orEmpty()) ?: return null
        val expectedId = intent.getLongExtra(ReminderReceiver.EXTRA_MEAL_RECORD_ID, -1L)
        val record = MealLearning.latestOpen(PrototypeStore(this).loadMealRecords(), type) ?: return null
        val store = PrototypeStore(this)
        return type.takeIf { MealReminderFreshness.endAllowed(store.loadMealReminderEnabled(), store.loadMealDurationTrackingEnabled(), record, expectedId, System.currentTimeMillis()) }
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
    var foregroundDetectionTrace by remember { mutableStateOf(store.loadForegroundDetectionTrace()) }
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
    var statusPromptTrace by remember { mutableStateOf(store.loadStatusPromptTrace()) }
    var nextStatusPromptAt by remember { mutableLongStateOf(store.loadNextStatusPromptAt()) }
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
    LaunchedEffect(Unit) {
        if (store.loadSleepDataEnabled()) {
            runCatching {
                val source = HealthConnectSleepDataSource(context)
                source.readLastMainSleep()?.let(store::saveSleepSummary)
            }
        }
    }
    DisposableEffect(appLifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) {
                notificationForegroundCheck++
                // 通知栏里完成/最低版本/延后/跳过是后台 Receiver 写的，回到前台时重读事件，让今日统计与记录卡同步。
                taskEvents = store.loadTaskEvents()
                statusPromptTrace = store.loadStatusPromptTrace()
                nextStatusPromptAt = store.loadNextStatusPromptAt()
                foregroundDetectionTrace = store.loadForegroundDetectionTrace()
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
    var energyRecordedAt by remember { mutableLongStateOf(store.loadEnergyRecordedAt()) }
    val planningEnergyLevel = if (StatusFreshnessPolicy.isCurrent(energyRecordedAt)) energyLevel else "正常"
    var commuteProfile by remember { mutableStateOf(store.loadCommuteProfile()) }
    var campusLifeEnabled by remember { mutableStateOf(store.loadCampusLifeEnabled()) }
    var hiddenPlaces by remember { mutableStateOf(store.loadHiddenPlaces()) }
    var campusMapPackage by remember { mutableStateOf(store.loadCampusMapPackage()) }
    var currentCampusPlace by remember { mutableStateOf(store.loadCurrentCampusPlace()) }
    var customPlaces by remember { mutableStateOf(store.loadCustomPlaces()) }
    var amapKey by remember { mutableStateOf(store.loadAmapKey()) }
    var campusCenter by remember { mutableStateOf(store.loadCampusCenter()) }
    var tutorialSearch by remember { mutableStateOf(store.loadTutorialSearchSettings()) }
    var aiWeeklySummary by remember { mutableStateOf(store.loadAiWeeklySummarySettings()) }
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
    var mealDurationTrackingEnabled by remember { mutableStateOf(store.loadMealDurationTrackingEnabled()) }
    var mealSkipDays by remember { mutableStateOf(store.loadMealSkipDays()) }
    var mealPromptOpen by remember { mutableStateOf<MealType?>(null) }
    var mealFinishOpen by remember { mutableStateOf<MealType?>(null) }
    var mealRecordsOpen by remember { mutableStateOf(false) }
    var planPage by remember { mutableStateOf<PlanPage?>(null) }
    var settingsSubPage by remember { mutableStateOf<SettingsSubPage?>(null) }
    var settingsBackStack by remember { mutableStateOf<List<SettingsSubPage>>(emptyList()) }
    LaunchedEffect(
        notificationForegroundCheck,
        permissionOnboardingPending,
        baselineOnboardingOpen,
        baselineWhereToFindOpen,
        featureIntroOpen
    ) {
        if (notificationForegroundCheck == 0 || permissionOnboardingPending || baselineOnboardingOpen || baselineWhereToFindOpen || featureIntroOpen) return@LaunchedEffect
        delay(500)
        val message = NotificationHealthPolicy.startupMessage(NotificationChannelSettings.health(context), mealReminderEnabled) ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(message = message, actionLabel = "查看说明", withDismissAction = true)
        if (result == SnackbarResult.ActionPerformed) {
            tab = 3
            todayInboxOpen = false
            planPage = null
            settingsBackStack = emptyList()
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
    /** 统一处理识别结果：去重、保留冲突为待确认课程并生成提示（计算在 CourseSchedule，只保留保存/状态副作用）。 */
    fun applyRecognizedCourses(recognized: List<Course>) {
        val merge = mergeRecognizedCourses(courses, recognized)
        // 与已确认课程冲突的识别结果也保留为待确认：应用已有冲突警示机制，由用户决定确认/编辑/忽略。
        if (merge.added.isNotEmpty()) {
            val updated = courses + merge.added
            courses = updated
            store.saveCourses(updated)
        }
        courseImportMessage = merge.message
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

    /**
     * 任务离开日程时同步移除同 id 的空闲活动会话与全部闹钟。
     * 以会话是否存在为准，兼容旧版改期曾把活动 kind 误写为“任务”的数据。
     */
    fun removeScheduledActivity(itemId: Long) {
        val stored = store.loadGameSessions()
        if (stored.none { it.id == itemId }) return
        gameSessions = ScheduledActivitySessions.remove(stored, itemId)
        store.saveGameSessions(gameSessions)
        ReminderScheduler.cancelGameReminders(context, itemId)
    }

    /** 放回收集箱：清掉时间与范围，保留原调度日记忆；三处共用（回收卡 / 快速改期建议 / 时间轴弹窗）。 */
    fun returnToInbox(item: Item) {
        val result = TaskActions.returnToInbox(items, item)
        saveItems(result.items)
        recordTaskEvent(result.event!!)
        removeScheduledActivity(item.id)
    }
    /** 改期保存的完整动作：数据变换在 TaskActions，事件/基线/提醒/游戏会话同步在 FApp 层（原 saveDelayedItem）。 */
    fun applyDelayed(item: Item, scheduledAt: Long, duration: Int, label: String, priority: String) {
        val storedSessions = store.loadGameSessions()
        val scheduledActivity = storedSessions.firstOrNull { it.id == item.id && it.isOpen() }
        // 兼容 7.1.3 以前活动改期后被误写成普通任务的记录。
        val source = if (scheduledActivity != null && item.kind !in setOf("活动", "游戏")) item.copy(kind = "活动") else item
        val plan = TaskActions.planDelayed(items, source, scheduledAt, duration, label, priority)
        saveItems(plan.items)
        store.appendBaselineEvent(BaselineRecorder.event(BaselineEventType.TASK_RESCHEDULED, plan.baselinePayload))
        recordTaskEvent(plan.event)
        ReminderScheduler.scheduleTaskReminder(context, plan.delayedItem)
        if (scheduledActivity != null) {
            gameSessions = ScheduledActivitySessions.reschedule(storedSessions, item.id, scheduledAt, duration)
            store.saveGameSessions(gameSessions)
            gameSessions.firstOrNull { it.id == item.id && it.isOpen() }?.let {
                ReminderScheduler.cancelGameReminders(context, item.id)
                ReminderScheduler.scheduleGameReminders(context, it)
            }
        }
    }
    /** 目标任务安排（算法建议点击与自定义时间共用）：写入日程、记事件、建提醒。 */
    val scheduleGoalItem = { goal: Goal, at: Long ->
        val weekday = todayWeekday(at)
        val startMinute = minuteOfDay(at)
        val scheduled = Item(title = goal.title, detail = goalTaskDetail(goal, at), kind = "任务", scheduledAt = at, goalId = goal.id, durationMinutes = goal.durationMinutes)
        saveItems(listOf(scheduled) + items)
        store.appendBaselineEvent(BaselineRecorder.event(BaselineEventType.TASK_SCHEDULED, "${goal.title} · ${weekdayName(weekday)} ${GoalPlanner.displayTime(startMinute)}"))
        recordTaskEvent(TaskRecorder.event(TaskEventType.TASK_SCHEDULED, scheduled.id, scheduled.title, scheduledAt = at))
        ReminderScheduler.scheduleTaskReminder(context, scheduled)
        scope.launch { snackbarHostState.showSnackbar("已把《${goal.title}》排到 ${weekdayName(weekday)} ${GoalPlanner.displayTime(startMinute)}") }
    }
    fun selectTab(index: Int) {
        // Reset only the destination. The outgoing page must survive its exit animation.
        if (index == 0) todayInboxOpen = false
        if (index == 2) planPage = null
        if (index == 3) {
            settingsSubPage = null
            settingsBackStack = emptyList()
        }
        tab = index
    }
    BackHandler(enabled = tab == 0 && todayInboxOpen) { todayInboxOpen = false }
    BackHandler(enabled = tab == 2 && planPage != null) { planPage = null }
    BackHandler(enabled = tab == 3 && settingsSubPage != null) {
        settingsSubPage = settingsBackStack.lastOrNull()
        settingsBackStack = settingsBackStack.dropLast(1)
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
        val window = (context as? android.app.Activity)?.window
        SideEffect {
            window?.let {
                if (Build.VERSION.SDK_INT >= 29) it.isNavigationBarContrastEnforced = false
                WindowCompat.getInsetsController(it, it.decorView).apply {
                    isAppearanceLightNavigationBars = themeSpec.colorScheme.background.luminance() > 0.5f
                    isAppearanceLightStatusBars = themeSpec.colorScheme.background.luminance() > 0.5f
                }
            }
        }
        val density = LocalDensity.current
        val keyboardVisible = WindowInsets.ime.getBottom(density) > 0
        var floatingBarHeight by remember { mutableStateOf(112.dp) }
        Box(Modifier.fillMaxSize().imePadding()) {
        // Horizontal cutouts constrain the viewport. Top safety travels with scroll content.
        val safeContentInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout)
        val topSafety = safeContentInsets.asPaddingValues().calculateTopPadding()
        val hasTopNotice = StorageProtection.readOnly || globalLoading
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = safeContentInsets.only(WindowInsetsSides.Horizontal),
            snackbarHost = { SnackbarHost(snackbarHostState, Modifier.padding(bottom = floatingBarHeight)) },
            topBar = {
                if (hasTopNotice) {
                Column(Modifier.fillMaxWidth().windowInsetsPadding(
                    safeContentInsets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
                )) {
                    if (StorageProtection.readOnly) Surface(color = MaterialTheme.colorScheme.errorContainer) {
                        Column(Modifier.fillMaxWidth().padding(12.dp)) {
                            Text("数据保护：损坏数据尚未备份，已暂停保存。当前操作不会写入；请释放存储空间后重试，并重新打开应用。")
                            TextButton(onClick = { StorageProtection.retry() }) { Text("重试备份") }
                        }
                    }
                    if (globalLoading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                }
            },
        ) { padding ->
            CompositionLocalProvider(
                LocalFloatingBottomPadding provides if (keyboardVisible) 0.dp else floatingBarHeight,
                LocalScrollingTopPadding provides if (hasTopNotice) 0.dp else topSafety
            ) {
            // Applied and consumed once for both root pages and their animated children.
            val pageModifier = Modifier.padding(padding).consumeWindowInsets(padding)
                .padding(bottom = if (keyboardVisible) floatingBarHeight else 0.dp)
            // 假期阶段不把课程当作日程：日程/今日摘要/空挡/目标建议均不显示课程（课程管理页仍保留）。
            val scheduleCourses = if (baselineProfile.lifeStage == LifeStage.HOLIDAY) emptyList<Course>() else courses
            Box(pageModifier) {
            // Equal depth means a sibling cross-fade, never a hierarchical slide.
            SubpageMotion(tab, depth = { 0 }) { visibleTab ->
            val pageModifier = Modifier.fillMaxSize()
            when (visibleTab) {
                0 -> TodayScreen(
                    pageModifier, items,
                    inboxOpen = todayInboxOpen,
                    onInboxOpenChange = { todayInboxOpen = it },
                    energyLevel = energyLevel,
                    energyRecordedAt = energyRecordedAt,
                    onEnergyLevelChange = { updated ->
                        val recordedAt = System.currentTimeMillis()
                        energyLevel = updated
                        energyRecordedAt = recordedAt
                        store.saveEnergyLevel(updated, recordedAt)
                    },
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
                            val result = TaskActions.completeNow(items, item)
                            saveItems(result.items)
                            recordTaskEvent(result.event!!)
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
                        val result = TaskActions.shrinkToInbox(items, item)
                        saveItems(result.items)
                        recordTaskEvent(result.event!!)
                        removeScheduledActivity(item.id)
                    },
                    onReturnToInbox = { item -> returnToInbox(item) },
                    onApplyAdjustment = { item, adjustment ->
                        when (adjustment.action) {
                            AdjustAction.BACK_TO_INBOX -> returnToInbox(item)
                            AdjustAction.REARRANGE -> rescheduleTarget = item
                            else -> adjustment.targetTime?.let { at ->
                                applyDelayed(item, at, adjustment.durationMinutes, "${formatDateTime(at)} · ${adjustment.durationMinutes}分钟", item.priority)
                            }
                        }
                    },
                    onPause = { item ->
                        val result = TaskActions.pause(items, item)
                        saveItems(result.items)
                        removeScheduledActivity(item.id)
                    },
                    onAbandon = { item ->
                        val result = TaskActions.abandon(items, item)
                        saveItems(result.items)
                        recordTaskEvent(result.event!!)
                        removeScheduledActivity(item.id)
                    },
                    baselineEvents = store.loadBaselineEvents(500),
                    taskEvents = taskEvents,
                    mealRecords = mealRecords,
                    mealReminderEnabled = mealReminderEnabled,
                    statusCheckInEnabled = statusCheckInSettings.enabled,
                    onEnableStatusCheckIn = {
                        val updated = statusCheckInSettings.copy(enabled = true)
                        statusCheckInSettings = updated
                        store.saveStatusCheckInSettings(updated)
                        ReminderScheduler.scheduleDailyStatusCheckIn(context, updated)
                        nextStatusPromptAt = store.loadNextStatusPromptAt()
                        scope.launch { snackbarHostState.showSnackbar("已开启每日精力询问，预计 ${formatDateTime(nextStatusPromptAt)}") }
                    },
                    windDownEnabled = windDownEnabled,
                    baselineProfile = baselineProfile,
                    courses = scheduleCourses,
                    mealSkipDays = mealSkipDays,
                    onMealPrompt = { mealPromptOpen = it },
                    onMealFinish = { mealFinishOpen = it }
                )
                1 -> ScheduleScreen(
                    pageModifier, items, scheduleCourses, commuteProfile,
                    energyLevel = planningEnergyLevel,
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
                            val result = TaskActions.completeNow(items, item)
                            saveItems(result.items)
                            recordTaskEvent(result.event!!)
                        } else completionTarget = item
                    },
                    onDeleteItem = { item ->
                        val result = TaskActions.deleteItem(items, item)
                        saveItems(result.items)
                        recordTaskEvent(result.event!!)
                        removeScheduledActivity(item.id)
                    }
                )
                2 -> PlansScreen(
                    pageModifier, items, courses, commuteProfile, baselineProfile.lifeStage,
                    page = planPage,
                                    onPageChange = { planPage = it; if (it == PlanPage.REVIEW) gameSessions = store.loadGameSessions(); if (it == PlanPage.HISTORY) taskEvents = store.loadTaskEvents() },
                    onResume = { item ->
                        val result = TaskActions.resume(items, item)
                        saveItems(result.items)
                        recordTaskEvent(result.event!!)
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
                            courseImportMessage = "请先在 设置 → 高级工具 → 课表识别（视觉模型）开启并填写硅基流动 key，再导入课表截图。"
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
                        val scheduled = item.copy(kind = "任务", scheduledAt = target, dayOnly = false, windowStartAt = null, windowEndAt = null, detail = TaskScheduleText.scheduledDetail(target, item.durationMinutes))
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
                        val plan = GoalPlanner.autoPlan(
                            goals, scheduleCourses, items, commuteProfile,
                            completionRate = { weekday, startHour -> PlanLearning.completionRate(store, weekday, startHour) }
                        )
                        // 完成率学习排序在纯计算内完成；学习记录发生在保存之前（与原内联顺序一致）。
                        plan.learnedSlots.forEach { (weekday, startHour) -> PlanLearning.recordScheduled(store, weekday, startHour) }
                        if (plan.newItems.isNotEmpty()) {
                            saveItems(plan.newItems + items)
                            plan.newItems.forEach { ReminderScheduler.scheduleTaskReminder(context, it) }
                            plan.newItems.forEach { recordTaskEvent(TaskRecorder.event(TaskEventType.TASK_SCHEDULED, it.id, it.title, scheduledAt = it.scheduledAt ?: 0)) }
                        }
                        autoPlanMessage = plan.message
                    },
                    autoPlanMessage = autoPlanMessage,
                    tutorialSearch = tutorialSearch,
                    aiWeeklySummary = aiWeeklySummary,
                    courseVision = courseVision,
                    onSearchTutorial = { tutorialSearchOpen = true },
                    onVideoAnalysis = { videoAnalysisOpen = true },
                    feedback = feedback,
                    gameSessions = gameSessions,
                    checkIns = statusCheckIns,
                    taskEvents = taskEvents,
                    store = store
                )
                else -> SettingsScreen(pageModifier, settingsScrollState, themeOption, commuteProfile, campusLifeEnabled, campusMapPackage, currentCampusPlace, improvementNotes, activitySettings, statusCheckInSettings, statusPromptTrace = statusPromptTrace, nextStatusPromptAt = nextStatusPromptAt, onStatusPromptTest = {
                    if (!statusCheckInSettings.enabled) {
                        scope.launch { snackbarHostState.showSnackbar("请先开启每日精力询问") }
                    } else {
                        ReminderScheduler.scheduleStatusCheckInTest(context)
                        nextStatusPromptAt = store.loadNextStatusPromptAt()
                        scope.launch { snackbarHostState.showSnackbar("测试询问将在约 1 分钟后触发；返回本页可查看结果") }
                    }
                }, windDownEnabled = windDownEnabled, checkIns = statusCheckIns, baselineProfile = baselineProfile, mealRecords = mealRecords, mealReminderEnabled = mealReminderEnabled,
                    mealDurationTrackingEnabled = mealDurationTrackingEnabled,
                    onMealDurationTrackingEnabledChange = { enabled ->
                        mealDurationTrackingEnabled = enabled
                        store.saveMealDurationTrackingEnabled(enabled)
                        if (!enabled) ReminderScheduler.cancelMealEndReminders(context)
                    },
                    foregroundDetectionTrace = foregroundDetectionTrace,
                    subPage = settingsSubPage, onSubPageChange = { target ->
                    if (target == null) {
                        settingsSubPage = null
                        settingsBackStack = emptyList()
                    } else {
                        settingsBackStack = NavigationMotion.historyAfterOpen(settingsBackStack, settingsSubPage, target)
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
                }, aiWeeklySummary = aiWeeklySummary, onAiWeeklySummarySettingsChange = { updated ->
                    aiWeeklySummary = updated
                    store.saveAiWeeklySummarySettings(updated)
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
                    nextStatusPromptAt = store.loadNextStatusPromptAt()
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
                    if (enabled) ReminderScheduler.scheduleDailyMealReminders(context, baselineProfile) else {
                        mealDurationTrackingEnabled = false
                        store.saveMealDurationTrackingEnabled(false)
                        ReminderScheduler.cancelAllMealReminders(context)
                    }
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
            } // primary destination motion
            } // inset-aware viewport
        } // content padding provider / Scaffold
        FloatingNavigationBar(
            safeInsets = safeContentInsets,
            containerColor = themeSpec.navigationBarColor,
            selectedTab = tab,
            hasSubpage = when (tab) {
                0 -> todayInboxOpen
                2 -> planPage != null
                3 -> settingsSubPage != null
                else -> false
            },
            selectedPageDescription = when (tab) {
                0 -> if (todayInboxOpen) "收集箱" else "今日主页"
                1 -> "日程"
                2 -> planPage?.title ?: "计划主页"
                else -> settingsSubPage?.title ?: "设置主页"
            },
            onSelectTab = { selectTab(it) },
            onAdd = { addMenuOpen = true },
            modifier = Modifier.align(Alignment.BottomCenter).onSizeChanged {
                floatingBarHeight = with(density) { it.height.toDp() }
            }
        )
        if (!hasTopNotice) StatusBarScrim(topSafety, Modifier.align(Alignment.TopCenter))
        } // page with overlaid navigation; no full-width bottom surface
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
                    val tomorrowAt = dateAt(1, 10)
                    Item(title = draft.title, detail = TaskScheduleText.dayOnlyDetail(tomorrowAt), kind = "任务", scheduledAt = tomorrowAt, dayOnly = true)
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
        if (activityOpen) ActivityDialog(suggestedNextStepName, activityPreset, activityHistory, upcomingCommitment, planningEnergyLevel, onDismiss = { activityOpen = false; activityPreset = null }) { category, name, endsAt, nextStep ->
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
            initialEnergy = planningEnergyLevel,
            initialActivity = activeSession?.name.orEmpty(),
            onDismiss = { statusCheckInOpen = false },
            onSave = { selectedEnergy, selectedActivity ->
                val checkIn = StatusCheckIn(selectedEnergy, selectedActivity)
                store.saveStatusCheckIn(checkIn)
                store.appendBaselineEvent(BaselineRecorder.event(BaselineEventType.CHECK_IN_RECORDED, "精力$selectedEnergy · $selectedActivity"))
                energyLevel = selectedEnergy
                energyRecordedAt = checkIn.recordedAt
                latestStatusCheckIn = checkIn
                statusCheckIns = store.loadStatusCheckIns(365)
                statusCheckInOpen = false
            }
        )
        if (activityStatusOpen) ActivityStatusDialog(
            initialActivity = activeSession?.name.orEmpty(),
            onDismiss = { activityStatusOpen = false },
            onSave = { selectedActivity, remindMinutes ->
                val checkIn = StatusCheckIn(planningEnergyLevel, selectedActivity)
                store.saveStatusCheckIn(checkIn)
                store.appendBaselineEvent(BaselineRecorder.event(BaselineEventType.CHECK_IN_RECORDED, "精力${checkIn.energy} · $selectedActivity"))
                latestStatusCheckIn = checkIn
                energyLevel = checkIn.energy
                energyRecordedAt = checkIn.recordedAt
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
            applyDelayed(item, scheduledAt, duration, label, priority)
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
            energyLevel = planningEnergyLevel,
            onDismiss = { inboxScheduleTarget = null; schedulePresetExact = null },
            onSchedule = { startsAt, duration, label, priority ->
                val isNew = items.none { it.id == item.id } // 「直接安排」流：新项未落盘，确认时才创建
                val scheduled = TaskActions.scheduledShape(item, startsAt, duration, label, priority)
                saveItems(if (isNew) listOf(scheduled) + items else items.map { if (it.id == item.id) scheduled else it })
                if (isNew) recordTaskEvent(TaskRecorder.event(TaskEventType.TASK_CREATED, scheduled.id, scheduled.title, scheduledAt = 0))
                val persistedTime = formatDateTime(startsAt)
                store.appendBaselineEvent(BaselineRecorder.event(BaselineEventType.TASK_SCHEDULED, "${item.title.removePrefix("重新安排：")} · $persistedTime"))
                recordTaskEvent(TaskRecorder.event(TaskEventType.TASK_SCHEDULED, scheduled.id, scheduled.title, scheduledAt = startsAt, extra = persistedTime))
                ReminderScheduler.scheduleTaskReminder(context, scheduled)
                inboxScheduleTarget = null
                schedulePresetExact = null
            },
            onKeepWindow = { start, end, duration, label, priority ->
                val isNew = items.none { it.id == item.id }
                val flexible = TaskActions.flexibleShape(item, start, end, duration, label, priority)
                saveItems(if (isNew) listOf(flexible) + items else items.map { if (it.id == item.id) flexible else it })
                if (isNew) recordTaskEvent(TaskRecorder.event(TaskEventType.TASK_CREATED, flexible.id, flexible.title, scheduledAt = 0))
                val persistedRange = "${formatDateTime(start)}–${formatDateTime(end)}"
                store.appendBaselineEvent(BaselineRecorder.event(BaselineEventType.TASK_SCHEDULED, "${item.title.removePrefix("重新安排：")} · 弹性范围 $persistedRange"))
                recordTaskEvent(TaskRecorder.event(TaskEventType.TASK_SCHEDULED, flexible.id, flexible.title, scheduledAt = start, extra = "弹性范围 $persistedRange"))
                inboxScheduleTarget = null
                schedulePresetExact = null
            },
            initialExactTime = schedulePresetExact
        ) }
        flexiblePlanTarget?.let { item -> FlexiblePlanDialog(
            item = item,
            items = items,
            courses = courses,
            energyLevel = planningEnergyLevel,
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
        // 自填教学楼自动进入地点库：地点库独立于课程，之后可在地点管理里修改分区/用途（计算在 CampusPlacesEditor）。
        fun ensureCoursePlaceInLibrary(course: Course) {
            val updated = ensurePlaceForCourse(course, campusPlaces, customPlaces) ?: return
            customPlaces = updated
            store.saveCustomPlaces(updated)
        }
        if (addCourseOpen) CourseEditorDialog(null, campusPlaces, onDismiss = { addCourseOpen = false }) { course ->
            courses = courses + course.copy(needsConfirmation = false)
            store.saveCourses(courses)
            ensureCoursePlaceInLibrary(course)
            addCourseOpen = false
        }
        courseEditor?.let { original -> CourseEditorDialog(original, campusPlaces, onDismiss = { courseEditor = null }) { edited ->
            courses = courses.map { if (it == original) edited.copy(needsConfirmation = false) else it }
            store.saveCourses(courses)
            ensureCoursePlaceInLibrary(edited)
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
            val result = TaskActions.convertToGoal(items, item, goal.title)
            saveItems(result.items)
            recordTaskEvent(result.event!!)
            removeScheduledActivity(item.id)
            convertTarget = null
        } }
        attachTarget?.let { item -> AttachToPlanDialog(
            goals = goals,
            onDismiss = { attachTarget = null },
            onAttach = { goal ->
                val result = TaskActions.attachToGoal(items, item, goal)
                saveItems(result.items)
                recordTaskEvent(result.event!!)
                removeScheduledActivity(item.id)
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
            val result = TaskActions.completeWithLevel(items, item, level)
            saveItems(result.items)
            store.appendBaselineEvent(BaselineRecorder.event(BaselineEventType.TASK_COMPLETED, "${item.title} · $level"))
            recordTaskEvent(result.event!!)
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
            events = store.loadBaselineEvents(500),
            onDismiss = { baselineEventsOpen = false },
            onClear = {
                store.clearBaselineEvents()
                baselineEventsOpen = false
            },
            onDelete = { eventId -> store.removeBaselineEvent(eventId) }
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
                    val record = MealRecord(
                        mealType = type,
                        lifeStage = baselineProfile.lifeStage?.storageKey.orEmpty(),
                        startedAt = now,
                        endedAt = if (mealDurationTrackingEnabled) null else now
                    )
                    store.appendMealRecord(record)
                    mealRecords = store.loadMealRecords()
                    val minuteNow = java.util.Calendar.getInstance().apply { timeInMillis = now }.let { it.get(java.util.Calendar.HOUR_OF_DAY) * 60 + it.get(java.util.Calendar.MINUTE) }
                    store.appendBaselineEvent(BaselineRecorder.event(BaselineEventType.MEAL_STARTED, "${type.label} ${formatMinute(minuteNow)}"))
                    ReminderScheduler.cancelMealReminder(context, type)
                    if (mealDurationTrackingEnabled) ReminderScheduler.scheduleMealEndReminder(context, record, plan.minutes)
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


/** 手动结束一个游戏安排时，记录实际结束（与通知「结束」动作同逻辑）。 */
private fun recordGameItemEnd(context: Context, store: PrototypeStore, sessionId: Long) {
    val updated = store.loadGameSessions().firstOrNull { it.id == sessionId }?.let { GameStats.endedSession(it) }
    if (updated != null) {
        store.updateGameSession(sessionId) { updated }
        ReminderScheduler.cancelGameReminders(context, sessionId)
    }
}
