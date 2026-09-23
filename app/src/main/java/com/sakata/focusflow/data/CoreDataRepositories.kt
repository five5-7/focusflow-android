package com.sakata.focusflow.data

import android.content.Context
import com.sakata.focusflow.ActivitySession
import com.sakata.focusflow.Goal
import com.sakata.focusflow.Item
import com.sakata.focusflow.PrototypeStore
import com.sakata.focusflow.TaskEvent
import com.sakata.focusflow.TaskEventType

data class CoreDataSnapshot(
    val items: List<Item>,
    val taskEvents: List<TaskEvent>,
    val goals: List<Goal>,
    val activitySessions: List<ActivitySession> = emptyList()
)

sealed interface CoreDataReadResult {
    data class Ready(val snapshot: CoreDataSnapshot) : CoreDataReadResult
    data class NotReady(val reason: String) : CoreDataReadResult
    data class Invalid(val reason: String) : CoreDataReadResult
}

fun interface CoreDataReadRepository {
    fun read(): CoreDataReadResult
}

/** Read boundary used when the runtime composition root selects the Legacy source. */
class LegacyCoreDataReadRepository(
    private val store: PrototypeStore,
    private val itemsLoader: () -> List<Item> = store::loadItems
) : CoreDataReadRepository {
    override fun read(): CoreDataReadResult = CoreDataReadResult.Ready(
        CoreDataSnapshot(
            items = itemsLoader(),
            taskEvents = store.loadTaskEvents(),
            goals = store.loadGoals(),
            activitySessions = store.loadSessions()
        )
    )
}

interface RoomCoreDataSource {
    fun migrationState(): MigrationStateEntity?
    fun tasks(): List<TaskEntity>
    fun taskEvents(): List<TaskEventEntity>
    fun plans(): List<PlanEntity>
    fun recurrenceRuleIds(): List<Long> = emptyList()
    fun taskOccurrenceIds(): List<Long> = emptyList()
    fun activitySessions(): List<ActivitySessionEntity> = emptyList()
}

class DatabaseRoomCoreDataSource(private val database: FocusFlowDatabase) : RoomCoreDataSource {
    override fun migrationState(): MigrationStateEntity? =
        database.migrationStateDao().find(LegacyDataImporter.MIGRATION_KEY)

    override fun tasks(): List<TaskEntity> = database.taskDao().all()
    override fun taskEvents(): List<TaskEventEntity> = database.taskEventDao().all()
    override fun plans(): List<PlanEntity> = database.planDao().all()
    override fun recurrenceRuleIds(): List<Long> = database.recurrenceRuleDao().allIds()
    override fun taskOccurrenceIds(): List<Long> = database.taskOccurrenceDao().allIds()
    override fun activitySessions(): List<ActivitySessionEntity> = database.activitySessionDao().all()
}

/** Reads Room without mutating it. Invalid rows are reported instead of being dropped or fixed. */
class RoomCoreDataReadRepository(private val source: RoomCoreDataSource) : CoreDataReadRepository {
    override fun read(): CoreDataReadResult {
        val state = try {
            source.migrationState()
        } catch (error: Exception) {
            return CoreDataReadResult.Invalid("migration state read failed: ${error.javaClass.simpleName}")
        } ?: return CoreDataReadResult.NotReady("migration state is missing")

        return try {
            val tasks = source.tasks()
            val events = source.taskEvents()
            val plans = source.plans()
            val recurrenceRuleIds = source.recurrenceRuleIds()
            val taskOccurrenceIds = source.taskOccurrenceIds()
            val sessions = source.activitySessions()
            val countProblem = countProblem(
                state,
                tasks,
                events,
                plans,
                recurrenceRuleIds,
                taskOccurrenceIds,
                sessions
            )
            if (countProblem != null) return CoreDataReadResult.Invalid(countProblem)
            val orderProblem = orderProblem(tasks, events, plans, sessions)
            if (orderProblem != null) return CoreDataReadResult.Invalid(orderProblem)

            val mappedTasks = tasks.map(TaskEntity::toLegacy)
            if (tasks.zip(mappedTasks).any { (entity, item) -> entity.status != TaskStatusKey.fromLegacy(item) }) {
                return CoreDataReadResult.Invalid("tasks contains an inconsistent status")
            }
            if (plans.any { it.state != PlanEntity.ACTIVE }) {
                return CoreDataReadResult.Invalid("plans contains an unsupported state")
            }

            val mappedEvents = events.map { entity ->
                val type = TaskEventType.fromKey(entity.type)
                    ?: return CoreDataReadResult.Invalid("task_events contains an unknown type")
                entity.toLegacy(type)
            }
            CoreDataReadResult.Ready(
                CoreDataSnapshot(
                    items = mappedTasks,
                    taskEvents = mappedEvents,
                    goals = plans.map(PlanEntity::toLegacy),
                    activitySessions = sessions.map(ActivitySessionEntity::toLegacy)
                )
            )
        } catch (error: Exception) {
            CoreDataReadResult.Invalid("Room snapshot read failed: ${error.javaClass.simpleName}")
        }
    }

