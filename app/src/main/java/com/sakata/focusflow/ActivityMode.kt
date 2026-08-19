package com.sakata.focusflow

data class ActivitySession(
    val id: Long = System.currentTimeMillis(),
    val name: String,
    val category: String = name,
    val plannedStartAt: Long = System.currentTimeMillis(),
    val actualStartAt: Long = System.currentTimeMillis(),
    val endsAt: Long,
    val nextStep: String = "",
    val status: String = STATUS_ACTIVE,
    val extensionCount: Int = 0,
    val extensionReason: String = "",
    val actualEndAt: Long? = null,
    val endChoice: String = ""
) {
    fun isOpen(): Boolean = status in OPEN_STATUSES

    companion object {
        const val STATUS_ACTIVE = "active"
        const val STATUS_EXTENDED = "extended"
        const val STATUS_AWAITING_CONFIRMATION = "awaiting_confirmation"
        const val STATUS_COMPLETED = "completed"
        const val STATUS_SKIPPED = "skipped"
        val OPEN_STATUSES = setOf(STATUS_ACTIVE, STATUS_EXTENDED, STATUS_AWAITING_CONFIRMATION)
    }
}

data class ActivityReminderSettings(
    val notificationsEnabled: Boolean = true,
    val previewMinutes: Int = 10,
    val maxExtensions: Int = 3,
    val strongerEndReminder: Boolean = true
)

data class ActivityCommitment(val title: String, val startsAt: Long)

