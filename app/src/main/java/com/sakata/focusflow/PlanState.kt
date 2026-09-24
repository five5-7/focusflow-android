package com.sakata.focusflow

/** Existing goals are active plans; wanted plans have no automatic schedule or weekly target. */
enum class PlanState(val key: String, val label: String) {
    WANTED("wanted", "想做"),
    IN_PROGRESS("active", "进行中"),
    PAUSED("paused", "暂停"),
    COMPLETED("completed", "完成");

    companion object {
        fun fromKey(key: String): PlanState? = entries.firstOrNull { it.key == key }
    }
}
