package com.sakata.focusflow

import java.util.Calendar

/** 个人账目分析：只统计用户填写的消费草稿金额；数据不足时只显示已积累的部分，不预测、不推断。 */
object ExpenseInsights {
    data class LocationStat(val name: String, val count: Int, val averageAmount: Int)

    data class ExpenseSummary(
        val withAmountCount: Int,
        val totalAmount: Int,
        val monthRecords: Int,
        val monthAmount: Int,
        val topCategories: List<Pair<String, Int>>,
        val topLocations: List<LocationStat>,
        val mealTypeAmounts: List<Pair<String, Int>>
    )

    fun summarize(records: List<MealRecord>, now: Long = System.currentTimeMillis()): ExpenseSummary {
        val calendar = Calendar.getInstance().apply { timeInMillis = now }
        val monthStart = calendar.clone() as Calendar
        monthStart.set(Calendar.DAY_OF_MONTH, 1)
        monthStart.set(Calendar.HOUR_OF_DAY, 0); monthStart.set(Calendar.MINUTE, 0); monthStart.set(Calendar.SECOND, 0); monthStart.set(Calendar.MILLISECOND, 0)
        val inMonth: (Long) -> Boolean = { it in monthStart.timeInMillis until now }

        val withAmount = records.filter { it.amount > 0 }
        val topCategories = withAmount.filter { it.category.isNotBlank() }
            .groupBy { it.category }.mapValues { (_, list) -> list.sumOf { it.amount } }
            .entries.sortedByDescending { it.value }.take(3).map { it.key to it.value }
        val topLocations = withAmount.filter { it.location.isNotBlank() }
            .groupBy { it.location }.mapValues { (_, list) ->
                LocationStat(list.first().location, list.size, list.sumOf { it.amount } / list.size)
            }
            .entries.sortedByDescending { it.value.count }.take(2)
            .map { it.value }
        val mealTypeAmounts = withAmount.groupBy { it.mealType.label }
            .mapValues { (_, list) -> list.sumOf { it.amount } }
            .entries.sortedByDescending { it.value }.map { it.key to it.value }

        return ExpenseSummary(
            withAmountCount = withAmount.size,
            totalAmount = withAmount.sumOf { it.amount },
            monthRecords = records.count { inMonth(it.startedAt) },
            monthAmount = withAmount.filter { inMonth(it.startedAt) }.sumOf { it.amount },
            topCategories = topCategories,
            topLocations = topLocations,
            mealTypeAmounts = mealTypeAmounts
        )
    }
}
