package com.sakata.focusflow.data

import com.sakata.focusflow.ActivitySession
import com.sakata.focusflow.Course
import com.sakata.focusflow.Goal
import com.sakata.focusflow.Item
import com.sakata.focusflow.PrototypeStore
import com.sakata.focusflow.TaskEvent
import com.sakata.focusflow.TaskEventType
import com.sakata.focusflow.TaskRecorder

/**
 * Single runtime boundary for Task, TaskEvent and Plan data.
 *
 * Runtime construction is owned by [CoreDataRuntimeCompositionRoot]. Room and Legacy expose the
 * same contract, while the composition root guarantees that only the selected writer is created.
 */
interface CoreDataRepository : CoreDataReadRepository {
    val source: CoreDataRuntimeSource

    fun replaceTasks(tasks: List<Item>, expectedTasks: List<Item>): CoreDataWriteResult

    fun replaceTasksAndAppendEvents(
        tasks: List<Item>,
        events: List<TaskEvent>,
        expectedTasks: List<Item>
    ): CoreDataWriteResult

    fun replaceTasksAppendEventsAndPlans(
        tasks: List<Item>,
        events: List<TaskEvent>,
        plans: List<Goal>,
        expectedTasks: List<Item>,
        expectedPlans: List<Goal>
    ): CoreDataWriteResult

    fun replacePlans(plans: List<Goal>, expectedPlans: List<Goal>): CoreDataWriteResult
    fun appendTaskEvent(event: TaskEvent): CoreDataWriteResult
    fun replaceTaskEvents(events: List<TaskEvent>): CoreDataWriteResult

    fun replaceActivitySessions(
        sessions: List<ActivitySession>,
        expectedSessions: List<ActivitySession>
    ): CoreDataWriteResult

    fun replaceCourses(courses: List<Course>, expectedCourses: List<Course>): CoreDataWriteResult

    fun mutateScheduledTask(
        id: Long,
        expectedScheduledAt: Long,
        completionMinimum: Boolean? = null,
        transform: (Item) -> Item,
        event: (Item, Item) -> TaskEvent
    ): CoreDataWriteResult

    /** Runs the pre-Room task-history bootstrap only when the selected source still needs it. */
    fun ensureTaskHistoryMigrated(): CoreDataWriteResult

    /** Stage 7.3: permanently removes the given trashed ids. Ids already gone are ignored. */
    fun purgeTrash(purgedIds: Set<Long>): CoreDataWriteResult

    /** Stage 7.3: permanently removes every group member whose retention elapsed at [now]. */
    fun purgeExpiredTrash(now: Long): CoreDataWriteResult
}

internal interface LegacyCoreDataPersistence {
    fun read(): CoreDataSnapshot
    fun saveItemsIfUnchanged(items: List<Item>, expectedItems: List<Item>): Boolean
    fun saveItemsAndTaskEvents(items: List<Item>, events: List<TaskEvent>, expectedItems: List<Item>): Boolean
    fun saveItemsTaskEventsAndGoals(
        items: List<Item>,
        events: List<TaskEvent>,
        goals: List<Goal>,
        expectedItems: List<Item>,
        expectedGoals: List<Goal>
    ): Boolean
    fun saveGoalsIfUnchanged(goals: List<Goal>, expectedGoals: List<Goal>): Boolean
    fun appendTaskEvent(event: TaskEvent)
    fun replaceTaskEvents(events: List<TaskEvent>): Boolean
    fun saveActivitySessionsIfUnchanged(
        sessions: List<ActivitySession>,
        expectedSessions: List<ActivitySession>
    ): Boolean
    fun saveCoursesIfUnchanged(courses: List<Course>, expectedCourses: List<Course>): Boolean
    fun mutateScheduledTask(
        id: Long,
        expectedScheduledAt: Long,
        completionMinimum: Boolean?,
        transform: (Item) -> Item,
        event: (Item, Item) -> TaskEvent
    ): CoreDataTaskMutation?
    fun migrateTaskHistory()
    fun purgeTrash(purgedIds: Set<Long>): Boolean
    fun purgeExpiredTrash(now: Long): Boolean
}

