package com.sakata.focusflow

/**
 * 提醒打扰控制：免打扰时段（静音低打扰类提醒：状态询问、饭点、睡前减速；
 * 活动到点/任务提醒保持时间敏感语义不被静音）+ 一次性静音（muteUntil 之前全部静音）。
 */
data class QuietHoursSettings(
    val enabled: Boolean = false,
    /** 免打扰开始分钟（0–1439，如 23:00 = 1380）。 */
    val startMinute: Int = 23 * 60,
    /** 免打扰结束分钟（0–1439，如 07:00 = 420）。跨天时段（start>end）按“到次日 end”处理。 */
    val endMinute: Int = 7 * 60,
    val suppressStatusCheckIn: Boolean = true,
    val suppressMeal: Boolean = true,
    val suppressWindDown: Boolean = true,
    /** 一次性静音截止时间（epoch ms）；之前所有提醒静音，0 表示未启用。 */
    val muteUntil: Long = 0L
) {
    /** 当前时刻是否处于免打扰时段（含跨天）。 */
    fun inQuietHours(now: Long = System.currentTimeMillis()): Boolean {
        if (!enabled) return false
        val calendar = java.util.Calendar.getInstance().apply { timeInMillis = now }
        val minute = calendar.get(java.util.Calendar.HOUR_OF_DAY) * 60 + calendar.get(java.util.Calendar.MINUTE)
        return if (startMinute <= endMinute) minute in startMinute until endMinute
        else minute >= startMinute || minute < endMinute
    }

    /** 一次性静音是否生效。 */
    fun isMuted(now: Long = System.currentTimeMillis()): Boolean = muteUntil > now

    companion object {
        /** 某类提醒在免打扰时段内是否被静音（活动到点/任务等时间敏感提醒保持）。 */
        fun suppresses(settings: QuietHoursSettings, action: String): Boolean = when (action) {
            ReminderReceiver.ACTION_STATUS_CHECK_IN -> settings.suppressStatusCheckIn
            ReminderReceiver.ACTION_MEAL_REMINDER, ReminderReceiver.ACTION_MEAL_END_REMINDER -> settings.suppressMeal
            ReminderReceiver.ACTION_WIND_DOWN -> settings.suppressWindDown
            else -> false
        }
    }
}

/** 下一次早上 7 点的时间戳（“静音到明早”用；若已过今天 7 点则为明天 7 点）。 */
fun nextMorning(now: Long = System.currentTimeMillis()): Long {
    val calendar = java.util.Calendar.getInstance().apply { timeInMillis = now }
    calendar.set(java.util.Calendar.HOUR_OF_DAY, 7)
    calendar.set(java.util.Calendar.MINUTE, 0)
    calendar.set(java.util.Calendar.SECOND, 0)
    calendar.set(java.util.Calendar.MILLISECOND, 0)
    if (calendar.timeInMillis <= now) calendar.add(java.util.Calendar.DAY_OF_YEAR, 1)
    return calendar.timeInMillis
}
