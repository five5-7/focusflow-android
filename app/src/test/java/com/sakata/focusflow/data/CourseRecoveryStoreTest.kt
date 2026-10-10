package com.sakata.focusflow.data

import com.sakata.focusflow.CampusZone
import com.sakata.focusflow.Course
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Test

/**
 * Stage 7.5 Legacy integration batch: the recovery store driven by an in-memory persistence that
 * separates the in-memory map from the "on disk" state, exactly like SharedPreferences does.
 * A commit that reports failure may already have updated memory, so it must never be reported as
 * success and must poison later state-based short circuits until a confirmed commit happens.
 */
class CourseRecoveryStoreTest {

    private val deletedAt = 1_000L
    private val expiresAt = deletedAt + CourseRecoveryJournal.RETENTION_MS

    @After
    fun clearGuard() {
        CourseRecoveryWriteGuard.reset()
    }

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

    /**
     * In-memory persistence with PrototypeStore-equivalent guard semantics. Reads always see the
     * in-memory map; `persisted*` is what a process restart would find.
     */
    private class FakeStorage(
        courses: List<Course> = emptyList(),
        raw: String? = null
    ) : CourseRecoveryStorage {
        var memoryCourses: List<Course> = courses
        var memoryRaw: String? = raw
        var persistedCourses: List<Course> = courses
        var persistedRaw: String? = raw

        enum class CommitMode { CONFIRM, GUARD_REJECT, FAIL_BEFORE_MEMORY, FAIL_AFTER_MEMORY, THROW_AFTER_MEMORY, THROW_BEFORE_MEMORY }

        var mode = CommitMode.CONFIRM
        var readOnly = false
        var pendingJournal = false
        var commits = 0
        private val lock = Any()

        override fun isStorageReadOnly(): Boolean = readOnly

        override fun <T> withCourseWriteLock(block: () -> T): T = synchronized(lock) { block() }

        override fun readCourses(): List<Course> = memoryCourses

        override fun loadCourseRecoveryGroups(): CourseRecoveryGroupsRead {
            val stored = memoryRaw ?: return CourseRecoveryGroupsRead.Ready(emptyList())
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
        ): CourseRecoveryCommit {
            commits++
            if (readOnly || memoryCourses != expectedCourses) {
                return CourseRecoveryCommit.Rejected("guard refused")
            }
            val current = (loadCourseRecoveryGroups() as? CourseRecoveryGroupsRead.Ready)?.groups
                ?: return CourseRecoveryCommit.Rejected("payload unreadable")
            if (current != expectedGroups) return CourseRecoveryCommit.Rejected("payload changed")
            val encoded = CourseRecoveryCodec.encode(groups)
            return when (mode) {
                CommitMode.CONFIRM -> {
                    memoryCourses = courses; memoryRaw = encoded
                    persistedCourses = courses; persistedRaw = encoded
                    CourseRecoveryCommit.Committed
                }
                CommitMode.GUARD_REJECT -> CourseRecoveryCommit.Rejected("guard refused")
                CommitMode.FAIL_BEFORE_MEMORY -> CourseRecoveryCommit.Failed("commit returned false")
                CommitMode.FAIL_AFTER_MEMORY -> {
                    memoryCourses = courses; memoryRaw = encoded
                    CourseRecoveryCommit.Failed("commit returned false")
                }
                CommitMode.THROW_AFTER_MEMORY -> {
                    memoryCourses = courses; memoryRaw = encoded
                    throw IllegalStateException("commit exploded after the memory update")
                }
                CommitMode.THROW_BEFORE_MEMORY -> throw IllegalStateException("commit exploded")
            }
        }

        override fun commitCourseRecoveryGroups(
            groups: List<CourseRecoveryGroup>,
            expectedGroups: List<CourseRecoveryGroup>
        ): CourseRecoveryCommit {
            commits++
            if (readOnly) return CourseRecoveryCommit.Rejected("guard refused")
            val current = (loadCourseRecoveryGroups() as? CourseRecoveryGroupsRead.Ready)?.groups
                ?: return CourseRecoveryCommit.Rejected("payload unreadable")
            if (current != expectedGroups) return CourseRecoveryCommit.Rejected("payload changed")
            val encoded = CourseRecoveryCodec.encode(groups)
            return when (mode) {
                CommitMode.CONFIRM -> {
                    memoryRaw = encoded; persistedRaw = encoded
                    CourseRecoveryCommit.Committed
                }
                CommitMode.GUARD_REJECT -> CourseRecoveryCommit.Rejected("guard refused")
                CommitMode.FAIL_BEFORE_MEMORY -> CourseRecoveryCommit.Failed("commit returned false")
                CommitMode.FAIL_AFTER_MEMORY -> {
                    memoryRaw = encoded
                    CourseRecoveryCommit.Failed("commit returned false")
                }
                CommitMode.THROW_AFTER_MEMORY -> {
                    memoryRaw = encoded
                    throw IllegalStateException("commit exploded after the memory update")
                }
                CommitMode.THROW_BEFORE_MEMORY -> throw IllegalStateException("commit exploded")
            }
        }
    }

