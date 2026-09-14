package com.sakata.focusflow

internal enum class MissingPermission(val label: String) {
    NOTIFICATIONS("通知权限"),
    EXACT_ALARMS("精确闹钟"),
    USAGE_ACCESS("使用情况访问")
}

/** 设置主页提醒的纯策略；只有已开启功能真正依赖的权限才会提示。 */
internal object PermissionReminderPolicy {
    fun missing(
        notificationsGranted: Boolean,
        exactAlarmsGranted: Boolean,
        usageAccessRequired: Boolean,
        usageAccessGranted: Boolean
    ): List<MissingPermission> = buildList {
        if (!notificationsGranted) add(MissingPermission.NOTIFICATIONS)
        if (!exactAlarmsGranted) add(MissingPermission.EXACT_ALARMS)
        if (usageAccessRequired && !usageAccessGranted) add(MissingPermission.USAGE_ACCESS)
    }

    fun summary(missing: List<MissingPermission>): String =
        "待设置：${missing.joinToString("、") { it.label }}"
}
