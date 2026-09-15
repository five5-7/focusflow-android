package com.sakata.focusflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertFalse(form.any { it.first == "rememberMe" })
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
}
