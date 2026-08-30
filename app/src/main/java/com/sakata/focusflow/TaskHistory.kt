package com.sakata.focusflow

import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/** 任务生命周期事件类型。storageKey 为持久化值；旧版本未知 type 在解码时丢弃，不回写。 */
enum class TaskEventType(val label: String, val storageKey: String) {
    TASK_CREATED("任务创建", "task_created"),
    TASK_SCHEDULED("任务安排", "task_scheduled"),
    TASK_RESCHEDULED("任务改期", "task_rescheduled"),
    TASK_COMPLETED("任务完成", "task_completed"),
    /** 预留：当前无触发入口；为语义完整的 6.5 事件模型定义。 */
    TASK_UNCOMPLETED("取消完成", "task_uncompleted"),
    TASK_TO_INBOX("放回收集箱", "task_to_inbox"),
    /** 收集箱项转换为目标（6.7）。scheduledAt 恒为 0：转换不产生日程计划。 */
    TASK_CONVERTED("转为目标", "task_converted"),
    /** 收集箱项归入已有目标（6.8）。scheduledAt 恒为 0：归入不产生日程计划。 */
    TASK_ATTACHED_TO_PLAN("归入计划", "task_attached_to_plan"),
    TASK_DELETED("删除任务", "task_deleted"),
    TASK_RESTORED("恢复任务", "task_restored");

    companion object {
        fun fromKey(key: String): TaskEventType? = entries.firstOrNull { it.storageKey == key }
    }
}

/**
 * 一条任务历史事件。追加保存，删除任务不删除其历史事件。
 * [itemId] 用于按任务去重；删除后仍保留（事件本就是历史）。
 * [scheduledAt] 为安排/改期目标时刻，0 表示无；它是完成率分母的归日依据。
 */
data class TaskEvent(
    val id: Long = newItemId(),
    val itemId: Long,
    val type: TaskEventType,
    val recordedAt: Long,
    val title: String = "",
    val scheduledAt: Long = 0,
    val extra: String = ""
)

object TaskRecorder {
    fun event(
        type: TaskEventType,
        itemId: Long,
        title: String = "",
        scheduledAt: Long = 0,
        extra: String = "",
        at: Long = System.currentTimeMillis()
    ): TaskEvent = TaskEvent(
        id = newItemId(),
        itemId = itemId,
        type = type,
        recordedAt = at,
        title = title,
        scheduledAt = scheduledAt,
        extra = extra
    )

    /** 展示文本，沿用 BaselineRecorder 的 "M月d日 HH:mm" 中文时间格式。 */
    fun displayText(event: TaskEvent): String {
        val time = java.text.SimpleDateFormat("M月d日 HH:mm", java.util.Locale.CHINA).format(java.util.Date(event.recordedAt))
        val parts = listOf(
            event.type.label,
            event.title.ifBlank { null },
            event.extra.ifBlank { null },
            time
        )
        return parts.filterNotNull().joinToString(" · ")
    }
}

object TaskEventCodec {
    fun encode(events: List<TaskEvent>): String {
        val values = JSONArray()
        events.forEach { event -> values.put(JSONObject().apply {
            put("id", event.id)
            put("itemId", event.itemId)
            put("type", event.type.storageKey)
            put("recordedAt", event.recordedAt)
            put("title", event.title)
            put("scheduledAt", event.scheduledAt)
            put("extra", event.extra)
        }) }
        return values.toString()
    }

    /** 全部 opt* 读取；未知 type 与 recordedAt<=0 的条目丢弃（不覆盖原始值，符合数据契约降级策略）。 */
    fun decode(json: String): List<TaskEvent> = runCatching {
        val values = JSONArray(json)
        buildList {
            for (index in 0 until values.length()) {
                val value = values.optJSONObject(index) ?: continue
                val type = TaskEventType.fromKey(value.optString("type")) ?: continue
                val recordedAt = value.optLong("recordedAt", 0)
                if (recordedAt <= 0) continue
                add(
                    TaskEvent(
                        id = value.optLong("id", 0).takeIf { it != 0L } ?: newItemId(),
                        itemId = value.optLong("itemId", 0),
                        type = type,
                        recordedAt = recordedAt,
                        title = value.optString("title", ""),
                        scheduledAt = value.optLong("scheduledAt", 0),
                        extra = value.optString("extra", "")
                    )
                )
            }
        }
    }.getOrDefault(emptyList())
}

/** 某自然日的任务统计。 [dayStart] 为该日 00:00 毫秒。 */
data class DayTaskSummary(
    val dayStart: Long,
    val scheduledCount: Int,
    val completedCount: Int,
    val completedPlannedCount: Int,
    val rescheduledCount: Int,
    val scheduleChangesCount: Int
) {
    val uncompletedCount: Int get() = (scheduledCount - completedPlannedCount).coerceAtLeast(0)
    val completionPercent: Int? get() = scheduledCount.takeIf { it > 0 }?.let { completedPlannedCount * 100 / it }
}

