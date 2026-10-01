package com.sakata.focusflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FrameTimingMathTest {
    @Test
    fun `empty frame window has no summary`() {
        assertNull(FrameTimingMath.summarize(emptyList(), 16_666_666L))
    }

    @Test
    fun `summary reports average p90 max and budget misses`() {
        val stats = FrameTimingMath.summarize(
            listOf(8_000_000L, 10_000_000L, 12_000_000L, 20_000_000L, 40_000_000L),
            frameBudgetNanos = 16_666_666L
        )!!

        assertEquals(5, stats.frameCount)
        assertEquals(18.0, stats.averageMs, 0.001)
        assertEquals(40.0, stats.p90Ms, 0.001)
        assertEquals(40.0, stats.maxMs, 0.001)
        assertEquals(2, stats.overBudgetFrames)
    }

    @Test
    fun `invalid durations are ignored`() {
        val stats = FrameTimingMath.summarize(listOf(-1L, 0L, 5_000_000L), 10_000_000L)!!
        assertEquals(1, stats.frameCount)
        assertEquals(5.0, stats.maxMs, 0.001)
    }
}
