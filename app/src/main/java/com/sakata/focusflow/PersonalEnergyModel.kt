package com.sakata.focusflow

import java.util.Calendar

data class PersonalEnergyPattern(
    val slotLabel: String,
    val slotSampleCount: Int,
    val typicalEnergy: String?
)

/**
 * 个人情境模型：只做同一时段内的本机比较，避免混合早晚差异。
 * 所有门槛都偏保守；数据不足时返回空结论。
 */
object PersonalEnergyModel {
    private const val MIN_SLOT_SAMPLES = 4
    private const val DOMINANT_SHARE = 0.6

    fun slotSampleCount(at: Long, checkIns: List<StatusCheckIn>): Int {
        val target = slotFor(at).label
        return checkIns.count { slotFor(it.recordedAt).label == target }
    }

    fun analyze(now: Long, checkIns: List<StatusCheckIn>): PersonalEnergyPattern {
        val slot = slotFor(now)
        val inSlot = checkIns.filter { slotFor(it.recordedAt).label == slot.label }
        val counts = inSlot.groupingBy { it.energy }.eachCount()
        val dominant = counts.maxByOrNull { it.value }
            ?.takeIf { inSlot.size >= MIN_SLOT_SAMPLES && it.value.toDouble() / inSlot.size >= DOMINANT_SHARE }
            ?.key

        return PersonalEnergyPattern(slot.label, inSlot.size, dominant)
    }

    fun display(pattern: PersonalEnergyPattern): List<String> = buildList {
        pattern.typicalEnergy?.let { add("你的${pattern.slotLabel}个人基线多为“$it”（${pattern.slotSampleCount} 次记录）。") }
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
