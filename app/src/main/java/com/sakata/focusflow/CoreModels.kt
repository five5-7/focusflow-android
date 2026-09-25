package com.sakata.focusflow

import java.util.UUID

internal fun newItemId(): Long {
    var id: Long
    do {
        val uuid = UUID.randomUUID()
        id = (uuid.mostSignificantBits xor uuid.leastSignificantBits) and Long.MAX_VALUE
    } while (id == 0L)
    return id
}

data class Item(
    val id: Long = newItemId(),
    val title: String,
    val detail: String,
    val kind: String,
    val done: Boolean = false,
    val scheduledAt: Long? = null,
    val dayOnly: Boolean = false,
    val goalId: Long? = null,
    val completionLevel: String = "",
    val completedAt: Long? = null,
    val durationMinutes: Int = 60,
    val windowStartAt: Long? = null,
    val windowEndAt: Long? = null,
    val rescheduleCount: Int = 0,
    val lastRescheduledAt: Long? = null,
    /** 恢复操作清除 scheduledAt 前保留的计划时间，仅用于统计兼容。 */
    val recoverySourceScheduledAt: Long? = null,
    /** 三档优先级存储键（见 ItemPriority），未知值一律兜底 "mid"。 */
    val priority: String = "mid",
    /** 收集箱内部去向；旧数据缺失时按待整理处理，不影响原 kind 语义。 */
    val captureRoute: String = CaptureRoute.INBOX.storageKey,
    /** 首次整理时保留的原始说明；系统生成的状态文案不得覆盖它。 */
    val sourceDetail: String = "",
    // null 表示旧数据尚未分离备注；空字符串表示用户明确清空。
    val userNote: String? = null,
    /** “逐步推进”当前确认的下一步；为空表示等待补充。 */
    val nextAction: String = "",
    /** 从某条逐步推进想法派生的任务；父条目继续保留在收集箱。 */
    val parentCaptureId: Long? = null,
    /** Independent local deadline date, never used to schedule an alarm. */
    val dueAt: Long? = null,
    val checklist: List<ChecklistEntry> = emptyList(),
    /** Only linked tasks on a name-only plan use near/later buckets. */
    val planBucket: String = "near",
    val planFocus: Boolean = false,
    /** Rule-bearing template and one dated occurrence never share an ID. */
    val repeatFrequency: String = "",
    val repeatStartDay: Long? = null,
    val repeatMinute: Int = -1,
    val repeatTemplateId: Long? = null,
    val repeatOccurrenceDay: Long? = null,
    val repeatPaused: Boolean = false
)

data class ChecklistEntry(val id: Long, val title: String, val done: Boolean = false)

enum class CaptureRoute(val storageKey: String) {
    INBOX("inbox"),
    PROGRESS("progress"),
    REFERENCE("reference");

    companion object {
        fun fromKey(key: String?): CaptureRoute = entries.firstOrNull { it.storageKey == key } ?: INBOX
    }
}

/** 任务优先级：低/中/高，用于动态调整建议的分档依据。 */
enum class ItemPriority(val label: String, val storageKey: String) {
    LOW("低", "low"),
    MID("中", "mid"),
    HIGH("高", "high");

    companion object {
        fun fromKey(key: String?): ItemPriority = entries.firstOrNull { it.storageKey == key } ?: MID
    }
}

data class CommuteProfile(
    val enabled: Boolean = false,
    // 与持久化层的新安装默认值一致；旧代码也按 10/6/5 推导三种方式。
    val oneWayMinutes: Int = 10,
    val useDefaultForUnknown: Boolean = true,
    val nearMinutes: Int = 5,
    val fairlyNearMinutes: Int = 10,
    val fairlyFarMinutes: Int = 15,
    val farMinutes: Int = 25,
    val campusMode: String = "步行",
    /** 各方式的路上预留（不含两端楼内缓冲）。0 表示读取旧版统一默认值。 */
    val walkingReserveMinutes: Int = 0,
    val bicycleReserveMinutes: Int = 0,
    val eBikeReserveMinutes: Int = 0,
    val buildingBufferMinutes: Int = 3,
    val eBikeBattery: String = "未知",
    val routeCalibrations: Map<String, Int> = emptyMap(),
    val routeObservations: Map<String, List<Int>> = emptyMap()
) {
    fun reserveMinutesFor(mode: String): Int = when (mode) {
        "自行车" -> bicycleReserveMinutes.takeIf { it > 0 } ?: maxOf(3, (oneWayMinutes * 0.6f).toInt())
        "电动车" -> eBikeReserveMinutes.takeIf { it > 0 } ?: maxOf(3, (oneWayMinutes * 0.5f).toInt())
        else -> walkingReserveMinutes.takeIf { it > 0 } ?: oneWayMinutes
    }
}
