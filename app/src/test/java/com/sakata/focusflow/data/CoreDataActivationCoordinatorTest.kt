package com.sakata.focusflow.data

import com.sakata.focusflow.TaskEventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreDataActivationCoordinatorTest {
    @Test
    fun `disabled gate keeps legacy active without touching migration`() {
        val marker = FakeActivationStore()
        val migration = RecordingMigrationRunner(reportFor(sourceFingerprint()))
        var legacyReads = 0
        var roomReads = 0
        val coordinator = CoreDataActivationCoordinator(
            legacyReader = { legacyReads++; legacyResult() },
            migrationRunner = migration,
            roomVerifier = CoreDataRoomActivationVerifier {
                roomReads++
                roomVerification()
            },
            activationStore = marker
        )

        val decision = coordinator.selectSource(activationEnabled = false)

        assertEquals(CoreDataRuntimeSource.LEGACY, decision.source)
        assertEquals(CoreDataActivationStatus.LEGACY_ACTIVATION_DISABLED, decision.status)
        assertEquals(0, legacyReads)
        assertEquals(0, migration.calls)
        assertEquals(0, roomReads)
        assertEquals(0, marker.beginCalls)
    }

    @Test
    fun `successful cutover persists intent before migration and selects Room`() {
        val fingerprint = sourceFingerprint()
        val marker = FakeActivationStore()
        val migration = RecordingMigrationRunner(reportFor(fingerprint))
        val times = ArrayDeque(listOf(100L, 200L))
        val coordinator = coordinator(
            marker = marker,
            migration = migration,
            now = { times.removeFirst() }
        )

        val decision = coordinator.selectSource(activationEnabled = true)

        assertEquals(CoreDataRuntimeSource.ROOM, decision.source)
        assertEquals(CoreDataActivationStatus.ROOM_ACTIVATED, decision.status)
        assertEquals(MigrationStatus.IMPORTED, decision.migrationStatus)
        assertEquals(CoreDataConsistencyStatus.CONSISTENT, decision.consistencyStatus)
        assertEquals(1, marker.beginCalls)
        assertEquals(1, marker.completeCalls)
        assertEquals(1, migration.calls)
        val active = marker.state as CoreDataActivationMarkerState.Active
        assertEquals(CoreDataActivationRecord(fingerprint, 100L, 200L), active.record)
    }

    @Test
    fun `active Room remains authoritative even when activation flag is off`() {
        val fingerprint = sourceFingerprint()
        val marker = FakeActivationStore(
            CoreDataActivationMarkerState.Active(
                CoreDataActivationRecord(fingerprint, 100L, 200L)
            )
        )
        val migration = RecordingMigrationRunner(reportFor(fingerprint))
        var legacyReads = 0
        val coordinator = CoreDataActivationCoordinator(
            legacyReader = { legacyReads++; legacyResult() },
            migrationRunner = migration,
            roomVerifier = CoreDataRoomActivationVerifier { roomVerification() },
            activationStore = marker
        )

        val decision = coordinator.selectSource(activationEnabled = false)

        assertEquals(CoreDataRuntimeSource.ROOM, decision.source)
        assertEquals(CoreDataActivationStatus.ROOM_ALREADY_ACTIVE, decision.status)
        assertEquals(0, legacyReads)
        assertEquals(0, migration.calls)
    }

    @Test
    fun `invalid active Room blocks instead of falling back to stale legacy`() {
        val fingerprint = sourceFingerprint()
        val marker = FakeActivationStore(
            CoreDataActivationMarkerState.Active(
                CoreDataActivationRecord(fingerprint, 100L, 200L)
            )
        )
        var legacyReads = 0
        val coordinator = CoreDataActivationCoordinator(
            legacyReader = { legacyReads++; legacyResult() },
            migrationRunner = RecordingMigrationRunner(reportFor(fingerprint)),
            roomVerifier = CoreDataRoomActivationVerifier {
                roomVerification(read = CoreDataReadResult.Invalid("injected invalid Room"))
            },
            activationStore = marker
        )

        val decision = coordinator.selectSource(activationEnabled = true)

        assertEquals(CoreDataRuntimeSource.NONE, decision.source)
        assertEquals(CoreDataActivationStatus.BLOCKED_ROOM_INVALID, decision.status)
        assertEquals(0, legacyReads)
    }

    @Test
    fun `active marker and Room fingerprint disagreement fails closed`() {
        val fingerprint = sourceFingerprint()
        val marker = FakeActivationStore(
            CoreDataActivationMarkerState.Active(
                CoreDataActivationRecord(fingerprint, 100L, 200L)
            )
        )
        val coordinator = CoreDataActivationCoordinator(
            legacyReader = ::legacyResult,
            migrationRunner = RecordingMigrationRunner(reportFor(fingerprint)),
            roomVerifier = CoreDataRoomActivationVerifier {
                roomVerification(stateFingerprint = "different")
            },
            activationStore = marker
        )

        val decision = coordinator.selectSource(activationEnabled = true)

        assertEquals(CoreDataRuntimeSource.NONE, decision.source)
        assertEquals(CoreDataActivationStatus.BLOCKED_ROOM_INVALID, decision.status)
    }

    @Test
    fun `failure before activating marker safely leaves legacy authoritative`() {
        val marker = FakeActivationStore().apply { beginMode = WriteMode.NO_OP }
        val migration = RecordingMigrationRunner(reportFor(sourceFingerprint()))

        val decision = coordinator(marker, migration).selectSource(activationEnabled = true)

        assertEquals(CoreDataRuntimeSource.LEGACY, decision.source)
        assertEquals(CoreDataActivationStatus.LEGACY_ACTIVATION_NOT_STARTED, decision.status)
        assertEquals(0, migration.calls)
        assertTrue(marker.state is CoreDataActivationMarkerState.Inactive)
    }

    @Test
    fun `corrupt legacy source blocks activation but keeps pre-cutover source`() {
        val marker = FakeActivationStore()
        val migration = RecordingMigrationRunner(reportFor("unused"))
        val coordinator = CoreDataActivationCoordinator(
            legacyReader = {
                LegacyReadResult.Failure("items", "invalid JSON", backupSucceeded = true)
            },
            migrationRunner = migration,
            roomVerifier = CoreDataRoomActivationVerifier { error("must not verify Room") },
            activationStore = marker
        )

        val decision = coordinator.selectSource(activationEnabled = true)

        assertEquals(CoreDataRuntimeSource.LEGACY, decision.source)
        assertEquals(CoreDataActivationStatus.LEGACY_ACTIVATION_BLOCKED, decision.status)
        assertEquals(0, marker.beginCalls)
        assertEquals(0, migration.calls)
    }

    @Test
    fun `activating state resumes after restart even if feature flag is off`() {
        val fingerprint = sourceFingerprint()
        val record = CoreDataActivationRecord(fingerprint, 100L)
        val marker = FakeActivationStore(CoreDataActivationMarkerState.Activating(record))
        val migration = RecordingMigrationRunner(
            reportFor(fingerprint, MigrationStatus.ALREADY_IMPORTED)
        )

        val decision = coordinator(marker, migration) { 200L }
            .selectSource(activationEnabled = false)

        assertEquals(CoreDataRuntimeSource.ROOM, decision.source)
        assertEquals(CoreDataActivationStatus.ROOM_ACTIVATED, decision.status)
        assertEquals(0, marker.beginCalls)
        assertEquals(1, marker.completeCalls)
        assertEquals(1, migration.calls)
    }

    @Test
    fun `legacy change after activating marker blocks without choosing either source`() {
        val fingerprint = sourceFingerprint()
        val marker = FakeActivationStore(
            CoreDataActivationMarkerState.Activating(
                CoreDataActivationRecord("frozen-fingerprint", 100L)
            )
        )
        val migration = RecordingMigrationRunner(reportFor(fingerprint))

        val decision = coordinator(marker, migration).selectSource(activationEnabled = true)

        assertEquals(CoreDataRuntimeSource.NONE, decision.source)
        assertEquals(CoreDataActivationStatus.BLOCKED_SOURCE_CHANGED, decision.status)
        assertEquals(0, migration.calls)
        assertEquals(0, marker.completeCalls)
    }

    @Test
    fun `migration marker failure during activation blocks and is retryable`() {
        val fingerprint = sourceFingerprint()
        val marker = FakeActivationStore(
            CoreDataActivationMarkerState.Activating(
                CoreDataActivationRecord(fingerprint, 100L)
            )
        )
        val migration = RecordingMigrationRunner(
            reportFor(fingerprint, MigrationStatus.MARKER_WRITE_FAILED)
        )

        val decision = coordinator(marker, migration).selectSource(activationEnabled = true)

        assertEquals(CoreDataRuntimeSource.NONE, decision.source)
        assertEquals(CoreDataActivationStatus.BLOCKED_MIGRATION, decision.status)
        assertEquals(MigrationStatus.MARKER_WRITE_FAILED, decision.migrationStatus)
        assertTrue(marker.state is CoreDataActivationMarkerState.Activating)
    }

    @Test
    fun `Room mismatch during activation remains blocked in activating state`() {
        val fingerprint = sourceFingerprint()
        val marker = FakeActivationStore(
            CoreDataActivationMarkerState.Activating(
                CoreDataActivationRecord(fingerprint, 100L)
            )
        )
        val original = coreSnapshot()
        val changed = original.copy(
            items = original.items.map { item -> item.copy(title = "changed during verification") }
        )
        val coordinator = coordinator(
            marker = marker,
            migration = RecordingMigrationRunner(reportFor(fingerprint)),
            room = roomVerification(read = CoreDataReadResult.Ready(changed))
        )

        val decision = coordinator.selectSource(activationEnabled = true)

        assertEquals(CoreDataRuntimeSource.NONE, decision.source)
        assertEquals(CoreDataActivationStatus.BLOCKED_ROOM_MISMATCH, decision.status)
        assertEquals(CoreDataConsistencyStatus.MISMATCH, decision.consistencyStatus)
        assertTrue(marker.state is CoreDataActivationMarkerState.Activating)
    }

    @Test
    fun `completion write that stays activating never returns legacy or Room`() {
        val fingerprint = sourceFingerprint()
        val marker = FakeActivationStore(
            CoreDataActivationMarkerState.Activating(
                CoreDataActivationRecord(fingerprint, 100L)
            )
        ).apply { completeMode = WriteMode.NO_OP }

        val decision = coordinator(marker).selectSource(activationEnabled = true)

        assertEquals(CoreDataRuntimeSource.NONE, decision.source)
        assertEquals(CoreDataActivationStatus.BLOCKED_ACTIVATION_INCOMPLETE, decision.status)
        assertTrue(marker.state is CoreDataActivationMarkerState.Activating)
    }

    @Test
    fun `invalid or unreadable marker never guesses a source`() {
        val invalid = FakeActivationStore(
            CoreDataActivationMarkerState.Invalid("injected invalid marker")
        )
        val unreadable = FakeActivationStore().apply { failReads = true }

        val invalidDecision = coordinator(invalid).selectSource(activationEnabled = false)
        val unreadableDecision = coordinator(unreadable).selectSource(activationEnabled = false)

        assertEquals(CoreDataRuntimeSource.NONE, invalidDecision.source)
        assertEquals(CoreDataActivationStatus.BLOCKED_MARKER_INVALID, invalidDecision.status)
        assertEquals(CoreDataRuntimeSource.NONE, unreadableDecision.source)
        assertEquals(CoreDataActivationStatus.BLOCKED_MARKER_UNREADABLE, unreadableDecision.status)
    }

    private fun coordinator(
        marker: FakeActivationStore,
        migration: RecordingMigrationRunner = RecordingMigrationRunner(reportFor(sourceFingerprint())),
        room: CoreDataRoomVerification = roomVerification(),
        now: () -> Long = { 200L }
    ) = CoreDataActivationCoordinator(
        legacyReader = ::legacyResult,
        migrationRunner = migration,
        roomVerifier = CoreDataRoomActivationVerifier { room },
        activationStore = marker,
        now = now
    )

    private fun legacyResult(): LegacyReadResult = LegacyPreferencesReader(
        MapLegacySource(
            mapOf(
                LegacyPreferencesReader.KEY_ITEMS to fixture("migration/8.3-rc.12/items.json"),
                LegacyPreferencesReader.KEY_TASK_EVENTS to fixture("migration/8.3-rc.12/task_events.json"),
                LegacyPreferencesReader.KEY_GOALS to fixture("migration/8.3-rc.12/goals.json")
            )
        )
    ) { _, _ -> true }.read()

    private fun sourceFingerprint(): String =
        (legacyResult() as LegacyReadResult.Success).snapshot.sourceFingerprint

    private fun coreSnapshot(): CoreDataSnapshot {
        val snapshot = (legacyResult() as LegacyReadResult.Success).snapshot
        return CoreDataSnapshot(
            items = snapshot.tasks.map(TaskEntity::toLegacy),
            taskEvents = snapshot.taskEvents.map { event ->
                event.toLegacy(requireNotNull(TaskEventType.fromKey(event.type)))
            },
            goals = snapshot.plans.map(PlanEntity::toLegacy)
        )
    }

    private fun roomVerification(
        stateFingerprint: String = sourceFingerprint(),
        read: CoreDataReadResult = CoreDataReadResult.Ready(coreSnapshot())
    ): CoreDataRoomVerification {
        val snapshot = (legacyResult() as LegacyReadResult.Success).snapshot
        return CoreDataRoomVerification(
            migrationState = MigrationStateEntity(
                migrationKey = LegacyDataImporter.MIGRATION_KEY,
                sourceDataVersion = snapshot.sourceDataVersion,
                sourceFingerprint = stateFingerprint,
                taskCount = snapshot.tasks.size,
                taskEventCount = snapshot.taskEvents.size,
                planCount = snapshot.plans.size,
                completedAt = 50L
            ),
            readResult = read
        )
    }

    private fun reportFor(
        fingerprint: String,
        status: MigrationStatus = MigrationStatus.IMPORTED
    ): MigrationReport = MigrationReport(
        status = status,
        sourceFingerprint = fingerprint,
        taskCount = 1,
        taskEventCount = 2,
        planCount = 1
    )

    private fun fixture(path: String): String = requireNotNull(javaClass.classLoader?.getResource(path))
        .readText(Charsets.UTF_8)
}

