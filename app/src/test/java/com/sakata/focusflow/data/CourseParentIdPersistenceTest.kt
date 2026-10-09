package com.sakata.focusflow.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sakata.focusflow.CampusZone
import com.sakata.focusflow.Course
import com.sakata.focusflow.PrototypeStore
import com.sakata.focusflow.StorageProtection
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Regression guard for the identity that joins separate meeting rows into one course.
 *
 * A fresh store instance models the read side after process recreation. Losing this field
 * would make an explicitly grouped course appear split again after restart.
 */
@RunWith(RobolectricTestRunner::class)
class CourseParentIdPersistenceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun clearPreferences() {
        context.getSharedPreferences("focusflow", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        StorageProtection.retry()
    }

    @Test
    fun `parent course ids survive a fresh store instance`() {
        val meetings = listOf(
            course(id = 101L, courseId = 700L, title = "线性代数"),
            course(id = 102L, courseId = 700L, title = "线性代数").copy(weekday = 3)
        )
        val repository = LegacyCoreDataRepository(PrototypeStore(context))

        assertEquals(
            CoreDataWriteStatus.APPLIED,
            repository.replaceCourses(meetings, emptyList()).status
        )

        val reloaded = PrototypeStore(context).loadCourses().sortedBy { it.id }
        assertEquals(listOf(101L, 102L), reloaded.map { it.id })
        assertEquals(listOf(700L, 700L), reloaded.map { it.courseId })
    }

    private fun course(id: Long, courseId: Long, title: String) = Course(
        title = title,
        weekday = 1,
        startPeriod = 1,
        endPeriod = 2,
        building = "东一",
        zone = CampusZone.EAST_TEACHING,
        needsConfirmation = false,
        enabled = true,
        id = id,
        courseId = courseId
    )
}
