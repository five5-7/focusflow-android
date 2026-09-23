package com.sakata.focusflow.data

import com.sakata.focusflow.ActivitySession
import com.sakata.focusflow.Goal
import com.sakata.focusflow.Item
import com.sakata.focusflow.TaskEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CoreDataRuntimeCompositionRootTest {
    @Test
    fun `product activation policy remains disabled`() {
        assertFalse(CoreDataRuntimePolicy.ACTIVATION_ENABLED)
    }

    @Test
    fun `startup and Receiver share one Legacy repository without constructing Room`() {
        var selections = 0
        var legacyFactories = 0
        var roomFactories = 0
        val legacy = RecordingRepository(CoreDataRuntimeSource.LEGACY)
        val root = CoreDataRuntimeCompositionRoot(
            selectSource = {
                selections++
                decision(
                    CoreDataRuntimeSource.LEGACY,
                    CoreDataActivationStatus.LEGACY_ACTIVATION_DISABLED
                )
            },
            legacyRepositoryFactory = { legacyFactories++; legacy },
            roomRepositoryFactory = {
                roomFactories++
                RecordingRepository(CoreDataRuntimeSource.ROOM)
            }
        )

        val startup = root.resolve() as CoreDataRuntimeResolution.Ready
        val receiver = root.resolve() as CoreDataRuntimeResolution.Ready

        assertSame(startup.repository, receiver.repository)
        assertEquals(CoreDataRuntimeSource.LEGACY, startup.repository.source)
        assertEquals(1, selections)
        assertEquals(1, legacyFactories)
        assertEquals(0, roomFactories)
        assertEquals(1, legacy.historyMigrationCalls)
    }

    @Test
    fun `blocked activation creates no writer and a restarted process can select Room`() {
        var blockedLegacyFactories = 0
        var blockedRoomFactories = 0
        val interruptedProcess = CoreDataRuntimeCompositionRoot(
            selectSource = {
                decision(CoreDataRuntimeSource.NONE, CoreDataActivationStatus.BLOCKED_MIGRATION)
            },
            legacyRepositoryFactory = {
                blockedLegacyFactories++
                RecordingRepository(CoreDataRuntimeSource.LEGACY)
            },
            roomRepositoryFactory = {
                blockedRoomFactories++
                RecordingRepository(CoreDataRuntimeSource.ROOM)
            }
        )

        val blockedStartup = interruptedProcess.resolve()
        val blockedReceiver = interruptedProcess.resolve()

        assertTrue(blockedStartup is CoreDataRuntimeResolution.Blocked)
        assertSame(blockedStartup, blockedReceiver)
        assertEquals(0, blockedLegacyFactories)
        assertEquals(0, blockedRoomFactories)

        var restartedLegacyFactories = 0
        var restartedRoomFactories = 0
        val room = RecordingRepository(CoreDataRuntimeSource.ROOM)
        val restartedProcess = CoreDataRuntimeCompositionRoot(
            selectSource = {
                decision(CoreDataRuntimeSource.ROOM, CoreDataActivationStatus.ROOM_ACTIVATED)
            },
            legacyRepositoryFactory = {
                restartedLegacyFactories++
                RecordingRepository(CoreDataRuntimeSource.LEGACY)
            },
            roomRepositoryFactory = { restartedRoomFactories++; room }
        )

        val resumedReceiver = restartedProcess.resolve() as CoreDataRuntimeResolution.Ready
        val resumedStartup = restartedProcess.resolve() as CoreDataRuntimeResolution.Ready

        assertSame(resumedReceiver.repository, resumedStartup.repository)
        assertEquals(CoreDataRuntimeSource.ROOM, resumedReceiver.repository.source)
        assertEquals(0, restartedLegacyFactories)
        assertEquals(1, restartedRoomFactories)
        assertEquals(1, room.historyMigrationCalls)
    }

    @Test
    fun `active selection constructs only the Room writer even with the product gate disabled`() {
        var legacyFactories = 0
        var roomFactories = 0
        val root = CoreDataRuntimeCompositionRoot(
            selectSource = {
                decision(CoreDataRuntimeSource.ROOM, CoreDataActivationStatus.ROOM_ALREADY_ACTIVE)
            },
            legacyRepositoryFactory = {
                legacyFactories++
                RecordingRepository(CoreDataRuntimeSource.LEGACY)
            },
            roomRepositoryFactory = {
                roomFactories++
                RecordingRepository(CoreDataRuntimeSource.ROOM)
            }
        )

        val ready = root.resolve() as CoreDataRuntimeResolution.Ready

        assertEquals(CoreDataRuntimeSource.ROOM, ready.repository.source)
        assertEquals(0, legacyFactories)
        assertEquals(1, roomFactories)
    }

    @Test
    fun `repository source mismatch fails closed`() {
        val root = CoreDataRuntimeCompositionRoot(
            selectSource = {
                decision(
                    CoreDataRuntimeSource.LEGACY,
                    CoreDataActivationStatus.LEGACY_ACTIVATION_DISABLED
                )
            },
            legacyRepositoryFactory = { RecordingRepository(CoreDataRuntimeSource.ROOM) },
            roomRepositoryFactory = { error("must not create Room") }
        )

        val blocked = root.resolve() as CoreDataRuntimeResolution.Blocked

        assertEquals(CoreDataRuntimeSource.NONE, blocked.decision.source)
        assertEquals(CoreDataActivationStatus.BLOCKED_RUNTIME_ASSEMBLY, blocked.decision.status)
    }

    @Test
    fun `failed history bootstrap blocks the selected source and never falls back`() {
        var roomFactories = 0
        val legacy = RecordingRepository(
            CoreDataRuntimeSource.LEGACY,
            historyResult = CoreDataWriteResult(CoreDataWriteStatus.WRITE_FAILED)
        )
        val root = CoreDataRuntimeCompositionRoot(
            selectSource = {
                decision(CoreDataRuntimeSource.LEGACY, CoreDataActivationStatus.LEGACY_ACTIVATION_DISABLED)
            },
            legacyRepositoryFactory = { legacy },
            roomRepositoryFactory = {
                roomFactories++
                RecordingRepository(CoreDataRuntimeSource.ROOM)
            }
        )

        val blocked = root.resolve() as CoreDataRuntimeResolution.Blocked
        assertSame(blocked, root.resolve())
        assertEquals(CoreDataRuntimeSource.NONE, blocked.decision.source)
        assertEquals(CoreDataActivationStatus.BLOCKED_RUNTIME_ASSEMBLY, blocked.decision.status)
        assertEquals("task history migration failed: WRITE_FAILED", blocked.decision.message)
        assertEquals(1, legacy.historyMigrationCalls)
        assertEquals(0, roomFactories)
    }

    @Test
    fun `history bootstrap exception blocks Room instead of escaping resolution`() {
        var legacyFactories = 0
        val room = RecordingRepository(CoreDataRuntimeSource.ROOM, historyFailure = true)
        val root = CoreDataRuntimeCompositionRoot(
            selectSource = {
                decision(CoreDataRuntimeSource.ROOM, CoreDataActivationStatus.ROOM_ALREADY_ACTIVE)
            },
            legacyRepositoryFactory = {
                legacyFactories++
                RecordingRepository(CoreDataRuntimeSource.LEGACY)
            },
            roomRepositoryFactory = { room }
        )

        val blocked = root.resolve() as CoreDataRuntimeResolution.Blocked
        assertSame(blocked, root.resolve())
        assertEquals(CoreDataRuntimeSource.NONE, blocked.decision.source)
        assertEquals(CoreDataActivationStatus.BLOCKED_RUNTIME_ASSEMBLY, blocked.decision.status)
        assertEquals("task history migration failed: IllegalStateException", blocked.decision.message)
        assertEquals(1, room.historyMigrationCalls)
        assertEquals(0, legacyFactories)
    }

    @Test
    fun `product writers are assembled only behind the application composition root`() {
        val sourceRoot = File("src/main/java/com/sakata/focusflow")
        val kotlinFiles = sourceRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        val factoryUsers = kotlinFiles.filter {
            "AndroidCoreDataRuntimeFactory.create(" in it.readText()
        }
        assertEquals(listOf("FocusFlowApplication.kt"), factoryUsers.map(File::getName))

        val directConstruction = Regex("\\b(?:LegacyCoreDataRepository|RoomCoreDataRepository)\\(")
        val allowed = setOf("CoreDataRepository.kt", "CoreDataRuntime.kt")
        kotlinFiles.filterNot { it.name in allowed }.forEach { file ->
            assertFalse(
                "${file.name} constructs a core-data writer outside the composition root",
                directConstruction.containsMatchIn(file.readText())
            )
        }
    }

    private fun decision(
        source: CoreDataRuntimeSource,
        status: CoreDataActivationStatus
    ) = CoreDataActivationDecision(source = source, status = status)
}

