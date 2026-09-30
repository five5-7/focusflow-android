package com.sakata.focusflow.data

import com.sakata.focusflow.CampusZone
import com.sakata.focusflow.Course
import org.junit.Assert.*
import org.junit.Test

class CourseRoomProjectionTest {
    private fun row(id: Long, parent: Long) = Course("线性代数", id.toInt() % 7 + 1,
        1, 2, "东楼", CampusZone.OTHER, needsConfirmation = false, id = id,
        courseId = parent, externalSelectionKeyCandidate = "unverified")

    @Test fun `one parent holds multiple rules and survives deleting its original meeting`() {
        val rows = listOf(row(11, 11), row(19, 11), row(24, 24))
        val parents = rows.toCourseParentEntities()
        val rules = rows.mapIndexed { index, course -> CourseMeetingRuleEntity.fromLegacy(course, index) }
        assertEquals(listOf(11L, 24L), parents.map(CourseEntity::id))
        assertEquals(rows, mapCourseRules(parents, rules))
        val withoutAnchor = rows.drop(1)
        assertEquals(listOf(11L, 24L), withoutAnchor.toCourseParentEntities().map(CourseEntity::id))
        assertEquals(withoutAnchor, mapCourseRules(withoutAnchor.toCourseParentEntities(),
            withoutAnchor.mapIndexed { index, course -> CourseMeetingRuleEntity.fromLegacy(course, index) }))
    }

    @Test fun `parent disagreements and orphan rules are rejected`() {
        val rows = listOf(row(11, 11), row(19, 11))
        assertThrows(IllegalArgumentException::class.java) {
            (rows + rows.last().copy(id = 21, title = "不同标题")).toCourseParentEntities()
        }
        val rules = rows.mapIndexed { index, course -> CourseMeetingRuleEntity.fromLegacy(course, index) }
        assertNull(mapCourseRules(emptyList(), rules))
    }
}
