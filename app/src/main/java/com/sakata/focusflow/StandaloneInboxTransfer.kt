package com.sakata.focusflow

import android.content.Context
import com.sakata.focusflow.data.CoreDataReadResult
import com.sakata.focusflow.data.CoreDataRepository

/** Keeps a notification's identity across retries without changing the existing task schema. */
internal object StandaloneInboxTransfer {
    internal enum class Result { MOVED, ALREADY_MOVED, STALE, ID_COLLISION, WRITE_FAILED }

    private fun sourceLabel(id: Long) = "来自自定义提醒 #$id"

    /**
     * A task and an independent reminder live in different stores. Write the task first, using the
     * reminder ID as its stable task ID; only then complete the reminder. If completion fails, a
     * retry finds the same task instead of creating another one. An unrelated task with that ID
     * is a conflict, never overwritten. All reminder actions share the object's process lock.
     */
    fun move(context: Context, repository: CoreDataRepository, id: Long, expectedAt: Long): Result =
        synchronized(StandaloneReminders) {
            val reminder = StandaloneReminders.all(context).singleOrNull {
                it.id == id && it.scheduledAt == expectedAt && it.completedAt == null
            } ?: return@synchronized Result.STALE
            val snapshot = (repository.read() as? CoreDataReadResult.Ready)?.snapshot
                ?: return@synchronized Result.WRITE_FAILED
            val current = snapshot.items.firstOrNull { it.id == id }
            val recorded = snapshot.taskEvents.any { it.itemId == id &&
                it.type == TaskEventType.TASK_CREATED && it.extra == sourceLabel(id) }
            if (current != null && current.sourceDetail != sourceLabel(id)) {
                return@synchronized Result.ID_COLLISION
            }
            if (current == null && !recorded) {
                val draft = Item(id = id, title = reminder.title, detail = "", kind = "收集箱",
                    sourceDetail = sourceLabel(id), captureRoute = CaptureRoute.INBOX.storageKey)
                val saved = repository.replaceTasksAndAppendEvents(
                    listOf(draft) + snapshot.items,
                    listOf(TaskRecorder.event(TaskEventType.TASK_CREATED, id, draft.title,
                        extra = sourceLabel(id))),
                    snapshot.items
                )
                if (!saved.applied) return@synchronized Result.WRITE_FAILED
            }
            if (!StandaloneReminders.complete(context, id, expectedAt)) return@synchronized Result.WRITE_FAILED
            StandaloneReminders.cancel(context, reminder)
            context.getSystemService(android.app.NotificationManager::class.java)
                .cancel(StandaloneReminders.notificationTag(id), 0)
            if (current == null && !recorded) Result.MOVED else Result.ALREADY_MOVED
        }
}
