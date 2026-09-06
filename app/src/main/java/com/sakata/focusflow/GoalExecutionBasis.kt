package com.sakata.focusflow

/** Builds the immutable execution context copied into a scheduled task. */
internal fun goalTaskDetail(goal: Goal, scheduledAt: Long): String {
    val guide = buildString {
        if (goal.firstAction.isNotBlank()) append(" · 第一步：${goal.firstAction}")
        if (goal.resourceTitle.isNotBlank()) {
            append(" · 教程：${goal.resourceTitle}")
            goal.resourceUnit.takeIf { it.isNotBlank() }?.let { append("（$it）") }
        }
        if (goal.minimumVersion.isNotBlank()) append(" · 最低版本：${goal.minimumVersion}")
    }
    return "${goal.metricType}：${goal.metricTarget.ifBlank { "本次完成" }} · " +
        "${formatDateTime(scheduledAt)}$guide"
}