private class PrototypeStoreCoreDataPersistence(
    private val store: PrototypeStore
) : LegacyCoreDataPersistence, CourseRecoveryStorage {
    override fun read(): CoreDataSnapshot =
        (LegacyCoreDataReadRepository(store).read() as CoreDataReadResult.Ready).snapshot

    override fun saveItemsIfUnchanged(items: List<Item>, expectedItems: List<Item>): Boolean =
        store.saveItemsIfUnchanged(items, expectedItems)

    override fun saveItemsAndTaskEvents(
        items: List<Item>,
        events: List<TaskEvent>,
        expectedItems: List<Item>
    ): Boolean = store.saveItemsAndTaskEvents(items, events, expectedItems)

    override fun saveItemsTaskEventsAndGoals(
        items: List<Item>,
        events: List<TaskEvent>,
        goals: List<Goal>,
        expectedItems: List<Item>,
        expectedGoals: List<Goal>
    ): Boolean = store.saveItemsTaskEventsAndGoals(
        items,
        events,
        goals,
        expectedItems,
        expectedGoals
    )

    override fun saveGoalsIfUnchanged(goals: List<Goal>, expectedGoals: List<Goal>): Boolean =
        store.saveGoalsIfUnchanged(goals, expectedGoals)

    override fun appendTaskEvent(event: TaskEvent) = store.appendTaskEvent(event)

    override fun replaceTaskEvents(events: List<TaskEvent>): Boolean = store.replaceTaskEvents(events)

    override fun saveActivitySessionsIfUnchanged(
        sessions: List<ActivitySession>,
        expectedSessions: List<ActivitySession>
    ): Boolean = store.saveActivitySessionsIfUnchanged(sessions, expectedSessions)

    override fun saveCoursesIfUnchanged(courses: List<Course>, expectedCourses: List<Course>): Boolean =
        store.saveCoursesIfUnchanged(courses, expectedCourses)

    override fun mutateScheduledTask(
        id: Long,
        expectedScheduledAt: Long,
        completionMinimum: Boolean?,
        transform: (Item) -> Item,
        event: (Item, Item) -> TaskEvent
    ): CoreDataTaskMutation? = store.mutateScheduledTask(
        id,
        expectedScheduledAt,
        completionMinimum,
        transform,
        event
    )?.let { CoreDataTaskMutation(it.before, it.after) }

    override fun migrateTaskHistory() {
        store.migrateTaskHistory()
    }

    override fun purgeTrash(purgedIds: Set<Long>): Boolean = store.purgeTrash(purgedIds)

    override fun purgeExpiredTrash(now: Long): Boolean = store.purgeExpiredTrash(now)

    // ------------------------------------------------------------ stage 7.5 course recovery

    override fun isStorageReadOnly(): Boolean = store.isStorageReadOnly()

    override fun <T> withCourseWriteLock(block: () -> T): T = store.withCourseWriteLock(block)

    override fun readCourses(): List<Course> = store.readCourses()

    override fun loadCourseRecoveryGroups(): CourseRecoveryGroupsRead = store.loadCourseRecoveryGroups()

    override fun hasPendingCourseEditJournal(): Boolean = store.hasPendingCourseEditJournal()

    override fun commitCoursesAndRecoveryGroups(
        courses: List<Course>,
        groups: List<CourseRecoveryGroup>,
        expectedCourses: List<Course>,
        expectedGroups: List<CourseRecoveryGroup>
    ): CourseRecoveryCommit = store.commitCoursesAndRecoveryGroups(courses, groups, expectedCourses, expectedGroups)

    override fun commitCourseRecoveryGroups(
        groups: List<CourseRecoveryGroup>,
        expectedGroups: List<CourseRecoveryGroup>
    ): CourseRecoveryCommit = store.commitCourseRecoveryGroups(groups, expectedGroups)
}

