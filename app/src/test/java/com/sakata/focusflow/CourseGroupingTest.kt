package com.sakata.focusflow

import org.junit.Assert.*
import org.junit.Test

class CourseGroupingTest {
    private fun row(id: Long, weekday: Int, start: Int = 1, title: String = "物理实验") = Course(
        title = title, weekday = weekday, startPeriod = start, endPeriod = start + 1,
        building = "东楼", zone = CampusZone.OTHER, id = id,
        externalSchoolYearCode = "2025", externalTermCode = "2",
        externalSelectionKeyCandidate = "candidate"
    )

    @Test fun `candidate alone stays independent until explicit confirmation`() {
        val before = listOf(row(10, 1), row(20, 4))
        assertEquals(listOf(10L, 20L), before.map(Course::courseId))
        val after = requireNotNull(CourseGrouping.confirmImported(before, setOf(10, 20)))
        assertEquals(listOf(10L, 10L), after.map(Course::courseId))
        assertEquals(listOf(10L, 20L), after.map(Course::id))
        assertTrue(after.none(Course::needsConfirmation))
        assertNull(CourseGrouping.confirmImported(before, setOf(10, 99)))
        assertNull(CourseGrouping.confirmImported(after, setOf(10, 20)))
    }

    @Test fun `conflicting provenance or meeting cannot silently form a course`() {
        val first = row(10, 1)
        assertNull(CourseGrouping.confirmImported(listOf(first, row(20, 2).copy(
            externalSelectionKeyCandidate = "other")), setOf(10, 20)))
        assertNull(CourseGrouping.confirmImported(listOf(first, row(20, 1)), setOf(10, 20)))
        assertNull(CourseGrouping.confirmImported(listOf(first, row(20, 2),
            row(30, 1).copy(needsConfirmation = false)), setOf(10, 20)))
    }

    @Test fun `manual link and separation retain every meeting id including former parent`() {
        val before = listOf(row(10, 1), row(20, 4), row(30, 6)).map {
            it.copy(needsConfirmation = false)
        }
        val linked = requireNotNull(CourseGrouping.linkConfirmed(before, setOf(10, 20)))
        assertEquals(listOf(10L, 10L, 30L), linked.map(Course::courseId))
        val separated = requireNotNull(CourseGrouping.separate(linked, 10))
        assertEquals(listOf(10L, 20L, 30L), separated.map(Course::courseId))
        assertEquals(before.map(Course::id), separated.map(Course::id))
        assertNull(CourseGrouping.linkConfirmed(before + row(40, 7, title = "化学").copy(
            needsConfirmation = false), setOf(10, 40)))
    }
}
