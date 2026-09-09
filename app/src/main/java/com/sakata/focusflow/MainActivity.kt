package com.sakata.focusflow

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.core.view.WindowCompat
import androidx.core.content.FileProvider
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.zIndex
import java.io.File
import java.net.URL
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 8.1.0 退出确认时间窗：与 Snackbar Short 显示时长一致，提示消失前再次按系统返回才真正退出。 */
private const val EXIT_PROMPT_WINDOW_MS = 4000L

/** 挂起的课程编辑器：original 为 null 表示「新增课程」，非空表示「编辑该课程」。 */
private data class SuspendedCourseEditor(val original: Course?)

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
        // 8.1.0 第三轮：冷启动到 Compose 的 BackHandler 挂上之间有一段窗口，
        // 此时系统返回会直接结束 Activity（表现为"刚打开就返回，没有二次确认"）。
        // 这里先挂一个兜底回调吞掉返回；首帧组合完成后立刻禁用，此后完全交给 Compose，
        // 避免将来某个状态没有 Compose 处理器时返回被永久吞掉（审计 P3）。
        onBackPressedDispatcher.addCallback(this, startupBackFallback)
        // 8.1.0：判断本次开机系统是否把开机广播送给了我们（ColorOS 会推迟），设置页据此如实提示。
        BootRecovery.noteLaunch(this)
        setContent {
            LaunchedEffect(Unit) { startupBackFallback.isEnabled = false }
            FocusFlowApp(statusCheckInRequested, mealPromptRequested, mealFinishRequested, quickCaptureRequested, permissionOnboardingPending) {
                statusCheckInRequested = false
                mealPromptRequested = null
                mealFinishRequested = null
                quickCaptureRequested = false
            }
        }
    }

    /** 冷启动窗口内的返回兜底：首帧组合完成后由 Compose 关闭。 */
    private val startupBackFallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() = Unit
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
    var organizeTarget by remember { mutableStateOf<Item?>(null) }
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
    DisposableEffect(appLifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) {
                notificationForegroundCheck++
                items = store.loadItems()
                gameSessions = store.loadGameSessions()
                activeSession = store.loadLatestActiveSession()
                activityHistory = store.loadRecentActivitySessions()
                // 弹窗可能持有通知操作前的任务快照，返回前台后重新打开。
                rescheduleTarget = null
                inboxScheduleTarget = null
                flexiblePlanTarget = null
                inboxEditTarget = null
                organizeTarget = null
                convertTarget = null
                attachTarget = null
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
    // 8.1.0 动画速度（外观页）：全局时长倍率，写入 MotionSettings 供各动画换算。
    var animationSpeed by remember { mutableStateOf(store.loadAnimationSpeed()) }
    LaunchedEffect(animationSpeed) { MotionSettings.update(animationSpeed) }
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
    var campusLifeEnabled by remember {
        mutableStateOf(
            CampusLifePolicy.initialEnabled(
                stored = store.loadCampusLifeEnabled(),
                featureIntroShown = store.loadFeatureIntroShown(),
                choiceShown = store.loadCampusLifeChoiceShown()
            )
        )
    }
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
    var campusLifeChoiceOpen by remember { mutableStateOf(false) }
    var updateNoticeOpen by remember { mutableStateOf(false) }
    var baselineWhereToFindOpen by remember { mutableStateOf(false) }
    // 首次开启课表视觉模型且未填 key 时自动弹出申请引导（只弹一次）。
    LaunchedEffect(courseVision.enabled) {
        if (courseVision.enabled && tutorialSearch.apiKey.isBlank() && !store.loadCourseVisionGuideShown()) {
            store.saveCourseVisionGuideShown(true)
            courseVisionGuideOpen = true
        }
    }
    var courses by remember { mutableStateOf(if (store.hasCourseSetup()) store.loadCourses() else emptyList()) }
    var coursePeriodTable by remember { mutableStateOf(store.loadCoursePeriodTable()) }
    var coursePeriodTableConfigured by remember { mutableStateOf(store.hasCoursePeriodTable()) }
    var courseTimetableCompact by remember { mutableStateOf(store.loadCourseTimetableCompact()) }
    var courseTimetableTrailingDaysExpanded by remember { mutableStateOf(store.loadCourseTimetableTrailingDaysExpanded()) }
    CourseGapPlanner.configure(coursePeriodTable)
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
    LaunchedEffect(notificationForegroundCheck) {
        goals = store.loadGoals()
        completionTarget = null
        editGoalTarget = null
        goalScheduleTarget = null
    }
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
    // 仅新安装在快速入门前询问一次；已有用户升级时不弹出，也不改写现有校园生活设置。
    LaunchedEffect(permissionOnboardingPending, baselineOnboardingOpen, baselineWhereToFindOpen) {
        if (!permissionOnboardingPending && !baselineOnboardingOpen && !baselineWhereToFindOpen &&
            !store.loadFeatureIntroShown() && !store.loadCampusLifeChoiceShown()
        ) campusLifeChoiceOpen = true
    }
    // 首次启动快速入门：校园生活选择完成后再弹，避免两个弹窗叠在一起。
    LaunchedEffect(permissionOnboardingPending, baselineOnboardingOpen, baselineWhereToFindOpen, campusLifeChoiceOpen) {
        if (!permissionOnboardingPending && !baselineOnboardingOpen && !baselineWhereToFindOpen && !campusLifeChoiceOpen &&
            store.loadCampusLifeChoiceShown() && !store.loadFeatureIntroShown()
        ) {
            store.saveFeatureIntroShown(true)
            featureIntroOpen = true
        }
    }

    // 首次安装先完成快速入门；既有用户或后续覆盖安装才显示一次版本更新说明。
    LaunchedEffect(permissionOnboardingPending, baselineOnboardingOpen, baselineWhereToFindOpen, campusLifeChoiceOpen, featureIntroOpen) {
        if (permissionOnboardingPending || baselineOnboardingOpen || baselineWhereToFindOpen || campusLifeChoiceOpen || featureIntroOpen) return@LaunchedEffect
        val seenVersion = store.loadLastSeenAppVersion()
        if (seenVersion == null && !store.loadFeatureIntroShown()) return@LaunchedEffect
        if (seenVersion != BuildConfig.VERSION_NAME) {
            store.saveLastSeenAppVersion(BuildConfig.VERSION_NAME)
            updateNoticeOpen = true
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
    // 8.1.0 课程编辑器挂起：从课程编辑器跳转「管理地点与出行参数」时暂存，回到课程页自动重开（草稿箱恢复内容）。
    var suspendedCourseEditor by remember { mutableStateOf<SuspendedCourseEditor?>(null) }
    // 8.1.0 会话历史列表弹窗（长按底栏回退键打开）。
    var historyListOpen by remember { mutableStateOf(false) }
    // 8.1.0 转场判定：只有"跳转"（通知/深链/跨页跳转）才改变位移幅度与静止缩放，其余导航一律普通切换。
    var lastNavWasJump by remember { mutableStateOf(false) }
    // 8.1.0 第三轮：只有"把目标页签的子页收回主页"时才抑制（snapPageChange）；打开子页照常播放放大动画。
    var pageSnapTab by remember { mutableIntStateOf(-1) }
    var pageSnapToken by remember { mutableIntStateOf(0) }
    // 抑制在"子页层读到 token 的那一次转场"后立即失效（见 SubpageMotion）；这里的定时器只是兜底，
    // 防止目标页签当时没组合、token 一直挂着影响它后续的主动转场。窗口随「动画速度」缩放。
    val consumePageSnap = remember { { pageSnapTab = TabMotionRules.NO_TAB } }
    LaunchedEffect(pageSnapToken) {
        if (pageSnapToken != 0) {
            delay(motionMillis(MotionSpec.SUBPAGE_HOME_MS + 120).toLong())
            pageSnapTab = TabMotionRules.NO_TAB
        }
    }
    // 8.1.0 第三轮：本次导航离开的"正在看子页"的页签（在应用快照前判定，见 captureDepartingSubpage）。
    var leavingSubpageTab by remember { mutableIntStateOf(-1) }
    LaunchedEffect(leavingSubpageTab) {
        if (leavingSubpageTab != -1) {
            delay(motionMillis(MotionSpec.SUBPAGE_CROSS_MS + 80).toLong())
            leavingSubpageTab = TabMotionRules.NO_TAB
        }
    }
    // 8.1.0 检查更新（仅 GitHub 正式版；下载后调系统安装）。
    var updateCheckState by remember { mutableStateOf(UpdateCheckState()) }
    var autoCheckUpdates by remember { mutableStateOf(store.loadAutoCheckUpdates()) }
    var acceptRcUpdates by remember { mutableStateOf(store.loadAcceptRcUpdates()) }
    var downloadedUpdate by remember { mutableStateOf<File?>(null) }
    // 8.1.0 导航历史与草稿保险箱（会话内）：页面目的地变化统一记录，回退/折返键恢复快照。
    val navHistory = remember { NavHistory() }
    val draftVault = remember { DraftVault() }
    // 8.1.0 第三轮：弹窗改为页内浮层（底栏仍可点、历史键可用），见 AppDialog.kt。
    val dialogHost = remember { AppDialogHostState() }

    /** 把页面状态写回（统一导航与回退/折返恢复共用；不记录历史）。 */
    fun applySnapshot(snapshot: PageSnapshot) {
        tab = snapshot.tab
        todayInboxOpen = snapshot.todayInboxOpen
        planPage = snapshot.planPage
        settingsSubPage = snapshot.settingsSubPage
        settingsBackStack = snapshot.settingsBackStack
    }

    /** 当前页面快照（用于局部修改后 goTo）。 */
    fun pageSnapshot() = PageSnapshot(tab, todayInboxOpen, planPage, settingsSubPage, settingsBackStack)

    /**
     * 8.1.0 第三轮：所有导航统一先走这里（规则见 [TabMotionRules]，纯函数、有单测）。
     * 1) 记录本次导航离开的、正在看子页的页签；
     * 2) 若这次导航把目标页签的子页**收回主页**，标记"该子页切换不补播动画"。
     * 打开子页（深链/通知/跳转）不抑制——那是用户明确要去的地方，应当从图标放大展开。
     */
    fun prepareNavigation(next: PageSnapshot) {
        val current = pageSnapshot()
        leavingSubpageTab = TabMotionRules.departingSubpageTab(current, next)
        if (TabMotionRules.destinationSubpageReset(current, next)) {
            pageSnapTab = next.tab
            pageSnapToken++
        }
    }

    /** 所有页面级导航统一入口：记录历史并应用新目的地；目的地未变化则忽略。 */
    fun goTo(next: PageSnapshot) {
        if (navHistory.goTo(next) == null) return
        prepareNavigation(next)
        applySnapshot(next)
    }

    /** 回退/折返：只走历史栈，不再记录新历史。 */
    fun goBackHistory() {
        lastNavWasJump = false
        navHistory.back()?.let { prepareNavigation(it); applySnapshot(it) }
    }

    fun goForwardHistory() {
        lastNavWasJump = false
        navHistory.forward()?.let { prepareNavigation(it); applySnapshot(it) }
    }

    /** 跨页跳转（通知/深链/课程编辑器跳地点等）：缩放+位移动画。 */
    fun jumpTo(next: PageSnapshot) {
        lastNavWasJump = true
        goTo(next)
    }

    // 回到课程页时恢复被挂起的课程编辑器（新增或编辑，草稿内容由草稿箱恢复）。
    LaunchedEffect(tab, planPage, suspendedCourseEditor) {
        val suspended = suspendedCourseEditor ?: return@LaunchedEffect
        if (tab == 2 && planPage == PlanPage.COURSES) {
            suspendedCourseEditor = null
            val original = suspended.original
            if (original == null) addCourseOpen = true else courseEditor = original
        }
    }

    // 8.1.0 检查更新：下载正式版 APK 到应用缓存并交给系统安装器。
    fun installUpdate(file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
    }

    fun downloadUpdate(url: String, versionName: String): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val file = File(dir, "FocusFlow-$versionName.apk")
        val conn = URL(url).openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 20000
        conn.readTimeout = 120000
        conn.inputStream.use { input -> file.outputStream().use { output -> input.copyTo(output) } }
        return file
    }

    /** 检查 GitHub 正式版；silent 时静默（自动检查），发现新正式版只提示一次、不下载。 */
    fun checkForUpdate(silent: Boolean = false) {
        if (!silent && downloadedUpdate != null) {
            installUpdate(downloadedUpdate!!)
            return
        }
        scope.launch {
            updateCheckState = UpdateCheckState(checking = true, message = "正在检查 GitHub 更新…")
            val release = withContext(Dispatchers.IO) { runCatching { UpdateChecker.fetchLatest(includePrerelease = acceptRcUpdates) }.getOrNull() }
            val latest = release?.versionName
            val newer = latest != null && UpdateChecker.isNewer(BuildConfig.VERSION_NAME, latest, release.isFormal)
            val kind = if (release?.isFormal == true) "正式版" else "候选版"
            if (release == null) {
                updateCheckState = UpdateCheckState(message = "检查失败：无法访问 GitHub")
                return@launch
            }
            if (silent) {
                updateCheckState = UpdateCheckState(latestFormal = latest, message = if (newer) "发现新$kind ${release.versionName}" else "已是最新$kind ${release.versionName}")
                if (newer) {
                    val result = snackbarHostState.showSnackbar(
                        message = "发现新$kind ${release.versionName}，可到 设置 → 检查更新 下载安装",
                        actionLabel = "查看",
                        withDismissAction = true
                    )
                    if (result == SnackbarResult.ActionPerformed) {
                        jumpTo(PageSnapshot(3, todayInboxOpen, planPage, null, emptyList()))
                    }
                }
                return@launch
            }
            if (!newer) {
                updateCheckState = UpdateCheckState(latestFormal = latest, message = "当前已是最新$kind ${release.versionName}")
                return@launch
            }
            val apkUrl = release.apkUrl
            if (apkUrl == null) {
                updateCheckState = UpdateCheckState(latestFormal = latest, message = "发现新$kind ${release.versionName}，请到发布页下载")
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(release.pageUrl))) }
                return@launch
            }
            updateCheckState = UpdateCheckState(checking = true, latestFormal = latest, message = "正在下载 ${release.versionName}…")
            val file = withContext(Dispatchers.IO) { runCatching { downloadUpdate(apkUrl, release.versionName) }.getOrNull() }
            if (file == null) {
                updateCheckState = UpdateCheckState(latestFormal = latest, message = "下载失败，请稍后重试")
            } else {
                downloadedUpdate = file
                updateCheckState = UpdateCheckState(latestFormal = latest, message = "已下载 ${release.versionName}，点击安装")
            }
        }
    }

    // 8.1.0 自动检查更新：开关开启时每次启动检查一次（同一天去重）。
    LaunchedEffect(Unit) {
        if (autoCheckUpdates) {
            val day = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(java.util.Date())
            if (store.loadLastUpdateCheckDay() != day) {
                store.saveLastUpdateCheckDay(day)
                checkForUpdate(silent = true)
            }
        }
    }

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
            jumpTo(PageSnapshot(3, false, null, SettingsSubPage.ACTIVITY_REMINDERS, emptyList()))
        }
    }
    // 提升到 app 层：设置页主列表在子页面往返/切 tab 时保持滚动位置。
    val settingsScrollState = remember { ScrollState(0) }
    val suggestedNextStep = remember(items) {
        items
            .filter { !it.done && it.kind != "收集箱" && it.kind != "暂停" }
            .sortedWith(compareBy<Item> { it.scheduledAt ?: Long.MAX_VALUE }.thenBy { it.title })
            .firstOrNull()
    }
    // 8.1.0 第三轮：这两处是每次重组都会重算的派生值，直接挂在 app 层，
    // 每次导航都会付一遍代价（items 几百条、taskEvents 几千条时尤其明显），改为 remember 缓存。
    val activeCourses = remember(courses, baselineProfile.lifeStage, campusLifeEnabled) {
        if (baselineProfile.lifeStage == LifeStage.HOLIDAY || !campusLifeEnabled) emptyList()
        else CourseActivationPolicy.activeInUpcomingWeek(courses.filter { !it.needsConfirmation })
    }
    val upcomingCommitment = remember(items, activeCourses) { NextActionPlanner.nextCommitment(items, activeCourses) }
    val suggestedNextStepName = upcomingCommitment?.title ?: suggestedNextStep?.title.orEmpty()
    // 全部地点：内置目录或地点包为基底，自定义地点按名去重合并（同名自定义胜出）。
    // 新安装不预置任何校园地点；只有用户导入地点包或自行添加后才参与课程与通勤。
    val basePlaces = campusMapPackage?.places.orEmpty()
    val campusPlaces = if (campusLifeEnabled) basePlaces.filterNot { b -> customPlaces.any { it.name.lowercase() == b.name.lowercase() } || b.name.lowercase() in hiddenPlaces } + customPlaces else emptyList()
    /** 统一处理识别结果：去重、保留冲突为待确认课程并生成提示（计算在 CourseSchedule，只保留保存/状态副作用）。 */
    fun applyRecognizedCourses(recognized: List<Course>) {
        val merge = mergeRecognizedCourses(courses, recognized)
        // 与已确认课程冲突的识别结果也保留为待确认：应用已有冲突警示机制，由用户决定确认/编辑/忽略。
        if (merge.added.isNotEmpty()) {
            val updated = courses + merge.added
            courses = updated
            store.saveCourses(updated)
            navHistory.markWorkedHere()
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
    fun saveItems(updated: List<Item>): Boolean {
        val previous = items
        if (!store.saveItemsIfUnchanged(updated, previous)) {
            items = store.loadItems()
            scope.launch { snackbarHostState.showSnackbar("条目已在通知或其他操作中更新，请重新打开后操作。") }
            return false
        }
        items = updated
        ReminderScheduler.syncTaskReminders(context, previous, updated)
        taskEvents = store.loadTaskEvents()
        navHistory.markWorkedHere()
        return true
    }

    fun saveItemsWithEvents(updated: List<Item>, events: List<TaskEvent>): Boolean {
        if (events.isEmpty()) {
            scope.launch { snackbarHostState.showSnackbar("条目状态已变化，请重新打开后操作。") }
            return false
        }
        val previous = items
        if (!store.saveItemsAndTaskEvents(updated, events, expectedItems = previous)) {
            items = store.loadItems()
            scope.launch { snackbarHostState.showSnackbar("保存失败，尚未确认此次操作；请检查存储空间或数据保护提示。") }
            return false
        }
        items = updated
        ReminderScheduler.syncTaskReminders(context, previous, updated)
        taskEvents = store.loadTaskEvents()
        navHistory.markWorkedHere()
        return true
    }

    fun saveItemsWithEvent(updated: List<Item>, event: TaskEvent?): Boolean =
        event?.let { saveItemsWithEvents(updated, listOf(it)) } ?: run {
            scope.launch { snackbarHostState.showSnackbar("条目状态已变化，请重新打开后操作。") }
            false
        }

    fun saveGoals(updated: List<Goal>): Boolean {
        val previous = goals
        if (!store.saveGoalsIfUnchanged(updated, previous)) {
            goals = store.loadGoals()
            scope.launch { snackbarHostState.showSnackbar("目标已在其他操作中更新，请重新打开后操作。") }
            return false
        }
        goals = updated
        navHistory.markWorkedHere()
        return true
    }

    fun saveItemsAndGoalsWithEvent(updatedItems: List<Item>, updatedGoals: List<Goal>, event: TaskEvent?): Boolean {
        if (event == null) return false
        val previousItems = items
        val previousGoals = goals
        if (!store.saveItemsTaskEventsAndGoals(
                updatedItems, listOf(event), updatedGoals,
                expectedItems = previousItems, expectedGoals = previousGoals
            )) {
            items = store.loadItems()
            goals = store.loadGoals()
            scope.launch { snackbarHostState.showSnackbar("任务或目标已发生变化，请重新打开后操作。") }
            return false
        }
        items = updatedItems
        goals = updatedGoals
        ReminderScheduler.syncTaskReminders(context, previousItems, updatedItems)
        taskEvents = store.loadTaskEvents()
        return true
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
        if (!saveItemsWithEvent(result.items, result.event)) return
        removeScheduledActivity(item.id)
    }
    /** 改期保存的完整动作：数据变换在 TaskActions，事件/基线/提醒/游戏会话同步在 FApp 层（原 saveDelayedItem）。 */
    fun applyDelayed(item: Item, scheduledAt: Long, duration: Int, label: String, priority: String) {
        val storedSessions = store.loadGameSessions()
        val scheduledActivity = storedSessions.firstOrNull { it.id == item.id && it.isOpen() }
        // 兼容 7.1.3 以前活动改期后被误写成普通任务的记录。
        val source = if (scheduledActivity != null && item.kind !in setOf("活动", "游戏")) item.copy(kind = "活动") else item
        val plan = TaskActions.planDelayed(items, source, scheduledAt, duration, label, priority)
        if (!saveItemsWithEvent(plan.items, plan.event)) return
        store.appendBaselineEvent(BaselineRecorder.event(BaselineEventType.TASK_RESCHEDULED, plan.baselinePayload))
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
    val scheduleGoalItem: (Goal, Long) -> Unit = schedule@ { goal, at ->
        val weekday = todayWeekday(at)
        val startMinute = minuteOfDay(at)
        val scheduled = Item(title = goal.title, detail = goalTaskDetail(goal, at), kind = "任务", scheduledAt = at, goalId = goal.id, durationMinutes = goal.durationMinutes)
        if (!saveItemsWithEvent(listOf(scheduled) + items,
                TaskRecorder.event(TaskEventType.TASK_SCHEDULED, scheduled.id, scheduled.title, scheduledAt = at))) return@schedule
        store.appendBaselineEvent(BaselineRecorder.event(BaselineEventType.TASK_SCHEDULED, "${goal.title} · ${weekdayName(weekday)} ${GoalPlanner.displayTime(startMinute)}"))
        ReminderScheduler.scheduleTaskReminder(context, scheduled)
        scope.launch { snackbarHostState.showSnackbar("已把《${goal.title}》排到 ${weekdayName(weekday)} ${GoalPlanner.displayTime(startMinute)}") }
    }
    fun selectTab(index: Int) {
        // Reset only the destination. The outgoing page must survive its exit animation.
        // 目标页签的副页会被重置回主页；"顺带重置"不补播动画，由 prepareNavigation 统一判定。
        lastNavWasJump = false
        goTo(PageSnapshot(
            tab = index,
            todayInboxOpen = if (index == 0) false else todayInboxOpen,
            planPage = if (index == 2) null else planPage,
            settingsSubPage = if (index == 3) null else settingsSubPage,
            settingsBackStack = if (index == 3) emptyList() else settingsBackStack
        ))
    }
    // 8.1.0 两级退出：非今日页主页按返回先回今日主页；今日页主页按返回二次确认退出，可记忆不再提示。
    // 子页返回处理器在本处理器之后组合，子页打开时优先；弹窗宿主也组合在本处理器之后，弹窗打开时返回先关弹窗。
    var lastExitPromptAt by remember { mutableLongStateOf(0L) }
    var exitConfirmDisabled by remember { mutableStateOf(store.loadExitConfirmDisabled()) }
    // 只按"当前页签"判断是否在子页：别的页签遗留的子页状态（切走后保留）不该让本处理器失效，
    // 否则系统返回没有任何处理器接管，会直接退出应用、跳过二次确认。
    val onCurrentSubpage = (tab == 0 && todayInboxOpen) ||
        (tab == 2 && planPage != null) ||
        (tab == 3 && settingsSubPage != null)
    BackHandler(enabled = !onCurrentSubpage) {
        if (tab != 0) {
            lastNavWasJump = false
            goTo(PageSnapshot(0, false, planPage, settingsSubPage, settingsBackStack))
        } else if (exitConfirmDisabled) {
            (context as? android.app.Activity)?.finish()
        } else {
            val now = System.currentTimeMillis()
            if (now - lastExitPromptAt <= EXIT_PROMPT_WINDOW_MS) {
                (context as? android.app.Activity)?.finish()
            } else {
                lastExitPromptAt = now
                scope.launch {
                    val result = snackbarHostState.showSnackbar(
                        message = "再按一次返回键退出应用",
                        actionLabel = "不再提示",
                        withDismissAction = true,
                        // 8.1.0：有 action 时 Material3 默认时长是 Indefinite，会让"提示还在但窗口已过"；
                        // 显式回到 Short，提示生命周期与 EXIT_PROMPT_WINDOW_MS 重新对齐。
                        duration = SnackbarDuration.Short
                    )
                    if (result == SnackbarResult.ActionPerformed) {
                        exitConfirmDisabled = true
                        store.saveExitConfirmDisabled(true)
                    }
                }
            }
        }
    }
    BackHandler(enabled = tab == 0 && todayInboxOpen) { goTo(pageSnapshot().copy(todayInboxOpen = false)) }
    BackHandler(enabled = tab == 2 && planPage != null) { goTo(pageSnapshot().copy(planPage = null)) }
    BackHandler(enabled = tab == 3 && settingsSubPage != null) {
        goTo(pageSnapshot().copy(
            settingsSubPage = settingsBackStack.lastOrNull(),
            settingsBackStack = settingsBackStack.dropLast(1)
        ))
    }

    LaunchedEffect(statusCheckInRequested) {
        if (statusCheckInRequested) {
            jumpTo(PageSnapshot(0, false, planPage, settingsSubPage, settingsBackStack))
            statusCheckInOpen = true
            onRequestHandled()
        }
    }

    LaunchedEffect(quickCaptureRequested) {
        if (quickCaptureRequested) {
            jumpTo(PageSnapshot(0, true, planPage, settingsSubPage, settingsBackStack))
            onRequestHandled()
        }
    }

    LaunchedEffect(mealPromptRequested, mealFinishRequested) {
        if (mealPromptRequested != null || mealFinishRequested != null) {
            jumpTo(PageSnapshot(0, false, planPage, settingsSubPage, settingsBackStack))
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
        ProvideDraftVault(draftVault) {
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
        // 8.1.0 第三轮：弹窗浮层挂在应用根，所有页面的 AppDialog 都能注册进来。
        CompositionLocalProvider(LocalAppDialogHost provides dialogHost) {
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
                    if (globalLoading) FocusFlowProgressBar()
                }
                }
            },
        ) { padding ->
            CompositionLocalProvider(
                LocalFloatingBottomPadding provides if (keyboardVisible) 0.dp else floatingBarHeight,
                LocalScrollingTopPadding provides if (hasTopNotice) 0.dp else topSafety
            ) {
            // Applied and consumed once for both root pages and their animated children.
            // 弹窗打开时页面内容对无障碍不可见（与系统弹窗行为一致）。
            val pageModifier = Modifier.padding(padding).consumeWindowInsets(padding)
                .padding(bottom = if (keyboardVisible) floatingBarHeight else 0.dp)
                .then(if (dialogHost.isOpen) Modifier.clearAndSetSemantics {} else Modifier)
            // 假期或校园生活关闭时，课程不参与今日、日程、空挡与目标建议；原数据仍保留。
            val scheduleCourses = activeCourses
            Box(pageModifier) {
            // 8.1.0 性能+转场：四个页签常驻组合（零重组），切换按导航类型做位移/缩放/淡入转场。
            // 8.1.0 ④/第三轮：收敛原点 = 底栏槽位中心（由真机 UI dump 量得：图标中心 y≈0.878）。
            val collapseOrigins = listOf(
                TransformOrigin(0.171f, 0.878f),
                TransformOrigin(0.335f, 0.878f),
                TransformOrigin(0.664f, 0.878f),
                TransformOrigin(0.829f, 0.878f)
            )
            // 8.1.0 第三轮：启动只组合当前页签（首帧不被三个整页拖慢）；
            // 首帧之后再逐帧补齐其余页签——否则第一次切到某页签时才组合整页，切换会明显掉帧。
            val visitedTabs = remember { mutableStateListOf(0) }
            // 当前页签在组合期就入表：否则切过去的那一帧它还没被组合，页面会空白一帧。
            if (tab !in visitedTabs) visitedTabs.add(tab)
            LaunchedEffect(Unit) {
                withFrameNanos { }
                for (extra in listOf(1, 2, 3)) {
                    if (extra !in visitedTabs) visitedTabs.add(extra)
                    withFrameNanos { }
                }
            }
            val currentSnapshot = PageSnapshot(tab, todayInboxOpen, planPage, settingsSubPage, settingsBackStack)
            listOf(0, 1, 2, 3).forEach { visibleTab ->
            if (visibleTab !in visitedTabs) return@forEach
            val isVisibleTab = visibleTab == tab
            // 8.1.0 第三轮：该页签此刻是否停在子页（决定它离开时收起、回来时从图标放大）。
            val hasSubpageNow = TabMotionRules.subpageOpenOn(visibleTab, currentSnapshot)
            // 8.1.0 第三轮：只有"本次导航离开的那个正在看子页的页签"才播收起动画（见 TabMotionRules）。
            // 主页与主页之间直接切换一律用左右平移，不缩放。
            val collapseLeaving = !isVisibleTab && visibleTab == leavingSubpageTab
            // 8.1.0 第三轮：页签平动幅度加大，切换方向更易读（原 48/64dp 太含蓄）。
            val slidePx = with(LocalDensity.current) {
                (if (lastNavWasJump) MotionSpec.JUMP_SLIDE_DP.dp else MotionSpec.TAB_SLIDE_DP.dp).toPx()
            }
            // 隐藏页签的静止缩放：仍开着子页 → 停在图标大小，回来时从图标放大；否则 1.0，只平移。
            val hiddenScale = TabMotionRules.restingScale(hasSubpageNow, lastNavWasJump)
            // 缩放规格：隐藏时瞬间归位（不可见，不该留动画）；到达时只有"仍开着子页"才放大。
            val scaleSpec: FiniteAnimationSpec<Float> = when {
                collapseLeaving -> MotionSpec.collapseAcross()
                isVisibleTab -> if (TabMotionRules.growsOnArrival(hasSubpageNow)) MotionSpec.grow() else snap()
                else -> snap()
            }
            // 透明度用 Animatable：首次组合（含首次进入某页签）也能淡入，而不是"啪"地出现；
            // 今日页签是启动页，初值直接给 1，避免启动时整页淡入显得更慢。
            val tabAlpha = remember { Animatable(if (visibleTab == 0) 1f else 0f) }
            LaunchedEffect(isVisibleTab, collapseLeaving, hasSubpageNow) {
                tabAlpha.animateTo(
                    if (isVisibleTab) 1f else 0f,
                    when {
                        collapseLeaving -> MotionSpec.collapseAcross()
                        // 从图标放大：直接给满，避免和放大叠加成"淡淡的影子"。
                        isVisibleTab -> if (hasSubpageNow) snap() else MotionSpec.grow()
                        else -> MotionSpec.move()
                    }
                )
            }
            val tabX by animateFloatAsState(
                if (isVisibleTab || collapseLeaving) 0f else if (visibleTab < tab) -slidePx else slidePx,
                MotionSpec.move(),
                label = "tabX$visibleTab"
            )
            val tabScale by animateFloatAsState(
                if (isVisibleTab) 1f else hiddenScale,
                scaleSpec,
                label = "tabScale$visibleTab"
            )
            // 8.1.0 第三轮：正在缩小的副页必须盖在目标主页之上，否则会被目标页的卡片压住。
            // 但它只负责绘制、**不参与输入**（见下方 then 分支）：一旦挂上消费型 pointerInput，
            // 它作为同级最上层命中目标会吞掉可见页签的点击（实测：离开子页后约 400ms 内点不动）。
            val shrinkingOnTop = collapseLeaving
            Box(
                Modifier.fillMaxSize()
                    .zIndex(
                        when {
                            shrinkingOnTop -> 2f
                            isVisibleTab -> 1f
                            else -> 0f
                        }
                    )
                    .graphicsLayer {
                        alpha = tabAlpha.value
                        translationX = tabX
                        scaleX = tabScale
                        scaleY = tabScale
                        // 8.1.0 第三轮：收起时吸进**目标页签**的槽位；从图标放大时从**自己**的槽位长出来。
                        if (collapseLeaving) transformOrigin = collapseOrigins[tab.coerceIn(0, 3)]
                        else if (isVisibleTab && hasSubpageNow) transformOrigin = collapseOrigins[visibleTab]
                    }
                    // 8.1.0 第三轮：完全淡出的页签跳过绘制，只保留组合（切换仍是零重组），省掉不可见的合成开销。
                    .drawWithContent { if (tabAlpha.value > 0.004f) drawContent() }
                    .then(
                        // 收起中的页签也不拦截输入：它只是画在上层，点击应落到可见页签。
                        if (isVisibleTab || collapseLeaving) Modifier
                        else Modifier.clearAndSetSemantics {}.pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                            }
                        }
                    )
            ) {
            CompositionLocalProvider(
                LocalNavCollapseOrigin provides collapseOrigins[visibleTab],
                LocalPageSnapToken provides if (visibleTab == pageSnapTab) pageSnapToken else 0,
                // 子页层读到抑制 token 后立即复位：抑制只作用于"那一次转场"，不再留 360ms 窗口。
                LocalPageSnapConsumed provides consumePageSnap
            ) {
            val pageModifier = Modifier.fillMaxSize()
            when (visibleTab) {
                0 -> TodayScreen(
                    pageModifier, items,
                    inboxOpen = todayInboxOpen,
                    onInboxOpenChange = { goTo(pageSnapshot().copy(todayInboxOpen = it)) },
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
                            // 生活阶段只影响当下参与计算的课程，不替用户开关校园生活。
                            store.appendBaselineEvent(BaselineRecorder.event(BaselineEventType.LIFE_STAGE_SET, stage.label))
                        }
                    },
                    onOpenSchedule = { selectTab(1) },
                    onOpenGoals = { jumpTo(PageSnapshot(2, todayInboxOpen, PlanPage.GOALS, settingsSubPage, settingsBackStack)) },
                    onStartGoalTask = { task ->
                        activityPreset = ActivityLaunchPreset(name = task.title, category = "学习", minutes = task.durationMinutes.coerceIn(5, 360), nextStep = upcomingCommitment?.title.orEmpty(), minimumVersion = false)
                        activityOpen = true
                    },
                    latestStatusCheckIn = latestStatusCheckIn,
                    checkIns = statusCheckIns,
                    onRecordActivity = { activityStatusOpen = true },
                    onTaskDone = { item ->
                        if (item.goalId == null) {
                            if (items.none { it.id == item.id && !it.done }) return@TodayScreen
                            val result = TaskActions.completeNow(items, item)
                            if (saveItemsWithEvent(result.items, result.event) && item.kind in setOf("游戏", "活动")) {
                                recordGameItemEnd(context, store, item.id)
                            }
                        } else completionTarget = item
                    },
                    goals = goals,
                    feedback = feedback,
                    activeSession = activeSession,
                    activityHistory = activityHistory,
                    nextCommitment = upcomingCommitment,
                    commuteProfile = commuteProfile,
                    onCommuteProfileChange = { updated ->
                        commuteProfile = updated
                        store.saveCommuteProfile(updated)
                    },
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
                    onOrganize = { item -> organizeTarget = item },
                    onCreateNextAction = { parent ->
                        val result = TaskActions.createNextAction(items, parent)
                        if (result.created != null) {
                            saveItemsWithEvent(result.items, result.event)
                        }
                    },
                    onRestoreCapture = { item ->
                        val result = TaskActions.restoreCaptureToInbox(items, item)
                        saveItemsWithEvent(result.items, result.event)
                    },
                    onShrink = { item ->
                        val result = TaskActions.shrinkToInbox(items, item)
                        if (saveItemsWithEvent(result.items, result.event)) removeScheduledActivity(item.id)
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
                        if (saveItems(result.items)) removeScheduledActivity(item.id)
                    },
                    onAbandon = { item ->
                        val result = TaskActions.abandon(items, item)
                        if (saveItemsWithEvent(result.items, result.event)) removeScheduledActivity(item.id)
                    },
                    baselineEvents = store.loadBaselineEvents(500),
                    taskEvents = taskEvents,
                    mealRecords = mealRecords,
                    mealReminderEnabled = mealReminderEnabled,
                    statusCheckInEnabled = statusCheckInSettings.enabled,
                    onEnableStatusCheckIn = {
                        val updated = statusCheckInSettings.copy(enabled = true)
                        statusCheckInSettings = updated
                        store.restartEnergySampling()
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
                    pageModifier, items, scheduleCourses, coursePeriodTable, coursePeriodTableConfigured, courseTimetableCompact, courseTimetableTrailingDaysExpanded, commuteProfile,
                    campusLifeEnabled = campusLifeEnabled,
                    onCampusLifeRequired = { scope.launch { snackbarHostState.showSnackbar(CampusLifePolicy.disabledMessage()) } },
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
                        if (item.goalId == null) {
                            val result = TaskActions.completeNow(items, item)
                            if (saveItemsWithEvent(result.items, result.event) && item.kind in setOf("游戏", "活动")) {
                                recordGameItemEnd(context, store, item.id)
                            }
                        } else completionTarget = item
                    },
                    onDeleteItem = { item ->
                        val result = TaskActions.deleteItem(items, item)
                        if (saveItemsWithEvent(result.items, result.event)) removeScheduledActivity(item.id)
                    },
                    onSaveCoursePeriodTable = { table ->
                        coursePeriodTable = table
                        coursePeriodTableConfigured = true
                        CourseGapPlanner.configure(table)
                        store.saveCoursePeriodTable(table)
                    },
                    onCourseTimetableCompactChange = { compact ->
                        courseTimetableCompact = compact
                        store.saveCourseTimetableCompact(compact)
                    },
                    onCourseTimetableTrailingDaysExpandedChange = { expanded ->
                        courseTimetableTrailingDaysExpanded = expanded
                        store.saveCourseTimetableTrailingDaysExpanded(expanded)
                    },
                    onEditCourse = { courseEditor = it }
                )
                2 -> PlansScreen(
                    pageModifier, items, courses, commuteProfile, baselineProfile.lifeStage,
                    campusLifeEnabled = campusLifeEnabled,
                    onCampusLifeRequired = { scope.launch { snackbarHostState.showSnackbar(CampusLifePolicy.disabledMessage()) } },
                    page = planPage,
                                    onPageChange = { goTo(pageSnapshot().copy(planPage = it)); if (it == PlanPage.REVIEW) gameSessions = store.loadGameSessions(); if (it == PlanPage.HISTORY) taskEvents = store.loadTaskEvents() },
                    onResume = { item ->
                        val result = TaskActions.resume(items, item)
                        saveItemsWithEvent(result.items, result.event)
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
                    onToggleCourse = { course ->
                        courses = courses.map { if (it.id == course.id) it.copy(enabled = !it.enabled) else it }
                        store.saveCourses(courses)
                    },
                    onDeleteCourses = { targets ->
                        courses = removeCoursesById(courses, targets)
                        store.saveCourses(courses)
                    },
                    goals = goals,
                    onAddGoal = { goalFinderSuggestion = ""; addGoalOpen = true },
                    onEditGoal = { goal -> goalFinderSuggestion = ""; editGoalTarget = goal },
                    onDeleteGoal = { goal ->
                        if (saveGoals(goals.filterNot { it.id == goal.id })) {
                            scope.launch { snackbarHostState.showSnackbar("已删除目标《${goal.title}》") }
                        }
                    },
                    onScheduleGoal = { goal, suggestion ->
                        scheduleGoalItem(goal, GoalPlanner.nextOccurrence(suggestion.weekday, suggestion.startMinute))
                    },
                    onChooseGoalTime = { goalScheduleTarget = it },
                    onScheduleFlexible = { item, weekday, startMinute ->
                        val target = GoalPlanner.nextOccurrence(weekday, startMinute)
                        val scheduled = item.preservingNote().copy(kind = "任务", scheduledAt = target, dayOnly = false, windowStartAt = null, windowEndAt = null, detail = TaskScheduleText.scheduledDetail(target, item.durationMinutes))
                        if (saveItemsWithEvent(
                                items.map { if (it.id == item.id) scheduled else it },
                                TaskRecorder.event(TaskEventType.TASK_SCHEDULED, scheduled.id, scheduled.title, scheduledAt = target)
                            )) {
                            ReminderScheduler.scheduleTaskReminder(context, scheduled)
                            scope.launch { snackbarHostState.showSnackbar("已把《${item.title}》排到 ${weekdayName(weekday)} ${GoalPlanner.displayTime(startMinute)}") }
                        }
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
                        if (plan.newItems.isNotEmpty()) {
                            val events = plan.newItems.map {
                                TaskRecorder.event(TaskEventType.TASK_SCHEDULED, it.id, it.title, scheduledAt = it.scheduledAt ?: 0)
                            }
                            if (saveItemsWithEvents(plan.newItems + items, events)) {
                                plan.learnedSlots.forEach { (weekday, startHour) -> PlanLearning.recordScheduled(store, weekday, startHour) }
                                plan.newItems.forEach { ReminderScheduler.scheduleTaskReminder(context, it) }
                                autoPlanMessage = plan.message
                            } else {
                                autoPlanMessage = "安排未保存；任务状态刚刚发生变化，请重新生成。"
                            }
                        } else {
                            autoPlanMessage = plan.message
                        }
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
                    onReplaceTaskEvents = { updated ->
                        if (store.replaceTaskEvents(updated)) {
                            taskEvents = updated
                            true
                        } else false
                    },
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
                        goTo(pageSnapshot().copy(settingsSubPage = null, settingsBackStack = emptyList()))
                    } else {
                        goTo(pageSnapshot().copy(
                            settingsSubPage = target,
                            settingsBackStack = NavigationMotion.historyAfterOpen(settingsBackStack, settingsSubPage, target)
                        ))
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
                }, onCampusLifeRequired = {
                    scope.launch { snackbarHostState.showSnackbar(CampusLifePolicy.disabledMessage()) }
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
                    if ((updated.enabled && !statusCheckInSettings.enabled) ||
                        (updated.adaptiveSamplingEnabled && !statusCheckInSettings.adaptiveSamplingEnabled)
                    ) store.restartEnergySampling()
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
                }, onGlobalLoadingChange = { globalLoading = it },
                    exitConfirmDisabled = exitConfirmDisabled,
                    onExitConfirmEnabledChange = { enabled ->
                        exitConfirmDisabled = !enabled
                        store.saveExitConfirmDisabled(!enabled)
                    },
                    autoCheckUpdates = autoCheckUpdates,
                    onAutoCheckUpdatesChange = { enabled ->
                        autoCheckUpdates = enabled
                        store.saveAutoCheckUpdates(enabled)
                    },
                    acceptRcUpdates = acceptRcUpdates,
                    onAcceptRcUpdatesChange = { enabled ->
                        acceptRcUpdates = enabled
                        store.saveAcceptRcUpdates(enabled)
                    },
                    updateCheckState = updateCheckState,
                    onCheckUpdate = { checkForUpdate() },
                    animationSpeed = animationSpeed,
                    onAnimationSpeedChange = { scale ->
                        animationSpeed = scale
                        store.saveAnimationSpeed(scale)
                    })
            }
            }
        }
            } // tab fade layer
            } // primary destinations keep-alive
            } // inset-aware viewport
        } // content padding provider / Scaffold
        // 弹窗浮层在底栏之前：因此弹窗打开时悬浮底栏（含回退／折返键）仍在最上层、可点。
        // 传底栏实测高度：卡片只在底栏之上的区域居中，横屏时底部按钮不会被底栏盖住。
        AppDialogHost(dialogHost, bottomInset = floatingBarHeight)
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
            canGoBack = navHistory.canGoBack(),
            canGoForward = navHistory.canGoForward(),
            onBackHistory = { goBackHistory() },
            onForwardHistory = { goForwardHistory() },
            onLongPressBack = { historyListOpen = true },
            // 弹窗打开时底栏跟着遮罩一起压暗（同一条 Animatable，严格同步）；只改绘制不改可点性。
            dimAmount = { MotionSpec.SCRIM_ALPHA * dialogHost.progress.value },
            // 8.1.0 第三轮：底栏始终在页面之上，副页缩小淡出时从其下方掠过，不被副页盖住。
            modifier = Modifier.align(Alignment.BottomCenter).zIndex(2f).onSizeChanged {
                floatingBarHeight = with(density) { it.height.toDp() }
            }
        )
        if (!hasTopNotice) StatusBarScrim(topSafety, Modifier.align(Alignment.TopCenter))
        // page with overlaid navigation; no full-width bottom surface
        if (addMenuOpen) AddMenuDialog(
            onDismiss = { addMenuOpen = false },
            onQuickCapture = { addMenuOpen = false; addOpen = true },
            onGamePlan = { addMenuOpen = false; gamePlanOpen = true }
        )
        if (gamePlanOpen) GamePlanDialog(
            courses = activeCourses,
            profile = commuteProfile,
            items = items,
            onDismiss = { gamePlanOpen = false },
            onSave = saveGame@ { item, session ->
                // 个性化频率提醒：同一活动在计划当天已安排的次数超过你历史单日习惯时温和提示（有历史才提示，不写死）。
                val sameDayCount = gameSessions.count { it.title == session.title && it.plannedStartAt in dayRange(session.plannedStartAt) }
                val histMax = GameStats.historicalDailyMax(gameSessions, session.title)
                if (!saveItems(listOf(item) + items)) return@saveGame
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
            onSave = capture@ { draft, tomorrow ->
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
                if (!saveItemsWithEvent(listOf(captured) + items,
                        TaskRecorder.event(TaskEventType.TASK_CREATED, captured.id, captured.title, scheduledAt = captured.scheduledAt ?: 0))) return@capture
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
                ReminderScheduler.scheduleDailyStatusCheckIn(context, statusCheckInSettings)
                nextStatusPromptAt = store.loadNextStatusPromptAt()
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
                ReminderScheduler.scheduleDailyStatusCheckIn(context, statusCheckInSettings)
                nextStatusPromptAt = store.loadNextStatusPromptAt()
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
            onSchedule = scheduleInbox@ { startsAt, duration, label, priority ->
                val isNew = items.none { it.id == item.id } // 「直接安排」流：新项未落盘，确认时才创建
                val scheduled = TaskActions.scheduledShape(item, startsAt, duration, label, priority)
                val persistedTime = formatDateTime(startsAt)
                val events = buildList {
                    if (isNew) add(TaskRecorder.event(TaskEventType.TASK_CREATED, scheduled.id, scheduled.title, scheduledAt = 0))
                    add(TaskRecorder.event(TaskEventType.TASK_SCHEDULED, scheduled.id, scheduled.title, scheduledAt = startsAt, extra = persistedTime))
                }
                val updated = if (isNew) listOf(scheduled) + items else items.map { if (it.id == item.id) scheduled else it }
                if (!saveItemsWithEvents(updated, events)) return@scheduleInbox
                store.appendBaselineEvent(BaselineRecorder.event(BaselineEventType.TASK_SCHEDULED, "${item.title.removePrefix("重新安排：")} · $persistedTime"))
                ReminderScheduler.scheduleTaskReminder(context, scheduled)
                inboxScheduleTarget = null
                schedulePresetExact = null
            },
            onKeepWindow = keepWindow@ { start, end, duration, label, priority ->
                val isNew = items.none { it.id == item.id }
                val flexible = TaskActions.flexibleShape(item, start, end, duration, label, priority)
                val persistedRange = "${formatDateTime(start)}–${formatDateTime(end)}"
                val events = buildList {
                    if (isNew) add(TaskRecorder.event(TaskEventType.TASK_CREATED, flexible.id, flexible.title, scheduledAt = 0))
                    add(TaskRecorder.event(TaskEventType.TASK_SCHEDULED, flexible.id, flexible.title, scheduledAt = start, extra = "弹性范围 $persistedRange"))
                }
                val updated = if (isNew) listOf(flexible) + items else items.map { if (it.id == item.id) flexible else it }
                if (!saveItemsWithEvents(updated, events)) return@keepWindow
                store.appendBaselineEvent(BaselineRecorder.event(BaselineEventType.TASK_SCHEDULED, "${item.title.removePrefix("重新安排：")} · 弹性范围 $persistedRange"))
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
            onSelect = selectFlexible@ { suggestion ->
                val scheduled = item.preservingNote().copy(
                    scheduledAt = suggestion.startsAt,
                    durationMinutes = suggestion.durationMinutes,
                    dayOnly = false,
                    windowStartAt = null,
                    windowEndAt = null,
                    detail = "初步安排：${formatDateTime(suggestion.startsAt)} · ${suggestion.durationMinutes} 分钟；可随时改期"
                )
                if (!saveItemsWithEvent(
                        items.map { if (it.id == item.id) scheduled else it },
                        TaskRecorder.event(TaskEventType.TASK_SCHEDULED, scheduled.id, scheduled.title, scheduledAt = suggestion.startsAt, extra = "初步安排")
                    )) return@selectFlexible
                store.appendBaselineEvent(BaselineRecorder.event(BaselineEventType.TASK_SCHEDULED, "${item.title} · 初步安排 ${formatDateTime(suggestion.startsAt)}"))
                ReminderScheduler.scheduleTaskReminder(context, scheduled)
                flexiblePlanTarget = null
            }
        ) }
        inboxEditTarget?.let { item -> InboxEditDialog(item, onDismiss = { inboxEditTarget = null }) editInbox@ { title, detail, durationMinutes, priority ->
            // 编辑不记录事件：统计基于发生的事件，编辑不应污染计划/完成率。
            if (!saveItems(items.map { if (it.id == item.id) it.copy(title = title, detail = detail, userNote = detail, durationMinutes = durationMinutes, priority = priority) else it })) return@editInbox
            inboxEditTarget = null
        } }
        // 自填教学楼自动进入地点库：地点库独立于课程，之后可在地点管理里修改分区/用途（计算在 CampusPlacesEditor）。
        fun ensureCoursePlaceInLibrary(course: Course) {
            val updated = ensurePlaceForCourse(course, campusPlaces, customPlaces) ?: return
            customPlaces = updated
            store.saveCustomPlaces(updated)
        }
        if (addCourseOpen) CourseEditorDialog(null, campusPlaces, maxPeriod = coursePeriodTable.periods.size, onDismiss = { addCourseOpen = false }, onOpenCommutePlaces = {
            addCourseOpen = false; suspendedCourseEditor = SuspendedCourseEditor(null); jumpTo(PageSnapshot(3, todayInboxOpen, planPage, SettingsSubPage.COMMUTE_PLACES, emptyList()))
        }) { course ->
            courses = courses + course.copy(needsConfirmation = false)
            store.saveCourses(courses)
            ensureCoursePlaceInLibrary(course)
            addCourseOpen = false
        }
        courseEditor?.let { original -> CourseEditorDialog(original, campusPlaces, maxPeriod = coursePeriodTable.periods.size, onDismiss = { courseEditor = null }, onOpenCommutePlaces = {
            courseEditor = null; suspendedCourseEditor = SuspendedCourseEditor(original); jumpTo(PageSnapshot(3, todayInboxOpen, planPage, SettingsSubPage.COMMUTE_PLACES, emptyList()))
        }) { edited ->
            courses = courses.map { if (it == original) edited.copy(needsConfirmation = false) else it }
            store.saveCourses(courses)
            ensureCoursePlaceInLibrary(edited)
            courseEditor = null
        } }
        if (addGoalOpen || editGoalTarget != null) GoalEditorDialog(
            initialGoal = editGoalTarget,
            resources = resources,
            suggestedFirstAction = goalFinderSuggestion,
            courses = activeCourses,
            profile = commuteProfile,
            items = items,
            onDismiss = { addGoalOpen = false; editGoalTarget = null; goalFinderSuggestion = "" },
            onOpenFinder = { goalTitle, goalOutcome ->
                finderContext = listOf(goalTitle, goalOutcome).filter { it.isNotBlank() }.joinToString(" ")
                tutorialFinderOpen = true
            }
        ) { goal ->
            val updatedGoals = if (editGoalTarget == null) goals + goal else goals.map { if (it.id == goal.id) goal else it }
            if (saveGoals(updatedGoals)) {
                addGoalOpen = false
                editGoalTarget = null
                goalFinderSuggestion = ""
            }
        }
        organizeTarget?.let { item -> CaptureOrganizeDialog(
            item = item,
            onDismiss = { organizeTarget = null },
            onProgress = { nextAction ->
                val result = TaskActions.routeToProgress(items, item, nextAction)
                if (result.event != null && saveItemsWithEvent(result.items, result.event)) {
                    removeScheduledActivity(item.id)
                    organizeTarget = null
                }
            },
            onReference = {
                val result = TaskActions.routeToReference(items, item)
                if (saveItemsWithEvent(result.items, result.event)) {
                    removeScheduledActivity(item.id)
                    organizeTarget = null
                }
            },
            onConvertToGoal = { organizeTarget = null; convertTarget = item },
            onAttachToPlan = { organizeTarget = null; attachTarget = item }
        ) }
        convertTarget?.let { item -> GoalEditorDialog(
            initialGoal = null,
            initialTitle = item.title,
            initialDurationMinutes = item.durationMinutes,
            initialOutcome = item.editableNote(),
            resources = resources,
            suggestedFirstAction = goalFinderSuggestion.ifBlank { item.nextAction },
            courses = activeCourses,
            profile = commuteProfile,
            items = items,
            onDismiss = { convertTarget = null; goalFinderSuggestion = "" },
            onOpenFinder = { goalTitle, goalOutcome ->
                finderContext = listOf(goalTitle, goalOutcome).filter { it.isNotBlank() }.joinToString(" ")
                tutorialFinderOpen = true
            }
        ) { goal ->
            // 转换后条目不再作为任务保留：目标将自行派生效任务（GoalPlanner）。
            val result = TaskActions.convertToGoal(items, item, goal.title)
            val updatedGoals = goals + goal.copy(sourceNotes = item.editableNote())
            val previousItems = items
            val previousGoals = goals
            if (store.saveGoalConversion(
                    updatedGoals,
                    result.items,
                    result.event!!,
                    expectedGoals = previousGoals,
                    expectedItems = previousItems
                )) {
                goals = updatedGoals
                items = result.items
                ReminderScheduler.syncTaskReminders(context, previousItems, result.items)
                taskEvents = store.loadTaskEvents()
                removeScheduledActivity(item.id)
                convertTarget = null
            } else {
                goals = store.loadGoals()
                items = store.loadItems()
                scope.launch { snackbarHostState.showSnackbar("任务或目标已发生变化，请重新打开后操作。") }
            }
        } }
        attachTarget?.let { item -> AttachToPlanDialog(
            goals = goals,
            onDismiss = { attachTarget = null },
            onAttach = { goal ->
                val result = TaskActions.attachToGoal(items, item, goal)
                if (saveItemsWithEvent(result.items, result.event)) {
                    removeScheduledActivity(item.id)
                    attachTarget = null
                }
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
        completionTarget?.let { item -> CompletionDialog(item, goals.firstOrNull { it.id == item.goalId }, onDismiss = { completionTarget = null }) complete@ { level ->
            val result = TaskActions.completeWithLevel(items, item, level)
            val goalId = item.goalId
            val updatedGoals = if (goalId == null) goals else {
                val key = GoalPlanner.currentWeekKey()
                goals.map { goal -> if (goal.id != goalId) goal else if (goal.completionWeekKey == key) {
                    if (level == "最低版本") goal.copy(minimumCompletionsThisWeek = goal.minimumCompletionsThisWeek + 1) else goal.copy(completedThisWeek = goal.completedThisWeek + 1)
                } else if (level == "最低版本") goal.copy(minimumCompletionsThisWeek = 1, completionWeekKey = key) else goal.copy(completedThisWeek = 1, minimumCompletionsThisWeek = 0, completionWeekKey = key) }
            }
            if (!saveItemsAndGoalsWithEvent(result.items, updatedGoals, result.event)) return@complete
            store.appendBaselineEvent(BaselineRecorder.event(BaselineEventType.TASK_COMPLETED, "${item.title} · $level"))
            // 完成率学习：记录该目标任务所在时段完成一次。
            item.scheduledAt?.let { time ->
                val cal = java.util.Calendar.getInstance().apply { timeInMillis = time }
                PlanLearning.recordCompleted(store, weekdayOf(time), cal.get(java.util.Calendar.HOUR_OF_DAY))
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
        if (campusLifeChoiceOpen) CampusLifeChoiceDialog(
            onEnable = {
                campusLifeEnabled = true
                store.saveCampusLifeEnabled(true)
                store.saveCampusLifeChoiceShown(true)
                campusLifeChoiceOpen = false
            },
            onSkip = {
                campusLifeEnabled = false
                store.saveCampusLifeEnabled(false)
                store.saveCampusLifeChoiceShown(true)
                campusLifeChoiceOpen = false
            }
        )
        if (featureIntroOpen) WelcomeIntroDialog(onDismiss = {
            store.saveLastSeenAppVersion(BuildConfig.VERSION_NAME)
            featureIntroOpen = false
        })
        if (updateNoticeOpen) UpdateNoticeDialog(
            version = BuildConfig.VERSION_NAME,
            onDismiss = { updateNoticeOpen = false },
            onOpenRoadmap = {
                updateNoticeOpen = false
                jumpTo(PageSnapshot(3, false, null, SettingsSubPage.ROADMAP, emptyList()))
            }
        )
        if (baselineEventsOpen) BaselineEventsDialog(
            events = store.loadBaselineEvents(500),
            onDismiss = { baselineEventsOpen = false },
            onClear = {
                store.clearBaselineEvents()
                baselineEventsOpen = false
            },
            onDelete = { eventId -> store.removeBaselineEvent(eventId) }
        )
        if (baselineResetConfirmOpen) AppDialog(
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
        if (historyListOpen) HistoryListDialog(
            entries = navHistory.entries(),
            current = navHistory.current,
            onSelect = { target ->
                lastNavWasJump = false
                // 与 goTo / back / forward 一致：先按 TabMotionRules 判定"谁在离开子页/目标子页是否被改掉"，
                // 否则这条路径会丢掉收起动画、或把本该抑制的补播又播出来。
                if (navHistory.jumpTo(target)) {
                    prepareNavigation(navHistory.current)
                    applySnapshot(navHistory.current)
                }
                historyListOpen = false
            },
            onDismiss = { historyListOpen = false }
        )
        if (mealRecordsOpen) MealRecordsDialog(
            records = mealRecords,
            onDismiss = { mealRecordsOpen = false },
            onDelete = { id ->
                store.deleteMealRecord(id)
                mealRecords = store.loadMealRecords()
            }
        )
        } // CompositionLocalProvider(LocalAppDialogHost)
    }
        }
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