/** Legacy adapter that preserves the existing optimistic-concurrency and atomic commit rules. */
class LegacyCoreDataRepository internal constructor(
    private val persistence: LegacyCoreDataPersistence
) : CoreDataRepository {
    constructor(store: PrototypeStore) : this(PrototypeStoreCoreDataPersistence(store))

    override val source: CoreDataRuntimeSource = CoreDataRuntimeSource.LEGACY

    /**
     * Stage 7.5: the recovery store shares this adapter's persistence primitives (same lock, same
     * commit). A persistence that does not implement [CourseRecoveryStorage] reports "not ready"
     * instead of silently falling back to the in-memory path.
     */
    internal val recoveryStore: CourseRecoveryStore =
        (persistence as? CourseRecoveryStorage)?.let(::LegacyCourseRecoveryStore)
            ?: UnsupportedCourseRecoveryStore

    /**
     * The course write lock for this runtime. Every Legacy course writer (ordinary saves, the
     * recovery store, the merge/split journal operations) takes it, so a course read, a journal
     * check or creation and the course commit cannot interleave. Runtimes without such a lock keep
     * their previous behaviour instead of pretending to be serialized.
     */
    internal fun <T> withCourseWriteLock(block: () -> T): T =
        (persistence as? CourseRecoveryStorage)?.withCourseWriteLock(block) ?: block()

    override fun read(): CoreDataReadResult = try {
        CoreDataReadResult.Ready(persistence.read())
    } catch (error: Exception) {
        CoreDataReadResult.Invalid("legacy snapshot read failed: ${error.javaClass.simpleName}")
    }

    override fun replaceTasks(
        tasks: List<Item>,
        expectedTasks: List<Item>
    ): CoreDataWriteResult = resultAfterWrite(
        applied = persistence.saveItemsIfUnchanged(tasks, expectedTasks),
        expectedTasks = expectedTasks
    )

    override fun replaceTasksAndAppendEvents(
        tasks: List<Item>,
        events: List<TaskEvent>,
        expectedTasks: List<Item>
    ): CoreDataWriteResult = resultAfterWrite(
        applied = persistence.saveItemsAndTaskEvents(tasks, events, expectedTasks),
        expectedTasks = expectedTasks
    )

    override fun replaceTasksAppendEventsAndPlans(
        tasks: List<Item>,
        events: List<TaskEvent>,
        plans: List<Goal>,
        expectedTasks: List<Item>,
        expectedPlans: List<Goal>
    ): CoreDataWriteResult = resultAfterWrite(
        applied = persistence.saveItemsTaskEventsAndGoals(
            tasks,
            events,
            plans,
            expectedTasks,
            expectedPlans
        ),
        expectedTasks = expectedTasks,
        expectedPlans = expectedPlans
    )

    override fun replacePlans(
        plans: List<Goal>,
        expectedPlans: List<Goal>
    ): CoreDataWriteResult = resultAfterWrite(
        applied = persistence.saveGoalsIfUnchanged(plans, expectedPlans),
        expectedPlans = expectedPlans
    )

    override fun appendTaskEvent(event: TaskEvent): CoreDataWriteResult = try {
        persistence.appendTaskEvent(event)
        applied()
    } catch (error: Exception) {
        failed(error)
    }

    override fun replaceTaskEvents(events: List<TaskEvent>): CoreDataWriteResult =
        if (persistence.replaceTaskEvents(events)) applied()
        else CoreDataWriteResult(CoreDataWriteStatus.WRITE_FAILED, "legacy write failed")

    override fun replaceActivitySessions(
        sessions: List<ActivitySession>,
        expectedSessions: List<ActivitySession>
    ): CoreDataWriteResult {
        val beforeById = expectedSessions.associateBy(ActivitySession::id)
        val changed = sessions.firstOrNull { beforeById[it.id] != it }
        return resultAfterWrite(
            applied = persistence.saveActivitySessionsIfUnchanged(sessions, expectedSessions),
            expectedActivitySessions = expectedSessions,
            activityMutation = changed?.let { CoreDataActivityMutation(beforeById[it.id], it) }
        )
    }

    override fun replaceCourses(courses: List<Course>, expectedCourses: List<Course>): CoreDataWriteResult =
        resultAfterWrite(
            applied = persistence.saveCoursesIfUnchanged(courses, expectedCourses),
            expectedCourses = expectedCourses
        )

    override fun mutateScheduledTask(
        id: Long,
        expectedScheduledAt: Long,
        completionMinimum: Boolean?,
        transform: (Item) -> Item,
        event: (Item, Item) -> TaskEvent
    ): CoreDataWriteResult = try {
        val mutation = persistence.mutateScheduledTask(
            id,
            expectedScheduledAt,
            completionMinimum,
            transform,
            event
        ) ?: return CoreDataWriteResult(
            CoreDataWriteStatus.CONDITION_NOT_MET,
            "task is missing, stale or could not be written"
        )
        applied(mutation)
    } catch (error: Exception) {
        failed(error)
    }

    override fun ensureTaskHistoryMigrated(): CoreDataWriteResult = try {
        persistence.migrateTaskHistory()
        applied()
    } catch (error: Exception) {
        failed(error)
    }

    override fun purgeTrash(purgedIds: Set<Long>): CoreDataWriteResult = try {
        if (persistence.purgeTrash(purgedIds)) applied()
        else CoreDataWriteResult(CoreDataWriteStatus.WRITE_FAILED, "legacy trash purge failed")
    } catch (error: Exception) {
        failed(error)
    }

    override fun purgeExpiredTrash(now: Long): CoreDataWriteResult = try {
        if (persistence.purgeExpiredTrash(now)) applied()
        else CoreDataWriteResult(CoreDataWriteStatus.WRITE_FAILED, "legacy trash expiry failed")
    } catch (error: Exception) {
        failed(error)
    }

    private fun resultAfterWrite(
        applied: Boolean,
        expectedTasks: List<Item>? = null,
        expectedPlans: List<Goal>? = null,
        expectedActivitySessions: List<ActivitySession>? = null,
        expectedCourses: List<Course>? = null,
        activityMutation: CoreDataActivityMutation? = null
    ): CoreDataWriteResult {
        if (applied) return applied(activityMutation = activityMutation)
        val current = try { persistence.read() } catch (error: Exception) {
            return CoreDataWriteResult(CoreDataWriteStatus.WRITE_FAILED,
                "legacy snapshot read failed: ${error.javaClass.simpleName}")
        }
        return when {
            expectedTasks != null && current.items != expectedTasks -> CoreDataWriteResult(
                CoreDataWriteStatus.STALE_TASKS,
                "task snapshot changed"
            )
            expectedPlans != null && current.goals != expectedPlans -> CoreDataWriteResult(
                CoreDataWriteStatus.STALE_PLANS,
                "plan snapshot changed"
            )
            expectedActivitySessions != null && current.activitySessions != expectedActivitySessions ->
                CoreDataWriteResult(
                    CoreDataWriteStatus.STALE_ACTIVITY_SESSIONS,
                    "activity session snapshot changed"
                )
            expectedCourses != null && current.courses != expectedCourses -> CoreDataWriteResult(
                CoreDataWriteStatus.STALE_COURSES,
                "course snapshot changed"
            )
            else -> CoreDataWriteResult(CoreDataWriteStatus.WRITE_FAILED, "legacy write failed")
        }
    }

    private fun applied(
        mutation: CoreDataTaskMutation? = null,
        activityMutation: CoreDataActivityMutation? = null
    ) = CoreDataWriteResult(
        CoreDataWriteStatus.APPLIED,
        taskMutation = mutation,
        activityMutation = activityMutation
    )

    private fun failed(error: Exception) = CoreDataWriteResult(
        CoreDataWriteStatus.WRITE_FAILED,
        "legacy write failed: ${error.javaClass.simpleName}"
    )
}

