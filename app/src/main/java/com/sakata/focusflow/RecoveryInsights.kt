package com.sakata.focusflow

import java.util.Calendar
import kotlin.jvm.JvmName

enum class RecoveryReason { MISSED, REPEATEDLY_RESCHEDULED }

data class RecoveryCandidate(val item: Item, val reason: RecoveryReason)

data class WeeklyExecutionSummary(
    val plannedCount: Int,
    val completedCount: Int,
    val rescheduledCount: Int,
    val missedCount: Int,
    val frequentReschedulePeriod: String?
) {
    val completionPercent: Int?
        get() = plannedCount.takeIf { it > 0 }?.let { completedCount * 100 / it }
}

/** 只根据本地任务记录给出恢复入口；不自动移动或删除任务。 */
object RecoveryInsights {
    /** An all-day task has no end time to miss until its calendar day has passed. */
    fun missedWindow(item: Item, now: Long = System.currentTimeMillis()): Boolean {
        val scheduledAt = item.scheduledAt ?: return false
        if (item.done || item.kind != "任务") return false
        if (item.dayOnly) return TaskHistory.dayStartOf(scheduledAt) < TaskHistory.dayStartOf(now)
        return scheduledAt + item.durationMinutes.coerceAtLeast(1) * 60_000L < now
    }

    fun overdueLabel(item: Item, now: Long = System.currentTimeMillis()): String? {
        if (!missedWindow(item, now)) return null
        val scheduledAt = requireNotNull(item.scheduledAt)
        val overdueBy = now - (if (item.dayOnly) {
            java.util.Calendar.getInstance().apply {
                timeInMillis = scheduledAt
                set(java.util.Calendar.HOUR_OF_DAY, 0)
                set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
                add(java.util.Calendar.DAY_OF_YEAR, 1)
            }.timeInMillis
        } else scheduledAt + item.durationMinutes.coerceAtLeast(1) * 60_000L)
        val days = overdueBy / (24 * 60 * 60_000L)
        val hours = overdueBy / (60 * 60_000L)
        return when {
            days >= 1 -> "已逾期 ${days} 天"
            hours >= 1 -> "已逾期 ${hours} 小时"
            else -> "刚刚逾期"
        }
    }

    fun candidates(items: List<Item>, now: Long = System.currentTimeMillis()): List<RecoveryCandidate> =
        items.asSequence()
            .filter { !it.done && it.kind !in setOf("收集箱", "暂停") }
            .mapNotNull { item ->
                val missed = missedWindow(item, now)
                when {
                    missed -> RecoveryCandidate(item, RecoveryReason.MISSED)
                    item.rescheduleCount >= 2 -> RecoveryCandidate(item, RecoveryReason.REPEATEDLY_RESCHEDULED)
                    else -> null
                }
            }
            .sortedWith(compareBy<RecoveryCandidate> { it.reason != RecoveryReason.MISSED }
                .thenByDescending { it.item.rescheduleCount }
                .thenBy { it.item.scheduledAt ?: Long.MAX_VALUE })
            .toList()

    fun weeklySummary(items: List<Item>, now: Long = System.currentTimeMillis(), events: List<BaselineEvent> = emptyList()): WeeklyExecutionSummary {
        val start = WeekReview.weekStartOf(now)
        val end = weekEnd(start)
        val planned = items.filter { item ->
            sequenceOf(item.scheduledAt, item.recoverySourceScheduledAt).filterNotNull().any { it in start until end }
        }
        val completed = planned.count { it.done && it.completedAt?.let { time -> time in start until end } == true }
        val rescheduleTimes = if (events.isNotEmpty()) events
            .filter { it.type == BaselineEventType.TASK_RESCHEDULED && it.recordedAt in start until end }
            .map(BaselineEvent::recordedAt)
        else items.mapNotNull(Item::lastRescheduledAt).filter { it in start until end }
        val missed = planned.count { item ->
            !item.done && (item.scheduledAt ?: item.recoverySourceScheduledAt ?: now) + item.durationMinutes.coerceAtLeast(1) * 60_000L < now
        }
        val period = rescheduleTimes.map(::dayPeriod)
            .groupingBy { it }.eachCount()
            .maxByOrNull { it.value }
            ?.takeIf { it.value >= 2 }
            ?.key
        return WeeklyExecutionSummary(planned.size, completed, rescheduleTimes.size, missed, period)
    }

    /**
     * 事件优先版：6.5 起本周统计以发生过的事件为准（删除/放回不改写历史）。
     * [taskEvents] 为空时回退上面的 items 推导；归档的重复未处理本次仍计入周内未处理数。
     */
    @JvmName("weeklySummaryWithTaskEvents")
    fun weeklySummary(items: List<Item>, now: Long, taskEvents: List<TaskEvent>): WeeklyExecutionSummary {
        if (taskEvents.isEmpty()) return weeklySummary(items, now)
        val start = WeekReview.weekStartOf(now)
        val end = weekEnd(start)
        // 计划数 = 本周出现过的计划项（按 itemId 去重），与旧 items 版"本周计划集合"同义；
        // 完成数取仍有效的完成事件；立即撤回后不能继续算已完成，删除任务仍保留历史事件。
        val plannedIds = taskEvents.asSequence()
            .filter { it.type in setOf(TaskEventType.TASK_CREATED, TaskEventType.TASK_SCHEDULED, TaskEventType.TASK_RESCHEDULED) && it.scheduledAt in start until end }
            .map { it.itemId }.filter { it != 0L }.toSet()
            .minus(TaskHistory.excludedRepeatIds(taskEvents, start, end))
        val completedIds = TaskHistory.currentCompletions(taskEvents).asSequence()
            .filter { it.recordedAt in start until end }
            .map { it.itemId }.filter { it != 0L }.toSet()
        val rescheduleTimes = taskEvents
            .filter { it.type == TaskEventType.TASK_RESCHEDULED && it.recordedAt in start until end }
            .map(TaskEvent::recordedAt)
        val currentlyMissed = items.asSequence().filter { item ->
            !item.done && item.kind == "任务" &&
                sequenceOf(item.scheduledAt, item.recoverySourceScheduledAt).filterNotNull().any { it in start until end } &&
                (item.scheduledAt ?: item.recoverySourceScheduledAt ?: now) + item.durationMinutes.coerceAtLeast(1) * 60_000L < now
        }.map { it.id }.toSet()
        val archivedRepeatMisses = taskEvents.asSequence()
            .filter { it.type == TaskEventType.REPEAT_MISSED && it.scheduledAt in start until end }
            .map { it.itemId }.toSet()
        val missed = (currentlyMissed + archivedRepeatMisses).intersect(plannedIds).size
        val period = rescheduleTimes.map(::dayPeriod)
            .groupingBy { it }.eachCount()
            .maxByOrNull { it.value }
            ?.takeIf { it.value >= 2 }
            ?.key
        return WeeklyExecutionSummary(plannedIds.size, (plannedIds intersect completedIds).size, rescheduleTimes.size, missed, period)
    }

    private fun dayPeriod(time: Long): String = when (Calendar.getInstance().apply { timeInMillis = time }.get(Calendar.HOUR_OF_DAY)) {
        in 5..11 -> "上午"
        in 12..17 -> "下午"
        else -> "晚上"
    }

    private fun weekEnd(start: Long): Long = Calendar.getInstance().apply {
        timeInMillis = start; add(Calendar.DAY_OF_YEAR, 7)
    }.timeInMillis
}
