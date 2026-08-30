package com.sakata.focusflow

import org.junit.Assert.assertEquals
import org.junit.Test

class ItemPriorityTest {

    @Test fun `unknown or missing keys fall back to mid`() {
        assertEquals(ItemPriority.MID, ItemPriority.fromKey(null))
        assertEquals(ItemPriority.MID, ItemPriority.fromKey(""))
        assertEquals(ItemPriority.MID, ItemPriority.fromKey("urgent"))
        assertEquals(ItemPriority.MID, ItemPriority.fromKey("急"))
    }

    @Test fun `known keys map to their levels with canonical labels`() {
        assertEquals(ItemPriority.LOW, ItemPriority.fromKey("low"))
        assertEquals(ItemPriority.MID, ItemPriority.fromKey("mid"))
        assertEquals(ItemPriority.HIGH, ItemPriority.fromKey("high"))
        assertEquals("低", ItemPriority.LOW.label)
        assertEquals("中", ItemPriority.MID.label)
        assertEquals("高", ItemPriority.HIGH.label)
    }

    @Test fun `new items default to mid and unknown values normalize on load`() {
        val fresh = Item(title = "默认", detail = "", kind = "任务")
        assertEquals("mid", fresh.priority)
        assertEquals("mid", ItemPriority.fromKey(fresh.priority).storageKey)
        // 模拟损坏值经 load 处理后的归一结果
        assertEquals("mid", ItemPriority.fromKey("weird").storageKey)
        assertEquals("high", ItemPriority.fromKey("high").storageKey)
    }
}
