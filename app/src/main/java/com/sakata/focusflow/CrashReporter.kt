package com.sakata.focusflow

import android.content.Context
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.PrintWriter
import java.io.RandomAccessFile
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 本地崩溃上报：未捕获异常写入 filesDir/crash.log（不引第三方、不上传），设置页可查看/复制/清空。 */
object CrashReporter {
    /** crash.log 硬上限：超过后先保留后半段再追加新条目，避免单文件无限增长。 */
    internal const val MAX_LOG_BYTES = 512L * 1024L

    /** init 只生效一次：Activity 重建重复调用时不再包一层 handler，防止同一崩溃被按层数重复追加。 */
    private var initialized = false

    private fun crashFile(context: Context): File = File(context.filesDir, "crash.log")

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        val default = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { appendCrash(crashFile(context), throwable) }
            default?.uncaughtException(thread, throwable)
        }
    }

    @Synchronized
    fun read(context: Context): String = runCatching {
        val file = crashFile(context)
        if (file.length() > MAX_LOG_BYTES) trim(file, MAX_LOG_BYTES)
        file.readText(Charsets.UTF_8)
    }.getOrDefault("")

    @Synchronized
    fun clear(context: Context) {
        runCatching { crashFile(context).delete() }
    }

    /** 新条目与旧文件都按 UTF-8 字节限额处理；并发崩溃不能绕过大小检查。 */
    @Synchronized
    internal fun appendCrash(file: File, throwable: Throwable, maxBytes: Long = MAX_LOG_BYTES) {
        val limit = maxBytes.coerceIn(0L, MAX_LOG_BYTES).toInt()
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date())
        val marker = "\n[堆栈过长，已截断]\n".toByteArray(Charsets.UTF_8)
        val reserved = if (limit >= marker.size) marker.size else 0
        val writer = LimitedUtf8Writer(limit - reserved)
        writer.write("\n==== $stamp ====\n")
        throwable.printStackTrace(PrintWriter(writer))
        val body = writer.bytes()
        val entry = if (writer.truncated && reserved > 0) body + marker else body
        if (file.length() + entry.size > limit) {
            trim(file, (limit - entry.size).toLong())
        }
        file.appendBytes(entry)
    }

    /** 只读限额内的尾部；从完整 UTF-8 字符或条目边界开始，避免先读入整个超限文件。 */
    @Synchronized
    internal fun trim(file: File, maxBytes: Long) {
        if (!file.exists()) return
        val tail = RandomAccessFile(file, "r").use { input ->
            val count = minOf(input.length(), maxBytes.coerceIn(0L, MAX_LOG_BYTES)).toInt()
            ByteArray(count).also {
                input.seek(input.length() - count)
                input.readFully(it)
            }
        }
        var start = 0
        while (start < tail.size && (tail[start].toInt() and 0xC0) == 0x80) start++
        val text = String(tail, start, tail.size - start, Charsets.UTF_8)
        val firstEntry = text.indexOf("\n====")
        val writer = LimitedUtf8Writer(maxBytes.coerceIn(0L, MAX_LOG_BYTES).toInt())
        writer.write(if (firstEntry > 0) text.substring(firstEntry) else text)
        file.writeBytes(writer.bytes())
    }

    /** printStackTrace 直接写入有上限的缓冲，避免先分配完整的超长堆栈字符串。 */
    private class LimitedUtf8Writer(private val limit: Int) : Writer() {
        private val output = ByteArrayOutputStream()
        var truncated = false
            private set

        override fun write(chars: CharArray, offset: Int, length: Int) {
            val remaining = (limit - output.size()).coerceAtLeast(0)
            var count = minOf(length, remaining)
            if (count > 0 && count < length && chars[offset + count - 1].isHighSurrogate()) count--
            if (length > count) truncated = true
            append(String(chars, offset, count))
        }

        override fun write(value: String, offset: Int, length: Int) {
            val remaining = (limit - output.size()).coerceAtLeast(0)
            var end = offset + minOf(length, remaining)
            if (end > offset && end < offset + length && value[end - 1].isHighSurrogate()) end--
            if (end - offset < length) truncated = true
            append(value.substring(offset, end))
        }

        private fun append(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            var count = minOf(bytes.size, limit - output.size())
            if (count < bytes.size) {
                truncated = true
                while (count > 0 && (bytes[count].toInt() and 0xC0) == 0x80) count--
            }
            output.write(bytes, 0, count)
        }

        fun bytes(): ByteArray = output.toByteArray()
        override fun flush() = Unit
        override fun close() = Unit
    }
}
