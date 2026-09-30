package com.sakata.focusflow.data

import com.sakata.focusflow.CampusZone
import com.sakata.focusflow.Course
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 7.5 Legacy integration batch: the recovery store driven by an in-memory persistence.
 * These tests pin the store contract (guards, single-commit pairing, outcomes); the real
 * SharedPreferences path is covered by `CourseRecoveryPrototypeStoreTest`.
 */
class CourseRecoveryStoreTest {

    private val deletedAt = 1_000L
    private val expiresAt = deletedAt + CourseRecoveryJournal.RETENTION_MS

    private fun course(
        id: Long,
        courseId: Long = id,
        title: String = "课程$id",
        weekday: Int = 1,
        startPeriod: Int = 1,
        endPeriod: Int = 2,
        building: String = "东一",
        zone: CampusZone = CampusZone.EAST_TEACHING,
        needsConfirmation: Boolean = false,
        enabled: Boolean = true
    ) = Course(
        title = title, weekday = weekday, startPeriod = startPeriod, endPeriod = endPeriod,
        building = building, zone = zone, needsConfirmation = needsConfirmation, enabled = enabled,
        id = id, courseId = courseId
    )

    private fun request(
        ids: Set<Long>,
        scope: CourseRecoveryScope = CourseRecoveryScope.MEETING,
        operationId: String = "op-1",
        now: Long = deletedAt,
        overrides: Map<Long, Boolean> = emptyMap(),
        delivered: Map<Long, Long> = emptyMap(),
        locations: Map<Long, Map<Long, String>> = emptyMap()
    ) = CourseRecoveryRequest(ids, scope, operationId, now, overrides, delivered, locations)

    private fun readyGroups(store: CourseRecoveryStore): List<CourseRecoveryGroup> {
        val read = store.read()
        assertTrue("expected Ready but got $read", read is CourseRecoveryGroupsRead.Ready)
        return (read as CourseRecoveryGroupsRead.Ready).groups
    }

    /** In-memory persistence with PrototypeStore-equivalent guard semantics. */
    private class FakeStorage(
        var courses: List<Course> = emptyList(),
        var raw: String? = null
    ) : CourseRecoveryStorage {
        var readOnly = false
        var pendingJournal = false
        var failCommits = false
        var throwOnCommit = false
        var thirdStateOnFailure = false

        /** Writes the new state but reports failure: the ambiguous commit case. */
        var landWhileFailing = false
        var commits = 0
        private val lock = Any()

        override fun isStorageReadOnly(): Boolean = readOnly

        override fun <T> withCourseWriteLock(block: () -> T): T = synchronized(lock) { block() }

        override fun readCourses(): List<Course> = courses

        override fun loadCourseRecoveryGroups(): CourseRecoveryGroupsRead {
            val stored = raw ?: return CourseRecoveryGroupsRead.Ready(emptyList())
            return when (val load = CourseRecoveryCodec.decode(stored)) {
                is CourseRecoveryLoad.Ready -> CourseRecoveryGroupsRead.Ready(load.groups)
                is CourseRecoveryLoad.Invalid -> CourseRecoveryGroupsRead.Invalid(load.reason, stored)
            }
        }

        override fun hasPendingCourseEditJournal(): Boolean = pendingJournal

        override fun commitCoursesAndRecoveryGroups(
            courses: List<Course>,
            groups: List<CourseRecoveryGroup>,
            expectedCourses: List<Course>,
            expectedGroups: List<CourseRecoveryGroup>
        ): Boolean {
            commits++
            if (throwOnCommit) throw IllegalStateException("commit exploded")
            if (failCommits) {
                if (landWhileFailing) {
                    this.courses = courses
                    this.raw = CourseRecoveryCodec.encode(groups)
                } else if (thirdStateOnFailure) {
                    this.courses = this.courses + Course(
                        "外部写入", 2, 1, 1, "西一", CampusZone.OTHER, id = 9_999L
                    )
                }
                return false
            }
            if (readOnly || this.courses != expectedCourses) return false
            val current = (loadCourseRecoveryGroups() as? CourseRecoveryGroupsRead.Ready)?.groups ?: return false
            if (current != expectedGroups) return false
            this.courses = courses
            this.raw = CourseRecoveryCodec.encode(groups)
            return true
        }

        override fun commitCourseRecoveryGroups(
            groups: List<CourseRecoveryGroup>,
            expectedGroups: List<CourseRecoveryGroup>
        ): Boolean {
            commits++
            if (throwOnCommit) throw IllegalStateException("commit exploded")
            if (failCommits) return false
            if (readOnly) return false
            val current = (loadCourseRecoveryGroups() as? CourseRecoveryGroupsRead.Ready)?.groups ?: return false
            if (current != expectedGroups) return false
            this.raw = CourseRecoveryCodec.encode(groups)
            return true
        }
    }

