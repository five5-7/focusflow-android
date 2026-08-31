package com.sakata.focusflow

import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files

class StorageProtectionTest {
    @Test fun failedBackup_blocksApplyCommitAndClearAcrossStoreInstances_thenRetries() {
        val root = Files.createTempDirectory("storage-protection").toFile()
        val blocked = File(root, "backup").apply { writeText("directory blocked") }
        var writes = 0
        val editor = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, _ ->
            when (method.name) {
                "apply" -> { writes++; null }
                "commit" -> { writes++; true }
                else -> proxy
            }
        } as SharedPreferences.Editor
        val prefs = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, _ ->
            if (method.name == "edit") editor else null
        } as SharedPreferences
        try {
            StorageProtection.backup(blocked, "items", "broken original")
            assertTrue(StorageProtection.readOnly)
            ProtectedPreferences(prefs).edit().putString("items", "[]").apply()
            assertFalse(ProtectedPreferences(prefs).edit().remove("items").commit())
            ProtectedPreferences(prefs).edit().clear().apply()
            assertEquals(0, writes)
            assertTrue(blocked.delete())
            StorageProtection.retry()
            assertFalse(StorageProtection.readOnly)
            assertEquals("broken original", blocked.listFiles()!!.single().readText())
            assertTrue(ProtectedPreferences(prefs).edit().putString("items", "[]").commit())
            assertEquals(1, writes)
        } finally {
            if (blocked.isFile) blocked.delete()
            StorageProtection.retry()
            root.deleteRecursively()
        }
    }
}
