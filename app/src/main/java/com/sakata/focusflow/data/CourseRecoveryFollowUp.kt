package com.sakata.focusflow.data

import android.content.Context
import com.sakata.focusflow.CourseLocationOverrides
import com.sakata.focusflow.StorageProtection
import com.sakata.focusflow.CourseReminders
import com.sakata.focusflow.PrototypeStore

/**
 * Stage 7.5 follow-up for a restored recovery group.
 *
 * The core commit (courses + RESTORING) happens first; this follow-up then puts the captured
 * reminder/location preferences back and coordinates the alarms before the group may reach its
 * terminal RESTORED state. The three preference files cannot share one commit, so every step is
 * idempotent and replayed from the start on resume: an unconfirmed step stops the follow-up and the
 * group stays RESTORING, which is exactly the state a later call resumes from.
 */
internal sealed interface CourseRestoreCompletionOutcome {
    /** Courses, preferences, reminder coordination and the RESTORED state are all confirmed. */
    data class Completed(val group: CourseRecoveryGroup) : CourseRestoreCompletionOutcome

    /** The core courses are committed but a follow-up step is unconfirmed; the group stays RESTORING. */
    data class CoreCommittedFollowUpPending(
        val group: CourseRecoveryGroup,
        val reason: String
    ) : CourseRestoreCompletionOutcome

    data class Rejected(
        val reason: String,
        val status: CoreDataWriteStatus = CoreDataWriteStatus.INVALID_STATE
    ) : CourseRestoreCompletionOutcome

    data class WriteUncertain(val reason: String) : CourseRestoreCompletionOutcome

    data class NotReady(val reason: String) : CourseRestoreCompletionOutcome
}

/**
 * Writes the captured preference values through the existing boundaries. It is an interface so the
 * "unconfirmed step keeps the group RESTORING" path can be exercised with a failing writer;
 * production always uses [BoundaryCourseRecoveryPreferenceWriter].
 */
internal interface CourseRecoveryPreferenceWriter {
    fun writeOverride(context: Context, meetingId: Long, enabled: Boolean?): Boolean

    fun writeWatermark(context: Context, meetingId: Long, at: Long): Boolean

    fun writeLocation(context: Context, meetingId: Long, epochDay: Long, place: String): Boolean
}

internal object BoundaryCourseRecoveryPreferenceWriter : CourseRecoveryPreferenceWriter {
    override fun writeOverride(context: Context, meetingId: Long, enabled: Boolean?): Boolean =
        CourseReminders.setOverride(context, meetingId, enabled)

    /**
     * Confirm every write explicitly, including equal/newer values, under the delivery monitor.
     * Reading SharedPreferences memory after a failed commit cannot prove disk durability.
     */
    override fun writeWatermark(context: Context, meetingId: Long, at: Long): Boolean {
        return CourseReminders.confirmWatermark(context,meetingId,at)
    }

    override fun writeLocation(context: Context, meetingId: Long, epochDay: Long, place: String): Boolean =
        CourseLocationOverrides.set(context, meetingId, epochDay, place)
}

internal object CourseRecoveryFollowUp {
    /**
     * Runs (or resumes) the follow-up for a RESTORING group under the shared course lock. Never
     * writes RESTORED unless every earlier step was confirmed.
     */
    fun run(
        context: Context,
        repository: CoreDataRepository,
        groupId: String,
        now: Long = System.currentTimeMillis(),
        writer: CourseRecoveryPreferenceWriter = BoundaryCourseRecoveryPreferenceWriter
    ): CourseRestoreCompletionOutcome = repository.withCourseWriteLock {
        // A failed commit may have changed only SharedPreferences' in-memory map. Replaying side
        // effects from that map could attach saved preferences to courses that never reached disk.
        if(StorageProtection.readOnly || Stage7CommitGuard.uncertain) return@withCourseWriteLock CourseRestoreCompletionOutcome.WriteUncertain("storage is protected or a disk write is unconfirmed")
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
            CourseRecoveryState.RESTORED -> return@withCourseWriteLock CourseRestoreCompletionOutcome.Completed(group)
            CourseRecoveryState.ACTIVE -> return@withCourseWriteLock CourseRestoreCompletionOutcome.Rejected(
                "the core restore has not been committed yet", CoreDataWriteStatus.CONDITION_NOT_MET
            )
            CourseRecoveryState.PURGING -> return@withCourseWriteLock CourseRestoreCompletionOutcome.Rejected(
                "a purge for this group is already in progress", CoreDataWriteStatus.CONDITION_NOT_MET
            )
            CourseRecoveryState.RESTORING -> Unit
        }

