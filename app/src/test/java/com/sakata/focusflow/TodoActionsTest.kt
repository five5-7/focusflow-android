package com.sakata.focusflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class TodoActionsTest {
    @Test fun titleOnlyCreatesOneUnscheduledTaskAndEvent() {
        val original = Item(id = 42, title = "旧任务", detail = "", kind = "任务")
        val result = TodoActions.create(listOf(original), "  复习课程  ", at = 1234)
        assertEquals("复习课程", result.item!!.title)
        assertEquals("任务", result.item.kind)
        assertNull(result.item.scheduledAt)
        assertEquals(false, result.item.dayOnly)
        assertEquals(listOf(result.item, original), result.items)
        assertEquals(result.item.id, result.event!!.itemId)
        assertEquals(TaskEventType.TASK_CREATED, result.event.type)
        assertEquals(1234L, result.event.recordedAt)
    }

    @Test fun blankTitleDoesNothing() {
        val result = TodoActions.create(emptyList(), " \n ")
        assertNull(result.item)
        assertNull(result.event)
        assertEquals(emptyList<Item>(), result.items)
    }

    @Test fun pastedLinesCreateDistinctTodosAndEventsInInputOrder() {
        val existing = Item(id = 71, title = "原任务", detail = "", kind = "任务")
        val result = TodoActions.createLines(listOf(existing), " 复习高数\n\n  练琴 \n写报告 ", at = 1234)
        assertEquals(listOf("复习高数", "练琴", "写报告"), result.created.map { it.title })
        assertEquals(4, result.items.size)
        assertEquals(existing, result.items.last())
        assertEquals(3, result.created.map { it.id }.toSet().size)
        assertTrue(result.events.zip(result.created).all { (event, item) ->
            event.type == TaskEventType.TASK_CREATED && event.itemId == item.id && event.recordedAt == 1234L
        })
        assertEquals(emptyList<Item>(), TodoActions.createLines(emptyList(), "  \n ").created)
        assertEquals(emptyList<Item>(), TodoActions.createLines(emptyList(), (1..51).joinToString("\n")).created)
    }

    @Test fun dateOnlyTodosUseLocalDayAndNoClockTime() {
        val at = 1_796_000_000_000L
        val result = TodoActions.createLines(emptyList(), "办事", dateOnlyAt = at)
        val task = result.created.single()
        assertTrue(task.dayOnly)
        assertEquals(TaskHistory.dayStartOf(at), task.scheduledAt)
        assertEquals(task.scheduledAt, result.events.single().scheduledAt)
    }

    @Test fun oneOffTimeCreatesTimedReminderAndRejectsPastTimeWithoutPartialSave() {
        val start = Calendar.getInstance().apply {
            set(2026, 9, 5, 12, 0, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val future = TodoActions.createLines(emptyList(), "复习", at = start, minute = 14 * 60)
        assertEquals(1, future.created.size)
        assertEquals(false, future.created.single().dayOnly)
        assertEquals(14, Calendar.getInstance().apply { timeInMillis = future.created.single().scheduledAt!! }.get(Calendar.HOUR_OF_DAY))
        assertEquals(future.created.single().scheduledAt, future.events.single().scheduledAt)
        assertTrue(TodoActions.createLines(emptyList(), "复习", at = start, minute = 11 * 60).created.isEmpty())
    }
}
