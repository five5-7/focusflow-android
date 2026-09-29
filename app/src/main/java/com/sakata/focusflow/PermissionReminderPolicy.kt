package com.sakata.focusflow

internal enum class MissingPermission(val label: String) {
    NOTIFICATIONS("通知权限"),
    EXACT_ALARMS("精确闹钟"),
    USAGE_ACCESS("使用情况访问")
}

/** 权限并不都是使用应用的前提；把提醒送达和体验增强分开呈现。 */
internal enum class PermissionPriority { REQUIRED, OPTIONAL }

internal val MissingPermission.priority: PermissionPriority
    get() = when (this) {
        MissingPermission.NOTIFICATIONS -> PermissionPriority.REQUIRED
        MissingPermission.EXACT_ALARMS, MissingPermission.USAGE_ACCESS -> PermissionPriority.OPTIONAL
    }

internal val MissingPermission.explanation: String
    get() = when (this) {
        MissingPermission.NOTIFICATIONS -> "用于显示日程和活动提醒；关闭后无法收到通知。"
        MissingPermission.EXACT_ALARMS -> "让到点提醒更准；不开启时系统仍可能延后送达。"
        MissingPermission.USAGE_ACCESS -> "仅在开启前台应用检测时需要，用于识别当前应用。"
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
