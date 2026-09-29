package com.sakata.focusflow

/** Compact facts for a rule; pending future occurrences are deliberately omitted. */
internal data class RepeatHistorySummary(
    val completed: Int,
    val skipped: Int,
    val missed: Int,
    val rescheduled: Int,
    val canceled: Int,
    val notDue: Int
) {
    val hasHistory: Boolean get() = completed + skipped + missed + rescheduled + canceled + notDue > 0

    fun label(): String = listOfNotNull(
        completed.takeIf { it > 0 }?.let { "完成 $it" },
        skipped.takeIf { it > 0 }?.let { "跳过 $it" },
        missed.takeIf { it > 0 }?.let { "未处理 $it" },
        rescheduled.takeIf { it > 0 }?.let { "改期 $it" },
        canceled.takeIf { it > 0 }?.let { "取消 $it" },
        notDue.takeIf { it > 0 }?.let { "暂停／条件未满足 $it" }
    ).joinToString(" · ")

    companion object {
        fun from(items: List<Item>, templateId: Long): RepeatHistorySummary {
            val occurrences = items.filter { it.repeatTemplateId == templateId }
            return RepeatHistorySummary(
                completed = occurrences.count { it.done },
                skipped = occurrences.count { it.kind == "重复历史" && it.detail == "主动跳过本次" },
                missed = occurrences.count { it.kind == "重复历史" && it.detail == "本次未处理" },
                rescheduled = occurrences.count { it.rescheduleCount > 0 },
                canceled = occurrences.count { it.kind == "重复历史" && it.detail in setOf(
                    "本次已取消", "规则停止，取消本次") },
                notDue = occurrences.count { it.kind == "重复历史" && it.detail in setOf(
                    "暂停时未处理", "当天没有生效课程，条件未满足") }
            )
        }
    }
}
