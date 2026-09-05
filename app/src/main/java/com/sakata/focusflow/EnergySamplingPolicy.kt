package com.sakata.focusflow

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

enum class EnergyTimeSlot(val label: String, val promptHour: Int) {
    MORNING("上午", 9), AFTERNOON("下午", 14), EVENING("晚上", 19);

    companion object {
        fun at(timestamp: Long, zone: ZoneId = ZoneId.systemDefault()): EnergyTimeSlot =
            when (Instant.ofEpochMilli(timestamp).atZone(zone).hour) {
                in 6..11 -> MORNING
                in 12..17 -> AFTERNOON
                else -> EVENING
            }
    }
}

enum class EnergySamplingPhase { BUILDING, MAINTENANCE }

data class EnergySamplingProgress(
    val phase: EnergySamplingPhase,
    val counts: Map<EnergyTimeSlot, Int>,
    val completedSlots: Set<EnergyTimeSlot>
)

data class EnergyPromptTarget(val triggerAt: Long, val slot: EnergyTimeSlot, val promptIndex: Int)

object EnergySamplingPolicy {
    private const val RECENT_DAYS = 30L
    private const val STABLE_MIN_SAMPLES = 6
    private const val VARIABLE_MAX_SAMPLES = 12
    private const val DOMINANT_SHARE = 0.6
    private const val MIN_PROMPT_GAP_MILLIS = 4 * 60 * 60_000L
    private val maintenanceWeekdays = setOf(1, 3, 6) // 周一、周三、周六（ISO）

    fun progress(now: Long, checkIns: List<StatusCheckIn>, zone: ZoneId = ZoneId.systemDefault()): EnergySamplingProgress {
        val recent = checkIns.filter { it.recordedAt in (now - RECENT_DAYS * 24 * 60 * 60_000L)..now }
        val grouped = recent.groupBy { EnergyTimeSlot.at(it.recordedAt, zone) }
        val counts = EnergyTimeSlot.entries.associateWith { grouped[it].orEmpty().size }
        val completed = EnergyTimeSlot.entries.filterTo(mutableSetOf()) { slot ->
            val values = grouped[slot].orEmpty()
            val dominant = values.groupingBy { it.energy }.eachCount().maxOfOrNull { it.value } ?: 0
            values.size >= VARIABLE_MAX_SAMPLES ||
                (values.size >= STABLE_MIN_SAMPLES && dominant.toDouble() / values.size >= DOMINANT_SHARE)
        }
        return EnergySamplingProgress(
            phase = if (completed.size == EnergyTimeSlot.entries.size) EnergySamplingPhase.MAINTENANCE else EnergySamplingPhase.BUILDING,
            counts = counts,
            completedSlots = completed
        )
    }

    fun nextPrompts(
        now: Long,
        startedAt: Long,
        checkIns: List<StatusCheckIn>,
        accelerated: Boolean,
        zone: ZoneId = ZoneId.systemDefault()
    ): List<EnergyPromptTarget> {
        val state = progress(now, checkIns, zone)
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val start = Instant.ofEpochMilli(startedAt.coerceAtMost(now)).atZone(zone).toLocalDate()
        val available = if (state.phase == EnergySamplingPhase.BUILDING) {
            EnergyTimeSlot.entries.filterNot(state.completedSlots::contains)
        } else {
            EnergyTimeSlot.entries.toList()
        }

        // The first delivery rebuilds alarms before the user answers. Once that answer is
        // saved, recover the optional same-day follow-up from the real record instead of
        // jumping straight to tomorrow.
        if (accelerated && state.phase == EnergySamplingPhase.BUILDING) {
            val todayRecords = checkIns.filter {
                Instant.ofEpochMilli(it.recordedAt).atZone(zone).toLocalDate() == today
            }
            if (todayRecords.size == 1) {
                val firstRecordedAt = todayRecords.single().recordedAt
                available.firstOrNull { slot ->
                    val triggerAt = atHour(today, slot.promptHour, zone)
                    triggerAt > now && triggerAt - firstRecordedAt >= MIN_PROMPT_GAP_MILLIS
                }?.let { slot ->
                    return listOf(EnergyPromptTarget(atHour(today, slot.promptHour, zone), slot, 2))
                }
            }
        }
        for (offset in 0L..14L) {
            val date = today.plusDays(offset)
            if (state.phase == EnergySamplingPhase.MAINTENANCE && date.dayOfWeek.value !in maintenanceWeekdays) continue
            val rotation = ChronoUnit.DAYS.between(start, date).coerceAtLeast(0).toInt()
            val preferred = EnergyTimeSlot.entries[rotation % EnergyTimeSlot.entries.size]
            val primarySlot = available.firstOrNull { it == preferred }
                ?: available.minByOrNull { state.counts[it] ?: 0 }
                ?: continue
            val primaryAt = atHour(date, primarySlot.promptHour, zone)
            if (primaryAt <= now) continue
            val result = mutableListOf(EnergyPromptTarget(primaryAt, primarySlot, 1))
            if (accelerated && state.phase == EnergySamplingPhase.BUILDING) {
                available.firstOrNull { it.promptHour >= primarySlot.promptHour + 4 && it != primarySlot }?.let { second ->
                    result += EnergyPromptTarget(atHour(date, second.promptHour, zone), second, 2)
                }
            }
            return result
        }
        return emptyList()
    }

    fun summary(now: Long, checkIns: List<StatusCheckIn>): String {
        val state = progress(now, checkIns)
        val counts = EnergyTimeSlot.entries.joinToString(" · ") { "${it.label}${state.counts[it] ?: 0}" }
        return if (state.phase == EnergySamplingPhase.BUILDING) "建模中：$counts" else "维护期：$counts · 每周抽查 2–3 次"
    }

    private fun atHour(date: LocalDate, hour: Int, zone: ZoneId): Long =
        date.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()
}
