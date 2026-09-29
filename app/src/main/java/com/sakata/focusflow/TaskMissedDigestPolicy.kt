package com.sakata.focusflow

import java.util.Calendar

/** One morning summary for yesterday's still unresolved slots, including archived repeat misses. */
internal object TaskMissedDigestPolicy {
    fun todayStart(now: Long): Long = TaskHistory.dayStartOf(now)

    fun localDayKey(now: Long): Long = java.time.Instant.ofEpochMilli(now)
        .atZone(java.time.ZoneId.systemDefault()).toLocalDate().toEpochDay()

    fun yesterdayStart(today: Long): Long = Calendar.getInstance().apply {
        timeInMillis = today
        add(Calendar.DAY_OF_YEAR, -1)
    }.timeInMillis

    fun triggerAfter(now: Long): Long = Calendar.getInstance().apply {
        timeInMillis = now
        set(Calendar.HOUR_OF_DAY, 9)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        if (timeInMillis <= now) add(Calendar.DAY_OF_YEAR, 1)
    }.timeInMillis

    fun isFresh(expectedDay: Long, lastDeliveredDayKey: Long, now: Long): Boolean {
        val today = todayStart(now)
        val hour = Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.HOUR_OF_DAY)
        return expectedDay == today && lastDeliveredDayKey != localDayKey(now) && hour in 9..11
    }

    fun missedTitles(items: List<Item>, events: List<TaskEvent>, today: Long, now: Long): List<String> {
        val yesterday = yesterdayStart(today)
        val current = items.asSequence().filter { item ->
            item.kind == "任务" && !item.done &&
                item.scheduledAt?.let { it >= yesterday && it < today } == true &&
                RecoveryInsights.missedWindow(item, now)
        }.map { it.id to it.title }
        val archived = events.asSequence().filter { event ->
            event.type == TaskEventType.REPEAT_MISSED &&
                event.scheduledAt >= yesterday && event.scheduledAt < today &&
                event.recordedAt >= today
        }.map { it.itemId to it.title }
        return (current + archived).distinctBy { it.first }.map { it.second }.toList()
    }
}
