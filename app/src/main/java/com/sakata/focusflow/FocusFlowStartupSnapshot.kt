package com.sakata.focusflow

/**
 * 首次组合需要的较重本地数据。只负责把 JSON/历史解析移出主线程；保存与前台恢复仍走原有 Store。
 */
internal data class FocusFlowStartupSnapshot(
    val gameSessions: List<GameSessionRecord>,
    val foregroundDetectionTrace: ForegroundDetectionTrace,
    val appCategories: Map<String, String>,
    val hiddenApps: Set<String>,
    val items: List<Item>,
    val taskEvents: List<TaskEvent>,
    val activeSession: ActivitySession?,
    val activityHistory: List<ActivitySession>,
    val statusCheckIns: List<StatusCheckIn>,
    val appearance: AppearanceSpec,
    val customThemeColors: FocusFlowThemeColors?,
    val themePresets: List<ThemePreset>,
    val commuteProfile: CommuteProfile,
    val hiddenPlaces: Set<String>,
    val campusMapPackage: CampusMapPackage?,
    val currentCampusPlace: String?,
    val customPlaces: List<CampusPlace>,
    val campusCenter: CampusCenter,
    val pendingPlaces: List<String>,
    val courses: List<Course>,
    val coursePeriodTable: CoursePeriodTable,
    val coursePeriodTableConfigured: Boolean,
    val goals: List<Goal>,
    val resources: List<LearningResource>,
    val feedback: List<TaskFeedback>,
    val improvementNotes: List<ImprovementNote>,
    val baselineProfile: BaselineProfile,
    val baselineVariants: List<BaselineProfile>,
    val mealRecords: List<MealRecord>,
    val mealSkipDays: Set<String>
) {
    val latestStatusCheckIn: StatusCheckIn? get() = statusCheckIns.lastOrNull()

    companion object {
        fun load(store: PrototypeStore): FocusFlowStartupSnapshot {
            // 恢复错过目标可能追加任务事件，必须先于 taskEvents 读取。
            val items = store.recoverMissedGoalTasks()
            val statusCheckIns = store.loadStatusCheckIns(365)
            return FocusFlowStartupSnapshot(
                gameSessions = store.loadGameSessions(),
                foregroundDetectionTrace = store.loadForegroundDetectionTrace(),
                appCategories = store.loadAppCategories(),
                hiddenApps = store.loadHiddenApps(),
                items = items,
                taskEvents = store.loadTaskEvents(),
                activeSession = store.loadLatestActiveSession(),
                activityHistory = store.loadRecentActivitySessions(),
                statusCheckIns = statusCheckIns,
                appearance = store.loadAppearance(),
                customThemeColors = store.loadCustomThemeColors(),
                themePresets = store.loadThemePresets(),
                commuteProfile = store.loadCommuteProfile(),
                hiddenPlaces = store.loadHiddenPlaces(),
                campusMapPackage = store.loadCampusMapPackage(),
                currentCampusPlace = store.loadCurrentCampusPlace(),
                customPlaces = store.loadCustomPlaces(),
                campusCenter = store.loadCampusCenter(),
                pendingPlaces = store.loadPendingPlaces(),
                courses = if (store.hasCourseSetup()) store.loadCourses() else emptyList(),
                coursePeriodTable = store.loadCoursePeriodTable(),
                coursePeriodTableConfigured = store.hasCoursePeriodTable(),
                goals = store.loadGoals(),
                resources = store.loadResources(),
                feedback = store.loadFeedback(),
                improvementNotes = store.loadImprovementNotes(),
                baselineProfile = store.loadBaselineProfile(),
                baselineVariants = store.loadBaselineVariants(),
                mealRecords = store.loadMealRecords(),
                mealSkipDays = store.loadMealSkipDays()
            )
        }
    }
}
