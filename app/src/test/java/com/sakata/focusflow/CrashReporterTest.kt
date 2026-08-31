package com.sakata.focusflow

import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

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
}
