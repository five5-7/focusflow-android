package com.sakata.focusflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    }

    @Test
    fun `reminder due inside advance window is scheduled immediately`() {
        val item = Item(id = 5, title = "马上开始", detail = "", kind = "任务", scheduledAt = now + 5 * 60_000L)

        val result = TaskReminderPolicy.nextReminder(listOf(item), ActivityReminderSettings(scheduleAdvanceMinutes = 10), now)

        assertEquals(now + 1_000L, result?.triggerAt)
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
}
