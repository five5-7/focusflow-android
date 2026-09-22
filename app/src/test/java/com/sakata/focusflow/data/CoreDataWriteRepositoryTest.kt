package com.sakata.focusflow.data

import com.sakata.focusflow.Goal
import com.sakata.focusflow.Item
import com.sakata.focusflow.TaskEvent
import com.sakata.focusflow.TaskEventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreDataWriteRepositoryTest {
    @Test
    fun `task snapshot write rejects a stale expected snapshot without mutation`() {
        val store = storeFor(sampleSnapshot())
        val before = readySnapshot(store)
        val staleExpected = before.items.map { it.copy(title = "stale") }
        val replacement = before.items.reversed()

        val result = RoomCoreDataWriteRepository(store).replaceTasks(replacement, staleExpected)

        assertEquals(CoreDataWriteStatus.STALE_TASKS, result.status)
        assertEquals(before, readySnapshot(store))
        assertEquals(0, store.committedTransactions)
    }

    @Test
    fun `task and appended history replace atomically and keep caller order`() {
        val store = storeFor(sampleSnapshot())
        val before = readySnapshot(store)
        val replacement = before.items.reversed()
        val appended = TaskEvent(801L, 3L, TaskEventType.TASK_RESCHEDULED, 30L, "", 200L)

        val result = RoomCoreDataWriteRepository(store).replaceTasksAndAppendEvents(
            replacement,
            listOf(appended),
            expectedTasks = before.items
        )
        val after = readySnapshot(store)

        assertEquals(CoreDataWriteStatus.APPLIED, result.status)
        assertEquals(listOf(3L, 90L), after.items.map { it.id })
        assertEquals(listOf(0, 1), store.taskRows.map { it.sourceOrder })
        assertEquals(listOf(800L, 801L), after.taskEvents.map { it.id })
        assertEquals(listOf(0, 1), store.eventRows.map { it.sourceOrder })
        assertEquals(1, store.committedTransactions)
        assertStateCountsMatch(store)
    }

    @Test
    fun `failure after task replacement rolls back the whole transaction`() {
        val store = storeFor(sampleSnapshot()).apply { failOnEvents = true }
        val before = readySnapshot(store)
        val stateBefore = store.state
        val appended = TaskEvent(801L, 3L, TaskEventType.TASK_COMPLETED, 30L)

        val result = RoomCoreDataWriteRepository(store).replaceTasksAndAppendEvents(
            before.items.reversed(),
            listOf(appended),
            expectedTasks = before.items
        )

        assertEquals(CoreDataWriteStatus.WRITE_FAILED, result.status)
        assertEquals(before, readySnapshot(store))
        assertEquals(stateBefore, store.state)
        assertEquals(0, store.committedTransactions)
    }

    @Test
    fun `migration count update failure rolls back completed table writes`() {
        val store = storeFor(sampleSnapshot()).apply { failOnCounts = true }
        val before = readySnapshot(store)

        val result = RoomCoreDataWriteRepository(store).replacePlans(
            listOf(Goal(55L, "replacement", 4, 20))
        )

        assertEquals(CoreDataWriteStatus.WRITE_FAILED, result.status)
        assertEquals(before, readySnapshot(store))
        assertEquals(0, store.committedTransactions)
    }

    @Test
    fun `task event and plan write checks both optimistic snapshots`() {
        val store = storeFor(sampleSnapshot())
        val before = readySnapshot(store)
        val stalePlans = before.goals.map { it.copy(title = "stale") }

        val result = RoomCoreDataWriteRepository(store).replaceTasksAppendEventsAndPlans(
            tasks = before.items.drop(1),
            events = listOf(TaskEvent(802L, 90L, TaskEventType.TASK_CONVERTED, 40L)),
            plans = before.goals.drop(1),
            expectedTasks = before.items,
            expectedPlans = stalePlans
        )

        assertEquals(CoreDataWriteStatus.STALE_PLANS, result.status)
        assertEquals(before, readySnapshot(store))
        assertEquals(0, store.committedTransactions)
    }

    @Test
    fun `task event and plan write preserves existing history in one commit`() {
        val store = storeFor(sampleSnapshot())
        val before = readySnapshot(store)
        val converted = TaskEvent(802L, 90L, TaskEventType.TASK_CONVERTED, 40L)
        val newPlans = before.goals + Goal(5L, "new plan", 1, 15)

        val result = RoomCoreDataWriteRepository(store).replaceTasksAppendEventsAndPlans(
            tasks = before.items.drop(1),
            events = listOf(converted),
            plans = newPlans,
            expectedTasks = before.items,
            expectedPlans = before.goals
        )
        val after = readySnapshot(store)

        assertEquals(CoreDataWriteStatus.APPLIED, result.status)
        assertEquals(listOf(90L), after.items.map { it.id })
        assertEquals(listOf(800L, 802L), after.taskEvents.map { it.id })
        assertEquals(listOf(70L, 4L, 5L), after.goals.map { it.id })
        assertEquals(1, store.committedTransactions)
        assertStateCountsMatch(store)
    }

    @Test
    fun `unconditional plan replacement matches the legacy save path`() {
        val store = storeFor(sampleSnapshot())
        val replacement = listOf(Goal(55L, "replacement", 4, 20))

        val result = RoomCoreDataWriteRepository(store).replacePlans(replacement)

        assertEquals(CoreDataWriteStatus.APPLIED, result.status)
        assertEquals(replacement, readySnapshot(store).goals)
        assertEquals(listOf(0), store.planRows.map { it.sourceOrder })
        assertStateCountsMatch(store)
    }

    @Test
    fun `fresh notification mutation updates task event and plan in one transaction`() {
        val store = storeFor(sampleSnapshot())
        val repository = RoomCoreDataWriteRepository(store) { 900L }

        val result = repository.mutateScheduledTask(
            id = 3L,
            expectedScheduledAt = 100L,
            completionMinimum = false,
            transform = { it.copy(done = true, completedAt = 30L) },
            event = { before, _ ->
                TaskEvent(803L, before.id, TaskEventType.TASK_COMPLETED, 30L, before.title)
            }
        )
        val after = readySnapshot(store)
        val updatedPlan = after.goals.first { it.id == 70L }

        assertEquals(CoreDataWriteStatus.APPLIED, result.status)
        assertEquals(3L, result.taskMutation?.before?.id)
        assertTrue(result.taskMutation?.after?.done == true)
        assertTrue(after.items.first { it.id == 3L }.done)
        assertEquals(listOf(800L, 803L), after.taskEvents.map { it.id })
        assertEquals(900L, updatedPlan.completionWeekKey)
        assertEquals(1, updatedPlan.completedThisWeek)
        assertEquals(0, updatedPlan.minimumCompletionsThisWeek)
        assertEquals(1, store.committedTransactions)
    }

    @Test
    fun `stale notification mutation does not append history or change plan`() {
        val store = storeFor(sampleSnapshot())
        val before = readySnapshot(store)

        val result = RoomCoreDataWriteRepository(store).mutateScheduledTask(
            id = 3L,
            expectedScheduledAt = 101L,
            completionMinimum = true,
            transform = { it.copy(done = true) },
            event = { task, _ -> TaskEvent(804L, task.id, TaskEventType.TASK_COMPLETED, 30L) }
        )

        assertEquals(CoreDataWriteStatus.CONDITION_NOT_MET, result.status)
        assertNull(result.taskMutation)
        assertEquals(before, readySnapshot(store))
        assertEquals(0, store.committedTransactions)
    }

    @Test
    fun `history replacement preserves detached events and exact order`() {
        val store = storeFor(sampleSnapshot())
        val replacement = listOf(
            TaskEvent(900L, 999L, TaskEventType.TASK_DELETED, 50L),
            TaskEvent(7L, 3L, TaskEventType.TASK_SCHEDULED, 60L, scheduledAt = 100L)
        )

        val result = RoomCoreDataWriteRepository(store).replaceTaskEvents(replacement)
        val after = readySnapshot(store)

        assertEquals(CoreDataWriteStatus.APPLIED, result.status)
        assertEquals(replacement, after.taskEvents)
        assertEquals(listOf(0, 1), store.eventRows.map { it.sourceOrder })
        assertStateCountsMatch(store)
    }

    @Test
    fun `duplicate appended event ID is rejected without mutation`() {
        val store = storeFor(sampleSnapshot())
        val before = readySnapshot(store)
        val duplicate = TaskEvent(800L, 3L, TaskEventType.TASK_COMPLETED, 80L)

        val result = RoomCoreDataWriteRepository(store).appendTaskEvent(duplicate)

        assertEquals(CoreDataWriteStatus.INVALID_INPUT, result.status)
        assertEquals(before, readySnapshot(store))
        assertFalse(result.message.contains(before.items.first().title))
        assertEquals(0, store.committedTransactions)
    }

    @Test
    fun `missing migration state blocks every write`() {
        val store = storeFor(sampleSnapshot()).apply { state = null }

        val result = RoomCoreDataWriteRepository(store).replaceTaskEvents(emptyList())

        assertEquals(CoreDataWriteStatus.NOT_READY, result.status)
        assertEquals(0, store.committedTransactions)
    }

    private fun sampleSnapshot(): CoreDataSnapshot = CoreDataSnapshot(
        items = listOf(
            Item(90L, "capture", "", "收集箱"),
            Item(3L, "scheduled", "", "任务", scheduledAt = 100L, goalId = 70L)
        ),
        taskEvents = listOf(
            TaskEvent(800L, 90L, TaskEventType.TASK_CREATED, 10L, "capture")
        ),
        goals = listOf(
            Goal(
                id = 70L,
                title = "plan",
                weeklyTarget = 2,
                durationMinutes = 30,
                completedThisWeek = 8,
                minimumCompletionsThisWeek = 6,
                completionWeekKey = 100L
            ),
            Goal(4L, "later", 1, 15)
        )
    )

    private fun storeFor(snapshot: CoreDataSnapshot): FakeTransactionalCoreDataStore {
        val tasks = snapshot.items.mapIndexed { index, item -> TaskEntity.fromLegacy(item, index) }
        val events = snapshot.taskEvents.mapIndexed { index, event -> TaskEventEntity.fromLegacy(event, index) }
        val plans = snapshot.goals.mapIndexed { index, goal -> PlanEntity.fromLegacy(goal, index) }
        return FakeTransactionalCoreDataStore(
            state = MigrationStateEntity(
                LegacyDataImporter.MIGRATION_KEY,
                1,
                "fixture",
                tasks.size,
                events.size,
                plans.size,
                1L
            ),
            taskRows = tasks,
            eventRows = events,
            planRows = plans
        )
    }

    private fun readySnapshot(store: FakeTransactionalCoreDataStore): CoreDataSnapshot =
        (RoomCoreDataReadRepository(store).read() as CoreDataReadResult.Ready).snapshot

    private fun assertStateCountsMatch(store: FakeTransactionalCoreDataStore) {
        val state = requireNotNull(store.state)
        assertEquals(store.taskRows.size, state.taskCount)
        assertEquals(store.eventRows.size, state.taskEventCount)
        assertEquals(store.planRows.size, state.planCount)
    }
}

