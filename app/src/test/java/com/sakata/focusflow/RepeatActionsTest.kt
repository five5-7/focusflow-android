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
        assertEquals(first + 9 * 60 * 60_000L,
            next.events.single { it.type == TaskEventType.REPEAT_MISSED }.scheduledAt)
        assertEquals(1, RecoveryInsights.weeklySummary(next.items, day(2026, 9, 26),
            created.events + next.events).missedCount)
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

    @Test fun `creating timed repeat after today's time begins at next date`() {
        val today = day(2026, 9, 25)
        val created = RepeatActions.create(emptyList(), "夜读", "daily", today,
            minute = 9 * 60, at = today + 12 * 60 * 60_000L)
        assertEquals(day(2026, 9, 26), created.items.first { it.kind == "任务" }.repeatOccurrenceDay)
    }
    @Test fun `class day repeat uses confirmed active courses and does not count days without class`() {
        val friday = day(2026, 9, 25)
        val epochDay = java.time.LocalDate.of(2026, 9, 25).toEpochDay()
        val course = Course("物理", 5, 1, 2, "东区", CampusZone.OTHER,
            needsConfirmation = false, effectiveFromEpochDay = epochDay, effectiveUntilEpochDay = epochDay)
        val ruleOnly = RepeatActions.create(emptyList(), "复习今天课程", "class_day", friday,
            at = friday, courses = listOf(course.copy(needsConfirmation = true)))
        assertEquals(1, ruleOnly.items.size)
        val active = RepeatActions.refresh(ruleOnly.items, friday, listOf(course))
        val instance = active.items.single { it.kind == "任务" }
        assertEquals(friday, instance.repeatOccurrenceDay)
        assertTrue(instance.detail.contains("物理"))
        assertEquals(active.items, RepeatActions.refresh(active.items, friday, listOf(course)).items)
        val next = RepeatActions.refresh(active.items, day(2026, 9, 26), listOf(course))
        assertEquals(0, next.items.count { it.kind == "任务" })
        assertEquals(1, next.events.count { it.type == TaskEventType.REPEAT_MISSED })
    }

    @Test fun `removing a future class cancels its conditional occurrence without marking it missed`() {
        val friday = day(2026, 9, 25)
        val saturday = day(2026, 9, 26)
        val course = Course("实验", 6, 1, 2, "东区", CampusZone.OTHER, needsConfirmation = false)
        val created = RepeatActions.create(emptyList(), "复习课程", "class_day", friday,
            at = friday, courses = listOf(course))
        assertEquals(saturday, created.items.single { it.kind == "任务" }.repeatOccurrenceDay)
        val removed = RepeatActions.refresh(created.items, friday, emptyList())
        assertTrue(removed.items.none { it.kind == "任务" })
        assertTrue(removed.events.none { it.type == TaskEventType.REPEAT_MISSED })
        assertEquals(0, TaskHistory.daySummary(created.events + removed.events, saturday).scheduledCount)
        assertTrue(RepeatActions.refresh(removed.items, saturday, emptyList()).events.isEmpty())
    }
    @Test fun `stopped rule retains history and cannot generate another occurrence`() {
        val friday = day(2026, 9, 25)
        val created = RepeatActions.create(emptyList(), "读书", "daily", friday, at = friday)
        val rule = created.items.first { it.kind == "重复模板" }
        val stopped = RepeatActions.stop(created.items, rule, friday + 60_000L)
        assertEquals("已停止重复", stopped.items.first { it.id == rule.id }.kind)
        assertEquals(1, stopped.items.count { it.kind == "重复历史" })
        assertTrue(RepeatActions.refresh(stopped.items, day(2026, 9, 26)).events.isEmpty())
        assertTrue(RepeatActions.pause(stopped.items, stopped.items.first { it.id == rule.id }, false).events.isEmpty())
        val deleted = RepeatActions.deleteRule(created.items, rule, friday + 60_000L)
        assertEquals("回收站", deleted.items.first { it.id == rule.id }.kind)
        assertEquals("重复模板", TrashActions.restore(deleted.items, setOf(rule.id)).items.first { it.id == rule.id }.kind)
    }

    @Test fun `paused stopped and canceled occurrences leave execution denominator but skips remain`() {
        val friday = day(2026, 9, 25)
        val created = RepeatActions.create(emptyList(), "练习", "daily", friday, at = friday)
        val rule = created.items.first { it.kind == "重复模板" }
        val instance = created.items.first { it.kind == "任务" }
        assertEquals(1, TaskHistory.daySummary(created.events, friday).scheduledCount)
        val paused = RepeatActions.pause(created.items, rule, true, friday)
        assertEquals(0, TaskHistory.daySummary(created.events + paused.events, friday).scheduledCount)
        assertEquals(0, RecoveryInsights.weeklySummary(paused.items, friday,
            created.events + paused.events).plannedCount)
        val stopped = RepeatActions.stop(created.items, rule, friday)
        assertEquals(0, TaskHistory.daySummary(created.events + stopped.events, friday).scheduledCount)
        val canceled = RepeatActions.cancelInstance(created.items, instance, friday)
        assertEquals(0, TaskHistory.daySummary(created.events + canceled.events, friday).scheduledCount)
        val skipped = RepeatActions.skip(created.items, instance, friday)
        assertEquals(1, TaskHistory.daySummary(created.events + skipped.events, friday).scheduledCount)
        val lateStop = RepeatActions.stop(created.items, rule, day(2026, 9, 26))
        assertEquals(1, TaskHistory.daySummary(created.events + lateStop.events, friday).scheduledCount)
        assertEquals("本次未处理", lateStop.items.first { it.id == instance.id }.detail)
        assertEquals(1, lateStop.events.count { it.type == TaskEventType.REPEAT_MISSED })
        val completedInstance = instance.copy(done = true)
        val completedItems = created.items.map { if (it.id == instance.id) completedInstance else it }
        assertTrue(RepeatActions.cancelInstance(completedItems, completedInstance, friday).events.isEmpty())
    }

    @Test fun `pausing after a timed slot ends keeps the missed fact`() {
        val friday = day(2026, 9, 25)
        val created = RepeatActions.create(emptyList(), "练习", "daily", friday,
            minute = 9 * 60, at = friday)
        val rule = created.items.first { it.kind == "重复模板" }
        val instance = created.items.first { it.kind == "任务" }
        val pauseAt = friday + 11 * 60 * 60_000L
        val paused = RepeatActions.pause(created.items, rule, true, pauseAt)
        assertEquals("本次未处理", paused.items.first { it.id == instance.id }.detail)
        assertEquals(1, paused.events.count { it.type == TaskEventType.REPEAT_MISSED })
        assertEquals(1, TaskHistory.daySummary(created.events + paused.events, friday).scheduledCount)
    }

}
