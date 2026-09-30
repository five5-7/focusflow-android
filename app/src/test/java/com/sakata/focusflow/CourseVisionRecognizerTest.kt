package com.sakata.focusflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CourseVisionRecognizerTest {
    @Test
    fun `prompt requires coordinates from the grid and forbids defaulting to one`() {
        val prompt = CourseVisionRecognizer.buildPrompt(emptyList())

        assertTrue(prompt.contains("顶部的星期表头"))
        assertTrue(prompt.contains("最左侧的节次标号"))
        assertTrue(prompt.contains("绝不能默认填 1"))
        assertTrue(prompt.contains("返回 []"))
    }

    @Test
    fun `valid grid coordinates and location are retained`() {
        val report = CourseVisionRecognizer.parseCourses(
            """[{"name":"高等数学","day":3,"startPeriod":5,"endPeriod":6,"location":"紫金港东1A-302"}]""",
            emptyList()
        )

        assertNull(report.rejectionReason)
        assertEquals(1, report.courses.size)
        assertEquals(3, report.courses.single().weekday)
        assertEquals(5, report.courses.single().startPeriod)
        assertEquals(6, report.courses.single().endPeriod)
        assertEquals("东1教学楼", report.courses.single().building)
    }

    @Test
    fun `missing coordinates are skipped instead of clamped to first period`() {
        val report = CourseVisionRecognizer.parseCourses(
            """[{"name":"没有星期","startPeriod":1,"endPeriod":2,"location":""},{"name":"大学英语","day":2,"startPeriod":3,"endPeriod":4,"location":""}]""",
            emptyList()
        )

        assertNull(report.rejectionReason)
        assertEquals(listOf("大学英语"), report.courses.map { it.title })
        assertTrue(report.warnings.single().contains("1 条课程缺少可靠的星期或节次"))
    }

    @Test
    fun `mostly unknown coordinate batch is rejected`() {
        val report = CourseVisionRecognizer.parseCourses(
            """[{"name":"课程一","day":1,"startPeriod":1,"endPeriod":2,"location":""},{"name":"课程二","startPeriod":3,"endPeriod":4,"location":""},{"name":"课程三","day":2,"location":""},{"name":"课程四","day":4,"startPeriod":7,"endPeriod":8,"location":""}]""",
            emptyList()
        )

        assertTrue(report.courses.isEmpty())
        assertNotNull(report.rejectionReason)
        assertTrue(report.rejectionReason!!.contains("已停止导入"))
    }

    @Test
    fun `three different courses collapsed into one cell reject the whole batch`() {
        val report = CourseVisionRecognizer.parseCourses(
            """[{"name":"课程一","day":1,"startPeriod":1,"endPeriod":1,"location":"A"},{"name":"课程二","day":1,"startPeriod":1,"endPeriod":1,"location":"B"},{"name":"课程三","day":1,"startPeriod":1,"endPeriod":1,"location":"C"}]""",
            emptyList()
        )

        assertTrue(report.courses.isEmpty())
        assertNotNull(report.rejectionReason)
        assertTrue(report.rejectionReason!!.contains("同一个星期和节次"))
    }

    @Test
    fun `exact slot conflicts require editing even after one course is confirmed`() {
        val first = course("课程一", needsConfirmation = true, id = 1)
        val second = course("课程二", needsConfirmation = true, id = 2)
        val allPending = listOf(first, second)
        assertEquals(setOf(1L, 2L), CourseConfirmationSafety.blockedDirectConfirmationIds(allPending))

        val afterEditingFirst = listOf(first.copy(needsConfirmation = false), second)
        assertFalse(CourseConfirmationSafety.isDirectConfirmationBlocked(first, afterEditingFirst))
        assertTrue(CourseConfirmationSafety.isDirectConfirmationBlocked(second, afterEditingFirst))
    }

    private fun course(title: String, needsConfirmation: Boolean, id: Long) = Course(
        title = title,
        weekday = 1,
        startPeriod = 1,
        endPeriod = 2,
        building = "地点待确认",
        zone = CampusZone.WEST_TEACHING,
        needsConfirmation = needsConfirmation,
        id = id
    )
}
