package com.sakata.focusflow.data

import android.content.Context
import android.content.SharedPreferences
import com.sakata.focusflow.ProtectedPreferences
import com.sakata.focusflow.TaskEventType
import java.util.concurrent.Callable

enum class CoreDataRuntimeSource { LEGACY, ROOM, NONE }

enum class CoreDataActivationStatus {
    LEGACY_ACTIVATION_DISABLED,
    LEGACY_ACTIVATION_NOT_STARTED,
    LEGACY_ACTIVATION_BLOCKED,
    ROOM_ACTIVATED,
    ROOM_ALREADY_ACTIVE,
    BLOCKED_MARKER_UNREADABLE,
    BLOCKED_MARKER_INVALID,
    BLOCKED_SOURCE_CHANGED,
    BLOCKED_MIGRATION,
    BLOCKED_ROOM_INVALID,
    BLOCKED_ROOM_MISMATCH,
    BLOCKED_ACTIVATION_INCOMPLETE,
    BLOCKED_ACTIVATION_UNCERTAIN,
    BLOCKED_RUNTIME_ASSEMBLY
}

data class CoreDataActivationDecision(
    val source: CoreDataRuntimeSource,
    val status: CoreDataActivationStatus,
    val message: String = "",
    val migrationStatus: MigrationStatus? = null,
    val consistencyStatus: CoreDataConsistencyStatus? = null
)

data class CoreDataActivationRecord(
    val sourceFingerprint: String,
    val startedAt: Long,
    val activatedAt: Long? = null
)

sealed interface CoreDataActivationMarkerState {
    data object Inactive : CoreDataActivationMarkerState
    data class Activating(val record: CoreDataActivationRecord) : CoreDataActivationMarkerState
    data class Active(val record: CoreDataActivationRecord) : CoreDataActivationMarkerState
    data class Invalid(val reason: String) : CoreDataActivationMarkerState
}

interface CoreDataActivationStore {
    fun read(): CoreDataActivationMarkerState
    fun begin(record: CoreDataActivationRecord): Boolean
    fun complete(record: CoreDataActivationRecord): Boolean
}

/**
 * Separate from the import-complete marker: this state is the authority for runtime source choice.
 * It is monotonic. There is deliberately no API that changes ACTIVE back to INACTIVE.
 */
class SharedPreferencesCoreDataActivationStore(context: Context) : CoreDataActivationStore {
    private val preferences: SharedPreferences = ProtectedPreferences(
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    )

    override fun read(): CoreDataActivationMarkerState {
        val phase = preferences.getString(KEY_PHASE, null)
        if (phase == null) {
            return if (preferences.contains(KEY_FINGERPRINT) ||
                preferences.contains(KEY_STARTED_AT) ||
                preferences.contains(KEY_ACTIVATED_AT)
            ) {
                CoreDataActivationMarkerState.Invalid("activation metadata exists without a phase")
            } else {
                CoreDataActivationMarkerState.Inactive
            }
        }

        val fingerprint = preferences.getString(KEY_FINGERPRINT, null).orEmpty()
        val startedAt = preferences.getLong(KEY_STARTED_AT, 0L)
        val activatedAt = preferences.getLong(KEY_ACTIVATED_AT, 0L)
        if (fingerprint.isBlank() || startedAt <= 0L) {
            return CoreDataActivationMarkerState.Invalid("activation metadata is incomplete")
        }
        return when (phase) {
            PHASE_ACTIVATING -> if (activatedAt == 0L) {
                CoreDataActivationMarkerState.Activating(
                    CoreDataActivationRecord(fingerprint, startedAt)
                )
            } else {
                CoreDataActivationMarkerState.Invalid("activating state has an activation time")
            }
            PHASE_ACTIVE -> if (activatedAt > 0L) {
                CoreDataActivationMarkerState.Active(
                    CoreDataActivationRecord(fingerprint, startedAt, activatedAt)
                )
            } else {
                CoreDataActivationMarkerState.Invalid("active state has no activation time")
            }
            else -> CoreDataActivationMarkerState.Invalid("activation phase is unknown")
        }
    }

