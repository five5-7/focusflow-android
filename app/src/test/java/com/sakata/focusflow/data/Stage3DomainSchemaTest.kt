package com.sakata.focusflow.data

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sakata.focusflow.ActivitySession
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class Stage3DomainSchemaTest {
    private lateinit var database: FocusFlowDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, FocusFlowDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `recurrence rule and occurrence preserve local calendar semantics`() {
        val rule = RecurrenceRuleEntity(
            id = 10L,
            templateTaskId = 20L,
            frequency = "weekly",
            interval = 2,
            daysOfWeekMask = 0b0010101,
            startsOnEpochDay = 21_000L,
            endsOnEpochDay = 21_090L,
            localTimeMinutes = 8 * 60 + 30,
            timeZoneId = "Asia/Shanghai",
            enabled = true,
            createdAt = 100L,
            updatedAt = 200L
        )
        val occurrence = TaskOccurrenceEntity(
            id = 30L,
            recurrenceRuleId = rule.id,
            templateTaskId = rule.templateTaskId,
            materializedTaskId = 40L,
            occurrenceEpochDay = 21_014L,
            scheduledAt = 1_815_000_000_000L,
            status = "rescheduled",
            completedAt = null,
            skippedAt = null,
            rescheduledTo = 1_815_003_600_000L,
            createdAt = 300L,
            updatedAt = 400L
        )

        database.recurrenceRuleDao().insertAll(listOf(rule))
        database.taskOccurrenceDao().insertAll(listOf(occurrence))

        assertEquals(listOf(rule), database.recurrenceRuleDao().all())
        assertEquals(listOf(occurrence), database.taskOccurrenceDao().all())
    }

    @Test
    fun `one recurrence rule cannot create two occurrences for the same local date`() {
        fun occurrence(id: Long) = TaskOccurrenceEntity(
            id = id,
            recurrenceRuleId = 10L,
            templateTaskId = 20L,
            materializedTaskId = null,
            occurrenceEpochDay = 21_014L,
            scheduledAt = 1_815_000_000_000L,
            status = "pending",
            completedAt = null,
            skippedAt = null,
            rescheduledTo = null,
            createdAt = 300L,
            updatedAt = 300L
        )
        database.taskOccurrenceDao().insertAll(listOf(occurrence(1L)))

        assertThrows(Exception::class.java) {
            database.taskOccurrenceDao().insertAll(listOf(occurrence(2L)))
        }
        assertEquals(listOf(1L), database.taskOccurrenceDao().allIds())
    }

    @Test
    fun `activity session survives Room round trip without losing legacy fields`() {
        val legacy = ActivitySession(
            id = 50L,
            name = "复盘",
            category = "学习",
            plannedStartAt = 1_000L,
            actualStartAt = 1_100L,
            endsAt = 4_000L,
            nextStep = "记录结论",
            status = ActivitySession.STATUS_COMPLETED,
            extensionCount = 2,
            extensionReason = "补充证据",
            actualEndAt = 3_900L,
            endChoice = "完成"
        )
        val entity = ActivitySessionEntity.fromLegacy(legacy, sourceOrder = 7)

        database.activitySessionDao().insertAll(listOf(entity))

        assertEquals(listOf(entity), database.activitySessionDao().all())
        assertEquals(legacy, database.activitySessionDao().all().single().toLegacy())
    }
}
