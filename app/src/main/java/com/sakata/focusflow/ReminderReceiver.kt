package com.sakata.focusflow

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import java.util.Calendar

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)
        val sessionId = intent.getLongExtra(EXTRA_SESSION_ID, -1L)
        val activityName = intent.getStringExtra(EXTRA_ACTIVITY_NAME) ?: "当前活动"
        val nextStep = intent.getStringExtra(EXTRA_NEXT_STEP).orEmpty()
        val store = PrototypeStore(context)
        when (intent.action) {
            ACTION_STATUS_CHECK_IN -> {
                val settings = store.loadStatusCheckInSettings()
                ReminderScheduler.scheduleDailyStatusCheckIn(context, settings)
                if (!settings.enabled) return
                if (suppressNow(store, intent.action)) return
                val active = store.loadLatestActiveSession()
                if (active != null) {
                    val minutes = ((active.endsAt - System.currentTimeMillis()) / 60_000L + 10).toInt().coerceIn(30, 180)
                    ReminderScheduler.snoozeStatusCheckIn(context, minutes)
                    return
                }
                if (store.loadLatestStatusCheckIn()?.recordedAt?.let(::isSameDayAsNow) == true) return
                showStatusCheckInNotification(context, manager, settings)
                return
            }
            ACTION_STATUS_CHECK_IN_SNOOZE -> {
                if (notificationId >= 0) manager.cancel(notificationId)
                ReminderScheduler.snoozeStatusCheckIn(context, store.loadStatusCheckInSettings().snoozeMinutes)
                return
            }
            ACTION_COMPLETE -> {
                if (notificationId >= 0) manager.cancel(notificationId)
                if (sessionId >= 0) {
                    store.finishSession(sessionId, ActivitySession.STATUS_COMPLETED, "notification_finish")
                    ReminderScheduler.cancelActivityReminders(context, sessionId)
                }
                return
            }
            ACTION_SKIP -> {
                if (notificationId >= 0) manager.cancel(notificationId)
                if (sessionId >= 0) {
                    store.finishSession(sessionId, ActivitySession.STATUS_SKIPPED, "replan")
                    ReminderScheduler.cancelActivityReminders(context, sessionId)
                }
                store.addReplanItem(nextStep.ifBlank { activityName })
                return
            }
            ACTION_SNOOZE -> {
                if (notificationId >= 0) manager.cancel(notificationId)
                val delayed = sessionId.takeIf { it >= 0 }?.let { store.extendSession(it, 10, "通知中延长") }
                delayed?.let { ReminderScheduler.scheduleActivityReminders(context, it) }
                return
            }
            ACTION_ACTIVITY_PREVIEW -> {
                showActivityPreview(context, manager, activityName, nextStep, sessionId)
                return
            }
            ACTION_ACTIVITY_END -> {
                if (sessionId >= 0) store.markSessionAwaitingConfirmation(sessionId)
            }
            ACTION_TASK_ADVANCE, ACTION_TASK_DUE -> {
                showTaskNotification(
                    context,
                    manager,
                    intent.getStringExtra(EXTRA_TASK_TITLE) ?: "日程任务",
                    intent.getLongExtra(EXTRA_TASK_ID, -1L),
                    intent.getLongExtra(EXTRA_TASK_START_AT, 0L),
                    dueNow = intent.action == ACTION_TASK_DUE
                )
                return
            }
            ACTION_TASK_TEST -> {
                store.markReminderTestDelivered()
                showTaskTestNotification(context, manager)
                return
            }
            ACTION_MEAL_REMINDER -> {
                if (suppressNow(store, intent.action)) return
                val type = MealType.fromLabel(intent.getStringExtra(EXTRA_MEAL_TYPE) ?: "")
                if (type != null) showMealPromptNotification(context, manager, type, intent.getBooleanExtra(EXTRA_MEAL_LEARNED, false))
                return
            }
            ACTION_MEAL_DISMISS -> {
                val type = MealType.fromLabel(intent.getStringExtra(EXTRA_MEAL_TYPE) ?: "")
                if (type != null) ReminderScheduler.dismissMealForToday(context, type)
                return
            }
            ACTION_MEAL_SNOOZE -> {
                if (notificationId >= 0) manager.cancel(notificationId)
                MealType.fromLabel(intent.getStringExtra(EXTRA_MEAL_TYPE) ?: "")?.let { ReminderScheduler.snoozeMealReminder(context, it) }
                return
            }
            ACTION_MEAL_END_REMINDER -> {
                if (suppressNow(store, intent.action)) return
                val type = MealType.fromLabel(intent.getStringExtra(EXTRA_MEAL_TYPE) ?: "")
                if (type != null) showMealEndNotification(context, manager, type)
                return
            }
            ACTION_WIND_DOWN -> {
                if (suppressNow(store, intent.action)) return
                showWindDownNotification(context, manager)
                return
            }
            ACTION_MEAL_STILL_EATING -> {
                if (notificationId >= 0) manager.cancel(notificationId)
                val type = MealType.fromLabel(intent.getStringExtra(EXTRA_MEAL_TYPE) ?: "")
                val store = PrototypeStore(context)
                val record = type?.let { MealLearning.latestOpen(store.loadMealRecords(), it) }
                val profile = store.loadBaselineProfile()
                val minutes = type?.let { MealLearning.predictedMinutes(store.loadMealRecords(), profile.lifeStage, java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_WEEK), it) ?: profile.meals.firstOrNull { m -> m.type == it }?.typicalMinutes ?: 20 }
                if (record != null && type != null && minutes != null) {
                    ReminderScheduler.scheduleMealEndReminder(context, record.copy(startedAt = System.currentTimeMillis()), minutes)
                }
                return
            }
            ACTION_DAILY_MEAL_REFRESH -> {
                ReminderScheduler.scheduleDailyMealReminders(context)
                return
            }
            ACTION_TASK_COMPLETE -> {
                if (notificationId >= 0) manager.cancel(notificationId)
                val taskId = intent.getLongExtra(EXTRA_TASK_ID, -1L)
                if (taskId >= 0) store.findItem(taskId)?.let { task ->
                    ReminderScheduler.cancelTaskReminder(context, taskId)
                    store.updateItem(taskId) { it.copy(done = true, completionLevel = "完整完成", completedAt = System.currentTimeMillis()) }
                    store.appendTaskEvent(TaskRecorder.event(TaskEventType.TASK_COMPLETED, task.id, task.title, extra = "完整完成"))
                    task.goalId?.let { store.markGoalCompleted(it) }
                    task.scheduledAt?.let { time ->
                        val cal = java.util.Calendar.getInstance().apply { timeInMillis = time }
                        val day = when (cal.get(java.util.Calendar.DAY_OF_WEEK)) { java.util.Calendar.SUNDAY -> 7 else -> cal.get(java.util.Calendar.DAY_OF_WEEK) - 1 }
                        PlanLearning.recordCompleted(store, day, cal.get(java.util.Calendar.HOUR_OF_DAY))
                    }
                }
                return
            }
            ACTION_TASK_MINIMUM -> {
                if (notificationId >= 0) manager.cancel(notificationId)
                val taskId = intent.getLongExtra(EXTRA_TASK_ID, -1L)
                if (taskId >= 0) store.findItem(taskId)?.let { task ->
                    ReminderScheduler.cancelTaskReminder(context, taskId)
                    store.updateItem(taskId) { it.copy(done = true, completionLevel = "最低版本", completedAt = System.currentTimeMillis()) }
                    store.appendTaskEvent(TaskRecorder.event(TaskEventType.TASK_COMPLETED, task.id, task.title, extra = "最低版本"))
                    task.goalId?.let { store.markGoalCompleted(it, minimum = true) }
                }
                return
            }
            ACTION_TASK_SNOOZE -> {
                if (notificationId >= 0) manager.cancel(notificationId)
                val taskId = intent.getLongExtra(EXTRA_TASK_ID, -1L)
                store.findItem(taskId)?.let { item ->
                    val delayed = item.copy(scheduledAt = System.currentTimeMillis() + 60 * 60_000L, detail = "已延后一小时；到时再问你")
                    store.updateItem(taskId) { delayed }
                    store.appendTaskEvent(TaskRecorder.event(TaskEventType.TASK_RESCHEDULED, item.id, item.title, scheduledAt = delayed.scheduledAt ?: 0, extra = "延后一小时"))
                    ReminderScheduler.scheduleTaskReminder(context, delayed)
                }
                return
            }
            ACTION_TASK_SKIP -> {
                if (notificationId >= 0) manager.cancel(notificationId)
                val taskId = intent.getLongExtra(EXTRA_TASK_ID, -1L)
                if (taskId >= 0) {
                    ReminderScheduler.cancelTaskReminder(context, taskId)
                    store.updateItem(taskId) { item -> item.copy(title = if (item.title.startsWith("重新安排：")) item.title else "重新安排：${item.title}", kind = "收集箱", detail = "这次没有做；可以改期、缩短、暂停或放弃", scheduledAt = null) }
                    store.findItem(taskId)?.let { updated ->
                        store.appendTaskEvent(TaskRecorder.event(TaskEventType.TASK_TO_INBOX, updated.id, updated.title.removePrefix("重新安排："), extra = "跳过"))
                    }
                }
                return
            }
            ACTION_GAME_START -> {
                showGameStartNotification(context, manager, intent)
                return
            }
            ACTION_GAME_END -> {
                handleGameEndCheck(context, manager, intent, followUp = false)
                return
            }
            ACTION_GAME_END_FOLLOWUP -> {
                handleGameEndCheck(context, manager, intent, followUp = true)
                return
            }
            ACTION_GAME_FINISH -> {
                if (notificationId >= 0) manager.cancel(notificationId)
                recordGameActualEnd(context, intent.getLongExtra(EXTRA_GAME_SESSION_ID, -1L), System.currentTimeMillis())
                return
            }
            ACTION_GAME_EXTEND -> {
                if (notificationId >= 0) manager.cancel(notificationId)
                val sessionId = intent.getLongExtra(EXTRA_GAME_SESSION_ID, -1L)
                PrototypeStore(context).loadGameSessions().firstOrNull { it.id == sessionId && it.isOpen() }?.let { session ->
                    val extended = session.copy(plannedEndAt = session.plannedEndAt + 15 * 60_000L)
                    store.updateGameSession(sessionId) { extended }
                    ReminderScheduler.cancelGameReminders(context, sessionId)
                    ReminderScheduler.scheduleGameReminders(context, extended)
                }
                return
            }
            else -> return
        }
        if (!store.loadActivityReminderSettings().notificationsEnabled) return
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val stronger = store.loadActivityReminderSettings().strongerEndReminder
        val endChannel = if (stronger) CHANNEL_ACTIVITY_END else CHANNEL_ACTIVITY_END_GENTLE
        // 所有提醒渠道升级为高重要性：横幅弹出几秒＋声音（像微信）；温和版到点提醒为静音横幅。
        ensureChannel(manager, endChannel, "活动结束提醒", silent = !stronger)
        val id = (System.currentTimeMillis() % Int.MAX_VALUE).toInt()
        val text = if (nextStep.isBlank()) "预计时间已到。现在结束、延长，或打开 FocusFlow 决定下一步。" else "预计时间已到。下一步：$nextStep"
        val openApp = PendingIntent.getActivity(context, id + 9, Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, endChannel)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("$activityName 时间到了")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp)
            .addAction(0, "结束活动", actionIntent(context, ACTION_COMPLETE, activityName, nextStep, sessionId, id, 1))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
        val current = store.findActivitySession(sessionId)
        if (current != null && current.extensionCount < store.loadActivityReminderSettings().maxExtensions) {
            notification.addAction(0, "延长 10 分钟", actionIntent(context, ACTION_SNOOZE, activityName, nextStep, sessionId, id, 2))
        }
        notification.addAction(0, "打开转场", openApp)
        manager.notify(id, notification.build())
    }

    /** 打扰控制：一次性静音期间全部静音；免打扰时段内按类型静音（状态询问/饭点/睡前减速）。 */
    private fun suppressNow(store: PrototypeStore, action: String?): Boolean {
        val quiet = store.loadQuietHoursSettings()
        if (quiet.isMuted()) return true
        return quiet.inQuietHours() && QuietHoursSettings.suppresses(quiet, action ?: "")
    }

    private fun showActivityPreview(context: Context, manager: NotificationManager, activityName: String, nextStep: String, sessionId: Long) {
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        ensureChannel(manager, CHANNEL_ACTIVITY_PREVIEW, "活动结束预告")
        val id = ((sessionId % Int.MAX_VALUE) + 700).toInt()
        val openApp = PendingIntent.getActivity(context, id, Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val text = if (nextStep.isBlank()) "$activityName 即将到达预计结束时间，可以开始收尾。" else "$activityName 即将结束；接下来准备：$nextStep"
        manager.notify(id, NotificationCompat.Builder(context, CHANNEL_ACTIVITY_PREVIEW)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("还有一点时间，准备收尾")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .build())
    }

    private fun showTaskNotification(context: Context, manager: NotificationManager, title: String, taskId: Long, startsAt: Long, dueNow: Boolean) {
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val task = PrototypeStore(context).findItem(taskId) ?: return
        // 改期与完成可能正好和旧广播交错；以当前存储状态为准，避免幽灵通知。
        if (task.done || task.scheduledAt != startsAt || task.kind in setOf("收集箱", "暂停", "游戏", "活动")) return
        ensureChannel(manager, CHANNEL_TASK, "FocusFlow 任务提醒")
        val openApp = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val id = ((taskId + 40_000L) % Int.MAX_VALUE).toInt()
        val minutes = ((startsAt - System.currentTimeMillis()) / 60_000L).toInt().coerceAtLeast(0)
        val timing = if (dueNow) "现在该开始了。" else if (minutes <= 1) "即将开始。" else "约 $minutes 分钟后开始。"
        val notification = NotificationCompat.Builder(context, CHANNEL_TASK)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(if (dueNow) "到点了：$title" else "即将开始：$title")
            .setContentText("$timing 可开始、稍后或改期。")
            .setContentIntent(openApp)
            .addAction(0, "完整完成", taskActionIntent(context, ACTION_TASK_COMPLETE, taskId, id, 11))
            .addAction(0, "稍后 1 小时", taskActionIntent(context, ACTION_TASK_SNOOZE, taskId, id, 12))
            .setAutoCancel(true)
        if (task.goalId != null) notification.addAction(0, "最低版本", taskActionIntent(context, ACTION_TASK_MINIMUM, taskId, id, 13))
        manager.notify(id, notification.build())
    }

    private fun showTaskTestNotification(context: Context, manager: NotificationManager) {
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        ensureChannel(manager, CHANNEL_TASK, "FocusFlow 任务提醒")
        val openApp = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify(
            TASK_TEST_NOTIFICATION_ID,
            NotificationCompat.Builder(context, CHANNEL_TASK)
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle("FocusFlow 测试提醒")
                .setContentText("后台测试广播已送达；返回设置可查看是否准时。")
                .setContentIntent(openApp)
                .setAutoCancel(true)
                .build()
        )
    }

    private fun showStatusCheckInNotification(
        context: Context,
        manager: NotificationManager,
        settings: StatusCheckInSettings
    ) {
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        ensureChannel(manager, CHANNEL_STATUS_CHECK_IN, "低打扰状态询问")
        val id = STATUS_CHECK_IN_NOTIFICATION_ID
        val openApp = PendingIntent.getActivity(
            context,
            id,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(EXTRA_OPEN_STATUS_CHECK_IN, true)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val snooze = PendingIntent.getBroadcast(
            context,
            id + 1,
            Intent(context, ReminderReceiver::class.java).apply {
                action = ACTION_STATUS_CHECK_IN_SNOOZE
                putExtra(EXTRA_NOTIFICATION_ID, id)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        manager.notify(id, NotificationCompat.Builder(context, CHANNEL_STATUS_CHECK_IN)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("现在状态怎么样？")
            .setContentText("用几秒记录精力和正在做的事；不回应也不会连续追问。")
            .setStyle(NotificationCompat.BigTextStyle().bigText("用几秒记录精力和正在做的事，帮助调整弹性任务。数据只保存在本机；不回应也不会连续追问。"))
            .setContentIntent(openApp)
            .addAction(0, "现在记录", openApp)
            .addAction(0, "稍后 ${settings.snoozeMinutes} 分钟", snooze)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build())
    }

    private fun showMealPromptNotification(context: Context, manager: NotificationManager, type: MealType, learned: Boolean) {
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        ensureChannel(manager, CHANNEL_MEAL, "饭点提醒")
        val id = MEAL_NOTIFICATION_BASE + type.ordinal
        val openApp = PendingIntent.getActivity(
            context,
            id,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(EXTRA_OPEN_MEAL_PROMPT, true)
                putExtra(EXTRA_MEAL_TYPE, type.label)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val snooze = PendingIntent.getBroadcast(
            context,
            id + 1,
            Intent(context, ReminderReceiver::class.java).apply {
                action = ACTION_MEAL_SNOOZE
                putExtra(EXTRA_NOTIFICATION_ID, id)
                putExtra(EXTRA_MEAL_TYPE, type.label)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val dismissed = PendingIntent.getBroadcast(
            context,
            id + 2,
            Intent(context, ReminderReceiver::class.java).apply {
                action = ACTION_MEAL_DISMISS
                putExtra(EXTRA_MEAL_TYPE, type.label)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = if (learned) "按你最近的记录，大概到${type.label}时间了。准备吃饭？" else "按你填写的大致时间，快到${type.label}了。准备吃饭？"
        manager.notify(id, NotificationCompat.Builder(context, CHANNEL_MEAL)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("准备${type.label}？")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp)
            .addAction(0, "已在吃", openApp)
            .addAction(0, "稍后 20 分钟", snooze)
            .setDeleteIntent(dismissed)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build())
    }

    private fun showWindDownNotification(context: Context, manager: NotificationManager) {
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        ensureChannel(manager, CHANNEL_WIND_DOWN, "睡前减速")
        val id = WIND_DOWN_NOTIFICATION_ID
        val openApp = PendingIntent.getActivity(
            context,
            id,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val profile = PrototypeStore(context).loadBaselineProfile()
        val sleepText = WindDownInsights.formatMinute(profile.sleepMinute.coerceAtLeast(0))
        val store2 = PrototypeStore(context)
        val lateNightCount = LifestyleInsights.lateNightActiveCount(store2.loadStatusCheckIns(90), store2.loadRecentActivitySessions())
        val text = if (lateNightCount >= 3) {
            "按你的习惯 ${sleepText} 睡觉。你最近 $lateNightCount 次在深夜（22 点后）仍活跃，今晚建议比平时更早收尾、放下手机。"
        } else {
            "按你的习惯 ${sleepText} 睡觉。现在收尾、洗漱、放下手机，明天从低强度任务开始。"
        }
        manager.notify(id, NotificationCompat.Builder(context, CHANNEL_WIND_DOWN)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("该开始睡前减速了")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build())
    }

    private fun showMealEndNotification(context: Context, manager: NotificationManager, type: MealType) {
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        ensureChannel(manager, CHANNEL_MEAL, "饭点提醒")
        val id = MEAL_NOTIFICATION_BASE + type.ordinal + 10
        val openApp = PendingIntent.getActivity(
            context,
            id,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(EXTRA_OPEN_MEAL_FINISH, true)
                putExtra(EXTRA_MEAL_TYPE, type.label)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stillEating = PendingIntent.getBroadcast(
            context,
            id + 1,
            Intent(context, ReminderReceiver::class.java).apply {
                action = ACTION_MEAL_STILL_EATING
                putExtra(EXTRA_NOTIFICATION_ID, id)
                putExtra(EXTRA_MEAL_TYPE, type.label)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        manager.notify(id, NotificationCompat.Builder(context, CHANNEL_MEAL)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("${type.label}吃完了吗？")
            .setContentText("结束并记录用餐时间，评价可留空；不回应不会影响任何学习。")
            .setStyle(NotificationCompat.BigTextStyle().bigText("结束并记录用餐时间；评价始终可选，不回应不会被视为没吃，也不会写入训练数据。"))
            .setContentIntent(openApp)
            .addAction(0, "吃完并记录", openApp)
            .addAction(0, "还在吃", stillEating)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build())
    }

    /** 创建高重要性（横幅弹出）渠道；silent=true 时为静音横幅（温和版到点提醒）。旧渠道就地删除，避免遗留。 */
    private fun ensureChannel(manager: NotificationManager, channelId: String, name: String, silent: Boolean = false) {
        manager.createNotificationChannel(
            NotificationChannel(channelId, name, NotificationManager.IMPORTANCE_HIGH).apply {
                if (silent) {
                    setSound(null, null)
                    enableVibration(false)
                }
            }
        )
        LEGACY_CHANNELS.forEach { if (manager.getNotificationChannel(it) != null) manager.deleteNotificationChannel(it) }
    }

    private fun isSameDayAsNow(timestamp: Long): Boolean {
        val now = Calendar.getInstance()
        val value = Calendar.getInstance().apply { timeInMillis = timestamp }
        return now.get(Calendar.ERA) == value.get(Calendar.ERA) &&
            now.get(Calendar.YEAR) == value.get(Calendar.YEAR) &&
            now.get(Calendar.DAY_OF_YEAR) == value.get(Calendar.DAY_OF_YEAR)
    }

    private fun taskActionIntent(context: Context, action: String, taskId: Long, notificationId: Int, actionOffset: Int): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            this.action = action
            putExtra(EXTRA_TASK_ID, taskId)
            putExtra(EXTRA_NOTIFICATION_ID, notificationId)
        }
        return PendingIntent.getBroadcast(context, notificationId + actionOffset, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun actionIntent(context: Context, action: String, activityName: String, nextStep: String, sessionId: Long, notificationId: Int, actionOffset: Int): PendingIntent {
        val requestCode = notificationId + actionOffset
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            this.action = action
            putExtra(EXTRA_ACTIVITY_NAME, activityName)
            putExtra(EXTRA_NEXT_STEP, nextStep)
            putExtra(EXTRA_SESSION_ID, sessionId)
            putExtra(EXTRA_NOTIFICATION_ID, notificationId)
        }
        return PendingIntent.getBroadcast(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun gameActionIntent(context: Context, action: String, sessionId: Long, title: String, notificationId: Int, actionOffset: Int): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            this.action = action
            putExtra(EXTRA_GAME_SESSION_ID, sessionId)
            putExtra(EXTRA_GAME_TITLE, title)
            putExtra(EXTRA_NOTIFICATION_ID, notificationId)
        }
        return PendingIntent.getBroadcast(context, notificationId + actionOffset, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** 空闲活动开始提醒：广播只触发提醒，不据此推断用户已经开始，更不自动写状态签到。 */
    private fun showGameStartNotification(context: Context, manager: NotificationManager, intent: Intent) {
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val sessionId = intent.getLongExtra(EXTRA_GAME_SESSION_ID, -1L)
        val session = PrototypeStore(context).loadGameSessions()
            .firstOrNull { it.id == sessionId && it.isOpen() } ?: return
        val expectedStartAt = intent.getLongExtra(EXTRA_GAME_PLANNED_AT, -1L)
        if (!ScheduledActivityPolicy.matchesCurrentPlan(expectedStartAt, session.plannedStartAt)) return
        ensureChannel(manager, CHANNEL_GAME, "活动开始与收尾提醒")
        val id = ((sessionId % Int.MAX_VALUE).toInt() + 400).coerceAtLeast(0)
        val openApp = PendingIntent.getActivity(context, id + 9, Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val copy = ScheduledActivityPolicy.startCopy(session)
        manager.notify(id, NotificationCompat.Builder(context, CHANNEL_GAME)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(copy.title)
            .setContentText(copy.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(copy.body))
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .build())
    }

    /** 到点检测：游戏/视频类在已授权且开启检测时检查前台是否还在玩；未授权或关闭时只提醒不检测（同其他活动）。到点提醒结束（可结束/延长 15 分钟）并 10 分钟后复查；检测到已不在玩则不再打扰并按时记录结束。 */
    private fun handleGameEndCheck(context: Context, manager: NotificationManager, intent: Intent, followUp: Boolean) {
        val store = PrototypeStore(context)
        val sessionId = intent.getLongExtra(EXTRA_GAME_SESSION_ID, -1L)
        val session = store.loadGameSessions().firstOrNull { it.id == sessionId && it.isOpen() } ?: return
        val expectedEndAt = intent.getLongExtra(EXTRA_GAME_PLANNED_AT, -1L)
        if (!ScheduledActivityPolicy.matchesCurrentPlan(expectedEndAt, session.plannedEndAt)) return
        val title = session.title
        val now = System.currentTimeMillis()
        val detectionOn = store.loadGameDetectionEnabled() && AppLibrary.hasUsageAccess(context)
        val targetCategory = when (ScheduledActivityPolicy.detection(session.category)) {
            ForegroundDetection.GAME -> AppCategory.GAME
            ForegroundDetection.VIDEO -> AppCategory.VIDEO
            null -> null
        }
        val foreground = if (detectionOn && targetCategory != null) AppLibrary.foregroundPackage(context) else null
        val stillPlaying = foreground != null &&
            (session.packageName == foreground || AppLibrary.categoryOf(context, foreground, store.loadAppCategories()) == targetCategory)
        // 无检测类别（学习/休息/运动/自定义）、检测不可用（未授权/关闭）或检测到仍在玩：提醒收尾；否则自动记录按时结束。
        if (targetCategory == null || !detectionOn || stillPlaying) {
            if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
            ensureChannel(manager, CHANNEL_GAME, "活动收尾提醒")
            val id = ((sessionId % Int.MAX_VALUE).toInt() + 500 + if (followUp) 1 else 0).coerceAtLeast(0)
            val openApp = PendingIntent.getActivity(context, id + 9, Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val text = when {
                stillPlaying && foreground != null -> if (followUp) "还在玩《${AppLibrary.appLabel(context, foreground)}》？计划时间已经过了，收个尾吧。" else "计划到点了，检测到你还在玩《${AppLibrary.appLabel(context, foreground)}》。"
                else -> "计划时间到了，收个尾吧（可结束或延长 15 分钟）。"
            }
            val notification = NotificationCompat.Builder(context, CHANNEL_GAME)
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle("$title 时间到了")
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(openApp)
                .addAction(0, "结束", gameActionIntent(context, ACTION_GAME_FINISH, sessionId, title, id, 1))
                .addAction(0, "延长 15 分钟", gameActionIntent(context, ACTION_GAME_EXTEND, sessionId, title, id, 2))
                .setAutoCancel(true)
            manager.notify(id, notification.build())
            // 只有确实检测到游戏/视频仍在前台时才复查；普通活动及无检测权限时只提醒一次。
            if (!followUp && targetCategory != null && detectionOn && stillPlaying) {
                ReminderScheduler.scheduleGameFollowUp(context, sessionId, title, session.plannedEndAt, now + 10 * 60_000L)
            }
        } else {
            recordGameActualEnd(context, sessionId, now)
        }
    }

    /** 记录游戏实际结束并清掉相关提醒。 */
    private fun recordGameActualEnd(context: Context, sessionId: Long, actualEndAt: Long) {
        val store = PrototypeStore(context)
        store.loadGameSessions().firstOrNull { it.id == sessionId && it.isOpen() }?.let { session ->
            val overrun = ((actualEndAt - session.plannedEndAt) / 60_000L).toInt().coerceAtLeast(0)
            store.updateGameSession(sessionId) { it.copy(actualEndAt = actualEndAt, endedOnTime = overrun == 0, overrunMinutes = overrun) }
            ReminderScheduler.cancelGameReminders(context, sessionId)
        }
    }

    companion object {
        const val ACTION_ACTIVITY_END = "com.sakata.focusflow.ACTIVITY_END"
        const val ACTION_ACTIVITY_PREVIEW = "com.sakata.focusflow.ACTIVITY_PREVIEW"
        const val ACTION_COMPLETE = "com.sakata.focusflow.COMPLETE_ACTIVITY"
        const val ACTION_SNOOZE = "com.sakata.focusflow.SNOOZE_ACTIVITY"
        const val ACTION_SKIP = "com.sakata.focusflow.SKIP_ACTIVITY"
        const val ACTION_TASK_ADVANCE = "com.sakata.focusflow.TASK_ADVANCE"
        const val ACTION_TASK_DUE = "com.sakata.focusflow.TASK_DUE"
        const val ACTION_TASK_TEST = "com.sakata.focusflow.TASK_TEST"
        const val ACTION_TASK_COMPLETE = "com.sakata.focusflow.TASK_COMPLETE"
        const val ACTION_TASK_SNOOZE = "com.sakata.focusflow.TASK_SNOOZE"
        const val ACTION_TASK_SKIP = "com.sakata.focusflow.TASK_SKIP"
        const val ACTION_TASK_MINIMUM = "com.sakata.focusflow.TASK_MINIMUM"
        const val ACTION_GAME_START = "com.sakata.focusflow.GAME_START"
        const val ACTION_GAME_END = "com.sakata.focusflow.GAME_END"
        const val ACTION_GAME_END_FOLLOWUP = "com.sakata.focusflow.GAME_END_FOLLOWUP"
        const val ACTION_GAME_FINISH = "com.sakata.focusflow.GAME_FINISH"
        const val ACTION_GAME_EXTEND = "com.sakata.focusflow.GAME_EXTEND"
        const val ACTION_STATUS_CHECK_IN = "com.sakata.focusflow.STATUS_CHECK_IN"
        const val ACTION_STATUS_CHECK_IN_SNOOZE = "com.sakata.focusflow.STATUS_CHECK_IN_SNOOZE"
        const val ACTION_WIND_DOWN = "com.sakata.focusflow.WIND_DOWN"
        const val ACTION_MEAL_REMINDER = "com.sakata.focusflow.MEAL_REMINDER"
        const val ACTION_MEAL_SNOOZE = "com.sakata.focusflow.MEAL_SNOOZE"
        const val ACTION_MEAL_DISMISS = "com.sakata.focusflow.MEAL_DISMISS"
        const val ACTION_MEAL_END_REMINDER = "com.sakata.focusflow.MEAL_END_REMINDER"
        const val ACTION_MEAL_STILL_EATING = "com.sakata.focusflow.MEAL_STILL_EATING"
        const val ACTION_DAILY_MEAL_REFRESH = "com.sakata.focusflow.DAILY_MEAL_REFRESH"
        const val EXTRA_ACTIVITY_NAME = "activity_name"
        const val EXTRA_NEXT_STEP = "next_step"
        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_TASK_ID = "task_id"
        const val EXTRA_TASK_TITLE = "task_title"
        const val EXTRA_TASK_START_AT = "task_start_at"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
        const val EXTRA_OPEN_STATUS_CHECK_IN = "open_status_check_in"
        const val EXTRA_OPEN_QUICK_CAPTURE = "open_quick_capture"
        const val EXTRA_OPEN_MEAL_PROMPT = "open_meal_prompt"
        const val EXTRA_OPEN_MEAL_FINISH = "open_meal_finish"
        const val EXTRA_MEAL_TYPE = "meal_type"
        const val EXTRA_MEAL_LEARNED = "meal_learned"
        const val EXTRA_GAME_SESSION_ID = "game_session_id"
        const val EXTRA_GAME_TITLE = "game_title"
        const val EXTRA_GAME_PLANNED_AT = "game_planned_at"
        private const val CHANNEL_ACTIVITY_PREVIEW = "focusflow_activity_preview_v2"
        private const val CHANNEL_ACTIVITY_END = "focusflow_activity_end_v3"
        private const val CHANNEL_ACTIVITY_END_GENTLE = "focusflow_activity_end_gentle_v3"
        const val CHANNEL_TASK = "focusflow_task_reminders"
        private const val CHANNEL_STATUS_CHECK_IN = "focusflow_status_check_in_v2"
        private const val CHANNEL_WIND_DOWN = "focusflow_wind_down_v2"
        const val CHANNEL_MEAL = "focusflow_meal_reminders_v2"
        private const val CHANNEL_GAME = "focusflow_game_v1"
        /** 3.9.8 之前创建的低重要性渠道；升级横幅后删除，避免设置页残留旧渠道。 */
        private val LEGACY_CHANNELS = listOf(
            "focusflow_activity_end_v2", "focusflow_activity_end_gentle_v2", "focusflow_activity_preview",
            "focusflow_status_check_in_v1", "focusflow_wind_down", "focusflow_meal_reminders"
        )
        private const val STATUS_CHECK_IN_NOTIFICATION_ID = 2_900_002
        private const val TASK_TEST_NOTIFICATION_ID = 2_900_003
        private const val WIND_DOWN_NOTIFICATION_ID = 2_900_004
        private const val MEAL_NOTIFICATION_BASE = 3_100_000
    }
}
