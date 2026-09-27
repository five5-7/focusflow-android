package com.sakata.focusflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class CourseReminderPolicyTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val monday = LocalDate.of(2026, 9, 28)
    private val table = CoursePeriodTable(listOf(CoursePeriodTime(8 * 60, 8 * 60 + 45)))
    private val course = Course("物理", 1, 1, 1, "东一", CampusZone.OTHER,
        needsConfirmation = false, id = 42)

    @Test fun `default is off and a single meeting can override either direction`() {
        assertFalse(CourseReminderSettings().enabledFor(course))
        assertTrue(CourseReminderSettings(overrides = mapOf(42L to true)).enabledFor(course))
        assertFalse(CourseReminderSettings(overrides = mapOf(42L to true)).enabledFor(course.copy(id = 43L)))
        assertFalse(CourseReminderSettings(enabled = true, overrides = mapOf(42L to false)).enabledFor(course))
    }

    @Test fun `pending and expired meetings have no alarm`() {
        assertNull(CourseReminderPolicy.startAt(course.copy(needsConfirmation = true), monday, table, zone))
        assertNull(CourseReminderPolicy.startAt(course.copy(effectiveUntilEpochDay = monday.minusDays(1).toEpochDay()), monday, table, zone))
        assertNull(CourseReminderPolicy.startAt(course.copy(enabled = false), monday, table, zone))
        assertNull(CourseReminderPolicy.startAt(course.copy(startPeriod = 2), monday, table, zone))
    }

    @Test fun `rescheduling uses current period start and rolls to next week after advance`() {
        val start = monday.atTime(8, 0).atZone(zone).toInstant().toEpochMilli()
        assertEquals(start - 10 * 60_000L, CourseReminderPolicy.nextTrigger(course, table,
            start - 20 * 60_000L, zone))
        assertEquals(start + 7 * 24 * 60 * 60_000L - 10 * 60_000L,
            CourseReminderPolicy.nextTrigger(course, table, start - 5 * 60_000L, zone))
        val changed = CoursePeriodTable(listOf(CoursePeriodTime(9 * 60, 9 * 60 + 45)))
        assertEquals(start + 50 * 60_000L, CourseReminderPolicy.nextTrigger(course, changed,
            start - 5 * 60_000L, zone))
    }

    @Test fun `future term starts without reopening the application`() {
        val futureMonday = monday.plusWeeks(5)
        val future = course.copy(effectiveFromEpochDay = futureMonday.toEpochDay())
        val now = monday.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val expected = futureMonday.atTime(7, 50).atZone(zone).toInstant().toEpochMilli()
        assertEquals(expected, CourseReminderPolicy.nextTrigger(future, table, now, zone))
    }

    @Test fun `local period start stays at school wall time across daylight saving`() {
        val sunday = course.copy(weekday = 7)
        val day = LocalDate.of(2026, 3, 29)
        val london = ZoneId.of("Europe/London")
        val start = CourseReminderPolicy.startAt(sunday, day, table, london)
        assertEquals(day.atTime(8, 0).atZone(london).toInstant().toEpochMilli(), start)
    }

    @Test fun `startAt rejects mismatched weekday inverted periods and invalid tables`() {
        assertNull(CourseReminderPolicy.startAt(course, monday.plusDays(1), table, zone))
        assertNull(CourseReminderPolicy.startAt(course.copy(endPeriod = 0), monday, table, zone))
        assertNull(CourseReminderPolicy.startAt(course, monday, CoursePeriodTable(emptyList()), zone))
        assertNull(CourseReminderPolicy.startAt(course, monday,
            CoursePeriodTable(List(21) { CoursePeriodTime(it * 60, it * 60 + 30) }), zone))
        assertNull(CourseReminderPolicy.startAt(course, monday,
            CoursePeriodTable(listOf(CoursePeriodTime(600, 650), CoursePeriodTime(620, 700))), zone))
    }

    @Test fun `effectiveFrom gates activation until its first matching day`() {
        val nextMonday = monday.plusWeeks(1)
        val future = course.copy(effectiveFromEpochDay = nextMonday.toEpochDay())
        assertNull(CourseReminderPolicy.startAt(future, monday, table, zone))
        assertNotNull(CourseReminderPolicy.startAt(future, nextMonday, table, zone))
    }

    @Test fun `now exactly at the trigger point rolls to the next week`() {
        val start = monday.atTime(8, 0).atZone(zone).toInstant().toEpochMilli()
        val trigger = start - 10 * 60_000L
        assertEquals(trigger, CourseReminderPolicy.nextTrigger(course, table, trigger - 1, zone))
        assertEquals(start + 7 * 24 * 60 * 60_000L - 10 * 60_000L,
            CourseReminderPolicy.nextTrigger(course, table, trigger, zone))
    }

    @Test fun `london spring transition rolls the weekly trigger with a 167 hour gap`() {
        val london = ZoneId.of("Europe/London")
        val sunday = course.copy(weekday = 7)
        val march22 = LocalDate.of(2026, 3, 22)
        val march29 = LocalDate.of(2026, 3, 29)
        val first = requireNotNull(CourseReminderPolicy.nextTrigger(sunday, table,
            march22.minusDays(1).atStartOfDay(london).toInstant().toEpochMilli(), london))
        val second = requireNotNull(CourseReminderPolicy.nextTrigger(sunday, table, first + 1, london))
        assertEquals(march22.atTime(7, 50).atZone(london).toInstant().toEpochMilli(), first)
        assertEquals(march29.atTime(7, 50).atZone(london).toInstant().toEpochMilli(), second)
        assertEquals(167 * 60 * 60_000L, second - first)
    }
}
