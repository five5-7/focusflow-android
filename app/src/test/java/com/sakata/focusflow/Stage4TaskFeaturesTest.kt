package com.sakata.focusflow

import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar

class Stage4TaskFeaturesTest {
    private val plan = Goal(id = 7, title = "编曲", weeklyTarget = 0, durationMinutes = 30)

    @Test fun `pasting lines creates one checklist task without scheduling every step`() {
        val existing = Item(id = 12, title = "旧任务", detail = "", kind = "任务")
        val created = TodoActions.createChecklist(listOf(existing), " 写歌 \n找鼓组\n写旋律", at = 100)
        assertEquals(1, created.created.size)
        assertEquals(1, created.events.size)
        assertEquals(listOf("找鼓组", "写旋律"), created.created.single().checklist.map { it.title })
        assertNull(created.created.single().scheduledAt)
        val checked = ChecklistActions.toggle(created.created.single(), created.created.single().checklist[0].id)!!
        assertTrue(checked.checklist[0].done)
        assertFalse(checked.done)
        assertEquals(existing, created.items.last())
        assertTrue(TodoActions.createChecklist(listOf(existing), "只有标题").created.isEmpty())
    }

    @Test fun `deadline is independent from scheduled time and orders undated work`() {
        val day = TaskHistory.dayStartOf(1_800_000_000_000L)
        val due = Item(id = 1, title = "今天截止", detail = "", kind = "任务", dueAt = day)
        val later = Item(id = 2, title = "以后", detail = "", kind = "任务")
        val groups = groupTodos(listOf(later, due), day + 60_000)
        assertEquals(listOf(due, later), groups.unscheduled)
        assertEquals(listOf(due), groupTodos(listOf(due), day + 86_400_000L).overdue)
        assertNull(due.scheduledAt)
    }

    @Test fun `plan buckets preserve one focus and reject stale or finished tasks`() {
        val first = Item(id = 1, title = "音阶", detail = "", kind = "任务", goalId = 7)
        val second = Item(id = 2, title = "曲式", detail = "", kind = "任务", goalId = 7)
        val other = Item(id = 3, title = "别的计划", detail = "", kind = "任务", goalId = 8, planFocus = true)
        val focused = PlanTaskActions.focus(listOf(first, second, other), plan, first)!!
        assertTrue(focused[0].planFocus)
        val moved = PlanTaskActions.move(focused, plan, focused[0], "later")!!
        assertFalse(moved[0].planFocus)
        assertNull(PlanTaskActions.focus(moved, plan, moved[0]))
        val next = PlanTaskActions.focus(moved, plan, moved[1])!!
        assertTrue(next[1].planFocus)
        assertTrue(next[2].planFocus)
        assertNull(PlanTaskActions.move(next, plan, first, "later"))
        assertNull(PlanTaskActions.focus(next, plan.copy(state = PlanState.PAUSED), next[1]))
    }

    @Test fun `wanted review rolls by calendar month and requires an existing list`() {
        val start = Calendar.getInstance().apply { set(2026, Calendar.JANUARY, 31, 10, 0, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
        val config = WantedReviewSettings(lastReviewedAt = start)
        val next = WantedReviewPolicy.nextAt(config)!!
        assertFalse(WantedReviewPolicy.due(config, 1, next - 1))
        assertTrue(WantedReviewPolicy.due(config, 1, next))
        assertFalse(WantedReviewPolicy.due(config, 0, next))
        assertFalse(WantedReviewPolicy.due(config.copy(enabled = false), 1, next))
        assertEquals(Calendar.FEBRUARY, Calendar.getInstance().apply { timeInMillis = next }.get(Calendar.MONTH))
    }
}
