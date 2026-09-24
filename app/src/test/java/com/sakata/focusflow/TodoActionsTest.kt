package com.sakata.focusflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

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
}