/** Room adapter is constructed only after the activation coordinator selects Room. */
class RoomCoreDataRepository(
    private val reader: CoreDataReadRepository,
    private val writer: RoomCoreDataWriteRepository
) : CoreDataRepository {
    override val source: CoreDataRuntimeSource = CoreDataRuntimeSource.ROOM
    override fun read(): CoreDataReadResult = reader.read()

    override fun replaceTasks(tasks: List<Item>, expectedTasks: List<Item>): CoreDataWriteResult =
        writer.replaceTasks(tasks, expectedTasks)

    override fun replaceTasksAndAppendEvents(
        tasks: List<Item>,
        events: List<TaskEvent>,
        expectedTasks: List<Item>
    ): CoreDataWriteResult = writer.replaceTasksAndAppendEvents(tasks, events, expectedTasks)

    override fun replaceTasksAppendEventsAndPlans(
        tasks: List<Item>,
        events: List<TaskEvent>,
        plans: List<Goal>,
        expectedTasks: List<Item>,
        expectedPlans: List<Goal>
    ): CoreDataWriteResult = writer.replaceTasksAppendEventsAndPlans(
        tasks,
        events,
        plans,
        expectedTasks,
        expectedPlans
    )

    override fun replacePlans(plans: List<Goal>, expectedPlans: List<Goal>): CoreDataWriteResult =
        writer.replacePlans(plans, expectedPlans)

    override fun appendTaskEvent(event: TaskEvent): CoreDataWriteResult = writer.appendTaskEvent(event)
    override fun replaceTaskEvents(events: List<TaskEvent>): CoreDataWriteResult = writer.replaceTaskEvents(events)

    override fun replaceActivitySessions(
        sessions: List<ActivitySession>,
        expectedSessions: List<ActivitySession>
    ): CoreDataWriteResult = writer.replaceActivitySessions(sessions, expectedSessions)

    override fun replaceCourses(courses: List<Course>, expectedCourses: List<Course>): CoreDataWriteResult =
        writer.replaceCourses(courses, expectedCourses)

    override fun mutateScheduledTask(
        id: Long,
        expectedScheduledAt: Long,
        completionMinimum: Boolean?,
        transform: (Item) -> Item,
        event: (Item, Item) -> TaskEvent
    ): CoreDataWriteResult = writer.mutateScheduledTask(
        id,
        expectedScheduledAt,
        completionMinimum,
        transform,
        event
    )

    override fun ensureTaskHistoryMigrated(): CoreDataWriteResult = applied()

    override fun purgeTrash(purgedIds: Set<Long>): CoreDataWriteResult = writer.purgeTrash(purgedIds)

    override fun purgeExpiredTrash(now: Long): CoreDataWriteResult = writer.purgeExpiredTrash(now)

    private fun applied() = CoreDataWriteResult(CoreDataWriteStatus.APPLIED)
}

