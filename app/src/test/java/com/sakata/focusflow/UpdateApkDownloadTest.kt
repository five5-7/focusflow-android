package com.sakata.focusflow

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class UpdateApkDownloadTest {
    private lateinit var dir: File
    @Before fun setUp() { dir = Files.createTempDirectory("update-download-test").toFile() }
    @After fun tearDown() { dir.deleteRecursively() }

    @Test fun completeDownloadPublishesApkAndDisconnects() {
        val bytes = apkBytes()
        val connection = FakeConnection(bytes)
        val file = UpdateApkDownload.download(dir, "9.0.0-rc.1", "https://example.com/app.apk") { connection }
        assertTrue(file.name.endsWith(".apk"))
        assertArrayEquals(bytes, file.readBytes())
        assertTrue(connection.disconnected)
        assertTrue(file.parentFile!!.listFiles()!!.all { it.extension == "apk" })
    }

    @Test fun interruptedDownloadRemovesPartialFileAndPreservesPreviouslyDownloadedApk() {
        val dir = File(dir, "updates").apply { mkdirs() }
        val previous = File(dir, "FocusFlow-9.0.0-rc.1.apk").apply { writeText("previous valid download") }
        val connection = FakeConnection(apkBytes(), expectedExtraBytes = 5)
        expectFailure { UpdateApkDownload.download(this.dir, "9.0.0-rc.1", "https://example.com/app.apk") { connection } }
        assertTrue(previous.readText() == "previous valid download")
        assertTrue(dir.listFiles()!!.contentEquals(arrayOf(previous)))
        assertTrue(connection.disconnected)
    }

    @Test fun httpErrorCannotBecomeDownloadedApk() {
        val connection = FakeConnection("error page".toByteArray(), status = 503)
        expectFailure { UpdateApkDownload.download(dir, "9.0.0", "https://example.com/app.apk") { connection } }
        assertTrue(File(dir, "updates").listFiles()!!.isEmpty())
        assertTrue(connection.disconnected)
    }

    @Test fun nonApkPayloadIsRejectedAndTemporaryFileIsDeleted() {
        val connection = FakeConnection("HTTP 200 HTML page".toByteArray())
        expectFailure { UpdateApkDownload.download(dir, "9.0.0", "https://example.com/app.apk") { connection } }
        assertFalse(File(dir, "updates/FocusFlow-9.0.0.apk").exists())
        assertTrue(File(dir, "updates").listFiles()!!.isEmpty())
        assertTrue(connection.disconnected)
    }

    private fun expectFailure(block: () -> Unit) {
        var failed = false
        try { block() } catch (_: IOException) { failed = true }
        assertTrue("download must fail", failed)
    }

    private fun apkBytes(): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zip.write("manifest fixture".toByteArray())
            zip.closeEntry()
        }
    }.toByteArray()

    private class FakeConnection(
        private val bytes: ByteArray,
        private val status: Int = 200,
        private val expectedExtraBytes: Int = 0
    ) : HttpURLConnection(URL("https://example.com/app.apk")) {
        var disconnected = false
        override fun connect() = Unit
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun getInputStream() = ByteArrayInputStream(bytes)
        override fun getResponseCode() = status
        override fun getContentLengthLong() = bytes.size.toLong() + expectedExtraBytes
    }
}
