package com.sakata.focusflow

import java.util.Calendar

/** 睡前减速：提醒、明日早课感知与熬夜恢复提示。只依据用户填写的睡觉锚点与已确认课程。 */
object WindDownInsights {
    /** 睡前提前多少分钟开始减速。 */
    const val WIND_DOWN_MINUTES = 40

    data class Advice(
        val message: String,
        val note: String? = null,
        val tomorrowText: String? = null,
        /** note 为警示语义（如明早有课需注意休息）时置 true，UI 用警示色显示。 */
        val alert: Boolean = false
    )

    /** 星期索引（1=周一..7=周日）。 */
    private fun weekdayIndex(calendar: Calendar): Int =
        when (calendar.get(Calendar.DAY_OF_WEEK)) { Calendar.SUNDAY -> 7 else -> calendar.get(Calendar.DAY_OF_WEEK) - 1 }

    /** 减速开始分钟（某星期的睡觉锚点前 40 分钟，跨午夜归一化；默认今天）。 */
    fun windDownMinute(profile: BaselineProfile, weekday: Int = weekdayIndex(Calendar.getInstance())): Int? {
        val sleep = profile.sleepMinuteFor(weekday)
        return if (sleep in 0 until 24 * 60) (sleep - WIND_DOWN_MINUTES + 24 * 60) % (24 * 60) else null
    }

    /** 明日（自然日）0 点到后天 0 点之间的已安排未完成任务。 */
    private fun tomorrowTasks(items: List<Item>, tomorrowStart: Long, dayAfterStart: Long): List<Item> =
        items.filter { !it.done && it.scheduledAt != null && it.scheduledAt in tomorrowStart until dayAfterStart }

    /**
     * 当前睡前提示：减速前 4 小时提醒、减速中、熬夜恢复；
     * 附带明日早课（第 1–2 节有课）或可稍晚收尾的信息，并结合签到/活动历史识别深夜活跃与娱乐习惯。
     * 太早时返回 null。减速与熬夜阶段同时生成明日准备信息（明日课数、任务与待整理项）。
     */
    fun advice(
        profile: BaselineProfile,
        courses: List<Course>,
        items: List<Item>,
        checkIns: List<StatusCheckIn> = emptyList(),
        activityHistory: List<ActivitySession> = emptyList(),
        now: Long = System.currentTimeMillis()
    ): Advice? {
        val calendar = Calendar.getInstance().apply { timeInMillis = now }
        val sleep = profile.sleepMinuteFor(weekdayIndex(calendar))
        if (sleep !in 0 until 24 * 60) return null
        val windDown = windDownMinute(profile, weekdayIndex(calendar)) ?: return null
        val todayMinute = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)

        val message = when {
            todayMinute in windDown..sleep -> "已到睡前减速时间：按你的习惯 ${formatMinute(sleep)} 睡觉，现在开始收尾。"
            todayMinute > sleep -> "已过睡觉锚点 ${formatMinute(sleep)}。现在开始减速，明天优先安排低强度任务。"
            todayMinute >= windDown - 4 * 60 -> "距离睡前减速还有 ${windDown - todayMinute} 分钟（睡觉锚点 ${formatMinute(sleep)}）。"
            else -> return null
        }

        var day = when (calendar.get(Calendar.DAY_OF_WEEK)) { Calendar.SUNDAY -> 7 else -> calendar.get(Calendar.DAY_OF_WEEK) - 1 }
        val tomorrowWeekday = (day % 7) + 1
        val tomorrowConfirmed = courses.filter { !it.needsConfirmation && it.weekday == tomorrowWeekday }
        val firstClass = tomorrowConfirmed.minByOrNull { it.startPeriod }
        val earlyClassTomorrow = firstClass != null && firstClass.startPeriod <= 2
        val notes = mutableListOf<String>()
        // 睡眠习惯：最近深夜（22 点后）仍活跃的次数 ≥3 次时给出警示性建议。
        val lateNightCount = LifestyleInsights.lateNightActiveCount(checkIns, activityHistory, now = now)
        if (lateNightCount >= 3) notes += "你最近 $lateNightCount 次在深夜（22 点后）仍活跃，今晚建议比平时更早收尾。"
        when {
            earlyClassTomorrow -> notes += "明天 ${formatMinute(CourseGapPlanner.periodStart(firstClass.startPeriod))} 有课，注意休息。"
            todayMinute < windDown && (firstClass == null || firstClass.startPeriod >= 3) -> notes += "明天上午没有早课，今晚可以稍晚一点收尾。"
        }
        // 娱乐习惯：当前时段恰逢常见娱乐时段（样本 ≥3 次）时提醒留出收尾时间。
        LifestyleInsights.typicalEntertainmentPeriod(activityHistory)?.let { hour ->
            if (todayMinute in (hour * 60) until (hour * 60 + 120)) {
                notes += "现在这个时段你常安排娱乐（${hour}:00 前后），记得留出收尾时间。"
            }
        }
        val note = notes.joinToString(" ").takeIf { it.isNotBlank() }

        val tomorrowText = if (todayMinute >= windDown) {
            val tomorrowStart = Calendar.getInstance().apply { timeInMillis = now }.let { cal ->
                cal.add(Calendar.DAY_OF_YEAR, 1)
                cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
                cal.timeInMillis
            }
            val dayAfterStart = tomorrowStart + 24 * 60 * 60 * 1000L
            val tomorrowTasks = tomorrowTasks(items, tomorrowStart, dayAfterStart)
            val pendingInbox = items.count { !it.done && it.kind == "收集箱" }
            buildString {
                if (tomorrowConfirmed.isNotEmpty()) {
                    append("明天 ${tomorrowConfirmed.size} 门课")
                    firstClass?.let { append("：最早 ${it.title} ${formatMinute(CourseGapPlanner.periodStart(it.startPeriod))}") }
                } else append("明天没有已确认课程")
                if (tomorrowTasks.isNotEmpty()) {
                    append(" · 任务 ${tomorrowTasks.take(2).joinToString("、") { it.title }}")
                    if (tomorrowTasks.size > 2) append(" 等 ${tomorrowTasks.size} 个")
                } else append(" · 明日任务 0 个")
                if (pendingInbox > 0) append(" · 收集箱还有 $pendingInbox 项待整理")
            }
        } else null
        return Advice(message, note, tomorrowText, alert = earlyClassTomorrow || lateNightCount >= 3)
    }

    fun formatMinute(minute: Int): String = "%02d:%02d".format(minute / 60, minute % 60)
}
