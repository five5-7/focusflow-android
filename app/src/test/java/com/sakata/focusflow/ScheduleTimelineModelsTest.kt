package com.sakata.focusflow

import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleTimelineModelsTest {
    // 2026-08-31 是周一（weekday=1），全部样例时间落在这一天
    private val monday = Calendar.getInstance().apply { clear(); set(2026, Calendar.AUGUST, 31, 0, 0) }.timeInMillis
    private fun at(hour: Int, minute: Int): Long = monday + hour * 60 * 60_000L + minute * 60_000L

    private fun course(title: String, from: Int, to: Int, zone: CampusZone = CampusZone.WEST_TEACHING) =
        Course(title, 1, from, to, "", zone, needsConfirmation = false)

    private fun task(title: String, scheduled: Long, duration: Int = 30, id: Long = newItemId()) =
        Item(id = id, title = title, detail = "", kind = "任务", scheduledAt = scheduled, durationMinutes = duration)

    private val enabledProfile = CommuteProfile(enabled = true, campusMode = "步行", buildingBufferMinutes = 3)

    @Test
    fun `conflict note names course overlap`() {
        val t = task("自习", at(8, 30), 60) // 510..570 与高数 480..525 重叠
        val note = taskConflictNote(t, listOf(course("高数", 1, 1)), listOf(t), null)
        assertEquals("与课程「高数」重叠（08:00–08:45）", note)
    }

    @Test
    fun `conflict note names other task and excludes self`() {
        val other = task("另一任务", at(10, 0), 30) // 600..630
        val target = task("待办", at(10, 20), 20) // 620..640
        val note = taskConflictNote(target, emptyList(), listOf(other, target), null)
        assertEquals("与已安排任务「另一任务」重叠（10:00–10:30）", note)
        // 自身不在列表里也照常判定（excludeId 兜底）
        assertEquals(
            "与已安排任务「另一任务」重叠（10:00–10:30）",
            taskConflictNote(target, emptyList(), listOf(other), null)
        )
    }

    @Test
    fun `conflict note names commute overlap`() {
        val courses = listOf(
            course("高数", 1, 1), course("英语", 5, 5, CampusZone.EAST_TEACHING)
        )
        val t = task("取快递", at(9, 0), 10) // 540..550 与通勤 525..545 重叠
        val note = taskConflictNote(t, courses, listOf(t), enabledProfile)
        assertTrue(note != null && note.contains("通勤"))
    }

    @Test
    fun `no conflict note when free or touching boundary`() {
        val free = task("空闲", at(12, 0), 30)
        val courseBlock = course("高数", 1, 1) // 480..525
        assertNull(taskConflictNote(free, listOf(courseBlock), listOf(free), null))
        // 下课 08:45 相接即下一任务开始时不算重叠
        val touching = task("相接", at(8, 45), 30)
        assertNull(taskConflictNote(touching, listOf(courseBlock), listOf(touching), null))
    }
    @Test
    fun `overlapping courses are merged without consuming task events`() {
        val first = event("course-a", 8 * 60, 9 * 60, ScheduleType.COURSE)
        val second = event("course-b", 8 * 60 + 30, 10 * 60, ScheduleType.COURSE)
        val task = event("task", 11 * 60, 12 * 60, ScheduleType.TASK)

        val result = mergeConflictingCourses(listOf(first, task, second))

        assertEquals(2, result.size)
        val merged = result.first { it.type == ScheduleType.COURSE }
        assertTrue(merged.isConflict)
        assertEquals(8 * 60, merged.startMinute)
        assertEquals(10 * 60, merged.endMinute)
        assertTrue(result.contains(task))
    }

    @Test
    fun `overlapping events receive separate lanes and touching events reuse a lane`() {
        val first = event("first", 8 * 60, 9 * 60, ScheduleType.TASK)
        val overlap = event("overlap", 8 * 60 + 30, 9 * 60 + 30, ScheduleType.TASK)
        val touching = event("touching", 9 * 60, 10 * 60, ScheduleType.TASK)

        val layouts = layoutTimelineEvents(listOf(first, overlap, touching))

        assertEquals(3, layouts.size)
        assertEquals(0, layouts.first { it.event.key == "first" }.lane)
        assertEquals(1, layouts.first { it.event.key == "overlap" }.lane)
        assertEquals(0, layouts.first { it.event.key == "touching" }.lane)
        assertTrue(layouts.all { it.laneCount == 2 })
    }

    @Test
    fun `events entirely outside visible day are omitted`() {
        val before = event("before", 4 * 60, 5 * 60, ScheduleType.TASK)
        val after = event("after", 24 * 60, 25 * 60, ScheduleType.TASK)
        val visible = event("visible", 6 * 60, 7 * 60, ScheduleType.TASK)

        val layouts = layoutTimelineEvents(listOf(before, visible, after))

        assertEquals(listOf("visible"), layouts.map { it.event.key })
        assertFalse(layouts.single().event.isConflict)
    }

    @Test
    fun `activity kind maps to activity type before title heuristics`() {
        fun item(title: String, kind: String, goalId: Long? = null) =
            Item(title = title, detail = "", kind = kind, goalId = goalId)

        assertEquals(ScheduleType.ACTIVITY, item("游戏", "游戏").scheduleType())
        assertEquals(ScheduleType.ACTIVITY, item("晚间放松", "活动").scheduleType())
        assertEquals(ScheduleType.EXERCISE, item("锻炼两小时", "任务").scheduleType())
        assertEquals(ScheduleType.LEARNING, item("学习计划", "任务", goalId = 1L).scheduleType())
        assertEquals(ScheduleType.REST, item("洗漱睡觉", "习惯").scheduleType())
        assertEquals(ScheduleType.TASK, item("批改作业", "任务").scheduleType())
    }

    private fun event(key: String, start: Int, end: Int, type: ScheduleType) = TimelineEvent(
        key = key,
        title = key,
        detail = key,
        weekday = 1,
        startMinute = start,
        endMinute = end,
        type = type
    )
}
