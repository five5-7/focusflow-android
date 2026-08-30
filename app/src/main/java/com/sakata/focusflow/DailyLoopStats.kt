package com.sakata.focusflow

import java.util.Calendar
import kotlin.jvm.JvmName

data class DailyLoopSummary(
    val plannedCount: Int,
    val completedPlannedCount: Int,
    val completedCount: Int,
    val rescheduledCount: Int,
    val inboxCount: Int
) {
    val completionPercent: Int?
        get() = plannedCount.takeIf { it > 0 }?.let { completedPlannedCount * 100 / it }
}

object DailyLoopStats {
    fun summarize(items: List<Item>, now: Long = System.currentTimeMillis(), events: List<BaselineEvent> = emptyList()): DailyLoopSummary {
        val planned = items.filter { item ->
            sequenceOf(item.scheduledAt, item.recoverySourceScheduledAt).filterNotNull().any { sameDay(it, now) }
        }
        return DailyLoopSummary(
            plannedCount = planned.size,
            completedPlannedCount = planned.count { it.done && it.completedAt?.let { time -> sameDay(time, now) } == true },
            completedCount = items.count { it.done && it.completedAt?.let { time -> sameDay(time, now) } == true },
            rescheduledCount = if (events.isNotEmpty()) events.count { it.type == BaselineEventType.TASK_RESCHEDULED && sameDay(it.recordedAt, now) }
                else items.count { it.lastRescheduledAt?.let { time -> sameDay(time, now) } == true },
            inboxCount = items.count { !it.done && it.kind == "收集箱" }
        )
    }

    /**
     * 事件优先版：6.5 起今日统计以发生过的事件为准（删除/放回不改写历史）。
     * [taskEvents] 为空时回退上面的 items 推导（与新安装、迁移未执行时行为一致）。
     */
    @JvmName("summarizeWithTaskEvents")
    fun summarize(items: List<Item>, now: Long, taskEvents: List<TaskEvent>): DailyLoopSummary {
        if (taskEvents.isEmpty()) return summarize(items, now)
        val today = TaskHistory.daySummary(taskEvents, TaskHistory.dayStartOf(now))
        return DailyLoopSummary(
            plannedCount = today.scheduledCount,
            completedPlannedCount = today.completedPlannedCount,
            completedCount = today.completedCount,
            rescheduledCount = today.rescheduledCount,
            inboxCount = items.count { !it.done && it.kind == "收集箱" }
        )
    }

    private fun sameDay(first: Long, second: Long): Boolean {
        val left = Calendar.getInstance().apply { timeInMillis = first }
        val right = Calendar.getInstance().apply { timeInMillis = second }
        return left.get(Calendar.ERA) == right.get(Calendar.ERA) &&
            left.get(Calendar.YEAR) == right.get(Calendar.YEAR) &&
            left.get(Calendar.DAY_OF_YEAR) == right.get(Calendar.DAY_OF_YEAR)
    }
}
