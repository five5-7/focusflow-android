package com.sakata.focusflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 脱敏形状摘要的**隐私契约**测试：它必须在给出字段证据的同时，绝不泄露取值与个人信息。
 */
class ZjuResponseShapeTest {

    private val payload = """
        {"xh":"SECRET-ID","xm":"SECRET-NAME","xy":"SECRET-COLLEGE",
         "xnm":"2026-2027","xqm":"1|秋",
         "kbList":[
           {"xqj":"1","djj":"1","skcd":"2","jcs":"1,2","dsz":"2","xkkh":"KEY-1",
            "kcmc":"SECRET-COURSE-A","cdmc":"SECRET-ROOM-A"},
           {"xqj":"3","djj":"3","skcd":"2","jcs":"3,4","dsz":"2","xkkh":"KEY-1",
            "kcmc":"SECRET-COURSE-A","cdmc":"SECRET-ROOM-B"}
         ]}
    """.trimIndent()

    @Test
    fun `summarizes field names with types counts and distinct values`() {
        val summary = ZjuResponseShape.summarize(payload)

        assertTrue(summary.contains("rows=2"))
        assertTrue("row count of parseable objects must be reported", summary.contains("objects=2"))
        assertTrue(summary.contains("xqj=str/2/2/2"))
        assertTrue(summary.contains("xkkh=str/2/2/1"))
        assertTrue("top level keys must be listed", summary.contains("keys=[") && summary.contains("xnm"))
    }

    @Test
    fun `never leaks any raw value or course text`() {
        val summary = ZjuResponseShape.summarize(payload)

        for (secret in listOf("SECRET-ID", "SECRET-NAME", "SECRET-COLLEGE", "SECRET-COURSE-A", "SECRET-ROOM-A", "SECRET-ROOM-B")) {
            assertFalse("summary must not contain [$secret]: $summary", summary.contains(secret))
        }
        assertFalse("course codes must not appear verbatim", summary.contains("KEY-1"))
        assertFalse(summary.contains("2026-2027"))
    }

    @Test
    fun `personal field names are redacted at top level and inside rows`() {
        // 行里放**真机日志实证存在**的行键（jszgh 教师职工号 / xzb 行政班 / zy 专业）：
        // 只测猜测键名等于对真实载荷失效。
        val withRowPii = """
            {"xh":"SECRET-ID","xm":"SECRET-NAME",
             "kbList":[
               {"xqj":"1","djj":"1","skcd":"2","xkkh":"KEY-1","jszgh":"SECRET-STAFF","xzb":"SECRET-CLASS","zy":"SECRET-MAJOR","kcmc":"SECRET-COURSE"}
             ]}
        """.trimIndent()

        val summary = ZjuResponseShape.summarize(withRowPii)

        assertTrue("redaction must be visible", summary.contains("[redacted]="))
        for (name in listOf("xh", "xm", "jszgh", "xzb", "zy")) {
            assertFalse("personal field name must not appear: $name", summary.contains("$name="))
        }
        for (secret in listOf("SECRET-ID", "SECRET-NAME", "SECRET-STAFF", "SECRET-CLASS", "SECRET-MAJOR", "SECRET-COURSE")) {
            assertFalse("personal value must never appear: $secret", summary.contains(secret))
        }
    }

    @Test
    fun `numeric identity fields still get a fingerprint`() {
        // 若教务把节次/星期返回成 JSON number，值集为空就会没有指纹、跨次稳定性无从比较。
        val payload = """{"kbList":[{"xqj":1,"djj":6,"skcd":2,"xkkh":"KEY-1"}]}"""

        val summary = ZjuResponseShape.summarize(payload)

        assertTrue("numeric field must be typed as num: $summary", summary.contains("djj=num/1/1/1#"))
        assertTrue("numeric field must be counted as non-blank", summary.contains("xqj=num/1/1/1#"))
    }

    @Test
    fun `identity fields survive the row field cap`() {
        val extras = (1..45).joinToString(",") { "\"pad$it\":\"v$it\"" }
        val payload = """{"kbList":[{"xkkh":"KEY-1","xqj":"1",$extras}]}"""

        val summary = ZjuResponseShape.summarize(payload)

        assertTrue("identity field must not be dropped by the cap: $summary", summary.contains("xkkh=str/1/1/1#"))
        assertTrue("omitted count must be reported", summary.contains("omitted="))
    }

    @Test
    fun `a truncated value set is marked so it is not read as instability`() {
        val rows = (1..70).joinToString(",") { """{"xqj":"$it","xkkh":"KEY-$it"}""" }
        val summary = ZjuResponseShape.summarize("""{"kbList":[$rows]}""")

        assertTrue("truncation must be visible: $summary", summary.contains("/64+"))
    }

    @Test
    fun `identity fields get a stable fingerprint while text fields get none`() {
        val first = ZjuResponseShape.summarize(payload)
        val second = ZjuResponseShape.summarize(payload)

        assertEquals("the same input must produce the same summary", first, second)
        assertTrue("identity field must carry a fingerprint", first.contains("xkkh=str/2/2/1#"))
        assertFalse("course title must not be fingerprinted", first.contains("kcmc=str/2/2/1#"))
        assertFalse("location must not be fingerprinted", first.contains("cdmc=str/2/2/2#"))
    }

    @Test
    fun `a different selection key changes the fingerprint`() {
        val other = payload.replace("KEY-1", "KEY-9")
        val base = ZjuResponseShape.summarize(payload)
        val changed = ZjuResponseShape.summarize(other)

        val basePrint = base.substringAfter("xkkh=str/2/2/1#").take(12)
        val otherPrint = changed.substringAfter("xkkh=str/2/2/1#").take(12)
        assertNotEquals(basePrint, otherPrint)
    }

    @Test
    fun `unparseable and empty payloads yield markers instead of throwing`() {
        assertTrue(ZjuResponseShape.summarize("   ").startsWith("shape=empty"))
        assertTrue(ZjuResponseShape.summarize("<html>login</html>").startsWith("shape=not-json"))
        assertTrue(ZjuResponseShape.summarize("""{"xnm":"2026-2027"}""").contains("json-no-kbList"))
        assertTrue(ZjuResponseShape.summarize("""{"kbList":[]}""").contains("rows=0"))
    }
}
