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

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)
        val sessionId = intent.getLongExtra(EXTRA_SESSION_ID, -1L)
        val activityName = intent.getStringExtra(EXTRA_ACTIVITY_NAME) ?: "当前活动"
        val store = PrototypeStore(context)
        when (intent.action) {
            ACTION_COMPLETE -> {
                if (notificationId >= 0) manager.cancel(notificationId)
                if (sessionId >= 0) store.updateSession(sessionId, "completed")
                return
            }
            ACTION_SKIP -> {
                if (notificationId >= 0) manager.cancel(notificationId)
                if (sessionId >= 0) store.updateSession(sessionId, "skipped")
                store.addReplanItem(activityName)
                return
            }
            ACTION_SNOOZE -> {
                if (notificationId >= 0) manager.cancel(notificationId)
                val delayed = ActivitySession(id = sessionId.takeIf { it >= 0 } ?: System.currentTimeMillis(), name = activityName, endsAt = System.currentTimeMillis() + 10 * 60_000L)
                store.saveSession(delayed)
                ReminderScheduler.scheduleActivityEnd(context, delayed)
                return
            }
            ACTION_ACTIVITY_END -> Unit
            ACTION_TASK_DUE -> {
                showTaskNotification(context, manager, intent.getStringExtra(EXTRA_TASK_TITLE) ?: "已改期任务", intent.getLongExtra(EXTRA_TASK_ID, -1L))
                return
            }
            ACTION_TASK_COMPLETE -> {
                if (notificationId >= 0) manager.cancel(notificationId)
                val taskId = intent.getLongExtra(EXTRA_TASK_ID, -1L)
                if (taskId >= 0) store.findItem(taskId)?.let { task ->
                    store.updateItem(taskId) { it.copy(done = true, completionLevel = "完整完成") }
                    task.goalId?.let { store.markGoalCompleted(it) }
                }
                return
            }
            ACTION_TASK_MINIMUM -> {
                if (notificationId >= 0) manager.cancel(notificationId)
                val taskId = intent.getLongExtra(EXTRA_TASK_ID, -1L)
                if (taskId >= 0) store.findItem(taskId)?.let { task ->
                    store.updateItem(taskId) { it.copy(done = true, completionLevel = "最低版本") }
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
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "FocusFlow 提醒", NotificationManager.IMPORTANCE_HIGH))
        val id = (System.currentTimeMillis() % Int.MAX_VALUE).toInt()
        val text = "现在结束，开始下一件事；也可以明确选择稍后再处理。"
       
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("$activityName 时间到了")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .addAction(0, "完成", actionIntent(context, ACTION_COMPLETE, activityName, sessionId, id, 1))
            .addAction(0, "稍后 10 分钟", actionIntent(context, ACTION_SNOOZE, activityName, sessionId, id, 2))
            .addAction(0, "跳过本次", actionIntent(context, ACTION_SKIP, activityName, sessionId, id, 3))
            .setAutoCancel(true)
            .build()
        manager.notify(id, notification)
    }

    private fun showTaskNotification(context: Context, manager: NotificationManager, title: String, taskId: Long) {
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "FocusFlow 提醒", NotificationManager.IMPORTANCE_HIGH))
        val openApp = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val id = (System.currentTimeMillis() % Int.MAX_VALUE).toInt()
        val task = PrototypeStore(context).findItem(taskId)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
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

    private fun taskActionIntent(context: Context, action: String, taskId: Long, notificationId: Int, actionOffset: Int): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            this.action = action
            putExtra(EXTRA_TASK_ID, taskId)
            putExtra(EXTRA_NOTIFICATION_ID, notificationId)
        }
        return PendingIntent.getBroadcast(context, notificationId + actionOffset, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun actionIntent(context: Context, action: String, activityName: String, sessionId: Long, notificationId: Int, actionOffset: Int): PendingIntent {
        val requestCode = notificationId + actionOffset
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            this.action = action
            putExtra(EXTRA_ACTIVITY_NAME, activityName)
            putExtra(EXTRA_SESSION_ID, sessionId)
            putExtra(EXTRA_NOTIFICATION_ID, notificationId)
        }
        return PendingIntent.getBroadcast(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    companion object {
        const val ACTION_ACTIVITY_END = "com.sakata.focusflow.ACTIVITY_END"
        const val ACTION_COMPLETE = "com.sakata.focusflow.COMPLETE_ACTIVITY"
        const val ACTION_SNOOZE = "com.sakata.focusflow.SNOOZE_ACTIVITY"
        const val ACTION_SKIP = "com.sakata.focusflow.SKIP_ACTIVITY"
        const val ACTION_TASK_DUE = "com.sakata.focusflow.TASK_DUE"
        const val ACTION_TASK_COMPLETE = "com.sakata.focusflow.TASK_COMPLETE"
        const val ACTION_TASK_SNOOZE = "com.sakata.focusflow.TASK_SNOOZE"
        const val ACTION_TASK_SKIP = "com.sakata.focusflow.TASK_SKIP"
        const val ACTION_TASK_MINIMUM = "com.sakata.focusflow.TASK_MINIMUM"
        const val EXTRA_ACTIVITY_NAME = "activity_name"
        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_TASK_ID = "task_id"
        const val EXTRA_TASK_TITLE = "task_title"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
        private const val CHANNEL_ID = "focusflow_reminders"
    }
}
