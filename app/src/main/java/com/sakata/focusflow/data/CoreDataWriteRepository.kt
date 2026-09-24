package com.sakata.focusflow.data

import com.sakata.focusflow.ActivitySession
import com.sakata.focusflow.Course
import com.sakata.focusflow.Goal
import com.sakata.focusflow.GoalPlanner
import com.sakata.focusflow.Item
import com.sakata.focusflow.TaskEvent
import com.sakata.focusflow.TaskHistory
import com.sakata.focusflow.TaskReminderActionFreshness
import java.util.concurrent.Callable

enum class CoreDataWriteStatus {
    APPLIED,
    NOT_READY,
    INVALID_STATE,
    INVALID_INPUT,
    STALE_TASKS,
    STALE_PLANS,
    STALE_ACTIVITY_SESSIONS,
    STALE_COURSES,
    CONDITION_NOT_MET,
    WRITE_FAILED
}

data class CoreDataTaskMutation(val before: Item, val after: Item)
data class CoreDataActivityMutation(val before: ActivitySession?, val after: ActivitySession)

data class CoreDataWriteResult(
    val status: CoreDataWriteStatus,
    val message: String = "",
    val taskMutation: CoreDataTaskMutation? = null,
    val activityMutation: CoreDataActivityMutation? = null
) {
    val applied: Boolean get() = status == CoreDataWriteStatus.APPLIED
}

/**
 * Minimal synchronous transaction surface used by the disabled Room write path. Callers must run
 * it off the main thread. Tests provide an in-memory implementation with rollback semantics.
 */
interface RoomCoreDataWriteStore : RoomCoreDataSource {
    fun <T> inTransaction(block: () -> T): T
    fun replaceTasks(tasks: List<TaskEntity>)
    fun replaceTaskEvents(events: List<TaskEventEntity>)
    fun replacePlans(plans: List<PlanEntity>)
    fun replaceActivitySessions(sessions: List<ActivitySessionEntity>)
    fun replaceCourses(courses: List<CourseEntity>, rules: List<CourseMeetingRuleEntity>)
    fun updateMigrationCounts(
        taskCount: Int,
        taskEventCount: Int,
        planCount: Int,
        activitySessionCount: Int,
        courseCount: Int,
        courseMeetingRuleCount: Int
    )
}

class DatabaseRoomCoreDataWriteStore(private val database: FocusFlowDatabase) : RoomCoreDataWriteStore {
    override fun <T> inTransaction(block: () -> T): T = database.runInTransaction(Callable { block() })

    override fun migrationState(): MigrationStateEntity? =
        database.migrationStateDao().find(LegacyDataImporter.MIGRATION_KEY)

    override fun tasks(): List<TaskEntity> = database.taskDao().all()
    override fun taskEvents(): List<TaskEventEntity> = database.taskEventDao().all()
    override fun plans(): List<PlanEntity> = database.planDao().all()
    override fun recurrenceRuleIds(): List<Long> = database.recurrenceRuleDao().allIds()
    override fun taskOccurrenceIds(): List<Long> = database.taskOccurrenceDao().allIds()
    override fun activitySessions(): List<ActivitySessionEntity> = database.activitySessionDao().all()
    override fun courses(): List<CourseEntity> = database.courseDao().all()
    override fun courseMeetingRules(): List<CourseMeetingRuleEntity> = database.courseMeetingRuleDao().all()

    override fun replaceTasks(tasks: List<TaskEntity>) {
        database.taskDao().deleteAll()
        if (tasks.isNotEmpty()) database.taskDao().insertAll(tasks)
    }

    override fun replaceTaskEvents(events: List<TaskEventEntity>) {
        database.taskEventDao().deleteAll()
        if (events.isNotEmpty()) database.taskEventDao().insertAll(events)
    }

    override fun replacePlans(plans: List<PlanEntity>) {
        database.planDao().deleteAll()
        if (plans.isNotEmpty()) database.planDao().insertAll(plans)
    }

    override fun replaceActivitySessions(sessions: List<ActivitySessionEntity>) {
        database.activitySessionDao().deleteAll()
        if (sessions.isNotEmpty()) database.activitySessionDao().insertAll(sessions)
    }