    // ---------------------------------------------------------------- A: storage and reads

    @Test
    fun `a missing key reads as an empty collection while corruption is reported with its raw payload`() {
        val storage = FakeStorage(courses = listOf(course(id = 11)))
        val store = LegacyCourseRecoveryStore(storage)
        assertEquals(CourseRecoveryGroupsRead.Ready(emptyList()), store.read())

        storage.raw = "{ \"version\": 1, \"groups\": [ {"
        val corrupt = store.read()
        assertTrue(corrupt is CourseRecoveryGroupsRead.Invalid)
        assertEquals(storage.raw, (corrupt as CourseRecoveryGroupsRead.Invalid).raw)
    }

    @Test
    fun `the unsupported runtime reports not ready instead of silently succeeding`() {
        assertEquals(
            CourseRecoveryGroupsRead.NotReady(UnsupportedCourseRecoveryStore.REASON),
            UnsupportedCourseRecoveryStore.read()
        )
        assertTrue(
            UnsupportedCourseRecoveryStore.deleteCoursesWithRecoveryGroup(
                emptyList(), request(setOf(11L))
            ) is CourseDeletionOutcome.NotReady
        )
        assertTrue(
            UnsupportedCourseRecoveryStore.restoreCourseRecoveryGroup("g", emptyList(), 1L) is
                CourseRestoreOutcome.NotReady
        )
        assertTrue(
            UnsupportedCourseRecoveryStore.purgeCourseRecoveryGroups(emptyList(), 1L) is
                CoursePurgeOutcome.NotReady
        )
    }

    // ---------------------------------------------------------------- B: delete + ACTIVE group

    @Test
    fun `delete commits the courses and the active group in one write`() {
        val courses = listOf(course(id = 11), course(id = 22))
        val storage = FakeStorage(courses = courses)
        val store = LegacyCourseRecoveryStore(storage)

        val outcome = store.deleteCoursesWithRecoveryGroup(
            courses,
            request(setOf(11L), overrides = mapOf(11L to true), delivered = mapOf(11L to 5_000L),
                locations = mapOf(11L to mapOf(20_000L to "临时教室")))
        )
        assertTrue("expected Applied but got $outcome", outcome is CourseDeletionOutcome.Applied)
        val group = (outcome as CourseDeletionOutcome.Applied).group
        assertEquals(CourseRecoveryState.ACTIVE, group.state)
        assertEquals(listOf(11L), group.members.map { it.id })
        assertEquals(true, group.members[0].reminderOverride)
        assertEquals(5_000L, group.members[0].deliveredAt)
        assertEquals(mapOf(20_000L to "临时教室"), group.members[0].temporaryLocations)
        assertEquals(1, storage.commits)

        assertEquals(listOf(22L), storage.courses.map(Course::id))
        assertEquals(listOf(group), readyGroups(store))
    }

    @Test
    fun `a rebuilt store instance still reads the committed group`() {
        val courses = listOf(course(id = 11))
        val storage = FakeStorage(courses = courses)
        LegacyCourseRecoveryStore(storage).deleteCoursesWithRecoveryGroup(courses, request(setOf(11L)))

        val rebuilt = LegacyCourseRecoveryStore(storage)
        assertEquals(1, readyGroups(rebuilt).size)
        assertEquals(CourseRecoveryState.ACTIVE, readyGroups(rebuilt).single().state)
    }

