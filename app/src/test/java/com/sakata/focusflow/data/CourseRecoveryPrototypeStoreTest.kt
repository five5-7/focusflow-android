package com.sakata.focusflow.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sakata.focusflow.CampusZone
import com.sakata.focusflow.Course
import com.sakata.focusflow.CourseEditPlans
import com.sakata.focusflow.CourseLocationOverrides
import com.sakata.focusflow.CourseMergeOperation
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit

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

    // ------------------------------------------------- item 3: strict capture of target preferences

    private fun rejectedDelete(reason: String): CourseDeletionOutcome.Rejected {
        val outcome = CourseRecoveryOperations.deleteCourses(
            context, freshRepository(), CourseRecoveryScope.MEETING, setOf(11L), "op-strict", now
        )
        assertTrue("expected Rejected but got $outcome", outcome is CourseDeletionOutcome.Rejected)
        val rejected = outcome as CourseDeletionOutcome.Rejected
        assertEquals(CoreDataWriteStatus.INVALID_INPUT, rejected.status)
        assertTrue("expected a diagnostic reason, got ${rejected.reason}", rejected.reason.contains(reason))
        return rejected
    }

    private fun assertNothingWasWritten() {
        assertEquals(listOf(11L, 22L), freshStore().loadCourses().map(Course::id))
        assertEquals(0, storedGroups().size)
    }

    @Test
    fun `a non-boolean reminder override rejects the delete and stays untouched`() {
        prefs("course_reminder_settings").edit().putString("meeting_11", "yes").commit()
        val before = prefs("course_reminder_settings").all.toMap()
        rejectedDelete("meeting_11")
        assertNothingWasWritten()
        assertEquals(before, prefs("course_reminder_settings").all.toMap())
    }

    @Test
    fun `a watermark of the wrong type rejects the delete instead of being dropped`() {
        prefs("course_reminder_settings").edit().putInt("delivered_11", 123).commit()
        val before = prefs("course_reminder_settings").all.toMap()
        rejectedDelete("delivered_11")
        assertNothingWasWritten()
        assertEquals(before, prefs("course_reminder_settings").all.toMap())

        prefs("course_reminder_settings").edit().putString("delivered_11", "123").commit()
        val beforeString = prefs("course_reminder_settings").all.toMap()
        rejectedDelete("delivered_11")
        assertEquals(beforeString, prefs("course_reminder_settings").all.toMap())
    }

    @Test
    fun `a non-positive watermark rejects the delete`() {
        prefs("course_reminder_settings").edit().putLong("delivered_11", 0L).commit()
        rejectedDelete("delivered_11")
        assertNothingWasWritten()
    }

    @Test
    fun `the normal absent false and true override states still capture`() {
        assertTrue(CourseReminders.setOverride(context, 11L, false))
        assertTrue(CourseReminders.markNotified(context, 11L, 7_000L))
        val absent = CourseRecoveryOperations.deleteCourses(
            context, freshRepository(), CourseRecoveryScope.MEETING, setOf(22L), "absent", now
        ) as CourseDeletionOutcome.Applied
        assertEquals(null, absent.group.members.single().reminderOverride)
        assertNull(absent.group.members.single().deliveredAt)

        val captured = CourseRecoveryOperations.deleteCourses(
            context, freshRepository(), CourseRecoveryScope.MEETING, setOf(11L), "present", now
        ) as CourseDeletionOutcome.Applied
        assertEquals(false, captured.group.members.single().reminderOverride)
        assertEquals(7_000L, captured.group.members.single().deliveredAt)
    }

    @Test
    fun `a non-canonical location day rejects the delete and keeps the raw value`() {
        prefs("course_location_overrides").edit().putString("11_020000", "临时教室").commit()
        val before = prefs("course_location_overrides").all.toMap()
        rejectedDelete("11_020000")
        assertNothingWasWritten()
        assertEquals(before, prefs("course_location_overrides").all.toMap())
    }

    @Test
    fun `two spellings of the same day reject the delete instead of being merged`() {
        prefs("course_location_overrides").edit()
            .putString("11_20000", "教室甲")
            .putString("11_020000", "教室乙")
            .commit()
        val before = prefs("course_location_overrides").all.toMap()
        rejectedDelete("11_")
        assertNothingWasWritten()
        assertEquals(before, prefs("course_location_overrides").all.toMap())
    }

    @Test
    fun `a non-text blank or over-long location rejects the delete`() {
        prefs("course_location_overrides").edit().putInt("11_20000", 5).commit()
        rejectedDelete("not text")

        prefs("course_location_overrides").edit().putString("11_20000", "   ").commit()
        rejectedDelete("blank")

        prefs("course_location_overrides").edit().putString("11_20000", "地".repeat(101)).commit()
        rejectedDelete("longer than 100")
        assertNothingWasWritten()
    }

    @Test
    fun `a malformed value for another meeting never blocks this delete`() {
        prefs("course_reminder_settings").edit()
            .putString("meeting_99", "corrupt")
            .putInt("delivered_99", 5)
            .commit()
        prefs("course_location_overrides").edit()
            .putString("99_020000", "别名")
            .putInt("99_20000", 3)
            .commit()
        val otherBefore = prefs("course_reminder_settings").all.toMap() +
            prefs("course_location_overrides").all.toMap()

        val outcome = CourseRecoveryOperations.deleteCourses(
            context, freshRepository(), CourseRecoveryScope.MEETING, setOf(11L), "op-11", now
        )
        assertTrue("expected Applied but got $outcome", outcome is CourseDeletionOutcome.Applied)
        assertEquals(listOf(22L), freshStore().loadCourses().map(Course::id))
        assertEquals(1, storedGroups().size)
        assertEquals(
            otherBefore,
            prefs("course_reminder_settings").all.toMap() + prefs("course_location_overrides").all.toMap()
        )
    }

    @Test
    fun `a non-string recovery payload is diagnosed instead of throwing or reading as empty`() {
        prefs("focusflow").edit().putBoolean("course_recovery_groups_v1", true).commit()

        val read = CourseRecoveryOperations.readGroups(freshRepository())
        assertTrue("expected Invalid but got $read", read is CourseRecoveryGroupsRead.Invalid)
        val invalid = read as CourseRecoveryGroupsRead.Invalid
        assertTrue("expected a type diagnosis, got ${invalid.reason}", invalid.reason.contains("Boolean"))
        assertNull(invalid.raw)

        val outcome = CourseRecoveryOperations.deleteCourses(
            context, freshRepository(), CourseRecoveryScope.MEETING, setOf(11L), "op-1", now
        )
        assertTrue("expected Rejected but got $outcome", outcome is CourseDeletionOutcome.Rejected)
        assertEquals(true, prefs("focusflow").getBoolean("course_recovery_groups_v1", false))
        assertEquals(listOf(11L, 22L), freshStore().loadCourses().map(Course::id))

        val restore = CourseRecoveryOperations.restoreGroup(freshRepository(), "missing", now)
        assertTrue(restore is CourseRestoreOutcome.Rejected)
        val purge = CourseRecoveryOperations.purgeExpired(freshRepository(), now)
        assertTrue(purge is CoursePurgeOutcome.Rejected)
        assertEquals(true, prefs("focusflow").getBoolean("course_recovery_groups_v1", false))
    }

    // ------------------------------------------------ item 2: one mutual exclusion for course writes

    @Test
    fun `a recovery delete waits for the course lock instead of interleaving`() {
        val repository = freshRepository()
        val lockHeld = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder = Thread {
            repository.withCourseWriteLock {
                lockHeld.countDown()
                release.await(10, TimeUnit.SECONDS)
            }
        }
        holder.start()
        assertTrue("the helper thread must hold the lock", lockHeld.await(10, TimeUnit.SECONDS))

        val done = CountDownLatch(1)
        var outcome: CourseDeletionOutcome? = null
        val worker = Thread {
            outcome = CourseRecoveryOperations.deleteCourses(
                context, repository, CourseRecoveryScope.MEETING, setOf(11L), "op-lock", now
            )
            done.countDown()
        }
        worker.start()
        assertFalse(
            "the delete must not reach its commit while another writer holds the lock",
            done.await(300, TimeUnit.MILLISECONDS)
        )
        release.countDown()
        assertTrue("the delete must finish once the lock is released", done.await(10, TimeUnit.SECONDS))
        holder.join()
        worker.join()

        assertTrue("expected Applied but got $outcome", outcome is CourseDeletionOutcome.Applied)
        assertEquals(listOf(22L), freshStore().loadCourses().map(Course::id))
        assertEquals(1, storedGroups().size)
    }

    @Test
    fun `a real merge waits for the recovery lock and keeps its own write`() {
        val repository = freshRepository()
        val before = freshStore().loadCourses()
        // Merge needs two meetings of the same slot/period.
        val mergeCourses = listOf(course(31, title = "实验"), course(32, title = "实验"))
        assertEquals(
            CoreDataWriteStatus.APPLIED,
            repository.replaceCourses(mergeCourses, before).status
        )
        val plan = CourseMergeOperation.preview(context, mergeCourses, 31L)
            as CourseEditPlans.CourseMergePlan.Applied

        val lockHeld = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder = Thread {
            repository.withCourseWriteLock {
                lockHeld.countDown()
                release.await(10, TimeUnit.SECONDS)
            }
        }
        holder.start()
        assertTrue(lockHeld.await(10, TimeUnit.SECONDS))

        val done = CountDownLatch(1)
        var outcome: CourseMergeOperation.Outcome? = null
        val merger = Thread {
            outcome = CourseMergeOperation.apply(
                context, repository, mergeCourses, setOf(31L, 32L), 31L, plan
            )
            done.countDown()
        }
        merger.start()
        assertFalse(
            "the merge must not create its journal while another writer holds the lock",
            done.await(300, TimeUnit.MILLISECONDS)
        )
        release.countDown()
        assertTrue("the merge must finish once the lock is released", done.await(10, TimeUnit.SECONDS))
        holder.join()
        merger.join()

        assertEquals(CourseMergeOperation.Outcome.APPLIED, outcome)
        assertEquals(listOf(31L), freshStore().loadCourses().map(Course::id))
        assertNull(prefs("course_merge_journal").getString("pending", null))
    }

    @Test
    fun `a journal created while the lock is held is observed and blocks the recovery write`() {
        val repository = freshRepository()
        val lockHeld = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder = Thread {
            repository.withCourseWriteLock {
                lockHeld.countDown()
                release.await(10, TimeUnit.SECONDS)
            }
        }
        holder.start()
        assertTrue(lockHeld.await(10, TimeUnit.SECONDS))
        // A merge/split journal appears before the recovery enters its critical section.
        prefs("course_merge_journal").edit().putString("pending", "{}").commit()

        val done = CountDownLatch(1)
        var outcome: CourseDeletionOutcome? = null
        val worker = Thread {
            outcome = CourseRecoveryOperations.deleteCourses(
                context, repository, CourseRecoveryScope.MEETING, setOf(11L), "op-journal", now
            )
            done.countDown()
        }
        worker.start()
        release.countDown()
        assertTrue(done.await(10, TimeUnit.SECONDS))
        holder.join()
        worker.join()

        assertTrue("expected Rejected but got $outcome", outcome is CourseDeletionOutcome.Rejected)
        assertEquals(
            CoreDataWriteStatus.CONDITION_NOT_MET,
            (outcome as CourseDeletionOutcome.Rejected).status
        )
        assertEquals(listOf(11L, 22L), freshStore().loadCourses().map(Course::id))
        assertEquals(0, storedGroups().size)
        assertEquals("{}", prefs("course_merge_journal").getString("pending", null))
    }

    @Test
    fun `two recovery deletes racing keep one group and lose no write`() {
        val repository = freshRepository()
        val barrier = CyclicBarrier(2)
        val results = arrayOfNulls<CourseDeletionOutcome>(2)
        val threads = (0..1).map { index ->
            Thread {
                barrier.await(10, TimeUnit.SECONDS)
                results[index] = CourseRecoveryOperations.deleteCourses(
                    context, repository, CourseRecoveryScope.MEETING, setOf(11L), "op-race-$index", now
                )
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join(10_000) }

        val applied = results.count { it is CourseDeletionOutcome.Applied }
        val rejected = results.count {
            it is CourseDeletionOutcome.Rejected &&
                (it as CourseDeletionOutcome.Rejected).status == CoreDataWriteStatus.STALE_COURSES
        }
        assertEquals("exactly one racer may commit: ${results.toList()}", 1, applied)
        assertEquals("the loser must be rejected as stale: ${results.toList()}", 1, rejected)
        assertEquals(listOf(22L), freshStore().loadCourses().map(Course::id))
        assertEquals(1, storedGroups().size)
        assertEquals(1, results.filterNotNull().count { it is CourseDeletionOutcome.Applied })
    }
}
