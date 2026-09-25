package com.sakata.focusflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityTaskReplanTest {
    @Test fun `replan keeps the linked task and rejects a stale or past selection`() {
        val task = Item(id = 77L, title = "复习", kind = "任务", scheduledAt = 100_000L,
            repeatTemplateId = 9L, repeatOccurrenceDay = 0L)
        val session = ActivitySession(id = 88L, name = "复习", taskId = task.id,
            actualStartAt = 1_000L, plannedStartAt = 1_000L, endsAt = 61_000L)
        assertTrue(ActivityTaskReplan.canCommit(session, task, task, 200_000L, 45, now = 150_000L))
        val delayed = TaskActions.planDelayed(listOf(task), task, 200_000L, 45,
            "", task.priority, now = 150_000L).delayedItem
        assertEquals(task.id, delayed.id)
        assertEquals("任务", delayed.kind)
        assertEquals(task.repeatTemplateId, delayed.repeatTemplateId)
        assertEquals(task.repeatOccurrenceDay, delayed.repeatOccurrenceDay)
        assertEquals(200_000L, delayed.scheduledAt)
        assertFalse(delayed.done)
        assertFalse(ActivityTaskReplan.canCommit(session, task, task.copy(title = "已更改"), 200_000L, 45, 150_000L))
        assertFalse(ActivityTaskReplan.canCommit(session, task, task, 140_000L, 45, 150_000L))
        assertFalse(ActivityTaskReplan.canCommit(session, task, task, 200_000L, 0, 150_000L))
        assertFalse(ActivityTaskReplan.canOpen(session.copy(taskId = 99L), task))
        assertFalse(ActivityTaskReplan.canOpen(session.copy(status = ActivitySession.STATUS_COMPLETED), task))
        assertFalse(ActivityTaskReplan.canOpen(session, task.copy(done = true)))
    }
}
