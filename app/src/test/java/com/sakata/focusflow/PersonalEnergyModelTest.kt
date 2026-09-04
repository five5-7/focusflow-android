package com.sakata.focusflow

import org.junit.Assert.assertEquals
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
        val result = PersonalEnergyModel.analyze(at(5, 15), records)
        assertEquals("下午", result.slotLabel)
        assertEquals(4, result.slotSampleCount)
        assertEquals("偏低", result.typicalEnergy)
    }

}
