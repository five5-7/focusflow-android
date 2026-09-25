package com.sakata.focusflow

import java.util.Calendar

data class WantedReviewSettings(
    val enabled: Boolean = true,
    val intervalMonths: Int = 1,
    val lastReviewedAt: Long = 0L
)

internal object WantedReviewPolicy {
    fun nextAt(settings: WantedReviewSettings): Long? {
        if (!settings.enabled || settings.lastReviewedAt <= 0) return null
        return Calendar.getInstance().apply {
            timeInMillis = settings.lastReviewedAt
            add(Calendar.MONTH, settings.intervalMonths.coerceIn(1, 12))
        }.timeInMillis
    }

    fun due(settings: WantedReviewSettings, wantedCount: Int, now: Long): Boolean =
        wantedCount > 0 && (nextAt(settings)?.let { now >= it } == true)
}