    private fun countProblem(
        state: MigrationStateEntity,
        tasks: List<TaskEntity>,
        events: List<TaskEventEntity>,
        plans: List<PlanEntity>,
        recurrenceRuleIds: List<Long>,
        taskOccurrenceIds: List<Long>,
        sessions: List<ActivitySessionEntity>
    ): String? = when {
        tasks.size != state.taskCount -> "tasks count does not match migration state"
        events.size != state.taskEventCount -> "task_events count does not match migration state"
        plans.size != state.planCount -> "plans count does not match migration state"
        recurrenceRuleIds.size != state.recurrenceRuleCount ->
            "recurrence_rules count does not match migration state"
        taskOccurrenceIds.size != state.taskOccurrenceCount ->
            "task_occurrences count does not match migration state"
        sessions.size != state.activitySessionCount -> "activity_sessions count does not match migration state"
        else -> null
    }

    private fun orderProblem(
        tasks: List<TaskEntity>,
        events: List<TaskEventEntity>,
        plans: List<PlanEntity>,
        sessions: List<ActivitySessionEntity>
    ): String? = when {
        tasks.any { it.sourceOrder < 0 } || tasks.map { it.sourceOrder }.toSet().size != tasks.size ->
            "tasks contains invalid source order"
        events.any { it.sourceOrder < 0 } || events.map { it.sourceOrder }.toSet().size != events.size ->
            "task_events contains invalid source order"
        plans.any { it.sourceOrder < 0 } || plans.map { it.sourceOrder }.toSet().size != plans.size ->
            "plans contains invalid source order"
        sessions.any { it.sourceOrder < 0 } || sessions.map { it.sourceOrder }.toSet().size != sessions.size ->
            "activity_sessions contains invalid source order"
        else -> null
    }
}

enum class CoreDataConsistencyStatus { CONSISTENT, NOT_READY, INVALID_SHADOW, MISMATCH }

data class CoreDataDifference(
    val domain: String,
    val reason: String,
    val legacyCount: Int,
    val roomCount: Int,
    val affectedIds: List<Long> = emptyList()
)

data class CoreDataConsistencyReport(
    val status: CoreDataConsistencyStatus,
    val differences: List<CoreDataDifference> = emptyList(),
    val message: String = ""
)

/** Produces metadata-only diagnostics; titles, notes and other user content never enter reports. */
object CoreDataConsistencyChecker {
    fun compare(legacy: CoreDataSnapshot, room: CoreDataReadResult): CoreDataConsistencyReport = when (room) {
        is CoreDataReadResult.NotReady -> CoreDataConsistencyReport(
            status = CoreDataConsistencyStatus.NOT_READY,
            message = room.reason
        )
        is CoreDataReadResult.Invalid -> CoreDataConsistencyReport(
            status = CoreDataConsistencyStatus.INVALID_SHADOW,
            message = room.reason
        )
        is CoreDataReadResult.Ready -> {
            val differences = buildList {
                difference("tasks", legacy.items, room.snapshot.items, Item::id)?.let(::add)
                difference("task_events", legacy.taskEvents, room.snapshot.taskEvents, TaskEvent::id)?.let(::add)
                difference("plans", legacy.goals, room.snapshot.goals, Goal::id)?.let(::add)
                difference(
                    "activity_sessions",
                    legacy.activitySessions,
                    room.snapshot.activitySessions,
                    ActivitySession::id
                )?.let(::add)
            }
            CoreDataConsistencyReport(
                status = if (differences.isEmpty()) CoreDataConsistencyStatus.CONSISTENT
                else CoreDataConsistencyStatus.MISMATCH,
                differences = differences
            )
        }
    }

