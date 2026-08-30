package com.sakata.focusflow

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

/** ReminderScheduler 的时间计算纯函数与闹钟降级链（B6 补测网）。 */
class ReminderTimingTest {

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int = 0): Long =
        Calendar.getInstance().apply {
            clear()
            set(year, month - 1, day, hour, minute, second)
        }.timeInMillis

    private fun fieldsAt(epoch: Long): Pair<Int, List<Int>> {
        val c = Calendar.getInstance().apply { timeInMillis = epoch }
        return c.get(Calendar.DAY_OF_MONTH) to listOf(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), c.get(Calendar.SECOND))
    }

    // 2026-01-05 是周一；以下 now 均为本地时区固定值。

    @Test fun nextDailyTriggerAt_staysTodayWhenTargetIsFarAhead() {
        val now = at(2026, 1, 5, 9, 0)
        val (day, fields) = fieldsAt(ReminderScheduler.nextDailyTriggerAt(now, 14))
        assertEquals(5, day)
        assertEquals(listOf(14, 0, 0), fields)
    }

    @Test fun nextDailyTriggerAt_pushesToTomorrowWhenTargetPassed() {
        val now = at(2026, 1, 5, 15, 0)
        val (day, fields) = fieldsAt(ReminderScheduler.nextDailyTriggerAt(now, 14))
        assertEquals(6, day)
        assertEquals(listOf(14, 0, 0), fields)
    }

    @Test fun nextDailyTriggerAt_withinGraceWindowPushesToTomorrow() {
        // 14:00 前 60 秒整：≤ now+60s → 推次日；前 61 秒仍取今天。
        val atGrace = at(2026, 1, 5, 13, 59, 0)
        assertEquals(6, fieldsAt(ReminderScheduler.nextDailyTriggerAt(atGrace, 14)).first)
        val justBefore = at(2026, 1, 5, 13, 58, 59)
        assertEquals(5, fieldsAt(ReminderScheduler.nextDailyTriggerAt(justBefore, 14)).first)
    }

    @Test fun nextDailyTriggerAt_coercesHourIntoValidRange() {
        val now = at(2026, 1, 5, 9, 0)
        assertEquals(listOf(23, 0, 0), fieldsAt(ReminderScheduler.nextDailyTriggerAt(now, 25)).second)
        assertEquals(listOf(0, 0, 0), fieldsAt(ReminderScheduler.nextDailyTriggerAt(now, -3)).second)
    }

    @Test fun todayAtMinute_usesGivenNow() {
        val now = at(2026, 1, 5, 10, 45)
        val (day, fields) = fieldsAt(ReminderScheduler.todayAtMinute(540, now))
        assertEquals(5, day)
        assertEquals(listOf(9, 0, 0), fields)
    }

    @Test fun todayAtMinute_rollsToProperHourForMinutes() {
        val now = at(2026, 1, 5, 10, 45)
        val (day, fields) = fieldsAt(ReminderScheduler.todayAtMinute(90, now))
        assertEquals(5, day)
        assertEquals(listOf(1, 30, 0), fields)
    }

    @Test fun nextDayAtMinute_returnsTomorrowAtMinute() {
        val (day, fields) = fieldsAt(ReminderScheduler.nextDayAtMinute(5))
        val today = Calendar.getInstance().get(Calendar.DAY_OF_MONTH)
        val expected = if (today == 31) 1 else today + 1
        assertEquals(expected, day)
        assertEquals(listOf(0, 5, 0), fields)
    }

    @Test fun mealEndTriggerAt_coercesMinutesRange() {
        val startedAt = 10_000L
        assertEquals(10_000L + 60 * 60_000L, ReminderScheduler.mealEndTriggerAt(startedAt, 60))
        assertEquals(10_000L + 120 * 60_000L, ReminderScheduler.mealEndTriggerAt(startedAt, 500))
        assertEquals(10_000L + 5 * 60_000L, ReminderScheduler.mealEndTriggerAt(startedAt, 2))
    }

    @Test fun timingChain_prefersAlarmClockThenExactThenInexact() {
        assertEquals(
            listOf(AlarmDeliveryMode.ALARM_CLOCK, AlarmDeliveryMode.EXACT, AlarmDeliveryMode.INEXACT),
            ReminderScheduler.timingChain(preferAlarmClock = true, sdkInt = 33, canScheduleExactAlarms = true)
        )
    }

    @Test fun timingChain_skipsAlarmClockWhenNotPreferred() {
        assertEquals(
            listOf(AlarmDeliveryMode.EXACT, AlarmDeliveryMode.INEXACT),
            ReminderScheduler.timingChain(preferAlarmClock = false, sdkInt = 33, canScheduleExactAlarms = true)
        )
    }

    @Test fun timingChain_inexactOnlyWithoutExactPermission() {
        assertEquals(
            listOf(AlarmDeliveryMode.INEXACT),
            ReminderScheduler.timingChain(preferAlarmClock = false, sdkInt = 33, canScheduleExactAlarms = false)
        )
    }

    @Test fun timingChain_stillTriesAlarmClockWithoutExactPermission() {
        assertEquals(
            listOf(AlarmDeliveryMode.ALARM_CLOCK, AlarmDeliveryMode.INEXACT),
            ReminderScheduler.timingChain(preferAlarmClock = true, sdkInt = 33, canScheduleExactAlarms = false)
        )
    }
}
