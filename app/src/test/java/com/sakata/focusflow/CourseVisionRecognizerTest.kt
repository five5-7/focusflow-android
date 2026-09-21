package com.sakata.focusflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CourseVisionRecognizerTest {
    @Test fun `prompt requires visible grid evidence and forbids invented headers`() {
        val prompt = CourseVisionRecognizer.buildPrompt(emptyList())
        assertTrue(prompt.contains("weekdayHeaderRowVisible"))
        assertTrue(prompt.contains("periodLabelColumnVisible"))
        assertTrue(prompt.contains("被裁掉、遮挡或看不清时不得补全"))
        assertTrue(prompt.contains("columnCenterX"))
        assertTrue(prompt.contains("courses 都返回空数组"))
    }

    @Test fun `valid grid evidence coordinates and location are retained`() {
        val report = CourseVisionRecognizer.parseCourses(payload(courses = """[{"name":"高等数学","day":3,"startPeriod":2,"endPeriod":3,"location":"紫金港东1A-302","columnCenterX":0.50,"startRowCenterY":0.40,"endRowCenterY":0.55}]"""), emptyList())
        assertNull(report.rejectionReason)
        assertEquals(1, report.courses.size)
        assertEquals(3, report.courses.single().weekday)
        assertEquals(2, report.courses.single().startPeriod)
        assertEquals(3, report.courses.single().endPeriod)
        assertEquals("东1教学楼", report.courses.single().building)
    }

    @Test fun `legacy evidence free array is rejected`() {
        val report = CourseVisionRecognizer.parseCourses("""[{"name":"高等数学","day":3,"startPeriod":2,"endPeriod":3,"location":""}]""", emptyList())
        assertTrue(report.courses.isEmpty())
        assertTrue(report.rejectionReason!!.contains("网格证据"))
    }

    @Test fun `missing weekday header rejects plausible course coordinates`() {
        val report = CourseVisionRecognizer.parseCourses(payload(weekdayVisible = false, weekdayHeaders = "[]", courses = """[{"name":"高等数学","day":3,"startPeriod":2,"endPeriod":3,"location":"","columnCenterX":0.50,"startRowCenterY":0.40,"endRowCenterY":0.55}]"""), emptyList())
        assertTrue(report.courses.isEmpty())
        assertTrue(report.rejectionReason!!.contains("星期标题行"))
    }

    @Test fun `course row treated as weekday is rejected by grid snapping`() {
        val report = CourseVisionRecognizer.parseCourses(payload(courses = """[{"name":"高等数学","day":1,"startPeriod":2,"endPeriod":3,"location":"","columnCenterX":0.80,"startRowCenterY":0.40,"endRowCenterY":0.55}]"""), emptyList())
        assertTrue(report.courses.isEmpty())
        assertTrue(report.rejectionReason!!.contains("横行误当成了一天"))
    }

    @Test fun `transposed grid evidence is rejected`() {
        val headers = """[
            {"day":1,"label":"周一","centerX":0.20,"centerY":0.10},
            {"day":2,"label":"周二","centerX":0.21,"centerY":0.25},
            {"day":3,"label":"周三","centerX":0.22,"centerY":0.40},
            {"day":4,"label":"周四","centerX":0.23,"centerY":0.55},
            {"day":5,"label":"周五","centerX":0.24,"centerY":0.70}
        ]""".trimIndent()
        val report = CourseVisionRecognizer.parseCourses(payload(weekdayHeaders = headers, courses = "[]"), emptyList())
        assertTrue(report.courses.isEmpty())
        assertTrue(report.rejectionReason!!.contains("星期标题证据不足或排列异常"))
    }

    @Test fun `mismatched weekday label is rejected`() {
        val report = CourseVisionRecognizer.parseCourses(payload(weekdayHeaders = validWeekdays.replace("\"周三\"", "\"周四\""), courses = "[]"), emptyList())
        assertTrue(report.courses.isEmpty())
        assertTrue(report.rejectionReason!!.contains("星期标题证据不足"))
    }

    @Test fun `course missing geometric evidence rejects whole batch`() {
        val report = CourseVisionRecognizer.parseCourses(payload(courses = """[{"name":"课程一","day":1,"startPeriod":1,"endPeriod":2,"location":""},{"name":"课程二","day":2,"startPeriod":2,"endPeriod":3,"location":"","columnCenterX":0.35,"startRowCenterY":0.40,"endRowCenterY":0.55}]"""), emptyList())
        assertTrue(report.courses.isEmpty())
        assertTrue(report.rejectionReason!!.contains("无法对应"))
    }

    @Test fun `three different courses collapsed into one cell reject the whole batch`() {
        val courses = (1..3).joinToString(prefix = "[", postfix = "]") { index ->
            """{"name":"课程$index","day":1,"startPeriod":1,"endPeriod":1,"location":"","columnCenterX":0.20,"startRowCenterY":0.25,"endRowCenterY":0.25}"""
        }
        val report = CourseVisionRecognizer.parseCourses(payload(courses = courses), emptyList())
        assertTrue(report.courses.isEmpty())
        assertNotNull(report.rejectionReason)
        assertTrue(report.rejectionReason!!.contains("同一个星期和节次"))
    }

    @Test fun `exact slot conflicts require editing even after one course is confirmed`() {
        val first = course("课程一", true, 1)
        val second = course("课程二", true, 2)
        assertEquals(setOf(1L, 2L), CourseConfirmationSafety.blockedDirectConfirmationIds(listOf(first, second)))
        val edited = listOf(first.copy(needsConfirmation = false), second)
        assertFalse(CourseConfirmationSafety.isDirectConfirmationBlocked(first, edited))
        assertTrue(CourseConfirmationSafety.isDirectConfirmationBlocked(second, edited))
    }

    private fun payload(weekdayVisible: Boolean = true, periodVisible: Boolean = true, weekdayHeaders: String = validWeekdays, periodLabels: String = validPeriods, courses: String): String = """{
        "grid":{"weekdayHeaderRowVisible":$weekdayVisible,"periodLabelColumnVisible":$periodVisible},
        "weekdayHeaders":$weekdayHeaders,
        "periodLabels":$periodLabels,
        "courses":$courses
    }""".trimIndent()

    private fun course(title: String, needsConfirmation: Boolean, id: Long) = Course(title = title, weekday = 1, startPeriod = 1, endPeriod = 2, building = "地点待确认", zone = CampusZone.WEST_TEACHING, needsConfirmation = needsConfirmation, id = id)

    companion object {
        private val validWeekdays = """[
            {"day":1,"label":"周一","centerX":0.20,"centerY":0.08},
            {"day":2,"label":"周二","centerX":0.35,"centerY":0.08},
            {"day":3,"label":"周三","centerX":0.50,"centerY":0.08},
            {"day":4,"label":"周四","centerX":0.65,"centerY":0.08},
            {"day":5,"label":"周五","centerX":0.80,"centerY":0.08}
        ]""".trimIndent()
        private val validPeriods = """[
            {"period":1,"label":"第一节","centerX":0.06,"centerY":0.25},
            {"period":2,"label":"第二节","centerX":0.06,"centerY":0.40},
            {"period":3,"label":"第三节","centerX":0.06,"centerY":0.55},
            {"period":4,"label":"第四节","centerX":0.06,"centerY":0.70}
        ]""".trimIndent()
    }
}