private class RecordingRepository(
    override val source: CoreDataRuntimeSource,
    private val historyResult: CoreDataWriteResult = CoreDataWriteResult(CoreDataWriteStatus.APPLIED),
    private val historyFailure: Boolean = false
) : CoreDataRepository {
    var historyMigrationCalls = 0

    override fun read(): CoreDataReadResult = CoreDataReadResult.Ready(
        CoreDataSnapshot(emptyList(), emptyList(), emptyList())
    )

    override fun replaceTasks(
        tasks: List<Item>,
        expectedTasks: List<Item>
    ): CoreDataWriteResult = applied()

    override fun replaceTasksAndAppendEvents(
        tasks: List<Item>,
        events: List<TaskEvent>,
        expectedTasks: List<Item>
    ): CoreDataWriteResult = applied()

    override fun replaceTasksAppendEventsAndPlans(
        tasks: List<Item>,
        events: List<TaskEvent>,
        plans: List<Goal>,
        expectedTasks: List<Item>,
        expectedPlans: List<Goal>
    ): CoreDataWriteResult = applied()

    override fun replacePlans(
        plans: List<Goal>,
        expectedPlans: List<Goal>
    ): CoreDataWriteResult = applied()

    override fun appendTaskEvent(event: TaskEvent): CoreDataWriteResult = applied()
    override fun replaceTaskEvents(events: List<TaskEvent>): CoreDataWriteResult = applied()

    override fun replaceActivitySessions(
        sessions: List<ActivitySession>,
        expectedSessions: List<ActivitySession>
    ): CoreDataWriteResult = applied()

    override fun mutateScheduledTask(
        id: Long,
        expectedScheduledAt: Long,
        completionMinimum: Boolean?,
        transform: (Item) -> Item,
        event: (Item, Item) -> TaskEvent
    ): CoreDataWriteResult = applied()

    override fun ensureTaskHistoryMigrated(): CoreDataWriteResult {
        historyMigrationCalls++
        if (historyFailure) throw IllegalStateException("test history bootstrap failure")
        return historyResult
    }

    private fun applied() = CoreDataWriteResult(CoreDataWriteStatus.APPLIED)
}
