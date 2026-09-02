package com.sakata.focusflow

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

internal fun formatDateTime(time: Long): String =
    SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(Date(time))

internal fun formatDate(time: Long): String =
    SimpleDateFormat("M月d日", Locale.CHINA).format(Date(time))

/**
 * 定时任务的持久化文案必须使用绝对日期，不能把“明晚”等相对词保存到第二天。
 * scheduledAt 才是时间真值；这里只修正 FocusFlow 自己生成的改期模板，不碰用户笔记。
 */
object TaskScheduleText {
    fun rescheduledDetail(scheduledAt: Long, durationMinutes: Int): String =
        "已改期至${formatDateTime(scheduledAt)} · ${durationMinutes.coerceIn(5, 360)}分钟；届时会再次出现"

    fun scheduledDetail(scheduledAt: Long, durationMinutes: Int): String =
        "已安排：${formatDateTime(scheduledAt)} · ${durationMinutes.coerceIn(5, 360)} 分钟；可随时改期"

    fun flexibleDetail(startAt: Long, endAt: Long, durationMinutes: Int): String =
        "弹性范围：${formatDateTime(startAt)}–${formatDateTime(endAt)} · 预计 ${durationMinutes.coerceIn(5, 360)} 分钟；尚未锁定具体时刻"

    fun dayOnlyDetail(scheduledAt: Long): String =
        "${formatDate(scheduledAt)}要做 · 尚未安排具体时间"

    fun activityDetail(category: String, scheduledAt: Long, durationMinutes: Int): String =
        "$category 安排 · ${formatDateTime(scheduledAt)}–${formatDateTime(scheduledAt + durationMinutes.coerceIn(5, 360) * 60_000L)}"

    fun canonicalize(item: Item): Item {
        val canonical = when {
            item.scheduledAt != null && item.detail.startsWith("已改期至") && item.detail.endsWith("；届时会再次出现") ->
                rescheduledDetail(item.scheduledAt, item.durationMinutes)
            item.scheduledAt != null && item.dayOnly && item.detail == "明天要做 · 尚未安排具体时间" ->
                dayOnlyDetail(item.scheduledAt)
            item.windowStartAt != null && item.windowEndAt != null && item.detail.startsWith("弹性范围：") && item.detail.endsWith("；尚未锁定具体时刻") ->
                flexibleDetail(item.windowStartAt, item.windowEndAt, item.durationMinutes)
            else -> return item
        }
        return if (item.detail == canonical) item else item.copy(detail = canonical)
    }

    fun eventExtra(event: TaskEvent): String {
        if (event.scheduledAt <= 0L) return event.extra
        if (event.type == TaskEventType.TASK_RESCHEDULED) return formatDateTime(event.scheduledAt)
        if (event.type == TaskEventType.TASK_SCHEDULED && containsRelativeDay(event.extra)) {
            return if (event.extra.startsWith("弹性范围")) "弹性范围 · ${formatDateTime(event.scheduledAt)}"
            else formatDateTime(event.scheduledAt)
        }
        return event.extra
    }

    private fun containsRelativeDay(text: String): Boolean =
        listOf("今天", "明天", "后天", "今晚", "明晚", "明早").any(text::contains)
}

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

/** 某时刻所在自然日的 [start, end) 毫秒范围。 */
internal fun dayRange(millis: Long): LongRange {
    val start = java.util.Calendar.getInstance().apply {
        timeInMillis = millis
        set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis
    return start until (start + 24 * 60 * 60 * 1000L)
}

internal fun weekdayOf(millis: Long): Int {
    val calendar = java.util.Calendar.getInstance().apply { timeInMillis = millis }
    return when (calendar.get(java.util.Calendar.DAY_OF_WEEK)) { java.util.Calendar.SUNDAY -> 7 else -> calendar.get(java.util.Calendar.DAY_OF_WEEK) - 1 }
}
