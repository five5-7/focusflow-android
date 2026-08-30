package com.sakata.focusflow

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class CorruptionBackupTest {
    private lateinit var dir: File

    @Before fun setUp() {
        dir = Files.createTempDirectory("corrupt-backup-test").toFile()
    }

    @After fun tearDown() {
        dir.deleteRecursively()
    }

    @Test fun shouldBackup_rejectsVacuousRaws() {
        listOf("", "   ", "[]", "{}").forEach { assertFalse(CorruptionBackup.shouldBackup(it)) }
        listOf("not-json{{{", "[{", "null", "[{\"id\":1}").forEach { assertTrue(CorruptionBackup.shouldBackup(it)) }
    }

    @Test fun backup_writesRawOnceAndSkipsDuplicate() {
        CorruptionBackup.backup(dir, "items", "BROKEN-raw")
        val files = dir.listFiles()!!.filter { it.name.startsWith("items-") }
        assertEquals(1, files.size)
        assertEquals("BROKEN-raw", files.single().readText(Charsets.UTF_8))
        CorruptionBackup.backup(dir, "items", "BROKEN-raw")
        assertEquals(1, dir.listFiles()!!.count { it.name.startsWith("items-") })
    }

    @Test fun backup_keepsLatestFivePerKey() {
        repeat(7) { i -> CorruptionBackup.backup(dir, "items", "BROKEN-$i") }
        val files = dir.listFiles()!!.filter { it.name.startsWith("items-") }
        assertEquals(5, files.size)
        val contents = files.map { it.readText(Charsets.UTF_8) }
        assertFalse(contents.contains("BROKEN-0"))
        assertFalse(contents.contains("BROKEN-1"))
        assertTrue(contents.contains("BROKEN-6"))
    }

    @Test fun backup_separatesKeys() {
        CorruptionBackup.backup(dir, "items", "BROKEN-items")
        CorruptionBackup.backup(dir, "feedback", "BROKEN-feedback")
        assertEquals(1, dir.listFiles()!!.count { it.name.startsWith("items-") })
        assertEquals(1, dir.listFiles()!!.count { it.name.startsWith("feedback-") })
    }
}
