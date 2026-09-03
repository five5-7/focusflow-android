package com.sakata.focusflow

import org.junit.Assert.assertEquals
import org.junit.Test

class StatusPromptPolicyTest {
    private val now = 1_700_000_000_000L
    private val enabled = StatusCheckInSettings(enabled = true)

    @Test fun `disabled and permission failures explain why no prompt appeared`() {
        assertEquals(StatusPromptOutcome.DISABLED, decide(StatusCheckInSettings()))
        assertEquals(StatusPromptOutcome.NOTIFICATIONS_BLOCKED, decide(enabled, notificationsAllowed = false))
    }

    @Test fun `quiet mute and active session do not masquerade as delivery`() {
        assertEquals(StatusPromptOutcome.MUTED, decide(enabled, muted = true))
        assertEquals(StatusPromptOutcome.QUIET_HOURS, decide(enabled, quiet = true))
        assertEquals(StatusPromptOutcome.ACTIVE_SESSION, decide(enabled, active = ActivitySession(name = "学习", endsAt = now + 60_000L)))
    }

    @Test fun `late prompt is skipped but fresh prompt is ready`() {
        assertEquals(StatusPromptOutcome.TOO_LATE, decide(enabled, expectedAt = now - StatusPromptPolicy.MAX_DELIVERY_DELAY_MILLIS - 1))
        assertEquals(StatusPromptOutcome.READY, decide(enabled, expectedAt = now - 60_000L))
    }

    @Test fun `second prompt is opt in and requires an earlier same day record`() {
        val second = enabled.copy(secondPromptEnabled = true, secondPromptHour = 19)
        assertEquals(StatusPromptOutcome.SECOND_NOT_NEEDED, decide(enabled, promptIndex = 2, latestRecordedAt = now - 5 * 60 * 60_000L, todayCount = 1))
        assertEquals(StatusPromptOutcome.SECOND_NOT_NEEDED, decide(second, promptIndex = 2, latestRecordedAt = null, todayCount = 0))
    }

    @Test fun `second prompt needs four hour gap and caps the day at two records`() {
        val second = enabled.copy(secondPromptEnabled = true, secondPromptHour = 19)
        assertEquals(StatusPromptOutcome.SECOND_NOT_NEEDED, decide(second, promptIndex = 2, latestRecordedAt = now - 3 * 60 * 60_000L, todayCount = 1))
        assertEquals(StatusPromptOutcome.READY, decide(second, promptIndex = 2, latestRecordedAt = now - 4 * 60 * 60_000L, todayCount = 1))
        assertEquals(StatusPromptOutcome.ALREADY_RECORDED, decide(second, promptIndex = 2, latestRecordedAt = now - 5 * 60 * 60_000L, todayCount = 2))
        assertEquals(StatusPromptOutcome.SECOND_NOT_NEEDED, decide(second, promptIndex = 2, latestRecordedAt = now - 5 * 60 * 60_000L, todayCount = 1, secondSlotSamples = 6))
    }

    private fun decide(
        settings: StatusCheckInSettings,
        expectedAt: Long = now,
        notificationsAllowed: Boolean = true,
        muted: Boolean = false,
        quiet: Boolean = false,
        active: ActivitySession? = null,
        promptIndex: Int = 1,
        latestRecordedAt: Long? = null,
        todayCount: Int = 0,
        secondSlotSamples: Int = 0
    ) = StatusPromptPolicy.decide(settings, expectedAt, now, notificationsAllowed, muted, quiet, active, latestRecordedAt, promptIndex, todayCount, secondSlotSamples)
}
