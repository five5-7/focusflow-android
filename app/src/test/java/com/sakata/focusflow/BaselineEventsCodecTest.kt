package com.sakata.focusflow

import org.junit.Assert.assertEquals
import org.junit.Test

class BaselineEventsCodecTest {
    @Test fun roundtrip_preservesFields() {
        val event = BaselineEvent(1L, BaselineEventType.MEAL_STARTED, 123L, "午餐 - 12:00")
        val decoded = BaselineEventsCodec.decode(BaselineEventsCodec.encode(listOf(event))).single()
        assertEquals(1L, decoded.id)
        assertEquals(BaselineEventType.MEAL_STARTED, decoded.type)
        assertEquals(123L, decoded.recordedAt)
        assertEquals("午餐 - 12:00", decoded.payload)
    }

    @Test fun decode_unknownTypeFallsBackToLifeStageSet() {
        val raw = """[{"id":1,"type":"not_real","recordedAt":123,"payload":""}]"""
        assertEquals(BaselineEventType.LIFE_STAGE_SET, BaselineEventsCodec.decode(raw).single().type)
    }

    @Test fun decode_badJson_returnsEmpty() {
        assertEquals(0, BaselineEventsCodec.decode("not-json{{{").size)
    }

    @Test fun without_removesOnlyMatchingId() {
        val events = listOf(
            BaselineEvent(1L, BaselineEventType.MEAL_STARTED, 100L, "a"),
            BaselineEvent(2L, BaselineEventType.CHECK_IN_RECORDED, 200L, "b"),
            BaselineEvent(3L, BaselineEventType.MEAL_ENDED, 300L, "c")
        )
        assertEquals(listOf(1L, 3L), BaselineEventsCodec.without(events, 2L).map { it.id })
        assertEquals(listOf(2L, 3L), BaselineEventsCodec.without(events, 1L).map { it.id })
        assertEquals(listOf(1L, 2L), BaselineEventsCodec.without(events, 3L).map { it.id })
    }

    @Test fun without_unmatched_returnsSameContent() {
        val events = listOf(BaselineEvent(9L, BaselineEventType.ACTIVITY_STARTED, 55L, ""))
        assertEquals(events, BaselineEventsCodec.without(events, 42L))
    }
}
