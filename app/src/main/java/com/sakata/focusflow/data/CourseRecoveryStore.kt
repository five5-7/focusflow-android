package com.sakata.focusflow.data

import android.content.Context
import com.sakata.focusflow.Course

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

    /**
     * Unreadable payload. [raw] preserves a textual payload for diagnosis (null when the stored value
     * was not a string at all, in which case [reason] names the actual type). Nothing may overwrite it.
     */
    data class Invalid(val reason: String, val raw: String?) : CourseRecoveryGroupsRead

    /** The selected runtime cannot store course recovery groups yet. */
    data class NotReady(val reason: String) : CourseRecoveryGroupsRead
}

/**
 * Result of a guarded commit. A plain Boolean cannot distinguish "a guard refused before writing"
 * from "the platform reported a failed write", and that difference decides whether the caller may
 * report success at all.
 */
internal sealed interface CourseRecoveryCommit {
    /** The platform confirmed the write (`commit()` returned true). */
    data object Committed : CourseRecoveryCommit

    /** A pre-commit guard refused (read-only, snapshot mismatch, unreadable payload, encode failure). */
    data class Rejected(val reason: String) : CourseRecoveryCommit

    /**
     * The write did not return success (false or an exception). SharedPreferences updates its
     * in-memory map before it touches disk, so a later read proves nothing about durability; this
     * outcome stays unproven until an independent confirmed commit happens.
     */
    data class Failed(val reason: String) : CourseRecoveryCommit
}

/**
 * Process-wide uncertainty guard for course recovery writes. `SharedPreferences.commit() == false`
 * may already have updated the in-memory map, so within the same process a later call must not read
 * those unverified values and report success or resume side effects. The guard is only cleared by a
 * later commit that the platform confirmed; a plain read, a rebuilt store object or a new
 * SharedPreferences handle does not clear it (the framework hands out the same instance per process).
 */
internal object CourseRecoveryWriteGuard {
    @Volatile
    private var uncertainReason: String? = null

    fun uncertainReason(): String? = uncertainReason

    fun markUncertain(reason: String) {
        uncertainReason = reason
    }

    /** Called only when a commit was confirmed by the platform. */
    fun clearAfterConfirmedCommit() {
        uncertainReason = null
    }

    /** Tests only: the guard is process-wide state. */
    fun reset() {
        uncertainReason = null
    }
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
    ): CourseRecoveryCommit

    /** Commits only the group payload (state transitions and purge). */
    fun commitCourseRecoveryGroups(
        groups: List<CourseRecoveryGroup>,
        expectedGroups: List<CourseRecoveryGroup>
    ): CourseRecoveryCommit
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

    /**
     * The commit did not report success and there is no independent persistence evidence: the
     * in-memory map may already hold the new value while the disk does not. Never reported as
     * success, and the uncertainty guard blocks later state-based short circuits.
     */
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

    data class WriteUncertain(val reason: String) : CoursePurgeOutcome

    data class NotReady(val reason: String) : CoursePurgeOutcome
}

internal sealed interface CourseRecoveryCompletion {
    /** The terminal RESTORED state was committed. */
    data class Completed(val group: CourseRecoveryGroup) : CourseRecoveryCompletion

    /** The group already reached RESTORED; nothing was written. */
    data class AlreadyCompleted(val group: CourseRecoveryGroup) : CourseRecoveryCompletion

    data class Rejected(val reason: String, val status: CoreDataWriteStatus = CoreDataWriteStatus.INVALID_STATE) :
        CourseRecoveryCompletion

    data class WriteUncertain(val reason: String) : CourseRecoveryCompletion

    data class NotReady(val reason: String) : CourseRecoveryCompletion
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

    /**
     * Marks a RESTORING group as RESTORED. Only a caller that already confirmed every follow-up
     * step (preferences and reminder coordination) may reach this; the store itself never treats a
     * partial follow-up as complete.
     */
    fun completeRestoreCourseRecoveryGroup(
        groupId: String,
        expectedGroups: List<CourseRecoveryGroup>,
        now: Long
    ): CourseRecoveryCompletion

