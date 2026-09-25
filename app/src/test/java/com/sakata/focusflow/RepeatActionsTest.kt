package com.sakata.focusflow

import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar

class RepeatActionsTest {
    private fun day(year: Int, month: Int, date: Int): Long = Calendar.getInstance().apply {
        set(year, month - 1, date, 12, 0, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis.let(TaskHistory::dayStartOf)

    @Test fun `daily rule has a separate dated task and missed day never builds an overdue queue`() {
        val first = day(2026, 9, 25)
        val created = RepeatActions.create(emptyList(), "音准练习", "daily", first, minute = 9 * 60, at = first)
        assertEquals(2, created.items.size)
        val template = created.items.first { it.kind == "重复模板" }
        val instance = created.items.first { it.kind == "任务" }
        assertNotEquals(template.id, instance.id)
        assertEquals(template.id, instance.repeatTemplateId)
        assertEquals(first, instance.repeatOccurrenceDay)
        assertEquals(created.items, RepeatActions.refresh(created.items, first + 20_000L).items)

        val next = RepeatActions.refresh(created.items, day(2026, 9, 26) + 60_000)
        assertEquals(1, next.items.count { it.kind == "任务" && !it.done })
        assertEquals(1, next.items.count { it.kind == "重复历史" })
        assertEquals(day(2026, 9, 26), next.items.first { it.kind == "任务" }.repeatOccurrenceDay)
        assertEquals(1, next.events.count { it.type == TaskEventType.REPEAT_MISSED })
        val muchLater = RepeatActions.refresh(next.items, day(2026, 10, 10))
        assertEquals(1, muchLater.items.count { it.kind == "任务" && !it.done })
        assertEquals(2, muchLater.items.count { it.kind == "重复历史" })
    }

    @Test fun `weekly skip and pause affect the instance and not the rule`() {
        val friday = day(2026, 9, 25)
        val created = RepeatActions.create(emptyList(), "每周总结", "weekly", friday, at = friday)
        val template = created.items.first { it.kind == "重复模板" }
        val instance = created.items.first { it.kind == "任务" }
        val skipped = RepeatActions.skip(created.items, instance, friday + 60_000)
        assertEquals("重复历史", skipped.items.first { it.id == instance.id }.kind)
        assertEquals("weekly", skipped.items.first { it.id == template.id }.repeatFrequency)
        assertEquals(day(2026, 10, 2),
            RepeatActions.refresh(skipped.items, day(2026, 10, 2)).items.first { it.kind == "任务" }.repeatOccurrenceDay)
        val paused = RepeatActions.pause(created.items, template, true)
        assertTrue(paused.items.first { it.id == template.id }.repeatPaused)
        assertEquals(TaskEventType.REPEAT_RULE_CHANGED, paused.events.first().type)
        assertTrue(paused.items.none { it.kind == "任务" })
        assertTrue(RepeatActions.refresh(paused.items, day(2026, 10, 2)).events.isEmpty())
        val resumed = RepeatActions.pause(paused.items, paused.items.first { it.id == template.id }, false)
        assertEquals(TaskEventType.REPEAT_RULE_CHANGED, resumed.events.single().type)
        assertEquals(day(2026, 10, 2), RepeatActions.refresh(resumed.items, day(2026, 10, 2))
            .items.first { it.kind == "任务" }.repeatOccurrenceDay)
    }

    @Test fun `rescheduled instance does not move next rule date`() {
        val friday = day(2026, 9, 25)
        val created = RepeatActions.create(emptyList(), "复习", "daily", friday, at = friday)
        val old = created.items.first { it.kind == "任务" }
        val moved = created.items.map { if (it.id == old.id) it.copy(scheduledAt = day(2026, 10, 1)) else it }
        val refreshed = RepeatActions.refresh(moved, day(2026, 9, 26))
        assertEquals(2, refreshed.items.count { it.kind == "任务" && !it.done })
        assertEquals(day(2026, 9, 26), refreshed.items.first { it.repeatOccurrenceDay == day(2026, 9, 26) }.repeatOccurrenceDay)
    }
}
