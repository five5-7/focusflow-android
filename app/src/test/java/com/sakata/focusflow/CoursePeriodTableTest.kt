package com.sakata.focusflow

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoursePeriodTableTest {
    @After fun restoreReferenceTable() {
        CourseGapPlanner.configure(CoursePeriodTable.reference())
    }

    @Test fun `reference table preserves existing course times`() {
        val table = CoursePeriodTable.reference()
        assertEquals(13, table.periods.size)
        assertEquals(CoursePeriodTime(480, 525), table.periods.first())
        assertTrue(table.isValid())
    }

    @Test fun `custom table drives shared course time conversion`() {
        val table = CoursePeriodTable(listOf(CoursePeriodTime(510, 560), CoursePeriodTime(570, 620)))
        CourseGapPlanner.configure(table)
        assertEquals(510, CourseGapPlanner.periodStart(1))
        assertEquals(620, CourseGapPlanner.periodEnd(2))
    }

    @Test fun `overlapping or reversed periods are rejected`() {
        assertFalse(CoursePeriodTable(listOf(CoursePeriodTime(500, 480))).isValid())
        assertFalse(CoursePeriodTable(listOf(CoursePeriodTime(480, 530), CoursePeriodTime(520, 565))).isValid())
    }
}
