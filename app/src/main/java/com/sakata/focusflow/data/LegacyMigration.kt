package com.sakata.focusflow.data

import android.content.Context
import android.content.SharedPreferences
import com.sakata.focusflow.ActivitySession
import com.sakata.focusflow.CorruptionBackup
import com.sakata.focusflow.ItemsCodec
import com.sakata.focusflow.ProtectedPreferences
import com.sakata.focusflow.StorageProtection
import com.sakata.focusflow.StoredGoalsCodec
import com.sakata.focusflow.TaskEventCodec
import com.sakata.focusflow.TaskEventType
import org.json.JSONArray
import java.io.File
import java.security.MessageDigest

interface LegacyPreferencesSource {
    fun getString(key: String): String?
    fun getInt(key: String, defaultValue: Int): Int
}

class SharedPreferencesLegacySource(private val preferences: SharedPreferences) : LegacyPreferencesSource {
    override fun getString(key: String): String? = preferences.getString(key, null)
    override fun getInt(key: String, defaultValue: Int): Int = preferences.getInt(key, defaultValue)
}

fun interface LegacyCorruptionBackup {
    fun backup(key: String, raw: String): Boolean
}

class FileLegacyCorruptionBackup(private val directory: File) : LegacyCorruptionBackup {
    override fun backup(key: String, raw: String): Boolean {
        val saved = CorruptionBackup.backup(directory, key, raw)
        if (!saved) StorageProtection.backup(directory, key, raw)
        return saved
    }
}

enum class MigrationDiagnosticLevel { INFO, WARNING }

data class MigrationDiagnostic(
    val level: MigrationDiagnosticLevel,
    val domain: String,
    val message: String
)

data class LegacySnapshot(
    val sourceDataVersion: Int,
    val sourceFingerprint: String,
    val hadLegacyPayload: Boolean,
    val tasks: List<TaskEntity>,
    val taskEvents: List<TaskEventEntity>,
    val plans: List<PlanEntity>,
    val recurrenceRules: List<RecurrenceRuleEntity>,
    val taskOccurrences: List<TaskOccurrenceEntity>,
    val activitySessions: List<ActivitySessionEntity>,
    val diagnostics: List<MigrationDiagnostic>
)

sealed interface LegacyReadResult {
    data class Success(val snapshot: LegacySnapshot) : LegacyReadResult
    data class Failure(
        val domain: String,
        val message: String,
        val backupSucceeded: Boolean
    ) : LegacyReadResult
}

/**
 * Reads, validates and maps the old SharedPreferences payload without modifying it.
 * All migrated domains are parsed before Room is touched, so malformed input cannot produce a
 * partially populated database that looks successful.
 */
