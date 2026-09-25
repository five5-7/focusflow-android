package com.sakata.focusflow

import org.junit.Assert.*
import org.junit.Test

class WantedPlanActionsTest {
    @Test fun `name only plan keeps optional fields empty and never creates weekly tasks`() {
        val wanted = requireNotNull(WantedPlanActions.create(emptyList(), "  写书  "))
        assertEquals(0, wanted.weeklyTarget)
        assertEquals("", wanted.metricTarget)
        val started = requireNotNull(WantedPlanActions.changeState(wanted, PlanState.IN_PROGRESS))
        assertTrue(GoalPlanner.autoPlan(listOf(started), emptyList(), emptyList(), CommuteProfile(), { _, _ -> null }).newItems.isEmpty())
    }

    @Test fun `edit preserves converted source notes and multiple linked tasks remain unscheduled`() {
        val wanted = requireNotNull(WantedPlanActions.create(emptyList(), "学钢琴", "钢琴\n原始想法"))
        val edited = requireNotNull(WantedPlanActions.edit(wanted, "  学钢琴入门 ", " 能弹一首歌 ", wanted.sourceNotes))
        assertEquals("钢琴\n原始想法", edited.sourceNotes)
        assertEquals("能弹一首歌", edited.desiredOutcome)
        assertNull(WantedPlanActions.edit(edited, "  ", "", ""))
        val active = requireNotNull(WantedPlanActions.changeState(edited, PlanState.IN_PROGRESS))
        val first = WantedPlanActions.linkedTask(emptyList(), active, "  找课程 ")
        val second = WantedPlanActions.linkedTask(first.items, active, "练基本音阶")
        assertEquals(listOf("练基本音阶", "找课程"), second.items.map { it.title })
        assertTrue(second.items.all { it.goalId == active.id && it.scheduledAt == null && it.kind == "任务" })
        assertEquals(TaskEventType.TASK_CREATED, second.event?.type)
        assertNull(WantedPlanActions.linkedTask(second.items, active, "  ").item)
        assertNull(WantedPlanActions.linkedTask(second.items, wanted, "重复").item)
    }

    @Test fun `batch conversion keeps all source titles and notes and removes only selected inbox records`() {
        val first = Item(id = 10, title = "学钢琴", kind = "收集箱", detail = "刚刚记录 · 找课程", userNote = "找课程")
        val second = Item(id = 11, title = "买琴", kind = "收集箱", detail = "刚刚记录 · 确认预算", userNote = "确认预算")
        val other = Item(id = 12, title = "本周任务", detail = "", kind = "任务")
        val result = WantedPlanActions.fromInbox(listOf(first, other, second), emptyList(), setOf(10, 11))
        assertEquals(listOf(other), result.items)
        assertEquals(PlanState.WANTED, result.created?.state)
        assertEquals("学钢琴", result.created?.title)
        assertEquals("学钢琴\n找课程\n\n买琴\n确认预算", result.created?.sourceNotes)
        assertEquals(2, result.events.size)
        assertTrue(result.events.all { it.type == TaskEventType.TASK_CONVERTED })
    }

    @Test fun `stale or child selection has no effects`() {
        val parent = Item(id = 10, title = "大方向", detail = "", kind = "收集箱")
        val child = Item(id = 11, title = "第一步", detail = "", kind = "收集箱", parentCaptureId = 10)
        val items = listOf(parent, child)
        assertNull(WantedPlanActions.fromInbox(items, emptyList(), setOf(10)).created)
        assertNull(WantedPlanActions.fromInbox(items, emptyList(), setOf(11)).created)
        val stale = WantedPlanActions.fromInbox(items, emptyList(), setOf(10, 999))
        assertEquals(items, stale.items)
        assertTrue(stale.events.isEmpty())
    }

    @Test fun `paused plan cannot become wanted and completed plan stays finished`() {
        val plan = requireNotNull(WantedPlanActions.create(emptyList(), "  练琴  "))
        assertEquals("练琴", plan.title)
        val paused = requireNotNull(WantedPlanActions.changeState(plan, PlanState.PAUSED))
        assertNull(WantedPlanActions.changeState(paused, PlanState.WANTED))
        assertEquals(PlanState.IN_PROGRESS, WantedPlanActions.changeState(paused, PlanState.IN_PROGRESS)?.state)
        val done = requireNotNull(WantedPlanActions.changeState(paused, PlanState.COMPLETED))
        assertNull(WantedPlanActions.changeState(done, PlanState.IN_PROGRESS))
        assertNull(WantedPlanActions.create(emptyList(), "   "))
    }
}
