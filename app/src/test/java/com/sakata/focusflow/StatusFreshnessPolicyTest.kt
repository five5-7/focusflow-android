package com.sakata.focusflow

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class StatusFreshnessPolicyTest {
    private fun at(day: Int, hour: Int): Long = Calendar.getInstance().apply {
        clear(); set(2026, Calendar.SEPTEMBER, day, hour, 0, 0)
    }.timeInMillis

    @Test fun recentSameDayEnergyIsCurrent() {
        assertTrue(StatusFreshnessPolicy.isCurrent(at(2, 10), at(2, 14)))
    }

    @Test fun oldOrPreviousDayEnergyIsNotCurrent() {
        assertFalse(StatusFreshnessPolicy.isCurrent(at(2, 7), at(2, 14)))
        assertFalse(StatusFreshnessPolicy.isCurrent(at(1, 23), at(2, 1)))
    }

    @Test fun missingAndFutureRecordsAreNotCurrent() {
        assertFalse(StatusFreshnessPolicy.isCurrent(0L, at(2, 14)))
        assertFalse(StatusFreshnessPolicy.isCurrent(at(2, 15), at(2, 14)))
    }
}
