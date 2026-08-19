package com.sakata.focusflow

import org.junit.Assert.assertEquals
import org.junit.Test

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
}