/** Cross-source operations that must behave identically before and after eventual activation. */
object CoreDataRepositoryOperations {
    fun latestActiveSession(repository: CoreDataRepository): ActivitySession? =
        snapshot(repository)?.activitySessions?.lastOrNull(ActivitySession::isOpen)

    fun findActivitySession(repository: CoreDataRepository, id: Long): ActivitySession? =
        snapshot(repository)?.activitySessions?.firstOrNull { it.id == id }

    fun recentActivitySessions(
        repository: CoreDataRepository,
        limit: Int = 20
    ): List<ActivitySession> = snapshot(repository)?.activitySessions
        ?.takeLast(limit.coerceAtLeast(1))
        ?.reversed()
        .orEmpty()

    fun saveActivitySession(
        repository: CoreDataRepository,
        session: ActivitySession
    ): CoreDataWriteResult {
        val read = repository.read()
        val current = (read as? CoreDataReadResult.Ready)?.snapshot ?: return invalidRead(read)
        if (current.activitySessions.any { it.id == session.id }) {
            return CoreDataWriteResult(CoreDataWriteStatus.CONDITION_NOT_MET, "activity ID already exists")
        }
        if (session.isOpen() && current.activitySessions.any { it.id != session.id && it.isOpen() }) {
            return CoreDataWriteResult(CoreDataWriteStatus.CONDITION_NOT_MET, "another activity is already running")
        }
        val updated = current.activitySessions + session
        return repository.replaceActivitySessions(updated, current.activitySessions)
    }

