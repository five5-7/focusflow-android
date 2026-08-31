package com.sakata.focusflow

import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryRetentionTest {
    @Test fun moreThan1000Events_preservesPlanCompletionAndReschedule() {
        val at = 1_700_000_000_000L
        var events = listOf(
            TaskEvent(id = 1, itemId = 9, type = TaskEventType.TASK_SCHEDULED, recordedAt = at, scheduledAt = at),
            TaskEvent(id = 2, itemId = 9, type = TaskEventType.TASK_COMPLETED, recordedAt = at),
            TaskEvent(id = 3, itemId = 9, type = TaskEventType.TASK_RESCHEDULED, recordedAt = at, scheduledAt = at)
        )
        val before = TaskHistory.daySummary(events, TaskHistory.dayStartOf(at))
        repeat(1500) { i -> events = TaskHistory.append(events, TaskEvent(id = i + 10L, itemId = i + 10L,
            type = TaskEventType.TASK_DELETED, recordedAt = at + 86_400_000L)) }
        val restored = TaskEventCodec.decode(TaskEventCodec.encode(events))
        assertEquals(1503, restored.size)
        assertEquals(before, TaskHistory.daySummary(restored, TaskHistory.dayStartOf(at)))
        assertEquals(50, TaskHistory.recentEvents(restored).size)
    }

    @Test fun baselineDelete_canReachOldestOf500WithoutChangingOthers() {
        val events = (1..500).map { BaselineEvent(it.toLong(), BaselineEventType.MEAL_STARTED, it.toLong(), "record") }
        val restored = BaselineEventsCodec.decode(BaselineEventsCodec.encode(BaselineEventsCodec.without(events, 1)))
        assertEquals(events.drop(1), restored)
    }
}
