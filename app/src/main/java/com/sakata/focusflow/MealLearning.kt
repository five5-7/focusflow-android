package com.sakata.focusflow

import java.util.Calendar

/** 消费记录暂时隐藏（7.0）：入口与金额输入不展示，数据保留不清除；恢复时改回 false。 */
const val EXPENSE_HIDDEN = true

/** 一餐实际开始后创建；endedAt 由“吃完了吗”确认补齐。金额与评价始终可选，只作为 v4 消费草稿，不会自动生成账目。 */
data class MealRecord(
    val id: Long = newItemId(),
    val mealType: MealType,
    val lifeStage: String = "",
    val startedAt: Long,
    val endedAt: Long? = null,
    val location: String = "",
    val category: String = "",
    val merchant: String = "",
    val amount: Int = -1,
    val payMethod: String = "",
    val rating: Int = 0,
    val note: String = "",
    val recordedAt: Long = System.currentTimeMillis()
)

/** 某餐今天的计划：优先学习值，数据不足时退回用户填写的基线，且明确标记未学习。 */
data class MealPlan(
    val startMinute: Int,
    val minutes: Int,
    val learned: Boolean,
    val sampleCount: Int
)

/** 吃完时可选填写的消费草稿；3.x 只保存草稿，不会自动生成账目或推断金额。 */
data class MealDraft(
    val amount: Int = -1,
    val rating: Int = 0,
    val note: String = "",
    val location: String = "",
    val category: String = "",
    val merchant: String = "",
    val payMethod: String = ""
)

object MealLearning {
    private const val MAX_SAMPLES = 8
    private const val MIN_SAMPLES = 3

    /** 阶段 × 星期 × 餐次，取最近确认开始时间的中位数；不足 3 次返回 null，不假装精确预测。 */
    fun predictedStartMinute(records: List<MealRecord>, lifeStage: LifeStage?, weekday: Int, mealType: MealType): Int? =
        samples(records, lifeStage, weekday, mealType).takeIf { it.size >= MIN_SAMPLES }?.let { medianMinutes(it) }

    /** 同一组合下的时长中位数（由确认的开始与结束时间计算）。 */
    fun predictedMinutes(records: List<MealRecord>, lifeStage: LifeStage?, weekday: Int, mealType: MealType): Int? {
        val durations = samples(records, lifeStage, weekday, mealType).mapNotNull { record ->
            record.endedAt?.takeIf { it >= record.startedAt }
                ?.let { ((it - record.startedAt) / 60_000L).toInt().coerceIn(5, 120) }
        }
        return durations.takeIf { it.size >= MIN_SAMPLES }?.let { median(it) }
    }

    fun todayPlan(records: List<MealRecord>, profile: BaselineProfile, weekday: Int, mealType: MealType): MealPlan {
        val learnedStart = predictedStartMinute(records, profile.lifeStage, weekday, mealType)
        val learnedMinutes = predictedMinutes(records, profile.lifeStage, weekday, mealType)
        // 按星期分组作息优先（如周五≈周末、周末晚起），无分组用主锚点。
        val timeline = profile.mealsFor(weekday).firstOrNull { it.type == mealType }
        val count = samples(records, profile.lifeStage, weekday, mealType).size
        return if (learnedStart != null) {
            MealPlan(learnedStart, learnedMinutes ?: timeline?.typicalMinutes ?: 20, true, count)
        } else {
            MealPlan(timeline?.typicalStartMinute ?: defaultStartMinute(mealType), timeline?.typicalMinutes ?: 20, false, count)
        }
    }

    /** 今天的该餐是否已开始（有当天确认记录），用于避免重复提醒。 */
    fun startedToday(records: List<MealRecord>, weekdayNow: Long, mealType: MealType): Boolean =
        records.any { it.mealType == mealType && sameDay(it.startedAt, weekdayNow) }

    /** 该餐是否有仍在进行中的记录（开始后未结束）。 */
    fun latestOpen(records: List<MealRecord>, mealType: MealType): MealRecord? =
        records.filter { it.mealType == mealType && it.endedAt == null }.maxByOrNull { it.startedAt }

    /** 该餐最近一次记录了地点的地方；没有记录时返回 null。 */
    fun recentLocation(records: List<MealRecord>, mealType: MealType): String? =
        records.filter { it.mealType == mealType && it.location.isNotBlank() }.maxByOrNull { it.startedAt }?.location

    fun defaultStartMinute(type: MealType): Int = when (type) {
        MealType.BREAKFAST -> 8 * 60 + 30
        MealType.LUNCH -> 12 * 60
        MealType.DINNER -> 18 * 60
    }

    fun sameDay(a: Long, b: Long): Boolean {
        val ca = Calendar.getInstance().apply { timeInMillis = a }
        val cb = Calendar.getInstance().apply { timeInMillis = b }
        return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR) && ca.get(Calendar.DAY_OF_YEAR) == cb.get(Calendar.DAY_OF_YEAR)
    }

    fun dayKey(timestamp: Long): String =
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA).format(java.util.Date(timestamp))

    private fun samples(records: List<MealRecord>, lifeStage: LifeStage?, weekday: Int, mealType: MealType): List<MealRecord> =
        records
            .filter { it.mealType == mealType && it.lifeStage == (lifeStage?.storageKey ?: "") }
            .filter { Calendar.getInstance().apply { timeInMillis = it.startedAt }.get(Calendar.DAY_OF_WEEK) == weekday }
            .sortedByDescending { it.startedAt }
            .take(MAX_SAMPLES)

    private fun medianMinutes(records: List<MealRecord>): Int {
        val minutes = records.map { record ->
            Calendar.getInstance().apply { timeInMillis = record.startedAt }.let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }
        }
        return median(minutes)
    }

    private fun median(values: List<Int>): Int {
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2
    }
}

/** 饭点广播和通知按钮只对当天、当前仍有效的记录生效。 */
object MealReminderFreshness {
    fun promptAllowed(
        enabled: Boolean,
        profileReady: Boolean,
        expectedAt: Long,
        now: Long,
        alreadyStarted: Boolean,
        skippedToday: Boolean
    ): Boolean = enabled && profileReady && !alreadyStarted && !skippedToday &&
        (expectedAt <= 0L || MealLearning.sameDay(expectedAt, now))

    fun endAllowed(enabled: Boolean, record: MealRecord?, expectedRecordId: Long, now: Long): Boolean =
        enabled && record != null && record.endedAt == null && MealLearning.sameDay(record.startedAt, now) &&
            (expectedRecordId <= 0L || expectedRecordId == record.id)
}
