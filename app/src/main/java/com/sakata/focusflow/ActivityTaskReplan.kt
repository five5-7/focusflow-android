package com.sakata.focusflow

/** Keep a linked timer's replan attached to the same unfinished task. */
internal object ActivityTaskReplan {
    fun canOpen(session: ActivitySession, task: Item?): Boolean =
        session.isOpen() && session.taskId != null && task != null && task.id == session.taskId &&
            task.kind == "任务" && !task.done

    fun canCommit(
        session: ActivitySession,
        original: Item,
        current: Item?,
        scheduledAt: Long,
        durationMinutes: Int,
        now: Long = System.currentTimeMillis()
    ): Boolean = canOpen(session, original) && current == original &&
        scheduledAt > now && durationMinutes in 5..360
}
