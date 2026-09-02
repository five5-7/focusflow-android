package com.sakata.focusflow

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class MealReminderFreshnessTest {
    private fun at(day: Int, hour: Int): Long = Calendar.getInstance().apply {
        clear(); set(2026, Calendar.SEPTEMBER, day, hour, 0, 0)
    }.timeInMillis

    @Test fun promptRequiresCurrentDayAndCurrentSettings() {
        val now = at(2, 12)
        assertTrue(MealReminderFreshness.promptAllowed(true, true, at(2, 11), now, false, false))
        assertFalse(MealReminderFreshness.promptAllowed(false, true, at(2, 11), now, false, false))
        assertFalse(MealReminderFreshness.promptAllowed(true, true, at(1, 11), now, false, false))
        assertFalse(MealReminderFreshness.promptAllowed(true, true, at(2, 11), now, true, false))
        assertFalse(MealReminderFreshness.promptAllowed(true, true, at(2, 11), now, false, true))
    }

    @Test fun mealEndActionOnlyTargetsTheCurrentOpenRecord() {
        val now = at(2, 13)
        val record = MealRecord(id = 8L, mealType = MealType.LUNCH, startedAt = at(2, 12))
        assertTrue(MealReminderFreshness.endAllowed(true, record, 8L, now))
        assertFalse(MealReminderFreshness.endAllowed(true, record, 9L, now))
        assertFalse(MealReminderFreshness.endAllowed(true, record.copy(endedAt = now), 8L, now))
        assertFalse(MealReminderFreshness.endAllowed(true, record.copy(startedAt = at(1, 12)), 8L, now))
    }
}
