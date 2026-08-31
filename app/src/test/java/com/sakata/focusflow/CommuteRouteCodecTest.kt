package com.sakata.focusflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommuteRouteCodecTest {
    @Test fun calibrations_roundtrip() {
        val calibrations = mapOf("西1→东2" to 25, "图书馆→食堂" to 12)
        assertEquals(calibrations, CommuteRouteCodec.decodeCalibrations(CommuteRouteCodec.encodeCalibrations(calibrations)))
    }

    @Test fun observations_roundtrip() {
        val observations = mapOf("西1→东2" to listOf(25, 24, 30))
        assertEquals(observations, CommuteRouteCodec.decodeObservations(CommuteRouteCodec.encodeObservations(observations)))
    }

    @Test fun decodeObservations_filtersOutOfRangeAndSlicesTwelve() {
        // 0/200 越界被剔除；过滤后再保留最近 12 次
        val raw = """{"路线A":[0,200,25,1,180,1,1,1,1,1,1,1,1,1]}"""
        val decoded = CommuteRouteCodec.decodeObservations(raw)
        assertEquals(listOf(25, 1, 180, 1, 1, 1, 1, 1, 1, 1, 1, 1), decoded["路线A"])
    }

    @Test fun legacyObservations_readsCalibrationAsSingleObservation() {
        val raw = """{"西1→东2":25,"bad":500}"""
        assertEquals(mapOf("西1→东2" to listOf(25)), CommuteRouteCodec.legacyObservations(raw))
    }

    @Test fun decode_badJson_returnsEmpty() {
        assertTrue(CommuteRouteCodec.decodeCalibrations("not-json{{{").isEmpty())
        assertTrue(CommuteRouteCodec.decodeObservations("not-json{{{").isEmpty())
    }
}
