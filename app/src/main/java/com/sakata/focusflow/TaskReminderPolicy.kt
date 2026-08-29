package com.sakata.focusflow

data class PendingTaskReminder(
    val itemId: Long,
    val title: String,
    val startsAt: Long,
    val triggerAt: Long,
    val stage: TaskReminderStage
)

enum class TaskReminderStage {
    ADVANCE,
    DUE
}

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
    ): PendingTaskReminder? = pendingReminders(items, settings, now).firstOrNull()

    fun pendingReminders(
        items: List<Item>,
        settings: ActivityReminderSettings,
        now: Long = System.currentTimeMillis()
    ): List<PendingTaskReminder> {
        if (!settings.scheduleRemindersEnabled) return emptyList()
        return items.asSequence()
            .filter { item ->
                !item.done &&
                    item.kind !in excludedKinds &&
                    item.scheduledAt?.let { it > now } == true
            }
            .flatMap { item ->
                val startsAt = requireNotNull(item.scheduledAt)
                val title = item.title.removePrefix("重新安排：")
                val reminders = mutableListOf(
                    PendingTaskReminder(
                        itemId = item.id,
                        title = title,
                        startsAt = startsAt,
                        triggerAt = startsAt,
                        stage = TaskReminderStage.DUE
                    )
                )
                val advanceMinutes = settings.scheduleAdvanceMinutes.coerceIn(0, 60)
                if (advanceMinutes > 0) {
                    reminders += PendingTaskReminder(
                        itemId = item.id,
                        title = title,
                        startsAt = startsAt,
                        triggerAt = maxOf(startsAt - advanceMinutes * 60_000L, now + 1_000L),
                        stage = TaskReminderStage.ADVANCE
                    )
                }
                reminders.asSequence()
            }
            .sortedWith(compareBy<PendingTaskReminder> { it.triggerAt }.thenBy { it.stage })
            .toList()
    }

    fun deliveryMode(sdkInt: Int, canScheduleExactAlarms: Boolean): AlarmDeliveryMode =
        if (sdkInt < 31 || canScheduleExactAlarms) AlarmDeliveryMode.EXACT else AlarmDeliveryMode.INEXACT
}
