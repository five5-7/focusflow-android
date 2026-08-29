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
    var flexiblePlanTarget by remember { mutableStateOf<Item?>(null) }
    var inboxEditTarget by remember { mutableStateOf<Item?>(null) }
    var gameSessions by remember { mutableStateOf(store.loadGameSessions()) }
    var gameDetectionEnabled by remember { mutableStateOf(store.loadGameDetectionEnabled()) }
    var appCategories by remember { mutableStateOf(store.loadAppCategories()) }
    var hiddenApps by remember { mutableStateOf(store.loadHiddenApps()) }
    var items by remember {
        mutableStateOf(store.recoverMissedGoalTasks())
    }
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
            if (event == Lifecycle.Event.ON_START) notificationForegroundCheck++
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
    val upcomingCommitment = nextActivityCommitment(items, courses)
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
                        if (item.goalId == null) saveItems(items.map { if (it.id == item.id) it.copy(done = true, completionLevel = "完成", completedAt = System.currentTimeMillis()) else it }) else completionTarget = item
                    },
                    goals = goals,
                    feedback = feedback,
                    activeSession = activeSession,
                    activityHistory = activityHistory,
                    nextCommitment = upcomingCommitment,
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
                    onShrink = { item -> saveItems(items.map { if (it.id == item.id) it.copy(title = item.title.removePrefix("重新安排："), kind = "任务", detail = "短版：先做 10 分钟 · 今天有空时") else it }) },
                    onPause = { item -> saveItems(items.map { if (it.id == item.id) it.copy(kind = "暂停", detail = "已暂停；随时可在计划中恢复") else it }) },
                    onAbandon = { item -> saveItems(items.filterNot { it.id == item.id }) },
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
                    Modifier.padding(padding), items, scheduleCourses,
                    energyLevel = energyLevel,
                    onPlanFlexible = { flexiblePlanTarget = it },
                    onAdjustFlexible = { inboxScheduleTarget = it },
                    onTaskDone = { item ->
                        if (item.kind == "游戏" || item.kind == "活动") recordGameItemEnd(context, store, item.id)
                        if (item.goalId == null) saveItems(items.map { if (it.id == item.id) it.copy(done = true, completionLevel = "完成", completedAt = System.currentTimeMillis()) else it }) else completionTarget = item
                    },
                    onDeleteItem = { item ->
                        saveItems(items.filterNot { it.id == item.id })
                        if (item.kind == "游戏" || item.kind == "活动") {
                            store.saveGameSessions(store.loadGameSessions().filterNot { it.id == item.id })
                            ReminderScheduler.cancelGameReminders(context, item.id)
                        }
                    }
                )
                2 -> PlansScreen(
                    Modifier.padding(padding), items, courses, commuteProfile, baselineProfile.lifeStage,
                    page = planPage,
                    onPageChange = { planPage = it; if (it == PlanPage.REVIEW) gameSessions = store.loadGameSessions() },
                    onResume = { item -> saveItems(items.map { if (it.id == item.id) it.copy(kind = "任务", detail = "已恢复；今天有空时再做", scheduledAt = null) else it }) },
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
                    onScheduleGoal = { goal, suggestion ->
                        val scheduled = Item(title = goal.title, detail = goalTaskDetail(goal, suggestion.weekday, suggestion.startMinute), kind = "任务", scheduledAt = GoalPlanner.nextOccurrence(suggestion.weekday, suggestion.startMinute), goalId = goal.id, durationMinutes = goal.durationMinutes)
                        saveItems(listOf(scheduled) + items)
                        store.appendBaselineEvent(BaselineRecorder.event(BaselineEventType.TASK_SCHEDULED, "${goal.title} · ${weekdayName(suggestion.weekday)} ${GoalPlanner.displayTime(suggestion.startMinute)}"))
                        ReminderScheduler.scheduleTaskReminder(context, scheduled)
                    },
                    onScheduleFlexible = { item, weekday, startMinute ->
                        val target = GoalPlanner.nextOccurrence(weekday, startMinute)
                        val scheduled = item.copy(kind = "任务", scheduledAt = target, dayOnly = false, windowStartAt = null, windowEndAt = null, detail = "已安排到 ${weekdayName(weekday)} ${GoalPlanner.displayTime(startMinute)}")
                        saveItems(items.map { if (it.id == item.id) scheduled else it })
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
                                    if (slotFree(target, goal.durationMinutes, scheduleCourses, items + newItems)) {
                                        newItems += Item(title = goal.title, detail = goalTaskDetail(goal, suggestion.weekday, suggestion.startMinute), kind = "任务", scheduledAt = target, goalId = goal.id, durationMinutes = goal.durationMinutes)
                                        PlanLearning.recordScheduled(store, suggestion.weekday, suggestion.startMinute / 60)
                                        scheduled++
                                    }
                                }
                            }
                            if (newItems.isEmpty()) {
                                autoPlanMessage = "本周空挡都被课程或已有安排占用，没有可排的时段；可先确认课程或调整目标时长。"
                            } else {
                                saveItems(newItems + items)
                                newItems.forEach { ReminderScheduler.scheduleTaskReminder(context, it) }
                                val byDay = newItems.groupBy { it.scheduledAt?.let(::weekdayOf) }.mapNotNull { (day, list) -> day?.let { "${weekdayName(it)} ${list.size} 个" } }.joinToString("、")
                                autoPlanMessage = "已把 ${newItems.size} 个目标任务排进本周空挡（避开课程与已有安排）：$byDay。可在日程里查看或调整。"
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
        if (addOpen) QuickCaptureDialog(onDismiss = { addOpen = false }) { text, tomorrow ->
            val captured = if (tomorrow) {
                Item(title = text, detail = "明天要做 · 尚未安排具体时间", kind = "任务", scheduledAt = dateAt(1, 10), dayOnly = true)
            } else Item(title = text, detail = "刚刚记录 · 稍后决定安排", kind = "收集箱")
            saveItems(listOf(captured) + items)
            if (tomorrow) ReminderScheduler.scheduleTaskReminder(context, captured)
            addOpen = false
        }
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
        rescheduleTarget?.let { item -> RescheduleTimeDialog(item, onDismiss = { rescheduleTarget = null }) { scheduledAt, duration, label ->
            val delayed = item.copy(kind = "任务", detail = "已改期至$label；届时会再次出现", scheduledAt = scheduledAt, durationMinutes = duration, dayOnly = false, windowStartAt = null, windowEndAt = null)
            saveItems(items.map { if (it.id == item.id) delayed else it })
            store.appendBaselineEvent(BaselineRecorder.event(BaselineEventType.TASK_RESCHEDULED, "${item.title.removePrefix("重新安排：")} → $label"))
            ReminderScheduler.scheduleTaskReminder(context, delayed)
            if (item.kind == "游戏" || item.kind == "活动") {
                store.loadGameSessions().firstOrNull { it.id == item.id && it.isOpen() }?.let { session ->
                    val updated = session.copy(plannedStartAt = scheduledAt, plannedEndAt = scheduledAt + duration * 60_000L)
                    store.updateGameSession(item.id) { updated }
                    ReminderScheduler.scheduleGameReminders(context, updated)
                }
            }
            rescheduleTarget = null
        } }
        inboxScheduleTarget?.let { item -> InboxScheduleDialog(
            item = item,
            items = items,
            courses = courses,
            energyLevel = energyLevel,
            onDismiss = { inboxScheduleTarget = null },
            onSchedule = { startsAt, duration, label ->
                val scheduled = item.copy(
                    title = item.title.removePrefix("重新安排："),
                    kind = "任务",
                    detail = "已安排：$label · $duration 分钟；可随时改期",
                    scheduledAt = startsAt,
                    durationMinutes = duration,
                    dayOnly = false,
                    windowStartAt = null,
                    windowEndAt = null
                )
                saveItems(items.map { if (it.id == item.id) scheduled else it })
                store.appendBaselineEvent(BaselineRecorder.event(BaselineEventType.TASK_SCHEDULED, "${item.title.removePrefix("重新安排：")} · $label"))
                ReminderScheduler.scheduleTaskReminder(context, scheduled)
                inboxScheduleTarget = null
            },
            onKeepWindow = { start, end, duration, label ->
                val flexible = item.copy(
                    title = item.title.removePrefix("重新安排："),
                    kind = "任务",
                    detail = "弹性范围：$label · 预计 $duration 分钟；尚未锁定具体时刻",
                    scheduledAt = null,
                    durationMinutes = duration,
                    dayOnly = false,
                    windowStartAt = start,
                    windowEndAt = end
                )
                saveItems(items.map { if (it.id == item.id) flexible else it })
                store.appendBaselineEvent(BaselineRecorder.event(BaselineEventType.TASK_SCHEDULED, "${item.title.removePrefix("重新安排：")} · 弹性范围 $label"))
                inboxScheduleTarget = null
            }
        ) }
        flexiblePlanTarget?.let { item -> FlexiblePlanDialog(
            item = item,
            suggestions = FlexiblePlanner.suggestions(item, items, courses, energyLevel),
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
                ReminderScheduler.scheduleTaskReminder(context, scheduled)
                flexiblePlanTarget = null
            }
        ) }
        inboxEditTarget?.let { item -> InboxEditDialog(item, onDismiss = { inboxEditTarget = null }) { title, detail ->
            saveItems(items.map { if (it.id == item.id) it.copy(title = title, detail = detail) else it })
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

@Composable private fun TodayScreen(
    modifier: Modifier,
    items: List<Item>,
    inboxOpen: Boolean,
    onInboxOpenChange: (Boolean) -> Unit,
    energyLevel: String,
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
    activeSession: ActivitySession?,
    activityHistory: List<ActivitySession>,
    nextCommitment: ActivityCommitment?,
    onStartActivity: () -> Unit,
    onStartSuggestion: (NextActionSuggestion, Boolean) -> Unit,
    onReplanSuggestion: (Item) -> Unit,
    onReviewActivity: () -> Unit,
    onPickTime: (Item) -> Unit,
    onEdit: (Item) -> Unit,
    onShrink: (Item) -> Unit,
    onPause: (Item) -> Unit,
    onAbandon: (Item) -> Unit,
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
    val nextSuggestion = recommendNextAction(items, nextCommitment, energyLevel, goals, feedback, now)
    val completedToday = items.count { it.done && it.completedAt?.let(::isToday) == true }
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
    Box(modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = !inboxOpen,
            enter = slideInHorizontally(animationSpec = tween(260), initialOffsetX = { -it / 4 }) + fadeIn(tween(180)),
            exit = slideOutHorizontally(animationSpec = tween(220), targetOffsetX = { -it / 4 }) + fadeOut(tween(150))
        ) {
    ScrollableWithBar(scrollState = rememberScrollState()) {
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
                            Text(latestStatusCheckIn?.let { "最近记录：${it.activity} · ${formatDateTime(it.recordedAt)}" } ?: "还没有记录正在进行的活动", style = MaterialTheme.typography.bodySmall)
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
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("收集箱", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            TextButton(onClick = { onInboxOpenChange(true) }) { Text("${inboxItems.size} 项  ›") }
        }
        if (inboxItems.isEmpty()) {
            Text("暂时没有新想法，点底部 ＋ 随手记录。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            inboxItems.take(2).forEach { item -> InboxItemCard(item, onPickTime, onEdit, onShrink, onPause, onAbandon) }
            if (inboxItems.size > 2) Text("还有 ${inboxItems.size - 2} 项，进入收集箱继续整理。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (visibility.energy) Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("当前精力", fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("偏低", "正常", "充足").forEach { level ->
                        FilterChip(selected = energyLevel == level, onClick = { onEnergyLevelChange(level) }, label = { Text(level) })
                    }
                }
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
        ElevatedCard { Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("$completedToday", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Text("今日完成", style = MaterialTheme.typography.labelMedium) }
            Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("$completedThisWeek", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Text("本周完成", style = MaterialTheme.typography.labelMedium) }
            Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("${inboxItems.size}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Text("待整理", style = MaterialTheme.typography.labelMedium) }
        } }
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
        AnimatedVisibility(
            visible = inboxOpen,
            enter = slideInHorizontally(animationSpec = tween(280), initialOffsetX = { it / 3 }) + fadeIn(tween(190)),
            exit = slideOutHorizontally(animationSpec = tween(230), targetOffsetX = { it / 3 }) + fadeOut(tween(150))
        ) {
            PlanSubpageFrame(Modifier.fillMaxSize(), "收集箱") {
                Text("集中处理尚未安排的想法；通过系统返回键或再次点击底栏“今日”回到概览。", style = MaterialTheme.typography.bodySmall)
                if (inboxItems.isEmpty()) {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))) {
                        Text("暂时没有新想法，点底部 ＋ 随手记录。", Modifier.fillMaxWidth().padding(16.dp))
                    }
                } else {
                    inboxItems.forEach { item -> InboxItemCard(item, onPickTime, onEdit, onShrink, onPause, onAbandon) }
                }
            }
        }
        if (helpOpen) HelpDialog(title = HelpCatalog.today.title, sections = HelpCatalog.today.sections, onDismiss = { helpOpen = false })
    }
}

@Composable private fun MealTodayCard(records: List<MealRecord>, profile: BaselineProfile, skipDays: Set<String>, now: Long, onPrompt: (MealType) -> Unit, onFinish: (MealType) -> Unit) {
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

private data class ScheduleWindowOption(val label: String, val startsAt: Long, val endsAt: Long)

private fun dateAtMinute(dayOffset: Int, minuteOfDay: Int): Long = java.util.Calendar.getInstance().apply {
    add(java.util.Calendar.DAY_OF_YEAR, dayOffset)
    set(java.util.Calendar.HOUR_OF_DAY, minuteOfDay / 60)
    set(java.util.Calendar.MINUTE, minuteOfDay % 60)
    set(java.util.Calendar.SECOND, 0)
    set(java.util.Calendar.MILLISECOND, 0)
}.timeInMillis

private fun scheduleWindowOptions(now: Long = System.currentTimeMillis()): List<ScheduleWindowOption> {
    val earliest = ((now + 15 * 60_000L + 14 * 60_000L) / (15 * 60_000L)) * (15 * 60_000L)
    val todayAfternoon = ScheduleWindowOption("今天下午", maxOf(earliest, dateAtMinute(0, 13 * 60)), dateAtMinute(0, 18 * 60))
    val todayEvening = ScheduleWindowOption("今天晚上", maxOf(earliest, dateAtMinute(0, 18 * 60)), dateAtMinute(0, 23 * 60 + 30))
    val tomorrowMorning = ScheduleWindowOption("明天上午", dateAtMinute(1, 8 * 60), dateAtMinute(1, 12 * 60))
    val tomorrowAfternoon = ScheduleWindowOption("明天下午", dateAtMinute(1, 13 * 60), dateAtMinute(1, 18 * 60))
    val weekEnd = java.util.Calendar.getInstance().apply {
        timeInMillis = now
        val weekday = when (get(java.util.Calendar.DAY_OF_WEEK)) { java.util.Calendar.SUNDAY -> 7 else -> get(java.util.Calendar.DAY_OF_WEEK) - 1 }
        add(java.util.Calendar.DAY_OF_YEAR, 7 - weekday)
        set(java.util.Calendar.HOUR_OF_DAY, 23)
        set(java.util.Calendar.MINUTE, 30)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis
    val thisWeek = ScheduleWindowOption("本周内", earliest, weekEnd)
    return listOf(todayAfternoon, todayEvening, tomorrowMorning, tomorrowAfternoon, thisWeek)
        .filter { it.endsAt > it.startsAt + 15 * 60_000L }
}

@Composable private fun FlexiblePlanDialog(item: Item, suggestions: List<FlexibleTimeSuggestion>, onDismiss: () -> Unit, onSelect: (FlexibleTimeSuggestion) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("为弹性任务初步规划") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(item.title, fontWeight = FontWeight.SemiBold)
                Text("这些时间已避开课程和定时任务，并保留前后缓冲。选择只是初步安排，之后仍可改期。", style = MaterialTheme.typography.bodySmall)
                if (suggestions.isEmpty()) {
                    Text("未来七天暂时没有足够连续的空档。任务会继续保留为弹性安排。")
                } else suggestions.forEach { suggestion ->
                    ElevatedCard(Modifier.fillMaxWidth().clickable { onSelect(suggestion) }) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(formatDateTime(suggestion.startsAt), fontWeight = FontWeight.Bold)
                            Text(suggestion.reason, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("保持弹性") } }
    )
}

@Composable private fun InboxScheduleDialog(
    item: Item,
    items: List<Item>,
    courses: List<Course>,
    energyLevel: String,
    onDismiss: () -> Unit,
    onSchedule: (Long, Int, String) -> Unit,
    onKeepWindow: (Long, Long, Int, String) -> Unit
) {
    val context = LocalContext.current
    val existingWindow = if (item.windowStartAt != null && item.windowEndAt != null) ScheduleWindowOption("当前范围", item.windowStartAt, item.windowEndAt) else null
    var mode by remember(item.id) { mutableStateOf(if (existingWindow == null) "推荐空档" else "大致时间") }
    var duration by remember(item.id) { mutableIntStateOf(item.durationMinutes.coerceIn(15, 180)) }
    var selectedWindow by remember(item.id) { mutableStateOf(existingWindow) }
    var exactTime by remember(item.id) { mutableStateOf<Long?>(null) }
    val windowOptions = scheduleWindowOptions().let { options -> if (existingWindow == null) options else listOf(existingWindow) + options }
    val planningItem = item.copy(
        durationMinutes = duration,
        scheduledAt = null,
        windowStartAt = if (mode == "大致时间") selectedWindow?.startsAt else null,
        windowEndAt = if (mode == "大致时间") selectedWindow?.endsAt else null
    )
    val suggestions = FlexiblePlanner.suggestions(planningItem, items, courses, energyLevel)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (item.kind == "收集箱") "安排收集箱任务" else "调整弹性安排") },
        text = {
            ScrollableDialogBox(maxHeight = 520.dp, spacing = 10.dp) {
                Text(item.title.removePrefix("重新安排："), fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("推荐空档", "大致时间", "精确时间").forEach { option ->
                        FilterChip(selected = mode == option, onClick = { mode = option }, label = { Text(option) })
                    }
                }
                Text("预计用时", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    listOf(15, 30, 60, 90).forEach { minutes ->
                        FilterChip(selected = duration == minutes, onClick = { duration = minutes }, label = { Text("$minutes 分") })
                    }
                }
                when (mode) {
                    "推荐空档" -> {
                        Text("参考已确认课程、未完成的定时任务和当前精力，并保留 15 分钟缓冲。", style = MaterialTheme.typography.bodySmall)
                        if (suggestions.isEmpty()) Text("未来七天没有足够连续的空档；可以改用大致时间继续保持弹性。")
                        suggestions.forEach { suggestion ->
                            ElevatedCard(Modifier.fillMaxWidth().clickable { onSchedule(suggestion.startsAt, duration, formatDateTime(suggestion.startsAt)) }) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text(formatDateTime(suggestion.startsAt), fontWeight = FontWeight.Bold)
                                    Text(suggestion.reason, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                    "大致时间" -> {
                        Text("只保存可接受的时间范围，不创建提醒，也不会在时间轴上伪装成固定日程。", style = MaterialTheme.typography.bodySmall)
                        windowOptions.forEach { option ->
                            FilterChip(
                                selected = selectedWindow == option,
                                onClick = { selectedWindow = option },
                                label = { Text("${option.label} · ${formatDateTime(option.startsAt)}–${formatTime(option.endsAt)}") }
                            )
                        }
                        selectedWindow?.let { window ->
                            val first = suggestions.firstOrNull()
                            Text(first?.let { "该范围内目前可优先考虑 ${formatDateTime(it.startsAt)}；保存范围后仍可稍后确认。" } ?: "该范围内暂时没有完整空档；可以先保存范围，日程变化后再尝试。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    else -> {
                        Text("选择一个明确时间后，任务会写入日程并创建提醒。", style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            listOf("明早 9:00" to dateAt(1, 9), "明晚 18:00" to dateAt(1, 18)).forEach { option ->
                                FilterChip(selected = exactTime == option.second, onClick = { exactTime = option.second }, label = { Text(option.first) })
                            }
                        }
                        OutlinedButton(onClick = {
                            val calendar = java.util.Calendar.getInstance()
                            DatePickerDialog(context, { _, year, month, day ->
                                TimePickerDialog(context, { _, hour, minute ->
                                    exactTime = java.util.Calendar.getInstance().apply {
                                        set(year, month, day, hour, minute, 0)
                                        set(java.util.Calendar.MILLISECOND, 0)
                                    }.timeInMillis
                                }, calendar.get(java.util.Calendar.HOUR_OF_DAY), calendar.get(java.util.Calendar.MINUTE), true).show()
                            }, calendar.get(java.util.Calendar.YEAR), calendar.get(java.util.Calendar.MONTH), calendar.get(java.util.Calendar.DAY_OF_MONTH)).show()
                        }) { Text(exactTime?.let { "已选：${formatDateTime(it)}" } ?: "自选日期与时间") }
                    }
                }
            }
        },
        confirmButton = {
            when (mode) {
                "大致时间" -> Button(enabled = selectedWindow != null, onClick = { selectedWindow?.let { onKeepWindow(it.startsAt, it.endsAt, duration, it.label) } }) { Text("保存范围") }
                "精确时间" -> Button(enabled = exactTime?.let { it > System.currentTimeMillis() } == true, onClick = { exactTime?.let { onSchedule(it, duration, formatDateTime(it)) } }) { Text("确认安排") }
                else -> {}
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
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

private fun formatTime(time: Long): String = java.text.SimpleDateFormat("HH:mm", java.util.Locale.CHINA).format(java.util.Date(time))
private fun formatActivityRemaining(milliseconds: Long): String {
    val totalSeconds = (milliseconds.coerceAtLeast(0) / 1_000L).toInt()
    val hours = totalSeconds / 3_600
    val minutes = (totalSeconds % 3_600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%02d:%02d".format(minutes, seconds)
}

private data class ActivityLaunchPreset(
    val name: String,
    val category: String,
    val minutes: Int,
    val nextStep: String,
    val minimumVersion: Boolean
)

private data class NextActionSuggestion(
    val item: Item,
    val reason: String,
    val minimumVersion: String? = null,
    val minimumMinutes: Int = 10
)

private fun recommendNextAction(items: List<Item>, nextCommitment: ActivityCommitment?, energyLevel: String = "正常", goals: List<Goal> = emptyList(), feedback: List<TaskFeedback> = emptyList(), now: Long = System.currentTimeMillis()): NextActionSuggestion? {
    val candidates = items.filter { !it.done && it.kind !in setOf("收集箱", "暂停", "计划") }
    fun recommendation(item: Item, baseReason: String): NextActionSuggestion {
        val goal = item.goalId?.let { id -> goals.firstOrNull { it.id == id } }
        val minimum = goal?.minimumVersion?.takeIf { it.isNotBlank() }
        val commonBarrier = goal?.let { current ->
            feedback.filter { it.goalId == current.id && it.barrier != "无" }.takeLast(12)
                .groupingBy(TaskFeedback::barrier).eachCount().maxByOrNull { it.value }?.key
        }
        val recommendMinimum = minimum != null && (energyLevel == "偏低" || commonBarrier in setOf("时间不够", "精力不足"))
        val reason = if (recommendMinimum) "$baseReason 结合当前精力或近期反馈，也可以先做“$minimum”。" else baseReason
        return NextActionSuggestion(item, reason, minimum, (goal?.durationMinutes?.div(3) ?: 10).coerceIn(5, 15))
    }
    val scheduled = candidates.filter { it.scheduledAt != null }
    val overdue = scheduled.filter { (it.scheduledAt ?: Long.MAX_VALUE) < now }.maxByOrNull { it.scheduledAt ?: Long.MIN_VALUE }
    if (overdue != null) {
        return recommendation(overdue, "原定 ${formatDateTime(overdue.scheduledAt ?: now)}，尚未确认完成；现在不合适时可以重新安排。")
    }
    val expiredWindow = candidates.filter { it.scheduledAt == null && (it.windowEndAt ?: Long.MAX_VALUE) < now }.maxByOrNull { it.windowEndAt ?: Long.MIN_VALUE }
    if (expiredWindow != null) {
        return recommendation(expiredWindow, "原先保留到 ${formatDateTime(expiredWindow.windowEndAt ?: now)} 的弹性范围已经过去；可以重新选择范围或直接开始。")
    }

    val upcoming = scheduled.filter { (it.scheduledAt ?: Long.MIN_VALUE) >= now }.minByOrNull { it.scheduledAt ?: Long.MAX_VALUE }
    val minutesUntilUpcoming = upcoming?.scheduledAt?.let { ((it - now) / 60_000L).toInt().coerceAtLeast(0) }
    if (upcoming != null && minutesUntilUpcoming != null && minutesUntilUpcoming <= 90) {
        return recommendation(upcoming, "${formatTime(upcoming.scheduledAt ?: now)} 开始，是最近的固定安排（约 $minutesUntilUpcoming 分钟后）。")
    }

    val flexible = candidates.filter {
        it.scheduledAt == null && (it.windowStartAt == null || it.windowStartAt <= now) && (it.windowEndAt == null || it.windowEndAt >= now)
    }
    val minutesBeforeCommitment = nextCommitment?.let { ((it.startsAt - now) / 60_000L).toInt().coerceAtLeast(0) }
    val usableMinutes = minutesBeforeCommitment?.minus(15)?.coerceAtLeast(0)
    val fittingCandidates = flexible.filter { usableMinutes == null || it.durationMinutes <= usableMinutes }
    val fitting = when (energyLevel) {
        "偏低" -> fittingCandidates.sortedWith(compareBy<Item> { if (it.durationMinutes <= 30) 0 else 1 }.thenBy { it.durationMinutes }).firstOrNull()
        "充足" -> fittingCandidates.sortedWith(compareByDescending<Item> { it.goalId != null }.thenByDescending { it.durationMinutes }).firstOrNull()
        else -> fittingCandidates.sortedWith(compareByDescending<Item> { it.goalId != null }.thenBy { it.durationMinutes }).firstOrNull()
    }
    if (fitting != null) {
        val reason = if (minutesBeforeCommitment == null) {
            when (energyLevel) {
                "偏低" -> "当前精力偏低；优先选择预计 ${fitting.durationMinutes} 分钟、较容易启动的一项。"
                "充足" -> "当前精力充足；优先推进较完整或与目标相关的一项。"
                else -> "当前没有临近的固定安排；这项任务可以直接开始。"
            }
        } else {
            "距离 ${nextCommitment.title} 约 $minutesBeforeCommitment 分钟；按当前精力选择本项，预计 ${fitting.durationMinutes} 分钟并保留 15 分钟缓冲。"
        }
        return recommendation(fitting, reason)
    }

    if (upcoming != null) {
        return recommendation(upcoming, "今天下一项固定安排在 ${formatTime(upcoming.scheduledAt ?: now)}；当前空档不足以稳妥放入其他任务。")
    }
    return flexible.minByOrNull(Item::durationMinutes)?.let { recommendation(it, "当前没有临近固定安排；先从预计用时较短的一项开始。") }
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
private fun periodForMinute(minute: Int): Int = (1..13).lastOrNull { CourseGapPlanner.periodStart(it) <= minute } ?: 1

/** 某时刻所在自然日的 [start, end) 毫秒范围。 */
private fun dayRange(millis: Long): LongRange {
    val start = java.util.Calendar.getInstance().apply {
        timeInMillis = millis
        set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis
    return start until (start + 24 * 60 * 60 * 1000L)
}

private enum class SettingsSubPage(val title: String) {
    ADVANCED("高级工具"),
    ROADMAP("版本路线图"), CAMPUS_PLACES("校园地点"), COMMUTE_PLACES("通勤与地点"), TUTORIAL_SEARCH("学习路径建议"),
    COURSE_VISION("课表识别（视觉模型）"), APP_DETECTION("前台应用检测"), STABILITY("稳定性与崩溃"),
    APPEARANCE("外观"), ACTIVITY_REMINDERS("日程与活动提醒"), QUIET_HOURS("提醒打扰控制"), CUSTOM_THEME("自定义主题")
}

/** 空挡内容建议：这段空挡适合做什么（目标优先，其次弹性任务）。 */
/** 按空挡匹配内容：未完成目标（时长能放下）优先，其次可安排的空闲弹性任务；目标按该时段历史完成率降序。 */
internal fun recommendForWindow(goals: List<Goal>, items: List<Item>, minutes: Int, store: PrototypeStore, weekday: Int, startMinute: Int): GapRecommendation? {
    val goal = goals.filter { g -> GoalPlanner.completedThisWeek(g) < g.weeklyTarget && g.durationMinutes <= minutes }
        .sortedWith(compareByDescending<Goal> { PlanLearning.completionRate(store, weekday, startMinute / 60) ?: -1f }
            .thenByDescending { it.weeklyTarget - GoalPlanner.completedThisWeek(it) }
            .thenByDescending { it.durationMinutes })
        .firstOrNull()
    if (goal != null) {
        val remaining = goal.weeklyTarget - GoalPlanner.completedThisWeek(goal)
        val rate = PlanLearning.completionRate(store, weekday, startMinute / 60)
        val rateNote = if (rate != null && rate >= 0.6f) " · 该时段完成率较高" else ""
        return GapRecommendation(goal.title, "目标还剩 $remaining 次 · 每次 ${goal.durationMinutes} 分钟$rateNote", goal, null)
    }
    val flexible = items.filter { it.kind == "任务" && it.scheduledAt == null && it.durationMinutes <= minutes }
        .sortedByDescending { it.durationMinutes }.firstOrNull()
    if (flexible != null) return GapRecommendation(flexible.title, "弹性任务 · 约 ${flexible.durationMinutes} 分钟", null, flexible)
    return null
}

@Composable private fun PlansScreen(modifier: Modifier, items: List<Item>, courses: List<Course>, profile: CommuteProfile, lifeStage: LifeStage?, page: PlanPage?, onPageChange: (PlanPage?) -> Unit, onResume: (Item) -> Unit, onConfirmCourse: (Course) -> Unit, onIgnoreCourse: (Course) -> Unit, onClearAwaitingCourses: () -> Unit, onAddCourse: () -> Unit, courseImportRunning: Boolean, courseImportMessage: String?, onImportCourses: () -> Unit, onEditCourse: (Course) -> Unit, goals: List<Goal>, onAddGoal: () -> Unit, onEditGoal: (Goal) -> Unit, onScheduleGoal: (Goal, GoalSuggestion) -> Unit, onScheduleFlexible: (Item, Int, Int) -> Unit, resources: List<LearningResource>, onAddResource: () -> Unit, onSelectResource: (LearningResource) -> Unit, onDeleteResource: (LearningResource) -> Unit, onDeselectResource: () -> Unit, onSummarizeResource: (LearningResource) -> Unit, onAutoPlanGoals: () -> Unit, autoPlanMessage: String?, tutorialSearch: TutorialSearchSettings, courseVision: CourseVisionSettings, onSearchTutorial: () -> Unit, onVideoAnalysis: () -> Unit, feedback: List<TaskFeedback>, gameSessions: List<GameSessionRecord>, checkIns: List<StatusCheckIn>, store: PrototypeStore) {
    // 假期阶段：空挡与目标建议不把课程当作安排（课程管理页仍用完整列表）。
    val planningCourses = if (lifeStage == LifeStage.HOLIDAY) emptyList<Course>() else courses
    var gapsTableExpanded by remember { mutableStateOf(false) }
    val awaitingCourses = courses.filter { it.needsConfirmation }
    val confirmedCourses = courses.filter { !it.needsConfirmation }
    val conflictingCourses = confirmedCourses.filter { course -> confirmedCourses.any { other -> other != course && coursesOverlap(course, other) } }
    val gaps = CourseGapPlanner.gaps(planningCourses.filter { !it.needsConfirmation }, profile, occupiedByWeekday(items))
    val paused = items.filter { it.kind == "暂停" }

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
                    pausedCount = paused.size
                )
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
                onAutoPlanGoals = onAutoPlanGoals,
                onScheduleGoal = onScheduleGoal
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
            PlanPage.REVIEW -> {
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
                            Text("数据来自“安排空闲活动”：游戏/视频到点检测前台应用，其余活动按结束确认记录实际结束；未授权使用情况访问时只靠手动结束。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                HorizontalDivider()
                Text("AI 周总结", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("每个目标的调整建议在其卡片下方（数据式）；这里按本周真实记录（目标完成、常见阻碍、游戏自律）生成一段简短 AI 复盘。", style = MaterialTheme.typography.bodySmall)
                if (!tutorialSearch.enabled || tutorialSearch.apiKey.isBlank()) {
                    Text("需在 设置 → 学习路径建议 开启“教程联网搜索”并填写硅基流动 key。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                            val summary = SiliconFlowClient.weeklySummary(tutorialSearch.apiKey, tutorialSearch.model, dataText)
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

/**
 * 主题预览色点：主色/副色/强调色/中性色（描边灰可见）/文字色（正文色可见）。
 */
private fun previewColors(spec: FocusFlowThemeSpec): List<Color> = listOf(
    spec.colorScheme.primary, spec.colorScheme.secondary, spec.colorScheme.tertiary,
    spec.colorScheme.outline, spec.colorScheme.onSurface
)

/** 中性色槽位渲染出的实际背景：向白提亮 85%（与 AppTheme.kt 自定义主题映射一致）。 */
private fun neutralBackground(neutral: Color): Color = lerp(neutral, Color.White, 0.85f)

/** WCAG 相对亮度。 */
private fun relativeLuminance(color: Color): Double {
    fun linearize(v: Float): Double {
        val c = v.toDouble()
        return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }
    return 0.2126 * linearize(color.red) + 0.7152 * linearize(color.green) + 0.0722 * linearize(color.blue)
}

/** WCAG 对比度（1:1 ~ 21:1）。 */
private fun contrastRatio(a: Color, b: Color): Double {
    val la = relativeLuminance(a)
    val lb = relativeLuminance(b)
    return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
}

/** 文字与背景最低对比度：低于此值视为撞色，禁止选用。 */
private const val MIN_TEXT_CONTRAST = 3.0

/**
 * 自定义主题配色槽位：主色/副色/强调色/中性色/文字色五个全局色，
 * 共同影响除课程色块与提醒警示外的所有界面区域。
 */
private enum class ThemeSlot(
    val label: String,
    val hint: String,
    val pick: (FocusFlowThemeColors) -> Color,
    val set: (FocusFlowThemeColors, Color) -> FocusFlowThemeColors
) {
    PRIMARY("主色", "按钮、导航、开关", { it.primaryAction }, { c, v -> c.copy(primaryAction = v) }),
    SECONDARY("副色", "次要强调与容器", { it.secondary }, { c, v -> c.copy(secondary = v) }),
    ACCENT("强调色", "提示与引导文字", { it.accent }, { c, v -> c.copy(accent = v) }),
    NEUTRAL("中性色", "背景、卡片与描边", { it.neutral }, { c, v -> c.copy(neutral = v) }),
    TEXT("文字色", "正文与标题", { it.text }, { c, v -> c.copy(text = v) })
}

/** 预设色板：深色 / 中亮 / 浅亮三组各 10 色，保证与任何主题搭配都有可选协调色。 */
private val presetSwatches: List<Color> = listOf(
    Color(0xFF0F6B7A), Color(0xFF4062A8), Color(0xFF5C4B9A), Color(0xFF7E4F90), Color(0xFFB84A6F), Color(0xFFC95878),
    Color(0xFFA44F34), Color(0xFFB5762E), Color(0xFF2C6D5A), Color(0xFF397A72),
    Color(0xFF2E8B9E), Color(0xFF5B82C4), Color(0xFF7D6CC0), Color(0xFF9A68B0), Color(0xFFD46A92), Color(0xFFE07E9C),
    Color(0xFFC06A4C), Color(0xFFD09247), Color(0xFF4A8F77), Color(0xFF57988F),
    Color(0xFF5AA7B5), Color(0xFF7FA0D1), Color(0xFF9D8FD3), Color(0xFFB68BC8), Color(0xFFE79AAF), Color(0xFF9FAF6E),
    Color(0xFF4C7A9C), Color(0xFF65558F), Color(0xFF6B94B4), Color(0xFF8575B0)
)

/** 黑白灰阶：补足预设色板缺失的纯黑/纯白与中性灰，供文字色与背景色选用。 */
private val grayScaleSwatches: List<Color> = listOf(
    Color(0xFF000000), Color(0xFF333333), Color(0xFF666666), Color(0xFF999999),
    Color(0xFFCCCCCC), Color(0xFFE0E0E0), Color(0xFFF5F5F5), Color(0xFFFFFFFF)
)

/** 自定义主题预设数量上限。 */
private const val MAX_THEME_PRESETS = 8

@Composable
private fun CustomThemeEditorContent(
    colors: FocusFlowThemeColors,
    onColorsChange: (FocusFlowThemeColors) -> Unit,
    presets: List<ThemePreset>,
    onPresetsChange: (List<ThemePreset>) -> Unit,
    onRestoreDefault: () -> Unit,
    customActive: Boolean,
    onApplyCustom: () -> Unit
) {
    var editingSlot by remember { mutableStateOf<ThemeSlot?>(null) }
    var namingPresetOpen by remember { mutableStateOf(false) }
    var editingPreset by remember { mutableStateOf<ThemePreset?>(null) }
    Text(
        "主题使用五个全局色：主色、副色、强调色、中性色、文字色，共同影响除课程色块与提醒警示外的所有界面区域。",
        style = MaterialTheme.typography.bodySmall
    )
    // 尚未启用自定义主题时（如从"以此改色"进入）：配色只作为工作副本，确认后才切换全局主题。
    if (!customActive) {
        ElevatedCard(
            colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        ) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("配色调整尚未生效：确认满意后点「应用此配色」启用自定义主题。", style = MaterialTheme.typography.bodySmall)
                Button(onClick = onApplyCustom, modifier = Modifier.align(Alignment.End)) { Text("应用此配色") }
            }
        }
    }
    editingPreset?.let { preset ->
        Text("正在编辑预设「${preset.name}」——改动会更新到该预设。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
    }
    ThemeSlot.entries.forEach { slot ->
        ElevatedCard(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().clickable { editingSlot = slot }.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(28.dp).clip(RoundedCornerShape(14.dp)).background(slot.pick(colors)))
                Column(Modifier.weight(1f)) {
                    Text(slot.label, fontWeight = FontWeight.SemiBold)
                    Text(slot.hint + " · 点选换色", style = MaterialTheme.typography.bodySmall)
                }
                Text("换色", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            }
        }
    }
    if (editingPreset == null) {
        TextButton(onClick = onRestoreDefault) { Text("恢复默认主题配色") }
    } else {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { editingPreset = null }) { Text("取消编辑") }
            Button(onClick = {
                onPresetsChange(presets.map { if (it == editingPreset) it.copy(colors = colors) else it })
                editingPreset = null
            }) { Text("更新此预设") }
        }
    }
    // 已保存的预设：命名存档，点击应用、可删除；最多 8 套。
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("已保存的预设", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        if (editingPreset != null) {
            // 编辑预设中：只能更新或取消，避免另存与更新混淆。
        } else if (presets.size >= MAX_THEME_PRESETS) {
            Text("最多 $MAX_THEME_PRESETS 套", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            TextButton(onClick = { namingPresetOpen = true }) { Text("保存当前为预设") }
        }
    }
    if (presets.isEmpty()) {
        Text("还没有预设：调整好配色后点“保存当前为预设”即可存档，之后可随时切换。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        presets.forEach { preset ->
            val active = preset.colors == colors
            ElevatedCard(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf(preset.colors.primaryAction, preset.colors.secondary, preset.colors.accent, preset.colors.neutral, preset.colors.text).forEach { color ->
                            Box(Modifier.size(14.dp).clip(RoundedCornerShape(7.dp)).background(color))
                        }
                    }
                    Column(Modifier.weight(1f)) {
                        Text(preset.name, fontWeight = FontWeight.SemiBold)
                        if (active) Text("当前配色", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                    // 应用预设 = 明确确认：载入配色并启用自定义主题。
                    TextButton(onClick = { onColorsChange(preset.colors); onApplyCustom(); editingPreset = null }, enabled = !active) { Text("应用") }
                    IconButton(onClick = { editingPreset = preset; onColorsChange(preset.colors) }) {
                        Icon(Icons.Filled.Edit, contentDescription = "编辑预设", modifier = Modifier.size(18.dp))
                    }
                    IconButton(onClick = {
                        onPresetsChange(presets.filterNot { it == preset })
                        if (editingPreset == preset) editingPreset = null
                    }) {
                        Icon(Icons.Filled.Close, contentDescription = "删除预设", modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
    if (namingPresetOpen) {
        var presetName by remember { mutableStateOf("预设 ${presets.size + 1}") }
        AlertDialog(
            onDismissRequest = { namingPresetOpen = false },
            title = { Text("保存当前配色为预设") },
            text = {
                OutlinedTextField(
                    value = presetName,
                    onValueChange = { presetName = it },
                    label = { Text("预设名称") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    enabled = presetName.isNotBlank(),
                    onClick = {
                        onPresetsChange(presets + ThemePreset(presetName.trim(), colors))
                        editingPreset = null
                        namingPresetOpen = false
                    }
                ) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { namingPresetOpen = false }) { Text("取消") } }
        )
    }
    editingSlot?.let { slot ->
        ColorPaletteDialog(
            current = slot.pick(colors),
            // 撞色限制：文字色与当前实际背景对比、中性色（背景）与当前文字对比，其余槽位不限。
            contrastOf = when (slot) {
                ThemeSlot.TEXT -> { candidate -> contrastRatio(candidate, neutralBackground(colors.neutral)) }
                ThemeSlot.NEUTRAL -> { candidate -> contrastRatio(neutralBackground(candidate), colors.text) }
                else -> null
            },
            onPick = { picked ->
                onColorsChange(slot.set(colors, picked))
                editingSlot = null
            },
            onDismiss = { editingSlot = null }
        )
    }
}

@Composable
private fun ColorPaletteDialog(
    current: Color,
    onPick: (Color) -> Unit,
    onDismiss: () -> Unit,
    contrastOf: ((Color) -> Double)? = null
) {
    val initialHsv = remember(current) { hsvTriple(current) }
    var hue by remember(current) { mutableStateOf(initialHsv.first) }
    var saturation by remember(current) { mutableStateOf(initialHsv.second) }
    var value by remember(current) { mutableStateOf(initialHsv.third) }
    var hexText by remember(current) { mutableStateOf(formatHex(current).removePrefix("#")) }
    val tempColor = Color.hsv(hue, saturation, value)
    LaunchedEffect(tempColor) { hexText = formatHex(tempColor).removePrefix("#") }
    val contrast = contrastOf?.invoke(tempColor)
    val usable = contrast == null || contrast >= MIN_TEXT_CONTRAST
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择颜色") },
        text = {
            ScrollableDialogBox(maxHeight = 720.dp, spacing = 12.dp) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(current).border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp)))
                    Text("当前颜色 · " + formatHex(current), style = MaterialTheme.typography.bodySmall)
                }
                if (contrastOf != null) {
                    Text("为防止文字与背景撞色，对比度不足 ${MIN_TEXT_CONTRAST}:1 的候选色不可用。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("预设色板 · 点选即应用", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                (grayScaleSwatches + presetSwatches).chunked(6).forEach { rowColors ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        rowColors.forEach { color ->
                            val swatchContrast = contrastOf?.invoke(color)
                            val swatchUsable = swatchContrast == null || swatchContrast >= MIN_TEXT_CONTRAST
                            val selected = color == current
                            Box(
                                Modifier.size(36.dp).clip(CircleShape).background(color)
                                    .then(if (swatchUsable) Modifier.clickable { onPick(color) } else Modifier)
                                    .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), CircleShape)
                                    .drawBehind {
                                        if (!swatchUsable) {
                                            // 撞色禁用的色块画斜杠：深色块用白杠、浅色块用深杠，保证可见。
                                            val slashColor = if (relativeLuminance(color) < 0.5) Color(0xB3FFFFFF) else Color(0x80404040)
                                            drawLine(slashColor, Offset(7.dp.toPx(), size.height - 7.dp.toPx()), Offset(size.width - 7.dp.toPx(), 7.dp.toPx()), strokeWidth = 2.5.dp.toPx())
                                        }
                                    }
                            )
                        }
                    }
                }
                Text("自定义", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                HueBar(hue = hue, onChange = { hue = it })
                SvPicker(hue = hue, saturation = saturation, value = value, onChange = { s, v ->
                    saturation = s
                    value = v
                })
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(24.dp).clip(RoundedCornerShape(6.dp)).background(tempColor).border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp)))
                    Text(formatHex(tempColor), style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.weight(1f))
                    if (contrast != null) {
                        Text(
                            if (usable) "对比度 %.1f : 1 · 可读".format(Locale.US, contrast)
                            else "对比度 %.1f : 1 · 与底色太接近".format(Locale.US, contrast),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (usable) Color(0xFF2F8F5B) else MaterialTheme.colorScheme.error
                        )
                    }
                }
                OutlinedTextField(
                    value = hexText,
                    onValueChange = { raw ->
                        val cleaned = raw.trim().removePrefix("#").uppercase().filter { it.isDigit() || it in 'A'..'F' }
                        hexText = cleaned.take(6)
                        if (cleaned.length == 6) {
                            val parsed = Color(android.graphics.Color.parseColor("#$cleaned"))
                            val h = hsvTriple(parsed)
                            hue = h.first
                            saturation = h.second
                            value = h.third
                        }
                    },
                    label = { Text("#RRGGBB") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = { TextButton(onClick = { onPick(tempColor); onDismiss() }, enabled = usable) { Text("应用") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/** 色相滑杆：左右拖动或点选切换色相（0..360）。 */
@Composable
private fun HueBar(hue: Float, onChange: (Float) -> Unit) {
    val stops = (0..10).map { Color.hsv(it * 36f, 1f, 1f) }
    Box(
        Modifier.fillMaxWidth().height(22.dp).clip(RoundedCornerShape(11.dp))
            .background(Brush.horizontalGradient(stops))
            .pointerInput(Unit) {
                detectTapGestures { onChange(((it.x / size.width) * 360f).coerceIn(0f, 360f)) }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    change.consume()
                    onChange(((change.position.x / size.width) * 360f).coerceIn(0f, 360f))
                }
            }
            .drawBehind {
                val x = (hue / 360f * size.width).coerceIn(9.dp.toPx(), size.width - 9.dp.toPx())
                drawCircle(Color.White, 7.dp.toPx(), Offset(x, size.height / 2f), style = Stroke(2.5.dp.toPx()))
                drawCircle(Color.Black, 7.dp.toPx(), Offset(x, size.height / 2f), style = Stroke(1.dp.toPx()))
            }
    )
}

/** 饱和度/明度取色区：横轴饱和度、纵轴明度（上亮下暗），拖动或点选取色。 */
@Composable
private fun SvPicker(hue: Float, saturation: Float, value: Float, onChange: (Float, Float) -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(10.dp))
            .background(Brush.horizontalGradient(listOf(Color.White, Color.hsv(hue, 1f, 1f))))
            .drawBehind {
                drawRect(Brush.verticalGradient(listOf(Color.White, Color.Black)), blendMode = BlendMode.Multiply)
                val cx = (saturation * size.width).coerceIn(9.dp.toPx(), size.width - 9.dp.toPx())
                val cy = ((1f - value) * size.height).coerceIn(9.dp.toPx(), size.height - 9.dp.toPx())
                drawCircle(Color.White, 8.dp.toPx(), Offset(cx, cy), style = Stroke(2.5.dp.toPx()))
                drawCircle(Color.Black, 8.dp.toPx(), Offset(cx, cy), style = Stroke(1.dp.toPx()))
            }
            .pointerInput(hue) {
                detectTapGestures { onChange((it.x / size.width).coerceIn(0f, 1f), (1f - it.y / size.height).coerceIn(0f, 1f)) }
            }
            .pointerInput(hue) {
                detectDragGestures { change, _ ->
                    change.consume()
                    onChange((change.position.x / size.width).coerceIn(0f, 1f), (1f - change.position.y / size.height).coerceIn(0f, 1f))
                }
            }
    )
}

/** 颜色 → HSV 三元组（hue 0..360）。 */
private fun hsvTriple(color: Color): Triple<Float, Float, Float> {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(color.toArgb(), hsv)
    return Triple(hsv[0], hsv[1], hsv[2])
}

/** 颜色 → #RRGGBB 文本。 */
private fun formatHex(color: Color): String = "#%06X".format(color.toArgb() and 0xFFFFFF)

private fun weekdayOf(millis: Long): Int {
    val calendar = java.util.Calendar.getInstance().apply { timeInMillis = millis }
    return when (calendar.get(java.util.Calendar.DAY_OF_WEEK)) { java.util.Calendar.SUNDAY -> 7 else -> calendar.get(java.util.Calendar.DAY_OF_WEEK) - 1 }
}

/** 今日安排摘要条目：课程或任务，按开始分钟排序。 */
private data class AgendaEntry(val startMinute: Int, val title: String, val subtitle: String, val isCourse: Boolean)

private fun todayAgenda(courses: List<Course>, items: List<Item>, now: Long = System.currentTimeMillis()): List<AgendaEntry> {
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

/** 某天的空挡标记：相邻课程之间的间隙（净分钟数 ≥10 才标记）。 */
private fun gapMarkersFor(courses: List<Course>, day: Int, profile: CommuteProfile): List<GapMarker> {
    val daily = courses.filter { it.weekday == day }.sortedBy { it.startPeriod }
    if (daily.size < 2) return emptyList()
    return daily.zipWithNext().mapNotNull { (from, to) ->
        val start = CourseGapPlanner.periodEnd(from.endPeriod)
        val end = CourseGapPlanner.periodStart(to.startPeriod)
        val net = end - start - ZijingangTravel.estimateMinutes(from.zone, to.zone, profile)
        if (net >= 10) GapMarker(start, end, net) else null
    }
}

/** 空挡课表视图：与日程一致的周时间轴课表，课程色块同课表，间隙标注净可用分钟数（≥60 分钟高亮）。 */
@Composable
internal fun GapTimelineContent(courses: List<Course>, profile: CommuteProfile) {
    val confirmed = courses.filter { !it.needsConfirmation }
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
                    TimelineDayLane(
                        events = confirmed.filter { it.weekday == day }.mapIndexed { index, course -> course.asTimelineEvent(index) },
                        gapMarkers = gapMarkersFor(confirmed, day, profile),
                        modifier = Modifier.weight(1f),
                        showLabels = false,
                        compactBlocks = true,
                        onSelect = {}
                    )
                }
            }
        }
    }
    Text("课程色块同课表；间隙显示扣除路程后的净可用分钟数（≥60 分钟深色高亮，适合安排目标或充电）。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** 本地判断：某时间点安排 durationMinutes 是否与课程/已有安排冲突（自动排计划用）。 */
private fun slotFree(target: Long, durationMinutes: Int, courses: List<Course>, items: List<Item>): Boolean {
    val calendar = java.util.Calendar.getInstance().apply { timeInMillis = target }
    val weekday = weekdayOf(target)
    val minute = calendar.get(java.util.Calendar.HOUR_OF_DAY) * 60 + calendar.get(java.util.Calendar.MINUTE)
    val end = minute + durationMinutes
    val courseBlocked = courses.filter { !it.needsConfirmation && it.weekday == weekday }.any { course ->
        CourseGapPlanner.periodStart(course.startPeriod) < end && minute < CourseGapPlanner.periodEnd(course.endPeriod)
    }
    if (courseBlocked) return false
    // 不同活动之间预留缓冲（10 分钟），避免背靠背、留出切换余地。
    val bufferMillis = 10 * 60_000L
    return items.none { item ->
        val start = item.scheduledAt ?: return@none false
        val itemCal = java.util.Calendar.getInstance().apply { timeInMillis = start }
        itemCal.get(java.util.Calendar.YEAR) == calendar.get(java.util.Calendar.YEAR) &&
            itemCal.get(java.util.Calendar.DAY_OF_YEAR) == calendar.get(java.util.Calendar.DAY_OF_YEAR) &&
            kotlin.math.abs(start - target) < (durationMinutes + item.durationMinutes) * 60_000L + bufferMillis
    }
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

/** 本周日程里已有安排（有固定时间的任务/事项）按星期几的占用分钟段；dayOnly 与仅时间范围的任务不算固定占用。 */
internal fun occupiedByWeekday(items: List<Item>, weekKey: Long = GoalPlanner.currentWeekKey()): Map<Int, List<IntRange>> {
    val weekEnd = weekKey + 7 * 24 * 60 * 60 * 1000L
    val calendar = java.util.Calendar.getInstance()
    return items.mapNotNull { item ->
        val at = item.scheduledAt ?: return@mapNotNull null
        if (item.dayOnly || at < weekKey || at >= weekEnd) return@mapNotNull null
        calendar.timeInMillis = at
        val weekday = weekdayOf(at)
        val startMinute = calendar.get(java.util.Calendar.HOUR_OF_DAY) * 60 + calendar.get(java.util.Calendar.MINUTE)
        val duration = item.durationMinutes.coerceAtLeast(15)
        weekday to (startMinute until startMinute + duration)
    }.groupBy({ it.first }, { it.second })
}

internal fun coursesOverlap(a: Course, b: Course): Boolean =
    a.weekday == b.weekday && a.startPeriod <= b.endPeriod && b.startPeriod <= a.endPeriod

@Composable private fun CourseEditorDialog(existing: Course?, places: List<CampusPlace>, onDismiss: () -> Unit, onSave: (Course) -> Unit) {
    var title by remember { mutableStateOf(existing?.title ?: "") }
    var weekday by remember { mutableIntStateOf(existing?.weekday ?: 1) }
    var startPeriod by remember { mutableStateOf(existing?.startPeriod?.toString() ?: "1") }
    var endPeriod by remember { mutableStateOf(existing?.endPeriod?.toString() ?: "1") }
    val availablePlaces = places.ifEmpty { ZijingangTravel.places }
    val existingPlace = availablePlaces.firstOrNull { it.name == existing?.building }
    var place by remember(availablePlaces, existing?.building) { mutableStateOf(existingPlace) }
    var customSelected by remember(existing?.building) { mutableStateOf(existing?.building != null && existingPlace == null) }
    var customName by remember(existing?.building) { mutableStateOf(if (existingPlace == null) (existing?.building ?: "") else "") }
    val parsedStart = startPeriod.toIntOrNull()
    val parsedEnd = endPeriod.toIntOrNull()
    val buildingName = if (customSelected) customName.trim() else (place?.name ?: "")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "新增课程" else "编辑课程") },
        text = { Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("课程名称") }, singleLine = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { (1..5).forEach { day -> FilterChip(selected = weekday == day, onClick = { weekday = day }, label = { Text(weekdayName(day)) }) } }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(modifier = Modifier.weight(1f), value = startPeriod, onValueChange = { startPeriod = it.filter(Char::isDigit) }, label = { Text("开始节次") }, singleLine = true)
                OutlinedTextField(modifier = Modifier.weight(1f), value = endPeriod, onValueChange = { endPeriod = it.filter(Char::isDigit) }, label = { Text("结束节次") }, singleLine = true)
            }
            Text("教学楼")
            availablePlaces.chunked(3).forEach { row -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { row.forEach { candidate -> FilterChip(selected = !customSelected && place == candidate, onClick = { place = candidate; customSelected = false }, label = { Text(candidate.name.removeSuffix("教学楼")) }) } } }
            FilterChip(selected = customSelected, onClick = { customSelected = true }, label = { Text("其他") })
            if (customSelected) OutlinedTextField(value = customName, onValueChange = { customName = it }, label = { Text("教学楼名称（自填，按东/西/北自动猜分区）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        } },
        confirmButton = { Button(enabled = title.isNotBlank() && parsedStart != null && parsedEnd != null && parsedStart in 1..13 && parsedEnd in parsedStart..13 && buildingName.isNotBlank(), onClick = {
            val zone = if (customSelected) CourseScreenshotParser.zoneByPrefix(buildingName) else (place?.zone ?: CampusZone.WEST_TEACHING)
            onSave(Course(title, weekday, parsedStart ?: 1, parsedEnd ?: 1, buildingName, zone, false))
        }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable private fun GoalEditorDialog(initialGoal: Goal?, resources: List<LearningResource>, suggestedFirstAction: String, courses: List<Course>, profile: CommuteProfile, items: List<Item>, onDismiss: () -> Unit, onOpenFinder: (String, String) -> Unit, onSave: (Goal) -> Unit) {
    var title by remember(initialGoal?.id) { mutableStateOf(initialGoal?.title.orEmpty()) }
    var weekly by remember(initialGoal?.id) { mutableStateOf(initialGoal?.weeklyTarget?.toString() ?: "3") }
    var duration by remember(initialGoal?.id) { mutableStateOf(initialGoal?.durationMinutes?.toString() ?: "30") }
    var metricType by remember(initialGoal?.id) { mutableStateOf(initialGoal?.metricType ?: "时长") }
    var metricTarget by remember(initialGoal?.id) { mutableStateOf(initialGoal?.metricTarget ?: "30 分钟") }
    var desiredOutcome by remember(initialGoal?.id) { mutableStateOf(initialGoal?.desiredOutcome.orEmpty()) }
    var resourceTitle by remember(initialGoal?.id) { mutableStateOf(initialGoal?.resourceTitle.orEmpty()) }
    var resourceUnit by remember(initialGoal?.id) { mutableStateOf(initialGoal?.resourceUnit.orEmpty()) }
    var firstAction by remember(initialGoal?.id, suggestedFirstAction) { mutableStateOf(suggestedFirstAction.ifBlank { initialGoal?.firstAction.orEmpty() }) }
    val weeklyNumber = weekly.toIntOrNull()
    val durationNumber = duration.toIntOrNull()
    val suggestedMinimum = GoalPlanner.suggestedMinimum(metricType, metricTarget, durationNumber ?: 30)
    // 数据式建议：按本周课程空挡统计可安排次数与单次时长（扣除已有安排；数据不足时不显示）。
    val gapSuggest = remember(courses, profile, durationNumber, items) {
        val confirmed = courses.filter { !it.needsConfirmation }
        val gaps = CourseGapPlanner.gaps(confirmed, profile, occupiedByWeekday(items)).filter { it.minutesFree >= 10 }
        if (gaps.isEmpty() || durationNumber == null) null
        else {
            val fit = gaps.filter { it.minutesFree >= durationNumber }
            val median = gaps.map { it.minutesFree }.sorted().let { it[it.size / 2] }
            Triple(fit.size, median, gaps.size)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initialGoal == null) "新增目标" else "编辑目标") },
        text = { Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("先描述你希望得到的结果；详细计划可以稍后由应用根据空档生成。")
            OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("目标名称") }, singleLine = true)
            OutlinedTextField(value = desiredOutcome, onValueChange = { desiredOutcome = it }, label = { Text("预期结果（例如：能稳定完成每周锻炼）") }, minLines = 2)
            OutlinedTextField(value = weekly, onValueChange = { weekly = it.filter(Char::isDigit) }, label = { Text("每周次数") }, singleLine = true)
            OutlinedTextField(value = duration, onValueChange = { duration = it.filter(Char::isDigit) }, label = { Text("预计占用分钟") }, singleLine = true)
            gapSuggest?.let { (fit, median, total) ->
                Text("本周课程空挡可安排约 $fit 次（共 $total 段空挡，中位单次约 $median 分钟）${if (fit < (weeklyNumber ?: 3)) "；与每周 $weeklyNumber 次相比略紧，可考虑降低时长或次数。" else ""}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("完成标准")
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) { listOf("时长", "次数", "成果").forEach { type -> FilterChip(selected = metricType == type, onClick = { metricType = type }, label = { Text(type) }) } }
            OutlinedTextField(value = metricTarget, onValueChange = { metricTarget = it }, label = { Text("例如：20 道题／读完一节／30 分钟") }, singleLine = true)
            OutlinedTextField(value = firstAction, onValueChange = { firstAction = it }, label = { Text("第一步行动（例如：打开题库先做第 1 题）") }, minLines = 2)
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) { Column(Modifier.padding(12.dp)) {
                Text("建议最低版本", fontWeight = FontWeight.SemiBold)
                Text(suggestedMinimum)
                Text("这是应用按目标类型与预计时长给出的保守起点；之后会结合教程和你的反馈调整。", style = MaterialTheme.typography.bodySmall)
            } }
            if (title.isNotBlank() || desiredOutcome.isNotBlank()) {
                OutlinedButton(onClick = { onOpenFinder(title.trim(), desiredOutcome.trim()) }, modifier = Modifier.fillMaxWidth()) { Text("搜学习教程（AI 建议 / 手动搜索）") }
            }
            Text("执行资料（可选，每个目标独立选择）", fontWeight = FontWeight.SemiBold)
            if (resources.isEmpty()) {
                Text("资料库为空；不添加资料也能正常执行。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                resources.forEach { resource ->
                    FilterChip(
                        selected = resourceTitle == resource.title,
                        onClick = { resourceTitle = if (resourceTitle == resource.title) "" else resource.title },
                        label = { Text(resource.title) }
                    )
                }
            }
            if (resourceTitle.isNotBlank()) {
                OutlinedTextField(value = resourceUnit, onValueChange = { resourceUnit = it }, label = { Text("教程章节／练习（可选）") }, singleLine = true)
            }
        } },
        confirmButton = { Button(enabled = title.isNotBlank() && desiredOutcome.isNotBlank() && metricTarget.isNotBlank() && weeklyNumber != null && durationNumber != null && weeklyNumber in 1..7 && durationNumber in 5..240, onClick = { onSave(Goal(id = initialGoal?.id ?: System.currentTimeMillis(), title = title, weeklyTarget = weeklyNumber ?: 1, durationMinutes = durationNumber ?: 5, metricType = metricType, metricTarget = metricTarget, minimumVersion = suggestedMinimum, resourceTitle = resourceTitle, resourceUnit = resourceUnit, completedThisWeek = initialGoal?.completedThisWeek ?: 0, minimumCompletionsThisWeek = initialGoal?.minimumCompletionsThisWeek ?: 0, completionWeekKey = initialGoal?.completionWeekKey ?: GoalPlanner.currentWeekKey(), desiredOutcome = desiredOutcome, firstAction = firstAction)) }) { Text(if (initialGoal == null) "创建" else "保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable private fun ResourceEditorDialog(onDismiss: () -> Unit, onSave: (LearningResource) -> Unit) {
    var title by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("收集教程") }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("只保存你已经确认过的真实链接、材料或笔记；保存后再到目标编辑器中按目标关联。")
        OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("教程名称") }, singleLine = true)
        OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("链接（可选）") }, singleLine = true)
        OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text("材料说明或笔记（链接为空时必填）") }, minLines = 2)
    } }, confirmButton = { Button(enabled = LearningResourcePolicy.canSave(title, url, note), onClick = { onSave(LearningResource(title = title.trim(), url = url.trim(), summary = note.trim())) }) { Text("确认保存") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

/** 按星期自动命名：连续段合并为“周一至周四”，其余逐列（如“周五”“周六、周日”）。 */
private fun autoGroupName(days: Set<Int>): String {
    val sorted = days.sorted()
    if (sorted.isEmpty()) return "未命名"
    val ranges = mutableListOf<Pair<Int, Int>>()
    var start = sorted.first()
    var prev = start
    for (day in sorted.drop(1)) {
        if (day == prev + 1) prev = day
        else { ranges += start to prev; start = day; prev = day }
    }
    ranges += start to prev
    return ranges.joinToString("、") { (a, b) -> if (a == b) weekdayName(a) else "${weekdayName(a)}至${weekdayName(b)}" }
}

/** 作息分组向导：设定作息 → 选中要应用的星期 → 未分配的星期继续建下一组 → 全部覆盖后按星期命名保存。 */
@Composable
private fun DayGroupWizardDialog(existingGroups: List<DayGroup>, defaultWake: Int, defaultSleep: Int, defaultMeals: List<MealTimeline>, onDismiss: () -> Unit, onSave: (List<DayGroup>) -> Unit) {
    var groups by remember { mutableStateOf(existingGroups) }
    var draftDays by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var draftWake by remember { mutableIntStateOf(defaultWake.coerceIn(300, 720)) }
    var draftSleep by remember { mutableIntStateOf(defaultSleep.coerceIn(1200, 1500)) }
    var draftMeals by remember { mutableStateOf(defaultMeals) }
    val assignedDays = groups.flatMap { it.days }.toSet()
    val unassigned = (1..7).filter { it !in assignedDays }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("作息分组向导") },
        text = { Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("步骤：先设定本组作息 → 选中要应用的星期 → “确定这一组”；未分配的星期继续下一组，最后按星期自动命名保存。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                (1..7).forEach { day ->
                    val group = groups.firstOrNull { day in it.days }
                    Surface(shape = RoundedCornerShape(8.dp), color = if (group == null) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primaryContainer) {
                        Text(weekdayName(day), Modifier.padding(horizontal = 6.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            if (unassigned.isEmpty()) {
                Text("7 天都已分配完成，点“保存”完成。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            } else {
                Text("本组应用到的星期（剩余：${unassigned.joinToString("、") { weekdayName(it) }}）", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    unassigned.forEach { day -> FilterChip(selected = day in draftDays, onClick = { draftDays = if (day in draftDays) draftDays - day else draftDays + day }, label = { Text(weekdayName(day)) }) }
                }
                Text("本组作息：起床 ${formatMinute(draftWake)} · 睡觉 ${formatMinute(draftSleep)}", fontWeight = FontWeight.SemiBold)
                Text("起床 ${formatMinute(draftWake)}")
                Slider(value = draftWake.toFloat(), onValueChange = { draftWake = it.toInt() }, valueRange = 300f..720f, steps = 27)
                Text("睡觉 ${formatMinute(draftSleep)}")
                Slider(value = draftSleep.toFloat(), onValueChange = { draftSleep = it.toInt() }, valueRange = 1200f..1500f, steps = 29)
                MealType.entries.forEach { type ->
                    val meal = draftMeals.firstOrNull { it.type == type }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(type.label, Modifier.width(48.dp))
                        Slider(
                            value = (meal?.typicalStartMinute ?: 480).toFloat(),
                            onValueChange = { minute -> draftMeals = (draftMeals.filterNot { it.type == type } + MealTimeline(type, minute.toInt(), meal?.typicalMinutes ?: 20)).sortedBy { it.type.ordinal } },
                            valueRange = 360f..1320f,
                            steps = 31,
                            modifier = Modifier.weight(1f)
                        )
                        Text(formatMinute(meal?.typicalStartMinute ?: 480), style = MaterialTheme.typography.labelSmall)
                    }
                }
                Button(enabled = draftDays.isNotEmpty(), onClick = {
                    groups = groups + DayGroup(autoGroupName(draftDays), draftDays, draftWake, draftSleep, draftMeals.filter { it.type in MealType.entries })
                    // 每组需显式选择星期：清空选中，避免下一组默认占用所有剩余天数。
                    draftDays = emptySet()
                }, modifier = Modifier.fillMaxWidth()) { Text("确定这一组（${autoGroupName(draftDays)}）") }
            }
            if (groups.isNotEmpty()) {
                Text("已建分组", fontWeight = FontWeight.SemiBold)
                groups.forEach { group ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(group.label, fontWeight = FontWeight.SemiBold)
                            Text("${group.days.sorted().joinToString("、") { weekdayName(it) }} · 起床 ${formatMinute(group.wakeMinute)} · 睡觉 ${formatMinute(group.sleepMinute)}", style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { groups = groups.filterNot { it.label == group.label } }) { Text("删除") }
                    }
                }
            }
        } },
        confirmButton = { Button(onClick = { onSave(groups) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
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

@Composable private fun StatusCheckInDialog(
    initialEnergy: String,
    initialActivity: String,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit
) {
    var energy by remember { mutableStateOf(initialEnergy.takeIf { it in StatusCheckInCatalog.energies } ?: "正常") }
    var activity by remember { mutableStateOf(initialActivity.takeIf { it in StatusCheckInCatalog.activities } ?: "空闲") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("记录现在状态") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("精力", fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatusCheckInCatalog.energies.forEach { value ->
                        FilterChip(selected = energy == value, onClick = { energy = value }, label = { Text(value) })
                    }
                }
                Text("正在做什么", fontWeight = FontWeight.Bold)
                StatusCheckInCatalog.activities.chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { value ->
                            FilterChip(selected = activity == value, onClick = { activity = value }, label = { Text(value) })
                        }
                    }
                }
                Text("这次记录只用于当前推荐和以后可选的本机学习，不会自动移动固定日程。", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { Button(onClick = { onSave(energy, activity) }) { Text("保存状态") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("暂不记录") } }
    )
}

/** 今日页「现在做什么」的活动状态记录：只问正在做什么；娱乐类可顺手设置收尾提醒（remindMinutes 为 null 表示不设提醒）。 */
@Composable private fun ActivityStatusDialog(
    initialActivity: String,
    onDismiss: () -> Unit,
    onSave: (activity: String, remindMinutes: Int?) -> Unit
) {
    var activity by remember { mutableStateOf(initialActivity.takeIf { it in StatusCheckInCatalog.activities } ?: "空闲") }
    var remind by remember { mutableStateOf<Int?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("记录现在状态") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("正在做什么", fontWeight = FontWeight.Bold)
                StatusCheckInCatalog.activities.chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { value ->
                            FilterChip(selected = activity == value, onClick = { activity = value }, label = { Text(value) })
                        }
                    }
                }
                if (activity == "娱乐") {
                    HorizontalDivider()
                    Text("娱乐类活动要不要提醒收尾？", fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(30, 60, 90).forEach { minutes ->
                            FilterChip(selected = remind == minutes, onClick = { remind = minutes }, label = { Text("$minutes 分钟") })
                        }
                        FilterChip(selected = remind == null, onClick = { remind = null }, label = { Text("不设提醒") })
                    }
                    Text("选了时间后，到点会提醒你结束（可处理到点或延长）。", style = MaterialTheme.typography.bodySmall)
                }
                Text("这次记录只用于当前推荐和以后可选的本机学习，不会自动移动固定日程。", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { Button(onClick = { onSave(activity, if (activity == "娱乐") remind else null) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("暂不记录") } }
    )
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
                        var reminderDiagnosticsRevision by remember { mutableIntStateOf(0) }
                        var taskTestMessage by remember { mutableStateOf<String?>(null) }
                        val nextTaskReminder = remember(activitySettings, reminderDiagnosticsRevision) {
                            TaskReminderPolicy.nextReminder(settingsStore.loadItems(), activitySettings)
                        }
                        val notificationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
                            notifGranted = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                            notificationHealth = NotificationChannelSettings.health(context)
                            ReminderScheduler.restoreTaskReminders(context)
                            reminderDiagnosticsRevision += 1
                        }
                        DisposableEffect(lifecycleOwner) {
                            val observer = LifecycleEventObserver { _, event ->
                                if (event == Lifecycle.Event.ON_RESUME) {
                                    notifGranted = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                                    notificationHealth = NotificationChannelSettings.health(context)
                                    exactAllowed = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
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
                        SettingSwitch("活动提醒", "关闭后仍会保留活动记录和手动转场", activitySettings.notificationsEnabled) { onActivitySettingsChange(activitySettings.copy(notificationsEnabled = it)) }
                        SettingSwitch("明确的到点提醒", "到达约定时间时使用更醒目的提醒", activitySettings.strongerEndReminder) { onActivitySettingsChange(activitySettings.copy(strongerEndReminder = it)) }
                        HorizontalDivider()
                        SettingSwitch("日程提醒", "课程以外的定时任务、目标安排会在开始前提醒；重启后自动恢复", activitySettings.scheduleRemindersEnabled) {
                            onActivitySettingsChange(activitySettings.copy(scheduleRemindersEnabled = it))
                        }
                        Text("日程默认提前：${activitySettings.scheduleAdvanceMinutes} 分钟")
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
                                    nextTaskReminder?.let { "下一条：${it.title} · ${formatDateTime(it.triggerAt)} 提醒（${formatDateTime(it.startsAt)} 开始）" }
                                        ?: if (activitySettings.scheduleRemindersEnabled) "目前没有未来的定时任务提醒。" else "日程提醒已关闭。",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                OutlinedButton(
                                    enabled = notifGranted,
                                    onClick = {
                                        val mode = ReminderScheduler.scheduleTaskReminderTest(context)
                                        taskTestMessage = if (mode == AlarmDeliveryMode.EXACT) "已安排精确测试提醒，1 分钟后应出现。" else "已安排普通测试提醒；系统可能延迟触发。"
                                    }
                                ) { Text("1 分钟后测试通知") }
                                taskTestMessage?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }
                            }
                        }
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

@Composable private fun CampusMapHelpDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("如何制作地点包") },
        text = {
            ScrollableDialogBox(maxHeight = 480.dp, spacing = 10.dp) {
                Text("一般情况下不需要自己制作地点包。紫金港目录由应用提供；自动地图搜索和地图点选接入后，它们会成为默认方式。", color = MaterialTheme.colorScheme.primary)
                Text("以下格式只用于迁移、备份或批量维护：", fontWeight = FontWeight.SemiBold)
                Text("1. 用任意文本编辑器新建 UTF-8 文件，并保存为 .json。")
                Text("2. 填写地点包名称和地点列表。每个地点需要名称、所属分区和类型。")
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))) {
                    Text(
                        "{\n  \"name\": \"我的校园\",\n  \"version\": 1,\n  \"places\": [\n    { \"name\": \"西1教学楼\", \"zone\": \"WEST_TEACHING\", \"kind\": \"教学楼\" },\n    { \"name\": \"图书馆\", \"zone\": \"LIBRARY\", \"kind\": \"学习\" }\n  ]\n}",
                        Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Text("分区代码")
                CampusZone.entries.forEach { zone -> Text("${zone.name}：${zone.label}", style = MaterialTheme.typography.bodySmall) }
                Text("3. 回到这里点击“导入地点包（JSON）”，从系统文件选择器选中该文件。")
                Text("导入前会检查版本、地点数量、重复名称和分区代码；失败时不会覆盖当前地点包。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                HorizontalDivider()
                Text("后续地图流程", fontWeight = FontWeight.Bold)
                Text("自动搜索会围绕所选校区获取教学楼、图书馆、运动、餐饮、宿舍与交通 POI，并根据地图类型推断用途。若结果缺失，用户只需在地图上点一下；逆地理编码会建议名称，用户确认即可。", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text("知道了") } }
    )
}

@Composable private fun BaselineTimePickButton(label: String, minute: Int, onChange: (Int) -> Unit) {
    val context = LocalContext.current
    OutlinedButton(onClick = {
        TimePickerDialog(context, { _, hour, minuteOfHour ->
            onChange(hour * 60 + minuteOfHour)
        }, minute / 60, minute % 60, true).show()
    }) { Text("$label ${formatMinute(minute)}") }
}

@Composable private fun BaselineOnboardingDialog(initial: BaselineProfile, onDismiss: () -> Unit, onSave: (BaselineProfile) -> Unit) {
    var step by remember { mutableIntStateOf(0) }
    var lifeStage by remember(initial) { mutableStateOf(initial.lifeStage) }
    var wakeMinute by remember(initial) { mutableIntStateOf(initial.wakeMinute.takeIf { it >= 0 } ?: 7 * 60) }
    var sleepMinute by remember(initial) { mutableIntStateOf(initial.sleepMinute.takeIf { it >= 0 } ?: 23 * 60) }
    var meals by remember(initial) {
        mutableStateOf(initial.meals.ifEmpty {
            listOf(
                MealTimeline(MealType.BREAKFAST, 8 * 60 + 30),
                MealTimeline(MealType.LUNCH, 12 * 60),
                MealTimeline(MealType.DINNER, 18 * 60)
            )
        })
    }
    var entertainment by remember(initial) { mutableStateOf(initial.entertainmentWindow) }
    val steps = listOf("生活阶段", "作息", "餐点", "娱乐")
    AlertDialog(
        onDismissRequest = {},
        title = { Text(if (initial.isComplete) "编辑习惯基线" else "先了解你的大致节奏（可跳过）") },
        text = {
            ScrollableDialogBox(maxHeight = 480.dp, spacing = 10.dp) {
                Text("${steps[step]} · ${step + 1} / ${steps.size}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                when (step) {
                    0 -> {
                        Text("记录的目的是将来给出更合适的提醒与安排，现在只需大致回答。")
                        Text("当前生活阶段", fontWeight = FontWeight.Bold)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            LifeStage.entries.forEach { stage ->
                                FilterChip(selected = lifeStage == stage, onClick = { lifeStage = stage }, label = { Text(stage.label) })
                            }
                        }
                        Text("假期和开学后的作息会分开学习，避免互相干扰。", style = MaterialTheme.typography.bodySmall)
                    }
                    1 -> {
                        Text("大致起床与睡觉时间；不用精确到分钟。", style = MaterialTheme.typography.bodySmall)
                        BaselineTimePickButton("起床", wakeMinute) { wakeMinute = it }
                        BaselineTimePickButton("睡觉", sleepMinute) { sleepMinute = it }
                    }
                    2 -> {
                        Text("每餐大约什么时候开始、通常吃多久？只记大致时间，之后会按你的实际确认自动调整。", style = MaterialTheme.typography.bodySmall)
                        meals.forEach { meal ->
                            ElevatedCard {
                                Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(meal.type.label, fontWeight = FontWeight.SemiBold)
                                    BaselineTimePickButton("开始", meal.typicalStartMinute) { minute ->
                                        meals = meals.map { if (it.type == meal.type) it.copy(typicalStartMinute = minute) else it }
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text("时长", style = MaterialTheme.typography.bodySmall)
                                        listOf(15, 20, 30, 45).forEach { minutes ->
                                            FilterChip(selected = meal.typicalMinutes == minutes, onClick = { meals = meals.map { if (it.type == meal.type) it.copy(typicalMinutes = minutes) else it } }, label = { Text("$minutes 分钟") })
                                        }
                                    }
                                }
                            }
                        }
                    }
                    else -> {
                        Text("常见的娱乐或放松时段（可选），例如“19:00-21:30”。")
                        OutlinedTextField(value = entertainment, onValueChange = { entertainment = it }, label = { Text("娱乐时段（可选）") }, placeholder = { Text("例如：19:00-21:30") }, singleLine = true)
                        Text("不需要今天就填得很准；之后任何时间都可以回来修正。", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            if (step < steps.size - 1) Button(enabled = step != 0 || lifeStage != null, onClick = { step += 1 }) { Text("下一步") }
            else Button(enabled = lifeStage != null, onClick = {
                onSave(BaselineProfile(lifeStage = lifeStage, wakeMinute = wakeMinute, sleepMinute = sleepMinute, meals = meals, entertainmentWindow = entertainment.trim()))
            }) { Text("完成") }
        },
        dismissButton = {
            Row {
                if (step > 0) TextButton(onClick = { step -= 1 }) { Text("上一步") }
                TextButton(onClick = onDismiss) { Text(if (initial.isComplete) "取消" else "跳过") }
            }
        }
    )
}

@Composable private fun BaselineEventsDialog(events: List<BaselineEvent>, onDismiss: () -> Unit, onClear: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("原始事件记录") },
        text = {
            ScrollableDialogBox(maxHeight = 460.dp, spacing = 8.dp) {
                Text("这些是你确认过的原始记录，按时间追加保存；学习算法不会覆盖它们。", style = MaterialTheme.typography.bodySmall)
                if (events.isEmpty()) {
                    Text("还没有记录。完成引导、开始活动、签到或确认通勤后会自动出现在这里。")
                } else {
                    events.takeLast(50).reversed().forEach { event ->
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))) {
                            Text(BaselineRecorder.displayPayload(event), Modifier.fillMaxWidth().padding(10.dp), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Text("共 ${events.size} 条记录，仅保存在本机。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = {
            if (events.isNotEmpty()) OutlinedButton(onClick = onClear) { Text("清除全部记录") }
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

@Composable private fun MealPromptDialog(type: MealType, plan: MealPlan, onDismiss: () -> Unit, onStarted: () -> Unit, onSnooze: () -> Unit, onSkip: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("准备${type.label}？") },
        text = {
            Text(if (plan.learned) "你最近 ${plan.sampleCount} 次${type.label}常在 ${formatMinute(plan.startMinute)} 左右开始，平均约 ${plan.minutes} 分钟。确认“已在吃”后，我会按这个时长在 ${formatMinute(plan.startMinute + plan.minutes)} 提醒你确认结束。" else "按你填写的大致时间，${formatMinute(plan.startMinute)} 附近是${type.label}时间。确认“已在吃”后，我会在 ${formatMinute(plan.startMinute + plan.minutes)} 提醒你确认结束。")
        },
        confirmButton = {
            Button(onClick = onStarted) { Text("已在吃") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onSnooze) { Text("稍后 20 分钟") }
                TextButton(onClick = onSkip) { Text("今天不需要") }
            }
        }
    )
}

@Composable private fun MealFinishDialog(record: MealRecord, type: MealType, onDismiss: () -> Unit, onFinished: (MealDraft) -> Unit, onStillEating: () -> Unit, onNoRecord: () -> Unit) {
    var amountText by remember(record.id) { mutableStateOf("") }
    var rating by remember(record.id) { mutableIntStateOf(0) }
    var note by remember(record.id) { mutableStateOf("") }
    var location by remember(record.id) { mutableStateOf("") }
    var category by remember(record.id) { mutableStateOf("") }
    var merchant by remember(record.id) { mutableStateOf("") }
    var payMethod by remember(record.id) { mutableStateOf("") }
    val amount = amountText.toIntOrNull()?.coerceIn(0, 9999) ?: -1
    val categories = listOf("食堂", "外卖", "自己做饭", "便利店", "其他")
    val payMethods = listOf("微信", "支付宝", "校园卡", "现金", "其他")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${type.label}吃完了吗？") },
        text = {
            ScrollableDialogBox(maxHeight = 460.dp, spacing = 8.dp) {
                Text("结束并记录用餐时间；地点、分类、商家、支付方式、金额、评价和备注都是可选的消费草稿，只保存在本机，不会自动生成账目。", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value = amountText, onValueChange = { amountText = it.filter(Char::isDigit).take(4) }, label = { Text("金额（元，可选）") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(value = location, onValueChange = { location = it.take(20) }, label = { Text("地点（可选）") }, placeholder = { Text("例如：大食堂、临水、外卖") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("宿舍", "食堂", "外卖", "便利店").forEach { quick ->
                        FilterChip(selected = location == quick, onClick = { location = if (location == quick) "" else quick }, label = { Text(quick) })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("分类", style = MaterialTheme.typography.bodySmall)
                    categories.forEach { item ->
                        FilterChip(selected = category == item, onClick = { category = if (category == item) "" else item }, label = { Text(item) })
                    }
                }
                OutlinedTextField(value = merchant, onValueChange = { merchant = it.take(30) }, label = { Text("商家／食物名（可选）") }, placeholder = { Text("例如：临水餐厅、麦香鸡套餐") }, singleLine = true)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("支付方式", style = MaterialTheme.typography.bodySmall)
                    payMethods.forEach { method ->
                        FilterChip(selected = payMethod == method, onClick = { payMethod = if (payMethod == method) "" else method }, label = { Text(method) })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("评价", style = MaterialTheme.typography.bodySmall)
                    listOf(1, 2, 3, 4, 5).forEach { star ->
                        FilterChip(selected = rating == star, onClick = { rating = if (rating == star) 0 else star }, label = { Text("$star") })
                    }
                    if (rating == 0) Text("不评价", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedTextField(value = note, onValueChange = { note = it.take(80) }, label = { Text("备注（可选）") }, singleLine = true)
            }
        },
        confirmButton = {
            Button(onClick = {
                onFinished(MealDraft(amount = amount, rating = rating, note = note.trim(), location = location.trim(), category = category, merchant = merchant.trim(), payMethod = payMethod))
            }) { Text("吃完了") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onStillEating) { Text("还在吃") }
                TextButton(onClick = onNoRecord) { Text("不记录") }
            }
        }
    )
}

@Composable private fun MealRecordsDialog(records: List<MealRecord>, onDismiss: () -> Unit, onDelete: (Long) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("就餐记录") },
        text = {
            ScrollableDialogBox(maxHeight = 460.dp, spacing = 8.dp) {
                Text("记录按时间追加保存，只有你确认的开始与结束时间会用于饭点学习；地点、分类、商家、支付方式、金额与评价只作为消费草稿保留，不会自动生成账目。", style = MaterialTheme.typography.bodySmall)
                if (records.isEmpty()) {
                    Text("还没有记录。开始吃饭并确认吃完后会自动出现在这里。")
                } else {
                    records.takeLast(50).reversed().forEach { record ->
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))) {
                            Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                val time = java.text.SimpleDateFormat("M月d日 HH:mm", java.util.Locale.CHINA)
                                val detail = buildString {
                                    append(record.mealType.label)
                                    append(" · ").append(time.format(java.util.Date(record.startedAt)))
                                    record.endedAt?.let { append("–").append(time.format(java.util.Date(it))) }
                                    if (record.location.isNotBlank()) append(" · ").append(record.location)
                                    if (record.category.isNotBlank()) append(" · ").append(record.category)
                                    if (record.merchant.isNotBlank()) append(" · ").append(record.merchant)
                                    if (record.amount > 0) append(" · ").append(record.amount).append(" 元")
                                    if (record.payMethod.isNotBlank()) append(" · ").append(record.payMethod)
                                    if (record.rating > 0) append(" · ").append(record.rating).append(" 星")
                                    if (record.note.isNotBlank()) append(" · ").append(record.note)
                                }
                                Text(detail, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = { onDelete(record.id) }) { Text("删除") }
                            }
                        }
                    }
                    Text("共 ${records.size} 条记录，仅保存在本机。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

@Composable private fun RouteCalibrationDialog(from: CampusPlace, to: CampusPlace, mode: String, currentMinutes: Int, history: List<Int>, onDismiss: () -> Unit, onSave: (Int) -> Unit) {
    var minutes by remember(from, to, mode) { mutableStateOf(currentMinutes.toString()) }
    val parsed = minutes.toIntOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("确认本次通勤耗时") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${from.name} → ${to.name} · $mode")
                Text("填写从出发到到达的实际总分钟数，包含进出楼和找教室时间。不读取定位。")
                if (history.isNotEmpty()) Text("已有 ${history.size} 次确认记录；学习值使用最近记录的中位数，单次异常不会直接覆盖结果。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                OutlinedTextField(value = minutes, onValueChange = { minutes = it.filter(Char::isDigit).take(3) }, label = { Text("实际分钟数") }, singleLine = true)
                Text("保存后，同一出行方式和分区组合会新增一条确认记录；课程空挡、目标候选时间与路线预览会使用学习后的中位数。", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { Button(enabled = parsed != null && parsed in 1..180, onClick = { parsed?.let(onSave) }) { Text("保存本次记录") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable internal fun CampusPlacePickerDialog(title: String, places: List<CampusPlace>, selectedName: String?, onDismiss: () -> Unit, onSelect: (CampusPlace) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            ScrollableDialogBox(maxHeight = 420.dp, spacing = 6.dp) {
                places.groupBy(CampusPlace::kind).forEach { (kind, groupedPlaces) ->
                    Text(kind, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    groupedPlaces.forEach { place ->
                        OutlinedButton(onClick = { onSelect(place) }, modifier = Modifier.fillMaxWidth()) {
                            Text(if (place.name == selectedName) "✓ ${place.name}" else place.name)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable private fun SettingSwitch(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) { Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.SemiBold); Text(detail) }; Switch(checked = checked, onCheckedChange = onChange) } }

/** 添加本机应用：搜索已安装应用 → 选择分类（自动识别提示，可改）。 */
@Composable private fun AddInstalledAppDialog(onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    val context = LocalContext.current
    val installed = remember {
        runCatching {
            val pm = context.packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(intent, 0)
                .map { it.activityInfo.packageName }
                .distinct()
                .filter { it != context.packageName }
                .map { pkg -> pkg to AppLibrary.appLabel(context, pkg) }
                .sortedBy { it.second }
        }.getOrDefault(emptyList())
    }
    var query by remember { mutableStateOf("") }
    var selectedPkg by remember { mutableStateOf<String?>(null) }
    val filtered = if (query.isBlank()) installed else installed.filter { it.first.contains(query, true) || it.second.contains(query, true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加本机应用") },
        text = {
            Column(Modifier.heightIn(max = 440.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("搜索应用名或包名") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                val selected = selectedPkg
                if (selected == null) {
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        filtered.take(80).forEach { (pkg, label) ->
                            Card(modifier = Modifier.fillMaxWidth().clickable { selectedPkg = pkg }) {
                                Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(label, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
                                    Text(pkg, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                } else {
                    Text("选择分类（《${AppLibrary.appLabel(context, selected)}》）：", style = MaterialTheme.typography.bodySmall)
                    val auto = autoCategoryByLabel(AppLibrary.appLabel(context, selected))
                    if (auto != null) Text("按应用名识别为：${auto.label}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        listOf(AppCategory.GAME, AppCategory.VIDEO, AppCategory.SOCIAL, AppCategory.STUDY, AppCategory.OTHER, AppCategory.UNKNOWN).forEach { c ->
                            FilterChip(selected = false, onClick = { onSave(selected, c.name); onDismiss() }, label = { Text(c.label) })
                        }
                    }
                    TextButton(onClick = { selectedPkg = null }) { Text("返回列表") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

/** 加号菜单：快速记录 / 安排空闲活动（触发方式，与原有入口不冲突）。 */
@Composable private fun AddMenuDialog(onDismiss: () -> Unit, onQuickCapture: () -> Unit, onGamePlan: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            Card {
                Column(Modifier.fillMaxWidth().clickable(onClick = onQuickCapture).padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("快速记录", fontWeight = FontWeight.SemiBold)
                    Text("记一个想法，稍后再安排。", style = MaterialTheme.typography.bodySmall)
                }
            }
            Card {
                Column(Modifier.fillMaxWidth().clickable(onClick = onGamePlan).padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("安排空闲活动（时间）", fontWeight = FontWeight.SemiBold)
                    Text("游戏/视频/学习/休息/运动，按空闲安排时间，到点提醒开始与收尾（游戏/视频可检测前台）。", style = MaterialTheme.typography.bodySmall)
                }
            }
        } },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/** 活动标题提示：按类别给出示例。 */
private fun activityTitleLabel(category: String): String = when (category) {
    "视频" -> "看什么（如：追剧/纪录片）"
    "学习" -> "学什么（如：复习高数）"
    "休息" -> "休息方式（如：午睡）"
    "运动" -> "运动项目（如：跑步）"
    "自定义" -> "活动名称"
    else -> "玩什么（如：原神）"
}

/** 安排空闲活动：类别 + 名称（可选，默认类别）+ 时长 + 建议/自定义时间；到点提醒开始（可选）与收尾。 */
@Composable private fun GamePlanDialog(courses: List<Course>, profile: CommuteProfile, items: List<Item>, onDismiss: () -> Unit, onSave: (Item, GameSessionRecord) -> Unit) {
    val context = LocalContext.current
    var category by remember { mutableStateOf("游戏") }
    var title by remember { mutableStateOf("") }
    var duration by remember { mutableStateOf("60") }
    var selected by remember { mutableStateOf<GoalSuggestion?>(null) }
    var remindStart by remember { mutableStateOf(false) }
    var customTime by remember { mutableStateOf<Long?>(null) }
    val durationNumber = duration.toIntOrNull()
    val suggestions = remember(durationNumber, courses, profile, items) {
        GoalPlanner.suggestions(Goal(title = "活动", weeklyTarget = 1, durationMinutes = durationNumber ?: 60), courses, profile, occupiedByWeekday(items)).take(5)
    }
    val chosen = selected ?: customTime?.let { at ->
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = at }
        GoalSuggestion(weekdayOf(at), cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE), durationNumber ?: 60)
    } ?: suggestions.firstOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("安排空闲活动") },
        text = { ScrollableDialogBox(maxHeight = 480.dp, spacing = 8.dp) {
            Text("到点提醒开始（可选）；结束时按类别检测前台应用（游戏/视频）或直接提醒收尾，并记录实际结束与超时。", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                listOf("游戏", "视频", "学习", "休息", "运动", "自定义").forEach { c ->
                    FilterChip(selected = category == c, onClick = { category = c }, label = { Text(c) })
                }
            }
            OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text(activityTitleLabel(category)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("30", "60", "90").forEach { value ->
                    FilterChip(selected = duration == value, onClick = { duration = value }, label = { Text("$value 分钟") })
                }
            }
            OutlinedTextField(value = duration, onValueChange = { duration = it.filter(Char::isDigit).take(3) }, label = { Text("时长（分钟，5–300）") }, singleLine = true)
            FilterChip(selected = remindStart, onClick = { remindStart = !remindStart }, label = { Text(if (remindStart) "到点提醒开始 ✓" else "到点提醒开始（可选）") })
            HorizontalDivider()
            Text("建议时间（本周空闲时段，也可自定义）", fontWeight = FontWeight.SemiBold)
            if (suggestions.isEmpty() && customTime == null) {
                Text("本周暂无足够空闲时段；可点“自定义时间”自己选，或稍后到日程里改期。", style = MaterialTheme.typography.bodySmall)
            } else suggestions.forEach { suggestion ->
                FilterChip(selected = customTime == null && chosen?.weekday == suggestion.weekday && chosen?.startMinute == suggestion.startMinute, onClick = { customTime = null; selected = suggestion }, label = { Text("${weekdayName(suggestion.weekday)} ${GoalPlanner.displayTime(suggestion.startMinute)}（可用 ${suggestion.freeMinutes} 分钟）") }, modifier = Modifier.fillMaxWidth())
            }
            FilterChip(selected = customTime != null, onClick = {
                selected = null
                val cal = java.util.Calendar.getInstance()
                TimePickerDialog(context, { _, hour, minute ->
                    val chosenAt = java.util.Calendar.getInstance().apply {
                        set(java.util.Calendar.HOUR_OF_DAY, hour)
                        set(java.util.Calendar.MINUTE, minute)
                        set(java.util.Calendar.SECOND, 0)
                        set(java.util.Calendar.MILLISECOND, 0)
                        if (timeInMillis <= System.currentTimeMillis()) add(java.util.Calendar.DAY_OF_YEAR, 1)
                    }
                    customTime = chosenAt.timeInMillis
                }, cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE), true).show()
            }, label = { Text(customTime?.let { at ->
                val cal2 = java.util.Calendar.getInstance().apply { timeInMillis = at }
                "自定义：${GoalPlanner.displayTime(cal2.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal2.get(java.util.Calendar.MINUTE))}（最近一次）"
            } ?: "自定义时间…") }, modifier = Modifier.fillMaxWidth())
        } },
        confirmButton = { Button(enabled = (durationNumber ?: 0) in 5..300 && chosen != null, onClick = {
            val d = durationNumber ?: return@Button
            val s = chosen ?: return@Button
            val finalTitle = title.trim().ifBlank { category }
            val scheduledAt = GoalPlanner.nextOccurrence(s.weekday, s.startMinute)
            val item = Item(title = finalTitle, detail = "${category}安排 · ${weekdayName(s.weekday)} ${GoalPlanner.displayTime(s.startMinute)}–${GoalPlanner.displayTime(s.startMinute + d)}", kind = "活动", scheduledAt = scheduledAt, durationMinutes = d)
            val session = GameSessionRecord(id = item.id, title = finalTitle, category = category, packageName = null, plannedStartAt = scheduledAt, plannedEndAt = scheduledAt + d * 60_000L, remindStart = remindStart)
            onSave(item, session)
        }) { Text("安排") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable private fun QuickCaptureDialog(onDismiss: () -> Unit, onSave: (String, Boolean) -> Unit) { var text by remember { mutableStateOf("") }; var tomorrow by remember { mutableStateOf(false) }; AlertDialog(onDismissRequest = onDismiss, title = { Text("快速记录") }, text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { Text("先保存想法，安排可以以后再说。"); OutlinedTextField(value = text, onValueChange = { text = it }, placeholder = { Text("例如：购买教材") }, singleLine = false); FilterChip(selected = tomorrow, onClick = { tomorrow = !tomorrow }, label = { Text("明天要做（不定时间）") }); if (tomorrow) Text("明天上午会温和提醒；你再决定具体什么时候做。", style = MaterialTheme.typography.bodySmall) } }, confirmButton = { Button(enabled = text.isNotBlank(), onClick = { onSave(text.trim(), tomorrow) }) { Text("保存") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }) }

@Composable private fun ActivityDialog(
    suggestedNextStep: String,
    preset: ActivityLaunchPreset?,
    activityHistory: List<ActivitySession>,
    nextCommitment: ActivityCommitment?,
    energyLevel: String,
    onDismiss: () -> Unit,
    onStart: (category: String, name: String, endsAt: Long, nextStep: String) -> Unit
) {
    val context = LocalContext.current
    var category by remember(preset) { mutableStateOf(preset?.category ?: "游戏／娱乐") }
    var customName by remember(preset) { mutableStateOf(preset?.name.orEmpty()) }
    var timeMode by remember { mutableStateOf("时长") }
    var minutes by remember(preset) { mutableStateOf((preset?.minutes ?: 60).toString()) }
    var untilAt by remember { mutableLongStateOf(System.currentTimeMillis() + 60 * 60_000L) }
    var nextStep by remember(preset, suggestedNextStep) { mutableStateOf(preset?.nextStep ?: suggestedNextStep) }
    val activityName = when {
        preset != null -> customName.trim()
        category == "自定义" -> customName.trim()
        else -> category
    }
    val timeSuggestion = ActivityTimeAdvisor.suggest(
        category = category,
        name = activityName,
        history = activityHistory,
        nextCommitment = nextCommitment,
        energyLevel = energyLevel,
        plannedMinutes = preset?.minutes
    )
    val calculatedEnd = if (timeMode == "时长") System.currentTimeMillis() + (minutes.toIntOrNull()?.coerceIn(1, 600) ?: 60) * 60_000L else untilAt
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("开始活动") },
        text = {
            ScrollableDialogBox(maxHeight = 480.dp, spacing = 10.dp) {
                Text("先约定什么时候收尾，以及收尾后要去哪里。")
                preset?.let {
                    Text(if (it.minimumVersion) "已预填推荐任务的最低版本；结束活动后仍由你确认任务是否完成。" else "已预填推荐任务；结束活动后仍由你确认任务是否完成。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
                listOf("游戏／娱乐", "学习", "休息", "自定义").forEach { label ->
                    FilterChip(selected = category == label, onClick = { category = label }, label = { Text(label) })
                }
                if (category == "自定义" || preset != null) OutlinedTextField(value = customName, onValueChange = { customName = it }, label = { Text("活动名称") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = timeMode == "时长", onClick = { timeMode = "时长" }, label = { Text("预计时长") })
                    FilterChip(selected = timeMode == "截至", onClick = { timeMode = "截至" }, label = { Text("直到时间") })
                }
                if (timeMode == "时长") {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f))) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text("建议 ${timeSuggestion.minutes} 分钟 · 约 ${formatTime(System.currentTimeMillis() + timeSuggestion.minutes * 60_000L)} 结束", fontWeight = FontWeight.SemiBold)
                            Text(timeSuggestion.reason, style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { minutes = timeSuggestion.minutes.toString() }) { Text(if (minutes == timeSuggestion.minutes.toString()) "已采用建议" else "采用建议时间") }
                        }
                    }
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
            ScrollableDialogBox(maxHeight = 480.dp, spacing = 10.dp) {
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
