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
}
