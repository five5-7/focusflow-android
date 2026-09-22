package com.sakata.focusflow.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.sakata.focusflow.Goal
import com.sakata.focusflow.Item
import com.sakata.focusflow.TaskEvent

object TaskStatusKey {
    const val CAPTURED = "captured"
    const val UNSCHEDULED = "unscheduled"
    const val SCHEDULED = "scheduled"
    const val PAUSED = "paused"
    const val COMPLETED = "completed"

    fun fromLegacy(item: Item): String = when {
        item.done -> COMPLETED
        item.kind == "收集箱" -> CAPTURED
        item.kind == "暂停" -> PAUSED
        item.scheduledAt != null || item.windowStartAt != null || item.windowEndAt != null -> SCHEDULED
        else -> UNSCHEDULED
    }
}

/**
 * 9.0 migration boundary for the legacy Item aggregate.
 *
 * Captures stay in this table with status=CAPTURED. Moving the same user record between a
 * capture and a scheduled task therefore keeps its ID and does not require a cross-table move.
 * Relationships intentionally are indexed but are not cascading foreign keys: deleted tasks
 * must not delete history, and legacy installs can contain dangling plan links that need a
 * diagnostic instead of destructive cleanup.
 */
@Entity(
    tableName = "tasks",
    indices = [
        Index(value = ["status"]),
        Index(value = ["scheduled_at"]),
        Index(value = ["plan_id"]),
        Index(value = ["parent_capture_id"])
    ]
)
data class TaskEntity(
    @PrimaryKey val id: Long,
    @ColumnInfo(name = "source_order") val sourceOrder: Int,
    val title: String,
    val detail: String,
    @ColumnInfo(name = "legacy_kind") val legacyKind: String,
    val status: String,
    val done: Boolean,
    @ColumnInfo(name = "scheduled_at") val scheduledAt: Long?,
    @ColumnInfo(name = "day_only") val dayOnly: Boolean,
    @ColumnInfo(name = "plan_id") val planId: Long?,
    @ColumnInfo(name = "completion_level") val completionLevel: String,
    @ColumnInfo(name = "completed_at") val completedAt: Long?,
    @ColumnInfo(name = "duration_minutes") val durationMinutes: Int,
    @ColumnInfo(name = "window_start_at") val windowStartAt: Long?,
    @ColumnInfo(name = "window_end_at") val windowEndAt: Long?,
    @ColumnInfo(name = "reschedule_count") val rescheduleCount: Int,
    @ColumnInfo(name = "last_rescheduled_at") val lastRescheduledAt: Long?,
    @ColumnInfo(name = "recovery_source_scheduled_at") val recoverySourceScheduledAt: Long?,
    val priority: String,
    @ColumnInfo(name = "capture_route") val captureRoute: String,
    @ColumnInfo(name = "source_detail") val sourceDetail: String,
    @ColumnInfo(name = "user_note") val userNote: String?,
    @ColumnInfo(name = "next_action") val nextAction: String,
    @ColumnInfo(name = "parent_capture_id") val parentCaptureId: Long?
) {
    companion object {
        fun fromLegacy(item: Item, sourceOrder: Int = 0): TaskEntity = TaskEntity(
            id = item.id,
            sourceOrder = sourceOrder,
            title = item.title,
            detail = item.detail,
            legacyKind = item.kind,
            status = TaskStatusKey.fromLegacy(item),
            done = item.done,
            scheduledAt = item.scheduledAt,
            dayOnly = item.dayOnly,
            planId = item.goalId,
            completionLevel = item.completionLevel,
            completedAt = item.completedAt,
            durationMinutes = item.durationMinutes,
            windowStartAt = item.windowStartAt,
            windowEndAt = item.windowEndAt,
            rescheduleCount = item.rescheduleCount,
            lastRescheduledAt = item.lastRescheduledAt,
            recoverySourceScheduledAt = item.recoverySourceScheduledAt,
            priority = item.priority,
            captureRoute = item.captureRoute,
            sourceDetail = item.sourceDetail,
            userNote = item.userNote,
            nextAction = item.nextAction,
            parentCaptureId = item.parentCaptureId
        )
    }
}

