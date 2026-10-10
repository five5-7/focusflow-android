package com.sakata.focusflow.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sakata.focusflow.Item
import com.sakata.focusflow.ItemsCodec
import com.sakata.focusflow.PrototypeStore
import com.sakata.focusflow.TaskEventType
import com.sakata.focusflow.TrashActions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Stage 7.3 Legacy path: permanent removal and expiry must stay atomic and restart safe. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class Stage7TrashPurgePersistenceTest {
    private val retention = 30L * 24 * 60 * 60 * 1000

    private fun prefs(context: Context) = context.getSharedPreferences("focusflow", Context.MODE_PRIVATE)

    private fun item(id: Long) = Item(id = id, title = "记录$id", detail = "原文$id", kind = "任务")

    @Test fun `permanent purge survives restart and keeps deletion history`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = prefs(context)
        assertTrue(prefs.edit().clear().commit())
        try {
            val original = item(7201L)
            assertTrue(prefs.edit().putString("items", ItemsCodec.encode(listOf(original))).commit())
            val store = PrototypeStore(context)
            val deleted = TrashActions.trash(listOf(original), setOf(original.id), at = 1000L)
            assertTrue(store.saveItemsAndTaskEvents(deleted.items, deleted.events, listOf(original)))
            assertEquals("active", PrototypeStore(context).loadTrashGroups().single().state)

            assertTrue(PrototypeStore(context).purgeTrash(setOf(original.id)))

            // Restart: nothing recoverable is left, and no dangling group survives.
            val reopened = PrototypeStore(context)
            assertTrue(reopened.loadItems().isEmpty())
            assertTrue(reopened.loadTrashGroups().isEmpty())
            // History is retained on purpose: purge never rewrites task_events.
            assertEquals(listOf(TaskEventType.TASK_DELETED),
                reopened.loadTaskEvents().filter { it.itemId == original.id }.map { it.type })
        } finally { prefs.edit().clear().commit() }
    }

    @Test fun `expiry sweep removes only the group that reached its retention`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = prefs(context)
        assertTrue(prefs.edit().clear().commit())
        try {
            val first = item(7211L)
            val second = item(7212L)
            assertTrue(prefs.edit()
                .putString("items", ItemsCodec.encode(listOf(first, second))).commit())
            val store = PrototypeStore(context)

            val firstDelete = TrashActions.trash(listOf(first, second), setOf(first.id), at = 1000L)
            assertTrue(store.saveItemsAndTaskEvents(firstDelete.items, firstDelete.events, listOf(first, second)))
            val secondDelete = TrashActions.trash(firstDelete.items, setOf(second.id), at = 1000L + retention)
            assertTrue(store.saveItemsAndTaskEvents(secondDelete.items, secondDelete.events, firstDelete.items))
            assertEquals(2, store.loadTrashGroups().size)

            // Exactly at the first group's expiresAt: it goes, the newer one stays.
            assertTrue(PrototypeStore(context).purgeExpiredTrash(1000L + retention))

            val reopened = PrototypeStore(context)
            assertEquals(listOf(second.id), reopened.loadItems().map { it.id })
            assertEquals(listOf(second.id), reopened.loadTrashGroups().single().members.map { it.itemId })
            assertEquals(2, reopened.loadTaskEvents().count { it.type == TaskEventType.TASK_DELETED })
        } finally { prefs.edit().clear().commit() }
    }

    @Test fun `expiry sweep never touches a tombstone that has no group`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = prefs(context)
        assertTrue(prefs.edit().clear().commit())
        try {
            val legacy = TrashActions.trash(listOf(item(7221L)), setOf(7221L), at = 1000L).items
            assertTrue(prefs.edit().putString("items", ItemsCodec.encode(legacy)).commit())

            assertTrue(PrototypeStore(context).purgeExpiredTrash(Long.MAX_VALUE))

            // No trustworthy deletedAt exists, so it must stay recoverable forever.
            val reopened = PrototypeStore(context)
            assertEquals(listOf(7221L), reopened.loadItems().map { it.id })
            assertEquals(1, TrashActions.restore(reopened.loadItems(), setOf(7221L), at = 2000L).events.size)
        } finally { prefs.edit().clear().commit() }
    }

    @Test fun `purge refuses to run on a corrupt journal and leaves stored bytes untouched`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = prefs(context)
        assertTrue(prefs.edit().clear().commit())
        try {
            val original = item(7231L)
            val tombstones = TrashActions.trash(listOf(original), setOf(original.id), at = 1000L).items
            val raw = ItemsCodec.encode(tombstones)
            assertTrue(prefs.edit().putString("items", raw).putString("trash_groups_v1", "bad-json").commit())

            assertFalse(PrototypeStore(context).purgeTrash(setOf(original.id)))

            assertEquals(raw, prefs.getString("items", null))
            assertEquals("bad-json", prefs.getString("trash_groups_v1", null))
        } finally { prefs.edit().clear().commit() }
    }

    @Test fun `purge keeps the live item of an already restored member`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = prefs(context)
        assertTrue(prefs.edit().clear().commit())
        try {
            val first = item(7241L)
            val second = item(7242L)
            assertTrue(prefs.edit().putString("items", ItemsCodec.encode(listOf(first, second))).commit())
            val store = PrototypeStore(context)
            val deleted = TrashActions.trash(listOf(first, second), setOf(first.id, second.id), at = 1000L)
            assertTrue(store.saveItemsAndTaskEvents(deleted.items, deleted.events, listOf(first, second)))

            val restored = TrashActions.restore(deleted.items, setOf(first.id), at = 1100L)
            assertTrue(PrototypeStore(context).saveItemsAndTaskEvents(restored.items, restored.events, deleted.items))
            assertEquals("partial", PrototypeStore(context).loadTrashGroups().single().state)

            assertTrue(PrototypeStore(context).purgeTrash(setOf(second.id)))

            val reopened = PrototypeStore(context)
            assertEquals(listOf(first), reopened.loadItems())
            assertEquals("restored", reopened.loadTrashGroups().single().state)
            assertEquals(setOf(first.id), reopened.loadTrashGroups().single().restoredIds)
        } finally { prefs.edit().clear().commit() }
    }

    @Test fun `expiry sweep never uses a historical restored group to delete a re-deleted item`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = prefs(context)
        assertTrue(prefs.edit().clear().commit())
        try {
            val original = item(7261L)
            assertTrue(prefs.edit().putString("items", ItemsCodec.encode(listOf(original))).commit())
            val store = PrototypeStore(context)
            // 1) delete, 2) restore (the group becomes historical), 3) delete again two minutes later.
            val first = TrashActions.trash(listOf(original), setOf(original.id), at = 1000L)
            assertTrue(store.saveItemsAndTaskEvents(first.items, first.events, listOf(original)))
            val restored = TrashActions.restore(first.items, setOf(original.id), at = 2000L)
            assertTrue(store.saveItemsAndTaskEvents(restored.items, restored.events, first.items))
            assertEquals("restored", store.loadTrashGroups().single().state)
            val redeletedAt = 1000L + 2 * 60_000L
            val second = TrashActions.trash(restored.items, setOf(original.id), at = redeletedAt)
            assertTrue(store.saveItemsAndTaskEvents(second.items, second.events, restored.items))
            assertEquals(2, store.loadTrashGroups().size)

            // The historical group expires first, but the item must survive: its own window is newer.
            assertTrue(PrototypeStore(context).purgeExpiredTrash(1000L + retention))
            assertEquals(listOf(original.id), PrototypeStore(context).loadItems().map { it.id })

            // Only its own group's expiry may sweep it.
            assertTrue(PrototypeStore(context).purgeExpiredTrash(redeletedAt + retention))
            assertTrue(PrototypeStore(context).loadItems().isEmpty())
        } finally { prefs.edit().clear().commit() }
    }

    @Test fun `repeated purge of the same id is a no-op success`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = prefs(context)
        assertTrue(prefs.edit().clear().commit())
        try {
            val original = item(7251L)
            assertTrue(prefs.edit().putString("items", ItemsCodec.encode(listOf(original))).commit())
            val store = PrototypeStore(context)
            val deleted = TrashActions.trash(listOf(original), setOf(original.id), at = 1000L)
            assertTrue(store.saveItemsAndTaskEvents(deleted.items, deleted.events, listOf(original)))

            assertTrue(PrototypeStore(context).purgeTrash(setOf(original.id)))
            val afterFirst = prefs.getString("items", null)
            assertTrue(PrototypeStore(context).purgeTrash(setOf(original.id)))
            assertEquals(afterFirst, prefs.getString("items", null))
            assertTrue(PrototypeStore(context).loadTrashGroups().isEmpty())
            assertNull(PrototypeStore(context).loadItems().firstOrNull { it.id == original.id })
        } finally { prefs.edit().clear().commit() }
    }
}
