package com.sakata.focusflow

/** Reject writes derived from a snapshot superseded by a notification action. */
internal object TaskSnapshotPolicy {
    fun canSave(expected: List<Item>, current: List<Item>): Boolean = expected == current
}
