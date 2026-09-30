package com.sakata.focusflow

import java.util.Calendar

data class PendingTaskReminder(
    val itemId: Long,
    val title: String,
    val startsAt: Long,
    val triggerAt: Long,
    val stage: TaskReminderStage
)

enum class TaskReminderStage {
    ADVANCE,
    DUE,
    MISSED,
    DEADLINE
}

enum class AlarmDeliveryMode {
    ALARM_CLOCK,
    EXACT,
    INEXACT
}

data class ReminderTestProbe(
    val expectedAt: Long,
    val deliveredAt: Long?
)

enum class ReminderTestResult {
    NONE,
    PENDING,
    ON_TIME,
    DELAYED,
    OVERDUE
}

object TaskReminderPolicy {
    private val excludedKinds = setOf("收集箱", "暂停", "游戏", "活动", "回收站", "重复历史", "重复模板")

    /** Due dates are stored as local midnight; remind at 18:00 on that date. */
    fun deadlineReminderAt(dueAt: Long): Long = Calendar.getInstance().apply {
        timeInMillis = dueAt
        set(Calendar.HOUR_OF_DAY, 18)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

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
                !item.done && !item.dayOnly &&
                    item.kind !in excludedKinds &&
                    item.scheduledAt?.let { start ->
                        val end = start + item.durationMinutes.coerceAtLeast(1) * 60_000L
                        end > now || (item.kind == "任务" && item.dueAt?.let { due ->
                            deadlineReminderAt(due) > maxOf(now, end + 60 * 60_000L)
                        } == true)
                    } == true
            }
            .flatMap { item ->
                val startsAt = requireNotNull(item.scheduledAt)
                val title = item.title.removePrefix("重新安排：")
                val reminders = mutableListOf<PendingTaskReminder>()
                if (startsAt > now) reminders += PendingTaskReminder(
                        itemId = item.id,
                        title = title,
                        startsAt = startsAt,
                        triggerAt = startsAt,
                        stage = TaskReminderStage.DUE
                    )
                val advanceMinutes = settings.scheduleAdvanceMinutes.coerceIn(0, 60)
                if (startsAt > now && advanceMinutes > 0) {
                    reminders += PendingTaskReminder(
                        itemId = item.id,
                        title = title,
                        startsAt = startsAt,
                        triggerAt = maxOf(startsAt - advanceMinutes * 60_000L, now + 1_000L),
                        stage = TaskReminderStage.ADVANCE
                    )
                }
                val slotEnd = startsAt + item.durationMinutes.coerceAtLeast(1) * 60_000L
                if (item.kind == "任务" && slotEnd > now) reminders += PendingTaskReminder(item.id, title, startsAt,
                    slotEnd, TaskReminderStage.MISSED)
                val due = item.dueAt
                if (item.kind == "任务" && due != null &&
                    deadlineReminderAt(due) > maxOf(now, slotEnd + 60 * 60_000L))
                    reminders += PendingTaskReminder(item.id, title, startsAt,
                        deadlineReminderAt(due), TaskReminderStage.DEADLINE)
                reminders.asSequence()
            }
            .sortedWith(compareBy<PendingTaskReminder> { it.triggerAt }.thenBy { it.stage })
            .toList()
    }

    fun deliveryMode(sdkInt: Int, canScheduleExactAlarms: Boolean): AlarmDeliveryMode =
        if (sdkInt < 31 || canScheduleExactAlarms) AlarmDeliveryMode.EXACT else AlarmDeliveryMode.INEXACT

    fun testResult(probe: ReminderTestProbe?, now: Long = System.currentTimeMillis()): ReminderTestResult {
        if (probe == null || probe.expectedAt <= 0L) return ReminderTestResult.NONE
        val deliveredAt = probe.deliveredAt
        if (deliveredAt != null) {
            return if (deliveredAt <= probe.expectedAt + TEST_ON_TIME_TOLERANCE_MS) {
                ReminderTestResult.ON_TIME
            } else {
                ReminderTestResult.DELAYED
            }
        }
        return if (now <= probe.expectedAt + TEST_ON_TIME_TOLERANCE_MS) {
            ReminderTestResult.PENDING
        } else {
            ReminderTestResult.OVERDUE
        }
    }

    private const val TEST_ON_TIME_TOLERANCE_MS = 30_000L
}

/** A delayed or duplicate broadcast must still belong to this exact unfinished time slot. */
object TaskMissedReminderPolicy {
    fun matches(item: Item?, expectedStartsAt: Long, now: Long = System.currentTimeMillis()): Boolean {
        if (item == null || item.kind != "任务" || item.done || item.dayOnly ||
            item.scheduledAt != expectedStartsAt || expectedStartsAt <= 0L) return false
        val endAt = expectedStartsAt + item.durationMinutes.coerceAtLeast(1) * 60_000L
        return now >= endAt && now - endAt <= 2 * 60 * 60_000L
    }
}

/** A deadline notification is only for the same still open task and original explicit due time. */
object TaskDeadlineReminderPolicy {
    fun matches(item: Item?, expectedStartsAt: Long, expectedDueAt: Long,
                now: Long = System.currentTimeMillis()): Boolean {
        if (item == null || item.done || item.kind != "任务" || item.dayOnly ||
            item.scheduledAt != expectedStartsAt || item.dueAt != expectedDueAt) return false
        val end = expectedStartsAt + item.durationMinutes.coerceAtLeast(1) * 60_000L
        val trigger = TaskReminderPolicy.deadlineReminderAt(expectedDueAt)
        return trigger > end + 60 * 60_000L && now >= trigger && now - trigger <= 2 * 60 * 60_000L
    }
}

/** 通知按钮也必须属于任务当前这次安排，不能让旧通知修改改期后的任务。 */
object TaskReminderActionFreshness {
    fun matches(item: Item?, expectedStartsAt: Long): Boolean =
        item != null && !item.done && !item.dayOnly &&
            item.kind !in setOf("收集箱", "暂停", "游戏", "活动", "回收站", "重复历史", "重复模板") &&
            item.scheduledAt != null && (expectedStartsAt <= 0L || item.scheduledAt == expectedStartsAt)
}
