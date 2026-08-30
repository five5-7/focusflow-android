package com.sakata.focusflow

import org.junit.Assert.assertEquals
import org.junit.Test

class TaskFeedbackCodecTest {
    @Test fun roundtrip_preservesFields() {
        val feedback = TaskFeedback(1L, 7L, "最低版本", "轻松", "时间不够", 999L)
        val decoded = TaskFeedbackCodec.decode(TaskFeedbackCodec.encode(listOf(feedback))).single()
        assertEquals(1L, decoded.id)
        assertEquals(7L, decoded.goalId)
        assertEquals("最低版本", decoded.completionLevel)
        assertEquals("轻松", decoded.difficulty)
        assertEquals("时间不够", decoded.barrier)
        assertEquals(999L, decoded.createdAt)
    }

    @Test fun roundtrip_multipleRecordsKeepsOrder() {
        val records = listOf(
            TaskFeedback(1L, 7L, "完整版", "轻松", "无", 1L),
            TaskFeedback(2L, 8L, "最低版本", "吃力", "精力不足", 2L)
        )
        val decoded = TaskFeedbackCodec.decode(TaskFeedbackCodec.encode(records))
        assertEquals(listOf(1L, 2L), decoded.map { it.id })
    }

    @Test fun decode_badJson_returnsEmpty() {
        assertEquals(0, TaskFeedbackCodec.decode("not-json{{{").size)
    }
}
