package com.sakata.focusflow

import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.charset.CodingErrorAction
import java.nio.ByteBuffer

class CrashReporterTest {
    private lateinit var dir: File

    @Before fun setUp() {
        dir = Files.createTempDirectory("crash-reporter-test").toFile()
    }

    @After fun tearDown() {
        dir.deleteRecursively()
    }

    @Test fun appendCrash_writesTimestampedEntry() {
        val file = File(dir, "crash.log")
        CrashReporter.appendCrash(file, IllegalStateException("boom"))
        val text = file.readText(Charsets.UTF_8)
        assertTrue(text.contains("==== "))
        assertTrue(text.contains("boom"))
        assertTrue(text.contains("IllegalStateException"))
    }

    @Test fun appendCrash_appendsMultipleEntries() {
        val file = File(dir, "crash.log")
        CrashReporter.appendCrash(file, IllegalStateException("first"))
        CrashReporter.appendCrash(file, IllegalArgumentException("second"))
        val text = file.readText(Charsets.UTF_8)
        assertTrue(text.contains("first"))
        assertTrue(text.contains("second"))
        // 后写条目在前一条目之后（保持日志顺序）
        assertTrue(text.indexOf("second") > text.indexOf("first"))
    }

    @Test fun appendCrash_trimsWhenOverLimit() {
        val file = File(dir, "crash.log")
        file.writeText("PRE-FILL-XYZZY".repeat(300), Charsets.UTF_8)
        CrashReporter.appendCrash(file, IllegalStateException("after-trim"), maxBytes = 1000)
        val text = file.readText(Charsets.UTF_8)
        assertTrue(text.contains("after-trim"))
        // 超限触发压缩：旧内容前段已被裁掉（最长保留尾巴 50 字节，5 段前缀共 70 字节必不在）
        assertTrue(!text.contains("PRE-FILL-XYZZY".repeat(5)))
        assertTrue(file.length() <= 1000)
    }

    @Test fun trim_keepsTailFromEntryBoundary() {
        val file = File(dir, "crash.log")
        file.writeText("AAAA\n==== 2026-01-01 ====\nBBBB\n", Charsets.UTF_8)
        CrashReporter.trim(file, maxBytes = 26)
        val text = file.readText(Charsets.UTF_8)
        assertTrue(text.contains("BBBB"))
        assertTrue(text.contains("===="))
        assertTrue(!text.contains("AAAA"))
    }

    @Test fun appendCrash_singleHugeUnicodeEntryRespectsHardByteLimit() {
        val file = File(dir, "crash.log")
        CrashReporter.appendCrash(file, IllegalStateException("异常😀".repeat(20_000)), maxBytes = 1024)
        assertTrue(file.length() <= 1024)
        val text = decodeStrict(file)
        assertTrue(text.contains("IllegalStateException"))
        assertTrue(text.contains("已截断"))
    }

    @Test fun trim_oversizedUnicodeHistoryDoesNotSplitUtf8Characters() {
        val file = File(dir, "crash.log")
        file.writeText("旧记录😀".repeat(200_000), Charsets.UTF_8)
        CrashReporter.trim(file, 997)
        assertTrue(file.length() <= 997)
        assertTrue(decodeStrict(file).contains("旧记录"))
    }

    @Test fun concurrentCrashEntriesStayWithinLimitAndKeepLatestEntry() {
        val file = File(dir, "crash.log")
        val threads = (1..8).map { index ->
            Thread { repeat(10) { CrashReporter.appendCrash(file, IllegalStateException("worker-$index-${"字".repeat(80)}"), 2048) } }
        }
        threads.forEach(Thread::start)
        threads.forEach(Thread::join)
        CrashReporter.appendCrash(file, IllegalStateException("last-entry"), 2048)
        assertTrue(file.length() <= 2048)
        assertTrue(decodeStrict(file).contains("last-entry"))
    }

    private fun decodeStrict(file: File): String = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(file.readBytes())).toString()
}
