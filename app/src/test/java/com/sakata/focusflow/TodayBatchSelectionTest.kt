package com.sakata.focusflow

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TodayBatchSelectionTest {
    private fun at(day: Int, hour: Int) = LocalDate.of(2026, 9, day).atTime(hour, 0)
        .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Test fun `selection includes only remaining independent tasks today`() {
        val now = at(25, 12)
        val eligible = Item(id = 1, title = "下午待办", detail = "", kind = "任务", scheduledAt = at(25, 14))
        val missed = eligible.copy(id = 2, scheduledAt = at(25, 9))
        val tomorrow = eligible.copy(id = 3, scheduledAt = at(26, 9))
        val goal = eligible.copy(id = 4, goalId = 50)
        val parent = eligible.copy(id = 5)
        val child = eligible.copy(id = 6, scheduledAt = null, parentCaptureId = 5)
        val allDay = eligible.copy(id = 7, scheduledAt = at(25, 0), dayOnly = true)
        val repeated = eligible.copy(id = 8, repeatTemplateId = 88, repeatOccurrenceDay = at(25, 0))

        assertEquals(setOf(1L, 7L), TodayBatchSelection.eligibleIds(
            listOf(eligible, missed, tomorrow, goal, parent, child, allDay, repeated), now))
    }

    @Test fun `today move and unschedule use exact batch undo`() {
        val now = at(25, 12)
        val timed = Item(id = 11, title = "下午", detail = "", kind = "任务", scheduledAt = at(25, 15))
        val allDay = Item(id = 12, title = "全天", detail = "", kind = "任务", scheduledAt = at(25, 0), dayOnly = true)
        val original = listOf(timed, allDay)
        val ids = TodayBatchSelection.eligibleIds(original, now)
        val moved = TodoBatchActions.apply(original, ids, TodoBatchAction.MOVE_DATE,
            targetDay = at(26, 12), keepTime = true)
        assertEquals(15, java.util.Calendar.getInstance().apply { timeInMillis = moved.items.first().scheduledAt!! }
            .get(java.util.Calendar.HOUR_OF_DAY))
        assertTrue(moved.items.last().dayOnly)
        assertEquals(original, TodoBatchActions.undo(moved.items, moved).first)

        val cleared = TodoBatchActions.apply(original, ids, TodoBatchAction.CLEAR_TIME)
        assertTrue(cleared.items.all { it.scheduledAt == null })
        assertEquals(original, TodoBatchActions.undo(cleared.items, cleared).first)
    }
}
