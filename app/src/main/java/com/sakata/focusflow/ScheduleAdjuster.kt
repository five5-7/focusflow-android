package com.sakata.focusflow

import java.util.Calendar

/** 一项可执行的调整动作；恢复卡"执行建议"按钮按此给出具体处置。 */
enum class AdjustAction(val label: String) {
    POSTPONE("今天稍后"),
    SHRINK("缩短为 15 分钟"),
    TOMORROW("延到明天"),
    BACK_TO_INBOX("放回收集箱"),
    REARRANGE("保留并重新安排")
}

/** 对错过/反复改期候选的一条建议：动作 + 一句话理由 + 目标时间（有则一键执行）。 */
data class DayAdjustment(
    val candidate: RecoveryCandidate,
    val action: AdjustAction,
    val reason: String,
    val targetTime: Long? = null,
    val durationMinutes: Int
)

/**
 * 动态调整一天：由 RecoveryInsights.candidates 出的待恢复项给出可执行建议。
 * 只读、不改动任何业务语义。排序 LOW→MID→HIGH，同级按错过时长降序（先处理最好移走的）。
 * 纯 Kotlin，无 Android 依赖，可单测。
 */
object ScheduleAdjuster {
    /**
     * 每个候选给一条建议（绝不自动执行）：
     * - 活动/游戏（有提醒链，缩短会破坏 plannedEndAt）→ 只给"保留并重新安排"；
     * - 今天有空档 → 移到今天最近空档（保持时长）；
     * - 无空档 → LOW 放回收集箱；MID/HIGH 优先缩短到 15 分钟，实在插不下再延明天。
     */
    fun suggestions(
        candidates: List<RecoveryCandidate>,
        items: List<Item>,
        courses: List<Course>,
        profile: CommuteProfile?,
        now: Long = System.currentTimeMillis()
    ): List<DayAdjustment> =
        candidates.mapNotNull { suggest(it, items, courses, profile, now) }
            .sortedWith(
                compareBy<DayAdjustment> { priorityRank(it.candidate.item.priority) }
                    .thenByDescending { it.candidate.item.scheduledAt?.let { t -> (now - t).coerceAtLeast(0) } ?: 0L }
            )

    fun suggest(
        candidate: RecoveryCandidate,
        items: List<Item>,
        courses: List<Course>,
        profile: CommuteProfile?,
        now: Long = System.currentTimeMillis()
    ): DayAdjustment? {
        val item = candidate.item
        val duration = item.durationMinutes.coerceIn(5, 360)
        if (item.kind == "活动" || item.kind == "游戏") {
            return DayAdjustment(
                candidate, AdjustAction.REARRANGE,
                "属于活动/游戏（有提醒链），不宜缩短或放回；请重新安排时间。",
                durationMinutes = duration
            )
        }
        val weekday = ScheduleOccupation.weekdayOf(now)
        val fromMinute = ScheduleOccupation.minuteOfDay(now)
        val todaySlot = ScheduleOccupation.nextFreeSlot(
            weekday, fromMinute, duration, courses, items, profile, excludeId = item.id
        )
        if (todaySlot != null) {
            return DayAdjustment(
                candidate, AdjustAction.POSTPONE,
                "今天 ${formatClock(todaySlot)} 有空档；保持预计 $duration 分钟移到那里。",
                targetTime = atMinute(now, todaySlot),
                durationMinutes = duration
            )
        }
        return when (ItemPriority.fromKey(item.priority)) {
            ItemPriority.LOW -> DayAdjustment(
                candidate, AdjustAction.BACK_TO_INBOX,
                "今天已无空档；先放回收集箱，之后有空再安排。",
                durationMinutes = duration
            )
            ItemPriority.MID, ItemPriority.HIGH -> {
                val shortSlot = ScheduleOccupation.nextFreeSlot(
                    weekday, fromMinute, ScheduleOccupation.BUFFER_MINUTES,
                    courses, items, profile, excludeId = item.id
                )
                if (shortSlot != null) {
                    DayAdjustment(
                        candidate, AdjustAction.SHRINK,
                        "今天只插得下 15 分钟；缩短为 15 分钟并把提醒改到 ${formatClock(shortSlot)}。",
                        targetTime = atMinute(now, shortSlot),
                        durationMinutes = ScheduleOccupation.BUFFER_MINUTES
                    )
                } else tomorrowAdjustment(candidate, items, courses, profile, now, duration)
            }
        }
    }

    private fun tomorrowAdjustment(
        candidate: RecoveryCandidate,
        items: List<Item>,
        courses: List<Course>,
        profile: CommuteProfile?,
        now: Long,
        duration: Int
    ): DayAdjustment {
        val weekday = ScheduleOccupation.weekdayOf(now)
        val tomorrowWeekday = if (weekday == 7) 1 else weekday + 1
        // 明早 8:00 起找；找不到就给 9:00 兜底（此后时间轴标红提醒）。
        val slot = ScheduleOccupation.nextFreeSlot(
            tomorrowWeekday, 8 * 60, duration, courses, items, profile, excludeId = candidate.item.id
        ) ?: 9 * 60
        return DayAdjustment(
            candidate, AdjustAction.TOMORROW,
            "明天 ${formatClock(slot)} 再做（今天已无合适空档）。",
            targetTime = atMinute(now, slot, dayOffset = 1),
            durationMinutes = duration
        )
    }

    private fun priorityRank(priorityKey: String): Int = when (ItemPriority.fromKey(priorityKey)) {
        ItemPriority.LOW -> 0
        ItemPriority.MID -> 1
        ItemPriority.HIGH -> 2
    }

    private fun formatClock(minute: Int): String = "%02d:%02d".format(minute / 60, minute % 60)

    private fun atMinute(base: Long, minute: Int, dayOffset: Int = 0): Long =
        Calendar.getInstance().apply {
            timeInMillis = base
            add(Calendar.DAY_OF_YEAR, dayOffset)
            set(Calendar.HOUR_OF_DAY, minute / 60)
            set(Calendar.MINUTE, minute % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
}