    @Test
    fun `single parent and batch scopes stay inside the requested range`() {
        val first = course(id = 11, courseId = 10, title = "高等数学")
        val twin = course(id = 22, courseId = 20, title = "高等数学")
        val third = course(id = 33, courseId = 30, title = "大学英语")
        val courses = listOf(first, twin, third)

        val single = FakeStorage(courses = courses)
        val singleGroup = (LegacyCourseRecoveryStore(single).deleteCoursesWithRecoveryGroup(
            courses, request(setOf(11L))
        ) as CourseDeletionOutcome.Applied).group
        assertEquals(listOf(11L), singleGroup.members.map { it.id })

        val parent = FakeStorage(courses = courses)
        val parentGroup = (LegacyCourseRecoveryStore(parent).deleteCoursesWithRecoveryGroup(
            courses, request(setOf(11L, 33L), CourseRecoveryScope.BATCH)
        ) as CourseDeletionOutcome.Applied).group
        assertEquals(listOf(11L, 33L), parentGroup.members.map { it.id })
        assertEquals(listOf(10L, 30L), parentGroup.parentIds)
        assertEquals(listOf(22L), parent.courses.map(Course::id))

        // A parent-scope delete that only covers one of the parent's meetings must be refused.
        val siblingA = course(id = 11, courseId = 10, title = "高等数学")
        val siblingB = course(id = 12, courseId = 10, title = "高等数学")
        val scopeMismatch = FakeStorage(courses = listOf(siblingA, siblingB))
        val rejected = LegacyCourseRecoveryStore(scopeMismatch).deleteCoursesWithRecoveryGroup(
            listOf(siblingA, siblingB), request(setOf(11L), CourseRecoveryScope.PARENT)
        )
        assertTrue("expected Rejected but got $rejected", rejected is CourseDeletionOutcome.Rejected)
        assertEquals(0, scopeMismatch.commits)
    }

    @Test
    fun `a stale course snapshot is rejected without overwriting anything`() {
        val courses = listOf(course(id = 11))
        val storage = FakeStorage(courses = courses)
        val store = LegacyCourseRecoveryStore(storage)

        val stale = store.deleteCoursesWithRecoveryGroup(emptyList(), request(setOf(11L)))
        assertEquals(CoreDataWriteStatus.STALE_COURSES, (stale as CourseDeletionOutcome.Rejected).status)
        assertEquals(0, storage.commits)
        assertEquals(courses, storage.courses)
        assertNull(storage.raw)
    }

    @Test
    fun `the same operation id never creates a second group or deletes twice`() {
        val courses = listOf(course(id = 11), course(id = 22))
        val storage = FakeStorage(courses = courses)
        val store = LegacyCourseRecoveryStore(storage)

        val first = store.deleteCoursesWithRecoveryGroup(courses, request(setOf(11L), operationId = "op-7"))
        assertTrue(first is CourseDeletionOutcome.Applied)

        val second = store.deleteCoursesWithRecoveryGroup(
            storage.courses, request(setOf(22L), operationId = "op-7")
        )
        assertTrue("expected AlreadyApplied but got $second", second is CourseDeletionOutcome.AlreadyApplied)
        assertEquals(1, storage.commits)
        assertEquals(listOf(22L), storage.courses.map(Course::id))
        assertEquals(1, readyGroups(store).size)
    }

    @Test
    fun `an unfinished merge or split journal blocks deletion and restoration`() {
        val courses = listOf(course(id = 11))
        val storage = FakeStorage(courses = courses)
        val store = LegacyCourseRecoveryStore(storage)
        storage.pendingJournal = true

        val rejected = store.deleteCoursesWithRecoveryGroup(courses, request(setOf(11L)))
        assertEquals(CoreDataWriteStatus.CONDITION_NOT_MET, (rejected as CourseDeletionOutcome.Rejected).status)
        assertEquals(0, storage.commits)

        storage.pendingJournal = false
        val applied = store.deleteCoursesWithRecoveryGroup(courses, request(setOf(11L)))
        assertTrue(applied is CourseDeletionOutcome.Applied)
        storage.pendingJournal = true
        val restore = store.restoreCourseRecoveryGroup(
            (applied as CourseDeletionOutcome.Applied).group.groupId, readyGroups(store), deletedAt + 1L
        )
        assertEquals(CoreDataWriteStatus.CONDITION_NOT_MET, (restore as CourseRestoreOutcome.Rejected).status)
    }

