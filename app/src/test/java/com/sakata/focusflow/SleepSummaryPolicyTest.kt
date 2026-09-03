package com.sakata.focusflow

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SleepSummaryPolicyTest {
    private val now = 1_700_000_000_000L

    @Test fun recentSummary_isShownWithoutOverridingEnergy() {
        val summary = SleepSummary(now - 8 * 60 * 60_000L, now - 60_000L, 479, "provider", now)
        assertNotNull(SleepSummaryPolicy.display(summary, now))
    }

    @Test fun staleOrFutureSummary_isIgnored() {
        val stale = SleepSummary(now - 50 * 60 * 60_000L, now - 40 * 60 * 60_000L, 600, "provider", now)
        val future = SleepSummary(now, now + 10 * 60_000L, 480, "provider", now)
        assertNull(SleepSummaryPolicy.display(stale, now))
        assertNull(SleepSummaryPolicy.display(future, now))
    }
}
