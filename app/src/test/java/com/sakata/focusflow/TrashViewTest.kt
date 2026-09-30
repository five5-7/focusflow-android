package com.sakata.focusflow

import com.sakata.focusflow.data.TrashGroupRecord
import com.sakata.focusflow.data.TrashJournal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Stage 7.3 recycle-bin presentation model. */
class TrashViewTest {
    private val retention = 30L * 24 * 60 * 60 * 1000
    private val day = 24L * 60 * 60 * 1000

    private fun item(id: Long, kind: String = "任务") =
        Item(id = id, title = "记录$id", detail = "", kind = kind)

    private fun trashed(ids: List<Long>, at: Long): Pair<List<Item>, List<TrashGroupRecord>> {
        val originals = ids.map { item(it) }
        val tombstones = TrashActions.trash(originals, ids.toSet(), at = at).items
        return tombstones to TrashJournal.update(originals, tombstones, emptyList())
    }

    @Test fun `grouped deletions render as one group with members in original order`() {
        val (tombstones, groups) = trashed(listOf(11L, 22L, 33L), at = 1000L)

        val view = TrashViews.build(tombstones, groups)

        assertEquals(1, view.groups.size)
        assertEquals(listOf(11L, 22L, 33L), view.groups.single().members.map { it.id })
        assertEquals(3, view.groups.single().size)
        assertFalse(view.groups.single().partial)
        assertTrue(view.ungrouped.isEmpty())
        assertFalse(view.isEmpty)
    }

    @Test fun `a partially restored group reports how many members came back`() {
        val (tombstones, groups) = trashed(listOf(11L, 22L), at = 1000L)
        val partial = TrashActions.restore(tombstones, setOf(11L), at = 1100L).items
        val marked = TrashJournal.update(tombstones, partial, groups)

        val view = TrashViews.build(partial, marked)

        assertTrue(view.groups.single().partial)
        assertEquals(1, view.groups.single().restoredCount)
        assertEquals(listOf(22L), view.groups.single().members.map { it.id })
    }

    @Test fun `tombstones without a group keep their own fallback section`() {
        // Repeat-template tombstones and pre-stage-7 leftovers are never grouped.
        val legacy = TrashActions.trash(listOf(item(41L), item(42L)), setOf(41L, 42L), at = 1000L).items

        val view = TrashViews.build(legacy, emptyList())

        assertTrue(view.groups.isEmpty())
        assertEquals(listOf(41L, 42L), view.ungrouped.map { it.id })
        assertFalse(view.isEmpty)
    }

    @Test fun `a fully restored group disappears from the recycle bin`() {
        val (tombstones, groups) = trashed(listOf(11L), at = 1000L)
        val restored = TrashActions.restore(tombstones, setOf(11L), at = 1100L).items
        val completed = TrashJournal.update(tombstones, restored, groups)

        assertTrue(TrashViews.build(restored, completed).isEmpty)
    }

    @Test fun `remaining retention label follows the frozen inclusive boundary`() {
        val expiresAt = 1000L + retention

        assertEquals("已到期", TrashViews.remainingLabel(expiresAt, expiresAt))
        assertEquals("已到期", TrashViews.remainingLabel(expiresAt, expiresAt + 1))
        assertEquals("剩余 30 天", TrashViews.remainingLabel(expiresAt, 1000L))
        assertEquals("剩余 1 天", TrashViews.remainingLabel(expiresAt, expiresAt - day))
        assertEquals("不足 1 天", TrashViews.remainingLabel(expiresAt, expiresAt - day + 1))
        assertEquals("不足 1 天", TrashViews.remainingLabel(expiresAt, expiresAt - 1))
    }

    @Test fun `a restored member never appears as a removable trash row`() {
        val (tombstones, groups) = trashed(listOf(11L, 22L), at = 1000L)
        val partial = TrashActions.restore(tombstones, setOf(11L), at = 1100L).items
        val marked = TrashJournal.update(tombstones, partial, groups)

        val view = TrashViews.build(partial, marked)
        val purgeable = TrashJournal.purgeableIds(partial, marked)

        assertEquals(listOf(22L), view.groups.single().members.map { it.id }.filter { it in purgeable })
        assertTrue(view.ungrouped.none { it.id == 11L })
    }

    @Test fun `unlinked members are ignored instead of fabricating a row`() {
        val (tombstones, groups) = trashed(listOf(11L, 22L), at = 1000L)
        // The tombstone of 22 vanished without a purge: the view must not invent a row for it.
        val dangling = tombstones.filterNot { it.id == 22L }

        val view = TrashViews.build(dangling, groups)

        assertEquals(listOf(11L), view.groups.single().members.map { it.id })
        assertTrue(view.ungrouped.isEmpty())
    }

    @Test fun `member order comes from the group not from the item list order`() {
        val (tombstones, groups) = trashed(listOf(11L, 22L), at = 1000L)
        val reversed = tombstones.reversed()
        assertEquals(listOf(22L, 11L), reversed.map { it.id })

        val view = TrashViews.build(reversed, groups)

        assertEquals(listOf(11L, 22L), view.groups.single().members.map { it.id })
    }
}
