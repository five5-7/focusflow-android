package com.sakata.focusflow

/** Stable information architecture for the 6.2 stage-C page reorganization. */
internal enum class TodaySection {
    NOW, NEXT, INBOX, ENERGY, GOALS, PROGRESS, MEALS, ACTIVITY_HISTORY, WIND_DOWN, CAMPUS
}

internal enum class SettingsHomeEntry {
    APPEARANCE, REMINDERS, INTERRUPTION_CONTROL, DAILY_ROUTINES, ADVANCED_TOOLS, HELP, ROADMAP
}

internal object StageCArchitecture {
    const val CAPTURE_TO_SCHEDULE_MAX_CLICKS = 3

    fun todaySections(
        visibility: DailyModuleVisibility,
        hasActivityHistory: Boolean
    ): List<TodaySection> = buildList {
        add(TodaySection.NOW)
        add(TodaySection.NEXT)
        add(TodaySection.INBOX)
        if (visibility.energy) add(TodaySection.ENERGY)
        if (visibility.goals) add(TodaySection.GOALS)
        add(TodaySection.PROGRESS)
        if (visibility.meals) add(TodaySection.MEALS)
        if (hasActivityHistory) add(TodaySection.ACTIVITY_HISTORY)
        if (visibility.windDown) add(TodaySection.WIND_DOWN)
        if (visibility.campus) add(TodaySection.CAMPUS)
    }

    val settingsHomeEntries: List<SettingsHomeEntry> = listOf(
        SettingsHomeEntry.APPEARANCE,
        SettingsHomeEntry.REMINDERS,
        SettingsHomeEntry.INTERRUPTION_CONTROL,
        SettingsHomeEntry.DAILY_ROUTINES,
        SettingsHomeEntry.ADVANCED_TOOLS,
        SettingsHomeEntry.HELP,
        SettingsHomeEntry.ROADMAP
    )
}
