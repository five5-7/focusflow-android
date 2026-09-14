package com.sakata.focusflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CourseImportPolicyTest {
    @Test
    fun `every imported course returns to pending confirmation`() {
        val batch = CourseImportBatch(
            CourseImportSource.SCHOOL_EXPORT,
            listOf(course(title = "  高等数学  ", confirmed = true))
        )

        val prepared = CourseImportPolicy.prepare(batch)

        assertEquals("高等数学", prepared.courses.single().title)
        assertTrue(prepared.courses.single().needsConfirmation)
    }

    @Test
    fun `invalid rows are removed and periods are bounded`() {
        val batch = CourseImportBatch(
            CourseImportSource.VISION_SCREENSHOT,
            listOf(
                course(title = " "),
                course(title = "英语", weekday = 8),
                course(title = "物理", startPeriod = 3, endPeriod = 25)
            )
        )

        val prepared = CourseImportPolicy.prepare(batch)

        assertEquals(1, prepared.courses.size)
        assertEquals(20, prepared.courses.single().endPeriod)
    }

    @Test
    fun `duplicate rows and discovered places are normalized`() {
        val first = course(title = "数据结构", building = " 西1 ")
        val batch = CourseImportBatch(
            CourseImportSource.SCHOOL_EXPORT,
            listOf(first, first.copy(id = first.id + 1)),
            listOf(" 西1 ", "西1", " ")
        )

        val prepared = CourseImportPolicy.prepare(batch)

        assertEquals(1, prepared.courses.size)
        assertEquals("西1", prepared.courses.single().building)
        assertEquals(listOf("西1"), prepared.newPlaces)
    }

    private fun course(
        title: String,
        weekday: Int = 1,
        startPeriod: Int = 1,
        endPeriod: Int = 2,
        building: String = "东1",
        confirmed: Boolean = false
    ) = Course(
        title = title,
        weekday = weekday,
        startPeriod = startPeriod,
        endPeriod = endPeriod,
        building = building,
        zone = CampusZone.EAST_TEACHING,
        needsConfirmation = !confirmed
    )
}
