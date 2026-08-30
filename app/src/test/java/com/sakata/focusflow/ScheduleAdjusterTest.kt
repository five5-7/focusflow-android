package com.sakata.focusflow

import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleAdjusterTest {
    // 2026-08-31 是周一（weekday=1），全部样例时间落在这一天
    private val monday = Calendar.getInstance().apply { clear(); set(2026, Calendar.AUGUST, 31, 0, 0) }.timeInMillis
    private fun at(hour: Int, minute: Int): Long = monday + hour * 60 * 60_000L + minute * 60_000L
    /** 全时段铺满 6:00–24:00 的三段占用（各被缓冲膨胀后合并成 5:45–24:15 的连续占用）。 */
    private fun fullDayBlocks() = listOf(
        task("早段", at(6, 0), 360), task("中段", at(12, 0), 360), task("晚段", at(18, 0), 360)
    )

    private fun task(title: String, scheduled: Long, duration: Int = 30, priority: String = "mid", kind: String = "任务") =
        Item(id = newItemId(), title = title, detail = "", kind = kind, scheduledAt = scheduled, durationMinutes = duration, priority = priority)

    private fun candidate(item: Item) = RecoveryCandidate(item, RecoveryReason.MISSED)

    @Test
    fun `today free slot yields postpone and keeps duration`() {
        val item = task("迟交报告", at(9, 0), 90) // 已错过
        val adjustment = ScheduleAdjuster.suggest(candidate(item), emptyList(), emptyList(), null, now = at(10, 0))

        assertEquals(AdjustAction.POSTPONE, adjustment?.action)
        assertEquals(90, adjustment?.durationMinutes)
        val cal = Calendar.getInstance().apply { timeInMillis = adjustment!!.targetTime!! }
        assertEquals(10, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, cal.get(Calendar.MINUTE))
    }

    @Test
    fun `low priority with full day goes back to inbox`() {
        val item = task("低优先级", at(9, 0), 30, priority = "low")
        val adjustment = ScheduleAdjuster.suggest(candidate(item), fullDayBlocks(), emptyList(), null, now = at(12, 0))

        assertEquals(AdjustAction.BACK_TO_INBOX, adjustment?.action)
        assertNull(adjustment?.targetTime)
    }

    @Test
    fun `mid priority shrinks to fifteen when only a short slot fits`() {
        val item = task("中优先级", at(10, 0), 60)
        // 6:00–23:00 被占用（膨胀合并 5:45–23:15），60 分钟放不下，但 23:15 前收尾的 15 分钟可以
        val blocks = listOf(
            task("早段", at(6, 0), 360), task("中段", at(12, 0), 360), task("晚段", at(18, 0), 300)
        )
        val adjustment = ScheduleAdjuster.suggest(candidate(item), blocks, emptyList(), null, now = at(12, 0))

        assertEquals(AdjustAction.SHRINK, adjustment?.action)
        assertEquals(15, adjustment?.durationMinutes)
        val cal = Calendar.getInstance().apply { timeInMillis = adjustment!!.targetTime!! }
        assertEquals(23, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(15, cal.get(Calendar.MINUTE))
        assertTrue(adjustment!!.reason.contains("15 分钟"))
    }

    @Test
    fun `high priority with no room postpones to tomorrow morning`() {
        val item = task("高优先级", at(10, 0), 60, priority = "high")
        val adjustment = ScheduleAdjuster.suggest(candidate(item), fullDayBlocks(), emptyList(), null, now = at(12, 0))

        assertEquals(AdjustAction.TOMORROW, adjustment?.action)
        val cal = Calendar.getInstance().apply { timeInMillis = adjustment!!.targetTime!! }
        assertEquals(8, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, cal.get(Calendar.MINUTE))
        assertEquals(1, cal.get(Calendar.DAY_OF_YEAR) - Calendar.getInstance().apply { timeInMillis = at(12, 0) }.get(Calendar.DAY_OF_YEAR))
    }

    @Test
    fun `activity kinds only get rearrange`() {
        val game = task("打一局", at(9, 0), 60, kind = "游戏")
        val activity = task("晚间放松", at(9, 0), 60, kind = "活动")
        val gameAdjustment = ScheduleAdjuster.suggest(candidate(game), fullDayBlocks(), emptyList(), null, now = at(12, 0))
        val activityAdjustment = ScheduleAdjuster.suggest(candidate(activity), fullDayBlocks(), emptyList(), null, now = at(12, 0))

        assertEquals(AdjustAction.REARRANGE, gameAdjustment?.action)
        assertEquals(AdjustAction.REARRANGE, activityAdjustment?.action)
        assertNull(gameAdjustment?.targetTime)
    }

    @Test
    fun `suggestions sort low priority to the front`() {
        val low = task("低档", at(9, 0), 30, priority = "low")
        val mid = task("中档", at(9, 0), 30, priority = "mid")
        val high = task("高档", at(9, 0), 30, priority = "high")
        val result = ScheduleAdjuster.suggestions(
            listOf(candidate(high), candidate(mid), candidate(low)),
            emptyList(), emptyList(), null, now = at(10, 0)
        )

        assertEquals(listOf("low", "mid", "high"), result.map { it.candidate.item.priority })
        assertTrue(result.all { it.action == AdjustAction.POSTPONE })
    }
}
