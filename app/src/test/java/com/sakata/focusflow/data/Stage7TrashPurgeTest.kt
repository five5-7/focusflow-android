package com.sakata.focusflow.data

import com.sakata.focusflow.Item
import com.sakata.focusflow.ItemsCodec
import com.sakata.focusflow.TrashActions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Stage 7.3 contract gates for permanent removal and expiry; see docs/9.0-stage7-3-data-contract.md. */
class Stage7TrashPurgeTest {
    private val retention = 30L * 24 * 60 * 60 * 1000

    private fun ordinary(vararg ids: Long) = ids.map { id ->
        Item(id = id, title = "记录$id", detail = "原文$id", kind = "任务")
    }

    /** Returns the persisted tombstone list together with the journal group it produced. */
    private fun trashed(original: List<Item>, ids: Set<Long>, at: Long): Pair<List<Item>, List<TrashGroupRecord>> {
        val tombstones = TrashActions.trash(original, ids, at = at).items
        return tombstones to TrashJournal.update(original, tombstones, emptyList())
    }

    @Test fun `expiry boundary is inclusive at expiresAt and exclusive one millisecond earlier`() {
        val originals = ordinary(11L)
        val (tombstones, groups) = trashed(originals, setOf(11L), at = 1000L)
        val expiresAt = 1000L + retention
        assertEquals(expiresAt, groups.single().expiresAt)

        // Freezes the 1 ms decision the contract made; a `<` implementation fails here.
        assertTrue(TrashJournal.expiredIds(tombstones, groups, expiresAt - 1).isEmpty())
        assertEquals(setOf(11L), TrashJournal.expiredIds(tombstones, groups, expiresAt))
        assertEquals(setOf(11L), TrashJournal.expiredIds(tombstones, groups, expiresAt + 1))

        // "Retained" must mean fully recoverable, not merely unselected.
        val kept = tombstones.filterNot { it.id in TrashJournal.expiredIds(tombstones, groups, expiresAt - 1) }
        assertEquals(tombstones, kept)
        assertEquals(1, TrashActions.restore(kept, setOf(11L), at = expiresAt - 1).events.size)
    }

    @Test fun `expiry sweeps the whole group and leaves no empty group record behind`() {
        val originals = ordinary(11L, 22L)
        val (tombstones, groups) = trashed(originals, setOf(11L, 22L), at = 1000L)
        val expiresAt = 1000L + retention

        val ids = TrashJournal.expiredIds(tombstones, groups, expiresAt)
        val after = tombstones.filterNot { it.id in ids }
        val purged = TrashJournal.purge(tombstones, after, groups, ids)

        assertTrue(after.isEmpty())
        assertTrue(purged.isEmpty())
    }

    @Test fun `replaying the same purge on an already purged state changes nothing`() {
        val originals = ordinary(11L, 22L)
        val (tombstones, groups) = trashed(originals, setOf(11L, 22L), at = 1000L)
        val after = tombstones.filterNot { it.id == 11L }
        val once = TrashJournal.purge(tombstones, after, groups, setOf(11L))
        assertEquals(listOf(22L), once.single().members.map { it.itemId })

        // Replaying the same id against the already-purged state must not shrink the group further.
        val twice = TrashJournal.purge(after, after, once, setOf(11L))
        assertEquals(once, twice)
        assertEquals(listOf(22L), twice.single().members.map { it.itemId })
        assertEquals(TrashJournalCodec.encode(once), TrashJournalCodec.encode(twice))
        // The surviving member is still sweepable at its own expiry.
        assertEquals(setOf(22L), TrashJournal.expiredIds(after, twice, 1000L + retention))
    }

    @Test fun `purging one member of a partial group keeps the restored live item and the group`() {
        val originals = ordinary(11L, 22L)
        val (tombstones, groups) = trashed(originals, setOf(11L, 22L), at = 1000L)
        val partial = TrashActions.restore(tombstones, setOf(11L), at = 1100L).items
        val marked = TrashJournal.update(tombstones, partial, groups)
        assertEquals("partial", marked.single().state)

        val after = partial.filterNot { it.id == 22L }
        val purged = TrashJournal.purge(partial, after, marked, setOf(22L))

        assertEquals(1, purged.size)
        assertEquals(listOf(11L), purged.single().members.map { it.itemId })
        assertEquals(setOf(11L), purged.single().restoredIds)
        assertEquals("restored", purged.single().state)
        // 11 was restored, so it is a live ordinary item and must survive untouched.
        assertEquals("任务", after.single { it.id == 11L }.kind)
        assertEquals(originals.single { it.id == 11L }, after.single { it.id == 11L })
    }

