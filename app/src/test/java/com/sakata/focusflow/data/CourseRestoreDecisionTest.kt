package com.sakata.focusflow.data

import com.sakata.focusflow.CampusZone
import com.sakata.focusflow.Course
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Batch-1 regression tests for the pure restore eligibility, lifecycle and purge decisions.
 * The live course list and `now` are always parameters; nothing here reads storage.
 */
class CourseRestoreDecisionTest {

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
        enabled: Boolean = true,
        from: Long? = null,
        until: Long? = null
    ) = Course(
        title = title, weekday = weekday, startPeriod = startPeriod, endPeriod = endPeriod,
        building = building, zone = zone, needsConfirmation = needsConfirmation, enabled = enabled,
        effectiveFromEpochDay = from, effectiveUntilEpochDay = until, id = id, courseId = courseId
    )

    private fun capture(
        before: List<Course>,
        ids: Set<Long>,
        scope: CourseRecoveryScope = CourseRecoveryScope.MEETING,
        now: Long = deletedAt,
        existing: List<CourseRecoveryGroup> = emptyList()
    ): CourseRecoveryGroup {
        val result = CourseRecoveryJournal.captureDeletion(
            before = before,
            after = before.filterNot { it.id in ids },
            request = CourseRecoveryRequest(ids, scope, "op-1", now),
            existing = existing
        )
        assertTrue("expected a captured group but got $result", result is CourseRecoveryCapture.Captured)
        return (result as CourseRecoveryCapture.Captured).group
    }

    private fun groupCourse(group: CourseRecoveryGroup, index: Int = 0): Course =
        requireNotNull(CourseSnapshotCodec.decode(group.members[index].courseJson))

    private fun decision(
        group: CourseRecoveryGroup,
        currentCourses: List<Course> = emptyList(),
        pendingJournal: Boolean = false,
        now: Long = deletedAt + 1L
    ) = CourseRecoveryJournal.restoreDecision(group, currentCourses, pendingJournal, now)

    // ------------------------------------------------------------------ time boundary

    @Test
    fun `active group restores one millisecond before expiry and is refused at expiry`() {
        val original = listOf(course(id = 11), course(id = 22, courseId = 11))
        val group = capture(original, setOf(11L, 22L), CourseRecoveryScope.PARENT)

        val early = decision(group, now = expiresAt - 1L)
        assertTrue("expected Eligible but got $early", early is CourseRestoreDecision.Eligible)
        assertEquals(original, (early as CourseRestoreDecision.Eligible).coursesToAdd)

        assertEquals(CourseRestoreDecision.Expired, decision(group, now = expiresAt))
        assertEquals(CourseRestoreDecision.Expired, decision(group, now = expiresAt + 1L))
    }

    // ------------------------------------------------------------------ occupancy vs interruption

    @Test
    fun `an existing member id is an occupancy conflict even when the content is identical`() {
        val original = course(id = 11)
        val group = capture(listOf(original), setOf(11L))

        assertEquals(CourseRestoreDecision.Occupied(11L), decision(group, currentCourses = listOf(original)))
        assertEquals(
            CourseRestoreDecision.Occupied(11L),
            decision(group, currentCourses = listOf(original.copy(building = "西九")))
        )
        // The snapshot itself is never accepted as proof that the user already restored the meeting.
        assertEquals(
            CourseRestoreDecision.Occupied(11L),
            decision(group, currentCourses = listOf(groupCourse(group)))
        )
    }

    // ------------------------------------------------------------------ unrelated changes vs conflicts

    @Test
    fun `unrelated course changes are allowed while an incompatible parent title rejects`() {
        val anchor = course(id = 11, courseId = 10, title = "大学物理")
        val group = capture(listOf(anchor), setOf(11L))

        val unrelated = listOf(course(id = 99, courseId = 99, title = "另一门课"))
        assertTrue(decision(group, currentCourses = unrelated) is CourseRestoreDecision.Eligible)

        val editedUnrelated = listOf(course(id = 99, courseId = 99, title = "改过名的另一门课", building = "西二"))
        assertTrue(decision(group, currentCourses = editedUnrelated) is CourseRestoreDecision.Eligible)

        val sameParentSameTitle = course(id = 12, courseId = 10, title = "大学物理")
        val compatible = decision(group, currentCourses = listOf(sameParentSameTitle))
        assertTrue("expected Eligible but got $compatible", compatible is CourseRestoreDecision.Eligible)
        assertEquals(
            "a compatible new meeting of the same parent must not join the restore set",
            listOf(anchor),
            (compatible as CourseRestoreDecision.Eligible).coursesToAdd
        )

        val conflicting = decision(group, currentCourses = listOf(course(id = 12, courseId = 10, title = "别的课")))
        assertEquals(CourseRestoreDecision.ParentTitleConflict(10L, "别的课"), conflicting)

        val otherParent = decision(group, currentCourses = listOf(course(id = 12, courseId = 77, title = "别的课")))
        assertTrue(otherParent is CourseRestoreDecision.Eligible)
    }

    @Test
    fun `a pending merge or split journal blocks a new restore`() {
        val group = capture(listOf(course(id = 11)), setOf(11L))
        assertEquals(
            CourseRestoreDecision.PendingCourseEditJournal,
            decision(group, pendingJournal = true, now = expiresAt - 1L)
        )
    }

    @Test
    fun `restore accepts unconfirmed disabled or already past courses because scheduling decides later`() {
        val pending = course(id = 11, needsConfirmation = true)
        val disabled = course(id = 22, enabled = false)
        val past = course(id = 33, from = 100L, until = 200L)
        val group = capture(listOf(pending, disabled, past), setOf(11L, 22L, 33L), CourseRecoveryScope.BATCH)

        val eligible = decision(group, now = expiresAt - 1L)
        assertTrue("expected Eligible but got $eligible", eligible is CourseRestoreDecision.Eligible)
        assertEquals(listOf(pending, disabled, past), (eligible as CourseRestoreDecision.Eligible).coursesToAdd)
    }

    // ------------------------------------------------------------------ terminal states

    @Test
    fun `restored groups stay terminal after their courses are edited`() {
        val original = course(id = 11)
        val active = capture(listOf(original), setOf(11L))
        val restoring = CourseRecoveryJournal.transition(active, CourseRecoveryState.RESTORING)!!
        val restored = CourseRecoveryJournal.transition(restoring, CourseRecoveryState.RESTORED, 5_000L)!!

        assertEquals(CourseRestoreDecision.AlreadyRestored, decision(restored, currentCourses = listOf(original)))
        assertEquals(
            CourseRestoreDecision.AlreadyRestored,
            decision(restored, currentCourses = listOf(original.copy(title = "改过名", enabled = false)))
        )
        // A terminal group is a finished operation, never an occupancy conflict.
        assertEquals(CourseRestoreDecision.AlreadyRestored, decision(restored, currentCourses = emptyList()))
        assertTrue(decision(restored, pendingJournal = true) is CourseRestoreDecision.AlreadyRestored)
        assertTrue(decision(restored, now = expiresAt + 1L) is CourseRestoreDecision.AlreadyRestored)
    }

    @Test
    fun `restoring and purging groups report their resume state instead of a new restore`() {
        val active = capture(listOf(course(id = 11)), setOf(11L))
        val restoring = CourseRecoveryJournal.transition(active, CourseRecoveryState.RESTORING)!!
        val purging = CourseRecoveryJournal.transition(active, CourseRecoveryState.PURGING)!!

        // Past the retention window these must not be treated as an ordinary expired active group.
        assertEquals(CourseRestoreDecision.ResumeRestoration, decision(restoring, now = expiresAt + 1L))
        assertEquals(CourseRestoreDecision.ResumePurge, decision(purging, now = expiresAt + 1L))
        assertEquals(
            CourseRestoreDecision.ResumeRestoration,
            decision(restoring, currentCourses = listOf(course(id = 11)), now = deletedAt + 1L)
        )
    }

    @Test
    fun `an unreadable group can never be restored`() {
        val group = capture(listOf(course(id = 11)), setOf(11L))
        val tampered = group.copy(sourceFingerprint = "tampered")
        val result = decision(tampered, now = expiresAt - 1L)
        assertTrue("expected Invalid but got $result", result is CourseRestoreDecision.Invalid)
        assertTrue((result as CourseRestoreDecision.Invalid).reason.contains("fingerprint"))
    }

    // ------------------------------------------------------------------ purge boundary

    @Test
    fun `purge selects only expired group records and never courses or preference keys`() {
        val expiredActive = capture(listOf(course(id = 11)), setOf(11L), now = deletedAt)
        val liveActive = capture(listOf(course(id = 22)), setOf(22L), now = expiresAt)
        val restoring = CourseRecoveryJournal.transition(
            capture(listOf(course(id = 33)), setOf(33L), now = deletedAt),
            CourseRecoveryState.RESTORING
        )!!
        val purging = CourseRecoveryJournal.transition(
            capture(listOf(course(id = 44)), setOf(44L), now = deletedAt),
            CourseRecoveryState.PURGING
        )!!
        val restored = CourseRecoveryJournal.transition(
            CourseRecoveryJournal.transition(
                capture(listOf(course(id = 55)), setOf(55L), now = deletedAt),
                CourseRecoveryState.RESTORING
            )!!,
            CourseRecoveryState.RESTORED,
            5_000L
        )!!
        val unreadable = capture(listOf(course(id = 66)), setOf(66L), now = deletedAt)
            .copy(groupId = "broken", sourceFingerprint = "tampered")

        val plan = CourseRecoveryJournal.purgePlan(
            listOf(expiredActive, liveActive, restoring, purging, restored, unreadable),
            now = expiresAt + 1L
        )

        assertEquals(setOf(expiredActive.groupId, restored.groupId), plan.groupIds)
        assertFalse(plan.groupIds.contains(liveActive.groupId))
        assertFalse(plan.groupIds.contains(restoring.groupId))
        assertFalse(plan.groupIds.contains(purging.groupId))
        assertFalse(plan.groupIds.contains("broken"))
        // A purge plan carries group ids only, so member ids (live course ids) and preference keys
        // cannot be selected by construction.
        assertTrue(plan.groupIds.none { it.toLongOrNull() != null })
        assertEquals(listOf(11L), expiredActive.members.map { it.id })
        assertTrue(CourseRecoveryJournal.purgePlan(listOf(expiredActive), now = expiresAt - 1L).groupIds.isEmpty())
    }

    @Test
    fun `restore keeps the original member order of a batch`() {
        val first = course(id = 11)
        val second = course(id = 22)
        val third = course(id = 33)
        val group = capture(listOf(first, second, third), setOf(11L, 33L), CourseRecoveryScope.BATCH)
        val eligible = decision(group, now = expiresAt - 1L)
        assertTrue(eligible is CourseRestoreDecision.Eligible)
        assertEquals(listOf(first, third), (eligible as CourseRestoreDecision.Eligible).coursesToAdd)
        assertEquals(listOf(0, 2), group.members.map { it.sourceOrder })
    }
}