private class RecordingMigrationRunner(var report: MigrationReport) : CoreDataMigrationRunner {
    var calls = 0
    override fun run(): MigrationReport {
        calls++
        return report
    }
}

private enum class WriteMode { PERSIST, NO_OP, THROW }

private class FakeActivationStore(
    var state: CoreDataActivationMarkerState = CoreDataActivationMarkerState.Inactive
) : CoreDataActivationStore {
    var beginMode = WriteMode.PERSIST
    var completeMode = WriteMode.PERSIST
    var failReads = false
    var beginCalls = 0
    var completeCalls = 0

    override fun read(): CoreDataActivationMarkerState {
        if (failReads) error("injected marker read failure")
        return state
    }

    override fun begin(record: CoreDataActivationRecord): Boolean {
        beginCalls++
        return when (beginMode) {
            WriteMode.PERSIST -> {
                state = CoreDataActivationMarkerState.Activating(record)
                true
            }
            WriteMode.NO_OP -> false
            WriteMode.THROW -> error("injected activation start failure")
        }
    }

    override fun complete(record: CoreDataActivationRecord): Boolean {
        completeCalls++
        return when (completeMode) {
            WriteMode.PERSIST -> {
                state = CoreDataActivationMarkerState.Active(record)
                true
            }
            WriteMode.NO_OP -> false
            WriteMode.THROW -> error("injected activation completion failure")
        }
    }
}
