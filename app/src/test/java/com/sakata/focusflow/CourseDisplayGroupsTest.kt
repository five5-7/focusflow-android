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

    @Test fun `consecutive periods of identical course become one visible span without dropping ids`() {
        val first = course("材料力学", 5, 1, 2)
        val second = course("材料力学", 5, 3, 4)
        val third = course("材料力学", 5, 7, 8)
        val saturday = course("材料力学", 6, 1, 2)
        val spans = connectedCourseSpans(listOf(saturday, second, third, first))
        assertEquals(listOf(1 to 4, 7 to 8, 1 to 2), spans.map { it.display.startPeriod to it.display.endPeriod })
        assertEquals(listOf(first.id, second.id), spans.first().records.map { it.id })
    }

    @Test fun `different place date or confirmation state never join`() {
        val first = course("材料力学", 5, 1, 2)
        val place = course("材料力学", 5, 3, 4).copy(building = "另一间教室")
        val date = course("材料力学", 5, 5, 6).copy(effectiveFromEpochDay = 100)
        val confirmed = course("材料力学", 5, 7, 8).copy(needsConfirmation = false)
        assertEquals(4, connectedCourseSpans(listOf(first, place, date, confirmed)).size)
    }

    @Test fun `trailing summary shows nonconflicting courses and expands colliding periods`() {
        val fridayMorning = course("材料力学", 5, 1, 2)
        val sundayAfternoon = course("其他", 7, 7, 8)
        assertTrue(trailingDaysCanCollapse(listOf(sundayAfternoon, fridayMorning)))
        assertEquals(listOf(fridayMorning.id, sundayAfternoon.id),
            listOf(sundayAfternoon, fridayMorning).sortedWith(courseMeetingOrder).map { it.id })
        assertEquals(false, trailingDaysCanCollapse(listOf(fridayMorning, course("其他", 6, 2, 3))))
    }

    private fun course(title: String, weekday: Int, start: Int, end: Int) = Course(
        title = title, weekday = weekday, startPeriod = start, endPeriod = end,
        building = "地点待确认", zone = CampusZone.OTHER
    )
}
