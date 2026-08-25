package com.sakata.focusflow

internal data class NotificationHealth(
    val appNotificationsAllowed: Boolean,
    val taskBannerAllowed: Boolean,
    val mealBannerAllowed: Boolean
) {
    val allReadableSettingsReady: Boolean
        get() = appNotificationsAllowed && taskBannerAllowed && mealBannerAllowed
}

internal object NotificationHealthPolicy {
    fun evaluate(
        appNotificationsAllowed: Boolean,
        taskBannerAllowed: Boolean,
        mealBannerAllowed: Boolean
    ) = NotificationHealth(appNotificationsAllowed, taskBannerAllowed, mealBannerAllowed)

    fun startupMessage(health: NotificationHealth): String? = when {
        !health.appNotificationsAllowed -> "FocusFlow 通知未开启，日程和饭点提醒不会出现。"
        !health.taskBannerAllowed && !health.mealBannerAllowed -> "日程和饭点横幅未开启，请按设置页的文字路径检查。"
        !health.taskBannerAllowed -> "日程横幅未开启，请按设置页的文字路径检查。"
        !health.mealBannerAllowed -> "饭点横幅未开启，请按设置页的文字路径检查。"
        else -> null
    }
}
