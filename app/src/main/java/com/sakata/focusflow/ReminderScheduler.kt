package com.sakata.focusflow

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

object ReminderScheduler {
    fun scheduleActivityEnd(context: Context, session: ActivitySession) {
        scheduleActivityReminders(context, session)
    }

    fun scheduleActivityReminders(
        context: Context,
        session: ActivitySession,
        settings: ActivityReminderSettings = PrototypeStore(context).loadActivityReminderSettings()
    ) {
        cancelActivityReminders(context, session.id)
        if (!settings.notificationsEnabled || !session.isOpen()) return
        val now = System.currentTimeMillis()
        val previewAt = session.endsAt - settings.previewMinutes * 60_000L
        if (settings.previewMinutes > 0 && previewAt > now) {
            scheduleActivityAlarm(context, session, ReminderReceiver.ACTION_ACTIVITY_PREVIEW, previewAt, 1)
        }
        if (session.endsAt > now) {
            scheduleActivityAlarm(context, session, ReminderReceiver.ACTION_ACTIVITY_END, session.endsAt, 2)
        }
    }

    fun restoreActivityReminders(context: Context) {
        val store = PrototypeStore(context)
        store.loadLatestActiveSession()?.let { scheduleActivityReminders(context, it, store.loadActivityReminderSettings()) }
    }

    fun cancelActivityReminders(context: Context, sessionId: Long) {
        val manager = context.getSystemService(AlarmManager::class.java)
        listOf(ReminderReceiver.ACTION_ACTIVITY_PREVIEW to 1, ReminderReceiver.ACTION_ACTIVITY_END to 2).forEach { (action, offset) ->
            val intent = Intent(context, ReminderReceiver::class.java).apply { this.action = action }
            val pending = PendingIntent.getBroadcast(context, activityRequestCode(sessionId, offset), intent, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
            pending?.let(manager::cancel)
        }
    }

    private fun scheduleActivityAlarm(context: Context, session: ActivitySession, actionName: String, triggerAt: Long, offset: Int) {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = actionName
            putExtra(ReminderReceiver.EXTRA_ACTIVITY_NAME, session.name)
            putExtra(ReminderReceiver.EXTRA_SESSION_ID, session.id)
            putExtra(ReminderReceiver.EXTRA_NEXT_STEP, session.nextStep)
        }
        val pending = PendingIntent.getBroadcast(
            context,
            activityRequestCode(session.id, offset),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        context.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
    }

    private fun activityRequestCode(sessionId: Long, offset: Int): Int {
        return (sessionId % 500_000_000L).toInt() * 2 + offset
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
