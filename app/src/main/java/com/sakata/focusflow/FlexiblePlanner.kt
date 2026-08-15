package com.sakata.focusflow

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

data class FlexibleTimeSuggestion(
    val startsAt: Long,
    val durationMinutes: Int,
    val reason: String
)

object FlexiblePlanner {
    private const val DAY_START = 6 * 60
    private const val DAY_END = 24 * 60
    private const val BUFFER = 15

    fun suggestions(
        item: Item,
        items: List<Item>,
        courses: List<Course>,
        energyLevel: String,
        now: Long = System.currentTimeMillis()
    ): List<FlexibleTimeSuggestion> {
        val duration = item.durationMinutes.coerceIn(5, 360)
        val preferredMinutes = when (energyLevel) {
            "偏低" -> listOf(10 * 60 + 30, 15 * 60, 19 * 60)
            "充足" -> listOf(9 * 60, 14 * 60, 18 * 60)
            else -> listOf(9 * 60 + 30, 14 * 60 + 30, 19 * 60)
        }
        return (0..6).mapNotNull { dayOffset ->
            val day = startOfDay(now, dayOffset)
            val weekday = weekday(day)
            val occupied = buildList {
                courses.filter { !it.needsConfirmation && it.weekday == weekday }.forEach { course ->
                    add(CourseGapPlanner.periodStart(course.startPeriod) to CourseGapPlanner.periodEnd(course.endPeriod))
                }
                items.filter { other -> other.id != item.id && !other.done && other.scheduledAt?.let { sameDay(it, day) } == true }.forEach { other ->
                    val start = minuteOfDay(requireNotNull(other.scheduledAt))
                    add(start to (start + other.durationMinutes.coerceIn(5, 360)))
                }
            }
            val earliest = if (dayOffset == 0) maxOf(DAY_START, roundUpToHalfHour(minuteOfDay(now) + 15)) else DAY_START
            val candidates = (earliest..(DAY_END - duration) step 30).filter { start ->
                val end = start + duration
                val candidateStart = atMinute(day, start)
                val candidateEnd = candidateStart + duration * 60_000L
                val insideWindow = (item.windowStartAt == null || candidateStart >= item.windowStartAt) && (item.windowEndAt == null || candidateEnd <= item.windowEndAt)
                insideWindow && occupied.none { (busyStart, busyEnd) -> start < busyEnd + BUFFER && end > busyStart - BUFFER }
            }
            val best = candidates.minByOrNull { start -> preferredMinutes.minOf { preferred -> kotlin.math.abs(start - preferred) } } ?: return@mapNotNull null
            val startsAt = atMinute(day, best)
            FlexibleTimeSuggestion(
                startsAt = startsAt,
                durationMinutes = duration,
                reason = "${display(startsAt)} · 预计 $duration 分钟；与固定安排保留 $BUFFER 分钟缓冲"
            )
        }.take(3)
    }

    private fun startOfDay(now: Long, offset: Int): Long = Calendar.getInstance().apply {
        timeInMillis = now
        add(Calendar.DAY_OF_YEAR, offset)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun atMinute(day: Long, minute: Int): Long = Calendar.getInstance().apply {
        timeInMillis = day
        set(Calendar.HOUR_OF_DAY, minute / 60)
        set(Calendar.MINUTE, minute % 60)
    }.timeInMillis

    private fun sameDay(time: Long, day: Long): Boolean {
        val left = Calendar.getInstance().apply { timeInMillis = time }
        val right = Calendar.getInstance().apply { timeInMillis = day }
        return left.get(Calendar.YEAR) == right.get(Calendar.YEAR) && left.get(Calendar.DAY_OF_YEAR) == right.get(Calendar.DAY_OF_YEAR)
    }

    private fun weekday(time: Long): Int = Calendar.getInstance().apply { timeInMillis = time }.get(Calendar.DAY_OF_WEEK).let {
        if (it == Calendar.SUNDAY) 7 else it - 1
    }

    private fun minuteOfDay(time: Long): Int = Calendar.getInstance().apply { timeInMillis = time }.let {
        it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE)
    }

    private fun roundUpToHalfHour(minute: Int): Int = ((minute + 29) / 30) * 30

    private fun display(time: Long): String = SimpleDateFormat("E M月d日 HH:mm", Locale.CHINA).format(Date(time))
}
