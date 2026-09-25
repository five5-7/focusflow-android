package com.sakata.focusflow

/** Only independent, still actionable tasks from today's schedule can enter the batch editor. */
internal object TodayBatchSelection {
    fun eligibleIds(items: List<Item>, now: Long): Set<Long> = items.asSequence()
        .filter { it.kind == "任务" && !it.done && it.goalId == null && it.parentCaptureId == null &&
            it.scheduledAt?.let { time -> ScheduleOccupation.sameDate(time, now) } == true &&
            !RecoveryInsights.missedWindow(it, now) }
        .filter { task -> items.none { it.parentCaptureId == task.id } }
        .mapTo(mutableSetOf()) { it.id }
}
