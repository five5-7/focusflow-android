package com.sakata.focusflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ZjuTimetableParserTest {
    @Test
    fun `parses current kbList shape into pending courses`() {
        val payload = """{"kbList":[{"xqj":"1","djj":"3","skcd":"2","dsz":"2","kcb":"数据结构<br>1-16周<br>张老师<br>紫金港东1A-302zwf"}]}"""
        val result = ZjuTimetableParser.parse(payload) as ZjuTimetableParseResult.Success
        val course = result.batch.courses.single()
        assertEquals(CourseImportSource.ZJU_TIMETABLE, result.batch.source)
        assertEquals("数据结构", course.title)
        assertEquals(1, course.weekday)
        assertEquals(3, course.startPeriod)
        assertEquals(4, course.endPeriod)
        assertEquals("东1A-302", course.building)
        assertEquals(CampusZone.EAST_TEACHING, course.zone)
        assertTrue(course.needsConfirmation)
        assertEquals(listOf("东1教学楼"), result.batch.newPlaces)
    }

    @Test
    fun `prefers structured names and reports non weekly rows`() {
        val payload = """{"kbList":[{"xqj":"5","jcs":"第9-10节","dsz":"0","kcmc":"大学英语","cdmc":"西2-201","kcb":"旧标题<br>1-15周(单周)<br>李老师<br>旧地点"}]}"""
        val result = ZjuTimetableParser.parse(payload) as ZjuTimetableParseResult.Success
        assertEquals("大学英语", result.batch.courses.single().title)
        assertEquals(9, result.batch.courses.single().startPeriod)
        assertEquals(10, result.batch.courses.single().endPeriod)
        assertEquals(1, result.nonWeeklyRows)
    }

    @Test
    fun `returns actionable failures for captcha and changed responses`() {
        val captcha = ZjuTimetableParser.parse("""{"captcha_error":true}""")
        val changed = ZjuTimetableParser.parse("""{"unexpected":[]}""")
        assertTrue(captcha is ZjuTimetableParseResult.Failure)
        assertTrue((captcha as ZjuTimetableParseResult.Failure).message.contains("验证码"))
        assertTrue(changed is ZjuTimetableParseResult.Failure)
        assertTrue((changed as ZjuTimetableParseResult.Failure).message.contains("kbList"))
    }

    @Test
    fun `skips invalid rows without accepting impossible periods`() {
        val payload = """{"kbList":[{"xqj":"8","djj":"1","skcd":"2","kcb":"无效课程<br>1-16周<br>老师<br>东1"},{"xqj":"2","djj":"1","skcd":"2","kcb":"有效课程<br>1-16周<br>老师<br>北2"}]}"""
        val result = ZjuTimetableParser.parse(payload) as ZjuTimetableParseResult.Success
        assertEquals(1, result.invalidRows)
        assertEquals("有效课程", result.batch.courses.single().title)
    }
}
