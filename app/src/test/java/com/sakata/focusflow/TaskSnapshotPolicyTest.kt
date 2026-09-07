package com.sakata.focusflow

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskSnapshotPolicyTest {
    private val task = Item(id = 1L, title = "任务", detail = "", kind = "任务", scheduledAt = 1000L)

    @Test fun unchangedSnapshotCanSave() {
        assertTrue(TaskSnapshotPolicy.canSave(listOf(task), listOf(task.copy())))
    }

    @Test fun notificationCompletionRejectsOldSnapshot() {
        assertFalse(TaskSnapshotPolicy.canSave(listOf(task), listOf(task.copy(done = true))))
    }

    @Test fun notificationRescheduleRejectsOldSnapshot() {
        assertFalse(TaskSnapshotPolicy.canSave(listOf(task), listOf(task.copy(scheduledAt = 2000L))))
    }

    @Test fun concurrentCaptureOrDeletionRejectsOldSnapshot() {
        assertFalse(TaskSnapshotPolicy.canSave(listOf(task), emptyList()))
        assertFalse(TaskSnapshotPolicy.canSave(emptyList(), listOf(task)))
    }
}
