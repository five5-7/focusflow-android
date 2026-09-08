package com.sakata.focusflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CourseGapPlannerTest {
    @org.junit.Before fun resetPeriodTable() {
        CourseGapPlanner.configure(CoursePeriodTable.reference())
    }

    @Test fun nestedCoursesDoNotCreateFreeTimeInsideLongCourse() {
        val long = a.copy(endPeriod = 5)
        val nested = b.copy(startPeriod = 2, endPeriod = 2)
        val third = b.copy(startPeriod = 4, endPeriod = 4)
        val gaps = CourseGapPlanner.gaps(listOf(long, nested, third), profile)
        assertEquals(2, gaps.size)
        assertTrue(gaps.all { it.minutesFree == 0 })
    }

    @Test fun gapAfterNestedCourseStartsAfterLongestPrecedingCourse() {
        val long = a.copy(endPeriod = 5)
        val nested = b.copy(startPeriod = 2, endPeriod = 2)
        val later = b.copy(startPeriod = 7, endPeriod = 7)
        val gap = CourseGapPlanner.gaps(listOf(later, nested, long), profile).last()
        assertEquals(long.id, gap.from.id)
        assertEquals(CourseGapPlanner.periodEnd(5) + gap.travelMinutes, gap.suggestedStartMinute)
        assertEquals(CourseGapPlanner.periodStart(7) - gap.suggestedStartMinute, gap.minutesFree)
    }

    @Test fun generatedFreeIntervalsNeverOverlapAnyCourse() {
        val random = kotlin.random.Random(8002)
        repeat(500) { sample ->
            val courses = List(random.nextInt(1, 13)) { index ->
                val start = random.nextInt(1, 14)
                a.copy(id = index.toLong() + 1, startPeriod = start,
                    endPeriod = random.nextInt(start, 14))
            }
            val intervals = CourseGapPlanner.gaps(courses, profile)
                .filter { it.minutesFree > 0 }
                .map { it.suggestedStartMinute to it.suggestedStartMinute + it.minutesFree } +
                CourseGapPlanner.freeWindows(courses).filter { it.weekday == 1 }
                    .map { it.startMinute to it.endMinute }
            intervals.forEach { (start, end) ->
                assertTrue("sample=$sample interval=$start..$end", courses.none {
                    start < CourseGapPlanner.periodEnd(it.endPeriod) &&
                        end > CourseGapPlanner.periodStart(it.startPeriod)
                })
            }
        }
    }
    private val profile = CommuteProfile() // 步行, 楼内缓冲 3
    private val a = Course("高数", 1, 1, 1, "西1教学楼", CampusZone.WEST_TEACHING, needsConfirmation = false)
    private val b = Course("线代", 1, 3, 3, "西2教学楼", CampusZone.WEST_TEACHING, needsConfirmation = false)

    @Test fun gaps_sameZone() {
        val gaps = CourseGapPlanner.gaps(listOf(a, b), profile)
        assertEquals(1, gaps.size)
        assertEquals(11, gaps[0].travelMinutes)       // “近”档 5 分钟 + 缓冲 3×2
        assertEquals(64, gaps[0].minutesFree)         // (600-525) - 11
        assertEquals(536, gaps[0].suggestedStartMinute) // 525 + 11
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

    @Test fun gaps_overlappingCoursesReturnZeroInsteadOfCrashing() {
        val overlapping = b.copy(startPeriod = 1, endPeriod = 2)

        val gap = CourseGapPlanner.gaps(listOf(a, overlapping), profile).single()

        assertEquals(0, gap.minutesFree)
        assertEquals(CourseGapPlanner.periodStart(overlapping.startPeriod), gap.suggestedStartMinute)
    }

    @Test fun gaps_commuteLongerThanBreakReturnsZeroInsteadOfCrashing() {
        val nextPeriod = b.copy(startPeriod = 2, endPeriod = 2, zone = CampusZone.EAST_TEACHING)
        val longCommute = profile.copy(farMinutes = 25)

        val gap = CourseGapPlanner.gaps(listOf(a, nextPeriod), longCommute).single()

        assertEquals(0, gap.minutesFree)
        assertEquals(CourseGapPlanner.periodStart(nextPeriod.startPeriod), gap.suggestedStartMinute)
    }

    @Test fun gaps_activityInsideInvalidGapStillReturnsZero() {
        val nextPeriod = b.copy(startPeriod = 2, endPeriod = 2, zone = CampusZone.EAST_TEACHING)
        val occupiedByActivity = mapOf(1 to listOf(520 until 565))

        val gap = CourseGapPlanner.gaps(listOf(a, nextPeriod), profile.copy(farMinutes = 25), occupiedByActivity).single()

        assertEquals(0, gap.minutesFree)
    }

    @Test fun deleteCourseUsesStableIdEvenWhenDialogSnapshotIsStale() {
        val other = a.copy(id = newItemId())
        val changedSnapshot = a.copy(enabled = false)

        val remaining = removeCoursesById(listOf(a, other), listOf(changedSnapshot))

        assertEquals(listOf(other), remaining)
    }
}