        val ids = group.members.mapTo(mutableSetOf()) { it.id }
        val courses = (repository.read() as? CoreDataReadResult.Ready)?.snapshot?.courses
            ?: return@withCourseWriteLock CourseRestoreCompletionOutcome.CoreCommittedFollowUpPending(
                group, "restored courses cannot be read"
            )
        if (courses.count { it.id in ids } != ids.size || group.members.any { member ->
                val original = CourseSnapshotCodec.decode(member.courseJson)
                original == null || courses.none { it.id == member.id && it.courseId == original.courseId }
            }) {
            return@withCourseWriteLock CourseRestoreCompletionOutcome.CoreCommittedFollowUpPending(
                group, "a restored meeting is missing or its course identity changed"
            )
        }

        val unconfirmed = applyCapturedPreferences(context, group, writer)
        if (unconfirmed != null) {
            return@withCourseWriteLock CourseRestoreCompletionOutcome.CoreCommittedFollowUpPending(
                group, unconfirmed
            )
        }
        val reminderError = runCatching {
            syncRestoredReminders(context, courses.filter { it.id in ids })
        }.exceptionOrNull()
        if (reminderError != null) {
            return@withCourseWriteLock CourseRestoreCompletionOutcome.CoreCommittedFollowUpPending(
                group, "restored reminders could not be coordinated: ${reminderError.javaClass.simpleName}"
            )
        }

        when (
            val completion = repository.courseRecoveryStore
                .completeRestoreCourseRecoveryGroup(groupId, groups, now)
        ) {
            is CourseRecoveryCompletion.Completed -> CourseRestoreCompletionOutcome.Completed(completion.group)
            is CourseRecoveryCompletion.AlreadyCompleted -> CourseRestoreCompletionOutcome.Completed(completion.group)
            is CourseRecoveryCompletion.Rejected -> CourseRestoreCompletionOutcome.Rejected(
                completion.reason, completion.status
            )
            is CourseRecoveryCompletion.WriteUncertain -> CourseRestoreCompletionOutcome.WriteUncertain(
                completion.reason
            )
            is CourseRecoveryCompletion.NotReady -> CourseRestoreCompletionOutcome.NotReady(completion.reason)
        }
    }

    /** Returns the first unconfirmed step, or null when every captured value is in place. */
    private fun applyCapturedPreferences(
        context: Context,
        group: CourseRecoveryGroup,
        writer: CourseRecoveryPreferenceWriter
    ): String? {
        group.members.sortedBy { it.sourceOrder }.forEach { member ->
            if (member.id <= 0L) return "meeting ${member.id} is not a valid meeting id"
            if (!writer.writeOverride(context, member.id, member.reminderOverride)) {
                return "the reminder override of meeting ${member.id} is not confirmed"
            }
            member.deliveredAt?.let { at ->
                if (!writer.writeWatermark(context, member.id, at)) {
                    return "the delivery watermark of meeting ${member.id} is not confirmed"
                }
            }
            member.temporaryLocations.toSortedMap().forEach { (day, place) ->
                if (!writer.writeLocation(context, member.id, day, place)) {
                    return "the temporary location of meeting ${member.id} on day $day is not confirmed"
                }
            }
        }
        return null
    }

    /**
     * Alarm coordination through the existing reminder boundary. It cancels the restored ids before
     * re-scheduling them, so an interrupted follow-up cannot leave a stale alarm behind. The
     * platform scheduling call has no result code, so this step cannot be "unconfirmed" - it only
     * runs after every preference step was confirmed.
     */
    private fun syncRestoredReminders(
        context: Context,
        restored: List<com.sakata.focusflow.Course>
    ) {
        val table = PrototypeStore(context).loadCoursePeriodTable()
        CourseReminders.sync(context, emptyList(), restored, table, CourseReminders.load(context))
    }
}
