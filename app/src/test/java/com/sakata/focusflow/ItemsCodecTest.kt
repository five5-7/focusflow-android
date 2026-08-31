package com.sakata.focusflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ItemsCodecTest {
    private val time = 1_700_000_000_000L

    private fun item(id: Long = 42L) = Item(
        id = id, title = "写周报", detail = "先提纲", kind = "任务",
        done = true, scheduledAt = time, dayOnly = false, goalId = 7L,
        completionLevel = "完整版", completedAt = time + 1000, durationMinutes = 100,
        windowStartAt = time + 300, windowEndAt = time + 900,
        rescheduleCount = 2, lastRescheduledAt = time + 500,
        recoverySourceScheduledAt = time + 200, priority = "high"
    )

    @Test fun roundtrip_preservesAllFields() {
        val result = ItemsCodec.decode(ItemsCodec.encode(listOf(item())))
        assertFalse(result.idsNormalized)
        val decoded = result.items.single()
        assertEquals(42L, decoded.id)
        assertEquals("写周报", decoded.title)
        assertEquals("先提纲", decoded.detail)
        assertEquals("任务", decoded.kind)
        assertEquals(true, decoded.done)
        assertEquals(time, decoded.scheduledAt)
        assertEquals(false, decoded.dayOnly)
        assertEquals(7L, decoded.goalId)
        assertEquals("完整版", decoded.completionLevel)
        assertEquals(time + 1000, decoded.completedAt)
        assertEquals(100, decoded.durationMinutes)
        assertEquals(time + 300, decoded.windowStartAt)
        assertEquals(time + 900, decoded.windowEndAt)
        assertEquals(2, decoded.rescheduleCount)
        assertEquals(time + 500, decoded.lastRescheduledAt)
        assertEquals(time + 200, decoded.recoverySourceScheduledAt)
        assertEquals("high", decoded.priority)
    }

    @Test fun roundtrip_nullableFieldsStayNull() {
        val empty = item().copy(
            scheduledAt = null, dayOnly = true, goalId = null, completedAt = null,
            windowStartAt = null, windowEndAt = null, lastRescheduledAt = null,
            recoverySourceScheduledAt = null, priority = "low", durationMinutes = 5
        )
        val decoded = ItemsCodec.decode(ItemsCodec.encode(listOf(empty))).items.single()
        assertNull(decoded.scheduledAt)
        assertEquals(true, decoded.dayOnly)
        assertNull(decoded.goalId)
        assertNull(decoded.completedAt)
        assertNull(decoded.windowStartAt)
        assertNull(decoded.windowEndAt)
        assertNull(decoded.lastRescheduledAt)
        assertNull(decoded.recoverySourceScheduledAt)
        assertEquals("low", decoded.priority)
        assertEquals(5, decoded.durationMinutes)
    }

    @Test fun decode_duplicateIds_renamesSecondWithFlag() {
        val result = ItemsCodec.decode(ItemsCodec.encode(listOf(item(id = 42L), item(id = 42L))))
        assertTrue(result.idsNormalized)
        assertEquals(2, result.items.size)
        assertNotEquals(result.items[0].id, result.items[1].id)
        assertNotEquals(42L, result.items[1].id)
    }

    @Test fun decode_duplicateCompletedByIds_unwrapsSecond() {
        // 同 id 且同 completedAt 的「重复完成」：第二条重置为未完成（防止统计重复计入）
        val result = ItemsCodec.decode(ItemsCodec.encode(listOf(item(id = 42L), item(id = 42L))))
        assertEquals(false, result.items[1].done)
        assertEquals("", result.items[1].completionLevel)
        assertNull(result.items[1].completedAt)
    }

    @Test fun decode_badJson_returnsEmptyWithoutRewrite() {
        val result = ItemsCodec.decode("not-json{{{")
        assertEquals(0, result.items.size)
        assertFalse(result.idsNormalized)
    }

    @Test fun decode_emptyString_returnsEmpty() {
        val result = ItemsCodec.decode("")
        assertEquals(0, result.items.size)
    }
}
