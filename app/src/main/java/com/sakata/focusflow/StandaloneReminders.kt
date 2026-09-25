package com.sakata.focusflow

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

/** A user-created reminder is independent of a task, schedule item and capture. */
internal data class StandaloneReminder(
    val id: Long,
    val title: String,
    val triggerAt: Long,
    val deliveredAt: Long? = null,
    val completedAt: Long? = null
)

internal object StandaloneReminders {
    private const val FILE = "standalone_reminders"
    private const val KEY = "entries"

    @Synchronized fun all(context: Context): List<StandaloneReminder> = runCatching {
        val array = JSONArray(context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, "[]"))
        (0 until array.length()).map { index ->
            val entry = array.getJSONObject(index)
            StandaloneReminder(entry.getLong("id"), entry.getString("title"), entry.getLong("triggerAt"),
                entry.optLong("deliveredAt").takeIf { it > 0 }, entry.optLong("completedAt").takeIf { it > 0 })
        }.filter { it.id > 0 && it.triggerAt > 0 }
    }.getOrDefault(emptyList())

    @Synchronized private fun save(context: Context, entries: List<StandaloneReminder>): Boolean {
        val encoded = JSONArray().apply { entries.forEach { entry -> put(JSONObject().apply {
            put("id", entry.id); put("title", entry.title); put("triggerAt", entry.triggerAt)
            put("deliveredAt", entry.deliveredAt ?: 0L); put("completedAt", entry.completedAt ?: 0L)
        }) } }.toString()
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY, encoded).commit()
    }

    @Synchronized fun create(context: Context, title: String, triggerAt: Long, now: Long = System.currentTimeMillis()): Boolean {
        val name = title.trim()
        if (name.isBlank() || name.length > 200 || triggerAt <= now) return false
        val previous = all(context)
        var id = newItemId()
        while (previous.any { it.id == id }) id = newItemId()
        return save(context, previous + StandaloneReminder(id, name, triggerAt))
    }

    /** Replayed or stale alarm intents do not show another notification. */
    @Synchronized fun markDelivered(context: Context, id: Long, expectedAt: Long,
                                    now: Long = System.currentTimeMillis()): StandaloneReminder? {
        val current = all(context)
        val entry = current.firstOrNull { it.id == id && it.triggerAt == expectedAt &&
            it.completedAt == null && it.deliveredAt == null && expectedAt <= now } ?: return null
        return entry.copy(deliveredAt = now).takeIf { updated ->
            save(context, current.map { if (it.id == id) updated else it })
        }
    }

    @Synchronized fun complete(context: Context, id: Long, expectedAt: Long,
                               now: Long = System.currentTimeMillis()): Boolean {
        val current = all(context)
        val entry = current.firstOrNull { it.id == id && it.triggerAt == expectedAt && it.completedAt == null }
            ?: return false
        return save(context, current.map { if (it.id == id) entry.copy(completedAt = now) else it })
    }

    fun pendingIntent(context: Context, reminder: StandaloneReminder): PendingIntent = PendingIntent.getBroadcast(
        context, 0,
        Intent(context, ReminderReceiver::class.java).apply {
            action = ReminderReceiver.ACTION_STANDALONE_DUE
            data = Uri.parse("focusflow://standalone/${reminder.id}")
            putExtra(ReminderReceiver.EXTRA_STANDALONE_ID, reminder.id)
            putExtra(ReminderReceiver.EXTRA_STANDALONE_AT, reminder.triggerAt)
        }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun restore(context: Context, now: Long = System.currentTimeMillis()) {
        val manager = context.getSystemService(AlarmManager::class.java)
        all(context).forEach { reminder ->
            if (reminder.completedAt == null && reminder.deliveredAt == null && reminder.triggerAt > now) {
                val pending = pendingIntent(context, reminder)
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms())
                    manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reminder.triggerAt, pending)
                else manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reminder.triggerAt, pending)
            }
        }
    }

    fun cancel(context: Context, reminder: StandaloneReminder) {
        context.getSystemService(AlarmManager::class.java).cancel(pendingIntent(context, reminder))
    }
}
