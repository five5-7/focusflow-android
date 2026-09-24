package com.sakata.focusflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SchoolCourseSyncTest {
    @Test
    fun `bulk confirmation keeps overlapping or disabled meetings pending`() {
        val safeFriday = course("材料力学", 5, 1, 2, "东1", confirmed = false)
        val safeSaturday = course("材料力学", 6, 1, 2, "东1", confirmed = false)
        val conflicting = course("另一门", 5, 5, 6, "东2", confirmed = false)
        val existing = course("已有课", 5, 6, 7, "东3", confirmed = true)
        val disabled = course("停用课", 2, 1, 2, "东1", confirmed = false).copy(enabled = false)

        assertEquals(
            setOf(safeFriday.id, safeSaturday.id),
            CourseConfirmationSafety.safeBatchConfirmationIds(listOf(safeFriday, safeSaturday, conflicting, existing, disabled))
        )
    }

    @Test
    fun `unique official match updates existing course while preserving user state`() {
        val old = course("高等数学", 1, 1, 2, "东1", confirmed = true)
            .copy(enabled = false, effectiveFromEpochDay = 100, effectiveUntilEpochDay = 200)
        val incoming = course("高等数学", 1, 3, 4, "东2", confirmed = false)

        val result = syncSchoolCourses(listOf(old), listOf(incoming))
        val updated = result.courses.single()

        assertEquals(old.id, updated.id)
        assertEquals(3, updated.startPeriod)
        assertEquals("东2", updated.building)
        assertFalse(updated.needsConfirmation)
        assertFalse(updated.enabled)
        assertEquals(100L, updated.effectiveFromEpochDay)
        assertEquals(200L, updated.effectiveUntilEpochDay)
        assertEquals(1, result.updatedCount)
        assertEquals(0, result.addedCount)
    }

    @Test
    fun `ambiguous same day courses are added as candidates instead of overwritten`() {
        val first = course("专题讨论", 2, 1, 2, "东1", confirmed = true)
        val second = course("专题讨论", 2, 3, 4, "东2", confirmed = true)
        val incoming = course("专题讨论", 2, 5, 6, "东3", confirmed = false)

        val result = syncSchoolCourses(listOf(first, second), listOf(incoming))

        assertEquals(3, result.courses.size)
        assertEquals(1, result.addedCount)
        assertTrue(result.courses.last().needsConfirmation)
        assertEquals(listOf(1, 3), result.courses.take(2).map { it.startPeriod })
    }

    @Test
    fun `exact match refreshes location without creating duplicate`() {
        val old = course("大学英语", 4, 7, 8, "西1", confirmed = true)
        val incoming = course("大学英语", 4, 7, 8, "西2", confirmed = false)

        val result = syncSchoolCourses(listOf(old), listOf(incoming))

        assertEquals(1, result.courses.size)
        assertEquals("西2", result.courses.single().building)
        assertEquals(1, result.updatedCount)
    }

    private fun course(
        title: String,
        weekday: Int,
        start: Int,
        end: Int,
        building: String,
        confirmed: Boolean
    ) = Course(
        title = title,
        weekday = weekday,
        startPeriod = start,
        endPeriod = end,
        building = building,
        zone = CourseScreenshotParser.zoneByPrefix(building),
        needsConfirmation = !confirmed
    )
}
