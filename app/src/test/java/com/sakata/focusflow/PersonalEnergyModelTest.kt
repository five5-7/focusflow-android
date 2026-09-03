package com.sakata.focusflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar

class PersonalEnergyModelTest {
    private fun at(day: Int, hour: Int): Long = Calendar.getInstance().apply {
        clear()
        set(2026, Calendar.SEPTEMBER, day, hour, 0, 0)
    }.timeInMillis

    @Test fun slotBaseline_doesNotMixMorningAndAfternoon() {
        val records = listOf(
            StatusCheckIn("偏低", "学习", at(1, 14)),
            StatusCheckIn("偏低", "学习", at(2, 15)),
            StatusCheckIn("偏低", "学习", at(3, 16)),
            StatusCheckIn("正常", "学习", at(4, 14)),
            StatusCheckIn("充足", "学习", at(1, 9)),
            StatusCheckIn("充足", "学习", at(2, 10))
        )
        val result = PersonalEnergyModel.analyze(at(5, 15), records, emptyList())
        assertEquals("下午", result.slotLabel)
        assertEquals(4, result.slotSampleCount)
        assertEquals("偏低", result.typicalEnergy)
    }

    @Test fun sleepAssociation_comparesOnlyPairedRecordsInSameSlot() {
        val records = listOf(
            StatusCheckIn("偏低", "学习", at(2, 14)),
            StatusCheckIn("偏低", "学习", at(3, 14)),
            StatusCheckIn("正常", "学习", at(4, 14)),
            StatusCheckIn("充足", "学习", at(5, 14))
        )
        val sleeps = listOf(
            sleepEndingBefore(records[0], 5 * 60), sleepEndingBefore(records[1], 5 * 60 + 30),
            sleepEndingBefore(records[2], 7 * 60), sleepEndingBefore(records[3], 8 * 60)
        )
        assertNotNull(PersonalEnergyModel.analyze(at(6, 14), records, sleeps).sleepComparison)
        assertNull(PersonalEnergyModel.analyze(at(6, 9), records, sleeps).sleepComparison)
    }

    @Test fun weakDifference_producesNoSleepClaim() {
        val records = listOf(
            StatusCheckIn("偏低", "学习", at(2, 14)),
            StatusCheckIn("正常", "学习", at(3, 14)),
            StatusCheckIn("偏低", "学习", at(4, 14)),
            StatusCheckIn("正常", "学习", at(5, 14))
        )
        val sleeps = listOf(
            sleepEndingBefore(records[0], 5 * 60), sleepEndingBefore(records[1], 5 * 60 + 30),
            sleepEndingBefore(records[2], 7 * 60), sleepEndingBefore(records[3], 8 * 60)
        )
        assertNull(PersonalEnergyModel.analyze(at(6, 14), records, sleeps).sleepComparison)
    }

    private fun sleepEndingBefore(checkIn: StatusCheckIn, minutes: Int): SleepSummary {
        val end = checkIn.recordedAt - 60 * 60_000L
        return SleepSummary(end - minutes * 60_000L, end, minutes, "watch", checkIn.recordedAt)
    }
}