    @Test
    fun `a corrupt payload blocks every write and keeps the raw content`() {
        val courses = listOf(course(id = 11))
        val storage = FakeStorage(courses = courses, raw = "{bad payload")
        val store = LegacyCourseRecoveryStore(storage)

        assertTrue(store.deleteCoursesWithRecoveryGroup(courses, request(setOf(11L))) is CourseDeletionOutcome.Rejected)
        assertTrue(store.restoreCourseRecoveryGroup("g", emptyList(), deletedAt) is CourseRestoreOutcome.Rejected)
        assertTrue(store.purgeCourseRecoveryGroups(emptyList(), deletedAt) is CoursePurgeOutcome.Rejected)
        assertEquals(0, storage.commits)
        assertEquals("{bad payload", storage.raw)
        assertEquals(courses, storage.courses)
    }

    // ---------------------------------------------------------------- C: restore + RESTORING

    @Test
    fun `restore commits the courses and the restoring state in one write`() {
        val courses = listOf(course(id = 11), course(id = 22))
        val storage = FakeStorage(courses = courses)
        val store = LegacyCourseRecoveryStore(storage)
        val group = (store.deleteCoursesWithRecoveryGroup(courses, request(setOf(11L)))
            as CourseDeletionOutcome.Applied).group
        assertEquals(listOf(22L), storage.courses.map(Course::id))

        val outcome = store.restoreCourseRecoveryGroup(group.groupId, readyGroups(store), deletedAt + 10L)
        assertTrue("expected CoreCommitted but got $outcome", outcome is CourseRestoreOutcome.CoreCommitted)
        val restoring = (outcome as CourseRestoreOutcome.CoreCommitted).group
        assertEquals(CourseRecoveryState.RESTORING, restoring.state)
        assertNull(restoring.restoredAt)
        assertEquals(listOf(22L, 11L), storage.courses.map(Course::id))
        assertEquals(listOf(restoring), readyGroups(store))
        assertEquals(2, storage.commits)
    }

    @Test
    fun `a rebuilt store resumes a restoring group instead of repeating the core restore`() {
        val courses = listOf(course(id = 11))
        val storage = FakeStorage(courses = courses)
        val group = (LegacyCourseRecoveryStore(storage).deleteCoursesWithRecoveryGroup(
            courses, request(setOf(11L))
        ) as CourseDeletionOutcome.Applied).group
        LegacyCourseRecoveryStore(storage).restoreCourseRecoveryGroup(
            group.groupId, readyGroups(LegacyCourseRecoveryStore(storage)), deletedAt + 10L
        )
        val coursesAfterRestore = storage.courses
        val commitsAfterRestore = storage.commits

        val rebuilt = LegacyCourseRecoveryStore(storage)
        val again = rebuilt.restoreCourseRecoveryGroup(
            group.groupId, readyGroups(rebuilt), deletedAt + 20L
        )
        assertTrue("expected NeedsFollowUp but got $again", again is CourseRestoreOutcome.NeedsFollowUp)
        assertEquals(coursesAfterRestore, storage.courses)
        assertEquals(commitsAfterRestore, storage.commits)
    }

    @Test
    fun `a terminal group answers completed and writes nothing`() {
        val courses = listOf(course(id = 11))
        val storage = FakeStorage(courses = courses)
        val store = LegacyCourseRecoveryStore(storage)
        val active = (store.deleteCoursesWithRecoveryGroup(courses, request(setOf(11L)))
            as CourseDeletionOutcome.Applied).group
        val coursesAfterDelete = storage.courses
        val restoring = CourseRecoveryJournal.transition(active, CourseRecoveryState.RESTORING)!!
        val restored = CourseRecoveryJournal.transition(restoring, CourseRecoveryState.RESTORED, 5_000L)!!
        storage.raw = CourseRecoveryCodec.encode(listOf(restored))
        val commitsBefore = storage.commits

        val outcome = store.restoreCourseRecoveryGroup(restored.groupId, listOf(restored), deletedAt + 30L)
        assertTrue("expected AlreadyRestored but got $outcome", outcome is CourseRestoreOutcome.AlreadyRestored)
        assertEquals(commitsBefore, storage.commits)
        assertEquals(coursesAfterDelete, storage.courses)
    }

