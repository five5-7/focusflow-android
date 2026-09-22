package com.sakata.focusflow.data

import com.sakata.focusflow.Goal
import com.sakata.focusflow.Item
import com.sakata.focusflow.TaskEvent
import com.sakata.focusflow.TaskEventType
import com.sakata.focusflow.TaskHistory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CoreDataRepositoryTest {
    @Test
    fun `legacy adapter exposes the same snapshot and is the locked runtime source`() {
        val snapshot = sampleSnapshot()
        val repository = LegacyCoreDataRepository(FakeLegacyPersistence(snapshot))

        assertEquals(CoreDataRuntimeSource.LEGACY, repository.source)
        assertEquals(snapshot, (repository.read() as CoreDataReadResult.Ready).snapshot)
    }

    @Test
    fun `Room adapter exposes the same contract without changing runtime assembly`() {
        val snapshot = sampleSnapshot()
        val tasks = snapshot.items.mapIndexed { index, item -> TaskEntity.fromLegacy(item, index) }
        val events = snapshot.taskEvents.mapIndexed { index, event -> TaskEventEntity.fromLegacy(event, index) }
        val plans = snapshot.goals.mapIndexed { index, plan -> PlanEntity.fromLegacy(plan, index) }
        val store = FakeTransactionalCoreDataStore(
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
        val repository = RoomCoreDataRepository(
            RoomCoreDataReadRepository(store),
            RoomCoreDataWriteRepository(store)
        )

        val result = repository.replaceTasks(snapshot.items.reversed(), snapshot.items)

        assertEquals(CoreDataRuntimeSource.ROOM, repository.source)
        assertTrue(result.applied)
        assertEquals(snapshot.items.reversed(), (repository.read() as CoreDataReadResult.Ready).snapshot.items)
    }

    @Test
    fun `legacy adapter preserves optimistic task rejection`() {
        val snapshot = sampleSnapshot()
        val persistence = FakeLegacyPersistence(snapshot)
        val repository = LegacyCoreDataRepository(persistence)
        val stale = snapshot.items.map { it.copy(title = "stale") }

        val result = repository.replaceTasks(snapshot.items.reversed(), stale)

        assertEquals(CoreDataWriteStatus.STALE_TASKS, result.status)
        assertEquals(snapshot, persistence.snapshot)
    }

    @Test
    fun `legacy adapter keeps tasks events and plans in one write`() {
        val snapshot = sampleSnapshot()
        val persistence = FakeLegacyPersistence(snapshot)
        val repository = LegacyCoreDataRepository(persistence)
        val event = TaskEvent(801L, 90L, TaskEventType.TASK_CONVERTED, 40L)
        val plans = snapshot.goals + Goal(8L, "new", 1, 15)

        val result = repository.replaceTasksAppendEventsAndPlans(
            tasks = snapshot.items.drop(1),
            events = listOf(event),
            plans = plans,
            expectedTasks = snapshot.items,
            expectedPlans = snapshot.goals
        )

        assertEquals(CoreDataWriteStatus.APPLIED, result.status)
        assertEquals(snapshot.items.drop(1), persistence.snapshot.items)
        assertEquals(listOf(800L, 801L), persistence.snapshot.taskEvents.map { it.id })
        assertEquals(plans, persistence.snapshot.goals)
        assertEquals(1, persistence.commits)
    }

    @Test
    fun `legacy write failure is not reported as an applied change`() {
        val snapshot = sampleSnapshot()
        val persistence = FakeLegacyPersistence(snapshot).apply { failWrites = true }
        val repository = LegacyCoreDataRepository(persistence)

        val result = repository.replacePlans(snapshot.goals.reversed(), snapshot.goals)

        assertEquals(CoreDataWriteStatus.WRITE_FAILED, result.status)
        assertEquals(snapshot, persistence.snapshot)
        assertFalse(result.applied)
    }

    @Test
    fun `missed goal recovery is source independent and appends its event`() {
        val now = 10 * 60 * 60_000L
        val missed = Item(
            id = 5L,
            title = "review",
            detail = "",
            kind = "任务",
            scheduledAt = now - 3 * 60 * 60_000L,
            goalId = 70L
        )
        val persistence = FakeLegacyPersistence(sampleSnapshot().copy(items = listOf(missed)))
        val repository = LegacyCoreDataRepository(persistence)

        val result = CoreDataRepositoryOperations.recoverMissedGoalTasks(repository, now)

        val snapshot = (result as CoreDataReadResult.Ready).snapshot
        assertEquals("重新安排：review", snapshot.items.single().title)
        assertEquals("收集箱", snapshot.items.single().kind)
        assertEquals(null, snapshot.items.single().scheduledAt)
        assertEquals(TaskEventType.TASK_TO_INBOX, snapshot.taskEvents.last().type)
        assertEquals(1, persistence.commits)
    }

    @Test
    fun `replan insertion uses the repository atomic task and event operation`() {
        val persistence = FakeLegacyPersistence(sampleSnapshot())
        val repository = LegacyCoreDataRepository(persistence)

        val result = CoreDataRepositoryOperations.addReplanItem(repository, "write report")

        assertTrue(result.applied)
        assertEquals("重新安排：write report", persistence.snapshot.items.first().title)
        assertEquals(TaskEventType.TASK_CREATED, persistence.snapshot.taskEvents.last().type)
        assertEquals(persistence.snapshot.items.first().id, persistence.snapshot.taskEvents.last().itemId)
    }

    @Test
    fun `notification mutation keeps freshness and plan completion in the adapter`() {
        val snapshot = sampleSnapshot()
        val persistence = FakeLegacyPersistence(snapshot)
        val repository = LegacyCoreDataRepository(persistence)

        val result = repository.mutateScheduledTask(
            id = 3L,
            expectedScheduledAt = 100L,
            completionMinimum = false,
            transform = { it.copy(done = true) },
            event = { before, _ ->
                TaskEvent(802L, before.id, TaskEventType.TASK_COMPLETED, 50L)
            }
        )

        assertTrue(result.applied)
        assertTrue(requireNotNull(result.taskMutation).after.done)
        assertTrue(persistence.snapshot.items.first { it.id == 3L }.done)
        assertEquals(1, persistence.snapshot.goals.first { it.id == 70L }.completedThisWeek)
    }

    @Test
    fun `runtime call sites do not bypass the core repository`() {
        val forbidden = Regex(
            "\\b(?:store|settingsStore|startupStore)\\.(?:loadItems|loadTaskEvents|loadGoals|" +
                "saveItemsIfUnchanged|saveItemsAndTaskEvents|saveItemsTaskEventsAndGoals|" +
                "saveGoalsIfUnchanged|replaceTaskEvents|saveGoalConversion|recoverMissedGoalTasks|" +
                "addReplanItem|findItem|mutateScheduledTask|migrateTaskHistory)\\b"
        )
        val files = listOf(
            "MainActivity.kt",
            "FocusFlowApplication.kt",
            "FocusFlowStartupSnapshot.kt",
            "ReminderReceiver.kt",
            "ReminderScheduler.kt",
            "SettingsScreen.kt"
        )

        files.forEach { name ->
            val source = File("src/main/java/com/sakata/focusflow/$name")
            assertTrue("missing source fixture: $name", source.isFile)
            assertFalse("$name bypasses CoreDataRepository", forbidden.containsMatchIn(source.readText()))
        }
    }

    private fun sampleSnapshot() = CoreDataSnapshot(
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
                completedThisWeek = 0,
                completionWeekKey = 100L
            )
        )
    )
}

