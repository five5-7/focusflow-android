package com.sakata.focusflow

import java.util.Calendar

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
    fun candidates(items: List<Item>, now: Long = System.currentTimeMillis()): List<RecoveryCandidate> =
        items.asSequence()
            .filter { !it.done && it.kind !in setOf("收集箱", "暂停") }
            .mapNotNull { item ->
                val missed = item.scheduledAt?.let { it + item.durationMinutes.coerceAtLeast(1) * 60_000L < now } == true
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
        val planned = items.filter { item ->
            sequenceOf(item.scheduledAt, item.recoverySourceScheduledAt).filterNotNull().any { it in start until start + WEEK_MILLIS }
        }
        val completed = planned.count { it.done && it.completedAt?.let { time -> time in start until start + WEEK_MILLIS } == true }
        val rescheduleTimes = if (events.isNotEmpty()) events
            .filter { it.type == BaselineEventType.TASK_RESCHEDULED && it.recordedAt in start until start + WEEK_MILLIS }
            .map(BaselineEvent::recordedAt)
        else items.mapNotNull(Item::lastRescheduledAt).filter { it in start until start + WEEK_MILLIS }
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

    private fun dayPeriod(time: Long): String = when (Calendar.getInstance().apply { timeInMillis = time }.get(Calendar.HOUR_OF_DAY)) {
        in 5..11 -> "上午"
        in 12..17 -> "下午"
        else -> "晚上"
    }

    private const val WEEK_MILLIS = 7L * 24 * 60 * 60 * 1000
}