    @Test
    fun `occupancy parent field and parent-id ownership conflicts reject the whole group`() {
        val courses = listOf(course(id = 11, courseId = 10, title = "大学物理"))
        val storage = FakeStorage(courses = courses)
        val store = LegacyCourseRecoveryStore(storage)
        val group = (store.deleteCoursesWithRecoveryGroup(courses, request(setOf(11L)))
            as CourseDeletionOutcome.Applied).group
        val groups = readyGroups(store)

        storage.courses = courses + course(id = 11, courseId = 10, title = "大学物理")
        assertTrue(store.restoreCourseRecoveryGroup(group.groupId, groups, deletedAt + 1L) is CourseRestoreOutcome.Rejected)

        storage.courses = listOf(course(id = 12, courseId = 10, title = "别的课"))
        assertTrue(store.restoreCourseRecoveryGroup(group.groupId, groups, deletedAt + 1L) is CourseRestoreOutcome.Rejected)

        storage.courses = listOf(course(id = 10, courseId = 77))
        assertTrue(store.restoreCourseRecoveryGroup(group.groupId, groups, deletedAt + 1L) is CourseRestoreOutcome.Rejected)

        storage.courses = listOf(course(id = 50, courseId = 11))
        assertTrue(store.restoreCourseRecoveryGroup(group.groupId, groups, deletedAt + 1L) is CourseRestoreOutcome.Rejected)

        storage.courses = listOf(course(id = 99, courseId = 99, title = "无关课程"))
        assertTrue(store.restoreCourseRecoveryGroup(group.groupId, groups, deletedAt + 1L) is CourseRestoreOutcome.CoreCommitted)
    }

    @Test
    fun `an expired group is refused while one millisecond before expiry still restores`() {
        val courses = listOf(course(id = 11))
        val storage = FakeStorage(courses = courses)
        val store = LegacyCourseRecoveryStore(storage)
        val group = (store.deleteCoursesWithRecoveryGroup(courses, request(setOf(11L)))
            as CourseDeletionOutcome.Applied).group
        val groups = readyGroups(store)

        val expired = store.restoreCourseRecoveryGroup(group.groupId, groups, expiresAt)
        assertEquals(CoreDataWriteStatus.CONDITION_NOT_MET, (expired as CourseRestoreOutcome.Rejected).status)

        val early = store.restoreCourseRecoveryGroup(group.groupId, groups, expiresAt - 1L)
        assertTrue("expected CoreCommitted but got $early", early is CourseRestoreOutcome.CoreCommitted)
    }

    // ---------------------------------------------------------------- D: purge, races, failures

    @Test
    fun `purge removes only the expired group records`() {
        val courses = listOf(course(id = 11), course(id = 22))
        val storage = FakeStorage(courses = courses)
        val store = LegacyCourseRecoveryStore(storage)
        val expired = (store.deleteCoursesWithRecoveryGroup(courses, request(setOf(11L), operationId = "old"))
            as CourseDeletionOutcome.Applied).group
        val live = (store.deleteCoursesWithRecoveryGroup(
            storage.courses, request(setOf(22L), operationId = "new", now = expiresAt)
        ) as CourseDeletionOutcome.Applied).group
        val coursesBeforePurge = storage.courses

        val outcome = store.purgeCourseRecoveryGroups(readyGroups(store), expiresAt + 1L)
        assertTrue("expected Applied but got $outcome", outcome is CoursePurgeOutcome.Applied)
        assertEquals(setOf(expired.groupId), (outcome as CoursePurgeOutcome.Applied).removedGroupIds)
        assertEquals(listOf(live), readyGroups(store))
        assertEquals(coursesBeforePurge, storage.courses)

        val nothing = store.purgeCourseRecoveryGroups(readyGroups(store), expiresAt + 1L)
        assertEquals(CoursePurgeOutcome.NothingToDo, nothing)
    }

    @Test
    fun `a corrupt payload is never purged away`() {
        val storage = FakeStorage(courses = listOf(course(id = 11)), raw = "{bad payload")
        val outcome = LegacyCourseRecoveryStore(storage).purgeCourseRecoveryGroups(emptyList(), expiresAt + 1L)
        assertTrue(outcome is CoursePurgeOutcome.Rejected)
        assertEquals("{bad payload", storage.raw)
    }