    fun finishAndStartNextActivitySession(
        repository: CoreDataRepository,
        id: Long,
        nextSession: ActivitySession,
        endedAt: Long = System.currentTimeMillis(),
        expectedEndsAt: Long? = null
    ): CoreDataWriteResult {
        val read = repository.read()
        val current = (read as? CoreDataReadResult.Ready)?.snapshot ?: return invalidRead(read)
        val before = current.activitySessions.firstOrNull { it.id == id }
            ?: return CoreDataWriteResult(CoreDataWriteStatus.CONDITION_NOT_MET, "activity session is missing")
        if (!before.isOpen() || (expectedEndsAt != null && expectedEndsAt > 0L &&
                before.endsAt != expectedEndsAt)) {
            return CoreDataWriteResult(CoreDataWriteStatus.CONDITION_NOT_MET, "activity session changed")
        }
        if (nextSession.id == id || current.activitySessions.any { it.id == nextSession.id || (it.id != id && it.isOpen()) }) {
            return CoreDataWriteResult(CoreDataWriteStatus.INVALID_INPUT, "next activity ID already exists")
        }
        val finished = before.copy(
            status = ActivitySession.STATUS_COMPLETED,
            actualEndAt = endedAt,
            endChoice = "started_next"
        )
        val updated = current.activitySessions.filterNot { it.id == id } + finished + nextSession
        return repository.replaceActivitySessions(updated, current.activitySessions)
    }

    fun finishActivitySession(
        repository: CoreDataRepository,
        id: Long,
        status: String,
        choice: String,
        endedAt: Long = System.currentTimeMillis(),
        expectedEndsAt: Long? = null
    ): CoreDataWriteResult = mutateActivitySession(repository, id, expectedEndsAt) { current ->
        if (!current.isOpen()) null
        else current.copy(status = status, actualEndAt = endedAt, endChoice = choice)
    }

    fun extendActivitySession(
        repository: CoreDataRepository,
        id: Long,
        minutes: Int,
        maxExtensions: Int,
        reason: String = "",
        expectedEndsAt: Long? = null,
        now: Long = System.currentTimeMillis()
    ): CoreDataWriteResult = mutateActivitySession(repository, id, expectedEndsAt) { current ->
        if (!current.isOpen() || current.extensionCount >= maxExtensions) null
        else current.copy(
            endsAt = now + minutes.coerceIn(1, 180) * 60_000L,
            status = ActivitySession.STATUS_EXTENDED,
            extensionCount = current.extensionCount + 1,
            extensionReason = reason,
            actualEndAt = null,
            endChoice = ""
        )
    }

    fun markActivitySessionAwaitingConfirmation(
        repository: CoreDataRepository,
        id: Long,
        expectedEndsAt: Long? = null
    ): CoreDataWriteResult = mutateActivitySession(repository, id, expectedEndsAt) { current ->
        if (!current.isOpen()) null
        else current.copy(status = ActivitySession.STATUS_AWAITING_CONFIRMATION)
    }

