package com.sakata.focusflow

import org.junit.Assert.assertEquals
import org.junit.Test

class StatusCheckInCodecTest {
    @Test fun roundtrip_preservesFields() {
        val checkIn = StatusCheckIn(energy = "充足", activity = "学习", recordedAt = 777L)
        val decoded = StatusCheckInCodec.decode(StatusCheckInCodec.encode(listOf(checkIn))).single()
        assertEquals("充足", decoded.energy)
        assertEquals("学习", decoded.activity)
        assertEquals(777L, decoded.recordedAt)
    }

    @Test fun decode_unknownEnergyFallsBackToNormal() {
        val raw = StatusCheckInCodec.encode(listOf(StatusCheckIn(energy = "炸裂", activity = "休息", recordedAt = 1L)))
        assertEquals("正常", StatusCheckInCodec.decode(raw).single().energy)
    }

    @Test fun decode_unknownActivityFallsBackToOther() {
        val raw = StatusCheckInCodec.encode(listOf(StatusCheckIn(energy = "正常", activity = "冥想", recordedAt = 1L)))
        assertEquals("其他", StatusCheckInCodec.decode(raw).single().activity)
    }

    @Test fun decode_badJson_returnsEmpty() {
        assertEquals(0, StatusCheckInCodec.decode("not-json{{{").size)
    }

}
