package com.sakata.focusflow

import android.content.Context
import java.io.File
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

    fun read(context: Context): String =
        runCatching { crashFile(context).readText(Charsets.UTF_8) }.getOrDefault("")

    fun clear(context: Context) {
        runCatching { crashFile(context).delete() }
    }

    /** 追加一条崩溃记录；超限时先压缩旧内容再追加。单元测试可传入小上限。 */
    internal fun appendCrash(file: File, throwable: Throwable, maxBytes: Long = MAX_LOG_BYTES) {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date())
        val entry = "\n==== $stamp ====\n${throwable.stackTraceToString()}\n"
        val entryBytes = entry.toByteArray(Charsets.UTF_8).size
        if (file.length() + entryBytes > maxBytes) {
            // 压缩目标为“上限 − 新条目”；单条堆栈已超上限时尽量少留旧内容（coerceAtLeast 0 → 清空旧文件）。
            trim(file, (maxBytes - entryBytes).coerceAtLeast(0L))
        }
        file.appendText(entry, Charsets.UTF_8)
    }

    /** 超限压缩：仅保留旧文件的后半段（从一个“==== ”条目边界开始），新条目继续追加。 */
    internal fun trim(file: File, maxBytes: Long) {
        val text = file.readText(Charsets.UTF_8)
        val tail = text.takeLast((maxBytes / 2).toInt())
        val firstEntry = tail.indexOf("\n====")
        file.writeText(if (firstEntry > 0) tail.substring(firstEntry) else tail, Charsets.UTF_8)
    }
}
