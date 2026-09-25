package com.sakata.focusflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskReminderPolicyTest {
    private val now = 1_000_000L

    @Test fun `date only item never creates a midnight alarm`() {
        val task = Item(id = 30, title = "仅日期", detail = "", kind = "任务",
            scheduledAt = now + 24 * 60 * 60_000L, dayOnly = true)
        assertTrue(TaskReminderPolicy.pendingReminders(listOf(task), ActivityReminderSettings(), now).isEmpty())
        assertFalse(TaskReminderActionFreshness.matches(task, task.scheduledAt!!))
    }

    @Test fun `deleted task cannot schedule or act on a stale notification`() {
        val task = Item(id = 31, title = "已移除", detail = "", kind = "任务", scheduledAt = now + 60_000L)
        val deleted = task.copy(kind = "回收站", trashedAt = now)
        assertTrue(TaskReminderPolicy.pendingReminders(listOf(deleted), ActivityReminderSettings(), now).isEmpty())
        assertFalse(TaskReminderActionFreshness.matches(deleted, task.scheduledAt!!))
        val legacyGoal = task.copy(kind = "目标")
        assertTrue(TaskReminderActionFreshness.matches(legacyGoal, task.scheduledAt!!))
    }

    @Test
    fun `next reminder ignores inbox done and untimed items`() {
        val items = listOf(
            Item(id = 1, title = "想法", detail = "", kind = "收集箱", scheduledAt = now + 60_000L),
            Item(id = 2, title = "已完成", detail = "", kind = "任务", done = true, scheduledAt = now + 120_000L),
            Item(id = 3, title = "没时间", detail = "", kind = "任务"),
            Item(id = 4, title = "要提醒", detail = "", kind = "任务", scheduledAt = now + 20 * 60_000L)
        )

        val result = TaskReminderPolicy.nextReminder(items, ActivityReminderSettings(scheduleAdvanceMinutes = 10), now)

        assertEquals(4L, result?.itemId)
        assertEquals(now + 10 * 60_000L, result?.triggerAt)
        assertEquals(TaskReminderStage.ADVANCE, result?.stage)
    }

    @Test
    fun `reminder due inside advance window is scheduled immediately`() {
        val item = Item(id = 5, title = "马上开始", detail = "", kind = "任务", scheduledAt = now + 5 * 60_000L)

        val reminders = TaskReminderPolicy.pendingReminders(listOf(item), ActivityReminderSettings(scheduleAdvanceMinutes = 10), now)

        assertEquals(3, reminders.size)
        assertEquals(TaskReminderStage.ADVANCE, reminders[0].stage)
        assertEquals(now + 1_000L, reminders[0].triggerAt)
        assertEquals(TaskReminderStage.DUE, reminders[1].stage)
        assertEquals(now + 5 * 60_000L, reminders[1].triggerAt)
        assertEquals(TaskReminderStage.MISSED, reminders[2].stage)
        assertEquals(now + 65 * 60_000L, reminders[2].triggerAt)
    }

    @Test
    fun `zero advance schedules only at-time reminder`() {
        val item = Item(id = 7, title = "到点开始", detail = "", kind = "任务", scheduledAt = now + 5 * 60_000L)

        val reminders = TaskReminderPolicy.pendingReminders(listOf(item), ActivityReminderSettings(scheduleAdvanceMinutes = 0), now)

        assertEquals(2, reminders.size)
        assertEquals(TaskReminderStage.DUE, reminders[0].stage)
        assertEquals(now + 5 * 60_000L, reminders[0].triggerAt)
        assertEquals(TaskReminderStage.MISSED, reminders[1].stage)
    }

    @Test
    fun `disabled reminders have no pending reminder`() {
        val item = Item(id = 6, title = "任务", detail = "", kind = "任务", scheduledAt = now + 60_000L)

        assertNull(TaskReminderPolicy.nextReminder(listOf(item), ActivityReminderSettings(scheduleRemindersEnabled = false), now))
    }

    @Test
    fun `exact delivery follows platform permission`() {
        assertEquals(AlarmDeliveryMode.EXACT, TaskReminderPolicy.deliveryMode(30, false))
        assertEquals(AlarmDeliveryMode.EXACT, TaskReminderPolicy.deliveryMode(35, true))
        assertEquals(AlarmDeliveryMode.INEXACT, TaskReminderPolicy.deliveryMode(35, false))
    }

    @Test
    fun `background test distinguishes on-time delayed and overdue delivery`() {
        val expectedAt = now + 60_000L

        assertEquals(ReminderTestResult.NONE, TaskReminderPolicy.testResult(null, now))
        assertEquals(ReminderTestResult.PENDING, TaskReminderPolicy.testResult(ReminderTestProbe(expectedAt, null), expectedAt + 20_000L))
        assertEquals(ReminderTestResult.ON_TIME, TaskReminderPolicy.testResult(ReminderTestProbe(expectedAt, expectedAt + 10_000L), expectedAt + 10_000L))
        assertEquals(ReminderTestResult.DELAYED, TaskReminderPolicy.testResult(ReminderTestProbe(expectedAt, expectedAt + 45_000L), expectedAt + 45_000L))
        assertEquals(ReminderTestResult.OVERDUE, TaskReminderPolicy.testResult(ReminderTestProbe(expectedAt, null), expectedAt + 31_000L))
    }

    @Test
    fun `notification action only changes the current scheduled occurrence`() {
        val scheduled = Item(id = 7, title = "任务", detail = "", kind = "任务", scheduledAt = now + 60_000L)
        assertTrue(TaskReminderActionFreshness.matches(scheduled, scheduled.scheduledAt!!))
        assertFalse(TaskReminderActionFreshness.matches(scheduled, now + 120_000L))
        assertFalse(TaskReminderActionFreshness.matches(scheduled.copy(done = true), scheduled.scheduledAt!!))
        assertFalse(TaskReminderActionFreshness.matches(scheduled.copy(kind = "收集箱", scheduledAt = null), -1L))
    }

    @Test fun `slot end reminder is scheduled once and rejects stale delivery`() {
        val start = now + 5 * 60_000L
        val task = Item(id = 82L, title = "写报告", kind = "任务", scheduledAt = start,
            durationMinutes = 30)
        val inProgress = TaskReminderPolicy.pendingReminders(listOf(task),
            ActivityReminderSettings(scheduleAdvanceMinutes = 10), start + 1_000L)
        assertEquals(listOf(TaskReminderStage.MISSED), inProgress.map { it.stage })
        assertEquals(start + 30 * 60_000L, inProgress.single().triggerAt)
        val end = start + 30 * 60_000L
        assertFalse(TaskMissedReminderPolicy.matches(task, start, end - 1L))
        assertTrue(TaskMissedReminderPolicy.matches(task, start, end))
        assertFalse(TaskMissedReminderPolicy.matches(task.copy(done = true), start, end))
        assertFalse(TaskMissedReminderPolicy.matches(task.copy(scheduledAt = start + 60_000L), start, end))
        assertFalse(TaskMissedReminderPolicy.matches(task.copy(dayOnly = true), start, end))
        assertFalse(TaskMissedReminderPolicy.matches(task, start, end + 2 * 60 * 60_000L + 1L))
        assertTrue(TaskReminderPolicy.pendingReminders(listOf(task), ActivityReminderSettings(), end).isEmpty())
    }
}