    fun purgeSelectedGroups(ids: Set<String>): CoursePurgeOutcome = CoursePurgeOutcome.NotReady("manual purge unavailable")

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

    override fun completeRestoreCourseRecoveryGroup(
        groupId: String,
        expectedGroups: List<CourseRecoveryGroup>,
        now: Long
    ): CourseRecoveryCompletion = CourseRecoveryCompletion.NotReady(REASON)

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
        // Identity guard. When a previous commit outcome is still unverified this short circuit must
        // not be taken: the in-memory group is exactly what the failed commit may have written.
        groups.firstOrNull { it.operationId == request.operationId }?.let {
            uncertaintyOrNull()?.let { reason ->
                return@withCourseWriteLock CourseDeletionOutcome.WriteUncertain(reason)
            }
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
        when (val commit = commitCourses(after, groups + group, current, groups)) {
            is CourseRecoveryCommit.Committed -> CourseDeletionOutcome.Applied(group)
            is CourseRecoveryCommit.Rejected -> CourseDeletionOutcome.Rejected(
                commit.reason, CoreDataWriteStatus.INVALID_STATE
            )
            is CourseRecoveryCommit.Failed -> CourseDeletionOutcome.WriteUncertain(
                markUncertain(commit.reason)
            )
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
        when (val decision = CourseRecoveryJournal.restoreDecision(group, current, pendingJournal, now)) {
            is CourseRestoreDecision.Eligible -> {
                // Keep the live list exactly as it is and append the restored meetings in source order.
                val updatedCourses = current + decision.coursesToAdd
                val restoring = CourseRecoveryJournal.transition(group, CourseRecoveryState.RESTORING)
                    ?: return@withCourseWriteLock CourseRestoreOutcome.Rejected(
                        "recovery group cannot enter RESTORING"
                    )
                val updatedGroups = groups.map { if (it.groupId == groupId) restoring else it }
                when (val commit = commitCourses(updatedCourses, updatedGroups, current, groups)) {
                    is CourseRecoveryCommit.Committed -> CourseRestoreOutcome.CoreCommitted(restoring)
                    is CourseRecoveryCommit.Rejected -> CourseRestoreOutcome.Rejected(
                        commit.reason, CoreDataWriteStatus.INVALID_STATE
                    )
                    is CourseRecoveryCommit.Failed -> CourseRestoreOutcome.WriteUncertain(
                        markUncertain(commit.reason)
                    )
                }
            }
            // Every no-write answer would let the caller resume side effects from in-memory state, so
            // an unverified commit outcome takes precedence over all of them.
            CourseRestoreDecision.AlreadyRestored -> uncertaintyOrNull()?.let { CourseRestoreOutcome.WriteUncertain(it) }
                ?: CourseRestoreOutcome.AlreadyRestored(group)
            CourseRestoreDecision.ResumeRestoration -> uncertaintyOrNull()?.let { CourseRestoreOutcome.WriteUncertain(it) }
                ?: CourseRestoreOutcome.NeedsFollowUp(group)
            CourseRestoreDecision.ResumePurge -> uncertaintyOrNull()?.let { CourseRestoreOutcome.WriteUncertain(it) }
                ?: CourseRestoreOutcome.NeedsPurgeFollowUp(group)
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

    override fun completeRestoreCourseRecoveryGroup(
        groupId: String,
        expectedGroups: List<CourseRecoveryGroup>,
        now: Long
    ): CourseRecoveryCompletion = storage.withCourseWriteLock {
        if (storage.isStorageReadOnly()) {
            return@withCourseWriteLock CourseRecoveryCompletion.Rejected(
                "storage is read-only", CoreDataWriteStatus.NOT_READY
            )
        }
        val groups = when (val read = storage.loadCourseRecoveryGroups()) {
            is CourseRecoveryGroupsRead.Ready -> read.groups
            is CourseRecoveryGroupsRead.Invalid -> return@withCourseWriteLock CourseRecoveryCompletion.Rejected(
                "course recovery payload is unreadable: ${read.reason}"
            )
            is CourseRecoveryGroupsRead.NotReady -> return@withCourseWriteLock CourseRecoveryCompletion.NotReady(
                read.reason
            )
        }
        if (groups != expectedGroups) {
            return@withCourseWriteLock CourseRecoveryCompletion.Rejected(
                "recovery group snapshot changed", CoreDataWriteStatus.INVALID_STATE
            )
        }
        val group = groups.firstOrNull { it.groupId == groupId }
            ?: return@withCourseWriteLock CourseRecoveryCompletion.Rejected(
                "unknown recovery group", CoreDataWriteStatus.INVALID_INPUT
            )
        when (group.state) {
            CourseRecoveryState.RESTORED -> return@withCourseWriteLock CourseRecoveryCompletion.AlreadyCompleted(
                group
            )
            CourseRecoveryState.ACTIVE -> return@withCourseWriteLock CourseRecoveryCompletion.Rejected(
                "the core restore has not been committed yet", CoreDataWriteStatus.CONDITION_NOT_MET
            )
            CourseRecoveryState.PURGING -> return@withCourseWriteLock CourseRecoveryCompletion.Rejected(
                "a purge for this group is already in progress", CoreDataWriteStatus.CONDITION_NOT_MET
            )
            CourseRecoveryState.RESTORING -> Unit
        }
        // A no-write answer must not be taken while a previous commit outcome is still unverified.
        uncertaintyOrNull()?.let { reason ->
            return@withCourseWriteLock CourseRecoveryCompletion.WriteUncertain(reason)
        }
        val restored = CourseRecoveryJournal.transition(group, CourseRecoveryState.RESTORED, now)
            ?: return@withCourseWriteLock CourseRecoveryCompletion.Rejected(
                "recovery group cannot enter RESTORED"
            )
        val updatedGroups = groups.map { if (it.groupId == groupId) restored else it }
        when (val commit = commitGroups(updatedGroups, groups)) {
            is CourseRecoveryCommit.Committed -> CourseRecoveryCompletion.Completed(restored)
            is CourseRecoveryCommit.Rejected -> CourseRecoveryCompletion.Rejected(
                commit.reason, CoreDataWriteStatus.INVALID_STATE
            )
            is CourseRecoveryCommit.Failed -> CourseRecoveryCompletion.WriteUncertain(
                markUncertain(commit.reason)
            )
        }
    }

    override fun purgeSelectedGroups(ids: Set<String>): CoursePurgeOutcome = storage.withCourseWriteLock {
        if(storage.isStorageReadOnly()) return@withCourseWriteLock CoursePurgeOutcome.Rejected("storage is read-only",CoreDataWriteStatus.NOT_READY)
        uncertaintyOrNull()?.let { return@withCourseWriteLock CoursePurgeOutcome.WriteUncertain(it) }
        val groups=(storage.loadCourseRecoveryGroups() as? CourseRecoveryGroupsRead.Ready)?.groups
            ?: return@withCourseWriteLock CoursePurgeOutcome.Rejected("recovery data unavailable")
        val selected=groups.filter { it.groupId in ids }
        if(selected.size!=ids.size || selected.any { it.state==CourseRecoveryState.RESTORING }) return@withCourseWriteLock CoursePurgeOutcome.Rejected("group changed or restoration pending")
        when(val c=commitGroups(groups.filterNot { it.groupId in ids },groups)) {
            CourseRecoveryCommit.Committed -> CoursePurgeOutcome.Applied(ids)
            is CourseRecoveryCommit.Rejected -> CoursePurgeOutcome.Rejected(c.reason)
            is CourseRecoveryCommit.Failed -> CoursePurgeOutcome.WriteUncertain(markUncertain(c.reason))
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
        if (plan.groupIds.isEmpty()) {
            uncertaintyOrNull()?.let { reason ->
                return@withCourseWriteLock CoursePurgeOutcome.WriteUncertain(reason)
            }
            return@withCourseWriteLock CoursePurgeOutcome.NothingToDo
        }
        val remaining = groups.filterNot { it.groupId in plan.groupIds }
        when (val commit = commitGroups(remaining, groups)) {
            is CourseRecoveryCommit.Committed -> CoursePurgeOutcome.Applied(plan.groupIds)
            is CourseRecoveryCommit.Rejected -> CoursePurgeOutcome.Rejected(
                commit.reason, CoreDataWriteStatus.INVALID_STATE
            )
            is CourseRecoveryCommit.Failed -> CoursePurgeOutcome.WriteUncertain(markUncertain(commit.reason))
        }
    }

    private fun commitCourses(
        courses: List<Course>,
        groups: List<CourseRecoveryGroup>,
        expectedCourses: List<Course>,
        expectedGroups: List<CourseRecoveryGroup>
    ): CourseRecoveryCommit {
        val commit = try {
            storage.commitCoursesAndRecoveryGroups(courses, groups, expectedCourses, expectedGroups)
        } catch (error: Exception) {
            CourseRecoveryCommit.Failed("commit threw ${error.javaClass.simpleName}")
        }
        recordCommit(commit)
        return commit
    }

    private fun commitGroups(
        groups: List<CourseRecoveryGroup>,
        expectedGroups: List<CourseRecoveryGroup>
    ): CourseRecoveryCommit {
        val commit = try {
            storage.commitCourseRecoveryGroups(groups, expectedGroups)
        } catch (error: Exception) {
            CourseRecoveryCommit.Failed("commit threw ${error.javaClass.simpleName}")
        }
        recordCommit(commit)
        return commit
    }

    /**
     * Only a platform-confirmed commit clears the uncertainty; a plain read, a rebuilt store object
     * or a new SharedPreferences handle proves nothing about the disk.
     */
    private fun recordCommit(commit: CourseRecoveryCommit) {
        when (commit) {
            is CourseRecoveryCommit.Committed -> CourseRecoveryWriteGuard.clearAfterConfirmedCommit()
            is CourseRecoveryCommit.Failed -> CourseRecoveryWriteGuard.markUncertain(commit.reason)
            is CourseRecoveryCommit.Rejected -> Unit
        }
    }

    private fun markUncertain(reason: String): String {
        CourseRecoveryWriteGuard.markUncertain(reason)
        return "commit outcome is unverified: $reason"
    }

    private fun uncertaintyOrNull(): String? =
        CourseRecoveryWriteGuard.uncertainReason()?.let {
            "a previous commit outcome is still unverified ($it); refusing to continue from in-memory state"
        }

}

internal val CoreDataRepository.courseRecoveryStore: CourseRecoveryStore
    get() = when (this) {
        is LegacyCoreDataRepository -> recoveryStore
        is RoomCoreDataRepository -> recoveryStore
        else -> UnsupportedCourseRecoveryStore
    }

/**
 * Runs [block] under the same course write lock the recovery store uses. The Legacy runtime
 * serializes ordinary course saves, merge/split journal operations and recovery writes here; the
 * Room runtime has no such lock yet and runs the block unchanged rather than pretending otherwise.
 */
internal fun <T> CoreDataRepository.withCourseWriteLock(block: () -> T): T =
    when(this) {
        is LegacyCoreDataRepository -> withCourseWriteLock(block)
        is RoomCoreDataRepository -> withCourseWriteLock(block)
        else -> block()
    }

/** Strict read of one delivery watermark: absent and corrupt are reported separately. */
internal sealed interface CourseRecoveryWatermarkRead {
    data class Value(val at: Long) : CourseRecoveryWatermarkRead

    data object Absent : CourseRecoveryWatermarkRead

    data object Corrupt : CourseRecoveryWatermarkRead
}

/**
 * Strict, read-only preference capture for the meetings a recovery group is about to snapshot.
 *
 * The everyday reminder boundary stays lenient on purpose (a broken key must not break normal
 * reminders), but a recovery snapshot may not silently drop or merge a corrupted value: a missing
 * key is legal, an existing key of the wrong type or shape rejects the whole capture. Only the
 * requested meeting ids are inspected, so corruption elsewhere never blocks this operation.
 */
internal sealed interface CourseRecoveryPreferenceCapture {
    data class Ready(
        val overrides: Map<Long, Boolean>,
        val delivered: Map<Long, Long>,
        val locations: Map<Long, Map<Long, String>>
    ) : CourseRecoveryPreferenceCapture

    data class Invalid(val reason: String) : CourseRecoveryPreferenceCapture
}

internal object CourseRecoveryPreferences {
    private const val SETTINGS_FILE = "course_reminder_settings"
    private const val LOCATIONS_FILE = "course_location_overrides"
    private const val MEETING_PREFIX = "meeting_"
    private const val DELIVERED_PREFIX = "delivered_"
    private const val MAX_PLACE_LENGTH = 100

    /** Strict single-key read used by the follow-up to distinguish "already satisfied" from failure. */
    fun watermark(context: Context, meetingId: Long): CourseRecoveryWatermarkRead {
        val settings = try {
            context.getSharedPreferences(SETTINGS_FILE, Context.MODE_PRIVATE).all
        } catch (error: Exception) {
            return CourseRecoveryWatermarkRead.Corrupt
        }
        return when (val value = settings[DELIVERED_PREFIX + meetingId]) {
            null -> CourseRecoveryWatermarkRead.Absent
            is Long -> if (value > 0L) CourseRecoveryWatermarkRead.Value(value) else CourseRecoveryWatermarkRead.Corrupt
            else -> CourseRecoveryWatermarkRead.Corrupt
        }
    }

    fun capture(context: Context, meetingIds: Set<Long>): CourseRecoveryPreferenceCapture {
        if (meetingIds.isEmpty()) {
            return CourseRecoveryPreferenceCapture.Ready(emptyMap(), emptyMap(), emptyMap())
        }
        val settings = try {
            context.getSharedPreferences(SETTINGS_FILE, Context.MODE_PRIVATE).all
        } catch (error: Exception) {
            return CourseRecoveryPreferenceCapture.Invalid(
                "course reminder preferences are unreadable: ${error.javaClass.simpleName}"
            )
        }
        val locationEntries = try {
            context.getSharedPreferences(LOCATIONS_FILE, Context.MODE_PRIVATE).all
        } catch (error: Exception) {
            return CourseRecoveryPreferenceCapture.Invalid(
                "course location preferences are unreadable: ${error.javaClass.simpleName}"
            )
        }

        val overrides = LinkedHashMap<Long, Boolean>()
        val delivered = LinkedHashMap<Long, Long>()
        meetingIds.sorted().forEach meeting@{ id ->
            settings[MEETING_PREFIX + id]?.let { value ->
                if (value !is Boolean) {
                    return CourseRecoveryPreferenceCapture.Invalid(
                        "override ${MEETING_PREFIX}$id is ${value.javaClass.simpleName}, not a boolean"
                    )
                }
                overrides[id] = value
            }
            settings[DELIVERED_PREFIX + id]?.let { value ->
                if (value !is Long) {
                    return CourseRecoveryPreferenceCapture.Invalid(
                        "watermark ${DELIVERED_PREFIX}$id is ${value.javaClass.simpleName}, not a long"
                    )
                }
                if (value <= 0L) {
                    return CourseRecoveryPreferenceCapture.Invalid(
                        "watermark ${DELIVERED_PREFIX}$id is not positive"
                    )
                }
                delivered[id] = value
            }
        }

        val locations = LinkedHashMap<Long, Map<Long, String>>()
        meetingIds.sorted().forEach meeting@{ id ->
            val prefix = "${id}_"
            val places = LinkedHashMap<Long, String>()
            locationEntries.keys.sorted().forEach location@{ key ->
                if (!key.startsWith(prefix)) return@location
                val dayText = key.removePrefix(prefix)
                val day = dayText.toLongOrNull()
                    ?: return CourseRecoveryPreferenceCapture.Invalid("location key $key has no numeric day")
                if (day.toString() != dayText) {
                    return CourseRecoveryPreferenceCapture.Invalid("location key $key is not a canonical day")
                }
                if (day < 0L) {
                    return CourseRecoveryPreferenceCapture.Invalid("location key $key has a negative day")
                }
                val value = locationEntries.getValue(key)
                if (value !is String) {
                    return CourseRecoveryPreferenceCapture.Invalid(
                        "location $key is ${value?.javaClass?.simpleName ?: "null"}, not text"
                    )
                }
                val place = value.trim()
                if (place.isEmpty()) {
                    return CourseRecoveryPreferenceCapture.Invalid("location $key is blank")
                }
                if (place.length > MAX_PLACE_LENGTH) {
                    return CourseRecoveryPreferenceCapture.Invalid(
                        "location $key is longer than $MAX_PLACE_LENGTH characters"
                    )
                }
                if (places.put(day, place) != null) {
                    return CourseRecoveryPreferenceCapture.Invalid(
                        "location key $key duplicates another spelling of day $day"
                    )
                }
            }
            if (places.isNotEmpty()) locations[id] = places
        }

        return CourseRecoveryPreferenceCapture.Ready(overrides, delivered, locations)
    }
}

/** One course deletion or interrupted restore shown independently in the course page. */
internal data class CourseRecoveryRestoreCandidate(
    val groupId: String,
    val meetingCount: Int,
    val state: CourseRecoveryState,
    val deletedAt: Long,
    val expiresAt: Long,
    val title: String
)

/** Business entry points; the follow-up also replays preferences and coordinates reminders. */
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
    ): CourseDeletionOutcome = repository.withCourseWriteLock {
        // The whole sequence runs inside the shared course lock: course snapshot, strict capture of
        // the target preferences, request construction and the store commit. Re-reading the courses
        // alone would not be enough, because a merge/split recovery can migrate the preferences of a
        // surviving meeting without changing the course list; capturing them outside the lock could
        // store pre-migration overrides, watermarks or locations.
        val snapshot = (repository.read() as? CoreDataReadResult.Ready)?.snapshot
            ?: return@withCourseWriteLock CourseDeletionOutcome.Rejected(
                "core data is not readable", CoreDataWriteStatus.NOT_READY
            )
        // A corrupted target preference must reject the whole delete before any write, leaving
        // courses, groups and every preference value untouched.
        val captured = CourseRecoveryPreferences.capture(context, requestedIds)
        if (captured is CourseRecoveryPreferenceCapture.Invalid) {
            return@withCourseWriteLock CourseDeletionOutcome.Rejected(
                "course preferences are unreadable for the requested meetings: ${captured.reason}",
                CoreDataWriteStatus.INVALID_INPUT
            )
        }
        val ready = captured as CourseRecoveryPreferenceCapture.Ready
        val request = CourseRecoveryRequest(
            requestedIds = requestedIds,
            scope = scope,
            operationId = operationId,
            now = now,
            reminderOverrides = ready.overrides,
            deliveredWatermarks = ready.delivered,
            temporaryLocations = ready.locations
        )
        // The store re-enters its own (reentrant) lock and keeps its snapshot guard as the storage
        // boundary protection.
        repository.courseRecoveryStore.deleteCoursesWithRecoveryGroup(snapshot.courses, request)
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

    /**
     * Production entry for "undo the last course deletion": restores the core courses and then runs
     * the follow-up (captured preferences, reminder coordination, RESTORED) under the same lock. A
     * group that is already RESTORING resumes its follow-up instead of re-appending courses, and a
     * partial follow-up is reported as [CourseRestoreCompletionOutcome.CoreCommittedFollowUpPending]
     * - never as full success.
     */
    fun restoreAndComplete(
        context: Context,
        repository: CoreDataRepository,
        groupId: String,
        now: Long = System.currentTimeMillis()
    ): CourseRestoreCompletionOutcome = repository.withCourseWriteLock {
        CourseRecoveryWriteGuard.uncertainReason()?.let { reason ->
            return@withCourseWriteLock CourseRestoreCompletionOutcome.WriteUncertain(
                "a previous course recovery commit is unverified ($reason)"
            )
        }
        val groups = when (val read = repository.courseRecoveryStore.read()) {
            is CourseRecoveryGroupsRead.Ready -> read.groups
            is CourseRecoveryGroupsRead.Invalid -> return@withCourseWriteLock CourseRestoreCompletionOutcome.Rejected(
                "course recovery payload is unreadable: ${read.reason}"
            )
            is CourseRecoveryGroupsRead.NotReady -> return@withCourseWriteLock CourseRestoreCompletionOutcome.NotReady(
                read.reason
            )
        }
        val group = groups.firstOrNull { it.groupId == groupId }
            ?: return@withCourseWriteLock CourseRestoreCompletionOutcome.Rejected(
                "unknown recovery group", CoreDataWriteStatus.INVALID_INPUT
            )
        when (group.state) {
            CourseRecoveryState.RESTORED -> CourseRestoreCompletionOutcome.Completed(group)
            CourseRecoveryState.RESTORING -> CourseRecoveryFollowUp.run(context, repository, groupId, now)
            CourseRecoveryState.PURGING -> CourseRestoreCompletionOutcome.Rejected(
                "a purge for this group is already in progress", CoreDataWriteStatus.CONDITION_NOT_MET
            )
            CourseRecoveryState.ACTIVE -> when (
                val core = repository.courseRecoveryStore.restoreCourseRecoveryGroup(groupId, groups, now)
            ) {
                is CourseRestoreOutcome.CoreCommitted -> CourseRecoveryFollowUp.run(context, repository, groupId, now)
                is CourseRestoreOutcome.NeedsFollowUp -> CourseRecoveryFollowUp.run(context, repository, groupId, now)
                is CourseRestoreOutcome.AlreadyRestored -> CourseRestoreCompletionOutcome.Completed(group)
                is CourseRestoreOutcome.Rejected -> CourseRestoreCompletionOutcome.Rejected(
                    core.reason, core.status
                )
                is CourseRestoreOutcome.WriteUncertain -> CourseRestoreCompletionOutcome.WriteUncertain(core.reason)
                is CourseRestoreOutcome.NotReady -> CourseRestoreCompletionOutcome.NotReady(core.reason)
                is CourseRestoreOutcome.NeedsPurgeFollowUp -> CourseRestoreCompletionOutcome.Rejected(
                    "a purge for this group is already in progress", CoreDataWriteStatus.CONDITION_NOT_MET
                )
            }
        }
    }

    /** Each real group gets its own UI entry; an interrupted restore survives the undo deadline. */
    fun restorableGroups(
        repository: CoreDataRepository,
        now: Long = System.currentTimeMillis()
    ): List<CourseRecoveryRestoreCandidate> {
        val groups = when (val read = repository.courseRecoveryStore.read()) {
            is CourseRecoveryGroupsRead.Ready -> read.groups
            else -> return emptyList()
        }
        return groups
            .filter {
                // ACTIVE = deleted, waiting for the user; RESTORING = core committed, follow-up open.
                // RESTORED is terminal and PURGING is already being removed, so neither is offered.
                (it.state == CourseRecoveryState.RESTORING ||
                    (it.state == CourseRecoveryState.ACTIVE && it.expiresAt > now)) &&
                    it.members.isNotEmpty()
            }
            .sortedWith(compareByDescending<CourseRecoveryGroup> { it.deletedAt }.thenBy { it.groupId })
            .map { group ->
                val title = group.members.firstOrNull()?.let { CourseSnapshotCodec.decode(it.courseJson)?.title }
                    ?: "课程"
                CourseRecoveryRestoreCandidate(
                    group.groupId, group.members.size, group.state,
                    group.deletedAt, group.expiresAt, title
                )
            }
    }

    fun latestRestorableGroup(
        repository: CoreDataRepository,
        now: Long = System.currentTimeMillis()
    ): CourseRecoveryRestoreCandidate? = restorableGroups(repository, now).firstOrNull()

    /** Every interrupted core restore needs a replay; the most recent group alone is insufficient. */
    fun pendingRestoringGroups(repository: CoreDataRepository): List<String> =
        when (val read = repository.courseRecoveryStore.read()) {
            is CourseRecoveryGroupsRead.Ready -> read.groups
                .filter { it.state == CourseRecoveryState.RESTORING }
                .sortedBy { it.deletedAt }
                .map { it.groupId }
            else -> emptyList()
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
}
