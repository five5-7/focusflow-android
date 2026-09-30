package com.sakata.focusflow

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sakata.focusflow.data.CoreDataReadResult
import com.sakata.focusflow.data.CoreDataRepository
import com.sakata.focusflow.data.CoreDataWriteResult
import com.sakata.focusflow.data.CoreDataWriteStatus
import com.sakata.focusflow.data.LegacyCoreDataRepository
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class StandaloneInboxTransferTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private fun newReminder(): StandaloneReminder {
        context.getSharedPreferences("standalone_reminders", Context.MODE_PRIVATE).edit().clear().commit()
        assertTrue(StandaloneReminders.create(context, "检查实验", 1_800_000_060_000L, 1_800_000_000_000L))
        return StandaloneReminders.all(context).single()
    }

    @Test fun `moving once writes one capture and one creation event then consumes the reminder`() {
        val reminder = newReminder()
        val repository = LegacyCoreDataRepository(PrototypeStore(context))

        assertEquals(StandaloneInboxTransfer.Result.MOVED,
            StandaloneInboxTransfer.move(context, repository, reminder.id, reminder.scheduledAt))
        assertEquals(StandaloneInboxTransfer.Result.STALE,
            StandaloneInboxTransfer.move(context, repository, reminder.id, reminder.scheduledAt))

        val snapshot = (repository.read() as CoreDataReadResult.Ready).snapshot
        assertEquals(1, snapshot.items.count { it.id == reminder.id && it.captureRoute == CaptureRoute.INBOX.storageKey })
        assertEquals(1, snapshot.taskEvents.count { it.itemId == reminder.id && it.type == TaskEventType.TASK_CREATED })
        assertNotNull(StandaloneReminders.all(context).single().completedAt)
    }

    @Test fun `failed task write leaves reminder live and retry creates one item`() {
        val reminder = newReminder()
        val repository = LegacyCoreDataRepository(PrototypeStore(context))
        val failing = object : CoreDataRepository by repository {
            override fun replaceTasksAndAppendEvents(
                tasks: List<Item>, events: List<TaskEvent>, expectedTasks: List<Item>
            ) = CoreDataWriteResult(CoreDataWriteStatus.WRITE_FAILED)
        }

        assertEquals(StandaloneInboxTransfer.Result.WRITE_FAILED,
            StandaloneInboxTransfer.move(context, failing, reminder.id, reminder.scheduledAt))
        assertNull(StandaloneReminders.all(context).single().completedAt)
        assertEquals(StandaloneInboxTransfer.Result.MOVED,
            StandaloneInboxTransfer.move(context, repository, reminder.id, reminder.scheduledAt))
        assertEquals(1, (repository.read() as CoreDataReadResult.Ready).snapshot.items.count { it.id == reminder.id })
    }

    @Test fun `unrelated task with same id is never overwritten`() {
        val reminder = newReminder()
        val repository = LegacyCoreDataRepository(PrototypeStore(context))
        val existing = Item(id = reminder.id, title = "原有事项", detail = "", kind = "任务")
        assertTrue(repository.replaceTasks(listOf(existing), emptyList()).applied)

        assertEquals(StandaloneInboxTransfer.Result.ID_COLLISION,
            StandaloneInboxTransfer.move(context, repository, reminder.id, reminder.scheduledAt))
        assertEquals(existing, (repository.read() as CoreDataReadResult.Ready).snapshot.items.single())
        assertNull(StandaloneReminders.all(context).single().completedAt)
    }
    @Test fun `creation history prevents a second capture after interrupted completion and deletion`() {
        val reminder = newReminder()
        val repository = LegacyCoreDataRepository(PrototypeStore(context))
        val label = "来自自定义提醒 #${reminder.id}"
        val item = Item(id = reminder.id, title = reminder.title, detail = "", kind = "收集箱",
            sourceDetail = label)
        assertTrue(repository.replaceTasksAndAppendEvents(listOf(item),
            listOf(TaskRecorder.event(TaskEventType.TASK_CREATED, item.id, item.title,
                extra = label)), emptyList()).applied)
        assertTrue(repository.replaceTasks(emptyList(), listOf(item)).applied)

        assertEquals(StandaloneInboxTransfer.Result.ALREADY_MOVED,
            StandaloneInboxTransfer.move(context, repository, reminder.id, reminder.scheduledAt))
        val snapshot = (repository.read() as CoreDataReadResult.Ready).snapshot
        assertTrue(snapshot.items.isEmpty())
        assertEquals(1, snapshot.taskEvents.count { it.itemId == reminder.id &&
            it.type == TaskEventType.TASK_CREATED })
        assertNotNull(StandaloneReminders.all(context).single().completedAt)
    }
}
