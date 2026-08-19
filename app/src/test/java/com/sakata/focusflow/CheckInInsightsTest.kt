package com.sakata.focusflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CheckInInsightsTest {
    private fun at(hour: Int, minute: Int = 0): Long =
        java.util.Calendar.getInstance().apply { clear(); set(2026, 0, 5, hour, minute, 0) }.timeInMillis

    private fun checkIn(energy: String, hour: Int) = StatusCheckIn(energy, "学习", at(hour))

    @Test fun slotEnergyFor_needsEnoughSamples() {
        assertNull(CheckInInsights.slotEnergyFor(9 * 60, listOf(checkIn("偏低", 8))))
    }

    @Test fun slotEnergyFor_returnsBestEnergyForSlot() {
        val list = listOf(checkIn("偏低", 8), checkIn("偏低", 9), checkIn("充足", 10), checkIn("充足", 20))
        assertEquals("偏低", CheckInInsights.slotEnergyFor(9 * 60, list))  // 上午：偏低 2 vs 充足 1
        assertNull(CheckInInsights.slotEnergyFor(20 * 60, list))          // 晚上只有 1 次样本
    }
}
