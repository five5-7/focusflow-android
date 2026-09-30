package com.sakata.focusflow.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sakata.focusflow.CampusZone
import com.sakata.focusflow.Course
import com.sakata.focusflow.CourseLocationOverrides
import com.sakata.focusflow.CourseReminders
import com.sakata.focusflow.PrototypeStore
import com.sakata.focusflow.StorageProtection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Real Legacy path: the recovery store on top of an actual [PrototypeStore] and real
 * SharedPreferences. "Rebuilt instance" means a fresh store object over the same preferences,
 * which is not the same thing as a process restart.
 */
@RunWith(RobolectricTestRunner::class)
class CourseRecoveryPrototypeStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val now = 1_000L

    private fun course(id: Long, courseId: Long = id, title: String = "课程$id") = Course(
        title = title, weekday = 1, startPeriod = 1, endPeriod = 2, building = "东一",
        zone = CampusZone.EAST_TEACHING, needsConfirmation = false, enabled = true,
        id = id, courseId = courseId
    )

    private fun freshStore() = PrototypeStore(context)
    private fun freshRepository() = LegacyCoreDataRepository(freshStore())

    private fun prefs(name: String) = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    @Before
    fun clearPreferences() {
        listOf(
            "focusflow", "course_reminder_settings", "course_location_overrides",
            "course_merge_journal", "course_split_journal"
        ).forEach { prefs(it).edit().clear().commit() }
        StorageProtection.retry()
        val seed = freshRepository()
        assertEquals(
            CoreDataWriteStatus.APPLIED,
            seed.replaceCourses(listOf(course(11), course(22)), emptyList()).status
        )
    }

    private fun storedGroups(repository: CoreDataRepository = freshRepository()): List<CourseRecoveryGroup> {
        val read = CourseRecoveryOperations.readGroups(repository)
        assertTrue("expected Ready but got $read", read is CourseRecoveryGroupsRead.Ready)
        return (read as CourseRecoveryGroupsRead.Ready).groups
    }

    @Test
    fun `delete persists the course list and the group so a rebuilt store still sees them`() {
        assertTrue(CourseReminders.setOverride(context, 11L, true))
        assertTrue(CourseReminders.markNotified(context, 11L, 5_000L))
        assertTrue(CourseLocationOverrides.set(context, 11L, 20_000L, "临时教室 3"))
        val reminderPrefsBefore = prefs("course_reminder_settings").all.toMap()
        val locationPrefsBefore = prefs("course_location_overrides").all.toMap()

        val outcome = CourseRecoveryOperations.deleteCourses(
            context, freshRepository(), CourseRecoveryScope.MEETING, setOf(11L), "op-1", now
        )
        assertTrue("expected Applied but got $outcome", outcome is CourseDeletionOutcome.Applied)

        // A rebuilt store instance sees the course list and the ACTIVE group.
        val rebuilt = freshStore()
        assertEquals(listOf(22L), rebuilt.loadCourses().map(Course::id))
        val group = storedGroups(LegacyCoreDataRepository(rebuilt)).single()
        assertEquals(CourseRecoveryState.ACTIVE, group.state)
        assertEquals(listOf(11L), group.members.map { it.id })
        assertEquals(true, group.members[0].reminderOverride)
        assertEquals(5_000L, group.members[0].deliveredAt)
        assertEquals(mapOf(20_000L to "临时教室 3"), group.members[0].temporaryLocations)
        assertEquals(course(11), CourseSnapshotCodec.decode(group.members[0].courseJson))

        // Preferences were only read, never written.
        assertEquals(reminderPrefsBefore, prefs("course_reminder_settings").all.toMap())
        assertEquals(locationPrefsBefore, prefs("course_location_overrides").all.toMap())
    }

    @Test
    fun `restore persists the courses and the restoring state across instances`() {
        val repository = freshRepository()
        val applied = CourseRecoveryOperations.deleteCourses(
            context, repository, CourseRecoveryScope.MEETING, setOf(11L), "op-1", now
        ) as CourseDeletionOutcome.Applied

        val restored = CourseRecoveryOperations.restoreGroup(repository, applied.group.groupId, now + 10L)
        assertTrue("expected CoreCommitted but got $restored", restored is CourseRestoreOutcome.CoreCommitted)

        val rebuilt = freshStore()
        assertEquals(listOf(22L, 11L), rebuilt.loadCourses().map(Course::id))
        val group = storedGroups(LegacyCoreDataRepository(rebuilt)).single()
        assertEquals(CourseRecoveryState.RESTORING, group.state)
        assertNull(group.restoredAt)
        assertNotNull(group.sourceFingerprint)

        // The second call resumes; it never re-appends the courses.
        val again = CourseRecoveryOperations.restoreGroup(
            LegacyCoreDataRepository(rebuilt), group.groupId, now + 20L
        )
        assertTrue("expected NeedsFollowUp but got $again", again is CourseRestoreOutcome.NeedsFollowUp)
        assertEquals(listOf(22L, 11L), freshStore().loadCourses().map(Course::id))
    }

    @Test
    fun `a corrupt payload blocks writes and keeps the raw content`() {
        prefs("focusflow").edit().putString("course_recovery_groups_v1", "{ not json").commit()

        val read = CourseRecoveryOperations.readGroups(freshRepository())
        assertTrue(read is CourseRecoveryGroupsRead.Invalid)
        assertEquals("{ not json", (read as CourseRecoveryGroupsRead.Invalid).raw)

        val outcome = CourseRecoveryOperations.deleteCourses(
            context, freshRepository(), CourseRecoveryScope.MEETING, setOf(11L), "op-1", now
        )
        assertTrue("expected Rejected but got $outcome", outcome is CourseDeletionOutcome.Rejected)
        assertEquals("{ not json", prefs("focusflow").getString("course_recovery_groups_v1", null))
        assertEquals(listOf(11L, 22L), freshStore().loadCourses().map(Course::id))
    }

    @Test
    fun `an unfinished merge journal blocks deletion until it is cleared`() {
        prefs("course_merge_journal").edit().putString("pending", "{}").commit()
        val blocked = CourseRecoveryOperations.deleteCourses(
            context, freshRepository(), CourseRecoveryScope.MEETING, setOf(11L), "op-1", now
        )
        assertEquals(CoreDataWriteStatus.CONDITION_NOT_MET, (blocked as CourseDeletionOutcome.Rejected).status)
        assertEquals(listOf(11L, 22L), freshStore().loadCourses().map(Course::id))

        prefs("course_merge_journal").edit().remove("pending").commit()
        val allowed = CourseRecoveryOperations.deleteCourses(
            context, freshRepository(), CourseRecoveryScope.MEETING, setOf(11L), "op-2", now
        )
        assertTrue("expected Applied but got $allowed", allowed is CourseDeletionOutcome.Applied)
    }

    @Test
    fun `a historical group never blocks an ordinary course edit`() {
        CourseRecoveryOperations.deleteCourses(
            context, freshRepository(), CourseRecoveryScope.MEETING, setOf(11L), "op-1", now
        )
        val beforeEdit = freshStore().loadCourses()
        // An ordinary edit appends a new course; the historical group still refers to meeting 11.
        val outcome = freshRepository().replaceCourses(beforeEdit + course(33), beforeEdit)
        assertEquals(CoreDataWriteStatus.APPLIED, outcome.status)
        assertEquals(listOf(22L, 33L), freshStore().loadCourses().map(Course::id))
        assertEquals(1, storedGroups().size)
    }

    @Test
    fun `purge removes only the expired group and leaves courses and preferences alone`() {
        assertTrue(CourseReminders.setOverride(context, 11L, false))
        CourseRecoveryOperations.deleteCourses(
            context, freshRepository(), CourseRecoveryScope.MEETING, setOf(11L), "old", now
        )
        // A second, still-fresh group: its retention window starts much later.
        val later = now + CourseRecoveryJournal.RETENTION_MS
        CourseRecoveryOperations.deleteCourses(
            context, freshRepository(), CourseRecoveryScope.MEETING, setOf(22L), "new", later
        )
        val coursesBeforePurge = freshStore().loadCourses()
        val reminderPrefsBefore = prefs("course_reminder_settings").all.toMap()
        val locationPrefsBefore = prefs("course_location_overrides").all.toMap()

        val purged = CourseRecoveryOperations.purgeExpired(
            freshRepository(), now + CourseRecoveryJournal.RETENTION_MS + 1L
        )
        assertTrue("expected Applied but got $purged", purged is CoursePurgeOutcome.Applied)
        assertEquals(1, (purged as CoursePurgeOutcome.Applied).removedGroupIds.size)
        assertEquals(1, storedGroups().size)
        assertEquals("new", storedGroups().single().operationId)
        assertEquals(coursesBeforePurge, freshStore().loadCourses())
        assertEquals(reminderPrefsBefore, prefs("course_reminder_settings").all.toMap())
        assertEquals(locationPrefsBefore, prefs("course_location_overrides").all.toMap())
    }

    @Test
    fun `the same operation id is idempotent on the real store`() {
        val repository = freshRepository()
        val first = CourseRecoveryOperations.deleteCourses(
            context, repository, CourseRecoveryScope.MEETING, setOf(11L), "op-1", now
        )
        assertTrue(first is CourseDeletionOutcome.Applied)
        val second = CourseRecoveryOperations.deleteCourses(
            context, freshRepository(), CourseRecoveryScope.MEETING, setOf(22L), "op-1", now
        )
        assertTrue("expected AlreadyApplied but got $second", second is CourseDeletionOutcome.AlreadyApplied)
        assertEquals(listOf(22L), freshStore().loadCourses().map(Course::id))
        assertEquals(1, storedGroups().size)
    }
}
