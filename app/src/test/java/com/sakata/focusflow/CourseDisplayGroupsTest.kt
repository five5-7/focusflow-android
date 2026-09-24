package com.sakata.focusflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CourseDisplayGroupsTest {
    @Test fun `same title is visually collected with weekday then period order and original ids`() {
        val saturday = course("材料力学", 6, 1, 2)
        val fridayLate = course("材料力学", 5, 7, 8)
        val fridayMorning = course("材料力学", 5, 1, 2)
        val unrelated = course("其他", 1, 3, 4)

        val groups = groupCourseMeetings(listOf(saturday, fridayLate, unrelated, fridayMorning))
        assertEquals(listOf("其他", "材料力学"), groups.map { it.title })
        assertEquals(listOf(fridayMorning.id, fridayLate.id, saturday.id), groups[1].meetings.map { it.id })
        assertTrue(groups[1].meetings.all { it.needsConfirmation })
    }

    private fun course(title: String, weekday: Int, start: Int, end: Int) = Course(
        title = title, weekday = weekday, startPeriod = start, endPeriod = end,
        building = "地点待确认", zone = CampusZone.OTHER
    )
}
