package com.sakata.focusflow

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipFile

/** 下载先落临时文件；长度/基本 APK 结构通过后再暴露给安装器。系统安装器负责签名核验。 */
internal object UpdateApkDownload {
    private const val MAX_APK_BYTES = 128L * 1024L * 1024L

    fun download(
        cacheDir: File,
        versionName: String,
        url: String,
        openConnection: (String) -> HttpURLConnection = { URL(it).openConnection() as HttpURLConnection }
    ): File {
        require(UpdateChecker.isKnownTag("v$versionName")) { "invalid update version" }
        val dir = File(cacheDir, "updates")
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("cannot create update cache")
        val target = File(dir, "FocusFlow-$versionName.apk")
        val temporary = File.createTempFile("download-", ".part", dir)
        var connection: HttpURLConnection? = null
        try {
            val conn = openConnection(url).also { connection = it }
            conn.connectTimeout = 20_000
            conn.readTimeout = 120_000
            if (conn.responseCode !in 200..299) throw IOException("APK download failed: HTTP ${conn.responseCode}")
            val expected = conn.contentLengthLong
            if (expected > MAX_APK_BYTES) throw IOException("APK exceeds download limit")
            var total = 0L
            conn.inputStream.use { input ->
                temporary.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > MAX_APK_BYTES) throw IOException("APK exceeds download limit")
                        output.write(buffer, 0, count)
                    }
                }
            }
            if (expected >= 0 && total != expected) throw IOException("incomplete APK download")
            ZipFile(temporary).use { zip ->
                if (zip.getEntry("AndroidManifest.xml") == null) throw IOException("download is not an APK")
            }
            if (!temporary.renameTo(target)) throw IOException("cannot publish downloaded APK")
            return target
        } finally {
            connection?.disconnect()
            temporary.delete()
        }
    }
}