@Entity(
    tableName = "task_events",
    indices = [Index(value = ["task_id"]), Index(value = ["recorded_at"])]
)
data class TaskEventEntity(
    @PrimaryKey val id: Long,
    @ColumnInfo(name = "source_order") val sourceOrder: Int,
    @ColumnInfo(name = "task_id") val taskId: Long,
    val type: String,
    @ColumnInfo(name = "recorded_at") val recordedAt: Long,
    val title: String,
    @ColumnInfo(name = "scheduled_at") val scheduledAt: Long,
    val extra: String
) {
    companion object {
        fun fromLegacy(event: TaskEvent, sourceOrder: Int = 0): TaskEventEntity = TaskEventEntity(
            id = event.id,
            sourceOrder = sourceOrder,
            taskId = event.itemId,
            type = event.type.storageKey,
            recordedAt = event.recordedAt,
            title = event.title,
            scheduledAt = event.scheduledAt,
            extra = event.extra
        )
    }
}

@Entity(tableName = "plans", indices = [Index(value = ["state"])])
data class PlanEntity(
    @PrimaryKey val id: Long,
    @ColumnInfo(name = "source_order") val sourceOrder: Int,
    val title: String,
    val state: String,
    @ColumnInfo(name = "weekly_target") val weeklyTarget: Int,
    @ColumnInfo(name = "duration_minutes") val durationMinutes: Int,
    @ColumnInfo(name = "metric_type") val metricType: String,
    @ColumnInfo(name = "metric_target") val metricTarget: String,
    @ColumnInfo(name = "minimum_version") val minimumVersion: String,
    @ColumnInfo(name = "resource_title") val resourceTitle: String,
    @ColumnInfo(name = "resource_unit") val resourceUnit: String,
    @ColumnInfo(name = "completed_this_week") val completedThisWeek: Int,
    @ColumnInfo(name = "minimum_completions_this_week") val minimumCompletionsThisWeek: Int,
    @ColumnInfo(name = "completion_week_key") val completionWeekKey: Long,
    @ColumnInfo(name = "desired_outcome") val desiredOutcome: String,
    @ColumnInfo(name = "first_action") val firstAction: String,
    @ColumnInfo(name = "source_notes") val sourceNotes: String
) {
    companion object {
        const val ACTIVE = "active"

        fun fromLegacy(goal: Goal, sourceOrder: Int = 0): PlanEntity = PlanEntity(
            id = goal.id,
            sourceOrder = sourceOrder,
            title = goal.title,
            state = ACTIVE,
            weeklyTarget = goal.weeklyTarget,
            durationMinutes = goal.durationMinutes,
            metricType = goal.metricType,
            metricTarget = goal.metricTarget,
            minimumVersion = goal.minimumVersion,
            resourceTitle = goal.resourceTitle,
            resourceUnit = goal.resourceUnit,
            completedThisWeek = goal.completedThisWeek,
            minimumCompletionsThisWeek = goal.minimumCompletionsThisWeek,
            completionWeekKey = goal.completionWeekKey,
            desiredOutcome = goal.desiredOutcome,
            firstAction = goal.firstAction,
            sourceNotes = goal.sourceNotes
        )
    }
}

@Entity(tableName = "migration_states")
data class MigrationStateEntity(
    @PrimaryKey @ColumnInfo(name = "migration_key") val migrationKey: String,
    @ColumnInfo(name = "source_data_version") val sourceDataVersion: Int,
    @ColumnInfo(name = "source_fingerprint") val sourceFingerprint: String,
    @ColumnInfo(name = "task_count") val taskCount: Int,
    @ColumnInfo(name = "task_event_count") val taskEventCount: Int,
    @ColumnInfo(name = "plan_count") val planCount: Int,
    @ColumnInfo(name = "completed_at") val completedAt: Long
)
