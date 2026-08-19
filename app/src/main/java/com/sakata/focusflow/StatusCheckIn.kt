package com.sakata.focusflow

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
