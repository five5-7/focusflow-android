package com.sakata.focusflow

/** 生活阶段：假期、上学、考试周。饭点与作息按阶段分开学习，避免互相污染。 */
enum class LifeStage(val label: String, val storageKey: String) {
    HOLIDAY("假期", "holiday"),
    SCHOOL("上学", "school"),
    EXAM("考试周", "exam");

    companion object {
        fun fromKey(key: String?): LifeStage? = entries.firstOrNull { it.storageKey == key }
    }
}

enum class MealType(val label: String) {
    BREAKFAST("早餐"), LUNCH("午餐"), DINNER("晚餐");

    companion object {
        fun fromLabel(label: String): MealType? = entries.firstOrNull { it.label == label }
    }
}

/** 一餐的大致时间：用户填写的锚点，不是精确预测；typicalMinutes 用于估计用餐时长。 */
data class MealTimeline(
    val type: MealType,
    val typicalStartMinute: Int,
    val typicalMinutes: Int = 20
)

/** 用户填写的习惯基线。lifeStage 为空表示尚未完成引导。 */
data class BaselineProfile(
    val lifeStage: LifeStage? = null,
    val wakeMinute: Int = -1,
    val sleepMinute: Int = -1,
    val meals: List<MealTimeline> = emptyList(),
    val entertainmentWindow: String = ""
) {
    val isComplete: Boolean
        get() = lifeStage != null && wakeMinute in 0 until 24 * 60 && sleepMinute in 0 until 24 * 60 && meals.size >= 2
}

enum class BaselineEventType(val label: String, val storageKey: String) {
    LIFE_STAGE_SET("生活阶段", "life_stage_set"),
    SCHEDULE_ANCHOR_SET("作息锚点", "schedule_anchor_set"),
    MEAL_TIMELINE_SET("餐次时间", "meal_timeline_set"),
    ACTIVITY_STARTED("活动开始", "activity_started"),
    ACTIVITY_ENDED("活动结束", "activity_ended"),
    ACTIVITY_SKIPPED("活动跳过", "activity_skipped"),
    TASK_SCHEDULED("任务安排", "task_scheduled"),
    TASK_RESCHEDULED("任务改期", "task_rescheduled"),
    TASK_COMPLETED("任务完成", "task_completed"),
    CHECK_IN_RECORDED("精力签到", "check_in_recorded"),
    COMMUTE_CONFIRMED("通勤确认", "commute_confirmed"),
    BASELINE_REBUILT("基线重建", "baseline_rebuilt")
}

/** 一条用户确认过的原始事件。追加保存，不因后续学习而覆盖；只记录用户确认的数据。 */
data class BaselineEvent(
    val id: Long,
    val type: BaselineEventType,
    val recordedAt: Long,
    val payload: String = ""
)

object BaselineRecorder {
    fun event(type: BaselineEventType, payload: String = "", at: Long = System.currentTimeMillis()): BaselineEvent =
        BaselineEvent(id = newItemId(), type = type, recordedAt = at, payload = payload)

    fun displayPayload(event: BaselineEvent): String {
        val time = java.text.SimpleDateFormat("M月d日 HH:mm", java.util.Locale.CHINA).format(java.util.Date(event.recordedAt))
        val text = if (event.payload.isBlank()) event.type.label else "${event.type.label} · ${event.payload}"
        return "$text · $time"
    }
}
