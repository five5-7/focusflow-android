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
    fun health(context: Context): NotificationHealth {
        val manager = context.getSystemService(NotificationManager::class.java)
        fun bannerEnabled(channelId: String): Boolean =
            manager.getNotificationChannel(channelId)?.importance
                ?.let { it >= NotificationManager.IMPORTANCE_HIGH } == true

        return NotificationHealthPolicy.evaluate(
            appNotificationsAllowed = manager.areNotificationsEnabled(),
            taskBannerAllowed = bannerEnabled(ReminderReceiver.CHANNEL_TASK),
            mealBannerAllowed = bannerEnabled(ReminderReceiver.CHANNEL_MEAL)
        )
    }
}
