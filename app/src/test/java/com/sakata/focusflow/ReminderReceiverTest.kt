package com.sakata.focusflow

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = FocusFlowApplication::class)
class ReminderReceiverTest {
    private fun freshContext(): Context {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("focusflow", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("course_reminder_settings", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("course_location_overrides", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("standalone_reminders", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSystemService(NotificationManager::class.java).cancelAll()
        Shadows.shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        return context
    }

    private fun notificationManagerOf(context: Context) =
        Shadows.shadowOf(context.getSystemService(NotificationManager::class.java))

    private fun alarmShadowOf(context: Context) =
        Shadows.shadowOf(context.getSystemService(AlarmManager::class.java))

    private fun awaitUntil(timeoutMs: Long = 3_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline && !condition()) Thread.sleep(20)
        assertTrue("condition not met within ${timeoutMs}ms", condition())
    }

    private fun settle() {
        Thread.sleep(300)
    }

    private data class DueCourse(val id: Long, val expectedAt: Long, val startAt: Long)

    /**
     * 种一门“刚好到点”的课：实际开始时间取当前分钟（提前量已过去 ≈5 分钟，属于接收端要处理的正常迟到广播）。
     *
     * 节次表必须始终合法（`0 ≤ startMinute < endMinute ≤ 1440`，见 `CoursePeriodTable.isValid`），
     * 而接收端按 `periodStart(1)` 反查开始时间，所以首节必须正好落在课次开始分钟上。
     * 因此当“当前分钟 + 45”会越过午夜时，改为把课次日期挪到昨天、开始时间锚定在 23:15：
     * 既保证 `endMinute = 1440` 合法，又让星期几与 45 分钟窗口仍然自洽。
     * （此前用 `minuteNow + 5` 直接算，23:10–23:54 会生成非法表；次日 00:20 之后又会因夹断而查不到开始时间。）
     */
    private fun seedDueCourse(context: Context, id: Long = 911L, building: String = "东一"): DueCourse {
        val zone = ZoneId.systemDefault()
        val minuteNow = Calendar.getInstance().let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }
        val today = LocalDate.now(zone)
        val anchorStart = 23 * 60 + 15
        val wrapsPastMidnight = minuteNow + 45 > 24 * 60
        val startMinute = if (wrapsPastMidnight) anchorStart else minuteNow
        val courseDate = if (wrapsPastMidnight) today.minusDays(1) else today
        val startAt = courseDate.atTime(startMinute / 60, startMinute % 60).atZone(zone).toInstant().toEpochMilli()
        val expectedAt = startAt - CourseReminderPolicy.ADVANCE_MINUTES * 60_000L
        val course = Course("实验", courseDate.dayOfWeek.value, 1, 1, building, CampusZone.OTHER,
            needsConfirmation = false, id = id)
        val store = PrototypeStore(context)
        store.saveCourses(listOf(course))
        store.saveCoursePeriodTable(
            CoursePeriodTable(listOf(CoursePeriodTime(startMinute, minOf(startMinute + 45, 24 * 60))))
        )
        assertTrue(CourseReminders.setGlobal(context, true))
        return DueCourse(id, expectedAt, startAt)
    }

    private fun courseIntent(expectedAt: Long, id: Long = 911L): Intent = Intent().apply {
        action = ReminderReceiver.ACTION_COURSE_DUE
        putExtra(ReminderReceiver.EXTRA_COURSE_ID, id)
        putExtra(ReminderReceiver.EXTRA_COURSE_TRIGGER_AT, expectedAt)
    }

    private fun standaloneDueIntent(id: Long, at: Long): Intent = Intent().apply {
        action = ReminderReceiver.ACTION_STANDALONE_DUE
        putExtra(ReminderReceiver.EXTRA_STANDALONE_ID, id)
        putExtra(ReminderReceiver.EXTRA_STANDALONE_AT, at)
    }

    @Test fun `due course notification uses title time and location then restores`() {
        val context = freshContext()
        val due = seedDueCourse(context)

        ReminderReceiver().onReceive(context, courseIntent(due.expectedAt))

        val shadowNotifications = notificationManagerOf(context)
        awaitUntil { shadowNotifications.getNotification("course:${due.id}", 0) != null }
        val notification = requireNotNull(shadowNotifications.getNotification("course:${due.id}", 0))
        assertEquals("实验", notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        val time = Instant.ofEpochMilli(due.startAt).atZone(ZoneId.systemDefault()).toLocalTime()
        assertEquals(
            "%02d:%02d 开始 · 东一".format(time.hour, time.minute),
            notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        )
        assertEquals("focusflow_course_reminders_v1", notification.channelId)
        assertTrue(alarmShadowOf(context).scheduledAlarms.isNotEmpty())
    }

    @Test fun `blank building falls back to a pending-location label`() {
        val context = freshContext()
        val due = seedDueCourse(context, building = "")

        ReminderReceiver().onReceive(context, courseIntent(due.expectedAt))

        val shadowNotifications = notificationManagerOf(context)
        awaitUntil { shadowNotifications.getNotification("course:${due.id}", 0) != null }
        val notification = requireNotNull(shadowNotifications.getNotification("course:${due.id}", 0))
        assertTrue(notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString().endsWith("· 地点待确认"))
    }

    @Test fun `temporary location overrides the base building`() {
        val context = freshContext()
        val due = seedDueCourse(context)
        val day = Instant.ofEpochMilli(due.startAt).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay()
        assertTrue(CourseLocationOverrides.set(context, due.id, day, "临时教室 3"))

        ReminderReceiver().onReceive(context, courseIntent(due.expectedAt))

        val shadowNotifications = notificationManagerOf(context)
        awaitUntil { shadowNotifications.getNotification("course:${due.id}", 0) != null }
        val notification = requireNotNull(shadowNotifications.getNotification("course:${due.id}", 0))
        assertEquals(
            "%02d:%02d 开始 · 临时教室 3".format(
                Instant.ofEpochMilli(due.startAt).atZone(ZoneId.systemDefault()).toLocalTime().hour,
                Instant.ofEpochMilli(due.startAt).atZone(ZoneId.systemDefault()).toLocalTime().minute
            ),
            notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        )
    }

    @Test fun `early exits neither notify nor reschedule`() {
        val context = freshContext()
        val due = seedDueCourse(context)
        val receiver = ReminderReceiver()

        receiver.onReceive(context, courseIntent(due.expectedAt, id = 0L))
        receiver.onReceive(context, courseIntent(0L))
        receiver.onReceive(context, courseIntent(System.currentTimeMillis() + 60_000L))
        settle()

        assertNull(notificationManagerOf(context).getNotification("course:${due.id}", 0))
        assertEquals(0, alarmShadowOf(context).scheduledAlarms.size)
    }

    @Test fun `late due broadcast restores without notifying`() {
        val context = freshContext()
        val due = seedDueCourse(context)

        ReminderReceiver().onReceive(context, courseIntent(due.expectedAt - 31 * 60_000L))

        awaitUntil { alarmShadowOf(context).scheduledAlarms.isNotEmpty() }
        assertNull(notificationManagerOf(context).getNotification("course:${due.id}", 0))
    }

    @Test fun `unknown course broadcast only restores`() {
        val context = freshContext()
        val due = seedDueCourse(context)

        ReminderReceiver().onReceive(context, courseIntent(due.expectedAt, id = 999L))

        awaitUntil { alarmShadowOf(context).scheduledAlarms.isNotEmpty() }
        assertNull(notificationManagerOf(context).getNotification("course:999", 0))
    }

    @Test fun `denied permission reschedules without notifying or marking delivered`() {
        val context = freshContext()
        val due = seedDueCourse(context)
        Shadows.shadowOf(context as Application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        ReminderReceiver().onReceive(context, courseIntent(due.expectedAt))

        awaitUntil { alarmShadowOf(context).scheduledAlarms.isNotEmpty() }
        assertNull(notificationManagerOf(context).getNotification("course:${due.id}", 0))
        assertTrue(CourseReminders.markNotified(context, due.id, due.expectedAt))
    }

    @Test fun `muted due broadcast still restores before the mute check`() {
        val context = freshContext()
        val due = seedDueCourse(context)
        PrototypeStore(context).saveQuietHoursSettings(
            QuietHoursSettings(muteUntil = System.currentTimeMillis() + 10 * 60_000L)
        )

        ReminderReceiver().onReceive(context, courseIntent(due.expectedAt))

        awaitUntil { alarmShadowOf(context).scheduledAlarms.isNotEmpty() }
        assertNull(notificationManagerOf(context).getNotification("course:${due.id}", 0))
        assertTrue(CourseReminders.markNotified(context, due.id, due.expectedAt))
    }

    @Test fun `standalone due shows complete and snooze actions`() {
        val context = freshContext()
        val now = System.currentTimeMillis()
        assertTrue(StandaloneReminders.create(context, "开会", now - 5_000L, now - 60_000L))
        val entry = StandaloneReminders.all(context).single()

        ReminderReceiver().onReceive(context, standaloneDueIntent(entry.id, entry.triggerAt))

        val shadowNotifications = notificationManagerOf(context)
        awaitUntil { shadowNotifications.getNotification("standalone:${entry.id}", 0) != null }
        val notification = requireNotNull(shadowNotifications.getNotification("standalone:${entry.id}", 0))
        assertEquals("开会", notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("你设置的提醒已到时间。", notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals(2, notification.actions.size)
    }

    @Test fun `muted standalone due consumes delivery before skipping the notification`() {
        val context = freshContext()
        val now = System.currentTimeMillis()
        assertTrue(StandaloneReminders.create(context, "开会", now - 5_000L, now - 60_000L))
        val entry = StandaloneReminders.all(context).single()
        PrototypeStore(context).saveQuietHoursSettings(
            QuietHoursSettings(muteUntil = now + 10 * 60_000L)
        )

        ReminderReceiver().onReceive(context, standaloneDueIntent(entry.id, entry.triggerAt))

        awaitUntil { StandaloneReminders.all(context).single().deliveredAt != null }
        assertNull(notificationManagerOf(context).getNotification("standalone:${entry.id}", 0))
    }

    @Test fun `denied permission keeps standalone reminder undelivered`() {
        val context = freshContext()
        val now = System.currentTimeMillis()
        assertTrue(StandaloneReminders.create(context, "开会", now - 5_000L, now - 60_000L))
        val entry = StandaloneReminders.all(context).single()
        Shadows.shadowOf(context as Application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        ReminderReceiver().onReceive(context, standaloneDueIntent(entry.id, entry.triggerAt))
        settle()

        assertNull(notificationManagerOf(context).getNotification("standalone:${entry.id}", 0))
        assertNull(StandaloneReminders.all(context).single().deliveredAt)
    }

    @Test fun `snooze can land inside an active mute window and keeps the original time`() {
        val context = freshContext()
        val now = System.currentTimeMillis()
        assertTrue(StandaloneReminders.create(context, "开会", now - 5_000L, now - 60_000L))
        val entry = StandaloneReminders.all(context).single()
        PrototypeStore(context).saveQuietHoursSettings(
            QuietHoursSettings(enabled = true, muteUntil = now + 10 * 60_000L)
        )
        val delivered = requireNotNull(StandaloneReminders.markDelivered(context, entry.id, entry.triggerAt, now))
        assertTrue(StandaloneReminders.snooze(context, entry.id, entry.triggerAt, delivered.deliveredAt!!, minutes = 5, now = now))

        val snoozed = StandaloneReminders.all(context).single()
        assertEquals(entry.triggerAt, snoozed.triggerAt)
        assertNull(snoozed.deliveredAt)
        assertNotNull(snoozed.snoozedUntil)
        assertTrue(PrototypeStore(context).loadQuietHoursSettings().isMuted(snoozed.scheduledAt))
    }
}
