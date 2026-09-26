package com.sakata.focusflow

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
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
}
