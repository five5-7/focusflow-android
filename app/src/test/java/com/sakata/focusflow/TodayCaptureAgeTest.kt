package com.sakata.focusflow

import org.junit.Assert.assertEquals
import org.junit.Test

class TodayCaptureAgeTest {
    @Test fun ageLabelsUseElapsedTimeWithoutShowingFutureAges() {
        val now = 2_000_000_000L
        assertEquals("刚刚", captureAgeLabel(now, now))
        assertEquals("刚刚", captureAgeLabel(now + 60_000L, now))
        assertEquals("59分钟前", captureAgeLabel(now - 59 * 60_000L, now))
        assertEquals("1小时前", captureAgeLabel(now - 60 * 60_000L, now))
        assertEquals("1天前", captureAgeLabel(now - 24 * 60 * 60_000L, now))
    }
}
