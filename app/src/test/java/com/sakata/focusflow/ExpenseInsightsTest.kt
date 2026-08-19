package com.sakata.focusflow

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

class ExpenseInsightsTest {
    private val day = 24 * 3600_000L
    private fun mealRecord(id: Long, amount: Int, category: String, location: String, startedAt: Long) = MealRecord(
        id = id, mealType = MealType.LUNCH, startedAt = startedAt, amount = amount, category = category, location = location
    )

    @Test fun summarize_totalsAndTopCategory() {
        val now = Calendar.getInstance().apply { clear(); set(2026, 0, 15, 12, 0, 0) }.timeInMillis
        val records = listOf(
            mealRecord(1, 1500, "食堂", "一食堂", now - 3 * day),
            mealRecord(2, 2000, "外卖", "宿舍", now - 2 * day),
            mealRecord(3, 0, "食堂", "一食堂", now - 1 * day) // 无金额不计入
        )
        val s = ExpenseInsights.summarize(records, now)
        assertEquals(2, s.withAmountCount)
        assertEquals(3500, s.totalAmount)
        assertEquals("外卖" to 2000, s.topCategories.first())
    }

    @Test fun summarize_topLocations_average() {
        val now = Calendar.getInstance().apply { clear(); set(2026, 0, 15, 12, 0, 0) }.timeInMillis
        val records = listOf(
            mealRecord(1, 1000, "食堂", "一食堂", now - 2 * day),
            mealRecord(2, 2000, "食堂", "一食堂", now - 1 * day),
            mealRecord(3, 500, "外卖", "宿舍", now - 3 * day)
        )
        val s = ExpenseInsights.summarize(records, now)
        val top = s.topLocations.first()
        assertEquals("一食堂", top.name)
        assertEquals(2, top.count)
        assertEquals(1500, top.averageAmount)
    }
}
