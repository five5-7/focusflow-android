package com.sakata.focusflow

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

internal fun formatDateTime(time: Long): String =
    SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(Date(time))

internal fun formatMinute(minute: Int): String =
    "%02d:%02d".format(minute / 60, minute % 60)

internal fun todayWeekday(): Int = calendarWeekday(Calendar.getInstance())

internal fun todayWeekday(time: Long): Int =
    calendarWeekday(Calendar.getInstance().apply { timeInMillis = time })

internal fun minuteOfDay(time: Long): Int = Calendar.getInstance().apply {
    timeInMillis = time
}.let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }

internal fun isInCurrentWeek(time: Long): Boolean {
    val weekStart = GoalPlanner.currentWeekKey()
    val weekEnd = weekStart + 7 * 24 * 60 * 60_000L
    return time >= weekStart && time < weekEnd
}

internal fun isToday(time: Long): Boolean {
    val target = Calendar.getInstance().apply { timeInMillis = time }
    val today = Calendar.getInstance()
    return target.get(Calendar.YEAR) == today.get(Calendar.YEAR) &&
        target.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)
}

internal fun weekdayName(day: Int): String =
    listOf("", "周一", "周二", "周三", "周四", "周五", "周六", "周日")[day]

private fun calendarWeekday(calendar: Calendar): Int =
    when (calendar.get(Calendar.DAY_OF_WEEK)) {
        Calendar.SUNDAY -> 7
        else -> calendar.get(Calendar.DAY_OF_WEEK) - 1
    }
