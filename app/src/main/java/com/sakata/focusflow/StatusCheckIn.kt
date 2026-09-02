package com.sakata.focusflow

import java.util.Calendar

data class StatusCheckInSettings(
    val enabled: Boolean = false,
    val promptHour: Int = 14,
    val snoozeMinutes: Int = 60,
    /** 询问时刻是否由系统按签到数据自动采纳（设置页显示“已自动调整”；手动调整后关闭，不再自动）。 */
    val promptHourAutoAdjusted: Boolean = false
)

data class StatusCheckIn(
    val energy: String,
    val activity: String,
    val recordedAt: Long = System.currentTimeMillis()
)

object StatusCheckInCatalog {
    val energies = listOf("偏低", "正常", "充足")
    val activities = listOf("空闲", "学习", "课程", "娱乐", "休息", "运动", "其他")
}

/** 精力是短期状态；旧记录仍保留，但不能无限期冒充“当前精力”。 */
object StatusFreshnessPolicy {
    const val MAX_AGE_MILLIS = 6 * 60 * 60_000L

    fun isCurrent(recordedAt: Long, now: Long = System.currentTimeMillis()): Boolean {
        if (recordedAt <= 0L || recordedAt > now + 5 * 60_000L || now - recordedAt > MAX_AGE_MILLIS) return false
        val recorded = Calendar.getInstance().apply { timeInMillis = recordedAt }
        val current = Calendar.getInstance().apply { timeInMillis = now }
        return recorded.get(Calendar.YEAR) == current.get(Calendar.YEAR) &&
            recorded.get(Calendar.DAY_OF_YEAR) == current.get(Calendar.DAY_OF_YEAR)
    }
}
