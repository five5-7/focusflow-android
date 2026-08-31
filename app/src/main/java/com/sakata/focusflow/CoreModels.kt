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
    val priority: String = "mid"
)

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
    val oneWayMinutes: Int = 0,
    val campusMode: String = "步行",
    val buildingBufferMinutes: Int = 3,
    val eBikeBattery: String = "未知",
    val routeCalibrations: Map<String, Int> = emptyMap(),
    val routeObservations: Map<String, List<Int>> = emptyMap()
)
