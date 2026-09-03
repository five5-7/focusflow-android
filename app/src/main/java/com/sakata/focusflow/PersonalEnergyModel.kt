package com.sakata.focusflow

import java.util.Calendar

data class PersonalEnergyPattern(
    val slotLabel: String,
    val slotSampleCount: Int,
    val typicalEnergy: String?,
    val sleepComparison: String?
)

/**
 * 个人情境模型：只做同一时段内的本机比较，避免把早晚差异误判为睡眠影响。
 * 所有门槛都偏保守；数据不足时返回空结论。
 */
object PersonalEnergyModel {
    private const val MIN_SLOT_SAMPLES = 4
    private const val MIN_SLEEP_GROUP_SAMPLES = 2
    private const val DOMINANT_SHARE = 0.6
    private const val MEANINGFUL_LOW_RATE_GAP = 0.5
    private const val MAX_SLEEP_TO_CHECK_IN_GAP = 36 * 60 * 60_000L

    fun slotSampleCount(at: Long, checkIns: List<StatusCheckIn>): Int {
        val target = slotFor(at).label
        return checkIns.count { slotFor(it.recordedAt).label == target }
    }

    fun analyze(
        now: Long,
        checkIns: List<StatusCheckIn>,
        sleeps: List<SleepSummary>
    ): PersonalEnergyPattern {
        val slot = slotFor(now)
        val inSlot = checkIns.filter { slotFor(it.recordedAt).label == slot.label }
        val counts = inSlot.groupingBy { it.energy }.eachCount()
        val dominant = counts.maxByOrNull { it.value }
            ?.takeIf { inSlot.size >= MIN_SLOT_SAMPLES && it.value.toDouble() / inSlot.size >= DOMINANT_SHARE }
            ?.key

        val paired = inSlot.mapNotNull { checkIn ->
            val sleep = sleeps
                .asSequence()
                .filter { it.endAt <= checkIn.recordedAt && checkIn.recordedAt - it.endAt <= MAX_SLEEP_TO_CHECK_IN_GAP }
                .maxByOrNull { it.endAt }
            sleep?.let { checkIn to it }
        }
        val short = paired.filter { it.second.durationMinutes < 6 * 60 }
        val adequate = paired.filter { it.second.durationMinutes >= 6 * 60 }
        val sleepComparison = if (short.size >= MIN_SLEEP_GROUP_SAMPLES && adequate.size >= MIN_SLEEP_GROUP_SAMPLES) {
            val shortLow = short.count { it.first.energy == "偏低" }.toDouble() / short.size
            val adequateLow = adequate.count { it.first.energy == "偏低" }.toDouble() / adequate.size
            when {
                shortLow - adequateLow >= MEANINGFUL_LOW_RATE_GAP ->
                    "同为${slot.label}时，少于 6 小时睡眠后更常记录为精力偏低（${short.size} 次对比 ${adequate.size} 次）；这是个人样本关联，不代表因果。"
                adequateLow - shortLow >= MEANINGFUL_LOW_RATE_GAP ->
                    "目前${slot.label}样本没有显示短睡眠对应更低精力（${short.size} 次对比 ${adequate.size} 次）；继续积累后再判断。"
                else -> null
            }
        } else null
        return PersonalEnergyPattern(slot.label, inSlot.size, dominant, sleepComparison)
    }

    fun display(pattern: PersonalEnergyPattern): List<String> = buildList {
        pattern.typicalEnergy?.let { add("你的${pattern.slotLabel}个人基线多为“$it”（${pattern.slotSampleCount} 次记录）。") }
        pattern.sleepComparison?.let(::add)
    }

    private data class Slot(val label: String)

    private fun slotFor(at: Long): Slot {
        val hour = Calendar.getInstance().apply { timeInMillis = at }.get(Calendar.HOUR_OF_DAY)
        return when (hour) {
            in 6..11 -> Slot("上午")
            in 12..17 -> Slot("下午")
            else -> Slot("晚上")
        }
    }
}
