package com.sakata.focusflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskReminderPolicyTest {
    private val now = 1_000_000L

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

        assertEquals(2, reminders.size)
        assertEquals(TaskReminderStage.ADVANCE, reminders[0].stage)
        assertEquals(now + 1_000L, reminders[0].triggerAt)
        assertEquals(TaskReminderStage.DUE, reminders[1].stage)
        assertEquals(now + 5 * 60_000L, reminders[1].triggerAt)
    }

    @Test
    fun `zero advance schedules only at-time reminder`() {
        val item = Item(id = 7, title = "到点开始", detail = "", kind = "任务", scheduledAt = now + 5 * 60_000L)

        val reminders = TaskReminderPolicy.pendingReminders(listOf(item), ActivityReminderSettings(scheduleAdvanceMinutes = 0), now)

        assertEquals(1, reminders.size)
        assertEquals(TaskReminderStage.DUE, reminders.single().stage)
        assertEquals(now + 5 * 60_000L, reminders.single().triggerAt)
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
}
