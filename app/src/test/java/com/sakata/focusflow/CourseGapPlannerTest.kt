package com.sakata.focusflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CourseGapPlannerTest {
    private val profile = CommuteProfile() // 步行, 楼内缓冲 3
    private val a = Course("高数", 1, 1, 1, "西1教学楼", CampusZone.WEST_TEACHING, needsConfirmation = false)
    private val b = Course("线代", 1, 3, 3, "西2教学楼", CampusZone.WEST_TEACHING, needsConfirmation = false)

    @Test fun gaps_sameZone() {
        val gaps = CourseGapPlanner.gaps(listOf(a, b), profile)
        assertEquals(1, gaps.size)
        assertEquals(8, gaps[0].travelMinutes)       // 同区 2 分钟 + 缓冲 3×2
        assertEquals(67, gaps[0].minutesFree)        // (600-525) - 8
        assertEquals(533, gaps[0].suggestedStartMinute) // 525 + 8
    }

    @Test fun gaps_occupiedMovesStartToLongestRun() {
        val occupied = mapOf(1 to listOf(550 until 560))
        val gaps = CourseGapPlanner.gaps(listOf(a, b), profile, occupied)
        assertEquals(1, gaps.size)
        assertEquals(40, gaps[0].minutesFree)        // [560,600] 最长段
        assertEquals(560, gaps[0].suggestedStartMinute)
    }

    @Test fun gaps_noAdjacentCourses() {
        assertTrue(CourseGapPlanner.gaps(listOf(a), profile).isEmpty())
    }
}