    @Test
    fun `an exception during commit is reported as a failure with no side effects`() {
        val courses = listOf(course(id = 11))
        val storage = FakeStorage(courses = courses)
        val store = LegacyCourseRecoveryStore(storage)
        storage.throwOnCommit = true

        val outcome = store.deleteCoursesWithRecoveryGroup(courses, request(setOf(11L)))
        assertTrue("expected WriteFailed but got $outcome", outcome is CourseDeletionOutcome.WriteFailed)
        assertEquals(courses, storage.courses)
        assertNull(storage.raw)
    }

    @Test
    fun `a failed commit is confirmed as not written and leaves the state alone`() {
        val courses = listOf(course(id = 11))
        val storage = FakeStorage(courses = courses)
        val store = LegacyCourseRecoveryStore(storage)
        storage.failCommits = true

        val outcome = store.deleteCoursesWithRecoveryGroup(courses, request(setOf(11L)))
        assertTrue("expected WriteFailed but got $outcome", outcome is CourseDeletionOutcome.WriteFailed)
        assertEquals(courses, storage.courses)
        assertNull(storage.raw)
    }

    @Test
    fun `a commit that lands anyway is reported as applied`() {
        val courses = listOf(course(id = 11))
        val storage = FakeStorage(courses = courses)
        storage.failCommits = true
        storage.landWhileFailing = true

        val outcome = LegacyCourseRecoveryStore(storage).deleteCoursesWithRecoveryGroup(
            courses, request(setOf(11L))
        )
        assertTrue("expected Applied but got $outcome", outcome is CourseDeletionOutcome.Applied)
        assertTrue(storage.courses.isEmpty())
        assertEquals(1, readyGroups(LegacyCourseRecoveryStore(storage)).size)
    }

    @Test
    fun `an unclassifiable commit result is reported as uncertain`() {
        val courses = listOf(course(id = 11))
        val storage = FakeStorage(courses = courses)
        val store = LegacyCourseRecoveryStore(storage)
        storage.failCommits = true
        storage.thirdStateOnFailure = true

        val outcome = store.deleteCoursesWithRecoveryGroup(courses, request(setOf(11L)))
        assertTrue("expected WriteUncertain but got $outcome", outcome is CourseDeletionOutcome.WriteUncertain)
        assertNotEquals(courses, storage.courses)
    }

    @Test
    fun `a competing write between read and commit blocks the recovery write`() {
        val courses = listOf(course(id = 11))
        val storage = FakeStorage(courses = courses)
        val store = LegacyCourseRecoveryStore(storage)
        val groupsBefore = readyGroups(store)

        // Another writer edits the course list after the caller captured its snapshot.
        val otherCourse = course(id = 99, courseId = 99, title = "并发写入")
        storage.courses = courses + otherCourse

        val restore = store.restoreCourseRecoveryGroup("missing", groupsBefore, deletedAt + 1L)
        assertTrue(restore is CourseRestoreOutcome.Rejected)
        val delete = store.deleteCoursesWithRecoveryGroup(courses, request(setOf(11L)))
        assertEquals(CoreDataWriteStatus.STALE_COURSES, (delete as CourseDeletionOutcome.Rejected).status)

        // The competing write survives untouched.
        assertEquals(courses + otherCourse, storage.courses)
        assertEquals(0, storage.commits)
    }

    @Test
    fun `a stale group snapshot is rejected while the courses stay untouched`() {
        val courses = listOf(course(id = 11))
        val storage = FakeStorage(courses = courses)
        val store = LegacyCourseRecoveryStore(storage)
        val group = (store.deleteCoursesWithRecoveryGroup(courses, request(setOf(11L)))
            as CourseDeletionOutcome.Applied).group

        val stalePurge = store.purgeCourseRecoveryGroups(emptyList(), expiresAt + 1L)
        assertTrue(stalePurge is CoursePurgeOutcome.Rejected)
        val staleRestore = store.restoreCourseRecoveryGroup(group.groupId, emptyList(), deletedAt + 1L)
        assertTrue(staleRestore is CourseRestoreOutcome.Rejected)
        assertEquals(listOf(group), readyGroups(store))
    }
}