    @Test fun `a restored member can never be purged`() {
        val originals = ordinary(11L, 22L)
        val (tombstones, groups) = trashed(originals, setOf(11L, 22L), at = 1000L)
        val partial = TrashActions.restore(tombstones, setOf(11L), at = 1100L).items
        val marked = TrashJournal.update(tombstones, partial, groups)

        assertTrue(TrashJournal.purgeableIds(partial, marked).none { it == 11L })
        assertThrows(IllegalArgumentException::class.java) {
            TrashJournal.purge(partial, partial.filterNot { it.id == 11L }, marked, setOf(11L))
        }
    }

    @Test fun `tombstones without a group are never swept but stay manually removable`() {
        val legacy = TrashActions.trash(ordinary(31L), setOf(31L), at = 1000L).items
        // Pre-stage-7 data: the tombstone already exists and no group was ever recorded for it.
        assertTrue(TrashJournal.update(legacy, legacy, emptyList()).isEmpty())
        assertEquals(1, TrashActions.restore(legacy, setOf(31L), at = 1100L).events.size)

        assertTrue(TrashJournal.expiredIds(legacy, emptyList(), Long.MAX_VALUE).isEmpty())
        assertEquals(setOf(31L), TrashJournal.purgeableIds(legacy, emptyList()))
        assertTrue(TrashJournal.purge(legacy, emptyList(), emptyList(), setOf(31L)).isEmpty())
    }

    @Test fun `repeat generated tombstones are out of scope for both sweep and manual purge`() {
        val occurrence = Item(id = 41L, title = "重复任务", detail = "", kind = "任务",
            repeatTemplateId = 7L, repeatOccurrenceDay = 100L)
        val tombstones = TrashActions.trash(listOf(occurrence), setOf(41L), at = 1000L).items
        val groups = TrashJournal.update(listOf(occurrence), tombstones, emptyList())
        assertEquals(1, groups.size)

        assertTrue(TrashJournal.purgeableIds(tombstones, groups).isEmpty())
        assertTrue(TrashJournal.expiredIds(tombstones, groups, Long.MAX_VALUE).isEmpty())
        assertThrows(IllegalArgumentException::class.java) {
            TrashJournal.purge(tombstones, emptyList(), groups, setOf(41L))
        }
    }

    @Test fun `purge fails closed on a live item a tampered snapshot or a stale after list`() {
        val originals = ordinary(11L)
        val (tombstones, groups) = trashed(originals, setOf(11L), at = 1000L)

        // A live item is not a tombstone.
        assertThrows(IllegalArgumentException::class.java) {
            TrashJournal.purge(originals, emptyList(), emptyList(), setOf(11L))
        }
        // Snapshot no longer matches the group member.
        assertThrows(IllegalArgumentException::class.java) {
            TrashJournal.purge(tombstones.map { it.copy(trashSnapshot = "broken") },
                emptyList(), groups, setOf(11L))
        }
        // Caller forgot to actually drop the tombstone.
        assertThrows(IllegalArgumentException::class.java) {
            TrashJournal.purge(tombstones, tombstones, groups, setOf(11L))
        }
    }

    @Test fun `purged journal still round trips through the strict codec`() {
        val originals = ordinary(11L, 22L)
        val (tombstones, groups) = trashed(originals, setOf(11L, 22L), at = 1000L)
        val after = tombstones.filterNot { it.id == 11L }
        val purged = TrashJournal.purge(tombstones, after, groups, setOf(11L))

        assertEquals(purged, TrashJournalCodec.decode(TrashJournalCodec.encode(purged)))
        assertEquals(listOf(22L), purged.single().members.map { it.itemId })
    }

    @Test fun `a sweep that lost the race to a restore fails closed and keeps the live item`() {
        val originals = ordinary(11L)
        val (tombstones, groups) = trashed(originals, setOf(11L), at = 1000L)

        // The sweep picked its target from the pre-restore state...
        assertEquals(setOf(11L), TrashJournal.expiredIds(tombstones, groups, 1000L + retention))
        // ...but the restore landed first, so the id now points at a live item.
        val restored = TrashActions.restore(tombstones, setOf(11L), at = 1100L).items
        val completed = TrashJournal.update(tombstones, restored, groups)

        assertThrows(IllegalArgumentException::class.java) {
            TrashJournal.purge(restored, restored.filterNot { it.id == 11L }, completed, setOf(11L))
        }
        // Nothing was removed and nothing was rewritten: the user's item is intact.
        assertEquals("任务", restored.single().kind)
        assertEquals(originals.single(), restored.single())
        assertTrue(TrashJournal.purgeableIds(restored, completed).isEmpty())
    }

