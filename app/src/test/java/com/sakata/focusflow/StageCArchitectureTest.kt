package com.sakata.focusflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StageCArchitectureTest {
    @Test
    fun `fresh install keeps the daily loop first and hides optional modules`() {
        val sections = StageCArchitecture.todaySections(
            visibility = FeatureVisibilityPolicy.daily(FeatureUsageSnapshot()),
            hasActivityHistory = false
        )

        assertEquals(
            listOf(TodaySection.NOW, TodaySection.NEXT, TodaySection.INBOX, TodaySection.PROGRESS),
            sections
        )
        assertFalse(sections.contains(TodaySection.MEALS))
        assertFalse(sections.contains(TodaySection.CAMPUS))
    }

    @Test
    fun `configured optional modules stay below the inbox`() {
        val sections = StageCArchitecture.todaySections(
            visibility = DailyModuleVisibility(
                meals = true,
                goals = true,
                courseBlocks = true,
                campus = true,
                energy = true,
                windDown = true
            ),
            hasActivityHistory = true
        )

        assertEquals(listOf(TodaySection.NOW, TodaySection.NEXT, TodaySection.INBOX), sections.take(3))
        assertTrue(sections.indexOf(TodaySection.MEALS) > sections.indexOf(TodaySection.INBOX))
        assertTrue(sections.indexOf(TodaySection.CAMPUS) > sections.indexOf(TodaySection.INBOX))
    }

    @Test
    fun `settings home exposes one advanced tools gateway`() {
        assertEquals(1, StageCArchitecture.settingsHomeEntries.count { it == SettingsHomeEntry.ADVANCED_TOOLS })
        assertEquals(3, StageCArchitecture.CAPTURE_TO_SCHEDULE_MAX_CLICKS)
    }
}
