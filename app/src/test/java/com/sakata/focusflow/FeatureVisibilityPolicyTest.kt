package com.sakata.focusflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeatureVisibilityPolicyTest {
    @Test
    fun `fresh install hides every optional daily module`() {
        val visible = FeatureVisibilityPolicy.daily(FeatureUsageSnapshot())

        assertFalse(visible.meals)
        assertFalse(visible.goals)
        assertFalse(visible.courseBlocks)
        assertFalse(visible.campus)
        assertFalse(visible.energy)
        assertFalse(visible.windDown)
    }

    @Test
    fun `configured data reveals only relevant daily modules`() {
        val visible = FeatureVisibilityPolicy.daily(
            FeatureUsageSnapshot(
                baselineComplete = true,
                mealRecordCount = 2,
                goalCount = 1,
                confirmedCourseCount = 4,
                lifeStage = LifeStage.SCHOOL,
                campusLifeEnabled = true,
                statusCheckInCount = 3
            )
        )

        assertTrue(visible.meals)
        assertTrue(visible.goals)
        assertTrue(visible.courseBlocks)
        assertTrue(visible.campus)
        assertTrue(visible.energy)
        assertTrue(visible.windDown)
    }

    @Test
    fun `holiday hides course and campus modules without deleting their data`() {
        val visible = FeatureVisibilityPolicy.daily(
            FeatureUsageSnapshot(
                confirmedCourseCount = 6,
                lifeStage = LifeStage.HOLIDAY,
                campusLifeEnabled = true
            )
        )

        assertFalse(visible.courseBlocks)
        assertFalse(visible.campus)
    }

    @Test
    fun `school stage emphasizes course setup only while no course data exists`() {
        val empty = FeatureUsageSnapshot(lifeStage = LifeStage.SCHOOL)
        val pending = empty.copy(pendingCourseCount = 1)

        assertTrue(FeatureVisibilityPolicy.shouldEmphasizeCourseSetup(empty))
        assertFalse(FeatureVisibilityPolicy.shouldEmphasizeCourseSetup(pending))
    }

    @Test
    fun `switching to plan always returns to collapsed hub`() {
        val current = NavigationSnapshot(
            root = RootDestination.PLAN,
            planSubpageOpen = true
        )

        assertEquals(
            NavigationSnapshot(root = RootDestination.PLAN),
            NavigationPolicy.selectRoot(current, RootDestination.PLAN)
        )
    }

    @Test
    fun `back closes the active secondary page before leaving root`() {
        val inbox = NavigationPolicy.openInbox(NavigationSnapshot())
        val plan = NavigationPolicy.openPlanSubpage(inbox)

        assertEquals(NavigationSnapshot(root = RootDestination.PLAN), NavigationPolicy.back(plan))
        assertNull(NavigationPolicy.back(NavigationSnapshot(root = RootDestination.PLAN)))
    }
}
