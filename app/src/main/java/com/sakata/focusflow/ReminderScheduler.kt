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
                        putExtra(ReminderReceiver.EXTRA_ACTIVITY_ENDS_AT, session.endsAt)
                    })
                }
            } else scheduleActivityReminders(context, session, store.loadActivityReminderSettings())
        }
        scheduleDailyStatusCheckIn(context, store.loadStatusCheckInSettings())
        scheduleDailyMealReminders(context, store.loadBaselineProfile())
        scheduleDailyWindDown(context, store.loadBaselineProfile())
        restoreTaskReminders(context)
    }

    fun scheduleDailyStatusCheckIn(
        context: Context,
        settings: StatusCheckInSettings = PrototypeStore(context).loadStatusCheckInSettings()
    ) {
        cancelStatusCheckIn(context)
        if (!settings.enabled) return
        val now = System.currentTimeMillis()
        val next = nextDailyTriggerAt(now, settings.promptHour)
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
            putExtra(ReminderReceiver.EXTRA_ACTIVITY_ENDS_AT, session.endsAt)
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

    private fun gameRequestCode(sessionId: Long, offset: Int): Int {
        return ((sessionId + 700_000L) % 500_000_000L).toInt() * 3 + offset
    }

    /** 游戏安排：到点提醒开始（可选）+ 到点检测前台是否还在玩（结束提醒始终有）。 */
    fun scheduleGameReminders(context: Context, session: GameSessionRecord) {
        cancelGameReminders(context, session.id)
        val now = System.currentTimeMillis()
        val manager = context.getSystemService(AlarmManager::class.java)
        if (session.remindStart && session.plannedStartAt > now) {
            val startIntent = Intent(context, ReminderReceiver::class.java).apply {
                action = ReminderReceiver.ACTION_GAME_START
                putExtra(ReminderReceiver.EXTRA_GAME_SESSION_ID, session.id)
                putExtra(ReminderReceiver.EXTRA_GAME_TITLE, session.title)
                putExtra(ReminderReceiver.EXTRA_GAME_PLANNED_AT, session.plannedStartAt)
            }
            val pending = PendingIntent.getBroadcast(context, gameRequestCode(session.id, 1), startIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, session.plannedStartAt, pending)
        }
        if (session.plannedEndAt > now) {
            val endIntent = Intent(context, ReminderReceiver::class.java).apply {
                action = ReminderReceiver.ACTION_GAME_END
                putExtra(ReminderReceiver.EXTRA_GAME_SESSION_ID, session.id)
                putExtra(ReminderReceiver.EXTRA_GAME_TITLE, session.title)
                putExtra(ReminderReceiver.EXTRA_GAME_PLANNED_AT, session.plannedEndAt)
            }
            val pending = PendingIntent.getBroadcast(context, gameRequestCode(session.id, 2), endIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, session.plannedEndAt, pending)
        }
    }

    /** 到点仍在玩时，10 分钟后复查一次。 */
    fun scheduleGameFollowUp(context: Context, sessionId: Long, title: String, plannedEndAt: Long, at: Long) {
        val followIntent = Intent(context, ReminderReceiver::class.java).apply {
            action = ReminderReceiver.ACTION_GAME_END_FOLLOWUP
            putExtra(ReminderReceiver.EXTRA_GAME_SESSION_ID, sessionId)
            putExtra(ReminderReceiver.EXTRA_GAME_TITLE, title)
            putExtra(ReminderReceiver.EXTRA_GAME_PLANNED_AT, plannedEndAt)
        }
        val pending = PendingIntent.getBroadcast(context, gameRequestCode(sessionId, 3), followIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        context.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
    }

    fun cancelGameReminders(context: Context, sessionId: Long) {
        val manager = context.getSystemService(AlarmManager::class.java)
        val actions = listOf(ReminderReceiver.ACTION_GAME_START, ReminderReceiver.ACTION_GAME_END, ReminderReceiver.ACTION_GAME_END_FOLLOWUP)
        actions.forEachIndexed { index, action ->
            val intent = Intent(context, ReminderReceiver::class.java).apply { this.action = action }
            val pending = PendingIntent.getBroadcast(context, gameRequestCode(sessionId, index + 1), intent, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
            pending?.let(manager::cancel)
        }
    }

    /** 开机/升级后恢复未结束的游戏提醒。 */
    fun restoreGameReminders(context: Context) {
        val store = PrototypeStore(context)
        store.loadGameSessions().filter { it.isOpen() }.forEach { session ->
            if (session.plannedEndAt > System.currentTimeMillis()) scheduleGameReminders(context, session)
        }
    }

    fun scheduleTaskReminder(
        context: Context,
        item: Item,
        settings: ActivityReminderSettings = PrototypeStore(context).loadActivityReminderSettings()
    ) {
        val scheduledAt = item.scheduledAt ?: return
        cancelTaskReminder(context, item.id)
        if (!settings.scheduleRemindersEnabled || item.done || item.kind in setOf("收集箱", "暂停", "游戏", "活动")) return
        // 不补发已经开始的日程；否则一次重启会把旧安排集中推送。
        val now = System.currentTimeMillis()
        if (scheduledAt <= now) return
        TaskReminderPolicy.pendingReminders(listOf(item), settings, now).forEach { reminder ->
            val actionName = when (reminder.stage) {
                TaskReminderStage.ADVANCE -> ReminderReceiver.ACTION_TASK_ADVANCE
                TaskReminderStage.DUE -> ReminderReceiver.ACTION_TASK_DUE
            }
            val intent = Intent(context, ReminderReceiver::class.java).apply {
                action = actionName
                putExtra(ReminderReceiver.EXTRA_TASK_ID, item.id)
                putExtra(ReminderReceiver.EXTRA_TASK_TITLE, reminder.title)
                putExtra(ReminderReceiver.EXTRA_TASK_START_AT, scheduledAt)
            }
            val pending = PendingIntent.getBroadcast(
                context,
                taskRequestCode(item.id, reminder.stage),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            scheduleTimeSensitiveAlarm(
                context = context,
                triggerAt = reminder.triggerAt,
                pending = pending,
                preferAlarmClock = reminder.stage == TaskReminderStage.DUE
            )
        }
    }

    /** 使用与真实日程相同的系统调度链路，帮助用户在一分钟内验证权限、渠道与后台触发。 */
    fun scheduleTaskReminderTest(context: Context, now: Long = System.currentTimeMillis()): AlarmDeliveryMode {
        cancelPending(context, TASK_TEST_REQUEST_CODE, ReminderReceiver.ACTION_TASK_TEST)
        val expectedAt = now + 60_000L
        PrototypeStore(context).saveReminderTestScheduled(expectedAt)
        val pending = PendingIntent.getBroadcast(
            context,
            TASK_TEST_REQUEST_CODE,
            Intent(context, ReminderReceiver::class.java).apply { action = ReminderReceiver.ACTION_TASK_TEST },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return scheduleTimeSensitiveAlarm(context, expectedAt, pending, preferAlarmClock = true)
    }

    fun cancelTaskReminder(context: Context, itemId: Long) {
        cancelPending(context, taskRequestCode(itemId, TaskReminderStage.ADVANCE), ReminderReceiver.ACTION_TASK_ADVANCE)
        cancelPending(context, taskRequestCode(itemId, TaskReminderStage.DUE), ReminderReceiver.ACTION_TASK_DUE)
    }

    /** 设备重启/应用更新后恢复未来日程，已开始或已完成项目不补发。 */
    fun restoreTaskReminders(context: Context) {
        val store = PrototypeStore(context)
        val settings = store.loadActivityReminderSettings()
        store.loadItems().filter { !it.done && it.scheduledAt != null && it.scheduledAt > System.currentTimeMillis() }
            .forEach { scheduleTaskReminder(context, it, settings) }
    }

    /** 保存日程时同步闹钟，删除、完成或改期都不会留下幽灵提醒。 */
    fun syncTaskReminders(context: Context, previous: List<Item>, updated: List<Item>) {
        previous.forEach { cancelTaskReminder(context, it.id) }
        val settings = PrototypeStore(context).loadActivityReminderSettings()
        updated.forEach { scheduleTaskReminder(context, it, settings) }
    }

    /** 为今天的每餐调度一次“准备吃饭”提醒；已开始或已标记“今天不需要”的餐次跳过。 */
    fun scheduleDailyMealReminders(
        context: Context,
        profile: BaselineProfile = PrototypeStore(context).loadBaselineProfile()
    ) {
        // 只替换“准备吃饭”闹钟；正在进行的餐的结束提醒不能被日常重排误取消。
        MealType.entries.forEach { cancelMealPrompt(context, it) }
        val store = PrototypeStore(context)
        if (!store.loadMealReminderEnabled() || profile.lifeStage == null) return
        val records = store.loadMealRecords()
        val skipDays = store.loadMealSkipDays()
        val todayKey = MealLearning.dayKey(System.currentTimeMillis())
        val todayWeekday = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
        val now = System.currentTimeMillis()
        MealType.entries.forEach { type ->
            val plan = MealLearning.todayPlan(records, profile, todayWeekday, type)
            val startAt = todayAtMinute(plan.startMinute, now)
            val skipKey = "$todayKey:${type.label}"
            if (MealLearning.startedToday(records, now, type) || skipKey in skipDays) return@forEach
            // 过了合理窗口后只在今日卡片留手动记录入口，不补发“准备吃饭”。
            if (startAt <= now - MEAL_PROMPT_GRACE_MINUTES * 60_000L) return@forEach
            scheduleMealReminderAt(context, type, plan, startAt)
        }
        restoreOpenMealEndReminders(context, records, profile)
        scheduleTomorrowMealRefresh(context)
    }

    fun snoozeMealReminder(context: Context, type: MealType) {
        val store = PrototypeStore(context)
        val profile = store.loadBaselineProfile()
        val plan = MealLearning.todayPlan(store.loadMealRecords(), profile, Calendar.getInstance().get(Calendar.DAY_OF_WEEK), type)
        scheduleMealReminderAt(context, type, plan, System.currentTimeMillis() + 20 * 60_000L)
    }

    /** 开始在吃后，按个人时长估计结束时间并提醒“吃完了吗”。 */
    fun scheduleMealEndReminder(context: Context, record: MealRecord, minutes: Int) {
        val triggerAt = mealEndTriggerAt(record.startedAt, minutes)
        if (triggerAt <= System.currentTimeMillis()) return
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ReminderReceiver.ACTION_MEAL_END_REMINDER
            putExtra(ReminderReceiver.EXTRA_MEAL_TYPE, record.mealType.label)
            putExtra(ReminderReceiver.EXTRA_MEAL_RECORD_ID, record.id)
        }
        val pending = PendingIntent.getBroadcast(context, mealEndRequestCode(record.mealType), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        context.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
    }

    fun cancelMealReminder(context: Context, type: MealType) {
        cancelMealPrompt(context, type)
        cancelPending(context, mealEndRequestCode(type), ReminderReceiver.ACTION_MEAL_END_REMINDER)
    }

    fun cancelAllMealReminders(context: Context) {
        MealType.entries.forEach { cancelMealReminder(context, it) }
        cancelPending(context, DAILY_MEAL_REFRESH_REQUEST_CODE, ReminderReceiver.ACTION_DAILY_MEAL_REFRESH)
    }

    fun dismissMealForToday(context: Context, type: MealType) {
        val store = PrototypeStore(context)
        val today = MealLearning.dayKey(System.currentTimeMillis())
        val retained = store.loadMealSkipDays().filterNot { it.substringBefore(':') != today }.toMutableSet()
        retained += "$today:${type.label}"
        store.saveMealSkipDays(retained)
        cancelMealPrompt(context, type)
    }

    private fun cancelMealPrompt(context: Context, type: MealType) {
        cancelPending(context, mealRequestCode(type), ReminderReceiver.ACTION_MEAL_REMINDER)
    }

    private fun restoreOpenMealEndReminders(context: Context, records: List<MealRecord>, profile: BaselineProfile) {
        MealType.entries.forEach { type ->
            val record = MealLearning.latestOpen(records, type) ?: return@forEach
            if (!MealLearning.sameDay(record.startedAt, System.currentTimeMillis())) return@forEach
            val plan = MealLearning.todayPlan(records, profile, Calendar.getInstance().get(Calendar.DAY_OF_WEEK), type)
            scheduleMealEndReminder(context, record, plan.minutes)
        }
    }

    /** 每天零点后自动产生新一轮饭点闹钟，不依赖再次打开应用。 */
    private fun scheduleTomorrowMealRefresh(context: Context) {
        val next = nextDayAtMinute(5)
        val pending = PendingIntent.getBroadcast(
            context,
            DAILY_MEAL_REFRESH_REQUEST_CODE,
            Intent(context, ReminderReceiver::class.java).apply { action = ReminderReceiver.ACTION_DAILY_MEAL_REFRESH },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        context.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pending)
    }

    private fun scheduleMealReminderAt(context: Context, type: MealType, plan: MealPlan, triggerAt: Long) {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ReminderReceiver.ACTION_MEAL_REMINDER
            putExtra(ReminderReceiver.EXTRA_MEAL_TYPE, type.label)
            putExtra(ReminderReceiver.EXTRA_MEAL_LEARNED, plan.learned)
            putExtra(ReminderReceiver.EXTRA_MEAL_PLANNED_AT, triggerAt)
        }
        val pending = PendingIntent.getBroadcast(context, mealRequestCode(type), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        context.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
    }

    /** 今天某整分钟的时刻。 */
    internal fun todayAtMinute(minuteOfDay: Int, now: Long = System.currentTimeMillis()): Long = Calendar.getInstance().apply {
        timeInMillis = now
        set(Calendar.HOUR_OF_DAY, minuteOfDay / 60)
        set(Calendar.MINUTE, minuteOfDay % 60)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** 下一次“当天 xx 点整”的触发时刻：今天该时刻仍远于 60 秒则取今天，否则推到明天。 */
    internal fun nextDailyTriggerAt(now: Long, hourOfDay: Int): Long = Calendar.getInstance().apply {
        timeInMillis = now
        set(Calendar.HOUR_OF_DAY, hourOfDay.coerceIn(0, 23))
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        if (timeInMillis <= now + 60_000L) add(Calendar.DAY_OF_YEAR, 1)
    }.timeInMillis

    /** 明天的整分钟时刻（默认用于零点后饭点轮换闹钟）。 */
    internal fun nextDayAtMinute(minuteOfDay: Int): Long = Calendar.getInstance().apply {
        add(Calendar.DAY_OF_YEAR, 1)
        set(Calendar.HOUR_OF_DAY, minuteOfDay / 60)
        set(Calendar.MINUTE, minuteOfDay % 60)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** 餐点结束提醒的触发时刻：开始时间 + 预估时长（5-120 分钟）。 */
    internal fun mealEndTriggerAt(startedAt: Long, minutes: Int): Long =
        startedAt + minutes.coerceIn(5, 120) * 60_000L


    private fun cancelPending(context: Context, requestCode: Int, action: String) {
        val pending = PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, ReminderReceiver::class.java).apply { this.action = action },
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        pending?.let { context.getSystemService(AlarmManager::class.java).cancel(it) }
    }

    private fun taskRequestCode(itemId: Long, stage: TaskReminderStage): Int {
        val offset = if (stage == TaskReminderStage.ADVANCE) 20_000L else 30_000L
        return ((itemId + offset) % Int.MAX_VALUE).toInt()
    }

    /** 时间敏感闹钟的后备链（强 → 弱）：preferAlarmClock 时先走系统闹钟路径，再精确、最后普通后台提醒。 */
    internal fun timingChain(
        preferAlarmClock: Boolean,
        sdkInt: Int,
        canScheduleExactAlarms: Boolean
    ): List<AlarmDeliveryMode> = buildList {
        if (preferAlarmClock) add(AlarmDeliveryMode.ALARM_CLOCK)
        if (TaskReminderPolicy.deliveryMode(sdkInt, canScheduleExactAlarms) == AlarmDeliveryMode.EXACT) add(AlarmDeliveryMode.EXACT)
        add(AlarmDeliveryMode.INEXACT)
    }

    private fun scheduleTimeSensitiveAlarm(
        context: Context,
        triggerAt: Long,
        pending: PendingIntent,
        preferAlarmClock: Boolean = false
    ): AlarmDeliveryMode {
        val manager = context.getSystemService(AlarmManager::class.java)
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()
        for (mode in timingChain(preferAlarmClock, Build.VERSION.SDK_INT, canExact)) {
            if (applyTimingMode(manager, mode, triggerAt, pending, context)) return mode
        }
        return AlarmDeliveryMode.INEXACT
    }

    private fun applyTimingMode(
        manager: AlarmManager,
        mode: AlarmDeliveryMode,
        triggerAt: Long,
        pending: PendingIntent,
        context: Context
    ): Boolean = try {
        when (mode) {
            AlarmDeliveryMode.ALARM_CLOCK -> {
                val showIntent = PendingIntent.getActivity(
                    context,
                    ALARM_CLOCK_SHOW_REQUEST_CODE,
                    Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                manager.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAt, showIntent), pending)
            }
            AlarmDeliveryMode.EXACT -> manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
            AlarmDeliveryMode.INEXACT -> manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
        }
        true
    } catch (_: SecurityException) {
        // 权限可能在检查后被系统撤销；沿降级链继续，不能静默丢失。
        false
    }

    private const val TASK_TEST_REQUEST_CODE = 2_900_001
    private const val ALARM_CLOCK_SHOW_REQUEST_CODE = 2_900_002

    private fun mealRequestCode(type: MealType): Int = 3_000_001 + type.ordinal

    private fun mealEndRequestCode(type: MealType): Int = 3_001_001 + type.ordinal

    /** 每晚睡前减速提醒：按睡觉锚点提前 40 分钟调度，改期用 setAndAllowWhileIdle。 */
    fun scheduleDailyWindDown(
        context: Context,
        profile: BaselineProfile = PrototypeStore(context).loadBaselineProfile()
    ) {
        cancelWindDown(context)
        val store = PrototypeStore(context)
        if (!store.loadWindDownEnabled() || profile.lifeStage == null) return
        val minute = WindDownInsights.windDownMinute(profile) ?: return
        val next = nextDailyTriggerAt(System.currentTimeMillis(), minute)
        scheduleWindDownAt(context, next)
    }

    private fun scheduleWindDownAt(context: Context, triggerAt: Long) {
        val pending = PendingIntent.getBroadcast(
            context,
            WIND_DOWN_REQUEST_CODE,
            Intent(context, ReminderReceiver::class.java).apply { action = ReminderReceiver.ACTION_WIND_DOWN },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        context.getSystemService(AlarmManager::class.java)
            .setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
    }

    fun cancelWindDown(context: Context) {
        val manager = context.getSystemService(AlarmManager::class.java)
        val pending = PendingIntent.getBroadcast(
            context,
            WIND_DOWN_REQUEST_CODE,
            Intent(context, ReminderReceiver::class.java).apply { action = ReminderReceiver.ACTION_WIND_DOWN },
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        pending?.let(manager::cancel)
    }

    private const val STATUS_CHECK_IN_REQUEST_CODE = 2_900_001
    private const val WIND_DOWN_REQUEST_CODE = 2_900_003
    private const val DAILY_MEAL_REFRESH_REQUEST_CODE = 3_000_090
    private const val MEAL_PROMPT_GRACE_MINUTES = 45L
}
