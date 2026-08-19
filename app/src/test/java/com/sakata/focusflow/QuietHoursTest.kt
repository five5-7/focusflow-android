package com.sakata.focusflow

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuietHoursTest {
    private fun at(hour: Int, minute: Int = 0): Long =
        java.util.Calendar.getInstance().apply { clear(); set(2026, 0, 5, hour, minute, 0) }.timeInMillis

    @Test fun inQuietHours_crossMidnight() {
        val s = QuietHoursSettings(enabled = true, startMinute = 23 * 60, endMinute = 7 * 60)
        assertTrue(s.inQuietHours(at(23, 30)))
        assertTrue(s.inQuietHours(at(3, 0)))
        assertFalse(s.inQuietHours(at(12, 0)))
    }

    @Test fun inQuietHours_disabledNever() {
        val s = QuietHoursSettings(enabled = false, startMinute = 23 * 60, endMinute = 7 * 60)
        assertFalse(s.inQuietHours(at(23, 30)))
    }

    @Test fun isMuted_untilTime() {
        val s = QuietHoursSettings(muteUntil = at(12, 0))
        assertTrue(s.isMuted(at(11, 0)))
        assertFalse(s.isMuted(at(13, 0)))
    }

    @Test fun suppresses_byType() {
        val s = QuietHoursSettings()
        assertTrue(QuietHoursSettings.suppresses(s, ReminderReceiver.ACTION_STATUS_CHECK_IN))
        assertTrue(QuietHoursSettings.suppresses(s, ReminderReceiver.ACTION_MEAL_REMINDER))
        assertTrue(QuietHoursSettings.suppresses(s, ReminderReceiver.ACTION_WIND_DOWN))
        assertFalse(QuietHoursSettings.suppresses(s, ReminderReceiver.ACTION_ACTIVITY_END))
    }
}
