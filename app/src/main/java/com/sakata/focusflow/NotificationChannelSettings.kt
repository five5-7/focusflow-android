package com.sakata.focusflow

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

/** Creates user-facing channels before their first notification. */
internal object NotificationChannelSettings {
    private val managedChannels = listOf(
        ReminderReceiver.CHANNEL_TASK to "FocusFlow 任务提醒",
        ReminderReceiver.CHANNEL_MEAL to "饭点提醒"
    )

    fun ensureManagedChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        managedChannels.forEach { (id, name) ->
            manager.createNotificationChannel(
                NotificationChannel(id, name, NotificationManager.IMPORTANCE_HIGH)
            )
        }
    }
}
