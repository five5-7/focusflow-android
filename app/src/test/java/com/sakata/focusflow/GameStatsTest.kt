package com.sakata.focusflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GameStatsTest {
    private val base = java.util.Calendar.getInstance().apply {
        clear(); set(2026, 0, 5, 8, 0, 0) // 2026-01-05 周一
    }.timeInMillis
    private val day = 24 * 60 * 60 * 1000L

    private fun record(id: Long, title: String, dayOffset: Int, onTime: Boolean = true) = GameSessionRecord(
        id = id, title = title, packageName = null,
        plannedStartAt = base + dayOffset * day,
        plannedEndAt = base + dayOffset * day + 30 * 60_000L,
        endedOnTime = onTime
    )

    @Test fun historicalDailyMax_requiresAtLeastThreeDays() {
        val two = listOf(record(1, "俯卧撑", 0), record(2, "俯卧撑", 1))
        assertNull(GameStats.historicalDailyMax(two, "俯卧撑"))
    }

    @Test fun historicalDailyMax_returnsMaxPerDay() {
        val records = listOf(
            record(1, "俯卧撑", 0),
            record(2, "俯卧撑", 1),
            record(3, "俯卧撑", 2),
            record(4, "俯卧撑", 2)  // 第 2 天安排了两次
        )
        assertEquals(2, GameStats.historicalDailyMax(records, "俯卧撑"))
    }

    @Test fun historicalDailyMax_ignoresOtherTitles() {
        val records = listOf(
            record(1, "俯卧撑", 0), record(2, "俯卧撑", 1), record(3, "俯卧撑", 2),
            record(4, "背单词", 0), record(5, "背单词", 1)
        )
        assertEquals(1, GameStats.historicalDailyMax(records, "俯卧撑"))
    }
}
