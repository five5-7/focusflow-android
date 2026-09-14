package com.sakata.focusflow

internal data class FrameTimingStats(
    val frameCount: Int,
    val averageMs: Double,
    val p90Ms: Double,
    val maxMs: Double,
    val overBudgetFrames: Int
)

internal object FrameTimingMath {
    fun summarize(durationsNanos: List<Long>, frameBudgetNanos: Long): FrameTimingStats? {
        val valid = durationsNanos.filter { it > 0L }.sorted()
        if (valid.isEmpty()) return null
        val p90Index = ((valid.size * 9 + 9) / 10 - 1).coerceIn(0, valid.lastIndex)
        return FrameTimingStats(
            frameCount = valid.size,
            averageMs = valid.average() / NANOS_PER_MS,
            p90Ms = valid[p90Index] / NANOS_PER_MS,
            maxMs = valid.last() / NANOS_PER_MS,
            overBudgetFrames = valid.count { it > frameBudgetNanos }
        )
    }

    private const val NANOS_PER_MS = 1_000_000.0
}
