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
class CourseLocationOverridesTest {
    @Test fun `corrupted and blank stored values read as null`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("course_location_overrides", Context.MODE_PRIVATE)
        prefs.edit().clear()
            .putInt("771_20000", 5)
            .putString("771_20001", "   ")
            .commit()

        assertNull(CourseLocationOverrides.get(context, 771L, 20_000L))
        assertNull(CourseLocationOverrides.get(context, 771L, 20_001L))
    }

    @Test fun `second set overwrites and stores trimmed text`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("course_location_overrides", Context.MODE_PRIVATE).edit().clear().commit()

        assertTrue(CourseLocationOverrides.set(context, 771L, 20_000L, "  临时教室 3  "))
        assertEquals("临时教室 3", CourseLocationOverrides.get(context, 771L, 20_000L))
        assertTrue(CourseLocationOverrides.set(context, 771L, 20_000L, "机房 5"))
        assertEquals("机房 5", CourseLocationOverrides.get(context, 771L, 20_000L))
        assertTrue(CourseLocationOverrides.set(context, 771L, 20_000L, "   "))
        assertNull(CourseLocationOverrides.get(context, 771L, 20_000L))
    }

    @Test fun `length boundary accepts 100 characters and rejects 101`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("course_location_overrides", Context.MODE_PRIVATE).edit().clear().commit()

        assertTrue(CourseLocationOverrides.set(context, 771L, 20_000L, "教".repeat(100)))
        assertEquals(100, CourseLocationOverrides.get(context, 771L, 20_000L)?.length)
        assertFalse(CourseLocationOverrides.set(context, 771L, 20_001L, "教".repeat(101)))
        assertNull(CourseLocationOverrides.get(context, 771L, 20_001L))
        assertFalse(CourseLocationOverrides.set(context, 0L, 20_000L, "教室"))
        assertFalse(CourseLocationOverrides.set(context, 771L, -1L, "教室"))
    }
}
