package com.sakata.focusflow

import com.sakata.focusflow.data.TrashGroupRecord

/** One trash group as the recycle-bin view renders it: active members only, in original order. */
internal data class TrashGroupView(
    val members: List<Item>,
    val restoredCount: Int,
    val expiresAt: Long
) {
    val partial: Boolean get() = restoredCount > 0
    val size: Int get() = members.size
}

/**
 * Recycle-bin content. Grouped ordinary deletions come first; tombstones that carry no group keep
 * their own section because they stay restorable and must never be swept by the expiry pass.
 */
internal data class TrashView(
    val groups: List<TrashGroupView>,
    val ungrouped: List<Item>
) {
    val isEmpty: Boolean get() = groups.isEmpty() && ungrouped.isEmpty()
}

internal object TrashViews {
    private const val DAY_MS = 24L * 60L * 60L * 1000L

    fun build(items: List<Item>, groups: List<TrashGroupRecord>): TrashView {
        val byId = items.associateBy(Item::id)
        val orderById = mutableMapOf<Long, Int>()
        val groupedIds = mutableSetOf<Long>()
        val views = groups.mapNotNull { group ->
            group.members.forEach { orderById[it.itemId] = it.sourceOrder }
            val active = group.members.filter { it.itemId !in group.restoredIds }
                .mapNotNull { member -> byId[member.itemId]?.takeIf { it.kind == "回收站" } }
                .sortedBy { orderById[it.id] ?: Int.MAX_VALUE }
            groupedIds += active.map(Item::id)
            if (active.isEmpty()) return@mapNotNull null
            TrashGroupView(
                members = active,
                restoredCount = group.restoredIds.size,
                expiresAt = group.expiresAt
            )
        }
        val ungrouped = items
            .filter { it.kind == "回收站" && it.trashedAt != null && it.id !in groupedIds }
            .sortedByDescending { it.trashedAt }
        return TrashView(views, ungrouped)
    }

    /**
     * Retention left at [now]. The stage 7.3 contract freezes the boundary at `now >= expiresAt`;
     * this label only reports the stored fact, it never promises a background timer.
     */
    fun remainingLabel(expiresAt: Long, now: Long): String {
        val remaining = expiresAt - now
        return when {
            remaining <= 0L -> "已到期"
            remaining < DAY_MS -> "不足 1 天"
            else -> "剩余 ${(remaining + DAY_MS - 1) / DAY_MS} 天"
        }
    }
}
