package com.sakata.focusflow.data

import android.content.Context
import com.sakata.focusflow.Course
import com.sakata.focusflow.CourseLocationOverrides
import com.sakata.focusflow.CourseReminders

/**
 * Stage 7.5, Legacy storage integration batch.
 *
 * This file adds the real persistence boundary for course recovery groups plus the callable
 * business-layer operations. It deliberately covers the Legacy path only: the Room path answers
 * with an explicit "not ready" result (see [UnsupportedCourseRecoveryStore]) until its own batch
 * adds a table and a migration. Preferences are read for the snapshot but never written here.
 */

/** Reads the stored recovery payload. A missing key is a legitimate empty list; corruption is not. */
internal sealed interface CourseRecoveryGroupsRead {
    data class Ready(val groups: List<CourseRecoveryGroup>) : CourseRecoveryGroupsRead

    /** Unreadable payload. [raw] is preserved for diagnosis; nothing may overwrite it silently. */
    data class Invalid(val reason: String, val raw: String) : CourseRecoveryGroupsRead

    /** The selected runtime cannot store course recovery groups yet. */
    data class NotReady(val reason: String) : CourseRecoveryGroupsRead
}

/** Storage primitives the recovery store needs. The real implementation is [PrototypeStore]. */
internal interface CourseRecoveryStorage {
    fun isStorageReadOnly(): Boolean

    /** Runs [block] under the same lock as every other course/task write. */
    fun <T> withCourseWriteLock(block: () -> T): T

    fun readCourses(): List<Course>

    fun loadCourseRecoveryGroups(): CourseRecoveryGroupsRead

    /** Read-only probe of the merge/split journals; an unfinished one blocks course writes. */
    fun hasPendingCourseEditJournal(): Boolean

    /** Commits courses and groups in ONE editor commit after re-checking both snapshots. */
    fun commitCoursesAndRecoveryGroups(
        courses: List<Course>,
        groups: List<CourseRecoveryGroup>,
        expectedCourses: List<Course>,
        expectedGroups: List<CourseRecoveryGroup>
    ): Boolean

    /** Commits only the group payload (state transitions and purge). */
    fun commitCourseRecoveryGroups(
        groups: List<CourseRecoveryGroup>,
        expectedGroups: List<CourseRecoveryGroup>
    ): Boolean
}

internal sealed interface CourseDeletionOutcome {
    /** Courses and the new ACTIVE group are committed together. */
    data class Applied(val group: CourseRecoveryGroup) : CourseDeletionOutcome

    /** The same operationId was already committed; nothing was written again. */
    data class AlreadyApplied(val group: CourseRecoveryGroup) : CourseDeletionOutcome

    data class Rejected(
        val reason: String,
        val status: CoreDataWriteStatus = CoreDataWriteStatus.INVALID_STATE
    ) : CourseDeletionOutcome

    /** Confirmed not written: the stored snapshot still matches the pre-write state. */
    data class WriteFailed(val reason: String) : CourseDeletionOutcome

    /** The commit reported failure and the stored state could not be classified. */
    data class WriteUncertain(val reason: String) : CourseDeletionOutcome

    data class NotReady(val reason: String) : CourseDeletionOutcome
}

internal sealed interface CourseRestoreOutcome {
    /** Core courses and the RESTORING state are committed together; follow-up steps are still open. */
    data class CoreCommitted(val group: CourseRecoveryGroup) : CourseRestoreOutcome

    /** A persisted RESTORING group: resume the follow-up steps, do not redo the core restore. */
    data class NeedsFollowUp(val group: CourseRecoveryGroup) : CourseRestoreOutcome

    /** The group already reached its terminal state; nothing was written. */
    data class AlreadyRestored(val group: CourseRecoveryGroup) : CourseRestoreOutcome

    /** A persisted PURGING group: resume the purge follow-up. */
    data class NeedsPurgeFollowUp(val group: CourseRecoveryGroup) : CourseRestoreOutcome

    data class Rejected(
        val reason: String,
        val status: CoreDataWriteStatus = CoreDataWriteStatus.INVALID_STATE
    ) : CourseRestoreOutcome

