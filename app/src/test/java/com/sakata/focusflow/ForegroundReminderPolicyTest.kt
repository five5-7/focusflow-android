package com.sakata.focusflow

import org.junit.Assert.assertEquals
import org.junit.Test

class ForegroundReminderPolicyTest {
    @Test fun `feature remains optional and explains missing access`() {
        assertEquals(ForegroundDetectionOutcome.DISABLED, decide(enabled = false))
        assertEquals(ForegroundDetectionOutcome.NO_ACCESS, decide(hasAccess = false))
    }

    @Test fun `same package or matching category is a match`() {
        assertEquals(ForegroundDetectionOutcome.MATCHED, decide(foreground = "game.pkg", scheduled = "game.pkg"))
        assertEquals(ForegroundDetectionOutcome.MATCHED, decide(foreground = "another.game", category = AppCategory.GAME))
    }

    @Test fun `unknown and other app stay conservative`() {
        assertEquals(ForegroundDetectionOutcome.UNKNOWN, decide(foreground = null))
        assertEquals(ForegroundDetectionOutcome.OTHER_APP, decide(foreground = "notes.pkg", category = AppCategory.OTHER))
    }

    private fun decide(
        enabled: Boolean = true,
        hasAccess: Boolean = true,
        foreground: String? = "game.pkg",
        category: AppCategory? = AppCategory.GAME,
        scheduled: String? = null
    ) = ForegroundReminderPolicy.decide(ForegroundDetection.GAME, enabled, hasAccess, foreground, category, scheduled)
}