class LegacyPreferencesReader(
    private val source: LegacyPreferencesSource,
    private val backup: LegacyCorruptionBackup
) {
    fun read(): LegacyReadResult {
        val itemsRaw = source.getString(KEY_ITEMS)
        val eventsRaw = source.getString(KEY_TASK_EVENTS)
        val goalsRaw = source.getString(KEY_GOALS)
        val sessionsRaw = source.getString(KEY_SESSIONS)
        val diagnostics = mutableListOf<MigrationDiagnostic>()

        return try {
            val tasks = decodeTasks(itemsRaw, diagnostics)
            val events = decodeTaskEvents(eventsRaw, tasks, diagnostics)
            val plans = decodePlans(goalsRaw)
            val sessions = decodeActivitySessions(sessionsRaw)
            appendRelationshipDiagnostics(itemsRaw, tasks, plans, diagnostics)
            LegacyReadResult.Success(
                LegacySnapshot(
                    sourceDataVersion = source.getInt(KEY_DATA_VERSION, 1),
                    sourceFingerprint = fingerprint(itemsRaw, eventsRaw, goalsRaw, sessionsRaw),
                    hadLegacyPayload = itemsRaw != null || eventsRaw != null || goalsRaw != null || sessionsRaw != null,
                    tasks = tasks,
                    taskEvents = events,
                    plans = plans,
                    recurrenceRules = emptyList(),
                    taskOccurrences = emptyList(),
                    activitySessions = sessions,
                    diagnostics = diagnostics
                )
            )
        } catch (error: LegacyDecodeException) {
            val raw = when (error.domain) {
                KEY_ITEMS -> itemsRaw
                KEY_TASK_EVENTS -> eventsRaw
                KEY_GOALS -> goalsRaw
                KEY_SESSIONS -> sessionsRaw
                else -> null
            }
            val backedUp = raw == null || !CorruptionBackup.shouldBackup(raw) || backup.backup(error.domain, raw)
            LegacyReadResult.Failure(error.domain, error.message ?: "Legacy data is invalid", backedUp)
        }
    }

    private fun decodeTasks(raw: String?, diagnostics: MutableList<MigrationDiagnostic>): List<TaskEntity> {
        val normalizedRaw = raw?.ifBlank { "[]" } ?: "[]"
        val values = strictArray(KEY_ITEMS, normalizedRaw)
        validatePositiveUniqueIds(KEY_ITEMS, values)
        val decoded = ItemsCodec.decode(normalizedRaw)
        if (decoded.items.size != values.length()) fail(KEY_ITEMS, "Not every item could be decoded")
        if (decoded.idsNormalized) fail(KEY_ITEMS, "Item IDs changed during decoding")
        if (decoded.items.any { it.title.isBlank() }) {
            diagnostics += MigrationDiagnostic(
                MigrationDiagnosticLevel.WARNING,
                KEY_ITEMS,
                "Blank task titles were preserved for manual review"
            )
        }
        return decoded.items.mapIndexed { index, item -> TaskEntity.fromLegacy(item, index) }
    }

    private fun decodeTaskEvents(
        raw: String?,
        tasks: List<TaskEntity>,
        diagnostics: MutableList<MigrationDiagnostic>
    ): List<TaskEventEntity> {
        val normalizedRaw = raw?.ifBlank { "[]" } ?: "[]"
        val values = strictArray(KEY_TASK_EVENTS, normalizedRaw)
        validatePositiveUniqueIds(KEY_TASK_EVENTS, values)
        for (index in 0 until values.length()) {
            val value = values.optJSONObject(index) ?: fail(KEY_TASK_EVENTS, "Entry $index is not an object")
            if (value.optLong("itemId", 0) <= 0) fail(KEY_TASK_EVENTS, "Entry $index has an invalid itemId")
            if (value.optLong("recordedAt", 0) <= 0) fail(KEY_TASK_EVENTS, "Entry $index has an invalid recordedAt")
            if (TaskEventType.fromKey(value.optString("type")) == null) {
                fail(KEY_TASK_EVENTS, "Entry $index has an unknown event type")
            }
        }
        val decoded = TaskEventCodec.decode(normalizedRaw)
        if (decoded.size != values.length()) fail(KEY_TASK_EVENTS, "Not every task event could be decoded")
        val taskIds = tasks.mapTo(hashSetOf()) { it.id }
        val detached = decoded.count { it.itemId !in taskIds }
        if (detached > 0) {
            diagnostics += MigrationDiagnostic(
                MigrationDiagnosticLevel.INFO,
                KEY_TASK_EVENTS,
                "$detached historical events refer to deleted tasks and were preserved"
            )
        }
        return decoded.mapIndexed { index, event -> TaskEventEntity.fromLegacy(event, index) }
    }

    private fun decodePlans(raw: String?): List<PlanEntity> {
        val normalizedRaw = raw?.ifBlank { "[]" } ?: "[]"
        val values = strictArray(KEY_GOALS, normalizedRaw)
        validatePositiveUniqueIds(KEY_GOALS, values)
        val decoded = StoredGoalsCodec.decodeGoals(normalizedRaw)
        if (decoded.size != values.length()) fail(KEY_GOALS, "Not every goal could be decoded")
        return decoded.mapIndexed { index, goal -> PlanEntity.fromLegacy(goal, index) }
    }

    private fun decodeActivitySessions(raw: String?): List<ActivitySessionEntity> {
        val normalizedRaw = raw?.ifBlank { "[]" } ?: "[]"
        val values = strictArray(KEY_SESSIONS, normalizedRaw)
        validatePositiveUniqueIds(KEY_SESSIONS, values)
        return List(values.length()) { index ->
            val value = values.optJSONObject(index) ?: fail(KEY_SESSIONS, "Entry $index is not an object")
            try {
                val id = value.getLong("id")
                val name = value.getString("name")
                val endsAt = value.getLong("endsAt")
                if (endsAt <= 0L) fail(KEY_SESSIONS, "Entry $index has an invalid endsAt")
                val plannedStartAt = value.optLong("plannedStartAt", id)
                val actualStartAt = value.optLong("actualStartAt", id)
                if (plannedStartAt <= 0L || actualStartAt <= 0L) {
                    fail(KEY_SESSIONS, "Entry $index has an invalid start time")
                }
                val status = value.optString("status", ActivitySession.STATUS_ACTIVE)
                if (status !in ACTIVITY_SESSION_STATUSES) {
                    fail(KEY_SESSIONS, "Entry $index has an unknown status")
                }
                val extensionCount = value.optInt("extensionCount")
                if (extensionCount < 0) fail(KEY_SESSIONS, "Entry $index has a negative extension count")
                ActivitySessionEntity.fromLegacy(
                    ActivitySession(
                        id = id,
                        name = name,
                        category = value.optString("category", name),
                        plannedStartAt = plannedStartAt,
                        actualStartAt = actualStartAt,
                        endsAt = endsAt,
                        nextStep = value.optString("nextStep"),
                        status = status,
                        extensionCount = extensionCount,
                        extensionReason = value.optString("extensionReason"),
                        actualEndAt = value.optLong("actualEndAt").takeIf { it > 0L },
                        endChoice = value.optString("endChoice")
                    ),
                    sourceOrder = index
                )
            } catch (error: LegacyDecodeException) {
                throw error
            } catch (error: Exception) {
                fail(KEY_SESSIONS, "Entry $index could not be decoded", error)
            }
        }
    }

    private fun appendRelationshipDiagnostics(
        raw: String?,
        tasks: List<TaskEntity>,
        plans: List<PlanEntity>,
        diagnostics: MutableList<MigrationDiagnostic>
    ) {
        val taskIds = tasks.mapTo(hashSetOf()) { it.id }
        val planIds = plans.mapTo(hashSetOf()) { it.id }
        val danglingPlans = tasks.count { it.planId != null && it.planId !in planIds }
        if (danglingPlans > 0) {
            diagnostics += MigrationDiagnostic(
                MigrationDiagnosticLevel.WARNING,
                KEY_ITEMS,
                "$danglingPlans tasks refer to missing plans; the original plan IDs were preserved"
            )
        }

        val values = strictArray(KEY_ITEMS, raw?.ifBlank { "[]" } ?: "[]")
        var danglingParents = 0
        for (index in 0 until values.length()) {
            val parent = values.getJSONObject(index).optLong("parentCaptureId", 0).takeIf { it > 0 }
            if (parent != null && parent !in taskIds) danglingParents++
        }
        if (danglingParents > 0) {
            diagnostics += MigrationDiagnostic(
                MigrationDiagnosticLevel.WARNING,
                KEY_ITEMS,
                "$danglingParents invalid parent-capture links were cleared by the legacy codec"
            )
        }
    }

    private fun strictArray(domain: String, raw: String): JSONArray = try {
        JSONArray(raw)
    } catch (error: Exception) {
        fail(domain, "Payload is not a valid JSON array", error)
    }

    private fun validatePositiveUniqueIds(domain: String, values: JSONArray) {
        val ids = hashSetOf<Long>()
        for (index in 0 until values.length()) {
            val value = values.optJSONObject(index) ?: fail(domain, "Entry $index is not an object")
            val id = value.optLong("id", 0)
            if (id <= 0) fail(domain, "Entry $index has a missing or non-positive ID")
            if (!ids.add(id)) fail(domain, "Duplicate ID $id")
        }
    }

    private fun fingerprint(vararg values: String?): String {
        val digest = MessageDigest.getInstance("SHA-256")
        values.forEachIndexed { index, value ->
            digest.update(index.toString().toByteArray(Charsets.UTF_8))
            digest.update(byteArrayOf(0))
            digest.update((value ?: "<missing>").toByteArray(Charsets.UTF_8))
            digest.update(byteArrayOf(0))
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    companion object {
        const val KEY_ITEMS = "items"
        const val KEY_TASK_EVENTS = "task_events"
        const val KEY_GOALS = "goals"
        const val KEY_SESSIONS = "sessions"
        const val KEY_DATA_VERSION = "data_version"

        private val ACTIVITY_SESSION_STATUSES = setOf(
            ActivitySession.STATUS_ACTIVE,
            ActivitySession.STATUS_EXTENDED,
            ActivitySession.STATUS_AWAITING_CONFIRMATION,
            ActivitySession.STATUS_COMPLETED,
            ActivitySession.STATUS_SKIPPED
        )

        fun fromContext(context: Context): LegacyPreferencesReader {
            val appContext = context.applicationContext
            val preferences = appContext.getSharedPreferences("focusflow", Context.MODE_PRIVATE)
            return LegacyPreferencesReader(
                SharedPreferencesLegacySource(preferences),
                FileLegacyCorruptionBackup(File(appContext.filesDir, CorruptionBackup.DIR_NAME))
            )
        }
    }
}

private class LegacyDecodeException(val domain: String, message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

private fun fail(domain: String, message: String, cause: Throwable? = null): Nothing =
    throw LegacyDecodeException(domain, message, cause)

data class DatabaseMigrationSummary(
    val taskIds: List<Long>,
    val taskEventIds: List<Long>,
    val planIds: List<Long>,
    val recurrenceRuleIds: List<Long> = emptyList(),
    val taskOccurrenceIds: List<Long> = emptyList(),
    val activitySessionIds: List<Long> = emptyList()
) {
    val taskCount: Int get() = taskIds.size
    val taskEventCount: Int get() = taskEventIds.size
    val planCount: Int get() = planIds.size
    val recurrenceRuleCount: Int get() = recurrenceRuleIds.size
    val taskOccurrenceCount: Int get() = taskOccurrenceIds.size
    val activitySessionCount: Int get() = activitySessionIds.size
    val isEmpty: Boolean get() = taskCount == 0 && taskEventCount == 0 && planCount == 0 &&
        recurrenceRuleCount == 0 && taskOccurrenceCount == 0 && activitySessionCount == 0
}

enum class AtomicImportOutcome { INSERTED, ALREADY_PRESENT, DATABASE_NOT_EMPTY }

interface LegacyMigrationStore {
    fun findState(key: String): MigrationStateEntity?
    fun summary(): DatabaseMigrationSummary
    fun importAtomically(snapshot: LegacySnapshot, state: MigrationStateEntity): AtomicImportOutcome
}

class RoomLegacyMigrationStore(private val database: FocusFlowDatabase) : LegacyMigrationStore {
    override fun findState(key: String): MigrationStateEntity? = database.migrationStateDao().find(key)

    override fun summary(): DatabaseMigrationSummary = DatabaseMigrationSummary(
        taskIds = database.taskDao().allIds(),
        taskEventIds = database.taskEventDao().allIds(),
        planIds = database.planDao().allIds(),
        recurrenceRuleIds = database.recurrenceRuleDao().allIds(),
        taskOccurrenceIds = database.taskOccurrenceDao().allIds(),
        activitySessionIds = database.activitySessionDao().allIds()
    )

    override fun importAtomically(
        snapshot: LegacySnapshot,
        state: MigrationStateEntity
    ): AtomicImportOutcome {
        var outcome = AtomicImportOutcome.INSERTED
        database.runInTransaction {
            if (database.migrationStateDao().find(state.migrationKey) != null) {
                outcome = AtomicImportOutcome.ALREADY_PRESENT
                return@runInTransaction
            }
            val current = summary()
            if (!current.isEmpty) {
                outcome = AtomicImportOutcome.DATABASE_NOT_EMPTY
                return@runInTransaction
            }
            database.planDao().insertAll(snapshot.plans)
            database.taskDao().insertAll(snapshot.tasks)
            database.taskEventDao().insertAll(snapshot.taskEvents)
            database.recurrenceRuleDao().insertAll(snapshot.recurrenceRules)
            database.taskOccurrenceDao().insertAll(snapshot.taskOccurrences)
            database.activitySessionDao().insertAll(snapshot.activitySessions)
            database.migrationStateDao().insert(state)
        }
        return outcome
    }
}

interface MigrationCompletionMarker {
    fun markComplete(report: MigrationReport): Boolean
}

class SharedPreferencesMigrationMarker(context: Context) : MigrationCompletionMarker {
    private val preferences = ProtectedPreferences(
        context.applicationContext.getSharedPreferences("focusflow", Context.MODE_PRIVATE)
    )

    override fun markComplete(report: MigrationReport): Boolean = preferences.edit()
        .putBoolean(LegacyDataImporter.MIGRATION_KEY, true)
        .putInt(LegacyPreferencesReader.KEY_DATA_VERSION, LegacyDataImporter.TARGET_DATA_VERSION)
        .putString("${LegacyDataImporter.MIGRATION_KEY}_fingerprint", report.sourceFingerprint)
        .commit()
}

enum class MigrationStatus {
    IMPORTED,
    INITIALIZED_EMPTY,
    ALREADY_IMPORTED,
    BLOCKED_CORRUPT_SOURCE,
    BLOCKED_SOURCE_CHANGED,
    BLOCKED_DATABASE_NOT_EMPTY,
    DATABASE_WRITE_FAILED,
    VERIFICATION_FAILED,
    MARKER_WRITE_FAILED
}

data class MigrationReport(
    val status: MigrationStatus,
    val sourceFingerprint: String = "",
    val taskCount: Int = 0,
    val taskEventCount: Int = 0,
    val planCount: Int = 0,
    val recurrenceRuleCount: Int = 0,
    val taskOccurrenceCount: Int = 0,
    val activitySessionCount: Int = 0,
    val diagnostics: List<MigrationDiagnostic> = emptyList(),
    val message: String = ""
)

/**
 * Coordinates the one-time import. Callers must run this off the main thread.
 * The database state is committed first and verified by IDs/counts; only then is the old
 * SharedPreferences data_version marker advanced. A marker-write failure is safely retryable.
 */
class LegacyDataImporter(
    private val reader: LegacyPreferencesReader,
    private val store: LegacyMigrationStore,
    private val marker: MigrationCompletionMarker,
    private val now: () -> Long = System::currentTimeMillis
) {
    fun importIfNeeded(): MigrationReport {
        val read = reader.read()
        if (read is LegacyReadResult.Failure) {
            return MigrationReport(
                status = MigrationStatus.BLOCKED_CORRUPT_SOURCE,
                message = "${read.domain}: ${read.message}; backup=${read.backupSucceeded}"
            )
        }
        val snapshot = (read as LegacyReadResult.Success).snapshot
        val expected = expectedSummary(snapshot)
        val existing = store.findState(MIGRATION_KEY)
        if (existing != null) {
            if (existing.sourceFingerprint != snapshot.sourceFingerprint) {
                return report(snapshot, MigrationStatus.BLOCKED_SOURCE_CHANGED, "Legacy payload changed after import")
            }
            if (!stateMatches(snapshot, existing)) {
                return report(snapshot, MigrationStatus.VERIFICATION_FAILED, "Migration state counts do not match the source")
            }
            if (!matches(expected, store.summary())) {
                return report(snapshot, MigrationStatus.VERIFICATION_FAILED, "Stored IDs or counts do not match the source")
            }
            val already = report(snapshot, MigrationStatus.ALREADY_IMPORTED)
            return if (marker.markComplete(already)) already
            else already.copy(status = MigrationStatus.MARKER_WRITE_FAILED, message = "Database is valid but completion marker could not be written")
        }

        if (!store.summary().isEmpty) {
            return report(snapshot, MigrationStatus.BLOCKED_DATABASE_NOT_EMPTY, "Database has data without a migration state")
        }

        val state = MigrationStateEntity(
            migrationKey = MIGRATION_KEY,
            sourceDataVersion = snapshot.sourceDataVersion,
            sourceFingerprint = snapshot.sourceFingerprint,
            taskCount = snapshot.tasks.size,
            taskEventCount = snapshot.taskEvents.size,
            planCount = snapshot.plans.size,
            completedAt = now(),
            recurrenceRuleCount = snapshot.recurrenceRules.size,
            taskOccurrenceCount = snapshot.taskOccurrences.size,
            activitySessionCount = snapshot.activitySessions.size
        )
        val outcome = try {
            store.importAtomically(snapshot, state)
        } catch (error: Exception) {
            return report(snapshot, MigrationStatus.DATABASE_WRITE_FAILED, error.message ?: error.javaClass.simpleName)
        }
        if (outcome == AtomicImportOutcome.DATABASE_NOT_EMPTY) {
            return report(snapshot, MigrationStatus.BLOCKED_DATABASE_NOT_EMPTY, "Database changed before the transaction")
        }
        if (!matches(expected, store.summary())) {
            return report(snapshot, MigrationStatus.VERIFICATION_FAILED, "Post-transaction ID or count verification failed")
        }
        val successStatus = if (snapshot.hadLegacyPayload) MigrationStatus.IMPORTED else MigrationStatus.INITIALIZED_EMPTY
        val success = report(snapshot, successStatus)
        return if (marker.markComplete(success)) success
        else success.copy(status = MigrationStatus.MARKER_WRITE_FAILED, message = "Database committed but completion marker could not be written")
    }

    private fun report(snapshot: LegacySnapshot, status: MigrationStatus, message: String = "") = MigrationReport(
        status = status,
        sourceFingerprint = snapshot.sourceFingerprint,
        taskCount = snapshot.tasks.size,
        taskEventCount = snapshot.taskEvents.size,
        planCount = snapshot.plans.size,
        recurrenceRuleCount = snapshot.recurrenceRules.size,
        taskOccurrenceCount = snapshot.taskOccurrences.size,
        activitySessionCount = snapshot.activitySessions.size,
        diagnostics = snapshot.diagnostics,
        message = message
    )

    private fun expectedSummary(snapshot: LegacySnapshot) = DatabaseMigrationSummary(
        taskIds = snapshot.tasks.map { it.id }.sorted(),
        taskEventIds = snapshot.taskEvents.map { it.id }.sorted(),
        planIds = snapshot.plans.map { it.id }.sorted(),
        recurrenceRuleIds = snapshot.recurrenceRules.map { it.id }.sorted(),
        taskOccurrenceIds = snapshot.taskOccurrences.map { it.id }.sorted(),
        activitySessionIds = snapshot.activitySessions.map { it.id }.sorted()
    )

    private fun matches(expected: DatabaseMigrationSummary, actual: DatabaseMigrationSummary): Boolean =
        expected == actual.copy(
            taskIds = actual.taskIds.sorted(),
            taskEventIds = actual.taskEventIds.sorted(),
            planIds = actual.planIds.sorted(),
            recurrenceRuleIds = actual.recurrenceRuleIds.sorted(),
            taskOccurrenceIds = actual.taskOccurrenceIds.sorted(),
            activitySessionIds = actual.activitySessionIds.sorted()
        )

    private fun stateMatches(snapshot: LegacySnapshot, state: MigrationStateEntity): Boolean =
        state.taskCount == snapshot.tasks.size &&
            state.taskEventCount == snapshot.taskEvents.size &&
            state.planCount == snapshot.plans.size &&
            state.recurrenceRuleCount == snapshot.recurrenceRules.size &&
            state.taskOccurrenceCount == snapshot.taskOccurrences.size &&
            state.activitySessionCount == snapshot.activitySessions.size

    companion object {
        const val MIGRATION_KEY = "room_migration_v9_0_task_plan_complete"
        const val TARGET_DATA_VERSION = 2
    }
}
