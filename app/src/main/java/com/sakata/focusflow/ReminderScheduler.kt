package com.sakata.focusflow

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

object ReminderScheduler {
    fun scheduleActivityEnd(context: Context, session: ActivitySession) {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ReminderReceiver.ACTION_ACTIVITY_END
            putExtra(ReminderReceiver.EXTRA_ACTIVITY_NAME, session.name)
            putExtra(ReminderReceiver.EXTRA_SESSION_ID, session.id)
        }
        val requestCode = (session.id % Int.MAX_VALUE).toInt()
        val pending = PendingIntent.getBroadcast(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        context.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP, session.endsAt, pending
        )
    }

    fun scheduleTaskReminder(context: Context, item: Item) {
        val scheduledAt = item.scheduledAt ?: return
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ReminderReceiver.ACTION_TASK_DUE
            putExtra(ReminderReceiver.EXTRA_TASK_ID, item.id)
            putExtra(ReminderReceiver.EXTRA_TASK_TITLE, item.title.removePrefix("重新安排："))
        }
        val requestCode = ((item.id + 10_000L) % Int.MAX_VALUE).toInt()
        val pending = PendingIntent.getBroadcast(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        context.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, scheduledAt, pending)
    }
}