/**
 * 任务历史统计（纯 Kotlin，可单测）。
 *
 * 核心原则：统计基于发生过的事件，而非当前任务状态倒推。
 * - 计划数：type ∈ {CREATED, SCHEDULED, RESCHEDULED} 且 scheduledAt>0，按 scheduledAt 归日、itemId 去重。
 *   同一天内多次移动只计 1；改期到别日不撤原日计划；放回收集箱/删除也不撤分母。
 * - 完成数：TASK_COMPLETED 按 recordedAt 归日、itemId 去重（已删除任务的完成仍在）。
 */
object TaskHistory {
    /** 自然日 00:00（与 TimeUtils 使用同一 Calendar 日界定义）。 */
    fun dayStartOf(millis: Long): Long {
        val calendar = Calendar.getInstance().apply { timeInMillis = millis }
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }

    private fun isSameDay(first: Long, second: Long): Boolean {
        val left = Calendar.getInstance().apply { timeInMillis = first }
        val right = Calendar.getInstance().apply { timeInMillis = second }
        return left.get(Calendar.ERA) == right.get(Calendar.ERA) &&
            left.get(Calendar.YEAR) == right.get(Calendar.YEAR) &&
            left.get(Calendar.DAY_OF_YEAR) == right.get(Calendar.DAY_OF_YEAR)
    }

    private val PLAN_EVENTS = setOf(
        TaskEventType.TASK_CREATED,
        TaskEventType.TASK_SCHEDULED,
        TaskEventType.TASK_RESCHEDULED
    )

    private val CHANGE_EVENTS = setOf(
        TaskEventType.TASK_SCHEDULED,
        TaskEventType.TASK_RESCHEDULED,
        TaskEventType.TASK_TO_INBOX,
        TaskEventType.TASK_CONVERTED,
        TaskEventType.TASK_ATTACHED_TO_PLAN,
        TaskEventType.TASK_DELETED,
        TaskEventType.TASK_RESTORED
    )

    fun daySummary(events: List<TaskEvent>, dayStart: Long): DayTaskSummary {
        val plannedIds = events.asSequence()
            .filter { it.type in PLAN_EVENTS && it.scheduledAt > 0 && isSameDay(it.scheduledAt, dayStart) }
            .map { it.itemId }
            .filter { it != 0L }
            .toSet()
        val completedIds = events.asSequence()
            .filter { it.type == TaskEventType.TASK_COMPLETED && isSameDay(it.recordedAt, dayStart) }
            .map { it.itemId }
            .filter { it != 0L }
            .toSet()
        return DayTaskSummary(
            dayStart = dayStart,
            scheduledCount = plannedIds.size,
            completedCount = completedIds.size,
            completedPlannedCount = (plannedIds intersect completedIds).size,
            rescheduledCount = events.count { it.type == TaskEventType.TASK_RESCHEDULED && isSameDay(it.recordedAt, dayStart) },
            scheduleChangesCount = events.count { it.type in CHANGE_EVENTS && isSameDay(it.recordedAt, dayStart) }
        )
    }

    /** 最近 [days] 天逐日统计，旧 → 新，最后一项是今天。 */
    fun lastDays(events: List<TaskEvent>, days: Int = 7, now: Long = System.currentTimeMillis()): List<DayTaskSummary> {
        val today = dayStartOf(now)
        return (0 until days).map { offset -> today - offset * 24L * 60 * 60 * 1000 }
            .map { daySummary(events, it) }
            .reversed()
    }

    /** 当天完成记录列表（按完成时间倒序），供今日完成记录卡使用。 */
    fun completedOn(events: List<TaskEvent>, dayStart: Long): List<TaskEvent> =
        events.filter { it.type == TaskEventType.TASK_COMPLETED && isSameDay(it.recordedAt, dayStart) }
            .sortedByDescending { it.recordedAt }

    /** 最近事件（按时间倒序），供历史页事件列表使用。 */
    fun recentEvents(events: List<TaskEvent>, limit: Int = 50): List<TaskEvent> =
        events.sortedByDescending { it.recordedAt }.take(limit.coerceAtLeast(1))
}

/** 6.5 一次性迁移：把存量 items 可可靠推断的事件补齐。不制造无法推断的假统计。
 * 6.7 转换事件（TASK_CONVERTED）无法从存量状态推断，也不在此补造。 */
object TaskHistoryMigration {
    fun buildEvents(items: List<Item>): List<TaskEvent> = buildList {
        for (item in items) {
            item.completedAt?.let { at ->
                add(TaskRecorder.event(TaskEventType.TASK_COMPLETED, item.id, item.title, extra = item.completionLevel.ifBlank { "完成" }, at = at))
            }
            item.lastRescheduledAt?.let { at ->
                add(TaskRecorder.event(TaskEventType.TASK_RESCHEDULED, item.id, item.title, scheduledAt = item.scheduledAt ?: 0, at = at))
            }
            item.recoverySourceScheduledAt?.let { at ->
                add(TaskRecorder.event(TaskEventType.TASK_SCHEDULED, item.id, item.title, scheduledAt = at, at = at))
            }
            item.scheduledAt?.let { at ->
                add(TaskRecorder.event(TaskEventType.TASK_SCHEDULED, item.id, item.title, scheduledAt = at, at = at))
            }
        }
    }
}
