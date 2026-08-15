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
            ACTION_TASK_DUE -> {
                showTaskNotification(context, manager, intent.getStringExtra(EXTRA_TASK_TITLE) ?: "已改期任务", intent.getLongExtra(EXTRA_TASK_ID, -1L))
                return
            }
            ACTION_TASK_COMPLETE -> {
                if (notificationId >= 0) manager.cancel(notificationId)
                val taskId = intent.getLongExtra(EXTRA_TASK_ID, -1L)
                if (taskId >= 0) store.findItem(taskId)?.let { task ->
                    store.updateItem(taskId) { it.copy(done = true, completionLevel = "完整完成", completedAt = System.currentTimeMillis()) }
                    task.goalId?.let { store.markGoalCompleted(it) }
                }
                return
            }
            ACTION_TASK_MINIMUM -> {
                if (notificationId >= 0) manager.cancel(notificationId)
                val taskId = intent.getLongExtra(EXTRA_TASK_ID, -1L)
                if (taskId >= 0) store.findItem(taskId)?.let { task ->
                    store.updateItem(taskId) { it.copy(done = true, completionLevel = "最低版本", completedAt = System.currentTimeMillis()) }
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
                    ReminderScheduler.scheduleTaskReminder(context, delayed)
                }
                return
            }
            ACTION_TASK_SKIP -> {
                if (notificationId >= 0) manager.cancel(notificationId)
                val taskId = intent.getLongExtra(EXTRA_TASK_ID, -1L)
                if (taskId >= 0) store.updateItem(taskId) { item -> item.copy(title = if (item.title.startsWith("重新安排：")) item.title else "重新安排：${item.title}", kind = "收集箱", detail = "这次没有做；可以改期、缩短、暂停或放弃", scheduledAt = null) }
                return
            }
            else -> return
        }
        if (!store.loadActivityReminderSettings().notificationsEnabled) return
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val stronger = store.loadActivityReminderSettings().strongerEndReminder
        val endChannel = if (stronger) CHANNEL_ACTIVITY_END else CHANNEL_ACTIVITY_END_GENTLE
        manager.createNotificationChannel(NotificationChannel(endChannel, "活动结束提醒", if (stronger) NotificationManager.IMPORTANCE_HIGH else NotificationManager.IMPORTANCE_DEFAULT))
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

    private fun showActivityPreview(context: Context, manager: NotificationManager, activityName: String, nextStep: String, sessionId: Long) {
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ACTIVITY_PREVIEW, "活动结束预告", NotificationManager.IMPORTANCE_DEFAULT))
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

    private fun showTaskNotification(context: Context, manager: NotificationManager, title: String, taskId: Long) {
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        manager.createNotificationChannel(NotificationChannel(CHANNEL_TASK, "FocusFlow 任务提醒", NotificationManager.IMPORTANCE_HIGH))
        val openApp = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val id = (System.currentTimeMillis() % Int.MAX_VALUE).toInt()
        val task = PrototypeStore(context).findItem(taskId)
        val notification = NotificationCompat.Builder(context, CHANNEL_TASK)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("现在适合处理：$title")
            .setContentText("这是之前改期的项目。打开 FocusFlow 可以完成、再次调整或暂停。")
            .setContentIntent(openApp)
            .addAction(0, "完整完成", taskActionIntent(context, ACTION_TASK_COMPLETE, taskId, id, 11))
            .addAction(0, "稍后 1 小时", taskActionIntent(context, ACTION_TASK_SNOOZE, taskId, id, 12))
            .setAutoCancel(true)
        if (task?.goalId != null) notification.addAction(0, "最低版本", taskActionIntent(context, ACTION_TASK_MINIMUM, taskId, id, 13))
        manager.notify(id, notification.build())
    }

    private fun showStatusCheckInNotification(
        context: Context,
        manager: NotificationManager,
        settings: StatusCheckInSettings
    ) {
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        manager.createNotificationChannel(NotificationChannel(CHANNEL_STATUS_CHECK_IN, "低打扰状态询问", NotificationManager.IMPORTANCE_DEFAULT))
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

    companion object {
        const val ACTION_ACTIVITY_END = "com.sakata.focusflow.ACTIVITY_END"
        const val ACTION_ACTIVITY_PREVIEW = "com.sakata.focusflow.ACTIVITY_PREVIEW"
        const val ACTION_COMPLETE = "com.sakata.focusflow.COMPLETE_ACTIVITY"
        const val ACTION_SNOOZE = "com.sakata.focusflow.SNOOZE_ACTIVITY"
        const val ACTION_SKIP = "com.sakata.focusflow.SKIP_ACTIVITY"
        const val ACTION_TASK_DUE = "com.sakata.focusflow.TASK_DUE"
        const val ACTION_TASK_COMPLETE = "com.sakata.focusflow.TASK_COMPLETE"
        const val ACTION_TASK_SNOOZE = "com.sakata.focusflow.TASK_SNOOZE"
        const val ACTION_TASK_SKIP = "com.sakata.focusflow.TASK_SKIP"
        const val ACTION_TASK_MINIMUM = "com.sakata.focusflow.TASK_MINIMUM"
        const val ACTION_STATUS_CHECK_IN = "com.sakata.focusflow.STATUS_CHECK_IN"
        const val ACTION_STATUS_CHECK_IN_SNOOZE = "com.sakata.focusflow.STATUS_CHECK_IN_SNOOZE"
        const val EXTRA_ACTIVITY_NAME = "activity_name"
        const val EXTRA_NEXT_STEP = "next_step"
        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_TASK_ID = "task_id"
        const val EXTRA_TASK_TITLE = "task_title"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
        const val EXTRA_OPEN_STATUS_CHECK_IN = "open_status_check_in"
        private const val CHANNEL_ACTIVITY_PREVIEW = "focusflow_activity_preview"
        private const val CHANNEL_ACTIVITY_END = "focusflow_activity_end_v2"
        private const val CHANNEL_ACTIVITY_END_GENTLE = "focusflow_activity_end_gentle_v2"
        private const val CHANNEL_TASK = "focusflow_task_reminders"
        private const val CHANNEL_STATUS_CHECK_IN = "focusflow_status_check_in_v1"
        private const val STATUS_CHECK_IN_NOTIFICATION_ID = 2_900_002
    }
}
