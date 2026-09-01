package com.sakata.focusflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskActionsTest {
    private val fixedNow = java.util.Calendar.getInstance().apply {
        clear(); set(2026, 0, 5, 10, 0, 0) // 2026-01-05 周一
    }.timeInMillis

    private fun item(
        id: Long = 1,
        title: String = "写周报",
        kind: String = "任务",
        scheduledAt: Long? = fixedNow + 2 * 60 * 60_000L,
        detail: String = "先写提纲，再补正文",
        goalId: Long? = null,
        durationMinutes: Int = 60,
        priority: String = "mid",
        recoverySourceScheduledAt: Long? = null,
        rescheduleCount: Int = 0
    ) = Item(
        id = id, title = title, detail = detail, kind = kind,
        scheduledAt = scheduledAt, goalId = goalId, durationMinutes = durationMinutes,
        priority = priority, recoverySourceScheduledAt = recoverySourceScheduledAt,
        rescheduleCount = rescheduleCount
    )

    @Test fun completeNow_marksOnlyTargetDone() {
        val a = item(id = 1)
        val b = item(id = 2, title = "另一项")
        val result = TaskActions.completeNow(listOf(a, b), a, now = fixedNow)
        assertEquals(true, result.items[0].done)
        assertEquals("完成", result.items[0].completionLevel)
        assertEquals(fixedNow, result.items[0].completedAt)
        assertEquals(false, result.items[1].done)
        assertEquals(TaskEventType.TASK_COMPLETED, result.event!!.type)
        assertEquals("完成", result.event!!.extra)
        assertEquals(a.id, result.event!!.itemId)
        assertEquals("写周报", result.event!!.title)
    }

    @Test fun completeNow_preservesUnscheduledAt() {
        // 完成不改任务的时间字段（统计以 scheduledAt 归日）
        val a = item(id = 1, goalId = 42)
        val result = TaskActions.completeNow(listOf(a), a, now = fixedNow)
        assertEquals(a.scheduledAt, result.items[0].scheduledAt)
        assertEquals(42L, result.items[0].goalId!!)
    }

    @Test fun completeWithLevel_forwardsLevelToItemAndEvent() {
        val result = TaskActions.completeWithLevel(listOf(item(id = 7)), item(id = 7), "最低版本", now = fixedNow)
        assertEquals("最低版本", result.items[0].completionLevel)
        assertEquals(true, result.items[0].done)
        assertEquals("最低版本", result.event!!.extra)
    }

    @Test fun shrinkToInbox_resetsTimeAndShortens() {
        val a = item(id = 3, title = "重新安排：写报告", scheduledAt = fixedNow)
        val result = TaskActions.shrinkToInbox(listOf(a), a)
        val moved = result.items[0]
        assertEquals("写报告", moved.title)
        assertEquals("收集箱", moved.kind)
        assertEquals(15, moved.durationMinutes)
        assertNull(moved.scheduledAt)
        assertNull(moved.windowStartAt)
        assertNull(moved.windowEndAt)
        assertEquals(false, moved.dayOnly)
        // 原调度日记忆：无先前恢复来源时回落到当前 planned 时间
        assertEquals(fixedNow, moved.recoverySourceScheduledAt)
        assertEquals(TaskEventType.TASK_TO_INBOX, result.event!!.type)
        assertEquals("缩为 15 分钟", result.event!!.extra)
    }

    @Test fun shrinkToInbox_keepsExistingRecoverySource() {
        val old = fixedNow - 24 * 3600_000L
        val a = item(id = 3, title = "写报告", scheduledAt = fixedNow, recoverySourceScheduledAt = old)
        val moved = TaskActions.shrinkToInbox(listOf(a), a).items[0]
        assertEquals(old, moved.recoverySourceScheduledAt)
    }

    @Test fun returnToInbox_clearsScheduleKeepsRecovery() {
        val a = item(id = 9, title = "重新安排：跑步", scheduledAt = fixedNow)
        val result = TaskActions.returnToInbox(listOf(a), a)
        val moved = result.items[0]
        assertEquals("收集箱", moved.kind)
        assertEquals("已放回收集箱；准备好后再安排", moved.detail)
        assertNull(moved.scheduledAt)
        assertNull(moved.windowStartAt)
        assertNull(moved.windowEndAt)
        assertEquals(false, moved.dayOnly)
        assertEquals(fixedNow, moved.recoverySourceScheduledAt)
        assertEquals(TaskEventType.TASK_TO_INBOX, result.event!!.type)
        assertTrue(result.event!!.extra.isBlank())
    }

    @Test fun abandon_removesAndRecordsWithExtra() {
        val a = item(id = 11, title = "复习")
        val b = item(id = 12, title = "保持")
        val result = TaskActions.abandon(listOf(a, b), a)
        assertEquals(1, result.items.size)
        assertEquals(b.id, result.items[0].id)
        assertEquals(TaskEventType.TASK_DELETED, result.event!!.type)
        assertEquals("放弃", result.event!!.extra)
    }

    @Test fun deleteItem_removesWithoutExtra() {
        val a = item(id = 11)
        val result = TaskActions.deleteItem(listOf(a, item(id = 12)), a)
        assertEquals(1, result.items.size)
        assertEquals(TaskEventType.TASK_DELETED, result.event!!.type)
        assertEquals("", result.event!!.extra)
    }

    @Test fun pause_onlyMarksAndProducesNoEvent() {
        val result = TaskActions.pause(listOf(item(id = 4)), item(id = 4))
        assertEquals("暂停", result.items[0].kind)
        assertEquals("已暂停；随时可在计划中恢复", result.items[0].detail)
        assertNull(result.event)
    }

    @Test fun resume_clearsTimeAndRecordsRestore() {
        val a = item(id = 6, title = "重新安排：整理材料", scheduledAt = fixedNow, kind = "暂停")
        val result = TaskActions.resume(listOf(a), a)
        val restored = result.items[0]
        assertEquals("任务", restored.kind)
        assertEquals("已恢复；今天有空时再做", restored.detail)
        assertNull(restored.scheduledAt)
        assertEquals(TaskEventType.TASK_RESTORED, result.event!!.type)
        assertEquals("整理材料", result.event!!.title)
    }

    @Test fun attachToGoal_linksGoalAndClearsSchedule() {
        val goal1 = Goal(id = 101, title = "高数", weeklyTarget = 3, durationMinutes = 45)
        val a = item(id = 21, title = "重新安排：习题课")
        val result = TaskActions.attachToGoal(listOf(a), a, goal1)
        val attached = result.items[0]
        assertEquals("习题课", attached.title)
        assertEquals("任务", attached.kind)
        assertEquals("属于目标：高数 · 尚未安排具体时间", attached.detail)
        assertEquals(101L, attached.goalId!!)
        assertNull(attached.scheduledAt)
        assertNull(attached.windowStartAt)
        assertNull(attached.windowEndAt)
        assertEquals(false, attached.dayOnly)
        assertEquals(TaskEventType.TASK_ATTACHED_TO_PLAN, result.event!!.type)
        assertEquals(0L, result.event!!.scheduledAt)
        assertEquals("高数", result.event!!.extra)
    }

    @Test fun convertToGoal_removesItemAndRecordsTargetTitle() {
        val a = item(id = 31, title = "阅读笔记")
        val result = TaskActions.convertToGoal(listOf(a, item(id = 32)), a, "读《专注力》")
        assertEquals(1, result.items.size)
        assertEquals(TaskEventType.TASK_CONVERTED, result.event!!.type)
        assertEquals("读《专注力》", result.event!!.extra)
        assertEquals(0L, result.event!!.scheduledAt)
    }

    @Test fun planDelayed_buildsRescheduledCopy() {
        val a = item(id = 8, title = "重新安排：写方案", scheduledAt = fixedNow, rescheduleCount = 2, priority = "low")
        val result = TaskActions.planDelayed(listOf(a), a, fixedNow + 3 * 3600_000L, 30, "周一 13:00", "high", now = fixedNow)
        val delayed = result.delayedItem
        // 副本不剥离前缀（与原来 saveDelayedItem 一致；事件文案才剥离）
        assertEquals("重新安排：写方案", delayed.title)
        assertEquals("已改期至周一 13:00；届时会再次出现", delayed.detail)
        assertEquals(fixedNow + 3 * 3600_000L, delayed.scheduledAt)
        assertEquals(30, delayed.durationMinutes)
        assertEquals(false, delayed.dayOnly)
        assertNull(delayed.windowStartAt)
        assertNull(delayed.windowEndAt)
        assertEquals("high", delayed.priority)
        assertEquals(3, delayed.rescheduleCount)
        assertEquals(fixedNow, delayed.lastRescheduledAt)
        assertEquals(result.items[0].id, delayed.id)
        assertEquals(TaskEventType.TASK_RESCHEDULED, result.event.type)
        assertEquals("写方案", result.event.title)
        assertEquals(fixedNow + 3 * 3600_000L, result.event.scheduledAt)
        assertEquals("周一 13:00", result.event.extra)
        assertEquals("写方案 → 周一 13:00", result.baselinePayload)
    }

    @Test fun planDelayed_keepsOtherItems() {
        val a = item(id = 8, title = "写方案")
        val b = item(id = 9, title = "另一项")
        val result = TaskActions.planDelayed(listOf(a, b), a, fixedNow, 30, "周一 10:00", "mid", now = fixedNow)
        assertEquals(2, result.items.size)
        assertEquals(a.id, result.items[0].id)
        assertEquals(b.id, result.items[1].id)
    }

    @Test fun planDelayed_preservesScheduledActivityKind() {
        val activity = item(id = 18, title = "跑步", kind = "活动")
        val result = TaskActions.planDelayed(listOf(activity), activity, fixedNow, 30, "周一 10:00", "mid", now = fixedNow)
        assertEquals("活动", result.delayedItem.kind)
        assertEquals("活动", result.items.single().kind)
    }

    @Test fun scheduledShape_locksTimeAndLabel() {
        val shaped = TaskActions.scheduledShape(item(id = 5, title = "重新安排：整理笔记", priority = "low"), fixedNow + 60_000L, 45, "周一 10:00", "high")
        assertEquals("整理笔记", shaped.title)
        assertEquals("任务", shaped.kind)
        assertEquals("已安排：周一 10:00 · 45 分钟；可随时改期", shaped.detail)
        assertEquals(fixedNow + 60_000L, shaped.scheduledAt)
        assertEquals(45, shaped.durationMinutes)
        assertEquals(false, shaped.dayOnly)
        assertNull(shaped.windowStartAt)
        assertNull(shaped.windowEndAt)
        assertEquals("high", shaped.priority)
    }

    @Test fun flexibleShape_keepsWindowAndClearsTime() {
        val shaped = TaskActions.flexibleShape(item(id = 6, title = "重新安排：论文", priority = "low"), fixedNow + 1, fixedNow + 2, 30, "每周二", "high")
        assertEquals("论文", shaped.title)
        assertEquals("弹性范围：每周二 · 预计 30 分钟；尚未锁定具体时刻", shaped.detail)
        assertNull(shaped.scheduledAt)
        assertEquals(fixedNow + 1, shaped.windowStartAt)
        assertEquals(fixedNow + 2, shaped.windowEndAt)
        assertEquals(30, shaped.durationMinutes)
        assertEquals(false, shaped.dayOnly)
        assertEquals("high", shaped.priority)
    }

    @Test fun completeNow_keepsUnmatchedItemsIdentical() {
        val a = item(id = 1)
        val b = item(id = 2, title = "无关项", kind = "收集箱", scheduledAt = null)
        val result = TaskActions.completeNow(listOf(a, b), a, now = fixedNow)
        assertEquals("写周报", result.items[0].title)
        assertEquals("无关项", result.items[1].title)
        assertEquals("收集箱", result.items[1].kind)
        assertNull(result.items[1].scheduledAt)
        assertEquals(false, result.items[1].done)
    }
}
