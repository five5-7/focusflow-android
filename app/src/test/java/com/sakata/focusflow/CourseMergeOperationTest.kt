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
class CourseMergeOperationTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private fun courses() = listOf(
        Course("实验", 1, 1, 2, "A", CampusZone.WEST_TEACHING,
            needsConfirmation = false, effectiveFromEpochDay = 100, effectiveUntilEpochDay = 200, id = 10),
        Course("实验", 1, 1, 2, "B", CampusZone.WEST_TEACHING,
            needsConfirmation = false, effectiveFromEpochDay = 201, effectiveUntilEpochDay = 300, id = 20)
    )

    @Test fun `manual merge carries locations and reminder override to the surviving id`() {
        val repository = LegacyCoreDataRepository(PrototypeStore(context))
        val before = courses()
        assertTrue(repository.replaceCourses(before, emptyList()).applied)
        assertTrue(CourseLocationOverrides.set(context, 10, 120, "原地点"))
        assertTrue(CourseLocationOverrides.set(context, 20, 220, "新地点"))
        assertTrue(CourseReminders.setOverride(context, 20, true))
        assertTrue(CourseReminders.markNotified(context, 20, 1_800_000_000_000L))
        val plan = CourseMergeOperation.preview(context, before, 20)
            as CourseEditPlans.CourseMergePlan.Applied

        assertEquals(CourseMergeOperation.Outcome.APPLIED,
            CourseMergeOperation.apply(context, repository, before, setOf(10, 20), 20, plan))
        assertEquals(listOf(10L), (repository.read() as CoreDataReadResult.Ready).snapshot.courses.map { it.id })
        assertEquals(mapOf(120L to "原地点", 220L to "新地点"), CourseLocationOverrides.snapshot(context, 10))
        assertTrue(CourseLocationOverrides.snapshot(context, 20).isEmpty())
        assertEquals(true, CourseReminders.load(context).overrides[10])
        assertFalse(CourseReminders.load(context).overrides.containsKey(20))
        assertFalse(CourseReminders.markNotified(context, 10, 1_800_000_000_000L))
        assertTrue(CourseMergeOperation.recover(context, repository))
    }

    @Test fun `failed course write keeps old records and both sets of preferences`() {
        val repository = LegacyCoreDataRepository(PrototypeStore(context))
        val before = courses()
        assertTrue(repository.replaceCourses(before, emptyList()).applied)
        assertTrue(CourseLocationOverrides.set(context, 20, 220, "仍在"))
        val failing = object : CoreDataRepository by repository {
            override fun replaceCourses(courses: List<Course>, expectedCourses: List<Course>) =
                CoreDataWriteResult(CoreDataWriteStatus.WRITE_FAILED)
        }
        val plan = CourseMergeOperation.preview(context, before, 20)
            as CourseEditPlans.CourseMergePlan.Applied

        assertEquals(CourseMergeOperation.Outcome.WRITE_FAILED,
            CourseMergeOperation.apply(context, failing, before, setOf(10, 20), 20, plan))
        assertEquals(before, (repository.read() as CoreDataReadResult.Ready).snapshot.courses)
        assertEquals("仍在", CourseLocationOverrides.get(context, 20, 220))
        assertTrue(CourseMergeOperation.recover(context, repository))
    }

    @Test fun `invalid recovery journal stays visible and blocks another merge`() {
        val repository = LegacyCoreDataRepository(PrototypeStore(context))
        val before = courses()
        assertTrue(repository.replaceCourses(before, emptyList()).applied)
        val prefs = context.getSharedPreferences("course_merge_journal", Context.MODE_PRIVATE)
        assertTrue(prefs.edit().putString("pending", "{damaged").commit())

        assertFalse(CourseMergeOperation.recover(context, repository))
        val plan = CourseMergeOperation.preview(context, before, 20)
            as CourseEditPlans.CourseMergePlan.Applied
        assertEquals(CourseMergeOperation.Outcome.RECOVERY_PENDING,
            CourseMergeOperation.apply(context, repository, before, setOf(10, 20), 20, plan))
        assertEquals(before, (repository.read() as CoreDataReadResult.Ready).snapshot.courses)
        assertEquals("{damaged", prefs.getString("pending", null))
    }

    @Test fun `restart after course commit finishes override migration`() {
        val repository = LegacyCoreDataRepository(PrototypeStore(context))
        val before = courses()
        assertTrue(repository.replaceCourses(before, emptyList()).applied)
        assertTrue(CourseLocationOverrides.set(context, 20, 220, "新地点"))
        assertTrue(CourseReminders.setOverride(context, 20, true))
        assertTrue(CourseReminders.markNotified(context, 20, 1_800_000_000_000L))
        val plan = CourseMergeOperation.preview(context, before, 20)
            as CourseEditPlans.CourseMergePlan.Applied
        val pending = JSONObject().apply {
            put("survivorId", 10)
            put("deletedIds", JSONArray().put(20))
            put("reminderEnabled", true)
            put("locations", JSONArray().put(JSONObject().put("day", 220).put("place", "新地点")))
        }
        val prefs = context.getSharedPreferences("course_merge_journal", Context.MODE_PRIVATE)
        assertTrue(prefs.edit().putString("pending", pending.toString()).commit())
        assertTrue(repository.replaceCourses(listOf(plan.survivingCourse), before).applied)

        assertTrue(CourseMergeOperation.recover(context, repository))
        assertNull(prefs.getString("pending", null))
        assertEquals("新地点", CourseLocationOverrides.get(context, 10, 220))
        assertNull(CourseLocationOverrides.get(context, 20, 220))
        assertEquals(true, CourseReminders.load(context).overrides[10])
        assertFalse(CourseReminders.markNotified(context, 10, 1_800_000_000_000L))
        assertTrue(CourseMergeOperation.recover(context, repository))
    }

    @Test fun `malformed stored override cannot be silently dropped by merge`() {
        val before = courses()
        assertTrue(context.getSharedPreferences("course_location_overrides", Context.MODE_PRIVATE)
            .edit().putInt("20_220", 7).commit())
        val result = CourseMergeOperation.preview(context, before, 20)
        assertEquals(CourseEditPlans.MergeRejectReason.CORRUPT_OVERRIDES,
            (result as CourseEditPlans.CourseMergePlan.Rejected).rejectReason)
        assertEquals(7, context.getSharedPreferences("course_location_overrides", Context.MODE_PRIVATE)
            .getInt("20_220", -1))
    }
}
