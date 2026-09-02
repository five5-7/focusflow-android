package com.sakata.focusflow

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityReminderFreshnessTest {
    private fun session(status: String = ActivitySession.STATUS_ACTIVE, endsAt: Long = 20_000L) =
        ActivitySession(id = 7L, name = "学习", endsAt = endsAt, status = status)

    @Test fun currentOpenSessionMatchesItsScheduledEnd() {
        assertTrue(ActivityReminderFreshness.matches(session(), 20_000L))
    }

    @Test fun oldAlarmAfterExtensionIsRejected() {
        assertFalse(ActivityReminderFreshness.matches(session(endsAt = 30_000L), 20_000L))
    }

    @Test fun endedOrMissingSessionIsRejected() {
        assertFalse(ActivityReminderFreshness.matches(session(ActivitySession.STATUS_COMPLETED), 20_000L))
        assertFalse(ActivityReminderFreshness.matches(null, 20_000L))
    }

    @Test fun legacyAlarmWithoutEndTimestampStillChecksOpenSession() {
        assertTrue(ActivityReminderFreshness.matches(session(), -1L))
    }
}