    fun recoverMissedGoalTasks(
        repository: CoreDataRepository,
        now: Long = System.currentTimeMillis()
    ): CoreDataReadResult {
        val read = repository.read()
        if (read !is CoreDataReadResult.Ready) return read
        val cutoff = now - MISSED_GOAL_GRACE_MS
        val events = mutableListOf<TaskEvent>()
        val recovered = read.snapshot.items.map { item ->
            if (item.goalId != null && item.kind == "任务" && !item.done &&
                (item.scheduledAt ?: Long.MAX_VALUE) < cutoff
            ) {
                events += TaskRecorder.event(
                    TaskEventType.TASK_TO_INBOX,
                    item.id,
                    item.title,
                    extra = "错过自动放回"
                )
                item.copy(
                    title = if (item.title.startsWith("重新安排：")) item.title else "重新安排：${item.title}",
                    kind = "收集箱",
                    detail = "上次目标安排未确认；可改期、缩短、暂停或放弃",
                    scheduledAt = null
                )
            } else item
        }
        if (recovered != read.snapshot.items) {
            repository.replaceTasksAndAppendEvents(recovered, events, read.snapshot.items)
            return repository.read()
        }
        return read
    }

    fun addReplanItem(repository: CoreDataRepository, activityName: String): CoreDataWriteResult {
        val read = repository.read()
        if (read !is CoreDataReadResult.Ready) return read.toWriteResult()
        val item = Item(
            title = "重新安排：$activityName",
            detail = "由未完成的活动转回；可以改期、缩短或暂停",
            kind = "收集箱"
        )
        return repository.replaceTasksAndAppendEvents(
            tasks = listOf(item) + read.snapshot.items,
            events = listOf(TaskRecorder.event(TaskEventType.TASK_CREATED, item.id, item.title)),
            expectedTasks = read.snapshot.items
        )
    }

    fun findTask(repository: CoreDataRepository, id: Long): Item? =
        (repository.read() as? CoreDataReadResult.Ready)?.snapshot?.items?.firstOrNull { it.id == id }

    private fun mutateActivitySession(
        repository: CoreDataRepository,
        id: Long,
        expectedEndsAt: Long?,
        transform: (ActivitySession) -> ActivitySession?
    ): CoreDataWriteResult {
        val read = repository.read()
        val current = (read as? CoreDataReadResult.Ready)?.snapshot
            ?: return invalidRead(read)
        val before = current.activitySessions.firstOrNull { it.id == id }
            ?: return CoreDataWriteResult(CoreDataWriteStatus.CONDITION_NOT_MET, "activity session is missing")
        if (expectedEndsAt != null && expectedEndsAt > 0L && before.endsAt != expectedEndsAt) {
            return CoreDataWriteResult(CoreDataWriteStatus.CONDITION_NOT_MET, "activity session end changed")
        }
        val after = transform(before)
            ?: return CoreDataWriteResult(CoreDataWriteStatus.CONDITION_NOT_MET, "activity session is closed or capped")
        if (after.id != before.id) {
            return CoreDataWriteResult(CoreDataWriteStatus.INVALID_INPUT, "activity transform changed its ID")
        }
        val updated = current.activitySessions.filterNot { it.id == id } + after
        return repository.replaceActivitySessions(updated, current.activitySessions)
    }

    private fun snapshot(repository: CoreDataRepository): CoreDataSnapshot? =
        (repository.read() as? CoreDataReadResult.Ready)?.snapshot

    private fun invalidRead(read: CoreDataReadResult): CoreDataWriteResult = when (read) {
        is CoreDataReadResult.NotReady -> CoreDataWriteResult(CoreDataWriteStatus.NOT_READY, read.reason)
        is CoreDataReadResult.Invalid -> CoreDataWriteResult(CoreDataWriteStatus.INVALID_STATE, read.reason)
        is CoreDataReadResult.Ready -> CoreDataWriteResult(CoreDataWriteStatus.WRITE_FAILED, "snapshot unavailable")
    }

    private fun CoreDataReadResult.toWriteResult(): CoreDataWriteResult = when (this) {
        is CoreDataReadResult.NotReady -> CoreDataWriteResult(CoreDataWriteStatus.NOT_READY, reason)
        is CoreDataReadResult.Invalid -> CoreDataWriteResult(CoreDataWriteStatus.INVALID_STATE, reason)
        is CoreDataReadResult.Ready -> error("ready snapshots are handled before conversion")
    }

    private const val MISSED_GOAL_GRACE_MS = 2 * 60 * 60_000L
}
