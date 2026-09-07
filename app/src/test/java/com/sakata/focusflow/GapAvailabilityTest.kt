package com.sakata.focusflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GapAvailabilityTest {
    private val from = Course("课程一", 1, 1, 1, "西区", CampusZone.WEST_TEACHING, false)
    private val to = Course("课程二", 1, 3, 3, "东区", CampusZone.EAST_TEACHING, false)

    @Test fun availabilityUsesNetGapAndFreeWindows() {
        val gap = CourseGap(from, to, minutesFree = 40, travelMinutes = 15, suggestedStartMinute = 540)
        val free = FreeWindow(2, 600, 720, 120, "整天空闲")

        val slots = availabilitySlots(listOf(gap), listOf(free))

        assertEquals(2, slots.size)
        assertEquals(540, slots.first().startMinute)
        assertEquals(580, slots.first().endMinute)
        assertEquals("课间", slots.first().kind)
    }

    @Test fun zeroSuggestionExplainsMissingContentInsteadOfClaimingNoGap() {
        val slots = listOf(AvailabilitySlot(1, 540, 600, 60, "课间"))

        val message = gapSuggestionEmptyMessage(listOf(from), slots, emptyList(), emptyList())

        assertTrue(message.contains("有 1 个可用时段"))
        assertTrue(message.contains("没有待完成目标或未定时任务"))
    }

    @Test fun zeroSuggestionExplainsDurationMismatch() {
        val slots = listOf(AvailabilitySlot(1, 540, 600, 60, "课间"))
        val item = Item(title = "长任务", detail = "", kind = "任务", durationMinutes = 90)

        val message = gapSuggestionEmptyMessage(listOf(from), slots, emptyList(), listOf(item))

        assertTrue(message.contains("最短需 90 分钟"))
        assertTrue(message.contains("最长空挡 60 分钟"))
    }
}
