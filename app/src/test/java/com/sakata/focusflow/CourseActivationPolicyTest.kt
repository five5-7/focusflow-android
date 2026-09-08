package com.sakata.focusflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class CourseActivationPolicyTest {
    private val monday = LocalDate.of(2026, 9, 7).toEpochDay()
    private fun course(day: Int = 1, enabled: Boolean = true, from: Long? = null, until: Long? = null) =
        Course("课程", day, 1, 2, "地点", CampusZone.WEST_TEACHING, false, enabled, from, until)

    @Test fun disabledAndOutOfRangeCoursesAreInactive() {
        assertFalse(CourseActivationPolicy.isActiveOn(course(enabled = false), monday))
        assertFalse(CourseActivationPolicy.isActiveOn(course(from = monday + 1), monday))
        assertFalse(CourseActivationPolicy.isActiveOn(course(until = monday - 1), monday))
        assertTrue(CourseActivationPolicy.isActiveOn(course(from = monday, until = monday), monday))
    }

    @Test fun upcomingWeekUsesTheActualNextWeekday() {
        val friday = course(day = 5, from = monday + 4, until = monday + 4)
        val expiredFriday = course(day = 5, until = monday + 3)
        assertEquals(listOf(friday), CourseActivationPolicy.activeInUpcomingWeek(listOf(friday, expiredFriday), monday))
    }

    @Test fun oldCoursesWithoutDatesStayActive() {
        assertTrue(CourseActivationPolicy.isActiveOn(course(), monday + 3650))
    }
}
