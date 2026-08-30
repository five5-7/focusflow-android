package com.sakata.focusflow

import java.util.Calendar

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
    fun summarize(items: List<Item>, now: Long = System.currentTimeMillis()): DailyLoopSummary {
        val planned = items.filter { item ->
            item.kind !in setOf("收集箱", "暂停") && item.scheduledAt?.let { sameDay(it, now) } == true
        }
        return DailyLoopSummary(
            plannedCount = planned.size,
            completedPlannedCount = planned.count { it.done },
            completedCount = items.count { it.done && it.completedAt?.let { time -> sameDay(time, now) } == true },
            rescheduledCount = items.count { it.lastRescheduledAt?.let { time -> sameDay(time, now) } == true },
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
