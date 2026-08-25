package com.sakata.focusflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleTimelineModelsTest {
    @Test
    fun `overlapping courses are merged without consuming task events`() {
        val first = event("course-a", 8 * 60, 9 * 60, ScheduleType.COURSE)
        val second = event("course-b", 8 * 60 + 30, 10 * 60, ScheduleType.COURSE)
        val task = event("task", 11 * 60, 12 * 60, ScheduleType.TASK)

        val result = mergeConflictingCourses(listOf(first, task, second))

        assertEquals(2, result.size)
        val merged = result.first { it.type == ScheduleType.COURSE }
        assertTrue(merged.isConflict)
        assertEquals(8 * 60, merged.startMinute)
        assertEquals(10 * 60, merged.endMinute)
        assertTrue(result.contains(task))
    }

    @Test
    fun `overlapping events receive separate lanes and touching events reuse a lane`() {
        val first = event("first", 8 * 60, 9 * 60, ScheduleType.TASK)
        val overlap = event("overlap", 8 * 60 + 30, 9 * 60 + 30, ScheduleType.TASK)
        val touching = event("touching", 9 * 60, 10 * 60, ScheduleType.TASK)

        val layouts = layoutTimelineEvents(listOf(first, overlap, touching))

        assertEquals(3, layouts.size)
        assertEquals(0, layouts.first { it.event.key == "first" }.lane)
        assertEquals(1, layouts.first { it.event.key == "overlap" }.lane)
        assertEquals(0, layouts.first { it.event.key == "touching" }.lane)
        assertTrue(layouts.all { it.laneCount == 2 })
    }

    @Test
    fun `events entirely outside visible day are omitted`() {
        val before = event("before", 4 * 60, 5 * 60, ScheduleType.TASK)
        val after = event("after", 24 * 60, 25 * 60, ScheduleType.TASK)
        val visible = event("visible", 6 * 60, 7 * 60, ScheduleType.TASK)

        val layouts = layoutTimelineEvents(listOf(before, visible, after))

        assertEquals(listOf("visible"), layouts.map { it.event.key })
        assertFalse(layouts.single().event.isConflict)
    }

    private fun event(key: String, start: Int, end: Int, type: ScheduleType) = TimelineEvent(
        key = key,
        title = key,
        detail = key,
        weekday = 1,
        startMinute = start,
        endMinute = end,
        type = type
    )
}