    data class WriteFailed(val reason: String) : CourseRestoreOutcome

    data class WriteUncertain(val reason: String) : CourseRestoreOutcome

    data class NotReady(val reason: String) : CourseRestoreOutcome
}

internal sealed interface CoursePurgeOutcome {
    /** Only these group records were removed; courses and preferences were untouched. */
    data class Applied(val removedGroupIds: Set<String>) : CoursePurgeOutcome

    data object NothingToDo : CoursePurgeOutcome

    data class Rejected(
        val reason: String,
        val status: CoreDataWriteStatus = CoreDataWriteStatus.INVALID_STATE
    ) : CoursePurgeOutcome

    data class WriteFailed(val reason: String) : CoursePurgeOutcome

    data class WriteUncertain(val reason: String) : CoursePurgeOutcome

    data class NotReady(val reason: String) : CoursePurgeOutcome
}

internal interface CourseRecoveryStore {
    fun read(): CourseRecoveryGroupsRead

    fun deleteCoursesWithRecoveryGroup(
        expectedCourses: List<Course>,
        request: CourseRecoveryRequest
    ): CourseDeletionOutcome

    fun restoreCourseRecoveryGroup(
        groupId: String,
        expectedGroups: List<CourseRecoveryGroup>,
        now: Long
    ): CourseRestoreOutcome

    fun purgeCourseRecoveryGroups(
        expectedGroups: List<CourseRecoveryGroup>,
        now: Long
    ): CoursePurgeOutcome
}

/** Room (and any other runtime without a course recovery table yet) must fail loudly, not silently. */
internal object UnsupportedCourseRecoveryStore : CourseRecoveryStore {
    const val REASON = "course recovery groups are not implemented on the Room path yet"

    override fun read(): CourseRecoveryGroupsRead = CourseRecoveryGroupsRead.NotReady(REASON)

    override fun deleteCoursesWithRecoveryGroup(
        expectedCourses: List<Course>,
        request: CourseRecoveryRequest
    ): CourseDeletionOutcome = CourseDeletionOutcome.NotReady(REASON)

    override fun restoreCourseRecoveryGroup(
        groupId: String,
        expectedGroups: List<CourseRecoveryGroup>,
        now: Long
    ): CourseRestoreOutcome = CourseRestoreOutcome.NotReady(REASON)

    override fun purgeCourseRecoveryGroups(
        expectedGroups: List<CourseRecoveryGroup>,
        now: Long
    ): CoursePurgeOutcome = CoursePurgeOutcome.NotReady(REASON)
}

/**
 * Legacy implementation. Every operation runs inside one [CourseRecoveryStorage.withCourseWriteLock]
 * scope, re-reads the live state instead of trusting the caller, and reuses the frozen pure model
 * ([CourseRecoveryJournal]) for capture, eligibility and purge selection.
 */
