package com.sakata.focusflow.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.sakata.focusflow.ActivitySession
import com.sakata.focusflow.Course
import com.sakata.focusflow.CampusZone
import com.sakata.focusflow.Goal
import com.sakata.focusflow.PlanState
import com.sakata.focusflow.ChecklistCodec
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
    @ColumnInfo(name = "parent_capture_id") val parentCaptureId: Long?,
    @ColumnInfo(name = "due_at") val dueAt: Long? = null,
    @ColumnInfo(name = "checklist_json", defaultValue = "'[]'") val checklistJson: String = "[]",
    @ColumnInfo(name = "plan_bucket", defaultValue = "'near'") val planBucket: String = "near",
    @ColumnInfo(name = "plan_focus", defaultValue = "0") val planFocus: Boolean = false
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
            parentCaptureId = item.parentCaptureId,
            dueAt = item.dueAt,
            checklistJson = ChecklistCodec.encodeArray(item.checklist).toString(),
            planBucket = item.planBucket,
            planFocus = item.planFocus
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
    @ColumnInfo(name = "source_notes") val sourceNotes: String,
    @ColumnInfo(name = "deadline_at") val deadlineAt: Long? = null
) {
    companion object {
        const val ACTIVE = "active"

        fun fromLegacy(goal: Goal, sourceOrder: Int = 0): PlanEntity = PlanEntity(
            id = goal.id,
            sourceOrder = sourceOrder,
            title = goal.title,
            state = goal.state.key,
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
            sourceNotes = goal.sourceNotes,
            deadlineAt = goal.deadlineAt
        )
    }
}

/**
 * A recurrence rule is a future 9.0 concept and therefore has no legacy migration source.
 *
 * Dates are stored as local epoch days and the wall-clock minute is paired with an explicit
 * time-zone ID. This keeps generation deterministic across daylight-saving changes without
 * turning an old one-off task or a weekly Goal target into an invented rule.
 */
@Entity(
    tableName = "recurrence_rules",
    indices = [Index(value = ["template_task_id"]), Index(value = ["enabled"])]
)
data class RecurrenceRuleEntity(
    @PrimaryKey val id: Long,
    @ColumnInfo(name = "template_task_id") val templateTaskId: Long,
    val frequency: String,
    val interval: Int,
    @ColumnInfo(name = "days_of_week_mask") val daysOfWeekMask: Int,
    @ColumnInfo(name = "starts_on_epoch_day") val startsOnEpochDay: Long,
    @ColumnInfo(name = "ends_on_epoch_day") val endsOnEpochDay: Long?,
    @ColumnInfo(name = "local_time_minutes") val localTimeMinutes: Int,
    @ColumnInfo(name = "time_zone_id") val timeZoneId: String,
    val enabled: Boolean,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long
)

/** One generated date of a recurrence rule; the template and materialized task remain distinct. */
@Entity(
    tableName = "task_occurrences",
    indices = [
        Index(value = ["recurrence_rule_id", "occurrence_epoch_day"], unique = true),
        Index(value = ["template_task_id"]),
        Index(value = ["materialized_task_id"]),
        Index(value = ["status"])
    ]
)
data class TaskOccurrenceEntity(
    @PrimaryKey val id: Long,
    @ColumnInfo(name = "recurrence_rule_id") val recurrenceRuleId: Long,
    @ColumnInfo(name = "template_task_id") val templateTaskId: Long,
    @ColumnInfo(name = "materialized_task_id") val materializedTaskId: Long?,
    @ColumnInfo(name = "occurrence_epoch_day") val occurrenceEpochDay: Long,
    @ColumnInfo(name = "scheduled_at") val scheduledAt: Long,
    val status: String,
    @ColumnInfo(name = "completed_at") val completedAt: Long?,
    @ColumnInfo(name = "skipped_at") val skippedAt: Long?,
    @ColumnInfo(name = "rescheduled_to") val rescheduledTo: Long?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long
)

