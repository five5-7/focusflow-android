package com.sakata.focusflow

import org.junit.Assert.assertEquals
import org.junit.Test

class InboxGroupingTest {
    private val now = 1_800_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    @Test fun separatesEarlierFromRecentAtSevenDayBoundary() {
        val items = (1L..4L).map { Item(id = it, title = "$it", detail = "", kind = "收集箱") }
        val groups = groupInboxByAge(items, mapOf(
            1L to now - 8 * day,
            2L to now - 7 * day,
            3L to now - day,
            4L to now + day
        ), now)
        assertEquals(listOf(4L, 3L, 2L), groups.recent.map { it.id })
        assertEquals(listOf(1L), groups.earlier.map { it.id })
        assertEquals(emptyList<Long>(), groups.undated.map { it.id })
    }

    @Test fun missingOrInvalidCreationEventPreservesLegacyOrder() {
        val items = listOf(3L, 1L, 2L).map { Item(id = it, title = "$it", detail = "", kind = "收集箱") }
        val groups = groupInboxByAge(items, mapOf(1L to 0L), now)
        assertEquals(emptyList<Long>(), groups.earlier.map { it.id })
        assertEquals(listOf(3L, 1L, 2L), groups.undated.map { it.id })
    }
}
