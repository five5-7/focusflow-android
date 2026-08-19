package com.sakata.focusflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CourseScheduleTest {
    private val course = Course("高数", 1, 1, 1, "西1教学楼", CampusZone.WEST_TEACHING, needsConfirmation = false)

    @Test fun periodStartAndEnd() {
        assertEquals(480, CourseGapPlanner.periodStart(1))
        assertEquals(525, CourseGapPlanner.periodEnd(1))
    }

    @Test fun freeWindows_afterLastClass() {
        val windows = CourseGapPlanner.freeWindows(listOf(course))
        val monday = windows.filter { it.weekday == 1 }
        assertEquals(1, monday.size)
        assertEquals(525, monday[0].startMinute)
        assertEquals(1320, monday[0].endMinute)
        assertEquals(795, monday[0].minutes)
        assertEquals("课后空闲", monday[0].kind)
    }

    @Test fun freeWindows_occupiedSplitsWindow() {
        val occupied = mapOf(1 to listOf(600 until 660))
        val windows = CourseGapPlanner.freeWindows(listOf(course), occupied = occupied)
        val monday = windows.filter { it.weekday == 1 }
        assertEquals(2, monday.size)
        assertEquals(525, monday[0].startMinute)
        assertEquals(600, monday[0].endMinute)
        assertEquals(660, monday[1].startMinute)
        assertEquals(1320, monday[1].endMinute)
    }

    @Test fun freeWindows_fullWeekWhenNoCourse() {
        val windows = CourseGapPlanner.freeWindows(emptyList())
        assertEquals(7, windows.size)
        assertTrue(windows.all { it.kind == "整天空闲" && it.minutes == 840 })
    }

    @Test fun freeWindows_unconfirmedIgnored() {
        val unconfirmed = course.copy(needsConfirmation = true)
        val windows = CourseGapPlanner.freeWindows(listOf(unconfirmed))
        assertEquals(7, windows.size)
        assertTrue(windows.all { it.kind == "整天空闲" })
    }
}
