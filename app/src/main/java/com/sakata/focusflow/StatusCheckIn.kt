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

enum class StatusPromptOutcome(val label: String) {
    NONE("尚未触发"),
    READY("已送达"),
    DISABLED("功能未开启"),
    NOTIFICATIONS_BLOCKED("系统通知未允许"),
    MUTED("一次性静音中"),
    QUIET_HOURS("免打扰时段已跳过"),
    ACTIVE_SESSION("活动进行中，已延后"),
    ALREADY_RECORDED("今天已经记录"),
    TOO_LATE("系统送达过晚，已跳过")
}

data class StatusPromptTrace(
    val outcome: StatusPromptOutcome = StatusPromptOutcome.NONE,
    val recordedAt: Long = 0L,
    val expectedAt: Long = 0L
)

/** 每日精力询问的唯一决策入口；超时不补发，避免晚上突然出现白天的询问。 */
object StatusPromptPolicy {
    const val MAX_DELIVERY_DELAY_MILLIS = 2 * 60 * 60_000L

    fun decide(
        settings: StatusCheckInSettings,
        expectedAt: Long,
        now: Long,
        notificationsAllowed: Boolean,
        muted: Boolean,
        quietHoursSuppressed: Boolean,
        activeSession: ActivitySession?,
        latestRecordedAt: Long?
    ): StatusPromptOutcome = when {
        !settings.enabled -> StatusPromptOutcome.DISABLED
        !notificationsAllowed -> StatusPromptOutcome.NOTIFICATIONS_BLOCKED
        muted -> StatusPromptOutcome.MUTED
        quietHoursSuppressed -> StatusPromptOutcome.QUIET_HOURS
        activeSession != null -> StatusPromptOutcome.ACTIVE_SESSION
        latestRecordedAt != null && MealLearning.sameDay(latestRecordedAt, now) -> StatusPromptOutcome.ALREADY_RECORDED
        expectedAt > 0L && now > expectedAt + MAX_DELIVERY_DELAY_MILLIS -> StatusPromptOutcome.TOO_LATE
        else -> StatusPromptOutcome.READY
    }
}

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