    @Synchronized
    override fun begin(record: CoreDataActivationRecord): Boolean {
        if (record.sourceFingerprint.isBlank() || record.startedAt <= 0L || record.activatedAt != null) {
            return false
        }
        if (read() !is CoreDataActivationMarkerState.Inactive) return false
        return preferences.edit()
            .putString(KEY_PHASE, PHASE_ACTIVATING)
            .putString(KEY_FINGERPRINT, record.sourceFingerprint)
            .putLong(KEY_STARTED_AT, record.startedAt)
            .remove(KEY_ACTIVATED_AT)
            .commit()
    }

    @Synchronized
    override fun complete(record: CoreDataActivationRecord): Boolean {
        val current = read()
        if (current !is CoreDataActivationMarkerState.Activating ||
            current.record.sourceFingerprint != record.sourceFingerprint ||
            current.record.startedAt != record.startedAt ||
            record.activatedAt == null || record.activatedAt <= 0L
        ) {
            return false
        }
        return preferences.edit()
            .putString(KEY_PHASE, PHASE_ACTIVE)
            .putString(KEY_FINGERPRINT, record.sourceFingerprint)
            .putLong(KEY_STARTED_AT, record.startedAt)
            .putLong(KEY_ACTIVATED_AT, record.activatedAt)
            .commit()
    }

    companion object {
        private const val PREFERENCES_NAME = "focusflow"
        private const val KEY_PHASE = "room_core_data_source_phase_v1"
        private const val KEY_FINGERPRINT = "room_core_data_source_fingerprint_v1"
        private const val KEY_STARTED_AT = "room_core_data_source_started_at_v1"
        private const val KEY_ACTIVATED_AT = "room_core_data_source_activated_at_v1"
        private const val PHASE_ACTIVATING = "activating"
        private const val PHASE_ACTIVE = "active"
    }
}

fun interface CoreDataMigrationRunner {
    fun run(): MigrationReport
}

data class CoreDataRoomVerification(
    val migrationState: MigrationStateEntity?,
    val readResult: CoreDataReadResult
)

fun interface CoreDataRoomActivationVerifier {
    fun verify(): CoreDataRoomVerification
}

/** Reads the migration state and all core tables in one Room transaction. */
class DatabaseCoreDataRoomActivationVerifier(
    private val database: FocusFlowDatabase
) : CoreDataRoomActivationVerifier {
    override fun verify(): CoreDataRoomVerification = database.runInTransaction(Callable {
        val source = DatabaseRoomCoreDataSource(database)
        CoreDataRoomVerification(
            migrationState = source.migrationState(),
            readResult = RoomCoreDataReadRepository(source).read()
        )
    })
}

/**
 * Chooses exactly one runtime source while coordinating the one-time migration cutover.
 *
 * The caller must run this before exposing any core-data writer. Once ACTIVATING is durable, the
 * legacy source is never returned: failures are blocked and retried instead of risking split-brain
 * writes. Once ACTIVE is durable, Room is authoritative and an invalid database fails closed.
 */
