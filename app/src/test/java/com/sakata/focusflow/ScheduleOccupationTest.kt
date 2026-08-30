package com.sakata.focusflow

import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleOccupationTest {
    // 2026-08-31 是周一（weekday=1），全部样例时间落在这一天
    private val monday = Calendar.getInstance().apply { clear(); set(2026, Calendar.AUGUST, 31, 0, 0) }.timeInMillis
    private fun at(hour: Int, minute: Int): Long = monday + hour * 60 * 60_000L + minute * 60_000L

    private fun course(title: String, weekday: Int = 1, from: Int, to: Int, zone: CampusZone = CampusZone.WEST_TEACHING) =
        Course(title, weekday, from, to, "", zone, needsConfirmation = false)

    private fun task(title: String, scheduled: Long, duration: Int = 30, id: Long = newItemId()) =
        Item(id = id, title = title, detail = "", kind = "任务", scheduledAt = scheduled, durationMinutes = duration)

    private val enabledProfile = CommuteProfile(enabled = true, campusMode = "步行", buildingBufferMinutes = 3)

    @Test fun `overlaps treats touching boundaries as free`() {
        // IntRange(300..320) 按 [300, 321) 半开处理
        assertFalse(ScheduleOccupation.overlaps(321, 360, listOf(300..320)))
        assertTrue(ScheduleOccupation.overlaps(310, 330, listOf(300..320)))
        assertTrue(ScheduleOccupation.overlaps(319, 322, listOf(300..320)))
    }

    @Test fun `dayOccupied dilates with buffer and merges nearby ranges`() {
        val courses = listOf(course("高数", from = 1, to = 1), course("英语", from = 2, to = 2))
        val occupied = ScheduleOccupation.dayOccupied(1, courses, emptyList(), null)
        // 480..525 与 530..575 各膨胀 15（半开 [465,540)/[510,590)）后相邻归并，闭区间表示 465..589
        assertEquals(listOf(465..589), occupied)
    }

    @Test fun `taskBlocks excludes candidate itself and completed items`() {
        val done = task("完成", at(9, 0), id = newItemId()).copy(done = true, completedAt = at(9, 30))
        val candidate = task("候选", at(10, 0), id = newItemId())
        val other = task("另一任务", at(11, 0))
        val tuesday = task("周二", at(11, 0)).copy(scheduledAt = monday + 24 * 60 * 60_000L + 11 * 60 * 60_000L)

        val blocks = ScheduleOccupation.taskBlocks(listOf(done, candidate, other, tuesday), 1, excludeId = candidate.id)

        assertEquals(listOf(other.title), blocks.map { it.title })
        assertEquals(11 * 60, blocks.single().startMinute)
        assertEquals(11 * 60 + 30, blocks.single().endMinute)
    }

    @Test fun `commuteBlocks require enabled profile and span the course gap`() {
        val courses = listOf(course("高数", from = 1, to = 1), course("英语", from = 5, to = 5, zone = CampusZone.EAST_TEACHING))
        assertEquals(emptyList<OccupiedBlock>(), ScheduleOccupation.commuteBlocks(courses, CommuteProfile()))

        val blocks = ScheduleOccupation.commuteBlocks(courses, enabledProfile)
        assertEquals(1, blocks.size)
        val block = blocks.single()
        assertEquals("commute", block.kind)
        // 下课 525（8:45），estim 走路14+缓冲6=20 → 545；下一课 700 之前，不截断
        assertEquals(525, block.startMinute)
        assertEquals(545, block.endMinute)
    }

    @Test fun `commuteBlocks truncate when next class starts before travel ends`() {
        val courses = listOf(course("高数", from = 1, to = 1), course("英语", from = 2, to = 2, zone = CampusZone.EAST_TEACHING))
        val block = ScheduleOccupation.commuteBlocks(courses, enabledProfile).single()
        // 剩 5 分钟空挡，通勤估算 20 分钟 → 截断到下一课开始
        assertEquals(525, block.startMinute)
        assertEquals(530, block.endMinute)
    }

    @Test fun `nextFreeSlot clamps before six in the morning and returns first free slot`() {
        assertEquals(360, ScheduleOccupation.nextFreeSlot(1, 300, 30, emptyList(), emptyList(), null))
    }

    @Test fun `nextFreeSlot jumps past blocked ranges including buffer`() {
        val existing = task("抵流任务", at(10, 0), duration = 30) // 600..630，膨胀后阻塞 [585, 645)
        val slot = ScheduleOccupation.nextFreeSlot(1, 600, 30, emptyList(), listOf(existing), null, excludeId = newItemId())
        assertEquals(645, slot) // 10:45，正好在缓冲尾之后
    }

    @Test fun `nextFreeSlot returns null when nothing fits before midnight`() {
        // 各 360 分钟铺满 6:00–24:00（单一任务受 5–360 持久化上限约束，需分段）
        val wholeDay = listOf(
            task("早段", at(6, 0), 360), task("中段", at(12, 0), 360), task("晚段", at(18, 0), 360)
        )
        assertNull(ScheduleOccupation.nextFreeSlot(1, 360, 30, emptyList(), wholeDay, null, excludeId = newItemId()))
    }

    @Test fun `dayOccupied includes commute travel when profile enabled`() {
        val courses = listOf(course("高数", from = 1, to = 1), course("英语", from = 5, to = 5, zone = CampusZone.EAST_TEACHING))
        val withTravel = ScheduleOccupation.dayOccupied(1, courses, emptyList(), enabledProfile)
        val withoutTravel = ScheduleOccupation.dayOccupied(1, courses, emptyList(), null)
        assertTrue(withTravel.size < withoutTravel.size || withTravel.first().first <= withoutTravel.first().first)
        // 两课较远（p1 与 p5），膨胀后两组区间互不相邻
        assertEquals(2, withTravel.size)
        assertEquals(465..559, withTravel.first())
        assertEquals(685..759, withTravel.last())
    }

    @Test fun `conflictingBlock names the overlap without buffer`() {
        val blocks = ScheduleOccupation.courseBlocks(listOf(course("高数", from = 1, to = 1)), 1)
        assertEquals("高数", ScheduleOccupation.conflictingBlock(blocks, 500, 530)?.title)
        assertNull(ScheduleOccupation.conflictingBlock(blocks, 525, 560))
    }

    @Test fun `weekday of monday is one`() {
        assertEquals(1, ScheduleOccupation.weekdayOf(at(9, 0)))
        assertEquals(9 * 60, ScheduleOccupation.minuteOfDay(at(9, 0)))
    }
}
