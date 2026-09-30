package com.sakata.focusflow

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sakata.focusflow.data.CoreDataReadResult
import com.sakata.focusflow.data.CoreDataRepository
import com.sakata.focusflow.data.CoreDataWriteResult
import com.sakata.focusflow.data.CoreDataWriteStatus
import com.sakata.focusflow.data.LegacyCoreDataRepository
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CourseSplitOperationTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private fun course() = Course("实验", 1, 1, 2, "A", CampusZone.WEST_TEACHING,
        needsConfirmation = false, effectiveFromEpochDay = 100, effectiveUntilEpochDay = 300, id = 10)

    @Test fun `following edit keeps earlier meeting and moves future overrides to new identity`() {
        val repository = LegacyCoreDataRepository(PrototypeStore(context))
        val original = course()
        assertTrue(repository.replaceCourses(listOf(original), emptyList()).applied)
        assertTrue(CourseLocationOverrides.set(context, 10, 120, "原地点"))
        assertTrue(CourseLocationOverrides.set(context, 10, 220, "后续地点"))
        assertTrue(CourseReminders.setOverride(context, 10, false))
        assertTrue(CourseReminders.markNotified(context, 10, 1_800_000_000_000L))

        assertEquals(CourseSplitOperation.Outcome.APPLIED,
            CourseSplitOperation.apply(context, repository, listOf(original), original,
                original.copy(building = "B"), 200))
        val records = (repository.read() as CoreDataReadResult.Ready).snapshot.courses
        assertEquals(2, records.size)
        assertEquals(199L, records[0].effectiveUntilEpochDay)
        assertEquals(10L, records[0].id)
        assertEquals(200L, records[1].effectiveFromEpochDay)
        assertEquals("B", records[1].building)
        assertNotEquals(10L, records[1].id)
        assertEquals("原地点", CourseLocationOverrides.get(context, 10, 120))
        assertNull(CourseLocationOverrides.get(context, 10, 220))
        assertEquals("后续地点", CourseLocationOverrides.get(context, records[1].id, 220))
        assertEquals(false, CourseReminders.load(context).overrides[records[1].id])
        assertTrue(CourseReminders.markNotified(context, records[1].id, 1_800_000_000_000L))
        assertTrue(CourseSplitOperation.recover(context, repository))
    }

    @Test fun `failed course CAS leaves locations and reminder untouched`() {
        val repository = LegacyCoreDataRepository(PrototypeStore(context))
        val original = course()
        assertTrue(repository.replaceCourses(listOf(original), emptyList()).applied)
        assertTrue(CourseLocationOverrides.set(context, 10, 220, "原地点"))
        val failing = object : CoreDataRepository by repository {
            override fun replaceCourses(courses: List<Course>, expectedCourses: List<Course>) =
                CoreDataWriteResult(CoreDataWriteStatus.WRITE_FAILED)
        }
        assertEquals(CourseSplitOperation.Outcome.WRITE_FAILED,
            CourseSplitOperation.apply(context, failing, listOf(original), original, original, 200))
        assertEquals(listOf(original), (repository.read() as CoreDataReadResult.Ready).snapshot.courses)
        assertEquals("原地点", CourseLocationOverrides.get(context, 10, 220))
        assertTrue(CourseSplitOperation.recover(context, repository))
    }

    @Test fun `invalid journal blocks split and remains available for repair`() {
        val repository = LegacyCoreDataRepository(PrototypeStore(context))
        val original = course()
        assertTrue(repository.replaceCourses(listOf(original), emptyList()).applied)
        val prefs = context.getSharedPreferences("course_split_journal", Context.MODE_PRIVATE)
        assertTrue(prefs.edit().putString("pending", "{damaged").commit())
        assertFalse(CourseSplitOperation.recover(context, repository))
        assertEquals(CourseSplitOperation.Outcome.RECOVERY_PENDING,
            CourseSplitOperation.apply(context, repository, listOf(original), original, original, 200))
        assertEquals("{damaged", prefs.getString("pending", null))
    }

    @Test fun `restart after course split commit migrates future preferences`() {
        val repository = LegacyCoreDataRepository(PrototypeStore(context))
        val original = course()
        assertTrue(repository.replaceCourses(listOf(original), emptyList()).applied)
        assertTrue(CourseLocationOverrides.set(context, 10, 220, "后续地点"))
        assertTrue(CourseReminders.setOverride(context, 10, true))
        val prefs = context.getSharedPreferences("course_split_journal", Context.MODE_PRIVATE)
        val journal = JSONObject().apply {
            put("originalId", 10)
            put("successorId", 20)
            put("boundary", 200)
            put("reminderEnabled", true)
            put("futureLocations", JSONArray().put(JSONObject().put("day", 220).put("place", "后续地点")))
        }
        assertTrue(prefs.edit().putString("pending", journal.toString()).commit())
        assertTrue(repository.replaceCourses(listOf(original.copy(effectiveUntilEpochDay = 199),
            original.copy(id = 20, effectiveFromEpochDay = 200)), listOf(original)).applied)

        assertTrue(CourseSplitOperation.recover(context, repository))
        assertNull(prefs.getString("pending", null))
        assertNull(CourseLocationOverrides.get(context, 10, 220))
        assertEquals("后续地点", CourseLocationOverrides.get(context, 20, 220))
        assertEquals(true, CourseReminders.load(context).overrides[20])
        assertTrue(CourseSplitOperation.recover(context, repository))
    }
}
