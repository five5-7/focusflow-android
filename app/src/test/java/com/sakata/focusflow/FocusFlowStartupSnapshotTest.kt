package com.sakata.focusflow

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sakata.focusflow.data.CoreDataReadResult
import com.sakata.focusflow.data.CoreDataRepository
import com.sakata.focusflow.data.LegacyCoreDataRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = FocusFlowApplication::class)
class FocusFlowStartupSnapshotTest {
    private lateinit var store: PrototypeStore
    private lateinit var repository: CoreDataRepository

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("focusflow", Context.MODE_PRIVATE).edit().clear().commit()
        store = PrototypeStore(context)
        repository = LegacyCoreDataRepository(store)
        assertTrue(repository.replaceTasks(listOf(Item(42L, "保留的记录", "", "收集箱")), emptyList()).applied)
    }

    @Test fun failedReadAfterRuntimeResolutionBlocksStartupWithoutDeletingData() {
        val unreadable = object : CoreDataRepository by repository {
            override fun read(): CoreDataReadResult = CoreDataReadResult.Invalid("snapshot unavailable")
        }
        val result = FocusFlowStartupSnapshot.load(store, unreadable)
        assertEquals(FocusFlowStartupLoad.Blocked("snapshot unavailable"), result)
        assertEquals("保留的记录", store.loadItems().single().title)
    }

    @Test fun notReadySourceBlocksInsteadOfCastingToReady() {
        val notReady = object : CoreDataRepository by repository {
            override fun read(): CoreDataReadResult = CoreDataReadResult.NotReady("migration incomplete")
        }
        assertEquals(FocusFlowStartupLoad.Blocked("migration incomplete"), FocusFlowStartupSnapshot.load(store, notReady))
    }

    @Test fun healthySnapshotPreservesExistingCoreData() {
        val result = FocusFlowStartupSnapshot.load(store, repository)
        assertTrue(result is FocusFlowStartupLoad.Ready)
        assertEquals(42L, (result as FocusFlowStartupLoad.Ready).snapshot.items.single().id)
    }
}
