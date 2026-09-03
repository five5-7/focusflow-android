package com.sakata.focusflow

import org.junit.Assert.assertEquals
import org.junit.Test

class SleepSummaryCodecTest {
    @Test fun roundtrip_preservesSummary() {
        val value = SleepSummary(10L, 20L, 480, "watch", 30L)
        assertEquals(value, SleepSummaryCodec.decode(SleepSummaryCodec.encode(listOf(value))).single())
    }

    @Test fun damagedInput_returnsEmpty() {
        assertEquals(emptyList<SleepSummary>(), SleepSummaryCodec.decode("not-json"))
    }
}
