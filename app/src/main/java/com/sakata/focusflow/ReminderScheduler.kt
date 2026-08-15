package com.sakata.focusflow

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Calendar

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
        store.loadLatestActiveSession()?.let { session ->
            if (session.endsAt <= System.currentTimeMillis()) {
                if (session.status != ActivitySession.STATUS_AWAITING_CONFIRMATION) {
                    store.markSessionAwaitingConfirmation(session.id)
                    context.sendBroadcast(Intent(context, ReminderReceiver::class.java).apply {
                        action = ReminderReceiver.ACTION_ACTIVITY_END
                        putExtra(ReminderReceiver.EXTRA_ACTIVITY_NAME, session.name)
                        putExtra(ReminderReceiver.EXTRA_SESSION_ID, session.id)
                        putExtra(ReminderReceiver.EXTRA_NEXT_STEP, session.nextStep)
                    })
                }
            } else scheduleActivityReminders(context, session, store.loadActivityReminderSettings())
        }
        scheduleDailyStatusCheckIn(context, store.loadStatusCheckInSettings())
    }

    fun scheduleDailyStatusCheckIn(
        context: Context,
        settings: StatusCheckInSettings = PrototypeStore(context).loadStatusCheckInSettings()
    ) {
        cancelStatusCheckIn(context)
        if (!settings.enabled) return
        val now = System.currentTimeMillis()
        val next = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, settings.promptHour)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= now + 60_000L) add(Calendar.DAY_OF_YEAR, 1)
        }.timeInMillis
        scheduleStatusCheckInAt(context, next)
    }

    fun snoozeStatusCheckIn(context: Context, minutes: Int) {
        cancelStatusCheckIn(context)
        scheduleStatusCheckInAt(context, System.currentTimeMillis() + minutes.coerceIn(30, 180) * 60_000L)
    }

    fun cancelStatusCheckIn(context: Context) {
        val manager = context.getSystemService(AlarmManager::class.java)
        val pending = PendingIntent.getBroadcast(
            context,
            STATUS_CHECK_IN_REQUEST_CODE,
            Intent(context, ReminderReceiver::class.java).apply { action = ReminderReceiver.ACTION_STATUS_CHECK_IN },
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        pending?.let(manager::cancel)
    }

    private fun scheduleStatusCheckInAt(context: Context, triggerAt: Long) {
        val pending = PendingIntent.getBroadcast(
            context,
            STATUS_CHECK_IN_REQUEST_CODE,
            Intent(context, ReminderReceiver::class.java).apply { action = ReminderReceiver.ACTION_STATUS_CHECK_IN },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        context.getSystemService(AlarmManager::class.java)
            .setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
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
        val manager = context.getSystemService(AlarmManager::class.java)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()) {
            manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
        } else {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
        }
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

    private const val STATUS_CHECK_IN_REQUEST_CODE = 2_900_001
}