private class FakeLegacyPersistence(
    var snapshot: CoreDataSnapshot
) : LegacyCoreDataPersistence {
    var failWrites = false
    var commits = 0
    var migrationCalls = 0

    override fun read(): CoreDataSnapshot = snapshot

    override fun saveItemsIfUnchanged(items: List<Item>, expectedItems: List<Item>): Boolean {
        if (failWrites || snapshot.items != expectedItems) return false
        snapshot = snapshot.copy(items = items)
        commits++
        return true
    }

    override fun saveItemsAndTaskEvents(
        items: List<Item>,
        events: List<TaskEvent>,
        expectedItems: List<Item>
    ): Boolean {
        if (failWrites || snapshot.items != expectedItems) return false
        snapshot = snapshot.copy(
            items = items,
            taskEvents = events.fold(snapshot.taskEvents, TaskHistory::append)
        )
        commits++
        return true
    }

    override fun saveItemsTaskEventsAndGoals(
        items: List<Item>,
        events: List<TaskEvent>,
        goals: List<Goal>,
        expectedItems: List<Item>,
        expectedGoals: List<Goal>
    ): Boolean {
        if (failWrites || snapshot.items != expectedItems || snapshot.goals != expectedGoals) return false
        snapshot = snapshot.copy(
            items = items,
            taskEvents = events.fold(snapshot.taskEvents, TaskHistory::append),
            goals = goals
        )
        commits++
        return true
    }

    override fun saveGoalsIfUnchanged(goals: List<Goal>, expectedGoals: List<Goal>): Boolean {
        if (failWrites || snapshot.goals != expectedGoals) return false
        snapshot = snapshot.copy(goals = goals)
        commits++
        return true
    }

    override fun appendTaskEvent(event: TaskEvent) {
        if (failWrites) error("injected write failure")
        snapshot = snapshot.copy(taskEvents = TaskHistory.append(snapshot.taskEvents, event))
        commits++
    }

    override fun replaceTaskEvents(events: List<TaskEvent>): Boolean {
        if (failWrites) return false
        snapshot = snapshot.copy(taskEvents = events)
        commits++
        return true
    }

    override fun mutateScheduledTask(
        id: Long,
        expectedScheduledAt: Long,
        completionMinimum: Boolean?,
        transform: (Item) -> Item,
        event: (Item, Item) -> TaskEvent
    ): CoreDataTaskMutation? {
        if (failWrites) return null
        val before = snapshot.items.firstOrNull { it.id == id && it.scheduledAt == expectedScheduledAt }
            ?: return null
        val after = transform(before)
        val plans = if (completionMinimum != null && before.goalId != null) {
            snapshot.goals.map { plan ->
                if (plan.id != before.goalId) plan
                else if (completionMinimum) {
                    plan.copy(minimumCompletionsThisWeek = plan.minimumCompletionsThisWeek + 1)
                } else {
                    plan.copy(completedThisWeek = plan.completedThisWeek + 1)
                }
            }
        } else snapshot.goals
        snapshot = snapshot.copy(
            items = snapshot.items.map { if (it.id == id) after else it },
            taskEvents = TaskHistory.append(snapshot.taskEvents, event(before, after)),
            goals = plans
        )
        commits++
        return CoreDataTaskMutation(before, after)
    }

    override fun migrateTaskHistory() {
        migrationCalls++
    }
}
