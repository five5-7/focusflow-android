package com.sakata.focusflow

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class DatedOccupationTest {
    private fun at(date: String, hour: Int, minute: Int = 0): Long =
        LocalDate.parse(date).atTime(hour, minute).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun task(time: Long, duration: Int = 60) =
        Item(id = 1, title = "占用", detail = "", kind = "任务", scheduledAt = time, durationMinutes = duration)

    @Test fun oldMondayDoesNotBlockNewMonday() {
        val old = task(at("2026-09-07", 9))
        assertTrue(ScheduleOccupation.taskBlocks(listOf(old), 1, targetDay = at("2026-09-14", 9)).isEmpty())
        assertTrue(slotFree(at("2026-09-14", 9), 30, emptyList(), listOf(old)))
    }

    @Test fun crossingMidnightOccupiesBothDates() {
        val item = task(at("2026-09-07", 23, 30), 120)
        val first = ScheduleOccupation.taskBlocks(listOf(item), 1, targetDay = at("2026-09-07", 12)).single()
        val second = ScheduleOccupation.taskBlocks(listOf(item), 2, targetDay = at("2026-09-08", 12)).single()
        assertEquals(1410, first.startMinute)
        assertEquals(1440, first.endMinute)
        assertEquals(0, second.startMinute)
        assertEquals(90, second.endMinute)
        assertFalse(slotFree(at("2026-09-08", 0, 30), 30, emptyList(), listOf(item)))
    }

    @Test fun newCrossMidnightPlanChecksSecondDay() {
        val item = task(at("2026-09-08", 0, 30))
        assertNotNull(conflictAdvice(at("2026-09-07", 23, 30), 120, emptyList(), listOf(item), null))
        assertFalse(slotFree(at("2026-09-07", 23, 30), 120, emptyList(), listOf(item)))
    }

    @Test fun dayOnlyAndCompletedItemsDoNotBlock() {
        val time = at("2026-09-07", 9)
        assertTrue(ScheduleOccupation.taskBlocks(listOf(task(time).copy(dayOnly = true)), 1, targetDay = time).isEmpty())
        assertTrue(ScheduleOccupation.taskBlocks(listOf(task(time).copy(done = true)), 1, targetDay = time).isEmpty())
        assertTrue(ScheduleOccupation.taskBlocks(listOf(task(time).copy(kind = "暂停")), 1, targetDay = time).isEmpty())
    }

    @Test fun previousSundayTailIsIncludedInNewWeek() {
        val occupied = occupiedByWeekday(listOf(task(at("2026-09-06", 23, 30), 120)), at("2026-09-07", 0))
        assertEquals(listOf(0 until 90), occupied[1])
        assertFalse(occupied.containsKey(7))
    }

    @Test fun completedAndPausedItemsDoNotEnterWeeklyOccupation() {
        val week = at("2026-09-07", 0)
        val time = at("2026-09-07", 9)
        val occupied = occupiedByWeekday(listOf(task(time).copy(done = true), task(time).copy(id = 2, kind = "暂停")), week)
        assertTrue(occupied.isEmpty())
    }

    @Test fun pastDayDoesNotBlockNextWeeksSameWeekday() {
        val yesterday = task(at("2026-09-07", 9))
        val nextMonday = task(at("2026-09-14", 10)).copy(id = 2)

        val occupied = occupiedByWeekday(listOf(yesterday, nextMonday), at("2026-09-08", 12))

        assertEquals(listOf(10 * 60 until 11 * 60), occupied[1])
    }

    @Test fun elapsedPartOfTodayCannotBeRecommendedAgain() {
        val occupied = occupiedByWeekday(emptyList(), at("2026-09-08", 12, 30))

        assertEquals(listOf(0 until 12 * 60 + 30), occupied[2])
    }

    @Test fun todayAgendaDoesNotReuseLastWeeksWeekday() {
        val previousMonday = task(at("2026-09-07", 9)).copy(title = "上周任务")
        val currentMonday = task(at("2026-09-14", 10)).copy(id = 2, title = "今天任务")

        val agenda = todayAgenda(emptyList(), listOf(previousMonday, currentMonday), at("2026-09-14", 8))

        assertEquals(listOf("今天任务"), agenda.map { it.title })
    }

    @Test fun missedTimedTaskMovesFromAgendaToRecoveryWithoutLosingItsSchedule() {
        val now = at("2026-09-14", 12)
        val missed = task(at("2026-09-14", 9)).copy(title = "早上的任务")
        val upcoming = task(at("2026-09-14", 14)).copy(id = 2, title = "下午的任务")

        assertEquals(listOf("下午的任务"), todayAgenda(emptyList(), listOf(missed, upcoming), now).map { it.title })
        assertEquals(missed.id, RecoveryInsights.candidates(listOf(missed, upcoming), now).single().item.id)
        assertEquals(at("2026-09-14", 9), missed.scheduledAt)
    }

    @Test fun allDayTaskStaysInTodayUntilTheNextCalendarDay() {
        val allDay = task(at("2026-09-14", 0)).copy(dayOnly = true)
        val noon = at("2026-09-14", 12)

        assertFalse(RecoveryInsights.missedWindow(allDay, noon))
        assertEquals(1, todayAgenda(emptyList(), listOf(allDay), noon).size)
        assertTrue(RecoveryInsights.missedWindow(allDay, at("2026-09-15", 0)))
    }

    @Test fun taskStaysScheduledUntilItsPlannedEnd() {
        val scheduled = task(at("2026-09-14", 9), 60)

        assertFalse(RecoveryInsights.missedWindow(scheduled, at("2026-09-14", 10)))
        assertEquals(1, todayAgenda(emptyList(), listOf(scheduled), at("2026-09-14", 10)).size)
        assertTrue(RecoveryInsights.missedWindow(scheduled, at("2026-09-14", 10, 1)))
    }
}