internal class LegacyCourseRecoveryStore(
    private val storage: CourseRecoveryStorage
) : CourseRecoveryStore {

    override fun read(): CourseRecoveryGroupsRead = storage.loadCourseRecoveryGroups()

    override fun deleteCoursesWithRecoveryGroup(
        expectedCourses: List<Course>,
        request: CourseRecoveryRequest
    ): CourseDeletionOutcome = storage.withCourseWriteLock {
        if (storage.isStorageReadOnly()) {
            return@withCourseWriteLock CourseDeletionOutcome.Rejected(
                "storage is read-only", CoreDataWriteStatus.NOT_READY
            )
        }
        val groups = when (val read = storage.loadCourseRecoveryGroups()) {
            is CourseRecoveryGroupsRead.Ready -> read.groups
            is CourseRecoveryGroupsRead.Invalid -> return@withCourseWriteLock CourseDeletionOutcome.Rejected(
                "course recovery payload is unreadable: ${read.reason}"
            )
            is CourseRecoveryGroupsRead.NotReady -> return@withCourseWriteLock CourseDeletionOutcome.NotReady(read.reason)
        }
        // An unfinished merge/split edit owns the course list; the group would capture a half state.
        if (storage.hasPendingCourseEditJournal()) {
            return@withCourseWriteLock CourseDeletionOutcome.Rejected(
                "unfinished course merge/split journal", CoreDataWriteStatus.CONDITION_NOT_MET
            )
        }
        // Identity guard: the same operation must not create a second group or delete twice.
        groups.firstOrNull { it.operationId == request.operationId }?.let {
            return@withCourseWriteLock CourseDeletionOutcome.AlreadyApplied(it)
        }
        val current = try {
            storage.readCourses()
        } catch (error: Exception) {
            return@withCourseWriteLock CourseDeletionOutcome.Rejected(
                "course snapshot is unreadable: ${error.javaClass.simpleName}"
            )
        }
        if (current != expectedCourses) {
            return@withCourseWriteLock CourseDeletionOutcome.Rejected(
                "course snapshot changed", CoreDataWriteStatus.STALE_COURSES
            )
        }
        val after = current.filterNot { it.id in request.requestedIds }
        val captured = try {
            CourseRecoveryJournal.captureDeletion(current, after, request, groups)
        } catch (error: Exception) {
            return@withCourseWriteLock CourseDeletionOutcome.Rejected(
                "course recovery capture failed: ${error.javaClass.simpleName}"
            )
        }
        val group = when (captured) {
            is CourseRecoveryCapture.Rejected -> return@withCourseWriteLock CourseDeletionOutcome.Rejected(
                captured.reason, CoreDataWriteStatus.INVALID_INPUT
            )
            is CourseRecoveryCapture.Captured -> captured.group
        }
        val updatedGroups = groups + group
        val committed = try {
            storage.commitCoursesAndRecoveryGroups(after, updatedGroups, current, groups)
        } catch (error: Exception) {
            false
        }
        if (committed) return@withCourseWriteLock CourseDeletionOutcome.Applied(group)
        confirmDeletionWrite(current, after, groups, updatedGroups) {
            CourseDeletionOutcome.Applied(group)
        }
    }

    override fun restoreCourseRecoveryGroup(
        groupId: String,
        expectedGroups: List<CourseRecoveryGroup>,
        now: Long
    ): CourseRestoreOutcome = storage.withCourseWriteLock {
        if (storage.isStorageReadOnly()) {
            return@withCourseWriteLock CourseRestoreOutcome.Rejected(
                "storage is read-only", CoreDataWriteStatus.NOT_READY
            )
        }
        val groups = when (val read = storage.loadCourseRecoveryGroups()) {
            is CourseRecoveryGroupsRead.Ready -> read.groups
            is CourseRecoveryGroupsRead.Invalid -> return@withCourseWriteLock CourseRestoreOutcome.Rejected(
                "course recovery payload is unreadable: ${read.reason}"
            )
            is CourseRecoveryGroupsRead.NotReady -> return@withCourseWriteLock CourseRestoreOutcome.NotReady(read.reason)
        }
        if (groups != expectedGroups) {
            return@withCourseWriteLock CourseRestoreOutcome.Rejected(
                "recovery group snapshot changed", CoreDataWriteStatus.INVALID_STATE
            )
        }
        val group = groups.firstOrNull { it.groupId == groupId }
            ?: return@withCourseWriteLock CourseRestoreOutcome.Rejected(
                "unknown recovery group", CoreDataWriteStatus.INVALID_INPUT
            )
        val current = try {
            storage.readCourses()
        } catch (error: Exception) {
            return@withCourseWriteLock CourseRestoreOutcome.Rejected(
                "course snapshot is unreadable: ${error.javaClass.simpleName}"
            )
        }
        // The pending journal state is probed here, inside the lock; a caller flag is never trusted.
        val pendingJournal = storage.hasPendingCourseEditJournal()
        return@withCourseWriteLock when (
            val decision = CourseRecoveryJournal.restoreDecision(group, current, pendingJournal, now)
        ) {
            is CourseRestoreDecision.Eligible -> {
                // Keep the live list exactly as it is and append the restored meetings in source order.
                val updatedCourses = current + decision.coursesToAdd
                val restoring = CourseRecoveryJournal.transition(group, CourseRecoveryState.RESTORING)
                    ?: return@withCourseWriteLock CourseRestoreOutcome.Rejected(
                        "recovery group cannot enter RESTORING"
                    )
                val updatedGroups = groups.map { if (it.groupId == groupId) restoring else it }
                val committed = try {
                    storage.commitCoursesAndRecoveryGroups(updatedCourses, updatedGroups, current, groups)
                } catch (error: Exception) {
                    false
                }
                if (committed) {
                    CourseRestoreOutcome.CoreCommitted(restoring)
                } else {
                    confirmRestoreWrite(current, updatedCourses, groups, updatedGroups) {
                        CourseRestoreOutcome.CoreCommitted(restoring)
                    }
                }
            }
            CourseRestoreDecision.AlreadyRestored -> CourseRestoreOutcome.AlreadyRestored(group)
            CourseRestoreDecision.ResumeRestoration -> CourseRestoreOutcome.NeedsFollowUp(group)
            CourseRestoreDecision.ResumePurge -> CourseRestoreOutcome.NeedsPurgeFollowUp(group)
            CourseRestoreDecision.Expired -> CourseRestoreOutcome.Rejected(
                "recovery group expired", CoreDataWriteStatus.CONDITION_NOT_MET
            )
            CourseRestoreDecision.PendingCourseEditJournal -> CourseRestoreOutcome.Rejected(
                "unfinished course merge/split journal", CoreDataWriteStatus.CONDITION_NOT_MET
            )
            is CourseRestoreDecision.Occupied -> CourseRestoreOutcome.Rejected(
                "meeting ${decision.meetingId} is already present", CoreDataWriteStatus.CONDITION_NOT_MET
            )
            is CourseRestoreDecision.ParentConflict -> CourseRestoreOutcome.Rejected(
                "parent ${decision.parentId} ${decision.field.wire} conflict: ${decision.actual}",
                CoreDataWriteStatus.CONDITION_NOT_MET
            )
            is CourseRestoreDecision.ParentOwnershipConflict -> CourseRestoreOutcome.Rejected(
                "parent id ownership conflict: parent ${decision.parentId}, meeting ${decision.conflictingMeetingId}",
                CoreDataWriteStatus.CONDITION_NOT_MET
            )
            is CourseRestoreDecision.Invalid -> CourseRestoreOutcome.Rejected(
                decision.reason, CoreDataWriteStatus.INVALID_STATE
            )
        }
    }

    override fun purgeCourseRecoveryGroups(
        expectedGroups: List<CourseRecoveryGroup>,
        now: Long
    ): CoursePurgeOutcome = storage.withCourseWriteLock {
        if (storage.isStorageReadOnly()) {
            return@withCourseWriteLock CoursePurgeOutcome.Rejected(
                "storage is read-only", CoreDataWriteStatus.NOT_READY
            )
        }
        val groups = when (val read = storage.loadCourseRecoveryGroups()) {
            is CourseRecoveryGroupsRead.Ready -> read.groups
            is CourseRecoveryGroupsRead.Invalid -> return@withCourseWriteLock CoursePurgeOutcome.Rejected(
                "course recovery payload is unreadable: ${read.reason}"
            )
            is CourseRecoveryGroupsRead.NotReady -> return@withCourseWriteLock CoursePurgeOutcome.NotReady(read.reason)
        }
        if (groups != expectedGroups) {
            return@withCourseWriteLock CoursePurgeOutcome.Rejected(
                "recovery group snapshot changed", CoreDataWriteStatus.INVALID_STATE
            )
        }
        val plan = CourseRecoveryJournal.purgePlan(groups, now)
        if (plan.groupIds.isEmpty()) return@withCourseWriteLock CoursePurgeOutcome.NothingToDo
        val remaining = groups.filterNot { it.groupId in plan.groupIds }
        val committed = try {
            storage.commitCourseRecoveryGroups(remaining, groups)
        } catch (error: Exception) {
            false
        }
        if (committed) return@withCourseWriteLock CoursePurgeOutcome.Applied(plan.groupIds)
        val stored = storage.loadCourseRecoveryGroups()
        if (stored !is CourseRecoveryGroupsRead.Ready) {
            return@withCourseWriteLock CoursePurgeOutcome.WriteUncertain(
                "commit returned false and the recovery payload could not be read back"
            )
        }
        when (stored.groups) {
            remaining -> CoursePurgeOutcome.Applied(plan.groupIds)
            groups -> CoursePurgeOutcome.WriteFailed("commit returned false; the recovery payload is unchanged")
            else -> CoursePurgeOutcome.WriteUncertain("commit returned false and the stored payload has a third state")
        }
    }

    /**
     * A failed commit is classified by re-reading: either the write landed anyway, or the stored
     * state still equals the pre-write snapshot (confirmed rejection), or it matches neither
     * (uncertain). SharedPreferences `commit() == false` alone proves none of these.
     */
    private fun confirmDeletionWrite(
        expectedCoursesBefore: List<Course>,
        expectedCoursesAfter: List<Course>,
        expectedGroupsBefore: List<CourseRecoveryGroup>,
        expectedGroupsAfter: List<CourseRecoveryGroup>,
        onLanded: () -> CourseDeletionOutcome
    ): CourseDeletionOutcome {
        val courses = try {
            storage.readCourses()
        } catch (error: Exception) {
            return CourseDeletionOutcome.WriteUncertain(
                "commit returned false and the course snapshot could not be read back"
            )
        }
        val stored = storage.loadCourseRecoveryGroups()
        if (stored !is CourseRecoveryGroupsRead.Ready) {
            return CourseDeletionOutcome.WriteUncertain(
                "commit returned false and the recovery payload could not be read back"
            )
        }
        return when {
            courses == expectedCoursesAfter && stored.groups == expectedGroupsAfter -> onLanded()
            courses == expectedCoursesBefore && stored.groups == expectedGroupsBefore ->
                CourseDeletionOutcome.WriteFailed("commit returned false; the stored snapshot is unchanged")
            else -> CourseDeletionOutcome.WriteUncertain(
                "commit returned false and the stored snapshot has a third state"
            )
        }
    }

    private fun confirmRestoreWrite(
        expectedCoursesBefore: List<Course>,
        expectedCoursesAfter: List<Course>,
        expectedGroupsBefore: List<CourseRecoveryGroup>,
        expectedGroupsAfter: List<CourseRecoveryGroup>,
        onLanded: () -> CourseRestoreOutcome
    ): CourseRestoreOutcome {
        val courses = try {
            storage.readCourses()
        } catch (error: Exception) {
            return CourseRestoreOutcome.WriteUncertain(
                "commit returned false and the course snapshot could not be read back"
            )
        }
        val stored = storage.loadCourseRecoveryGroups()
        if (stored !is CourseRecoveryGroupsRead.Ready) {
            return CourseRestoreOutcome.WriteUncertain(
                "commit returned false and the recovery payload could not be read back"
            )
        }
        return when {
            courses == expectedCoursesAfter && stored.groups == expectedGroupsAfter -> onLanded()
            courses == expectedCoursesBefore && stored.groups == expectedGroupsBefore ->
                CourseRestoreOutcome.WriteFailed("commit returned false; the stored snapshot is unchanged")
            else -> CourseRestoreOutcome.WriteUncertain(
                "commit returned false and the stored snapshot has a third state"
            )
        }
    }
}

