package com.sakata.focusflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InboxBatchActionsTest {
    private fun inbox(id: Long) = Item(id = id, title = "条目$id", detail = "原说明$id", kind = "收集箱")

    @Test fun rejectsMissingAndNonPendingSelectionWithoutPartialMutation() {
        val items = listOf(inbox(1), inbox(2).copy(captureRoute = CaptureRoute.PROGRESS.storageKey), inbox(3))
        listOf(setOf(1L, 5L), setOf(1L, 2L), emptySet()).forEach { ids ->
            val result = InboxBatchActions.apply(items, ids, InboxBatchAction.DELETE)
            assertEquals(items, result.items)
            assertTrue(result.events.isEmpty())
        }
    }

    @Test fun convertsSeveralItemsWithOriginalIdsAndOneEventEach() {
        val items = listOf(inbox(1), inbox(2), inbox(3))
        val result = InboxBatchActions.apply(items, setOf(1L, 3L), InboxBatchAction.TO_TASK, at = 1000)
        assertEquals(listOf("任务", "收集箱", "任务"), result.items.map { it.kind })
        assertEquals(listOf(1L, 2L, 3L), result.items.map { it.id })
        assertEquals("原说明1", result.items[0].userNote)
        assertEquals(listOf(1L, 3L), result.events.map { it.itemId })
        assertTrue(result.events.all { it.type == TaskEventType.CAPTURE_ROUTED && it.recordedAt == 1000L })
    }

    @Test fun keepingOnlyRecordsReviewAndDoesNotReplaceCreationOrContent() {
        val items = listOf(inbox(1), inbox(2))
        val result = InboxBatchActions.apply(items, setOf(2L), InboxBatchAction.KEEP, at = 2000)
        assertEquals(items, result.items)
        assertEquals(listOf(2L), result.events.map { it.itemId })
        assertEquals("暂时保留", result.events.single().extra)
    }

    @Test fun referenceConversionKeepsBothSelectedParentAndChild() {
        val parent = inbox(1)
        val child = inbox(2).copy(parentCaptureId = parent.id)
        val result = InboxBatchActions.apply(listOf(parent, child), setOf(1L, 2L), InboxBatchAction.REFERENCE)
        assertEquals(2, result.events.size)
        assertTrue(result.items.all { CaptureRoute.fromKey(it.captureRoute) == CaptureRoute.REFERENCE })
        assertTrue(result.items.all { it.parentCaptureId == null })
    }

    @Test fun deletionCanRestoreOriginalOrderWithoutReplacingLaterChanges() {
        val items = listOf(inbox(1), inbox(2), inbox(3), inbox(4))
        val result = InboxBatchActions.apply(items, setOf(2L, 3L), InboxBatchAction.DELETE, at = 3000)
        assertEquals(listOf(1L, 4L), result.items.map { it.id })
        val newItem = inbox(5)
        val later = listOf(newItem, result.items[0].copy(title = "后来修改"), result.items[1])
        val undone = InboxBatchActions.undoDelete(later, items, result.affected, at = 4000)
        assertEquals(listOf(5L, 1L, 2L, 3L, 4L), undone.items.map { it.id })
        assertEquals("后来修改", undone.items[1].title)
        assertEquals(listOf(2L, 3L), undone.events.map { it.itemId })
        assertTrue(undone.events.all { it.type == TaskEventType.TASK_RESTORED })
        assertTrue(InboxBatchActions.undoDelete(undone.items, items, result.affected).events.isEmpty())
    }

    @Test fun undoDeleteRestoresUntouchedChildLinkOnly() {
        val parent = inbox(1)
        val child = inbox(2).copy(parentCaptureId = parent.id, captureRoute = CaptureRoute.PROGRESS.storageKey)
        val deleted = InboxBatchActions.apply(listOf(parent, child), setOf(1L), InboxBatchAction.DELETE)
        assertEquals(null, deleted.items.single().parentCaptureId)
        val restored = InboxBatchActions.undoDelete(deleted.items, listOf(parent, child), deleted.affected)
        assertEquals(parent.id, restored.items.last().parentCaptureId)
        val edited = deleted.items.single().copy(title = "后来修改")
        val withEdit = InboxBatchActions.undoDelete(listOf(edited), listOf(parent, child), deleted.affected)
        assertEquals(null, withEdit.items.last().parentCaptureId)
    }
}
