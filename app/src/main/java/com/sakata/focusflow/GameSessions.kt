package com.sakata.focusflow

/** 一次空闲活动安排：计划开始/结束、实际结束与是否按时（前台应用检测与自律统计的依据）。 */
data class GameSessionRecord(
    val id: Long = System.currentTimeMillis(),
    val title: String,               // 做什么（如 “原神” / “复习高数”）
    val category: String = "游戏",   // 游戏/视频/学习/休息/运动/自定义；游戏与视频走前台检测
    val packageName: String?,        // 指定的应用包名（可选，用于精准检测）
    val plannedStartAt: Long,
    val plannedEndAt: Long,
    val actualEndAt: Long? = null,   // null = 尚未记录实际结束
    val endedOnTime: Boolean = false,
    val overrunMinutes: Int = 0,
    val remindStart: Boolean = false // 到点是否提醒开始（可选；结束提醒始终有）
) {
    fun isOpen(): Boolean = actualEndAt == null
}

/** 空闲活动自律统计：只做数据式汇总，不假装精确。 */
object GameStats {
    fun thisWeek(records: List<GameSessionRecord>, weekKey: Long = GoalPlanner.currentWeekKey()): List<GameSessionRecord> =
        records.filter { it.plannedStartAt >= weekKey && it.plannedStartAt < weekKey + 7 * 24 * 60 * 60 * 1000L }

    /** 汇总文案；本周无记录返回 null。 */
    fun summary(records: List<GameSessionRecord>, weekKey: Long = GoalPlanner.currentWeekKey()): String? {
        val week = thisWeek(records, weekKey)
        if (week.isEmpty()) return null
        val onTime = week.count { it.endedOnTime }
        val overrun = week.sumOf { it.overrunMinutes }
        val open = week.count { it.isOpen() }
        val base = "本周活动安排 ${week.size} 次 · 按时结束 $onTime 次 · 累计超时 $overrun 分钟"
        return if (open > 0) "$base · $open 次尚未记录结束" else base
    }

    /** 数据式建议：超时集中时段、整体表现。数据不足时不给具体建议。 */
    fun advice(records: List<GameSessionRecord>, weekKey: Long = GoalPlanner.currentWeekKey()): String? {
        val week = thisWeek(records, weekKey)
        if (week.size < 2) return null
        val overrunCount = week.count { it.overrunMinutes >= 10 }
        val onTimeCount = week.count { it.endedOnTime }
        if (overrunCount == 0) return "本周活动安排都能按时收尾，继续保持。"
        if (onTimeCount == 0) return "本周活动安排都超时了；建议把安排时长缩短 15 分钟，或把开始时间提前。"
        return "本周有 $overrunCount 次超时（共 ${week.sumOf { it.overrunMinutes }} 分钟）。可把活动安排提前或缩短，收尾前 10 分钟先保存进度。"
    }

    /** 结束一个打开会话的结果记录：按实际结束时间计算是否按时与超时分钟；非打开会话返回 null。 */
    fun endedSession(record: GameSessionRecord, now: Long = System.currentTimeMillis()): GameSessionRecord? {
        if (!record.isOpen()) return null
        val overrun = ((now - record.plannedEndAt) / 60_000L).toInt().coerceAtLeast(0)
        return record.copy(actualEndAt = now, endedOnTime = overrun == 0, overrunMinutes = overrun)
    }

    /** 某活动标题的历史单日最多安排次数（按计划开始日分组；至少 3 天样本，否则返回 null——不假装精确）。 */
    fun historicalDailyMax(records: List<GameSessionRecord>, title: String): Int? {
        val counts = records.filter { it.title == title }
            .groupBy { dayKey(it.plannedStartAt) }
            .mapValues { it.value.size }
            .values
        if (counts.size < 3) return null
        return counts.maxOrNull()
    }

    private fun dayKey(millis: Long): Long {
        val c = java.util.Calendar.getInstance().apply { timeInMillis = millis }
        c.set(java.util.Calendar.HOUR_OF_DAY, 0); c.set(java.util.Calendar.MINUTE, 0); c.set(java.util.Calendar.SECOND, 0); c.set(java.util.Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }
}