    @Test fun `an item deleted restored and deleted again stays purgeable and sweepable`() {
        val originals = ordinary(11L)
        // 1) delete -> G1
        val firstTombstones = TrashActions.trash(originals, setOf(11L), at = 1000L).items
        val g1 = TrashJournal.update(originals, firstTombstones, emptyList())
        // 2) full restore -> G1 becomes historical (state = restored)
        val restored = TrashActions.restore(firstTombstones, setOf(11L), at = 1100L).items
        val g1Restored = TrashJournal.update(firstTombstones, restored, g1)
        assertEquals("restored", g1Restored.single().state)
        // 3) delete the same id again -> G2 coexists with the historical G1
        val secondTombstones = TrashActions.trash(restored, setOf(11L), at = 2000L).items
        val both = TrashJournal.update(restored, secondTombstones, g1Restored)
        assertEquals(2, both.size)

        // The bin offers purge and the sweep selects it, so both must actually work.
        assertEquals(setOf(11L), TrashJournal.purgeableIds(secondTombstones, both))
        assertEquals(setOf(11L), TrashJournal.expiredIds(secondTombstones, both, 2000L + retention))

        val after = secondTombstones.filterNot { it.id == 11L }
        val purged = TrashJournal.purge(secondTombstones, after, both, setOf(11L))

        assertTrue(after.isEmpty())
        assertTrue(purged.none { group -> group.members.any { it.itemId !in group.restoredIds } })
        assertEquals(purged, TrashJournalCodec.decode(TrashJournalCodec.encode(purged)))
    }

    @Test fun `a deleted repeat rule template stays restorable never auto swept but manually purgeable`() {
        // Mirrors RepeatActions.deleteRule: a 重复模板 snapshot, no journal group.
        val template = Item(id = 51L, title = "每天", detail = "每天重复", kind = "重复模板",
            repeatFrequency = "daily", repeatStartDay = 100L, repeatMinute = 480)
        val tombstone = template.copy(kind = "回收站", detail = "重复规则已删除", trashedAt = 1000L,
            trashSnapshot = ItemsCodec.encode(listOf(template)), repeatFrequency = "")

        // No group, so the expiry sweep must never touch it.
        assertTrue(TrashJournal.expiredIds(listOf(tombstone), emptyList(), Long.MAX_VALUE).isEmpty())
        // The fallback section offers manual removal, and the data layer must honour it.
        assertEquals(setOf(51L), TrashJournal.purgeableIds(listOf(tombstone), emptyList()))
        assertTrue(TrashJournal.purge(listOf(tombstone), emptyList(), emptyList(), setOf(51L)).isEmpty())
    }

    @Test fun `a historical restored group must not expire the item that was trashed again later`() {
        val originals = ordinary(11L)
        val firstTombstones = TrashActions.trash(originals, setOf(11L), at = 1000L).items
        val g1 = TrashJournal.update(originals, firstTombstones, emptyList())
        val restored = TrashActions.restore(firstTombstones, setOf(11L), at = 2000L).items
        val g1Restored = TrashJournal.update(firstTombstones, restored, g1)
        assertEquals("restored", g1Restored.single().state)
        // Re-deleted two minutes later: its own retention window starts now, not at t0.
        val redeletedAt = 1000L + 2 * 60_000L
        val second = TrashActions.trash(restored, setOf(11L), at = redeletedAt).items
        val both = TrashJournal.update(restored, second, g1Restored)
        assertEquals(2, both.size)

        // G1's expiry must NOT sweep the item: that retention belongs to the historical group.
        assertTrue(TrashJournal.expiredIds(second, both, 1000L + retention).isEmpty())
        // It becomes sweepable only at its own group's expiry.
        assertEquals(setOf(11L), TrashJournal.expiredIds(second, both, redeletedAt + retention))
    }

    @Test fun `codec refuses to write restored ids that have no member`() {
        val snapshot = ItemsCodec.encode(ordinary(11L))
        val dangling = TrashGroupRecord(
            groupId = "g", kind = "ordinary_items", deletedAt = 1000L, expiresAt = 1000L + retention,
            members = listOf(TrashMember(11L, 0, snapshot)), restoredIds = setOf(22L)
        )
        assertThrows(IllegalArgumentException::class.java) { TrashJournalCodec.encode(listOf(dangling)) }
    }

    @Test fun `purgeable ids exclude the tombstone that still has a live sibling`() {
        val originals = ordinary(11L, 22L)
        val (tombstones, groups) = trashed(originals, setOf(11L, 22L), at = 1000L)
        val partial = TrashActions.restore(tombstones, setOf(11L), at = 1100L).items
        val marked = TrashJournal.update(tombstones, partial, groups)

        assertEquals(setOf(22L), TrashJournal.purgeableIds(partial, marked))
        assertFalse(TrashJournal.purgeableIds(partial, marked).contains(11L))
    }
}
