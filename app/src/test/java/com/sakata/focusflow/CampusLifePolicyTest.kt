package com.sakata.focusflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CampusLifePolicyTest {
    @Test fun disabledCampusKeepsDiscoverableEntriesButHidesDerivedSuggestions() {
        assertEquals(CampusFeatureAccess.PROMPT_TO_ENABLE, CampusLifePolicy.access(false, CampusFeature.COURSE_ENTRY))
        assertEquals(CampusFeatureAccess.PROMPT_TO_ENABLE, CampusLifePolicy.access(false, CampusFeature.COURSE_TIMETABLE))
        assertEquals(CampusFeatureAccess.HIDE, CampusLifePolicy.access(false, CampusFeature.GAP_SUGGESTIONS))
        assertEquals(CampusFeatureAccess.SHOW, CampusLifePolicy.access(false, CampusFeature.COMMUTE_SETTINGS))
    }

    @Test fun enabledCampusShowsEveryCampusFeature() {
        CampusFeature.entries.forEach { feature ->
            assertEquals(CampusFeatureAccess.SHOW, CampusLifePolicy.access(true, feature))
        }
    }

    @Test fun promptNamesTheCompleteSettingsPath() {
        assertTrue(CampusLifePolicy.disabledMessage().contains("设置 → 高级工具 → 通勤与地点 → 校园生活"))
    }

    @Test fun newInstallStartsOffButUpgradeKeepsStoredChoice() {
        assertEquals(false, CampusLifePolicy.initialEnabled(stored = true, featureIntroShown = false, choiceShown = false))
        assertEquals(true, CampusLifePolicy.initialEnabled(stored = true, featureIntroShown = true, choiceShown = false))
        assertEquals(false, CampusLifePolicy.initialEnabled(stored = false, featureIntroShown = true, choiceShown = false))
        assertEquals(true, CampusLifePolicy.initialEnabled(stored = true, featureIntroShown = false, choiceShown = true))
    }
}
