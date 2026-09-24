package com.sakata.focusflow

internal data class InboxAgeGroups(
    val recent: List<Item>,
    val earlier: List<Item>,
    val undated: List<Item>
)

/** Unknown creation times stay separate; list order is retained for legacy items. */
internal fun groupInboxByAge(
    items: List<Item>,
    createdAt: Map<Long, Long>,
    now: Long
): InboxAgeGroups {
    val threshold = now - 7L * 24 * 60 * 60 * 1000
    val recent = mutableListOf<Item>()
    val earlier = mutableListOf<Item>()
    val undated = mutableListOf<Item>()
    items.forEach { item ->
        val time = createdAt[item.id]
        when {
            time == null || time <= 0L -> undated += item
            time < threshold -> earlier += item
            else -> recent += item
        }
    }
    val byNewest = compareByDescending<Item> { createdAt.getValue(it.id) }
    return InboxAgeGroups(recent.sortedWith(byNewest), earlier.sortedWith(byNewest), undated)
}
