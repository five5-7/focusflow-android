package com.sakata.focusflow

import java.util.Calendar

/** 从签到记录总结时段精力规律与建议。数据不足时不给出具体建议（与应用“不假装精确”的原则一致）。 */
object CheckInInsights {
    data class SlotStat(
        val label: String,
        val count: Int,
        val energyCounts: Map<String, Int>,
        val topActivity: String?
    ) {
        val bestEnergy: String? get() = energyCounts.maxByOrNull { it.value }?.key
    }

    private val slotRanges = listOf(
        Triple("上午", 6, 12), Triple("下午", 12, 18), Triple("晚上", 18, 24)
    )

    /** 按时段汇总签到：次数、精力分布、最常见活动（“空闲”不计入活动）。 */
    fun slotStats(checkIns: List<StatusCheckIn>): List<SlotStat> =
        slotRanges.mapNotNull { (label, start, end) ->
            val list = checkIns.filter { hourOf(it.recordedAt) in start until end }
            if (list.isEmpty()) return@mapNotNull null
            SlotStat(
                label = label,
                count = list.size,
                energyCounts = list.groupingBy { it.energy }.eachCount(),
                topActivity = list.filter { it.activity != "空闲" }.groupingBy { it.activity }.eachCount().maxByOrNull { it.value }?.key
            )
        }

    /** 当前时段的精力建议；签到不足 3 次或该时段样本太少时不建议。 */
    fun currentSlotAdvice(checkIns: List<StatusCheckIn>, now: Long = System.currentTimeMillis()): String? {
        if (checkIns.size < 3) return null
        val hour = hourOf(now)
        val range = slotRanges.firstOrNull { hour in it.second until it.third } ?: return null
        val stat = slotStats(checkIns).firstOrNull { it.label == range.first } ?: return null
        if (stat.count < 2) return null
        return when (stat.bestEnergy) {
            "偏低" -> "现在这个时段（${stat.label}）你最近签到多为“偏低”，建议优先安排短任务。"
            "充足" -> "现在这个时段（${stat.label}）你最近签到多为“充足”，适合安排需要专注的任务。"
            else -> null
        }
    }

    /** 建议的每日询问时刻：签到时间的小时中位数，落在 8–22 点之间；签到不足 3 次时不建议。 */
    fun suggestedPromptHour(checkIns: List<StatusCheckIn>): Int? {
        if (checkIns.size < 3) return null
        val hours = checkIns.map { hourOf(it.recordedAt) }.sorted()
        return hours[hours.size / 2].coerceIn(8, 22)
    }

    /** 给定时刻的典型精力标签（按签到数据的该时段最高频精力）；样本不足返回 null。 */
    fun slotEnergyFor(minuteOfDay: Int, checkIns: List<StatusCheckIn>): String? {
        if (checkIns.size < 3) return null
        val hour = minuteOfDay / 60
        val range = slotRanges.firstOrNull { hour in it.second until it.third } ?: return null
        val stat = slotStats(checkIns).firstOrNull { it.label == range.first } ?: return null
        if (stat.count < 2) return null
        return stat.bestEnergy
    }

    private fun hourOf(millis: Long): Int = Calendar.getInstance().apply { timeInMillis = millis }.get(Calendar.HOUR_OF_DAY)
}
