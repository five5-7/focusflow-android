package com.sakata.focusflow

import org.junit.Assert.*
import org.junit.Test

class CaptureLifecycleTest {
    private fun parent() = Item(id = 11, title = "学编曲", detail = "课程在收藏夹", kind = "收集箱",
        captureRoute = "progress", nextAction = "打开课程")

    @Test fun notesSurviveSchedulePauseAndReturn() {
        val original = parent().copy(captureRoute = "inbox")
        val scheduled = TaskActions.scheduledShape(original, 100000L, 30, "", "mid")
        val paused = TaskActions.pause(listOf(scheduled), scheduled).items.single()
        val returned = TaskActions.returnToInbox(listOf(paused), paused).items.single()
        assertEquals(original.detail, returned.editableNote())
        assertEquals(original.detail, ItemsCodec.decode(ItemsCodec.encode(listOf(returned))).items.single().editableNote())
    }

    @Test fun explicitlyClearedNoteNeverRestoresOldSnapshot() {
        val item = parent().copy(sourceDetail = "旧备注", userNote = "")
        val scheduled = TaskActions.scheduledShape(item, 100000L, 30, "", "mid")
        assertEquals("", ItemsCodec.decode(ItemsCodec.encode(listOf(scheduled))).items.single().editableNote())
    }

    @Test fun oldDraftIsConsumedButNewSameNamedDraftIsPreserved() {
        val parent = parent()
        val child = Item(id = 12, title = parent.nextAction, detail = "", kind = "收集箱", parentCaptureId = 11, done = true)
        val current = ItemsCodec.encode(listOf(parent, child))
        val old = current.replace("\"captureSchemaVersion\":1,", "").replace(",\"captureSchemaVersion\":1", "")
        assertEquals("", ItemsCodec.decode(old).items.first().nextAction)
        assertEquals(parent.nextAction, ItemsCodec.decode(current).items.first().nextAction)
    }

    @Test fun orphanAndNestedLinksAreDetachedWithoutDeletingItems() {
        val parent = parent()
        val nested = parent.copy(id = 12, parentCaptureId = 11)
        val orphan = Item(id = 13, title = "保留", detail = "", kind = "任务", scheduledAt = 456L, parentCaptureId = 999)
        val result = ItemsCodec.decode(ItemsCodec.encode(listOf(parent, nested, orphan)))
        assertEquals(3, result.items.size)
        assertTrue(result.items.all { it.parentCaptureId == null })
        assertEquals(456L, result.items.last().scheduledAt)
    }

    @Test fun goalOriginalNotesSurviveEditingOutcomeAndCodec() {
        val goal = Goal(title = "目标", weeklyTarget = 1, durationMinutes = 30, sourceNotes = "原始说明")
        val edited = goal.copy(desiredOutcome = "新的预期")
        assertEquals("原始说明", StoredGoalsCodec.decodeGoals(StoredGoalsCodec.encodeGoals(listOf(edited))).single().sourceNotes)
    }
}
