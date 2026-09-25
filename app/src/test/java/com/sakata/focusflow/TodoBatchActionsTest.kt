package com.sakata.focusflow

import org.junit.Assert.*
import org.junit.Test

class TodoBatchActionsTest {
    private val first = Item(id = 1, title = "复习", detail = "原备注", kind = "任务", scheduledAt = 200_000L)
    private val second = Item(id = 2, title = "报告", detail = "", kind = "任务")
    private val linked = Item(id = 3, title = "目标任务", detail = "", kind = "任务", goalId = 55L)

    @Test fun `complete and undo keep unrelated work and record cancellation`() {
        val original = listOf(first, linked, second)
        val result = TodoBatchActions.apply(original, setOf(1, 2), TodoBatchAction.COMPLETE, at = 900L)
        assertTrue(result.items.filter { it.id != 3L }.all { it.done && it.completedAt == 900L })
        assertEquals(linked, result.items[1])
        assertEquals(2, result.events.size)
        val (restored, events) = TodoBatchActions.undo(result.items, result, at = 1000L)
        assertEquals(original, restored)
        assertTrue(events.all { it.type == TaskEventType.TASK_UNCOMPLETED })
    }

    @Test fun `unschedule and undo preserve notes and original time`() {
        val original = listOf(first, second)
        val result = TodoBatchActions.apply(original, setOf(1), TodoBatchAction.CLEAR_TIME)
        assertNull(result.items.first().scheduledAt)
        assertEquals("原备注", result.items.first().editableNote())
        assertEquals(TaskEventType.TASK_UNSCHEDULED, result.events.single().type)
        val (restored, events) = TodoBatchActions.undo(result.items, result)
        assertEquals(original, restored)
        assertEquals(200_000L, events.single().scheduledAt)
        assertEquals(TaskEventType.TASK_SCHEDULED, events.single().type)
    }

    @Test fun `deletion undo restores original order but does not overwrite later edits`() {
        val original = listOf(first, linked, second)
        val result = TodoBatchActions.apply(original, setOf(1, 2), TodoBatchAction.DELETE)
        assertEquals(listOf(linked), result.items)
        assertEquals(original, TodoBatchActions.undo(result.items, result).first)
        val edited = TodoBatchActions.apply(original, setOf(1), TodoBatchAction.COMPLETE)
            .items.map { if (it.id == 1L) it.copy(title = "新的名称") else it }
        val completed = TodoBatchActions.apply(original, setOf(1), TodoBatchAction.COMPLETE)
        assertEquals(edited to emptyList<TaskEvent>(), TodoBatchActions.undo(edited, completed))
    }

    @Test fun `rejects goal linked tasks and stale or mixed unscheduled selection atomically`() {
        val original = listOf(first, linked, second)
        assertTrue(TodoBatchActions.apply(original, setOf(1, 3), TodoBatchAction.COMPLETE).events.isEmpty())
        assertTrue(TodoBatchActions.apply(original, setOf(1, 2), TodoBatchAction.CLEAR_TIME).events.isEmpty())
        assertTrue(TodoBatchActions.apply(original, setOf(1, 999), TodoBatchAction.DELETE).events.isEmpty())
    }
}
