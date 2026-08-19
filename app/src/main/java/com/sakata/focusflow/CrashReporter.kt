package com.sakata.focusflow

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 本地崩溃上报：未捕获异常写入 filesDir/crash.log（不引第三方、不上传），设置页可查看/复制/清空。 */
object CrashReporter {
    private fun crashFile(context: Context): File = File(context.filesDir, "crash.log")

    fun init(context: Context) {
        val default = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date())
                crashFile(context).appendText("\n==== $stamp ====\n${throwable.stackTraceToString()}\n")
            }
            default?.uncaughtException(thread, throwable)
        }
    }

    fun read(context: Context): String = runCatching { crashFile(context).readText() }.getOrDefault("")

    fun clear(context: Context) {
        runCatching { crashFile(context).delete() }
    }
}
