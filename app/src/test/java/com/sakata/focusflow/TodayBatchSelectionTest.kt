package com.sakata.focusflow

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class TodayBatchSelectionTest {
    private fun at(day: Int, hour: Int) = LocalDate.of(2026, 9, day).atTime(hour, 0)
        .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Test fun `selection includes only remaining independent tasks today`() {
        val now = at(25, 12)
        val eligible = Item(id = 1, title = "下午待办", kind = "任务", scheduledAt = at(25, 14))
        val missed = eligible.copy(id = 2, scheduledAt = at(25, 9))
        val tomorrow = eligible.copy(id = 3, scheduledAt = at(26, 9))
        val goal = eligible.copy(id = 4, goalId = 50)
        val parent = eligible.copy(id = 5)
        val child = eligible.copy(id = 6, scheduledAt = null, parentCaptureId = 5)
        val allDay = eligible.copy(id = 7, scheduledAt = at(25, 0), dayOnly = true)

        assertEquals(setOf(1L, 7L), TodayBatchSelection.eligibleIds(
            listOf(eligible, missed, tomorrow, goal, parent, child, allDay), now))
    }
}
