package com.sakata.focusflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskEventCodecTest {
    @Test
    fun `encode then decode keeps all fields`() {
        val events = listOf(
            TaskEvent(itemId = 42, type = TaskEventType.TASK_RESCHEDULED, recordedAt = 1000, title = "高数", scheduledAt = 2000, extra = "延后一小时")
        )
        val decoded = TaskEventCodec.decode(TaskEventCodec.encode(events))
        assertEquals(1, decoded.size)
        assertEquals(42, decoded[0].itemId)
        assertEquals(TaskEventType.TASK_RESCHEDULED, decoded[0].type)
        assertEquals(1000, decoded[0].recordedAt)
        assertEquals("高数", decoded[0].title)
        assertEquals(2000, decoded[0].scheduledAt)
        assertEquals("延后一小时", decoded[0].extra)
    }

    @Test
    fun `unknown type entry is dropped`() {
        val json = """[{"id":1,"itemId":1,"type":"task_future_unknown","recordedAt":1000}]"""
        assertTrue(TaskEventCodec.decode(json).isEmpty())
    }

    @Test
    fun `missing fields get defaults`() {
        val json = """[{"type":"task_completed","recordedAt":1000}]"""
        val decoded = TaskEventCodec.decode(json)
        assertEquals(1, decoded.size)
        assertEquals(TaskEventType.TASK_COMPLETED, decoded[0].type)
        assertEquals(0, decoded[0].itemId)
        assertEquals("", decoded[0].title)
        assertEquals(0, decoded[0].scheduledAt)
        assertEquals("", decoded[0].extra)
    }

    @Test
    fun `converted round trip`() {
        val events = listOf(TaskEvent(itemId = 7, type = TaskEventType.TASK_CONVERTED, recordedAt = 1000, title = "高数", extra = "深度学习"))
        val decoded = TaskEventCodec.decode(TaskEventCodec.encode(events))
        assertEquals(1, decoded.size)
        assertEquals(TaskEventType.TASK_CONVERTED, decoded[0].type)
        assertEquals("高数", decoded[0].title)
        assertEquals("深度学习", decoded[0].extra)
        assertEquals(0, decoded[0].scheduledAt)
    }

    @Test
    fun `attached to plan round trip`() {
        val events = listOf(TaskEvent(itemId = 7, type = TaskEventType.TASK_ATTACHED_TO_PLAN, recordedAt = 1000, title = "高数", extra = "深度学习"))
        val decoded = TaskEventCodec.decode(TaskEventCodec.encode(events))
        assertEquals(1, decoded.size)
        assertEquals(TaskEventType.TASK_ATTACHED_TO_PLAN, decoded[0].type)
        assertEquals("高数", decoded[0].title)
        assertEquals("深度学习", decoded[0].extra)
        assertEquals(0, decoded[0].scheduledAt)
    }

    @Test
    fun `invalid recordedAt entry is dropped`() {
        val json = """[{"type":"task_completed","recordedAt":0}]"""
        assertTrue(TaskEventCodec.decode(json).isEmpty())
    }

    @Test
    fun `corrupt json decodes to empty list`() {
        assertTrue(TaskEventCodec.decode("not json at all").isEmpty())
    }
}
