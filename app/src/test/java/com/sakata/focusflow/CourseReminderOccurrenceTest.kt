package com.sakata.focusflow

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = FocusFlowApplication::class)
class CourseReminderOccurrenceTest {
    private fun freshContext(): Context {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("course_reminder_settings", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("focusflow", Context.MODE_PRIVATE).edit().clear().commit()
        return context
    }

    @Test fun `currentOccurrence only matches exact trigger and required state`() {
        val context = freshContext()
        val zone = ZoneId.systemDefault()
        val monday = LocalDate.of(2026, 10, 5)
        val table = CoursePeriodTable(listOf(CoursePeriodTime(8 * 60, 8 * 60 + 45)))
        val course = Course("实验", 1, 1, 1, "东一", CampusZone.OTHER, needsConfirmation = false, id = 901L)
        PrototypeStore(context).saveCourses(listOf(course))
        PrototypeStore(context).saveCoursePeriodTable(table)
        assertTrue(CourseReminders.setGlobal(context, true))
        val expectedAt = monday.atTime(7, 50).atZone(zone).toInstant().toEpochMilli()
        val start = monday.atTime(8, 0).atZone(zone).toInstant().toEpochMilli()

        assertNull(CourseReminders.currentOccurrence(context, 902L, expectedAt))
        assertNull(CourseReminders.currentOccurrence(context, 901L, expectedAt - 5 * 60_000L))
        assertNull(CourseReminders.currentOccurrence(context, 901L, expectedAt - 1L))
        assertNull(CourseReminders.currentOccurrence(context, 901L, expectedAt + 1L))
        val occurrence = CourseReminders.currentOccurrence(context, 901L, expectedAt)
        assertNotNull(occurrence)
        assertEquals(901L, requireNotNull(occurrence).first.id)
        assertEquals(start, occurrence.second)

        assertTrue(CourseReminders.setOverride(context, 901L, false))
        assertNull(CourseReminders.currentOccurrence(context, 901L, expectedAt))
        assertTrue(CourseReminders.setOverride(context, 901L, true))

        context.getSharedPreferences("focusflow", Context.MODE_PRIVATE).edit().remove("course_period_table").commit()
        assertNull(CourseReminders.currentOccurrence(context, 901L, expectedAt))
    }

    @Test fun `currentOccurrence accepts an adjacent-day lookback for early morning alarms`() {
        val context = freshContext()
        val zone = ZoneId.systemDefault()
        val monday = LocalDate.of(2026, 10, 5)
        val table = CoursePeriodTable(listOf(CoursePeriodTime(0, 45)))
        val course = Course("晨读", 1, 1, 1, "东一", CampusZone.OTHER, needsConfirmation = false, id = 903L)
        PrototypeStore(context).saveCourses(listOf(course))
        PrototypeStore(context).saveCoursePeriodTable(table)
        assertTrue(CourseReminders.setGlobal(context, true))

        val sundayNight = monday.atStartOfDay(zone).minusMinutes(10).toInstant().toEpochMilli()
        val start = monday.atStartOfDay(zone).toInstant().toEpochMilli()
        val occurrence = CourseReminders.currentOccurrence(context, 903L, sundayNight)
        assertNotNull(occurrence)
        assertEquals(903L, requireNotNull(occurrence).first.id)
        assertEquals(start, occurrence.second)
    }
}