/** The selected runtime's recovery store; Room reports "not ready" until its own batch. */
internal val CoreDataRepository.courseRecoveryStore: CourseRecoveryStore
    get() = when (this) {
        is LegacyCoreDataRepository -> recoveryStore
        else -> UnsupportedCourseRecoveryStore
    }

/**
 * Callable business-layer entry points. They read the reminder/location preferences through the
 * existing boundaries (read-only) and hand the captured request to the store. Nothing here writes a
 * preference, touches the UI, reschedules reminders or marks a group RESTORED.
 */
internal object CourseRecoveryOperations {
    fun readGroups(repository: CoreDataRepository): CourseRecoveryGroupsRead =
        repository.courseRecoveryStore.read()

    fun deleteCourses(
        context: Context,
        repository: CoreDataRepository,
        scope: CourseRecoveryScope,
        requestedIds: Set<Long>,
        operationId: String,
        now: Long = System.currentTimeMillis()
    ): CourseDeletionOutcome {
        val snapshot = (repository.read() as? CoreDataReadResult.Ready)?.snapshot
            ?: return CourseDeletionOutcome.Rejected("core data is not readable", CoreDataWriteStatus.NOT_READY)
        val request = CourseRecoveryRequest(
            requestedIds = requestedIds,
            scope = scope,
            operationId = operationId,
            now = now,
            reminderOverrides = reminderOverrides(context, requestedIds),
            deliveredWatermarks = deliveredWatermarks(context, requestedIds),
            temporaryLocations = temporaryLocations(context, requestedIds)
        )
        return repository.courseRecoveryStore.deleteCoursesWithRecoveryGroup(snapshot.courses, request)
    }

