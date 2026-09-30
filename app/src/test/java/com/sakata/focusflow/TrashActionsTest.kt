package com.sakata.focusflow

import org.junit.Assert.*
import org.junit.Test

class TrashActionsTest {
    @Test fun `single and batch tombstones preserve original order notes dates and ids across json reload`() {
        val first = Item(id = 1, title = "报告", detail = "原说明", kind = "任务",
            userNote = "原始备注", scheduledAt = 1_800_000_000_000L, goalId = 9L)
        val second = Item(id = 2, title = "想法", detail = "信息", kind = "收集箱")
        val untouched = Item(id = 3, title = "其余任务", detail = "", kind = "任务")
        val original = listOf(first, second, untouched)
        val deleted = TrashActions.trash(original, setOf(1L, 2L), at = 1000)
        assertEquals(original.map { it.id }, deleted.items.map { it.id })
        assertEquals(2, deleted.events.size)
        assertTrue(deleted.items.take(2).all { it.kind == "回收站" && it.scheduledAt == null })
        val afterRestart = ItemsCodec.decode(ItemsCodec.encode(deleted.items)).items
        assertEquals(original, TrashActions.restore(afterRestart, setOf(1L, 2L), at = 2000).items)
        assertEquals("任务", TrashActions.originalKind(afterRestart.first()))
    }

    @Test fun `stale nested and invalid snapshots never restore partially`() {
        val parent = Item(id = 1, title = "父", detail = "", kind = "收集箱")
        val child = Item(id = 2, title = "子", detail = "", kind = "收集箱", parentCaptureId = 1L)
        assertTrue(TrashActions.trash(listOf(parent, child), setOf(1L)).events.isEmpty())
        val deleted = TrashActions.trash(listOf(parent, child.copy(parentCaptureId = null)), setOf(1L, 2L), at = 1000)
        assertTrue(TrashActions.restore(deleted.items, setOf(1L, 999L)).events.isEmpty())
        val corrupted = deleted.items.map { if (it.id == 2L) it.copy(trashSnapshot = "not-json") else it }
        assertEquals(corrupted, TrashActions.restore(corrupted, setOf(1L, 2L)).items)
    }
}
