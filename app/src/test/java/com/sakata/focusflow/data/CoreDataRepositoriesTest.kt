package com.sakata.focusflow.data

import com.sakata.focusflow.Goal
import com.sakata.focusflow.Item
import com.sakata.focusflow.TaskEvent
import com.sakata.focusflow.TaskEventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreDataRepositoriesTest {
    @Test
    fun `Room repository round trip preserves legacy values and source order`() {
        val legacy = sampleSnapshot()
        val source = sourceFor(legacy)

        val result = RoomCoreDataReadRepository(source).read() as CoreDataReadResult.Ready

        assertEquals(legacy, result.snapshot)
        assertEquals(listOf(90L, 3L), result.snapshot.items.map { it.id })
        assertEquals(listOf(800L, 2L), result.snapshot.taskEvents.map { it.id })
        assertEquals(listOf(70L, 4L), result.snapshot.goals.map { it.id })
        assertEquals(
            CoreDataConsistencyStatus.CONSISTENT,
            CoreDataConsistencyChecker.compare(legacy, result).status
        )
    }

    @Test
    fun `content mismatch report contains only metadata and IDs`() {
        val legacy = sampleSnapshot()
        val changed = legacy.copy(
            items = legacy.items.mapIndexed { index, item ->
                if (index == 0) item.copy(title = "private changed title") else item
            }
        )

        val report = CoreDataConsistencyChecker.compare(
            legacy,
            RoomCoreDataReadRepository(sourceFor(changed)).read()
        )

        assertEquals(CoreDataConsistencyStatus.MISMATCH, report.status)
        assertEquals("content differs", report.differences.single().reason)
        assertEquals(listOf(90L), report.differences.single().affectedIds)
        assertFalse(report.toString().contains("private"))
        assertFalse(report.toString().contains(legacy.items.first().title))
    }

    @Test
    fun `same records in a different order are not accepted as consistent`() {
        val legacy = sampleSnapshot()
        val reordered = legacy.copy(items = legacy.items.reversed())

        val report = CoreDataConsistencyChecker.compare(
            legacy,
            CoreDataReadResult.Ready(reordered)
        )

        assertEquals(CoreDataConsistencyStatus.MISMATCH, report.status)
        assertEquals("order differs", report.differences.single().reason)
    }

    @Test
    fun `missing migration state keeps Room shadow out of service`() {
        val result = RoomCoreDataReadRepository(FakeRoomCoreDataSource()).read()
        val report = CoreDataConsistencyChecker.compare(sampleSnapshot(), result)

        assertTrue(result is CoreDataReadResult.NotReady)
        assertEquals(CoreDataConsistencyStatus.NOT_READY, report.status)
    }

    @Test
    fun `unknown event type and count mismatch are invalid instead of silently dropped`() {
        val legacy = sampleSnapshot()
        val unknownType = sourceFor(legacy).apply {
            eventRows = eventRows.mapIndexed { index, event ->
                if (index == 0) event.copy(type = "future_type") else event
            }
        }
        val wrongCount = sourceFor(legacy).apply {
            val current = requireNotNull(state)
            state = current.copy(taskCount = current.taskCount + 1)
        }

        assertTrue(RoomCoreDataReadRepository(unknownType).read() is CoreDataReadResult.Invalid)
        assertTrue(RoomCoreDataReadRepository(wrongCount).read() is CoreDataReadResult.Invalid)
    }

    @Test
    fun `duplicate source order is invalid instead of using database accident order`() {
        val legacy = sampleSnapshot()
        val source = sourceFor(legacy).apply {
            taskRows = taskRows.map { it.copy(sourceOrder = 0) }
        }

        val result = RoomCoreDataReadRepository(source).read()

        assertTrue(result is CoreDataReadResult.Invalid)
        assertTrue((result as CoreDataReadResult.Invalid).reason.contains("source order"))
    }

    @Test
    fun `inconsistent task status is invalid instead of being ignored`() {
        val source = sourceFor(sampleSnapshot()).apply {
            taskRows = taskRows.mapIndexed { index, task ->
                if (index == 0) task.copy(status = TaskStatusKey.COMPLETED) else task
            }
        }

        val result = RoomCoreDataReadRepository(source).read()

        assertTrue(result is CoreDataReadResult.Invalid)
        assertTrue((result as CoreDataReadResult.Invalid).reason.contains("status"))
    }

    private fun sourceFor(snapshot: CoreDataSnapshot): FakeRoomCoreDataSource {
        val tasks = snapshot.items.mapIndexed { index, item -> TaskEntity.fromLegacy(item, index) }
        val events = snapshot.taskEvents.mapIndexed { index, event -> TaskEventEntity.fromLegacy(event, index) }
        val plans = snapshot.goals.mapIndexed { index, goal -> PlanEntity.fromLegacy(goal, index) }
        return FakeRoomCoreDataSource(
            state = MigrationStateEntity(
                migrationKey = LegacyDataImporter.MIGRATION_KEY,
                sourceDataVersion = 1,
                sourceFingerprint = "fixture",
                taskCount = tasks.size,
                taskEventCount = events.size,
                planCount = plans.size,
                completedAt = 1L
            ),
            taskRows = tasks,
            eventRows = events,
            planRows = plans
        )
    }

    private fun sampleSnapshot(): CoreDataSnapshot {
        val first = Item(
            id = 90L,
            title = "first",
            detail = "detail",
            kind = "收集箱",
            priority = "high",
            sourceDetail = "source",
            userNote = "note"
        )
        val second = Item(
            id = 3L,
            title = "second",
            detail = "",
            kind = "任务",
            scheduledAt = 123L,
            goalId = 70L,
            durationMinutes = 25
        )
        val events = listOf(
            TaskEvent(800L, 90L, TaskEventType.TASK_CREATED, 10L, "first"),
            TaskEvent(2L, 3L, TaskEventType.TASK_SCHEDULED, 20L, "second", 123L)
        )
        val goals = listOf(
            Goal(70L, "plan one", 3, 25, desiredOutcome = "outcome"),
            Goal(4L, "plan two", 1, 10, minimumVersion = "minimum")
        )
        return CoreDataSnapshot(listOf(first, second), events, goals)
    }
}

private class FakeRoomCoreDataSource(
    var state: MigrationStateEntity? = null,
    var taskRows: List<TaskEntity> = emptyList(),
    var eventRows: List<TaskEventEntity> = emptyList(),
    var planRows: List<PlanEntity> = emptyList()
) : RoomCoreDataSource {
    override fun migrationState(): MigrationStateEntity? = state
    override fun tasks(): List<TaskEntity> = taskRows
    override fun taskEvents(): List<TaskEventEntity> = eventRows
    override fun plans(): List<PlanEntity> = planRows
}