@Entity(
    tableName = "activity_sessions",
    indices = [Index(value = ["status"]), Index(value = ["planned_start_at"])]
)
data class ActivitySessionEntity(
    @PrimaryKey val id: Long,
    @ColumnInfo(name = "source_order") val sourceOrder: Int,
    val name: String,
    val category: String,
    @ColumnInfo(name = "planned_start_at") val plannedStartAt: Long,
    @ColumnInfo(name = "actual_start_at") val actualStartAt: Long,
    @ColumnInfo(name = "ends_at") val endsAt: Long,
    @ColumnInfo(name = "next_step") val nextStep: String,
    val status: String,
    @ColumnInfo(name = "extension_count") val extensionCount: Int,
    @ColumnInfo(name = "extension_reason") val extensionReason: String,
    @ColumnInfo(name = "actual_end_at") val actualEndAt: Long?,
    @ColumnInfo(name = "end_choice") val endChoice: String
) {
    companion object {
        fun fromLegacy(session: ActivitySession, sourceOrder: Int = 0): ActivitySessionEntity =
            ActivitySessionEntity(
                id = session.id,
                sourceOrder = sourceOrder,
                name = session.name,
                category = session.category,
                plannedStartAt = session.plannedStartAt,
                actualStartAt = session.actualStartAt,
                endsAt = session.endsAt,
                nextStep = session.nextStep,
                status = session.status,
                extensionCount = session.extensionCount,
                extensionReason = session.extensionReason,
                actualEndAt = session.actualEndAt,
                endChoice = session.endChoice
            )
    }

    fun toLegacy(): ActivitySession = ActivitySession(
        id = id,
        name = name,
        category = category,
        plannedStartAt = plannedStartAt,
        actualStartAt = actualStartAt,
        endsAt = endsAt,
        nextStep = nextStep,
        status = status,
        extensionCount = extensionCount,
        extensionReason = extensionReason,
        actualEndAt = actualEndAt,
        endChoice = endChoice
    )
}

/** Legacy rows have no stable course code, so each row keeps its own parent ID until reviewed. */
@Entity(tableName = "courses")
data class CourseEntity(
    @PrimaryKey val id: Long,
    @ColumnInfo(name = "source_order") val sourceOrder: Int,
    val title: String,
    @ColumnInfo(name = "needs_confirmation") val needsConfirmation: Boolean
) {
    companion object {
        fun fromLegacy(course: Course, sourceOrder: Int) = CourseEntity(
            course.id, sourceOrder, course.title, course.needsConfirmation
        )
    }
}

@Entity(tableName = "course_meeting_rules", indices = [Index(value = ["course_id"])])
data class CourseMeetingRuleEntity(
    @PrimaryKey val id: Long,
    @ColumnInfo(name = "course_id") val courseId: Long,
    @ColumnInfo(name = "source_order") val sourceOrder: Int,
    val weekday: Int,
    @ColumnInfo(name = "start_period") val startPeriod: Int,
    @ColumnInfo(name = "end_period") val endPeriod: Int,
    val building: String,
    val zone: String,
    val enabled: Boolean,
    @ColumnInfo(name = "effective_from_epoch_day") val effectiveFromEpochDay: Long?,
    @ColumnInfo(name = "effective_until_epoch_day") val effectiveUntilEpochDay: Long?
) {
    companion object {
        fun fromLegacy(course: Course, sourceOrder: Int) = CourseMeetingRuleEntity(
            course.id, course.id, sourceOrder, course.weekday, course.startPeriod,
            course.endPeriod, course.building, course.zone.name, course.enabled,
            course.effectiveFromEpochDay, course.effectiveUntilEpochDay
        )
    }

    fun toLegacy(parent: CourseEntity): Course = Course(
        id = parent.id, title = parent.title, needsConfirmation = parent.needsConfirmation,
        weekday = weekday, startPeriod = startPeriod, endPeriod = endPeriod,
        building = building, zone = CampusZone.valueOf(zone), enabled = enabled,
        effectiveFromEpochDay = effectiveFromEpochDay,
        effectiveUntilEpochDay = effectiveUntilEpochDay
    )
}

@Entity(tableName = "migration_states")
data class MigrationStateEntity(
    @PrimaryKey @ColumnInfo(name = "migration_key") val migrationKey: String,
    @ColumnInfo(name = "source_data_version") val sourceDataVersion: Int,
    @ColumnInfo(name = "source_fingerprint") val sourceFingerprint: String,
    @ColumnInfo(name = "task_count") val taskCount: Int,
    @ColumnInfo(name = "task_event_count") val taskEventCount: Int,
    @ColumnInfo(name = "plan_count") val planCount: Int,
    @ColumnInfo(name = "completed_at") val completedAt: Long,
    @ColumnInfo(name = "recurrence_rule_count") val recurrenceRuleCount: Int = 0,
    @ColumnInfo(name = "task_occurrence_count") val taskOccurrenceCount: Int = 0,
    @ColumnInfo(name = "activity_session_count") val activitySessionCount: Int = 0,
    @ColumnInfo(name = "course_count") val courseCount: Int = 0,
    @ColumnInfo(name = "course_meeting_rule_count") val courseMeetingRuleCount: Int = 0
)
