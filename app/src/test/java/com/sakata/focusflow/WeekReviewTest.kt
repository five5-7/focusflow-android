package com.sakata.focusflow

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class WeekReviewTest {
    @Test fun weekStartOf_mondayStaysSameDay() {
        val monday = java.util.Calendar.getInstance().apply { clear(); set(2026, 0, 5, 8, 0, 0) }.timeInMillis
        assertEquals(monday - 8 * 3600_000L, WeekReview.weekStartOf(monday))
    }

    @Test fun weekStartOf_sundayGoesBackToMonday() {
        val sunday = java.util.Calendar.getInstance().apply { clear(); set(2026, 0, 11, 20, 0, 0) }.timeInMillis
        val expectedMonday = java.util.Calendar.getInstance().apply { clear(); set(2026, 0, 5, 0, 0, 0) }.timeInMillis
        assertEquals(expectedMonday, WeekReview.weekStartOf(sunday))
    }

    @Test fun weekLabel() {
        val start = java.util.Calendar.getInstance().apply { clear(); set(2026, 0, 5, 0, 0, 0) }.timeInMillis
        assertEquals("1/5", WeekReview.weekLabel(start))
    }

    @Test fun weeksStayOnMondayAcrossSpringDST() {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
            val monday = Calendar.getInstance().apply {
                set(2026, Calendar.MARCH, 9, 0, 0, 0); set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            val starts = WeekReview.weekStarts(monday, 3)
            assertEquals(listOf(23, 2, 9), starts.map {
                Calendar.getInstance().apply { timeInMillis = it }.get(Calendar.DAY_OF_MONTH)
            })
            assertEquals(listOf(Calendar.MONDAY, Calendar.MONDAY, Calendar.MONDAY), starts.map {
                Calendar.getInstance().apply { timeInMillis = it }.get(Calendar.DAY_OF_WEEK)
            })
        } finally {
            TimeZone.setDefault(original)
        }
    }
}
