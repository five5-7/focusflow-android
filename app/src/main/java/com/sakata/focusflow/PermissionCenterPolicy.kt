package com.sakata.focusflow

internal enum class PermissionCenterGroup { REQUIRED_FOR_REMINDERS, OPTIONAL }

internal enum class PermissionCenterStatus { READY, ACTION_NEEDED, MANUAL_CHECK, NOT_IN_USE }

internal enum class PermissionCenterItem(
    val label: String,
    val explanation: String,
    val group: PermissionCenterGroup
) {
    NOTIFICATIONS(
        "允许通知",
        "关闭后，日程、活动和饭点提醒都无法显示。",
        PermissionCenterGroup.REQUIRED_FOR_REMINDERS
    ),
    TASK_CHANNEL(
        "任务提醒渠道",
        "需要保持开启并使用提醒级别，系统才允许弹出任务通知。",
        PermissionCenterGroup.REQUIRED_FOR_REMINDERS
    ),
    BANNER(
        "横幅／悬浮通知",
        "ColorOS 的独立开关无法由应用读取，需要在通知管理中手动确认。",
        PermissionCenterGroup.OPTIONAL
    ),
    EXACT_ALARM(
        "精确闹钟",
        "让到点提醒更准；不可用时会回退为普通后台提醒。",
        PermissionCenterGroup.OPTIONAL
    ),
    BATTERY_UNRESTRICTED(
        "允许后台运行",
        "减少省电策略造成的延迟；仍需用一分钟测试确认实际效果。",
        PermissionCenterGroup.OPTIONAL
    ),
    AUTOSTART(
        "允许自启动",
        "ColorOS 不公开此状态，需要手动确认；它会影响重启后的提醒恢复。",
        PermissionCenterGroup.OPTIONAL
    ),
    USAGE_ACCESS(
        "使用情况访问",
        "只用于已开启的前台应用检测，其他功能不需要。",
        PermissionCenterGroup.OPTIONAL
    )
}

internal data class PermissionCenterEntry(
    val item: PermissionCenterItem,
    val status: PermissionCenterStatus
)

internal object PermissionCenterPolicy {
    fun entries(
        notificationsAllowed: Boolean,
        taskChannelReady: Boolean,
        exactAlarmAllowed: Boolean,
        batteryUnrestricted: Boolean,
        appDetectionEnabled: Boolean,
        usageAccessAllowed: Boolean
    ): List<PermissionCenterEntry> = listOf(
        PermissionCenterEntry(
            PermissionCenterItem.NOTIFICATIONS,
            if (notificationsAllowed) PermissionCenterStatus.READY else PermissionCenterStatus.ACTION_NEEDED
        ),
        PermissionCenterEntry(
            PermissionCenterItem.TASK_CHANNEL,
            if (taskChannelReady) PermissionCenterStatus.READY else PermissionCenterStatus.ACTION_NEEDED
        ),
        PermissionCenterEntry(PermissionCenterItem.BANNER, PermissionCenterStatus.MANUAL_CHECK),
        PermissionCenterEntry(
            PermissionCenterItem.EXACT_ALARM,
            if (exactAlarmAllowed) PermissionCenterStatus.READY else PermissionCenterStatus.ACTION_NEEDED
        ),
        PermissionCenterEntry(
            PermissionCenterItem.BATTERY_UNRESTRICTED,
            if (batteryUnrestricted) PermissionCenterStatus.READY else PermissionCenterStatus.ACTION_NEEDED
        ),
        PermissionCenterEntry(PermissionCenterItem.AUTOSTART, PermissionCenterStatus.MANUAL_CHECK),
        PermissionCenterEntry(
            PermissionCenterItem.USAGE_ACCESS,
            when {
                !appDetectionEnabled -> PermissionCenterStatus.NOT_IN_USE
                usageAccessAllowed -> PermissionCenterStatus.READY
                else -> PermissionCenterStatus.ACTION_NEEDED
            }
        )
    )

    fun summary(entries: List<PermissionCenterEntry>): String {
        val actionCount = entries.count { it.status == PermissionCenterStatus.ACTION_NEEDED }
        val manualCount = entries.count { it.status == PermissionCenterStatus.MANUAL_CHECK }
        return when {
            actionCount > 0 -> "$actionCount 项待设置 · $manualCount 项需手动确认"
            manualCount > 0 -> "可读取项已设置 · $manualCount 项需手动确认"
            else -> "权限已检查"
        }
    }
}
