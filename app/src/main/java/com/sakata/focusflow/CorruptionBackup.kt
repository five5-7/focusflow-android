package com.sakata.focusflow

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 损坏数据备份：解析失败的 prefs 原始串落盘到 filesDir/corrupt-backup/，后续保存覆盖也不会丢失原始数据。
 * 同一 key 同一内容只备份一份；每 key 最多保留最近 5 份，随时间滚动。可人工查看/恢复。 */
object CorruptionBackup {
    const val DIR_NAME = "corrupt-backup"

    /** 原始串是常规“无内容”写法（空白 / [] / {}）时，解码为空属正常，不算损坏。 */
    fun shouldBackup(raw: String): Boolean {
        val trimmed = raw.trim()
        return trimmed.isNotEmpty() && trimmed != "[]" && trimmed != "{}"
    }

    /** 备份原始串：同 key+同内容跳过；每 key 仅保留最新 5 份。 */
    fun backup(dir: File, key: String, raw: String) {
        dir.mkdirs()
        val same = dir.listFiles()
            ?.any { it.name.startsWith("$key-") && runCatching { it.readText(Charsets.UTF_8) == raw }.getOrDefault(false) }
            ?: false
        if (same) return
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())
        var target = File(dir, "$key-$stamp.xml")
        var suffix = 0
        while (target.exists()) target = File(dir, "$key-$stamp-${++suffix}.xml")
        target.writeText(raw, Charsets.UTF_8)
        dir.listFiles()
            ?.filter { it.name.startsWith("$key-") }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(5)
            ?.forEach { it.delete() }
    }
}
