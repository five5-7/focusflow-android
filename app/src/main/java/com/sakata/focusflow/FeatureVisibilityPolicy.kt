package com.sakata.focusflow

/** Snapshot used to decide which optional modules deserve space in the daily UI. */
data class FeatureUsageSnapshot(
    val baselineComplete: Boolean = false,
    val mealRecordCount: Int = 0,
    val mealReminderEnabled: Boolean = false,
    val goalCount: Int = 0,
    val confirmedCourseCount: Int = 0,
    val pendingCourseCount: Int = 0,
    val lifeStage: LifeStage? = null,
    val campusLifeEnabled: Boolean = false,
    val statusCheckInEnabled: Boolean = false,
    val statusCheckInCount: Int = 0,
    val windDownEnabled: Boolean = true
)

data class DailyModuleVisibility(
    val meals: Boolean,
    val goals: Boolean,
    val courseBlocks: Boolean,
    val campus: Boolean,
    val energy: Boolean,
    val windDown: Boolean
)

/**
 * Presentation policy only. It never enables background work or changes persisted settings.
 * Management entry points remain available in Plan/Settings even when a daily module is hidden.
 */
object FeatureVisibilityPolicy {
    fun daily(snapshot: FeatureUsageSnapshot): DailyModuleVisibility = DailyModuleVisibility(
        meals = snapshot.mealReminderEnabled && (snapshot.baselineComplete || snapshot.mealRecordCount > 0),
        goals = snapshot.goalCount > 0,
        courseBlocks = snapshot.lifeStage != LifeStage.HOLIDAY && snapshot.confirmedCourseCount > 0,
        campus = snapshot.lifeStage == LifeStage.SCHOOL && snapshot.campusLifeEnabled,
        energy = true,
        windDown = snapshot.windDownEnabled && snapshot.baselineComplete
    )

    fun shouldEmphasizeCourseSetup(snapshot: FeatureUsageSnapshot): Boolean =
        snapshot.lifeStage == LifeStage.SCHOOL &&
            snapshot.confirmedCourseCount == 0 &&
            snapshot.pendingCourseCount == 0
}

enum class RootDestination { TODAY, SCHEDULE, PLAN, SETTINGS }

data class NavigationSnapshot(
    val root: RootDestination = RootDestination.TODAY,
    val todayInboxOpen: Boolean = false,
    val planSubpageOpen: Boolean = false,
    val settingsSubpageOpen: Boolean = false
)

/** Navigation reset rules shared by the future split screen state holders. */
object NavigationPolicy {
    fun selectRoot(current: NavigationSnapshot, target: RootDestination): NavigationSnapshot =
        current.copy(
            root = target,
            todayInboxOpen = if (target == RootDestination.TODAY) current.todayInboxOpen else false,
            planSubpageOpen = false,
            settingsSubpageOpen = false
        )

    fun openInbox(current: NavigationSnapshot): NavigationSnapshot =
        current.copy(root = RootDestination.TODAY, todayInboxOpen = true)

    fun openPlanSubpage(current: NavigationSnapshot): NavigationSnapshot =
        current.copy(root = RootDestination.PLAN, todayInboxOpen = false, planSubpageOpen = true, settingsSubpageOpen = false)

    fun openSettingsSubpage(current: NavigationSnapshot): NavigationSnapshot =
        current.copy(root = RootDestination.SETTINGS, todayInboxOpen = false, planSubpageOpen = false, settingsSubpageOpen = true)

    fun back(current: NavigationSnapshot): NavigationSnapshot? = when {
        current.todayInboxOpen -> current.copy(todayInboxOpen = false)
        current.planSubpageOpen -> current.copy(planSubpageOpen = false)
        current.settingsSubpageOpen -> current.copy(settingsSubpageOpen = false)
        else -> null
    }
}
