package com.sakata.focusflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ZjuTimetableClientTest {
    @Test
    fun `CAS form keeps dynamic fields and substitutes encrypted credentials`() {
        val html = """
            <form>
              <input type="hidden" name="execution" value="e1s1">
              <input name='username' value=''>
              <input type=password name=password>
              <input type="checkbox" name="rememberMe" value="true">
            </form>
        """.trimIndent()
        val fields = ZjuTimetableClient.parseCasForm(html)
        val form = ZjuTimetableClient.buildCasForm(fields, "student", "encrypted")

        assertTrue("execution" to "e1s1" in form)
        assertTrue("username" to "student" in form)
        assertTrue("password" to "encrypted" in form)
        assertTrue("_eventId" to "submit" in form)
        assertTrue("rememberMe" to "true" in form)
    }

    @Test
    fun `timeout message identifies authentication and session phases`() {
        assertTrue(
            ZjuTimetableClient.timeoutMessage(ZjuImportStage.AUTHENTICATING)
                .contains("验证账号")
        )
        assertTrue(
            ZjuTimetableClient.timeoutMessage(ZjuImportStage.ESTABLISHING_SESSION)
                .contains("教务会话")
        )
    }

    @Test
    fun `semester parser uses selected real page option`() {
        val html = """
            <select id="xnm"><option value="2025-2026">2025-2026</option><option selected value="2026-2027">2026-2027</option></select>
            <select name='xqm'><option value='1|秋'>秋</option><option value='1|冬' selected>冬</option></select>
        """.trimIndent()

        assertEquals("2026-2027", ZjuTimetableClient.parseSemesterOption(html, "xnm")?.value)
        assertEquals("1|冬", ZjuTimetableClient.parseSemesterOption(html, "xqm")?.value)
    }

    @Test
    fun `option parsing keeps every valid option and skips blank values`() {
        val html = """
            <select id="xnm">
              <option value="">空值</option>
              <option value="&nbsp;">解码后为空</option>
              <option value="2026-2027" selected>2026-2027</option>
              <option value="2025-2026">2025-2026</option>
            </select>
        """.trimIndent()

        val options = ZjuTimetableClient.parseSemesterOptions(html, "xnm")

        assertEquals(listOf("2026-2027", "2025-2026"), options.map { it.value })
        assertEquals(listOf(true, false), options.map { it.selected })
        assertTrue(ZjuTimetableClient.parseSemesterOptions("<div></div>", "xnm").isEmpty())
    }

    @Test
    fun `two short terms stay distinguishable by value`() {
        val html = """
            <select name='xqm'>
              <option value='1|秋' selected>秋</option>
              <option value='1|短'>短</option>
              <option value='2|短'>短</option>
            </select>
        """.trimIndent()

        val options = ZjuTimetableClient.parseSemesterOptions(html, "xqm")

        assertEquals(listOf("1|秋", "1|短", "2|短"), options.map { it.value })
        assertEquals("1|短", ZjuTimetableClient.findOptionByValue(options, "1|短")?.value)
        assertEquals("2|短", ZjuTimetableClient.findOptionByValue(options, "2|短")?.value)
        assertNull(ZjuTimetableClient.findOptionByValue(options, "短"))
    }

    @Test
    fun `auto selection rejects ambiguity and duplicate values`() {
        fun options(vararg pairs: Pair<String, Boolean>) = pairs.map { (value, selected) ->
            ZjuSemesterOption(value, value, selected)
        }

        assertEquals("2026-2027",
            ZjuTimetableClient.selectAutoOption(options("2026-2027" to true, "2025-2026" to false))?.value)
        assertEquals("2026-2027",
            ZjuTimetableClient.selectAutoOption(options("2026-2027" to false))?.value)
        assertNull(ZjuTimetableClient.selectAutoOption(options("2026-2027" to false, "2025-2026" to false)))
        assertNull(ZjuTimetableClient.selectAutoOption(options("2026-2027" to true, "2025-2026" to true)))
        assertNull(ZjuTimetableClient.selectAutoOption(options("1|短" to false, "1|短" to false, "2|短" to false)))
        assertNull(ZjuTimetableClient.selectAutoOption(emptyList()))
    }

    @Test
    fun `explicit selection rejects unknown blank and duplicate values`() {
        val options = listOf(
            ZjuSemesterOption("1|短", "短", false),
            ZjuSemesterOption("2|短", "短", false)
        )
        val duplicated = listOf(
            ZjuSemesterOption("1|短", "短", false),
            ZjuSemesterOption("1|短", "短", false)
        )

        assertEquals("2|短", ZjuTimetableClient.findOptionByValue(options, " 2|短 ")?.value)
        assertNull(ZjuTimetableClient.findOptionByValue(options, "1|春"))
        assertNull(ZjuTimetableClient.findOptionByValue(options, ""))
        assertNull(ZjuTimetableClient.findOptionByValue(duplicated, "1|短"))
    }

    @Test
    fun `request form uses raw option values and derives term display`() {
        val form = ZjuTimetableClient.timetableRequestForm(
            ZjuSemesterOption("2026-2027", "2026-2027", true),
            ZjuSemesterOption("1|秋", "秋", true)
        )

        assertTrue("xnm" to "2026-2027" in form)
        assertTrue("xqm" to "1|秋" in form)
        assertTrue("xqmmc" to "秋" in form)
        assertTrue("xxqf" to "0" in form)
        assertTrue("xsfs" to "0" in form)
        assertTrue("captcha_value" to "" in form)

        val manualFallback = ZjuTimetableClient.timetableRequestForm(
            ZjuSemesterOption("2025-2026", "2025-2026", true),
            ZjuSemesterOption("3", "秋冬", true)
        )
        assertTrue("xqmmc" to "秋冬" in manualFallback)
    }

    @Test
    fun `fetch success keeps request codes beside display names`() {
        val success = ZjuTimetableFetchResult.Success(
            payload = "{}",
            schoolYear = "2026-2027",
            semester = "秋冬",
            schoolYearCode = "2026",
            termCode = "3"
        )
        assertEquals("2026-2027", success.schoolYear)
        assertEquals("秋冬", success.semester)
        assertEquals("2026", success.schoolYearCode)
        assertEquals("3", success.termCode)
    }
}