class CoreDataActivationCoordinator(
    private val legacyReader: () -> LegacyReadResult,
    private val migrationRunner: CoreDataMigrationRunner,
    private val roomVerifier: CoreDataRoomActivationVerifier,
    private val activationStore: CoreDataActivationStore,
    private val now: () -> Long = System::currentTimeMillis
) {
    fun selectSource(activationEnabled: Boolean): CoreDataActivationDecision {
        return when (val marker = readMarker()) {
            is MarkerRead.Failed -> blocked(
                CoreDataActivationStatus.BLOCKED_MARKER_UNREADABLE,
                marker.message
            )
            is MarkerRead.Value -> when (val state = marker.state) {
                CoreDataActivationMarkerState.Inactive -> {
                    if (!activationEnabled) {
                        legacy(CoreDataActivationStatus.LEGACY_ACTIVATION_DISABLED)
                    } else {
                        startActivation()
                    }
                }
                is CoreDataActivationMarkerState.Activating -> resumeActivation(state.record)
                is CoreDataActivationMarkerState.Active -> validateActiveRoom(state.record)
                is CoreDataActivationMarkerState.Invalid -> blocked(
                    CoreDataActivationStatus.BLOCKED_MARKER_INVALID,
                    state.reason
                )
            }
        }
    }

    private fun startActivation(): CoreDataActivationDecision {
        val legacyRead = readLegacy()
        if (legacyRead is LegacyActivationRead.Failed) {
            return legacy(
                CoreDataActivationStatus.LEGACY_ACTIVATION_BLOCKED,
                legacyRead.message
            )
        }
        val ready = legacyRead as LegacyActivationRead.Ready
        val record = CoreDataActivationRecord(
            sourceFingerprint = ready.fingerprint,
            startedAt = now()
        )
        try {
            activationStore.begin(record)
        } catch (error: Exception) {
            return blocked(
                CoreDataActivationStatus.BLOCKED_ACTIVATION_UNCERTAIN,
                "activation start write failed: ${error.javaClass.simpleName}"
            )
        }

        return when (val persisted = readMarker()) {
            is MarkerRead.Failed -> blocked(
                CoreDataActivationStatus.BLOCKED_ACTIVATION_UNCERTAIN,
                persisted.message
            )
            is MarkerRead.Value -> when (val state = persisted.state) {
                CoreDataActivationMarkerState.Inactive -> legacy(
                    CoreDataActivationStatus.LEGACY_ACTIVATION_NOT_STARTED,
                    "activation start was not persisted"
                )
                is CoreDataActivationMarkerState.Activating -> {
                    if (state.record != record) markerConflict()
                    else resumeActivation(record, ready)
                }
                is CoreDataActivationMarkerState.Active -> {
                    if (!sameIntent(state.record, record)) markerConflict()
                    else validateActiveRoom(state.record)
                }
                is CoreDataActivationMarkerState.Invalid -> blocked(
                    CoreDataActivationStatus.BLOCKED_ACTIVATION_UNCERTAIN,
                    state.reason
                )
            }
        }
    }

    private fun resumeActivation(
        record: CoreDataActivationRecord,
        previouslyRead: LegacyActivationRead.Ready? = null
    ): CoreDataActivationDecision {
        if (!validIntent(record)) return markerConflict()
        val legacyRead = previouslyRead ?: readLegacy()
        if (legacyRead is LegacyActivationRead.Failed) {
            return blocked(CoreDataActivationStatus.BLOCKED_MIGRATION, legacyRead.message)
        }
        val ready = legacyRead as LegacyActivationRead.Ready
        if (ready.fingerprint != record.sourceFingerprint) {
            return blocked(
                CoreDataActivationStatus.BLOCKED_SOURCE_CHANGED,
                "legacy source changed after activation started"
            )
        }

        val migration = try {
            migrationRunner.run()
        } catch (error: Exception) {
            return blocked(
                CoreDataActivationStatus.BLOCKED_MIGRATION,
                "migration failed: ${error.javaClass.simpleName}"
            )
        }
        if (migration.status !in MIGRATION_READY_STATUSES ||
            migration.sourceFingerprint != record.sourceFingerprint
        ) {
            return blocked(
                CoreDataActivationStatus.BLOCKED_MIGRATION,
                migration.message.ifBlank { "migration is not ready for activation" },
                migrationStatus = migration.status
            )
        }

        val room = verifyRoom(record.sourceFingerprint)
        if (room.decision != null) return room.decision
        val readResult = requireNotNull(room.readResult)
        val consistency = CoreDataConsistencyChecker.compare(ready.snapshot, readResult)
        if (consistency.status != CoreDataConsistencyStatus.CONSISTENT) {
            return blocked(
                CoreDataActivationStatus.BLOCKED_ROOM_MISMATCH,
                consistency.message.ifBlank { "Room does not match the frozen legacy source" },
                migrationStatus = migration.status,
                consistencyStatus = consistency.status
            )
        }

        val completed = record.copy(activatedAt = now())
        try {
            activationStore.complete(completed)
        } catch (error: Exception) {
            return blocked(
                CoreDataActivationStatus.BLOCKED_ACTIVATION_UNCERTAIN,
                "activation completion write failed: ${error.javaClass.simpleName}",
                migrationStatus = migration.status,
                consistencyStatus = consistency.status
            )
        }
        return finishActivation(completed, migration.status, consistency.status)
    }

    private fun finishActivation(
        expected: CoreDataActivationRecord,
        migrationStatus: MigrationStatus,
        consistencyStatus: CoreDataConsistencyStatus
    ): CoreDataActivationDecision = when (val persisted = readMarker()) {
        is MarkerRead.Failed -> blocked(
            CoreDataActivationStatus.BLOCKED_ACTIVATION_UNCERTAIN,
            persisted.message,
            migrationStatus,
            consistencyStatus
        )
        is MarkerRead.Value -> when (val state = persisted.state) {
            is CoreDataActivationMarkerState.Active -> {
                if (state.record == expected) {
                    CoreDataActivationDecision(
                        source = CoreDataRuntimeSource.ROOM,
                        status = CoreDataActivationStatus.ROOM_ACTIVATED,
                        migrationStatus = migrationStatus,
                        consistencyStatus = consistencyStatus
                    )
                } else markerConflict()
            }
            is CoreDataActivationMarkerState.Activating -> blocked(
                CoreDataActivationStatus.BLOCKED_ACTIVATION_INCOMPLETE,
                "activation completion was not persisted",
                migrationStatus,
                consistencyStatus
            )
            CoreDataActivationMarkerState.Inactive -> blocked(
                CoreDataActivationStatus.BLOCKED_ACTIVATION_UNCERTAIN,
                "activation state disappeared",
                migrationStatus,
                consistencyStatus
            )
            is CoreDataActivationMarkerState.Invalid -> blocked(
                CoreDataActivationStatus.BLOCKED_ACTIVATION_UNCERTAIN,
                state.reason,
                migrationStatus,
                consistencyStatus
            )
        }
    }

    private fun validateActiveRoom(record: CoreDataActivationRecord): CoreDataActivationDecision {
        if (!validActive(record)) return markerConflict()
        val room = verifyRoom(record.sourceFingerprint)
        return room.decision ?: CoreDataActivationDecision(
            source = CoreDataRuntimeSource.ROOM,
            status = CoreDataActivationStatus.ROOM_ALREADY_ACTIVE
        )
    }

    private data class RoomCheck(
        val readResult: CoreDataReadResult? = null,
        val decision: CoreDataActivationDecision? = null
    )

    private fun verifyRoom(expectedFingerprint: String): RoomCheck {
        val verification = try {
            roomVerifier.verify()
        } catch (error: Exception) {
            return RoomCheck(decision = blocked(
                CoreDataActivationStatus.BLOCKED_ROOM_INVALID,
                "Room verification failed: ${error.javaClass.simpleName}"
            ))
        }
        val state = verification.migrationState
        if (state == null || state.migrationKey != LegacyDataImporter.MIGRATION_KEY) {
            return RoomCheck(decision = blocked(
                CoreDataActivationStatus.BLOCKED_ROOM_INVALID,
                "Room migration state is missing"
            ))
        }
        if (state.sourceFingerprint != expectedFingerprint) {
            return RoomCheck(decision = blocked(
                CoreDataActivationStatus.BLOCKED_ROOM_INVALID,
                "Room migration fingerprint does not match activation"
            ))
        }
        return when (val read = verification.readResult) {
            is CoreDataReadResult.Ready -> RoomCheck(readResult = read)
            is CoreDataReadResult.NotReady -> RoomCheck(decision = blocked(
                CoreDataActivationStatus.BLOCKED_ROOM_INVALID,
                read.reason
            ))
            is CoreDataReadResult.Invalid -> RoomCheck(decision = blocked(
                CoreDataActivationStatus.BLOCKED_ROOM_INVALID,
                read.reason
            ))
        }
    }

    private sealed interface LegacyActivationRead {
        data class Ready(val fingerprint: String, val snapshot: CoreDataSnapshot) : LegacyActivationRead
        data class Failed(val message: String) : LegacyActivationRead
    }

    private fun readLegacy(): LegacyActivationRead {
        val read = try {
            legacyReader()
        } catch (error: Exception) {
            return LegacyActivationRead.Failed("legacy read failed: ${error.javaClass.simpleName}")
        }
        if (read is LegacyReadResult.Failure) {
            return LegacyActivationRead.Failed(
                "${read.domain}: ${read.message}; backup=${read.backupSucceeded}"
            )
        }
        val snapshot = (read as LegacyReadResult.Success).snapshot
        return try {
            val events = snapshot.taskEvents.map { entity ->
                val type = TaskEventType.fromKey(entity.type)
                    ?: return LegacyActivationRead.Failed("legacy task event type is invalid")
                entity.toLegacy(type)
            }
            LegacyActivationRead.Ready(
                fingerprint = snapshot.sourceFingerprint,
                snapshot = CoreDataSnapshot(
                    items = snapshot.tasks.map(TaskEntity::toLegacy),
                    taskEvents = events,
                    goals = snapshot.plans.map(PlanEntity::toLegacy),
                    activitySessions = snapshot.activitySessions.map(ActivitySessionEntity::toLegacy),
                    courses = snapshot.courses.zip(snapshot.courseMeetingRules).map { (parent, rule) ->
                        rule.toLegacy(parent)
                    }
                )
            )
        } catch (error: Exception) {
            LegacyActivationRead.Failed("legacy mapping failed: ${error.javaClass.simpleName}")
        }
    }

    private sealed interface MarkerRead {
        data class Value(val state: CoreDataActivationMarkerState) : MarkerRead
        data class Failed(val message: String) : MarkerRead
    }

    private fun readMarker(): MarkerRead = try {
        MarkerRead.Value(activationStore.read())
    } catch (error: Exception) {
        MarkerRead.Failed("activation marker read failed: ${error.javaClass.simpleName}")
    }

    private fun validIntent(record: CoreDataActivationRecord): Boolean =
        record.sourceFingerprint.isNotBlank() && record.startedAt > 0L && record.activatedAt == null

    private fun validActive(record: CoreDataActivationRecord): Boolean =
        record.sourceFingerprint.isNotBlank() && record.startedAt > 0L && (record.activatedAt ?: 0L) > 0L

    private fun sameIntent(left: CoreDataActivationRecord, right: CoreDataActivationRecord): Boolean =
        left.sourceFingerprint == right.sourceFingerprint && left.startedAt == right.startedAt

    private fun markerConflict() = blocked(
        CoreDataActivationStatus.BLOCKED_ACTIVATION_UNCERTAIN,
        "activation marker does not match the current attempt"
    )

    private fun legacy(status: CoreDataActivationStatus, message: String = "") =
        CoreDataActivationDecision(CoreDataRuntimeSource.LEGACY, status, message)

    private fun blocked(
        status: CoreDataActivationStatus,
        message: String,
        migrationStatus: MigrationStatus? = null,
        consistencyStatus: CoreDataConsistencyStatus? = null
    ) = CoreDataActivationDecision(
        source = CoreDataRuntimeSource.NONE,
        status = status,
        message = message,
        migrationStatus = migrationStatus,
        consistencyStatus = consistencyStatus
    )

    companion object {
        private val MIGRATION_READY_STATUSES = setOf(
            MigrationStatus.IMPORTED,
            MigrationStatus.INITIALIZED_EMPTY,
            MigrationStatus.ALREADY_IMPORTED
        )
    }
}
