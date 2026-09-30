package com.sakata.focusflow.data

import android.app.AlarmManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sakata.focusflow.CampusZone
import com.sakata.focusflow.Course
import com.sakata.focusflow.CourseLocationOverrides
import com.sakata.focusflow.CoursePeriodTable
import com.sakata.focusflow.CourseReminderSettings
import com.sakata.focusflow.CourseReminders
import com.sakata.focusflow.PrototypeStore
import com.sakata.focusflow.StorageProtection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import java.time.LocalDate

/**
 * Stage 7.5 follow-up: after the core restore commits the courses and the RESTORING state, the
 * captured preferences are written back, the alarms are coordinated and only then may the group
 * reach RESTORED. Every step is idempotent, so an interrupted follow-up resumes from RESTORING.
 */
@RunWith(RobolectricTestRunner::class)
class CourseRecoveryFollowUpTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val now = 10_000L

    private fun course(id: Long, courseId: Long = id, title: String = "课程$id") = Course(
        title = title, weekday = 1, startPeriod = 1, endPeriod = 2, building = "东一",
        zone = CampusZone.EAST_TEACHING, needsConfirmation = false, enabled = true,
        id = id, courseId = courseId
    )

    private fun freshStore() = PrototypeStore(context)
    private fun freshRepository() = LegacyCoreDataRepository(freshStore())

    private fun prefs(name: String) = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    private fun groups(repository: CoreDataRepository = freshRepository()): List<CourseRecoveryGroup> {
        val read = CourseRecoveryOperations.readGroups(repository)
        assertTrue("expected Ready but got $read", read is CourseRecoveryGroupsRead.Ready)
        return (read as CourseRecoveryGroupsRead.Ready).groups
    }

    /** A writer whose first step never confirms, to exercise the "stays RESTORING" path. */
    private val failingWriter = object : CourseRecoveryPreferenceWriter {
        override fun writeOverride(context: Context, meetingId: Long, enabled: Boolean?): Boolean = false

        override fun writeWatermark(context: Context, meetingId: Long, at: Long): Boolean = true

        override fun writeLocation(context: Context, meetingId: Long, epochDay: Long, place: String): Boolean = true
    }

    @Before
    fun clearState() {
        listOf(
            "focusflow", "course_reminder_settings", "course_location_overrides",
            "course_merge_journal", "course_split_journal"
        ).forEach { prefs(it).edit().clear().commit() }
        CourseRecoveryWriteGuard.reset()
        StorageProtection.retry()
        assertEquals(
            CoreDataWriteStatus.APPLIED,
            freshRepository().replaceCourses(listOf(course(11), course(22)), emptyList()).status
        )
    }

    private fun deleteWithCapturedPreferences(operationId: String = "op-1"): CourseDeletionOutcome.Applied {
        assertTrue(CourseReminders.setOverride(context, 11L, true))
        assertTrue(CourseReminders.markNotified(context, 11L, 5_000L))
        assertTrue(CourseLocationOverrides.set(context, 11L, 20_000L, "临时教室 3"))
        val outcome = CourseRecoveryOperations.deleteCourses(
            context, freshRepository(), CourseRecoveryScope.MEETING, setOf(11L), operationId, now
        )
        assertTrue("expected Applied but got $outcome", outcome is CourseDeletionOutcome.Applied)
        return outcome as CourseDeletionOutcome.Applied
    }

    @Test
    fun `restore and complete brings the courses back, writes the captured values and reaches RESTORED`() {
        val applied = deleteWithCapturedPreferences()
        val candidate = CourseRecoveryOperations.latestRestorableGroup(freshRepository(), now)
        assertNotNull(candidate)
        assertEquals(applied.group.groupId, candidate!!.groupId)
        assertEquals(1, candidate.meetingCount)
        assertEquals(CourseRecoveryState.ACTIVE, candidate.state)

        val outcome = CourseRecoveryOperations.restoreAndComplete(
            context, freshRepository(), applied.group.groupId, now + 1_000L
        )
        assertTrue("expected Completed but got $outcome", outcome is CourseRestoreCompletionOutcome.Completed)

        // Courses are back in the original relative order and the preferences match the snapshot.
        assertEquals(listOf(22L, 11L), freshStore().loadCourses().map(Course::id))
        assertEquals(true, CourseReminders.load(context).overrides[11L])
        assertEquals(5_000L, CourseRecoveryPreferences.watermark(context, 11L).let {
            (it as CourseRecoveryWatermarkRead.Value).at
        })
        assertEquals(mapOf(20_000L to "临时教室 3"), CourseLocationOverrides.snapshot(context, 11L))

        // The terminal state is persisted, and a rebuilt store object reads it.
        val group = groups().single()
        assertEquals(CourseRecoveryState.RESTORED, group.state)
        assertEquals(now + 1_000L, group.restoredAt)
        assertEquals(CourseRecoveryState.RESTORED, groups(LegacyCoreDataRepository(freshStore())).single().state)

        // A RESTORED group is no longer offered as restorable.
        assertNull(CourseRecoveryOperations.latestRestorableGroup(freshRepository(), now + 1_000L))
    }

    @Test
    fun `the follow-up writes the captured values back after they changed while deleted`() {
        val applied = deleteWithCapturedPreferences()
        // While the meeting was deleted its preferences drifted.
        assertTrue(CourseReminders.setOverride(context, 11L, false))
        assertTrue(prefs("course_reminder_settings").edit().remove("delivered_11").commit())
        assertTrue(CourseLocationOverrides.set(context, 11L, 20_000L, "被改过的地点"))

        val outcome = CourseRecoveryOperations.restoreAndComplete(
            context, freshRepository(), applied.group.groupId, now + 1_000L
        )
        assertTrue("expected Completed but got $outcome", outcome is CourseRestoreCompletionOutcome.Completed)
        assertEquals(true, CourseReminders.load(context).overrides[11L])
        assertEquals(
            CourseRecoveryWatermarkRead.Value(5_000L),
            CourseRecoveryPreferences.watermark(context, 11L)
        )
        assertEquals(mapOf(20_000L to "临时教室 3"), CourseLocationOverrides.snapshot(context, 11L))
    }

    @Test
    fun `an unconfirmed preference step keeps the group RESTORING and never claims success`() {
        val applied = deleteWithCapturedPreferences()
        val core = CourseRecoveryOperations.restoreGroup(freshRepository(), applied.group.groupId, now + 1L)
        assertTrue("expected CoreCommitted but got $core", core is CourseRestoreOutcome.CoreCommitted)

        val pending = CourseRecoveryFollowUp.run(
            context, freshRepository(), applied.group.groupId, now + 2L, failingWriter
        )
        assertTrue("expected follow-up pending but got $pending", pending is CourseRestoreCompletionOutcome.CoreCommittedFollowUpPending)
        val group = groups().single()
        assertEquals(CourseRecoveryState.RESTORING, group.state)
        assertNull(group.restoredAt)
        // The core courses are committed even though the follow-up is unconfirmed.
        assertEquals(listOf(22L, 11L), freshStore().loadCourses().map(Course::id))
    }

    @Test
    fun `a later call resumes the unconfirmed follow-up and only then reaches RESTORED`() {
        val applied = deleteWithCapturedPreferences()
        CourseRecoveryOperations.restoreGroup(freshRepository(), applied.group.groupId, now + 1L)
        CourseRecoveryFollowUp.run(context, freshRepository(), applied.group.groupId, now + 2L, failingWriter)
        assertEquals(CourseRecoveryState.RESTORING, groups().single().state)

        // A rebuilt store object resumes the same follow-up through the production entry.
        val resumed = CourseRecoveryOperations.restoreAndComplete(
            context, LegacyCoreDataRepository(freshStore()), applied.group.groupId, now + 3L
        )
        assertTrue("expected Completed but got $resumed", resumed is CourseRestoreCompletionOutcome.Completed)
        assertEquals(listOf(22L, 11L), freshStore().loadCourses().map(Course::id))
        assertEquals(CourseRecoveryState.RESTORED, groups(LegacyCoreDataRepository(freshStore())).single().state)
        assertEquals(now + 3L, groups().single().restoredAt)
        // Replaying the completed follow-up changes nothing.
        val again = CourseRecoveryOperations.restoreAndComplete(
            context, freshRepository(), applied.group.groupId, now + 4L
        )
        assertTrue(again is CourseRestoreCompletionOutcome.Completed)
        assertEquals(now + 3L, groups().single().restoredAt)
    }

    @Test
    fun `reminder coordination cancels the deleted meeting and schedules it again after the restore`() {
        val table = CoursePeriodTable.reference()
        freshStore().saveCoursePeriodTable(table)
        assertTrue(CourseReminders.setGlobal(context, true))
        val meeting = Course(
            "实验", LocalDate.now().plusDays(1).dayOfWeek.value, 1, 1, "东一", CampusZone.OTHER,
            needsConfirmation = false, id = 601L
        )
        val before = freshStore().loadCourses()
        assertEquals(CoreDataWriteStatus.APPLIED, freshRepository().replaceCourses(listOf(meeting), before).status)
        val shadow = Shadows.shadowOf(context.getSystemService(AlarmManager::class.java))
        val settings = CourseReminders.load(context)
        CourseReminders.sync(context, emptyList(), listOf(meeting), table, settings)
        assertEquals(1, shadow.scheduledAlarms.size)

        val deleted = CourseRecoveryOperations.deleteCourses(
            context, freshRepository(), CourseRecoveryScope.MEETING, setOf(601L), "op-alarm", now
        )
        assertTrue("expected Applied but got $deleted", deleted is CourseDeletionOutcome.Applied)
        val afterDelete = freshStore().loadCourses()
        CourseReminders.sync(context, listOf(meeting), afterDelete, table, settings)
        assertEquals(0, shadow.scheduledAlarms.size)

        val outcome = CourseRecoveryOperations.restoreAndComplete(
            context, freshRepository(), (deleted as CourseDeletionOutcome.Applied).group.groupId, now + 1L
        )
        assertTrue("expected Completed but got $outcome", outcome is CourseRestoreCompletionOutcome.Completed)
        assertEquals(1, shadow.scheduledAlarms.size)
        assertTrue(freshStore().loadCourses().any { it.id == 601L })
    }

    @Test
    fun `a group stuck in RESTORING is not purged until the follow-up completes`() {
        val applied = deleteWithCapturedPreferences()
        CourseRecoveryOperations.restoreGroup(freshRepository(), applied.group.groupId, now + 1L)
        CourseRecoveryFollowUp.run(context, freshRepository(), applied.group.groupId, now + 2L, failingWriter)
        val expiry = applied.group.expiresAt

        val skipped = CourseRecoveryOperations.purgeExpired(freshRepository(), expiry + 1L)
        assertEquals(CoursePurgeOutcome.NothingToDo, skipped)
        assertEquals(CourseRecoveryState.RESTORING, groups().single().state)

        CourseRecoveryOperations.restoreAndComplete(context, freshRepository(), applied.group.groupId, now + 3L)
        val purged = CourseRecoveryOperations.purgeExpired(freshRepository(), expiry + 1L)
        assertTrue("expected Applied but got $purged", purged is CoursePurgeOutcome.Applied)
        assertEquals(setOf(applied.group.groupId), (purged as CoursePurgeOutcome.Applied).removedGroupIds)
        assertEquals(0, groups().size)
        // Purging the finished group still leaves the restored courses alone.
        assertTrue(freshStore().loadCourses().any { it.id == 11L })
    }

    @Test
    fun `a batch delete of two meetings restores both with their own captured preferences`() {
        assertEquals(
            CoreDataWriteStatus.APPLIED,
            freshRepository().replaceCourses(listOf(course(11), course(22), course(33)), listOf(course(11), course(22))).status
        )
        assertTrue(CourseReminders.setOverride(context, 11L, true))
        assertTrue(CourseLocationOverrides.set(context, 11L, 20_000L, "甲地点"))
        assertTrue(CourseReminders.setOverride(context, 33L, false))
        assertTrue(CourseReminders.markNotified(context, 33L, 7_000L))

        val deleted = CourseRecoveryOperations.deleteCourses(
            context, freshRepository(), CourseRecoveryScope.BATCH, setOf(11L, 33L), "op-batch", now
        )
        assertTrue("expected Applied but got $deleted", deleted is CourseDeletionOutcome.Applied)
        val candidate = CourseRecoveryOperations.latestRestorableGroup(freshRepository(), now)
        assertEquals(2, candidate?.meetingCount)
        assertEquals(listOf(22L), freshStore().loadCourses().map(Course::id))

        val outcome = CourseRecoveryOperations.restoreAndComplete(
            context, freshRepository(), (deleted as CourseDeletionOutcome.Applied).group.groupId, now + 1L
        )
        assertTrue("expected Completed but got $outcome", outcome is CourseRestoreCompletionOutcome.Completed)
        assertEquals(listOf(22L, 11L, 33L), freshStore().loadCourses().map(Course::id))
        assertEquals(true, CourseReminders.load(context).overrides[11L])
        assertEquals(false, CourseReminders.load(context).overrides[33L])
        assertEquals(mapOf(20_000L to "甲地点"), CourseLocationOverrides.snapshot(context, 11L))
        assertEquals(CourseRecoveryWatermarkRead.Value(7_000L), CourseRecoveryPreferences.watermark(context, 33L))
        assertEquals(CourseRecoveryState.RESTORED, groups().single().state)
    }

    @Test
    fun `an ACTIVE group is refused by the follow-up and a PURGING group is never resurrected`() {
        val applied = deleteWithCapturedPreferences()
        val active = CourseRecoveryFollowUp.run(context, freshRepository(), applied.group.groupId, now + 1L)
        assertTrue("expected Rejected but got $active", active is CourseRestoreCompletionOutcome.Rejected)
        assertEquals(
            CoreDataWriteStatus.CONDITION_NOT_MET,
            (active as CourseRestoreCompletionOutcome.Rejected).status
        )
        assertFalse(freshStore().loadCourses().any { it.id == 11L })

        val unknown = CourseRecoveryFollowUp.run(context, freshRepository(), "missing", now + 1L)
        assertEquals(
            CoreDataWriteStatus.INVALID_INPUT,
            (unknown as CourseRestoreCompletionOutcome.Rejected).status
        )
    }
}
