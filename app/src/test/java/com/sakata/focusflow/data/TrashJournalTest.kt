package com.sakata.focusflow.data

import com.sakata.focusflow.Item
import com.sakata.focusflow.TrashActions
import org.junit.Assert.*
import org.junit.Test

class TrashJournalTest {
    @Test fun `batch delete records original order and survives strict json reload and partial restore`() {
        val original = listOf(Item(id = 11, title = "A", detail = "A note", kind = "任务"),
            Item(id = 22, title = "B", detail = "B note", kind = "收集箱"),
            Item(id = 33, title = "C", detail = "", kind = "任务"))
        val tombstones = TrashActions.trash(original, setOf(11L, 22L), at = 1000).items
        val saved = TrashJournalCodec.decode(TrashJournalCodec.encode(
            TrashJournal.update(original, tombstones, emptyList())))
        assertEquals(1, saved.size)
        assertEquals(listOf(11L, 22L), saved.single().members.map { it.itemId })
        assertEquals(listOf(0, 1), saved.single().members.map { it.sourceOrder })
        assertEquals(1000L + 30L * 24 * 60 * 60 * 1000, saved.single().expiresAt)
        val partial = TrashActions.restore(tombstones, setOf(11L), at = 1100).items
        val marked = TrashJournal.update(tombstones, partial, saved)
        assertEquals("partial", marked.single().state)
        assertEquals(setOf(11L), marked.single().restoredIds)
        val full = TrashActions.restore(partial, setOf(22L), at = 1200).items
        assertEquals("restored", TrashJournal.update(partial, full, marked).single().state)
    }

    @Test fun `legacy tombstone can restore without inventing a group and later edit remains valid`() {
        val initial = listOf(Item(id = 11, title = "A", detail = "", kind = "任务"))
        val tombstone = TrashActions.trash(initial, setOf(11L), at = 1000).items
        val restored = TrashActions.restore(tombstone, setOf(11L), at = 1100).items
        assertTrue(TrashJournal.update(tombstone, restored, emptyList()).isEmpty())
        val newGroup = TrashJournal.update(initial, tombstone, emptyList())
        val completed = TrashJournal.update(tombstone, restored, newGroup)
        assertEquals(completed, TrashJournal.update(restored, restored.map { it.copy(title = "edited") }, completed))
    }

    @Test fun `broken snapshot and active member removal fail closed`() {
        val initial = listOf(Item(id = 11, title = "A", detail = "", kind = "任务"))
        val tombstone = TrashActions.trash(initial, setOf(11L), at = 1000).items
        val group = TrashJournal.update(initial, tombstone, emptyList())
        assertThrows(IllegalArgumentException::class.java) {
            TrashJournal.update(tombstone, emptyList(), group)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TrashJournal.update(initial, tombstone.map { it.copy(trashSnapshot = "broken") }, emptyList())
        }
        assertThrows(Exception::class.java) { TrashJournalCodec.decode("not-json") }
    }
}
