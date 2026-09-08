package com.sakata.focusflow

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
        val target = EnergyTimeSlot.at(at)
        return checkIns.count { EnergyTimeSlot.at(it.recordedAt) == target }
    }

    fun analyze(now: Long, checkIns: List<StatusCheckIn>): PersonalEnergyPattern {
        val slot = EnergyTimeSlot.at(now)
        val inSlot = checkIns.filter { EnergyTimeSlot.at(it.recordedAt) == slot }
        val counts = inSlot.groupingBy { it.energy }.eachCount()
        val dominant = counts.maxByOrNull { it.value }
            ?.takeIf { inSlot.size >= MIN_SLOT_SAMPLES && it.value.toDouble() / inSlot.size >= DOMINANT_SHARE }
            ?.key

        return PersonalEnergyPattern(slot.label, inSlot.size, dominant)
    }

    fun display(pattern: PersonalEnergyPattern): List<String> = buildList {
        pattern.typicalEnergy?.let { add("你的${pattern.slotLabel}个人基线多为“$it”（${pattern.slotSampleCount} 次记录）。") }
    }

}
