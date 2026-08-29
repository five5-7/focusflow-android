package com.sakata.focusflow

data class PendingTaskReminder(
    val itemId: Long,
    val title: String,
    val startsAt: Long,
    val triggerAt: Long
)

enum class AlarmDeliveryMode {
    EXACT,
    INEXACT
}

object TaskReminderPolicy {
    private val excludedKinds = setOf("收集箱", "暂停", "游戏", "活动")

    fun nextReminder(
        items: List<Item>,
        settings: ActivityReminderSettings,
        now: Long = System.currentTimeMillis()
    ): PendingTaskReminder? {
        if (!settings.scheduleRemindersEnabled) return null
        return items.asSequence()
            .filter { item ->
                !item.done &&
                    item.kind !in excludedKinds &&
                    item.scheduledAt?.let { it > now } == true
            }
            .map { item ->
                val startsAt = requireNotNull(item.scheduledAt)
                PendingTaskReminder(
                    itemId = item.id,
                    title = item.title.removePrefix("重新安排："),
                    startsAt = startsAt,
                    triggerAt = maxOf(
                        startsAt - settings.scheduleAdvanceMinutes.coerceIn(0, 60) * 60_000L,
                        now + 1_000L
                    )
                )
            }
            .minByOrNull { it.triggerAt }
    }

    fun deliveryMode(sdkInt: Int, canScheduleExactAlarms: Boolean): AlarmDeliveryMode =
        if (sdkInt < 31 || canScheduleExactAlarms) AlarmDeliveryMode.EXACT else AlarmDeliveryMode.INEXACT
}
