package com.sakata.focusflow

internal enum class CampusFeature { COURSE_ENTRY, COURSE_TIMETABLE, GAP_SUGGESTIONS, COMMUTE_SETTINGS }
internal enum class CampusFeatureAccess { SHOW, PROMPT_TO_ENABLE, HIDE }

/** Keeps the campus-life boundary consistent without deleting or rewriting persisted data. */
internal object CampusLifePolicy {
    const val ENABLE_PATH = "设置 → 高级工具 → 通勤与地点 → 校园生活"

    fun access(enabled: Boolean, feature: CampusFeature): CampusFeatureAccess {
        if (enabled) return CampusFeatureAccess.SHOW
        return when (feature) {
            CampusFeature.COURSE_ENTRY,
            CampusFeature.COURSE_TIMETABLE -> CampusFeatureAccess.PROMPT_TO_ENABLE
            CampusFeature.GAP_SUGGESTIONS -> CampusFeatureAccess.HIDE
            CampusFeature.COMMUTE_SETTINGS -> CampusFeatureAccess.SHOW
        }
    }

    fun disabledMessage(): String = "请在 $ENABLE_PATH 开启校园生活开关"

    fun initialEnabled(stored: Boolean, featureIntroShown: Boolean, choiceShown: Boolean): Boolean =
        if (!featureIntroShown && !choiceShown) false else stored
}