    private fun <T> difference(
        domain: String,
        legacy: List<T>,
        room: List<T>,
        id: (T) -> Long
    ): CoreDataDifference? {
        if (legacy == room) return null
        val legacyIds = legacy.map(id)
        val roomIds = room.map(id)
        val reason = when {
            legacyIds == roomIds -> "content differs"
            legacyIds.toSet() == roomIds.toSet() -> "order differs"
            else -> "membership differs"
        }
        val affectedIds = when (reason) {
            "content differs" -> legacy.zip(room)
                .filter { (left, right) -> left != right }
                .map { (left, _) -> id(left) }
            "order differs" -> legacyIds.filterIndexed { index, value -> roomIds.getOrNull(index) != value }
            else -> (legacyIds.toSet() subtract roomIds.toSet()) + (roomIds.toSet() subtract legacyIds.toSet())
        }.take(MAX_REPORTED_IDS)
        return CoreDataDifference(domain, reason, legacy.size, room.size, affectedIds)
    }

    private const val MAX_REPORTED_IDS = 20
}

/** Opens an already-created Room database for one shadow read and never creates a new database. */
object ExistingRoomCoreDataReader {
    fun read(context: Context): CoreDataReadResult {
        val appContext = context.applicationContext
        if (!appContext.getDatabasePath(FocusFlowDatabase.FILE_NAME).exists()) {
            return CoreDataReadResult.NotReady("database file is missing")
        }
        return try {
            val database = FocusFlowDatabase.create(appContext)
            try {
                RoomCoreDataReadRepository(DatabaseRoomCoreDataSource(database)).read()
            } finally {
                database.close()
            }
        } catch (error: Exception) {
            CoreDataReadResult.Invalid("database open failed: ${error.javaClass.simpleName}")
        }
    }
}

internal fun TaskEntity.toLegacy(): Item = Item(
    id = id,
    title = title,
    detail = detail,
    kind = legacyKind,
    done = done,
    scheduledAt = scheduledAt,
    dayOnly = dayOnly,
    goalId = planId,
    completionLevel = completionLevel,
    completedAt = completedAt,
    durationMinutes = durationMinutes,
    windowStartAt = windowStartAt,
    windowEndAt = windowEndAt,
    rescheduleCount = rescheduleCount,
    lastRescheduledAt = lastRescheduledAt,
    recoverySourceScheduledAt = recoverySourceScheduledAt,
    priority = priority,
    captureRoute = captureRoute,
    sourceDetail = sourceDetail,
    userNote = userNote,
    nextAction = nextAction,
    parentCaptureId = parentCaptureId
)

internal fun TaskEventEntity.toLegacy(type: TaskEventType): TaskEvent = TaskEvent(
    id = id,
    itemId = taskId,
    type = type,
    recordedAt = recordedAt,
    title = title,
    scheduledAt = scheduledAt,
    extra = extra
)

internal fun PlanEntity.toLegacy(): Goal = Goal(
    id = id,
    title = title,
    weeklyTarget = weeklyTarget,
    durationMinutes = durationMinutes,
    metricType = metricType,
    metricTarget = metricTarget,
    minimumVersion = minimumVersion,
    resourceTitle = resourceTitle,
    resourceUnit = resourceUnit,
    completedThisWeek = completedThisWeek,
    minimumCompletionsThisWeek = minimumCompletionsThisWeek,
    completionWeekKey = completionWeekKey,
    desiredOutcome = desiredOutcome,
    firstAction = firstAction,
    sourceNotes = sourceNotes
)