private class FakeTransactionalCoreDataStore(
    var state: MigrationStateEntity?,
    var taskRows: List<TaskEntity>,
    var eventRows: List<TaskEventEntity>,
    var planRows: List<PlanEntity>
) : RoomCoreDataWriteStore {
    var failOnEvents = false
    var failOnCounts = false
    var committedTransactions = 0

    override fun <T> inTransaction(block: () -> T): T {
        val originalState = state
        val originalTasks = taskRows
        val originalEvents = eventRows
        val originalPlans = planRows
        return try {
            block().also { result ->
                if (result is CoreDataWriteResult && result.applied) committedTransactions++
            }
        } catch (error: Exception) {
            state = originalState
            taskRows = originalTasks
            eventRows = originalEvents
            planRows = originalPlans
            throw error
        }
    }

    override fun migrationState(): MigrationStateEntity? = state
    override fun tasks(): List<TaskEntity> = taskRows
    override fun taskEvents(): List<TaskEventEntity> = eventRows
    override fun plans(): List<PlanEntity> = planRows

    override fun replaceTasks(tasks: List<TaskEntity>) {
        taskRows = tasks
    }

    override fun replaceTaskEvents(events: List<TaskEventEntity>) {
        if (failOnEvents) error("injected event write failure")
        eventRows = events
    }

    override fun replacePlans(plans: List<PlanEntity>) {
        planRows = plans
    }

    override fun updateMigrationCounts(taskCount: Int, taskEventCount: Int, planCount: Int) {
        if (failOnCounts) error("injected count update failure")
        val current = requireNotNull(state) { "migration state missing" }
        state = current.copy(
            taskCount = taskCount,
            taskEventCount = taskEventCount,
            planCount = planCount
        )
    }
}
