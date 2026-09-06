package com.sakata.focusflow

internal data class NotificationHealth(
    val appNotificationsAllowed: Boolean,
    val taskBannerAllowed: Boolean,
    val mealBannerAllowed: Boolean
) {
    fun allReadableSettingsReady(mealReminderRequired: Boolean): Boolean =
        appNotificationsAllowed && taskBannerAllowed && (!mealReminderRequired || mealBannerAllowed)
}

internal object NotificationHealthPolicy {
    fun evaluate(
        appNotificationsAllowed: Boolean,
        taskBannerAllowed: Boolean,
        mealBannerAllowed: Boolean
    ) = NotificationHealth(appNotificationsAllowed, taskBannerAllowed, mealBannerAllowed)

    fun startupMessage(health: NotificationHealth, mealReminderRequired: Boolean): String? = when {
        !health.appNotificationsAllowed -> if (mealReminderRequired) "FocusFlow 通知未开启，日程和饭点提醒不会出现。" else "FocusFlow 通知未开启，日程提醒不会出现。"
        !health.taskBannerAllowed && mealReminderRequired && !health.mealBannerAllowed -> "日程和饭点横幅未开启，请按设置页的文字路径检查。"
        !health.taskBannerAllowed -> "日程横幅未开启，请按设置页的文字路径检查。"
        mealReminderRequired && !health.mealBannerAllowed -> "饭点横幅未开启，请按设置页的文字路径检查。"
        else -> null
    }
}
