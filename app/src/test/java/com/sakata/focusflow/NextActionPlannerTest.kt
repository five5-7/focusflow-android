package com.sakata.focusflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class NextActionPlannerTest {
    private val monday = Calendar.getInstance().apply {
        set(Calendar.YEAR, 2026); set(Calendar.MONTH, Calendar.AUGUST); set(Calendar.DAY_OF_MONTH, 31)
    }
    private fun at(day: Calendar, hour: Int, minute: Int): Long = day.copyTime(hour, minute)

    /** 独立复制一份 Calendar 并置时分秒。 */
    private fun Calendar.copyTime(hour: Int, minute: Int): Long {
        val copy = Calendar.getInstance().apply { timeInMillis = this@copyTime.timeInMillis }
        copy.set(Calendar.HOUR_OF_DAY, hour); copy.set(Calendar.MINUTE, minute)
        copy.set(Calendar.SECOND, 0); copy.set(Calendar.MILLISECOND, 0)
        return copy.timeInMillis
    }

    private fun task(title: String, duration: Int = 30, kind: String = "任务", scheduled: Long? = null, priority: String = "mid", goalId: Long? = null, windowEnd: Long? = null) = Item(
        id = title.hashCode().toLong(),
        title = title,
        detail = "",
        kind = kind,
        scheduledAt = scheduled,
        durationMinutes = duration,
        priority = priority,
        goalId = goalId,
        windowEndAt = windowEnd,
        done = false
    )

    // ---------- 迁移等价：现有推荐顺序 ----------

    @Test
    fun `overdue scheduled task wins`() {
        val now = at(monday, 11, 0)
        val overdue = task("missed", scheduled = at(monday, 10, 0))
        val later = task("later", scheduled = at(monday, 12, 0))
        val suggestion = NextActionPlanner.recommend(listOf(overdue, later), null, now = now)
        assertEquals("missed", suggestion?.item?.title)
        assertTrue(suggestion!!.reason.contains("原定"))
    }

    @Test
    fun `expired flexible window wins over plain flexible`() {
        val now = at(monday, 11, 0)
        val expired = task("expired", windowEnd = at(monday, 10, 30))
        val plain = task("plain")
        val suggestion = NextActionPlanner.recommend(listOf(plain, expired), null, now = now)
        assertEquals("expired", suggestion?.item?.title)
        assertTrue(suggestion!!.reason.contains("弹性范围已经过去"))
    }

    @Test
    fun `upcoming within ninety minutes beats flexible`() {
        val now = at(monday, 11, 0)
        val upcoming = task("upcoming", scheduled = at(monday, 11, 30))
        val flexible = task("flexible")
        val suggestion = NextActionPlanner.recommend(listOf(flexible, upcoming), null, now = now)
        assertEquals("upcoming", suggestion?.item?.title)
        assertTrue(suggestion!!.reason.contains("最近的固定安排"))
    }

    @Test
    fun `low energy prefers shorter task first`() {
        val now = at(monday, 11, 0)
        val short = task("short", duration = 20)
        val long = task("long", duration = 45)
        val suggestion = NextActionPlanner.recommend(listOf(long, short), null, energyLevel = "偏低", now = now)
        assertEquals("short", suggestion?.item?.title)
    }

    @Test
    fun `full energy prefers goal related and longer`() {
        val now = at(monday, 11, 0)
        val goalTask = task("goal", duration = 20, goalId = 1L)
        val plainLong = task("plainlong", duration = 45)
        val suggestion = NextActionPlanner.recommend(listOf(plainLong, goalTask), null, energyLevel = "充足", now = now)
        assertEquals("goal", suggestion?.item?.title)
    }

    @Test
    fun `normal energy prefers goal related then shorter`() {
        val now = at(monday, 11, 0)
        val goalTask = task("goal", duration = 45, goalId = 1L)
        val plainShort = task("plainshort", duration = 20)
        val suggestion = NextActionPlanner.recommend(listOf(plainShort, goalTask), null, energyLevel = "正常", now = now)
        assertEquals("goal", suggestion?.item?.title)
    }

    @Test
    fun `commitment buffer filters tasks that do not fit`() {
        val now = at(monday, 11, 0)
        val commitment = ActivityCommitment("课", at(monday, 11, 45))  // 45 分钟后 → usable 30
        val fits = task("fits", duration = 30)
        val tooLong = task("toolong", duration = 60)
        val suggestion = NextActionPlanner.recommend(listOf(tooLong, fits), commitment, now = now)
        assertEquals("fits", suggestion?.item?.title)
        assertTrue(suggestion!!.reason.contains("距离 课 约 45 分钟"))
    }

    @Test
    fun `fallback returns shortest flexible when nothing else qualifies and null when none`() {
        val now = at(monday, 11, 0)
        val short = task("short", duration = 15)
        val long = task("long", duration = 60)
        assertEquals("short", NextActionPlanner.recommend(listOf(long, short), null, now = now)?.item?.title)
        assertNull(NextActionPlanner.recommend(emptyList(), null, now = now))
    }

    @Test
    fun `next commitment picks earliest future task or course`() {
        val now = at(monday, 11, 0)
        val later = task("later", scheduled = at(monday, 15, 0))
        val sooner = task("sooner", scheduled = at(monday, 14, 0))
        val commitment = NextActionPlanner.nextCommitment(listOf(later, sooner), emptyList(), now)
        assertEquals("sooner", commitment?.title)
    }

    // ---------- 6.9 增量：优先级 + 今日剩余空挡 ----------

    @Test
    fun `high priority beats mid priority in same tier`() {
        val now = at(monday, 11, 0)
        val mid = task("mid", duration = 30, priority = "mid")
        val high = task("high", duration = 30, priority = "high")
        val suggestion = NextActionPlanner.recommend(listOf(mid, high), null, now = now)
        assertEquals("high", suggestion?.item?.title)
        assertTrue(suggestion!!.reason.contains("高优先级"))
    }

    @Test
    fun `low priority loses to same tier mid`() {
        val now = at(monday, 11, 0)
        val low = task("low", duration = 30, priority = "low")
        val mid = task("mid", duration = 30, priority = "mid")
        val suggestion = NextActionPlanner.recommend(listOf(low, mid), null, now = now)
        assertEquals("mid", suggestion?.item?.title)
        assertTrue(!suggestion!!.reason.contains("高优先级"))
    }

    @Test
    fun `remaining free minutes counts only after now`() {
        // 11:00–23:00 一个 60 分钟任务（含缓冲 15 分钟 → 10:45–12:15? 占用从任务开始膨胀，这里任务在 12:00）
        val now = at(monday, 11, 0)
        val blockTask = task("block", duration = 60, scheduled = at(monday, 12, 0))
        val remaining = NextActionPlanner.remainingFreeMinutes(now, emptyList(), listOf(blockTask), null)
        // 空闲：11:00–11:45（12:00 前 15 分钟缓冲）+ 13:15–24:00 → 45 + 645 = 690
        assertEquals(690, remaining)
    }

    @Test
    fun `remaining free minutes zero when fully occupied`() {
        // 11:00–24:00 连续占用：clamp 上限 24h 内的任务无法表达，改用两条相邻任务
        val now = at(monday, 11, 0)
        val first = task("a", duration = 60, scheduled = at(monday, 11, 0))
        val second = task("b", duration = 60, scheduled = at(monday, 12, 0))
        val third = task("c", duration = 60, scheduled = at(monday, 13, 0))
        val remaining = NextActionPlanner.remainingFreeMinutes(now, emptyList(), listOf(first, second, third), null)
        // 缓冲膨胀：10:45–12:15, 11:45–13:15, 12:45–14:15 归并 → 10:45–14:15；剩余 14:15–24:00
        assertEquals(585, remaining)
    }

    @Test
    fun `task too long for today remaining is not pushed and fallback null`() {
        val now = at(monday, 23, 0)  // 距日末 60 分钟；无占用
        val long = task("long", duration = 120)
        val suggestion = NextActionPlanner.recommend(listOf(long), null, now = now)
        assertNull(suggestion)  // 放不下不硬推
    }

    @Test
    fun `fit within remaining is still recommended at end of day`() {
        val now = at(monday, 23, 0)  // 距日末 60 分钟；无占用
        val short = task("short", duration = 30)
        val suggestion = NextActionPlanner.recommend(listOf(short), null, now = now)
        assertEquals("short", suggestion?.item?.title)
    }
}
