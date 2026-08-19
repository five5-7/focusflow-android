package com.sakata.focusflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class MealLearningTest {
    private val school = LifeStage.SCHOOL.storageKey // "school"
    private fun at(hour: Int, minute: Int = 0): Long =
        Calendar.getInstance().apply { clear(); set(2026, 0, 5, hour, minute, 0) }.timeInMillis // 周一

    private fun record(id: Long, mealType: MealType, lifeStage: String, startedAt: Long, durationMin: Int? = null) = MealRecord(
        id = id, mealType = mealType, lifeStage = lifeStage, startedAt = startedAt,
        endedAt = durationMin?.let { startedAt + it * 60_000L }
    )

    @Test fun defaultStartMinute() {
        assertEquals(8 * 60 + 30, MealLearning.defaultStartMinute(MealType.BREAKFAST))
        assertEquals(12 * 60, MealLearning.defaultStartMinute(MealType.LUNCH))
        assertEquals(18 * 60, MealLearning.defaultStartMinute(MealType.DINNER))
    }

    @Test fun predictedStartMinute_median() {
        val records = listOf(
            record(1, MealType.LUNCH, school, at(12, 0)),
            record(2, MealType.LUNCH, school, at(12, 15)),
            record(3, MealType.LUNCH, school, at(12, 30))
        )
        assertEquals(12 * 60 + 15, MealLearning.predictedStartMinute(records, LifeStage.SCHOOL, Calendar.MONDAY, MealType.LUNCH))
    }

    @Test fun predictedStartMinute_needsThreeSamples() {
        val records = listOf(
            record(1, MealType.LUNCH, school, at(12, 0)),
            record(2, MealType.LUNCH, school, at(12, 15))
        )
        assertNull(MealLearning.predictedStartMinute(records, LifeStage.SCHOOL, Calendar.MONDAY, MealType.LUNCH))
    }

    @Test fun predictedMinutes_median() {
        val records = listOf(
            record(1, MealType.LUNCH, school, at(12, 0), durationMin = 20),
            record(2, MealType.LUNCH, school, at(12, 0), durationMin = 30),
            record(3, MealType.LUNCH, school, at(12, 0), durationMin = 40)
        )
        assertEquals(30, MealLearning.predictedMinutes(records, LifeStage.SCHOOL, Calendar.MONDAY, MealType.LUNCH))
    }

    @Test fun todayPlan_noData_usesDefault() {
        val profile = BaselineProfile(lifeStage = LifeStage.SCHOOL)
        val plan = MealLearning.todayPlan(emptyList(), profile, Calendar.MONDAY, MealType.LUNCH)
        assertFalse(plan.learned)
        assertEquals(12 * 60, plan.startMinute)
    }

    @Test fun latestOpen_and_recentLocation() {
        val open = record(1, MealType.LUNCH, school, at(12, 0)) // endedAt = null
        val closed = record(2, MealType.LUNCH, school, at(11, 0), durationMin = 20).copy(location = "一食堂")
        val latest = MealLearning.latestOpen(listOf(closed, open), MealType.LUNCH)
        assertEquals(1L, latest?.id)
        assertEquals("一食堂", MealLearning.recentLocation(listOf(closed, open), MealType.LUNCH))
    }

    @Test fun startedToday_and_sameDay() {
        val today = record(1, MealType.LUNCH, school, at(12, 0))
        assertTrue(MealLearning.startedToday(listOf(today), at(15, 0), MealType.LUNCH))
        assertTrue(MealLearning.sameDay(at(9, 0), at(23, 0)))
    }
}