    private fun storageOf(vararg courses: Course) = FakeStorage(courses.toList())

    // ---------------------------------------------------------------- A: storage and reads

    @Test
    fun `a missing key reads as an empty collection while corruption is reported with its raw payload`() {
        val storage = storageOf(course(id = 11))
        val store = LegacyCourseRecoveryStore(storage)
        assertEquals(CourseRecoveryGroupsRead.Ready(emptyList()), store.read())

        storage.memoryRaw = "{ \"version\": 1, \"groups\": [ {"
        val corrupt = store.read()
        assertTrue(corrupt is CourseRecoveryGroupsRead.Invalid)
        assertEquals(storage.memoryRaw, (corrupt as CourseRecoveryGroupsRead.Invalid).raw)
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
    fun `delete commits the courses and the active group in one confirmed write`() {
        val courses = listOf(course(id = 11), course(id = 22))
        val storage = FakeStorage(courses)
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

        assertEquals(listOf(22L), storage.memoryCourses.map(Course::id))
        assertEquals(listOf(22L), storage.persistedCourses.map(Course::id))
        assertEquals(listOf(group), readyGroups(store))
    }

    @Test
    fun `a rebuilt store instance still reads the committed group`() {
        val courses = listOf(course(id = 11))
        val storage = FakeStorage(courses)
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

        val single = FakeStorage(courses)
        val singleGroup = (LegacyCourseRecoveryStore(single).deleteCoursesWithRecoveryGroup(
            courses, request(setOf(11L))
        ) as CourseDeletionOutcome.Applied).group
        assertEquals(listOf(11L), singleGroup.members.map { it.id })

        val batch = FakeStorage(courses)
        val batchGroup = (LegacyCourseRecoveryStore(batch).deleteCoursesWithRecoveryGroup(
            courses, request(setOf(11L, 33L), CourseRecoveryScope.BATCH)
        ) as CourseDeletionOutcome.Applied).group
        assertEquals(listOf(11L, 33L), batchGroup.members.map { it.id })
        assertEquals(listOf(10L, 30L), batchGroup.parentIds)
        assertEquals(listOf(22L), batch.memoryCourses.map(Course::id))

        val siblingA = course(id = 11, courseId = 10, title = "高等数学")
        val siblingB = course(id = 12, courseId = 10, title = "高等数学")
        val scopeMismatch = FakeStorage(listOf(siblingA, siblingB))
        val rejected = LegacyCourseRecoveryStore(scopeMismatch).deleteCoursesWithRecoveryGroup(
            listOf(siblingA, siblingB), request(setOf(11L), CourseRecoveryScope.PARENT)
        )
        assertTrue("expected Rejected but got $rejected", rejected is CourseDeletionOutcome.Rejected)
        assertEquals(0, scopeMismatch.commits)
    }

    @Test
    fun `a stale course snapshot is rejected before any write`() {
        val courses = listOf(course(id = 11))
        val storage = FakeStorage(courses)
        val store = LegacyCourseRecoveryStore(storage)

        val stale = store.deleteCoursesWithRecoveryGroup(emptyList(), request(setOf(11L)))
        assertEquals(CoreDataWriteStatus.STALE_COURSES, (stale as CourseDeletionOutcome.Rejected).status)
        assertEquals(0, storage.commits)
        assertEquals(courses, storage.memoryCourses)
        assertNull(storage.memoryRaw)
    }

    @Test
    fun `the same operation id never creates a second group or deletes twice`() {
        val courses = listOf(course(id = 11), course(id = 22))
        val storage = FakeStorage(courses)
        val store = LegacyCourseRecoveryStore(storage)

        val first = store.deleteCoursesWithRecoveryGroup(courses, request(setOf(11L), operationId = "op-7"))
        assertTrue(first is CourseDeletionOutcome.Applied)

        val second = store.deleteCoursesWithRecoveryGroup(
            storage.memoryCourses, request(setOf(22L), operationId = "op-7")
        )
        assertTrue("expected AlreadyApplied but got $second", second is CourseDeletionOutcome.AlreadyApplied)
        assertEquals(1, storage.commits)
        assertEquals(listOf(22L), storage.memoryCourses.map(Course::id))
        assertEquals(1, readyGroups(store).size)
    }

    @Test
    fun `an unfinished merge or split journal blocks deletion and restoration`() {
        val courses = listOf(course(id = 11))
        val storage = FakeStorage(courses)
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
        val storage = FakeStorage(courses, raw = "{bad payload")
        val store = LegacyCourseRecoveryStore(storage)

        assertTrue(store.deleteCoursesWithRecoveryGroup(courses, request(setOf(11L))) is CourseDeletionOutcome.Rejected)
        assertTrue(store.restoreCourseRecoveryGroup("g", emptyList(), deletedAt) is CourseRestoreOutcome.Rejected)
        assertTrue(store.purgeCourseRecoveryGroups(emptyList(), deletedAt) is CoursePurgeOutcome.Rejected)
        assertEquals(0, storage.commits)
        assertEquals("{bad payload", storage.memoryRaw)
        assertEquals(courses, storage.memoryCourses)
    }

    // ---------------------------------------------------------------- C: restore + RESTORING

    @Test
    fun `restore commits the courses and the restoring state in one confirmed write`() {
        val courses = listOf(course(id = 11), course(id = 22))
        val storage = FakeStorage(courses)
        val store = LegacyCourseRecoveryStore(storage)
        val group = (store.deleteCoursesWithRecoveryGroup(courses, request(setOf(11L)))
            as CourseDeletionOutcome.Applied).group
        assertEquals(listOf(22L), storage.memoryCourses.map(Course::id))

        val outcome = store.restoreCourseRecoveryGroup(group.groupId, readyGroups(store), deletedAt + 10L)
        assertTrue("expected CoreCommitted but got $outcome", outcome is CourseRestoreOutcome.CoreCommitted)
        val restoring = (outcome as CourseRestoreOutcome.CoreCommitted).group
        assertEquals(CourseRecoveryState.RESTORING, restoring.state)
        assertNull(restoring.restoredAt)
        assertEquals(listOf(22L, 11L), storage.memoryCourses.map(Course::id))
        assertEquals(listOf(22L, 11L), storage.persistedCourses.map(Course::id))
        assertEquals(listOf(restoring), readyGroups(store))
        assertEquals(2, storage.commits)
    }

    @Test
    fun `a rebuilt store resumes a restoring group instead of repeating the core restore`() {
        val courses = listOf(course(id = 11))
        val storage = FakeStorage(courses)
        val first = LegacyCourseRecoveryStore(storage)
        val group = (first.deleteCoursesWithRecoveryGroup(courses, request(setOf(11L)))
            as CourseDeletionOutcome.Applied).group
        first.restoreCourseRecoveryGroup(group.groupId, readyGroups(first), deletedAt + 10L)
        val coursesAfterRestore = storage.memoryCourses
        val commitsAfterRestore = storage.commits

        val rebuilt = LegacyCourseRecoveryStore(storage)
        val again = rebuilt.restoreCourseRecoveryGroup(group.groupId, readyGroups(rebuilt), deletedAt + 20L)
        assertTrue("expected NeedsFollowUp but got $again", again is CourseRestoreOutcome.NeedsFollowUp)
        assertEquals(coursesAfterRestore, storage.memoryCourses)
        assertEquals(commitsAfterRestore, storage.commits)
    }

    @Test
    fun `a terminal group answers completed and writes nothing`() {
        val courses = listOf(course(id = 11))
        val storage = FakeStorage(courses)
        val store = LegacyCourseRecoveryStore(storage)
        val active = (store.deleteCoursesWithRecoveryGroup(courses, request(setOf(11L)))
            as CourseDeletionOutcome.Applied).group
        val coursesAfterDelete = storage.memoryCourses
        val restoring = CourseRecoveryJournal.transition(active, CourseRecoveryState.RESTORING)!!
        val restored = CourseRecoveryJournal.transition(restoring, CourseRecoveryState.RESTORED, 5_000L)!!
        storage.memoryRaw = CourseRecoveryCodec.encode(listOf(restored))
        val commitsBefore = storage.commits

        val outcome = store.restoreCourseRecoveryGroup(restored.groupId, listOf(restored), deletedAt + 30L)
        assertTrue("expected AlreadyRestored but got $outcome", outcome is CourseRestoreOutcome.AlreadyRestored)
        assertEquals(commitsBefore, storage.commits)
        assertEquals(coursesAfterDelete, storage.memoryCourses)
    }

    @Test
    fun `occupancy parent field and parent-id ownership conflicts reject the whole group`() {
        val courses = listOf(course(id = 11, courseId = 10, title = "大学物理"))
        val storage = FakeStorage(courses)
        val store = LegacyCourseRecoveryStore(storage)
        val group = (store.deleteCoursesWithRecoveryGroup(courses, request(setOf(11L)))
            as CourseDeletionOutcome.Applied).group
        val groups = readyGroups(store)

        storage.memoryCourses = courses + course(id = 11, courseId = 10, title = "大学物理")
        assertTrue(store.restoreCourseRecoveryGroup(group.groupId, groups, deletedAt + 1L) is CourseRestoreOutcome.Rejected)

        storage.memoryCourses = listOf(course(id = 12, courseId = 10, title = "别的课"))
        assertTrue(store.restoreCourseRecoveryGroup(group.groupId, groups, deletedAt + 1L) is CourseRestoreOutcome.Rejected)

        storage.memoryCourses = listOf(course(id = 10, courseId = 77))
        assertTrue(store.restoreCourseRecoveryGroup(group.groupId, groups, deletedAt + 1L) is CourseRestoreOutcome.Rejected)

        storage.memoryCourses = listOf(course(id = 50, courseId = 11))
        assertTrue(store.restoreCourseRecoveryGroup(group.groupId, groups, deletedAt + 1L) is CourseRestoreOutcome.Rejected)

        storage.memoryCourses = listOf(course(id = 99, courseId = 99, title = "无关课程"))
        assertTrue(store.restoreCourseRecoveryGroup(group.groupId, groups, deletedAt + 1L) is CourseRestoreOutcome.CoreCommitted)
    }

    @Test
    fun `an expired group is refused while one millisecond before expiry still restores`() {
        val courses = listOf(course(id = 11))
        val storage = FakeStorage(courses)
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
        val storage = FakeStorage(courses)
        val store = LegacyCourseRecoveryStore(storage)
        val expired = (store.deleteCoursesWithRecoveryGroup(courses, request(setOf(11L), operationId = "old"))
            as CourseDeletionOutcome.Applied).group
        val live = (store.deleteCoursesWithRecoveryGroup(
            storage.memoryCourses, request(setOf(22L), operationId = "new", now = expiresAt)
        ) as CourseDeletionOutcome.Applied).group
        val coursesBeforePurge = storage.memoryCourses

        val outcome = store.purgeCourseRecoveryGroups(readyGroups(store), expiresAt + 1L)
        assertTrue("expected Applied but got $outcome", outcome is CoursePurgeOutcome.Applied)
        assertEquals(setOf(expired.groupId), (outcome as CoursePurgeOutcome.Applied).removedGroupIds)
        assertEquals(listOf(live), readyGroups(store))
        assertEquals(coursesBeforePurge, storage.memoryCourses)

        val nothing = store.purgeCourseRecoveryGroups(readyGroups(store), expiresAt + 1L)
        assertEquals(CoursePurgeOutcome.NothingToDo, nothing)
    }

    @Test
    fun `a corrupt payload is never purged away`() {
        val storage = FakeStorage(listOf(course(id = 11)), raw = "{bad payload")
        val outcome = LegacyCourseRecoveryStore(storage).purgeCourseRecoveryGroups(emptyList(), expiresAt + 1L)
        assertTrue(outcome is CoursePurgeOutcome.Rejected)
        assertEquals("{bad payload", storage.memoryRaw)
    }

    @Test
    fun `a refused commit is reported as rejected with no write`() {
        val courses = listOf(course(id = 11))
        val storage = FakeStorage(courses)
        val store = LegacyCourseRecoveryStore(storage)
        storage.mode = FakeStorage.CommitMode.GUARD_REJECT

        val outcome = store.deleteCoursesWithRecoveryGroup(courses, request(setOf(11L)))
        assertTrue("expected Rejected but got $outcome", outcome is CourseDeletionOutcome.Rejected)
        assertEquals(courses, storage.memoryCourses)
        assertNull(storage.memoryRaw)
        assertNull(CourseRecoveryWriteGuard.uncertainReason())
    }

    @Test
    fun `a failed delete commit with the memory already updated is uncertain and never success`() {
        val courses = listOf(course(id = 11))
        val storage = FakeStorage(courses)
        val store = LegacyCourseRecoveryStore(storage)
        storage.mode = FakeStorage.CommitMode.FAIL_AFTER_MEMORY

        val outcome = store.deleteCoursesWithRecoveryGroup(courses, request(setOf(11L)))
        assertTrue("expected WriteUncertain but got $outcome", outcome is CourseDeletionOutcome.WriteUncertain)
        // Memory moved, the disk did not: that is exactly why a read-back proves nothing.
        assertEquals(emptyList<Course>(), storage.memoryCourses)
        assertEquals(courses, storage.persistedCourses)
        assertTrue(storage.memoryRaw != null)
        assertNull(storage.persistedRaw)
        assertTrue(CourseRecoveryWriteGuard.uncertainReason() != null)
    }

    @Test
    fun `a failed restore commit with the memory already updated is uncertain`() {
        val courses = listOf(course(id = 11))
        val storage = FakeStorage(courses)
        val store = LegacyCourseRecoveryStore(storage)
        val group = (store.deleteCoursesWithRecoveryGroup(courses, request(setOf(11L)))
            as CourseDeletionOutcome.Applied).group
        val groups = readyGroups(store)
        storage.mode = FakeStorage.CommitMode.FAIL_AFTER_MEMORY

        val outcome = store.restoreCourseRecoveryGroup(group.groupId, groups, deletedAt + 10L)
        assertTrue("expected WriteUncertain but got $outcome", outcome is CourseRestoreOutcome.WriteUncertain)
        assertEquals(listOf(11L), storage.memoryCourses.map(Course::id))
        assertEquals(emptyList<Course>(), storage.persistedCourses)
        assertTrue(CourseRecoveryWriteGuard.uncertainReason() != null)
    }

    @Test
    fun `a failed purge commit with the memory already updated is uncertain`() {
        val courses = listOf(course(id = 11))
        val storage = FakeStorage(courses)
        val store = LegacyCourseRecoveryStore(storage)
        store.deleteCoursesWithRecoveryGroup(courses, request(setOf(11L)))
        val groups = readyGroups(store)
        storage.mode = FakeStorage.CommitMode.FAIL_AFTER_MEMORY

        val outcome = store.purgeCourseRecoveryGroups(groups, expiresAt + 1L)
        assertTrue("expected WriteUncertain but got $outcome", outcome is CoursePurgeOutcome.WriteUncertain)
        assertEquals(emptyList<CourseRecoveryGroup>(), readyGroups(store))
        assertTrue(CourseRecoveryWriteGuard.uncertainReason() != null)
    }

    @Test
    fun `an exception after the memory update is uncertain too`() {
        val courses = listOf(course(id = 11))
        val storage = FakeStorage(courses)
        val store = LegacyCourseRecoveryStore(storage)
        storage.mode = FakeStorage.CommitMode.THROW_AFTER_MEMORY

        val outcome = store.deleteCoursesWithRecoveryGroup(courses, request(setOf(11L)))
        assertTrue("expected WriteUncertain but got $outcome", outcome is CourseDeletionOutcome.WriteUncertain)
        assertEquals(emptyList<Course>(), storage.memoryCourses)
        assertEquals(courses, storage.persistedCourses)
        assertTrue(CourseRecoveryWriteGuard.uncertainReason() != null)
    }

    @Test
    fun `an exception before the memory update is uncertain as well, never a claim of success`() {
        val courses = listOf(course(id = 11))
        val storage = FakeStorage(courses)
        val store = LegacyCourseRecoveryStore(storage)
        storage.mode = FakeStorage.CommitMode.THROW_BEFORE_MEMORY

        val outcome = store.deleteCoursesWithRecoveryGroup(courses, request(setOf(11L)))
        assertTrue("expected WriteUncertain but got $outcome", outcome is CourseDeletionOutcome.WriteUncertain)
        assertEquals(courses, storage.memoryCourses)
        assertEquals(courses, storage.persistedCourses)
    }

    @Test
    fun `after an uncertain commit the in-memory group cannot unlock any short circuit`() {
        val courses = listOf(course(id = 11), course(id = 22))
        val storage = FakeStorage(courses)
        val store = LegacyCourseRecoveryStore(storage)
        storage.mode = FakeStorage.CommitMode.FAIL_AFTER_MEMORY

        // The delete "succeeded" in memory only, so its group is visible to later calls.
        val first = store.deleteCoursesWithRecoveryGroup(courses, request(setOf(11L), operationId = "op-1"))
        assertTrue(first is CourseDeletionOutcome.WriteUncertain)
        assertEquals(1, readyGroups(store).size)

        // Repeating the same operation must not answer AlreadyApplied.
        val repeated = store.deleteCoursesWithRecoveryGroup(
            storage.memoryCourses, request(setOf(11L), operationId = "op-1")
        )
        assertTrue("expected WriteUncertain but got $repeated", repeated is CourseDeletionOutcome.WriteUncertain)

        // A restore of the in-memory ACTIVE group may proceed to the core write, but a group already
        // advanced in memory must not answer a resume state.
        storage.mode = FakeStorage.CommitMode.FAIL_AFTER_MEMORY
        val restoring = CourseRecoveryJournal.transition(
            readyGroups(store).single(), CourseRecoveryState.RESTORING
        )!!
        storage.memoryRaw = CourseRecoveryCodec.encode(listOf(restoring))
        val resume = store.restoreCourseRecoveryGroup(
            restoring.groupId, listOf(restoring), deletedAt + 10L
        )
        assertTrue("expected WriteUncertain but got $resume", resume is CourseRestoreOutcome.WriteUncertain)

        // And an empty purge plan must not report NothingToDo from unverified state.
        val purge = store.purgeCourseRecoveryGroups(listOf(restoring), expiresAt + 1L)
        assertTrue("expected WriteUncertain but got $purge", purge is CoursePurgeOutcome.WriteUncertain)
    }

    @Test
    fun `only a confirmed commit clears the uncertainty guard`() {
        val courses = listOf(course(id = 11), course(id = 22))
        val storage = FakeStorage(courses)
        val store = LegacyCourseRecoveryStore(storage)
        storage.mode = FakeStorage.CommitMode.FAIL_AFTER_MEMORY
        store.deleteCoursesWithRecoveryGroup(courses, request(setOf(11L), operationId = "op-1"))
        assertTrue(CourseRecoveryWriteGuard.uncertainReason() != null)

        // A plain read or a rebuilt store object must not clear it.
        LegacyCourseRecoveryStore(storage).read()
        assertTrue(CourseRecoveryWriteGuard.uncertainReason() != null)

        // A later confirmed commit persists the whole in-memory map, so it resolves the uncertainty.
        storage.mode = FakeStorage.CommitMode.CONFIRM
        val confirmed = store.deleteCoursesWithRecoveryGroup(
            storage.memoryCourses, request(setOf(22L), operationId = "op-2")
        )
        assertTrue("expected Applied but got $confirmed", confirmed is CourseDeletionOutcome.Applied)
        assertNull(CourseRecoveryWriteGuard.uncertainReason())
        assertEquals(storage.memoryCourses, storage.persistedCourses)
        assertEquals(storage.memoryRaw, storage.persistedRaw)

        // With the uncertainty resolved the identity short circuit is allowed again.
        val repeated = store.deleteCoursesWithRecoveryGroup(
            storage.memoryCourses, request(setOf(11L), operationId = "op-1")
        )
        assertTrue("expected AlreadyApplied but got $repeated", repeated is CourseDeletionOutcome.AlreadyApplied)
    }

    @Test
    fun `a competing write between read and commit blocks the recovery write`() {
        val courses = listOf(course(id = 11))
        val storage = FakeStorage(courses)
        val store = LegacyCourseRecoveryStore(storage)
        val groupsBefore = readyGroups(store)

        val otherCourse = course(id = 99, courseId = 99, title = "并发写入")
        storage.memoryCourses = courses + otherCourse
        storage.persistedCourses = courses + otherCourse

        val restore = store.restoreCourseRecoveryGroup("missing", groupsBefore, deletedAt + 1L)
        assertTrue(restore is CourseRestoreOutcome.Rejected)
        val delete = store.deleteCoursesWithRecoveryGroup(courses, request(setOf(11L)))
        assertEquals(CoreDataWriteStatus.STALE_COURSES, (delete as CourseDeletionOutcome.Rejected).status)

        assertEquals(courses + otherCourse, storage.persistedCourses)
        assertEquals(0, storage.commits)
    }

    @Test
    fun `a stale group snapshot is rejected while the courses stay untouched`() {
        val courses = listOf(course(id = 11))
        val storage = FakeStorage(courses)
        val store = LegacyCourseRecoveryStore(storage)
        val group = (store.deleteCoursesWithRecoveryGroup(courses, request(setOf(11L)))
            as CourseDeletionOutcome.Applied).group

        val stalePurge = store.purgeCourseRecoveryGroups(emptyList(), expiresAt + 1L)
        assertTrue(stalePurge is CoursePurgeOutcome.Rejected)
        val staleRestore = store.restoreCourseRecoveryGroup(group.groupId, emptyList(), deletedAt + 1L)
        assertTrue(staleRestore is CourseRestoreOutcome.Rejected)
        assertEquals(listOf(group), readyGroups(store))
    }

    @Test
    fun `an unknown group id is rejected without touching storage`() {
        val storage = FakeStorage(listOf(course(id = 11)))
        val store = LegacyCourseRecoveryStore(storage)
        val outcome = store.restoreCourseRecoveryGroup("missing", emptyList(), deletedAt + 1L)
        assertEquals(CoreDataWriteStatus.INVALID_INPUT, (outcome as CourseRestoreOutcome.Rejected).status)
        assertEquals(0, storage.commits)
    }
}