    override fun replaceCourses(courses: List<CourseEntity>, rules: List<CourseMeetingRuleEntity>) {
        database.courseMeetingRuleDao().deleteAll()
        database.courseDao().deleteAll()
        if (courses.isNotEmpty()) database.courseDao().insertAll(courses)
        if (rules.isNotEmpty()) database.courseMeetingRuleDao().insertAll(rules)
    }

    override fun updateMigrationCounts(
        taskCount: Int,
        taskEventCount: Int,
        planCount: Int,
        activitySessionCount: Int,
        courseCount: Int,
        courseMeetingRuleCount: Int
    ) {
        val updated = database.migrationStateDao().updateCounts(
            LegacyDataImporter.MIGRATION_KEY,
            taskCount,
            taskEventCount,
            planCount,
            activitySessionCount,
            courseCount,
            courseMeetingRuleCount
        )
        check(updated == 1) { "migration state disappeared during transaction" }
    }
}

/**
 * Room equivalent of the current PrototypeStore atomic task/plan operations.
 *
 * The runtime composition root may construct this writer only after Room is selected. Every
 * mutation is a complete-list transaction so source order and the legacy optimistic-concurrency
 * contract remain observable while the cutover gate is still disabled.
 */
class RoomCoreDataWriteRepository(
    private val store: RoomCoreDataWriteStore,
    private val currentWeekKey: () -> Long = GoalPlanner::currentWeekKey
) {
    fun replaceTasks(tasks: List<Item>, expectedTasks: List<Item>): CoreDataWriteResult =
        transact { current ->
            if (current.items != expectedTasks) return@transact staleTasks()
            validate(tasks, current.taskEvents, current.goals)?.let {
                return@transact invalidInput(it)
            }
            store.replaceTasks(tasks.toTaskEntities())
            applied()
        }

    fun replaceTasksAndAppendEvents(
        tasks: List<Item>,
        events: List<TaskEvent>,
        expectedTasks: List<Item>? = null
    ): CoreDataWriteResult = transact { current ->
        if (expectedTasks != null && current.items != expectedTasks) return@transact staleTasks()
        val updatedEvents = events.fold(current.taskEvents, TaskHistory::append)
        validate(tasks, updatedEvents, current.goals)?.let {
            return@transact invalidInput(it)
        }
        store.replaceTasks(tasks.toTaskEntities())
        store.replaceTaskEvents(updatedEvents.toTaskEventEntities())
        applied()
    }

    fun replaceTasksAppendEventsAndPlans(
        tasks: List<Item>,
        events: List<TaskEvent>,
        plans: List<Goal>,
        expectedTasks: List<Item>? = null,
        expectedPlans: List<Goal>? = null
    ): CoreDataWriteResult = transact { current ->
        if (expectedTasks != null && current.items != expectedTasks) return@transact staleTasks()
        if (expectedPlans != null && current.goals != expectedPlans) return@transact stalePlans()
        val updatedEvents = events.fold(current.taskEvents, TaskHistory::append)
        validate(tasks, updatedEvents, plans)?.let {
            return@transact invalidInput(it)
        }
        store.replaceTasks(tasks.toTaskEntities())
        store.replaceTaskEvents(updatedEvents.toTaskEventEntities())
        store.replacePlans(plans.toPlanEntities())
        applied()
    }

    fun replacePlans(
        plans: List<Goal>,
        expectedPlans: List<Goal>? = null
    ): CoreDataWriteResult =
        transact { current ->
            if (expectedPlans != null && current.goals != expectedPlans) return@transact stalePlans()
            validate(current.items, current.taskEvents, plans)?.let {
                return@transact invalidInput(it)
            }
            store.replacePlans(plans.toPlanEntities())
            applied()
        }

    fun appendTaskEvent(event: TaskEvent): CoreDataWriteResult = transact { current ->
        val updatedEvents = TaskHistory.append(current.taskEvents, event)
        validate(current.items, updatedEvents, current.goals)?.let {
            return@transact invalidInput(it)
        }
        store.replaceTaskEvents(updatedEvents.toTaskEventEntities())
        applied()
    }

    fun replaceTaskEvents(events: List<TaskEvent>): CoreDataWriteResult = transact { current ->
        validate(current.items, events, current.goals)?.let {
            return@transact invalidInput(it)
        }
        store.replaceTaskEvents(events.toTaskEventEntities())
        applied()
    }

    fun replaceActivitySessions(
        sessions: List<ActivitySession>,
        expectedSessions: List<ActivitySession>
    ): CoreDataWriteResult = transact { current ->
        if (current.activitySessions != expectedSessions) return@transact staleActivitySessions()
        validate(current.items, current.taskEvents, current.goals, sessions)?.let {
            return@transact invalidInput(it)
        }
        store.replaceActivitySessions(sessions.toActivitySessionEntities())
        val beforeById = expectedSessions.associateBy(ActivitySession::id)
        val changed = sessions.firstOrNull { beforeById[it.id] != it }
        applied(
            activityMutation = changed?.let { CoreDataActivityMutation(beforeById[it.id], it) }
        )
    }

    fun replaceCourses(courses: List<Course>, expectedCourses: List<Course>): CoreDataWriteResult = transact { current ->
        if (current.courses != expectedCourses) return@transact CoreDataWriteResult(
            CoreDataWriteStatus.STALE_COURSES, "course snapshot changed"
        )
        positiveUniqueIds(courses.map(Course::id), "courses")?.let { return@transact invalidInput(it) }
        if (courses.any { it.title.isBlank() || it.weekday !in 1..7 || it.startPeriod !in 1..20 ||
                it.endPeriod !in it.startPeriod..20 ||
                (it.effectiveFromEpochDay != null && it.effectiveUntilEpochDay != null &&
                    it.effectiveFromEpochDay > it.effectiveUntilEpochDay)
            }) return@transact invalidInput("courses contains an invalid meeting")
        store.replaceCourses(
            courses.mapIndexed { index, course -> CourseEntity.fromLegacy(course, index) },
            courses.mapIndexed { index, course -> CourseMeetingRuleEntity.fromLegacy(course, index) }
        )
        applied()
    }

    fun mutateScheduledTask(
        id: Long,
        expectedScheduledAt: Long,
        completionMinimum: Boolean? = null,
        transform: (Item) -> Item,
        event: (Item, Item) -> TaskEvent
    ): CoreDataWriteResult = transact { current ->
        val before = current.items.firstOrNull { it.id == id }
            ?: return@transact conditionNotMet("task is missing")
        if (!TaskReminderActionFreshness.matches(before, expectedScheduledAt)) {
            return@transact conditionNotMet("scheduled time changed")
        }
        val after = transform(before)
        if (after.id != before.id) return@transact invalidInput("task transform changed its ID")
        val updatedTasks = current.items.map { if (it.id == id) after else it }
        val updatedEvents = TaskHistory.append(current.taskEvents, event(before, after))
        val updatedPlans = if (completionMinimum != null && before.goalId != null) {
            incrementPlanCompletion(current.goals, before.goalId, completionMinimum, currentWeekKey())
        } else current.goals
        validate(updatedTasks, updatedEvents, updatedPlans)?.let {
            return@transact invalidInput(it)
        }
        store.replaceTasks(updatedTasks.toTaskEntities())
        store.replaceTaskEvents(updatedEvents.toTaskEventEntities())
        if (updatedPlans !== current.goals) store.replacePlans(updatedPlans.toPlanEntities())
        applied(CoreDataTaskMutation(before, after))
    }

    private fun transact(operation: (CoreDataSnapshot) -> CoreDataWriteResult): CoreDataWriteResult = try {
        store.inTransaction {
            when (val read = RoomCoreDataReadRepository(store).read()) {
                is CoreDataReadResult.NotReady -> CoreDataWriteResult(
                    CoreDataWriteStatus.NOT_READY,
                    read.reason
                )
                is CoreDataReadResult.Invalid -> CoreDataWriteResult(
                    CoreDataWriteStatus.INVALID_STATE,
                    read.reason
                )
                is CoreDataReadResult.Ready -> {
                    val result = operation(read.snapshot)
                    if (result.applied) {
                        store.updateMigrationCounts(
                            taskCount = store.tasks().size,
                            taskEventCount = store.taskEvents().size,
                            planCount = store.plans().size,
                            activitySessionCount = store.activitySessions().size,
                            courseCount = store.courses().size,
                            courseMeetingRuleCount = store.courseMeetingRules().size
                        )
                    }
                    result
                }
            }
        }
    } catch (error: Exception) {
        CoreDataWriteResult(
            CoreDataWriteStatus.WRITE_FAILED,
            "transaction failed: ${error.javaClass.simpleName}"
        )
    }

    private fun validate(
        tasks: List<Item>,
        events: List<TaskEvent>,
        plans: List<Goal>,
        sessions: List<ActivitySession> = emptyList()
    ): String? {
        positiveUniqueIds(tasks.map { it.id }, "tasks")?.let { return it }
        positiveUniqueIds(events.map { it.id }, "task_events")?.let { return it }
        positiveUniqueIds(plans.map { it.id }, "plans")?.let { return it }
        positiveUniqueIds(sessions.map { it.id }, "activity_sessions")?.let { return it }
        if (events.any { it.itemId <= 0L }) return "task_events contains an invalid task ID"
        if (events.any { it.recordedAt <= 0L }) return "task_events contains an invalid timestamp"
        if (sessions.any { it.name.isBlank() }) return "activity_sessions contains a blank name"
        if (sessions.any { it.actualStartAt <= 0L || it.endsAt <= 0L }) {
            return "activity_sessions contains an invalid timestamp"
        }
        if (sessions.any { it.extensionCount < 0 }) {
            return "activity_sessions contains a negative extension count"
        }
        if (sessions.any { it.status !in SUPPORTED_ACTIVITY_STATUSES }) {
            return "activity_sessions contains an unknown status"
        }
        return null
    }

    private fun positiveUniqueIds(ids: List<Long>, domain: String): String? = when {
        ids.any { it <= 0L } -> "$domain contains a non-positive ID"
        ids.toSet().size != ids.size -> "$domain contains a duplicate ID"
        else -> null
    }

    private fun incrementPlanCompletion(
        plans: List<Goal>,
        planId: Long,
        minimum: Boolean,
        weekKey: Long
    ): List<Goal> = plans.map { plan ->
        if (plan.id != planId) plan
        else if (plan.completionWeekKey == weekKey) {
            if (minimum) plan.copy(minimumCompletionsThisWeek = plan.minimumCompletionsThisWeek + 1)
            else plan.copy(completedThisWeek = plan.completedThisWeek + 1)
        } else if (minimum) {
            plan.copy(minimumCompletionsThisWeek = 1, completionWeekKey = weekKey)
        } else {
            plan.copy(completedThisWeek = 1, minimumCompletionsThisWeek = 0, completionWeekKey = weekKey)
        }
    }

    private fun List<Item>.toTaskEntities(): List<TaskEntity> =
        mapIndexed { index, item -> TaskEntity.fromLegacy(item, index) }

    private fun List<TaskEvent>.toTaskEventEntities(): List<TaskEventEntity> =
        mapIndexed { index, event -> TaskEventEntity.fromLegacy(event, index) }

    private fun List<Goal>.toPlanEntities(): List<PlanEntity> =
        mapIndexed { index, goal -> PlanEntity.fromLegacy(goal, index) }

    private fun List<ActivitySession>.toActivitySessionEntities(): List<ActivitySessionEntity> =
        mapIndexed { index, session -> ActivitySessionEntity.fromLegacy(session, index) }

    private fun applied(
        mutation: CoreDataTaskMutation? = null,
        activityMutation: CoreDataActivityMutation? = null
    ) = CoreDataWriteResult(
        CoreDataWriteStatus.APPLIED,
        taskMutation = mutation,
        activityMutation = activityMutation
    )

    private fun staleTasks() = CoreDataWriteResult(
        CoreDataWriteStatus.STALE_TASKS,
        "task snapshot changed"
    )

    private fun stalePlans() = CoreDataWriteResult(
        CoreDataWriteStatus.STALE_PLANS,
        "plan snapshot changed"
    )

    private fun staleActivitySessions() = CoreDataWriteResult(
        CoreDataWriteStatus.STALE_ACTIVITY_SESSIONS,
        "activity session snapshot changed"
    )

    private fun invalidInput(message: String) =
        CoreDataWriteResult(CoreDataWriteStatus.INVALID_INPUT, message)

    private fun conditionNotMet(message: String) =
        CoreDataWriteResult(CoreDataWriteStatus.CONDITION_NOT_MET, message)

    private companion object {
        val SUPPORTED_ACTIVITY_STATUSES = setOf(
            ActivitySession.STATUS_ACTIVE,
            ActivitySession.STATUS_EXTENDED,
            ActivitySession.STATUS_AWAITING_CONFIRMATION,
            ActivitySession.STATUS_COMPLETED,
            ActivitySession.STATUS_SKIPPED
        )
    }
}