    fun restoreGroup(
        repository: CoreDataRepository,
        groupId: String,
        now: Long = System.currentTimeMillis()
    ): CourseRestoreOutcome {
        val groups = when (val read = repository.courseRecoveryStore.read()) {
            is CourseRecoveryGroupsRead.Ready -> read.groups
            is CourseRecoveryGroupsRead.Invalid -> return CourseRestoreOutcome.Rejected(
                "course recovery payload is unreadable: ${read.reason}"
            )
            is CourseRecoveryGroupsRead.NotReady -> return CourseRestoreOutcome.NotReady(read.reason)
        }
        return repository.courseRecoveryStore.restoreCourseRecoveryGroup(groupId, groups, now)
    }

    fun purgeExpired(
        repository: CoreDataRepository,
        now: Long = System.currentTimeMillis()
    ): CoursePurgeOutcome {
        val groups = when (val read = repository.courseRecoveryStore.read()) {
            is CourseRecoveryGroupsRead.Ready -> read.groups
            is CourseRecoveryGroupsRead.Invalid -> return CoursePurgeOutcome.Rejected(
                "course recovery payload is unreadable: ${read.reason}"
            )
            is CourseRecoveryGroupsRead.NotReady -> return CoursePurgeOutcome.NotReady(read.reason)
        }
        return repository.courseRecoveryStore.purgeCourseRecoveryGroups(groups, now)
    }

