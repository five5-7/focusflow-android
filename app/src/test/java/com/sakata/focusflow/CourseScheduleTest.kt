package com.sakata.focusflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CourseScheduleTest {
    private val course = Course("高数", 1, 1, 1, "西1教学楼", CampusZone.WEST_TEACHING, needsConfirmation = false)

    @Test fun periodStartAndEnd() {
        assertEquals(480, CourseGapPlanner.periodStart(1))
        assertEquals(525, CourseGapPlanner.periodEnd(1))
    }

    @Test fun freeWindows_afterLastClass() {
        val windows = CourseGapPlanner.freeWindows(listOf(course))
        val monday = windows.filter { it.weekday == 1 }
        assertEquals(1, monday.size)
        assertEquals(525, monday[0].startMinute)
        assertEquals(1320, monday[0].endMinute)
        assertEquals(795, monday[0].minutes)
        assertEquals("课后空闲", monday[0].kind)
    }

    @Test fun freeWindows_occupiedSplitsWindow() {
        val occupied = mapOf(1 to listOf(600 until 660))
        val windows = CourseGapPlanner.freeWindows(listOf(course), occupied = occupied)
        val monday = windows.filter { it.weekday == 1 }
        assertEquals(2, monday.size)
        assertEquals(525, monday[0].startMinute)
        assertEquals(600, monday[0].endMinute)
        assertEquals(660, monday[1].startMinute)
        assertEquals(1320, monday[1].endMinute)
    }

    @Test fun freeWindows_fullWeekWhenNoCourse() {
        val windows = CourseGapPlanner.freeWindows(emptyList())
        assertEquals(7, windows.size)
        assertTrue(windows.all { it.kind == "整天空闲" && it.minutes == 840 })
    }

    @Test fun freeWindows_unconfirmedIgnored() {
        val unconfirmed = course.copy(needsConfirmation = true)
        val windows = CourseGapPlanner.freeWindows(listOf(unconfirmed))
        assertEquals(7, windows.size)
        assertTrue(windows.all { it.kind == "整天空闲" })
    }

    @Test fun mergeRecognized_dedupsExisting() {
        val existing = Course("高数", 1, 1, 2, "西1教学楼", CampusZone.WEST_TEACHING, needsConfirmation = false)
        val merge = mergeRecognizedCourses(listOf(existing), listOf(existing))
        assertEquals(0, merge.added.size)
        assertEquals(0, merge.conflicts.size)
        assertEquals("识别到的课程都已存在，没有重复添加。", merge.message)
    }

    @Test fun mergeRecognized_keepsConflictAsPendingAndMentions() {
        val confirmed = Course("高数", 1, 1, 2, "西1教学楼", CampusZone.WEST_TEACHING, needsConfirmation = false)
        val conflict = Course("新课程", 1, 2, 3, "西1教学楼", CampusZone.WEST_TEACHING)
        val merge = mergeRecognizedCourses(listOf(confirmed), listOf(conflict))
        assertEquals(1, merge.added.size)
        assertEquals(1, merge.conflicts.size)
        assertTrue(merge.message.contains("有 1 门与已确认课程时间冲突"))
        assertTrue(merge.message.contains("已生成 1 门待确认课程"))
        assertTrue(merge.message.contains("请核对后再确认"))
    }

    @Test fun mergeRecognized_notesInnerOverlap() {
        val a = Course("课程A", 1, 1, 2, "西1教学楼", CampusZone.WEST_TEACHING)
        val b = Course("课程B", 1, 2, 3, "西1教学楼", CampusZone.WEST_TEACHING)
        val merge = mergeRecognizedCourses(emptyList(), listOf(a, b))
        assertEquals(2, merge.added.size)
        assertEquals(0, merge.conflicts.size)
        // 实现按「冲突门数」计数：每门与其它门重叠均 +1（与识别提示原逻辑一致）
        assertEquals(2, merge.innerConflicts)
        assertTrue(merge.message.contains("其中 2 门互相时间重叠"))
    }

    @Test fun mergeRecognized_emptyInputMessage() {
        val merge = mergeRecognizedCourses(emptyList(), emptyList())
        assertTrue(merge.message.contains("没有找到可解析的课程"))
    }
}
