package com.sakata.focusflow

import org.junit.Assert.assertEquals
import org.junit.Test

class TaskScheduleTextTest {
    private val scheduledAt = java.util.Calendar.getInstance().apply {
        clear(); set(2026, 8, 2, 18, 0, 0)
    }.timeInMillis

    @Test fun staleRelativeDetail_isRebuiltFromScheduledTimestamp() {
        val old = Item(
            id = 1, title = "复习", kind = "任务", scheduledAt = scheduledAt,
            durationMinutes = 45, detail = "已改期至明晚 18:00 · 45分钟；届时会再次出现"
        )
        assertEquals(
            "已改期至${formatDateTime(scheduledAt)} · 45分钟；届时会再次出现",
            TaskScheduleText.canonicalize(old).detail
        )
    }

    @Test fun userWrittenDetail_isNeverRewritten() {
        val item = Item(id = 2, title = "提醒", kind = "任务", scheduledAt = scheduledAt, detail = "明晚记得带材料")
        assertEquals(item, TaskScheduleText.canonicalize(item))
    }

    @Test fun historyDisplay_usesScheduledTimestampInsteadOfOldRelativeExtra() {
        val event = TaskEvent(
            id = 3, itemId = 1, type = TaskEventType.TASK_RESCHEDULED,
            recordedAt = scheduledAt - 24 * 60 * 60_000L,
            title = "复习", scheduledAt = scheduledAt, extra = "明晚 18:00"
        )
        assertEquals(formatDateTime(scheduledAt), TaskScheduleText.eventExtra(event))
    }

    @Test fun oldTomorrowOnlyTask_isMadeAbsoluteWithoutChangingItsTimestamp() {
        val old = Item(
            id = 4, title = "带材料", kind = "任务", scheduledAt = scheduledAt,
            dayOnly = true, detail = "明天要做 · 尚未安排具体时间"
        )
        val fixed = TaskScheduleText.canonicalize(old)
        assertEquals(scheduledAt, fixed.scheduledAt)
        assertEquals("${formatDate(scheduledAt)}要做 · 尚未安排具体时间", fixed.detail)
    }

    @Test fun oldFlexibleRelativeRange_isRebuiltFromRangeTimestamps() {
        val old = Item(
            id = 5, title = "论文", kind = "任务", scheduledAt = null,
            windowStartAt = scheduledAt, windowEndAt = scheduledAt + 3 * 60 * 60_000L,
            durationMinutes = 60,
            detail = "弹性范围：明天下午 · 预计 60 分钟；尚未锁定具体时刻"
        )
        assertEquals(
            TaskScheduleText.flexibleDetail(old.windowStartAt!!, old.windowEndAt!!, 60),
            TaskScheduleText.canonicalize(old).detail
        )
    }
}
