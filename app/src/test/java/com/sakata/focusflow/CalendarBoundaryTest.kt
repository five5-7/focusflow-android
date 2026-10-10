package com.sakata.focusflow

import java.time.LocalDateTime
import java.time.ZoneId
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CalendarBoundaryTest {
    private lateinit var previousZone: TimeZone
    @Before fun setUp() {
        previousZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
    }
    @After fun tearDown() { TimeZone.setDefault(previousZone) }

    @Test fun springDayEndsAtNextMidnightAfterTwentyThreeHours() {
        val range = dayRange(at("2026-03-08T12:00"))
        assertEquals(23 * 60 * 60_000L, range.last + 1 - range.first)
        assertTrue(at("2026-03-08T23:59:59") in range)
        assertFalse(at("2026-03-09T00:00") in range)
    }

    @Test fun autumnDayIncludesLastHourOfTwentyFiveHourDay() {
        val range = dayRange(at("2026-11-01T12:00"))
        assertEquals(25 * 60 * 60_000L, range.last + 1 - range.first)
        assertTrue(at("2026-11-01T23:30") in range)
        assertFalse(at("2026-11-02T00:00") in range)
    }

    @Test fun activityWeekExcludesNextMondayWhenSpringWeekIsShorter() {
        val sunday = record(at("2026-03-08T23:30"))
        val monday = record(at("2026-03-09T00:30"))
        assertEquals(listOf(sunday), GameStats.thisWeek(listOf(sunday, monday), at("2026-03-02T00:00")))
    }

    @Test fun activityWeekIncludesSundayLastHourWhenAutumnWeekIsLonger() {
        val sunday = record(at("2026-11-01T23:30"))
        val monday = record(at("2026-11-02T00:00"))
        assertEquals(listOf(sunday), GameStats.thisWeek(listOf(sunday, monday), at("2026-10-26T00:00")))
    }

    @Test fun ordinaryLocalDayRemainsTwentyFourHours() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
        val range = dayRange(at("2026-10-10T12:00"))
        assertEquals(24 * 60 * 60_000L, range.last + 1 - range.first)
        assertFalse(at("2026-10-11T00:00") in range)
    }

    private fun at(value: String) = LocalDateTime.parse(value).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    private fun record(start: Long) = GameSessionRecord(
        id = start, title = "活动", packageName = null, plannedStartAt = start, plannedEndAt = start + 30 * 60_000L
    )
}
