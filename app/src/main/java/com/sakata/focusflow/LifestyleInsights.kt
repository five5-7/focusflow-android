package com.sakata.focusflow

import java.util.Calendar

/** 从签到与活动历史识别睡眠/娱乐习惯，让睡前减速更贴合实际。数据不足时不给结论（不假装精确）。 */
object LifestyleInsights {
    private fun hourOf(millis: Long): Int = Calendar.getInstance().apply { timeInMillis = millis }.get(Calendar.HOUR_OF_DAY)

    private fun isLateNight(millis: Long): Boolean {
        val hour = hourOf(millis)
        return hour >= 22 || hour < 2
    }

    /** 最近 days 天内深夜（22:00–次日 02:00）的活跃记录数（签到或已开始的活动）。 */
    fun lateNightActiveCount(
        checkIns: List<StatusCheckIn>,
        activityHistory: List<ActivitySession>,
        days: Int = 14,
        now: Long = System.currentTimeMillis()
    ): Int {
        val since = now - days * 24 * 3600_000L
        val fromCheckIns = checkIns.count { it.recordedAt >= since && isLateNight(it.recordedAt) }
        val fromActivities = activityHistory.count { it.actualStartAt >= since && isLateNight(it.actualStartAt) && it.status != ActivitySession.STATUS_ACTIVE }
        return fromCheckIns + fromActivities
    }

    /** 娱乐习惯时段：活动记录中“娱乐”类目最常开始的小时；样本 ≥3 次才返回。 */
    fun typicalEntertainmentPeriod(activityHistory: List<ActivitySession>): Int? {
        val hours = activityHistory.filter { it.category.contains("娱乐") }.map { hourOf(it.actualStartAt) }
        if (hours.size < 3) return null
        return hours.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
    }
}
