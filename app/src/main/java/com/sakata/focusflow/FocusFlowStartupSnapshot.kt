package com.sakata.focusflow

import com.sakata.focusflow.data.CoreDataConsistencyChecker
import com.sakata.focusflow.data.CoreDataConsistencyReport
import com.sakata.focusflow.data.CoreDataReadResult
import com.sakata.focusflow.data.CoreDataRepository
import com.sakata.focusflow.data.CoreDataRepositoryOperations

/**
 * 首次组合需要的较重本地数据。核心任务快照通过统一 Repository 读取，其他设置域仍由 Store 提供。
 */
internal data class FocusFlowStartupSnapshot(
    val gameSessions: List<GameSessionRecord>,
    val gameDetectionEnabled: Boolean,
    val foregroundDetectionTrace: ForegroundDetectionTrace,
    val activitySettings: ActivityReminderSettings,
    val statusCheckInSettings: StatusCheckInSettings,
    val statusPromptTrace: StatusPromptTrace,
    val nextStatusPromptAt: Long,
    val quietHours: QuietHoursSettings,
    val quickCaptureEnabled: Boolean,
    val windDownEnabled: Boolean,
    val appCategories: Map<String, String>,
    val hiddenApps: Set<String>,
    val items: List<Item>,
    val taskEvents: List<TaskEvent>,
    val coreDataConsistency: CoreDataConsistencyReport?,
    val activeSession: ActivitySession?,
    val activityHistory: List<ActivitySession>,
    val statusCheckIns: List<StatusCheckIn>,
    val themeOption: FocusFlowThemeOption,
    val darkMode: Boolean,
    val animationSpeed: Float,
    val appearance: AppearanceSpec,
    val customThemeColors: FocusFlowThemeColors?,
    val themePresets: List<ThemePreset>,
    val energyLevel: String,
    val energyRecordedAt: Long,
    val commuteProfile: CommuteProfile,
    val campusLifeEnabled: Boolean,
    val hiddenPlaces: Set<String>,
    val campusMapPackage: CampusMapPackage?,
    val currentCampusPlace: String?,
    val customPlaces: List<CampusPlace>,
    val campusCenter: CampusCenter,
    val amapKey: String,
    val tutorialSearch: TutorialSearchSettings,
    val aiWeeklySummary: AiWeeklySummarySettings,
    val videoAnalysisModel: String,
    val courseVision: CourseVisionSettings,
    val courseVisionGuideShown: Boolean,
    val pendingPlaces: List<String>,
    val courses: List<Course>,
    val coursePeriodTable: CoursePeriodTable,
    val coursePeriodTableConfigured: Boolean,
    val courseTimetableCompact: Boolean,
    val courseTimetableTrailingDaysExpanded: Boolean,
    val goals: List<Goal>,
    val resources: List<LearningResource>,
    val feedback: List<TaskFeedback>,
    val improvementNotes: List<ImprovementNote>,
    val baselineProfile: BaselineProfile,
    val baselineVariants: List<BaselineProfile>,
    val onboardingDone: Boolean,
    val featureIntroShown: Boolean,
    val campusLifeChoiceShown: Boolean,
    val lastSeenAppVersion: String?,
    val mealRecords: List<MealRecord>,
    val mealReminderEnabled: Boolean,
    val mealDurationTrackingEnabled: Boolean,
    val mealSkipDays: Set<String>,
    val autoCheckUpdates: Boolean,
    val acceptRcUpdates: Boolean,
    val lastUpdateCheckDay: String,
    val exitConfirmDisabled: Boolean
) {
    val latestStatusCheckIn: StatusCheckIn? get() = statusCheckIns.lastOrNull()

    companion object {
        fun load(
            store: PrototypeStore,
            coreDataRepository: CoreDataRepository,
            shadowReader: (() -> CoreDataReadResult)? = null
        ): FocusFlowStartupSnapshot {
            // 恢复错过目标可能追加任务事件，必须先于 taskEvents 读取。
            val coreData = (CoreDataRepositoryOperations.recoverMissedGoalTasks(coreDataRepository)
                as CoreDataReadResult.Ready).snapshot
            val consistency = shadowReader?.invoke()?.let { room ->
                CoreDataConsistencyChecker.compare(coreData, room)
            }
            val statusCheckIns = store.loadStatusCheckIns(365)
            val themeOption = store.loadTheme()
            val featureIntroShown = store.loadFeatureIntroShown()
            val campusLifeChoiceShown = store.loadCampusLifeChoiceShown()
            return FocusFlowStartupSnapshot(
                gameSessions = store.loadGameSessions(),
                gameDetectionEnabled = store.loadGameDetectionEnabled(),
                foregroundDetectionTrace = store.loadForegroundDetectionTrace(),
                activitySettings = store.loadActivityReminderSettings(),
                statusCheckInSettings = store.loadStatusCheckInSettings(),
                statusPromptTrace = store.loadStatusPromptTrace(),
                nextStatusPromptAt = store.loadNextStatusPromptAt(),
                quietHours = store.loadQuietHoursSettings(),
                quickCaptureEnabled = store.loadQuickCaptureEnabled(),
                windDownEnabled = store.loadWindDownEnabled(),
                appCategories = store.loadAppCategories(),
                hiddenApps = store.loadHiddenApps(),
                items = coreData.items,
                taskEvents = coreData.taskEvents,
                coreDataConsistency = consistency,
                activeSession = store.loadLatestActiveSession(),
                activityHistory = store.loadRecentActivitySessions(),
                statusCheckIns = statusCheckIns,
                themeOption = themeOption,
                darkMode = store.loadDarkMode(),
                animationSpeed = store.loadAnimationSpeed(),
                appearance = store.loadAppearance(),
                customThemeColors = store.loadCustomThemeColors(),
                themePresets = store.loadThemePresets(),
                energyLevel = store.loadEnergyLevel(),
                energyRecordedAt = store.loadEnergyRecordedAt(),
                commuteProfile = store.loadCommuteProfile(),
                campusLifeEnabled = CampusLifePolicy.initialEnabled(
                    stored = store.loadCampusLifeEnabled(),
                    featureIntroShown = featureIntroShown,
                    choiceShown = campusLifeChoiceShown
                ),
                hiddenPlaces = store.loadHiddenPlaces(),
                campusMapPackage = store.loadCampusMapPackage(),
                currentCampusPlace = store.loadCurrentCampusPlace(),
                customPlaces = store.loadCustomPlaces(),
                campusCenter = store.loadCampusCenter(),
                amapKey = store.loadAmapKey(),
                tutorialSearch = store.loadTutorialSearchSettings(),
                aiWeeklySummary = store.loadAiWeeklySummarySettings(),
                videoAnalysisModel = store.loadVideoAnalysisModel(),
                courseVision = store.loadCourseVisionSettings(),
                courseVisionGuideShown = store.loadCourseVisionGuideShown(),
                pendingPlaces = store.loadPendingPlaces(),
                courses = if (store.hasCourseSetup()) store.loadCourses() else emptyList(),
                coursePeriodTable = store.loadCoursePeriodTable(),
                coursePeriodTableConfigured = store.hasCoursePeriodTable(),
                courseTimetableCompact = store.loadCourseTimetableCompact(),
                courseTimetableTrailingDaysExpanded = store.loadCourseTimetableTrailingDaysExpanded(),
                goals = coreData.goals,
                resources = store.loadResources(),
                feedback = store.loadFeedback(),
                improvementNotes = store.loadImprovementNotes(),
                baselineProfile = store.loadBaselineProfile(),
                baselineVariants = store.loadBaselineVariants(),
                onboardingDone = store.loadOnboardingDone(),
                featureIntroShown = featureIntroShown,
                campusLifeChoiceShown = campusLifeChoiceShown,
                lastSeenAppVersion = store.loadLastSeenAppVersion(),
                mealRecords = store.loadMealRecords(),
                mealReminderEnabled = store.loadMealReminderEnabled(),
                mealDurationTrackingEnabled = store.loadMealDurationTrackingEnabled(),
                mealSkipDays = store.loadMealSkipDays(),
                autoCheckUpdates = store.loadAutoCheckUpdates(),
                acceptRcUpdates = store.loadAcceptRcUpdates(),
                lastUpdateCheckDay = store.loadLastUpdateCheckDay(),
                exitConfirmDisabled = store.loadExitConfirmDisabled()
            )
        }
    }
}
