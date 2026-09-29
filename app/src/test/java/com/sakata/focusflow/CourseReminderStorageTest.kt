package com.sakata.focusflow

import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CourseReminderStorageTest {
    @Test fun `global switch override and one-off location survive reload`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("course_reminder_settings", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("course_location_overrides", Context.MODE_PRIVATE).edit().clear().commit()
        val course = Course("实验", 1, 1, 1, "东一", CampusZone.OTHER,
            needsConfirmation = false, id = 771L)
        assertFalse(CourseReminders.load(context).enabledFor(course))
        assertTrue(CourseReminders.setGlobal(context, true))
        assertTrue(CourseReminders.setOverride(context, course.id, false))
        assertFalse(CourseReminders.load(context).enabledFor(course))
        assertTrue(CourseReminders.setOverride(context, course.id, null))
        assertTrue(CourseReminders.load(context).enabledFor(course))
        assertTrue(CourseLocationOverrides.set(context, course.id, 20_000L, "临时教室 3"))
        assertEquals("临时教室 3", CourseLocationOverrides.get(context, course.id, 20_000L))
        assertNull(CourseLocationOverrides.get(context, course.id, 20_007L))
        assertTrue(CourseLocationOverrides.set(context, course.id, 20_000L, null))
        assertNull(CourseLocationOverrides.get(context, course.id, 20_000L))
    }

    @Test fun `duplicate due broadcast cannot notify twice`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("course_reminder_settings", Context.MODE_PRIVATE).edit().clear().commit()
        assertTrue(CourseReminders.markNotified(context, 771L, 1_000L))
        assertFalse(CourseReminders.markNotified(context, 771L, 1_000L))
        assertFalse(CourseReminders.markNotified(context, 771L, 999L))
        assertTrue(CourseReminders.markNotified(context, 771L, 2_000L))
    }

    @Test fun `unconfirmed period table never schedules reference-time course alarm`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("focusflow", Context.MODE_PRIVATE).edit()
            .remove("course_period_table").commit()
        val course = Course("实验", 1, 1, 1, "东一", CampusZone.OTHER,
            needsConfirmation = false, id = 987654321L)
        CourseReminders.sync(context, emptyList(), listOf(course), CoursePeriodTable.reference(),
            CourseReminderSettings(enabled = true))
        val pending = PendingIntent.getBroadcast(context, 0,
            Intent(context, ReminderReceiver::class.java).apply {
                action = ReminderReceiver.ACTION_COURSE_DUE
                data = Uri.parse("focusflow://course/reminder/${course.id}")
            }, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
        assertNull(pending)
    }

    @Test fun `delivered marker is isolated per meeting id`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("course_reminder_settings", Context.MODE_PRIVATE).edit().clear().commit()
        assertTrue(CourseReminders.markNotified(context, 771L, 1_000L))
        assertTrue(CourseReminders.markNotified(context, 772L, 1_000L))
        assertFalse(CourseReminders.markNotified(context, 771L, 1_000L))
        assertFalse(CourseReminders.markNotified(context, 772L, 1_000L))
        assertTrue(CourseReminders.markNotified(context, 771L, 2_000L))
        assertTrue(CourseReminders.markNotified(context, 772L, 2_000L))
    }

    @Test fun `markNotified rejects non-positive ids and timestamps`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("course_reminder_settings", Context.MODE_PRIVATE).edit().clear().commit()
        assertFalse(CourseReminders.markNotified(context, 0L, 1_000L))
        assertFalse(CourseReminders.markNotified(context, -5L, 1_000L))
        assertFalse(CourseReminders.markNotified(context, 771L, 0L))
        assertFalse(CourseReminders.markNotified(context, 771L, -1L))
        assertTrue(CourseReminders.markNotified(context, 771L, 1_000L))
        assertFalse(CourseReminders.markNotified(context, 771L, 1_000L))
        assertFalse(CourseReminders.markNotified(context, 771L, 999L))
        assertFalse(CourseReminders.markNotified(context, 0L, 2_000L))
        assertFalse(CourseReminders.markNotified(context, 771L, 1_000L))
    }

    @Test fun `load keeps valid overrides and ignores malformed keys`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("course_reminder_settings", Context.MODE_PRIVATE).edit().clear()
            .putBoolean("meeting_771", true)
            .putBoolean("meeting_772", false)
            .putBoolean("meeting_abc", true)
            .putBoolean("meeting_772x", true)
            .putString("meeting_773", "yes")
            .putBoolean("delivered_771", true)
            .commit()
        val settings = CourseReminders.load(context)
        assertEquals(setOf(771L, 772L), settings.overrides.keys)
        assertTrue(settings.enabledFor(Course("实验", 1, 1, 1, "东一", CampusZone.OTHER, needsConfirmation = false, id = 771L)))
        assertFalse(settings.enabledFor(Course("实验", 1, 1, 1, "东一", CampusZone.OTHER, needsConfirmation = false, id = 772L)))
        assertFalse(settings.enabled)
    }

    @Test fun `location-only resync keeps trigger time and meeting id`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("course_reminder_settings", Context.MODE_PRIVATE).edit().clear().commit()
        val zone = ZoneId.systemDefault()
        val tomorrow = LocalDate.now(zone).plusDays(1)
        val table = CoursePeriodTable(listOf(CoursePeriodTime(8 * 60, 8 * 60 + 45)))
        val course = Course("实验", tomorrow.dayOfWeek.value, 1, 1, "东一", CampusZone.OTHER,
            needsConfirmation = false, id = 601L)
        PrototypeStore(context).saveCoursePeriodTable(table)
        val shadow = Shadows.shadowOf(context.getSystemService(AlarmManager::class.java))

        CourseReminders.sync(context, emptyList(), listOf(course), table, CourseReminderSettings(enabled = true))
        val before = shadow.scheduledAlarms.single().triggerAtMs
        val moved = course.copy(building = "西二")
        CourseReminders.sync(context, listOf(course), listOf(moved), table, CourseReminderSettings(enabled = true))

        val alarm = shadow.scheduledAlarms.single()
        assertEquals(before, alarm.triggerAtMs)
        val saved = Shadows.shadowOf(alarm.operation).savedIntent
        assertEquals(601L, saved.getLongExtra(ReminderReceiver.EXTRA_COURSE_ID, -1L))
    }

    @Test fun `same meeting id drops the old alarm and reschedules after a period change`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("course_reminder_settings", Context.MODE_PRIVATE).edit().clear().commit()
        val zone = ZoneId.systemDefault()
        val tomorrow = LocalDate.now(zone).plusDays(1)
        val tableA = CoursePeriodTable(listOf(CoursePeriodTime(8 * 60, 8 * 60 + 45)))
        val tableB = CoursePeriodTable(listOf(CoursePeriodTime(9 * 60, 9 * 60 + 45)))
        val course = Course("实验", tomorrow.dayOfWeek.value, 1, 1, "东一", CampusZone.OTHER,
            needsConfirmation = false, id = 501L)
        val firstTrigger = tomorrow.atTime(7, 50).atZone(zone).toInstant().toEpochMilli()
        val secondTrigger = tomorrow.atTime(8, 50).atZone(zone).toInstant().toEpochMilli()
        val shadow = Shadows.shadowOf(context.getSystemService(AlarmManager::class.java))

        PrototypeStore(context).saveCoursePeriodTable(tableA)
        CourseReminders.sync(context, emptyList(), listOf(course), tableA, CourseReminderSettings(enabled = true))
        val first = shadow.scheduledAlarms.single()
        assertEquals(firstTrigger, first.triggerAtMs)

        PrototypeStore(context).saveCoursePeriodTable(tableB)
        CourseReminders.sync(context, listOf(course), listOf(course), tableB, CourseReminderSettings(enabled = true))
        val rescheduled = shadow.scheduledAlarms
        assertEquals(1, rescheduled.size)
        assertEquals(secondTrigger, rescheduled.single().triggerAtMs)
    }
}
