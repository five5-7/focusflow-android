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
        scheduleDailyMealReminders(context, store.loadBaselineProfile())
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

    /** 为今天的每餐调度一次“准备吃饭”提醒；已开始或已标记“今天不需要”的餐次跳过。 */
    fun scheduleDailyMealReminders(
        context: Context,
        profile: BaselineProfile = PrototypeStore(context).loadBaselineProfile()
    ) {
        cancelAllMealReminders(context)
        val store = PrototypeStore(context)
        if (!store.loadMealReminderEnabled() || profile.lifeStage == null) return
        val records = store.loadMealRecords()
        val skipDays = store.loadMealSkipDays()
        val todayKey = MealLearning.dayKey(System.currentTimeMillis())
        val todayWeekday = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
        val now = System.currentTimeMillis()
        MealType.entries.forEach { type ->
            val plan = MealLearning.todayPlan(records, profile, todayWeekday, type)
            val startAt = todayAtMinute(plan.startMinute)
            val skipKey = "$todayKey:${type.label}"
            if (MealLearning.startedToday(records, now, type) || skipKey in skipDays) return@forEach
            if (startAt <= now - 120 * 60_000L) return@forEach
            scheduleMealReminderAt(context, type, plan, startAt)
        }
    }

    fun snoozeMealReminder(context: Context, type: MealType) {
        val store = PrototypeStore(context)
        val profile = store.loadBaselineProfile()
        val plan = MealLearning.todayPlan(store.loadMealRecords(), profile, Calendar.getInstance().get(Calendar.DAY_OF_WEEK), type)
        scheduleMealReminderAt(context, type, plan, System.currentTimeMillis() + 20 * 60_000L)
    }

    /** 开始在吃后，按个人时长估计结束时间并提醒“吃完了吗”。 */
    fun scheduleMealEndReminder(context: Context, record: MealRecord, minutes: Int) {
        val triggerAt = record.startedAt + minutes.coerceIn(5, 120) * 60_000L
        if (triggerAt <= System.currentTimeMillis()) return
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ReminderReceiver.ACTION_MEAL_END_REMINDER
            putExtra(ReminderReceiver.EXTRA_MEAL_TYPE, record.mealType.label)
        }
        val pending = PendingIntent.getBroadcast(context, mealEndRequestCode(record.mealType), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        context.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
    }

    fun cancelMealReminder(context: Context, type: MealType) {
        cancelPending(context, mealRequestCode(type), ReminderReceiver.ACTION_MEAL_REMINDER)
        cancelPending(context, mealEndRequestCode(type), ReminderReceiver.ACTION_MEAL_END_REMINDER)
    }

    fun cancelAllMealReminders(context: Context) {
        MealType.entries.forEach { cancelMealReminder(context, it) }
    }

    private fun scheduleMealReminderAt(context: Context, type: MealType, plan: MealPlan, triggerAt: Long) {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ReminderReceiver.ACTION_MEAL_REMINDER
            putExtra(ReminderReceiver.EXTRA_MEAL_TYPE, type.label)
            putExtra(ReminderReceiver.EXTRA_MEAL_LEARNED, plan.learned)
        }
        val pending = PendingIntent.getBroadcast(context, mealRequestCode(type), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        context.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
    }

    private fun todayAtMinute(minute: Int): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, minute / 60)
        set(Calendar.MINUTE, minute % 60)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun cancelPending(context: Context, requestCode: Int, action: String) {
        val pending = PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, ReminderReceiver::class.java).apply { this.action = action },
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        pending?.let { context.getSystemService(AlarmManager::class.java).cancel(it) }
    }

    private fun mealRequestCode(type: MealType): Int = 3_000_001 + type.ordinal

    private fun mealEndRequestCode(type: MealType): Int = 3_001_001 + type.ordinal

    private const val STATUS_CHECK_IN_REQUEST_CODE = 2_900_001
}
