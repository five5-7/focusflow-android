package com.sakata.focusflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun `candidate rows keep original indices after invalid rows are filtered`() {
        val payload = """{"kbList":[{"xqj":"8","djj":"1","skcd":"2","kcb":"无效课程<br>1-16周<br>老师<br>东1"},{"xqj":"2","djj":"1","skcd":"2","kcb":"有效课程<br>1-16周<br>老师<br>北2"},"junk"]}"""
        val result = ZjuTimetableParser.parse(payload) as ZjuTimetableParseResult.Success
        assertEquals(2, result.invalidRows)
        assertEquals(listOf(0, 1), result.candidates.map { it.sourceRowIndex })
        assertEquals(8, result.candidates.first().weekday ?: 0)
        assertEquals("有效课程", result.batch.courses.single().title)
    }

    @Test
    fun `missing fields yield blank candidate metadata and never drop the row index`() {
        val payload = """{"kbList":[{"kcb":"只有标题<br>1-16周"}]}"""
        val result = ZjuTimetableParser.parse(payload) as ZjuTimetableParseResult.Success
        assertEquals(1, result.invalidRows)
        val candidate = result.candidates.single()
        assertEquals(0, candidate.sourceRowIndex)
        assertEquals("", candidate.externalSelectionKeyCandidate)
        assertEquals(null, candidate.weekday)
        assertEquals(null, candidate.startPeriod)
        assertEquals(null, candidate.endPeriod)
        assertFalse(candidate.hasRequestCodes)
    }

    @Test
    fun `request codes flow into every candidate and stay blank when absent`() {
        val payload = """{"kbList":[{"xqj":"1","djj":"1","skcd":"2","kcb":"甲<br>1-16周<br>师<br>东1zwf","xkkh":"KEY-1"},{"xqj":"2","djj":"3","skcd":"2","kcb":"乙<br>1-16周<br>师<br>东2zwf","xkkh":"KEY-2"}]}"""
        val withCodes = ZjuTimetableParser.parse(payload, " 2026 ", " 3 ") as ZjuTimetableParseResult.Success
        assertEquals(listOf("2026", "2026"), withCodes.candidates.map { it.schoolYearCode })
        assertEquals(listOf("3", "3"), withCodes.candidates.map { it.termCode })
        assertEquals(listOf("2026", "2026"), withCodes.batch.courses.map { it.externalSchoolYearCode })
        assertEquals(listOf("3", "3"), withCodes.batch.courses.map { it.externalTermCode })
        assertEquals(listOf("KEY-1", "KEY-2"), withCodes.batch.courses.map { it.externalSelectionKeyCandidate })
        assertTrue(withCodes.candidates.all { it.hasRequestCodes })

        val withoutCodes = ZjuTimetableParser.parse(payload) as ZjuTimetableParseResult.Success
        assertTrue(withoutCodes.candidates.none { it.hasRequestCodes })
        assertEquals("", withoutCodes.candidates.first().schoolYearCode)
        assertEquals("", withoutCodes.candidates.first().termCode)
    }

    @Test
    fun `duplicate selection keys stay per row and never group courses`() {
        val payload = """{"kbList":[{"xqj":"1","djj":"1","skcd":"2","kcb":"甲<br>1-16周<br>师<br>东1zwf","xkkh":"KEY-1"},{"xqj":"3","djj":"3","skcd":"2","kcb":"甲<br>1-16周<br>师<br>东2zwf","xkkh":"KEY-1"},{"xqj":"5","djj":"5","skcd":"2","kcb":"乙<br>1-16周<br>师<br>西1zwf","xkkh":"KEY-2"}]}"""
        val result = ZjuTimetableParser.parse(payload) as ZjuTimetableParseResult.Success
        assertEquals(
            listOf("KEY-1", "KEY-1", "KEY-2"),
            result.candidates.map { it.externalSelectionKeyCandidate }
        )
        assertEquals(3, result.batch.courses.size)
    }

    @Test
    fun `same title with different selection keys stays independent`() {
        val payload = """{"kbList":[{"xqj":"1","djj":"1","skcd":"2","kcb":"大学物理<br>1-16周<br>师<br>东1zwf","xkkh":"CLASS-A"},{"xqj":"1","djj":"3","skcd":"2","kcb":"大学物理<br>1-16周<br>师<br>东1zwf","xkkh":"CLASS-B"}]}"""
        val result = ZjuTimetableParser.parse(payload) as ZjuTimetableParseResult.Success
        assertEquals(
            listOf("CLASS-A", "CLASS-B"),
            result.candidates.map { it.externalSelectionKeyCandidate }
        )
        assertEquals(listOf("大学物理", "大学物理"), result.batch.courses.map { it.title })
    }

    @Test
    fun `candidate rows carry the course title for identity checks`() {
        val payload = """{"kbList":[{"xqj":"1","djj":"1","skcd":"2","kcmc":"高等数学","xkkh":"KEY-1"},{"xqj":"3","djj":"3","skcd":"2","kcmc":"线性代数","xkkh":"KEY-1"}]}"""
        val result = ZjuTimetableParser.parse(payload, "2026-2027", "1|秋") as ZjuTimetableParseResult.Success

        assertEquals(listOf("高等数学", "线性代数"), result.candidates.map { it.title })
    }

    @Test
    fun `one selection key with two different titles ends up needing review`() {
        // 端到端：解析器填的 title 必须真的让候选预览把"同号异标题"标成待核对，
        // 而不是各测一半（解析器不测 title、预览只吃手工构造的行）。
        val payload = """{"kbList":[{"xqj":"1","djj":"1","skcd":"2","kcmc":"高等数学","xkkh":"KEY-1"},{"xqj":"3","djj":"3","skcd":"2","kcmc":"线性代数","xkkh":"KEY-1"}]}"""
        val result = ZjuTimetableParser.parse(payload, "2026-2027", "1|秋") as ZjuTimetableParseResult.Success

        val group = CourseIdentityPreview.group(result.candidates).single()
        assertEquals(CourseIdentityPreview.PreviewStatus.NEEDS_REVIEW, group.status)
        assertEquals(
            listOf(CourseIdentityPreview.PreviewReviewReason.CONFLICTING_TITLES),
            group.reviewReasons
        )
    }

    @Test
    fun `one selection key with the same title twice is a multi meeting candidate`() {
        val payload = """{"kbList":[{"xqj":"1","djj":"1","skcd":"2","kcmc":"高等数学","xkkh":"KEY-1"},{"xqj":"3","djj":"3","skcd":"2","kcmc":"高等数学","xkkh":"KEY-1"}]}"""
        val result = ZjuTimetableParser.parse(payload, "2026-2027", "1|秋") as ZjuTimetableParseResult.Success

        val group = CourseIdentityPreview.group(result.candidates).single()
        assertEquals(CourseIdentityPreview.PreviewStatus.MULTI_MEETING_CANDIDATE, group.status)
        assertTrue(group.reviewReasons.isEmpty())
        assertEquals(listOf(0, 1), group.rows.map { it.sourceRowIndex })
    }

    @Test
    fun `top level personal fields never surface in parsed output`() {
        val payload = """{"xh":"SECRET-ID","xm":"SECRET-NAME","xy":"SECRET-COLLEGE","kbList":[{"xqj":"1","djj":"1","skcd":"2","kcb":"甲<br>1-16周<br>师<br>东1zwf","xkkh":"KEY-1"}]}"""
        val result = ZjuTimetableParser.parse(payload, "2026", "3") as ZjuTimetableParseResult.Success
        assertFalse(result.toString().contains("SECRET"))
    }
}
