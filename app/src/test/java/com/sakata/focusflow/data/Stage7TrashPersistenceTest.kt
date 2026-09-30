package com.sakata.focusflow.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sakata.focusflow.Item
import com.sakata.focusflow.ItemsCodec
import com.sakata.focusflow.PrototypeStore
import com.sakata.focusflow.TrashActions
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class Stage7TrashPersistenceTest {
    @Test fun `legacy delete and restore atomically persist group and keep detached history out of trash`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("focusflow", Context.MODE_PRIVATE)
        assertTrue(prefs.edit().clear().commit())
        try {
            val original = Item(id = 7101, title = "备忘", detail = "原文", kind = "任务", userNote = "完整备注")
            assertTrue(prefs.edit().putString("items", ItemsCodec.encode(listOf(original))).commit())
            val store = PrototypeStore(context)
            val deleted = TrashActions.trash(listOf(original), setOf(original.id), at = 1000)
            assertTrue(store.saveItemsAndTaskEvents(deleted.items, deleted.events, listOf(original)))
            val reopened = PrototypeStore(context)
            assertEquals("active", reopened.loadTrashGroups().single().state)
            assertEquals(original, ItemsCodec.decode(reopened.loadTrashGroups().single().members.single().snapshot).items.single())
            val stale = TrashActions.restore(deleted.items, setOf(original.id), at = 2000)
            assertFalse(reopened.saveItemsAndTaskEvents(stale.items, stale.events, listOf(original)))
            assertEquals("active", reopened.loadTrashGroups().single().state)
            assertTrue(reopened.saveItemsAndTaskEvents(stale.items, stale.events, deleted.items))
            assertEquals("restored", PrototypeStore(context).loadTrashGroups().single().state)
            assertEquals(original, PrototypeStore(context).loadItems().single())
            val migrated = LegacyPreferencesReader.fromContext(context).read() as LegacyReadResult.Success
            assertEquals(1, migrated.snapshot.trashGroups.size)
            assertEquals(original.id, migrated.snapshot.tasks.single().id)
        } finally { prefs.edit().clear().commit() }
    }

    @Test fun `corrupt group blocks deletion and migration without replacing old data`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("focusflow", Context.MODE_PRIVATE)
        assertTrue(prefs.edit().clear().commit())
        try {
            val original = Item(id = 7111, title = "备忘", detail = "", kind = "任务")
            val raw = ItemsCodec.encode(listOf(original))
            assertTrue(prefs.edit().putString("items", raw).putString("trash_groups_v1", "bad-json").commit())
            val deleted = TrashActions.trash(listOf(original), setOf(original.id), at = 1000)
            assertFalse(PrototypeStore(context).saveItemsAndTaskEvents(deleted.items, deleted.events, listOf(original)))
            assertEquals(raw, prefs.getString("items", null))
            assertEquals("bad-json", prefs.getString("trash_groups_v1", null))
            val read = LegacyPreferencesReader.fromContext(context).read() as LegacyReadResult.Failure
            assertEquals(LegacyPreferencesReader.KEY_TRASH_GROUPS, read.domain)
        } finally { prefs.edit().clear().commit() }
    }
}