    /** Explicit three-state overrides for the requested meetings only; absent keys stay absent. */
    private fun reminderOverrides(context: Context, ids: Set<Long>): Map<Long, Boolean> {
        val overrides = CourseReminders.load(context).overrides
        return ids.mapNotNull { id -> overrides[id]?.let { id to it } }.toMap()
    }

    /**
     * The delivery watermark has no read accessor on the reminder boundary yet, so the documented
     * key format (`course_reminder_settings` / `delivered_<id>`, see the 7.5 proposal §2.4) is read
     * directly. A regression test writes through [CourseReminders.markNotified] to prove the format
     * still matches; a future batch may expose a read-only accessor instead.
     */
    private fun deliveredWatermarks(context: Context, ids: Set<Long>): Map<Long, Long> {
        val preferences = context.getSharedPreferences(COURSE_REMINDER_SETTINGS_FILE, Context.MODE_PRIVATE)
        return ids.mapNotNull { id ->
            (preferences.all["$DELIVERED_PREFIX$id"] as? Long)?.let { id to it }
        }.toMap()
    }

    private fun temporaryLocations(context: Context, ids: Set<Long>): Map<Long, Map<Long, String>> =
        ids.mapNotNull { id ->
            CourseLocationOverrides.snapshot(context, id).takeIf { it.isNotEmpty() }?.let { id to it }
        }.toMap()

    // Mirrors `CourseReminders.FILE` / `DELIVERED_PREFIX`; kept in sync by the regression test.
    private const val COURSE_REMINDER_SETTINGS_FILE = "course_reminder_settings"
    private const val DELIVERED_PREFIX = "delivered_"
}
