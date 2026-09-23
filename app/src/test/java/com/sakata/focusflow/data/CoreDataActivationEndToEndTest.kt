package com.sakata.focusflow.data

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sakata.focusflow.ActivitySession
import com.sakata.focusflow.Item
import com.sakata.focusflow.PrototypeStore
import com.sakata.focusflow.TaskEvent
import com.sakata.focusflow.TaskEventType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CoreDataActivationEndToEndTest {
    private lateinit var context: Context
    private lateinit var preferences: SharedPreferences
    private val databases = mutableListOf<FocusFlowDatabase>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        check(preferences.edit().clear().commit())
    }

    @After
    fun tearDown() {
        databases.forEach { database ->
            if (database.isOpen) database.close()
        }
        preferences.edit().clear().commit()
    }

    @Test
    fun `8_2_1 fixture activates into a real Room database without changing legacy payload`() {
        assertFirstActivation("8.2.1")
    }

    @Test
    fun `8_3 fixture activates into a real Room database without changing legacy payload`() {
        assertFirstActivation("8.3-rc.12")
    }

    @Test
    fun `same named courses retain separate parents and complete meeting details`() {
        installFixture("8.3-rc.12")
        val raw = """[{"id":93,"title":"高数","weekday":1,"startPeriod":1,"endPeriod":2,"building":"东一","zone":"CAMPUS","needsConfirmation":false},{"id":17,"title":"高数","weekday":4,"startPeriod":3,"endPeriod":4,"building":"东二","zone":"CAMPUS","enabled":false,"effectiveFromEpochDay":21000,"effectiveUntilEpochDay":21100}]"""
        // Use an existing enum value to keep this test independent of campus presets.
        val zone = com.sakata.focusflow.CampusZone.entries.first().name
        check(preferences.edit().putString(LegacyPreferencesReader.KEY_COURSES, raw.replace("CAMPUS", zone)).commit())
        val before = corePayload()
        val database = newDatabase()
        val ready = runtime(
            database, SharedPreferencesCoreDataActivationStore(context),
            SharedPreferencesMigrationMarker(context), true, sequenceOf(100L, 200L)
        ).resolve() as CoreDataRuntimeResolution.Ready

        val courses = (ready.repository.read() as CoreDataReadResult.Ready).snapshot.courses
        assertEquals(listOf(93L, 17L), courses.map { it.id })
        assertEquals(listOf("东一", "东二"), courses.map { it.building })
        assertEquals(listOf(true, false), courses.map { it.enabled })
        assertEquals(21000L, courses[1].effectiveFromEpochDay)
        assertEquals(listOf(17L, 93L), database.courseDao().allIds())
        assertEquals(listOf(17L, 93L), database.courseMeetingRuleDao().allIds())
        assertEquals(2, database.migrationStateDao().find(LegacyDataImporter.MIGRATION_KEY)?.courseCount)
        assertEquals(2, database.migrationStateDao().find(LegacyDataImporter.MIGRATION_KEY)?.courseMeetingRuleCount)
        assertEquals(before, corePayload())
    }

    @Test
    fun `invalid course fails before Room import and leaves source intact`() {
        installFixture("8.3-rc.12")
        val raw = """[{"id":6,"title":"bad","weekday":9,"startPeriod":1,"endPeriod":2,"building":"A","zone":"X"}]"""
        check(preferences.edit().putString(LegacyPreferencesReader.KEY_COURSES, raw).commit())
        val database = newDatabase()
        val result = LegacyDataImporter(
            LegacyPreferencesReader.fromContext(context), RoomLegacyMigrationStore(database),
            SharedPreferencesMigrationMarker(context)
        ).importIfNeeded()

        assertEquals(MigrationStatus.BLOCKED_CORRUPT_SOURCE, result.status)
        assertEquals(raw, preferences.getString(LegacyPreferencesReader.KEY_COURSES, null))
        assertTrue(RoomLegacyMigrationStore(database).summary().isEmpty)
    }

    @Test
    fun `activating restart resumes the committed import without duplicate rows`() {
        installFixture("8.3-rc.12")
        val database = newDatabase()
        val activationStore = SharedPreferencesCoreDataActivationStore(context)
        val failingMarker = SwitchableMigrationMarker(succeeds = false)
        val interrupted = runtime(
            database = database,
            activationStore = activationStore,
            migrationMarker = failingMarker,
            activationEnabled = true,
            now = sequenceOf(100L, 200L)
        ).resolve() as CoreDataRuntimeResolution.Blocked

        assertEquals(CoreDataActivationStatus.BLOCKED_MIGRATION, interrupted.decision.status)
        assertEquals(MigrationStatus.MARKER_WRITE_FAILED, interrupted.decision.migrationStatus)
        assertTrue(activationStore.read() is CoreDataActivationMarkerState.Activating)
        assertEquals(listOf(301L), database.taskDao().allIds())
        assertEquals(listOf(501L, 502L), database.taskEventDao().allIds())
        assertEquals(listOf(401L), database.planDao().allIds())
        assertEquals(listOf(801L, 802L), database.activitySessionDao().allIds())

        failingMarker.succeeds = true
        val restarted = runtime(
            database = database,
            activationStore = activationStore,
            migrationMarker = failingMarker,
            activationEnabled = false,
            now = sequenceOf(300L)
        ).resolve() as CoreDataRuntimeResolution.Ready

        assertEquals(CoreDataRuntimeSource.ROOM, restarted.repository.source)
        assertEquals(CoreDataActivationStatus.ROOM_ACTIVATED, restarted.decision.status)
        assertEquals(MigrationStatus.ALREADY_IMPORTED, restarted.decision.migrationStatus)
        assertTrue(activationStore.read() is CoreDataActivationMarkerState.Active)
        assertEquals(listOf(301L), database.taskDao().allIds())
        assertEquals(listOf(501L, 502L), database.taskEventDao().allIds())
        assertEquals(listOf(401L), database.planDao().allIds())
        assertEquals(listOf(801L, 802L), database.activitySessionDao().allIds())
        assertEquals(2, failingMarker.calls)
    }

    @Test
    fun `receiver style task completion writes only Room after activation`() {
        installFixture("8.2.1")
        val legacyBefore = corePayload()
        val database = newDatabase()
        val ready = runtime(
            database = database,
            activationStore = SharedPreferencesCoreDataActivationStore(context),
            migrationMarker = SharedPreferencesMigrationMarker(context),
            activationEnabled = true,
            now = sequenceOf(100L, 200L)
        ).resolve() as CoreDataRuntimeResolution.Ready

        val result = ready.repository.mutateScheduledTask(
            id = 102L,
            expectedScheduledAt = 1_789_992_000_000L,
            completionMinimum = false,
            transform = { current ->
                current.copy(done = true, completionLevel = "完整完成", completedAt = 1_790_000_000_000L)
            },
            event = { current, _ ->
                TaskEvent(
                    id = 9_001L,
                    itemId = current.id,
                    type = TaskEventType.TASK_COMPLETED,
                    recordedAt = 1_790_000_000_000L,
                    title = current.title,
                    extra = "完整完成"
                )
            }
        )

        assertTrue(result.applied)
        assertEquals(CoreDataRuntimeSource.ROOM, ready.repository.source)
        assertEquals(legacyBefore, corePayload())
        val room = ready.repository.read() as CoreDataReadResult.Ready
        val completed = room.snapshot.items.single { it.id == 102L }
        assertTrue(completed.done)
        assertEquals("完整完成", completed.completionLevel)
        assertNotNull(room.snapshot.taskEvents.singleOrNull { it.id == 9_001L })
        assertEquals(1, room.snapshot.goals.single { it.id == 201L }.completedThisWeek)
    }

    @Test
    fun `legacy session persistence retains more than fifty entries`() {
        val repository = LegacyCoreDataRepository(PrototypeStore(context))

        (1L..60L).forEach { id ->
            val session = ActivitySession(
                id = id,
                name = "session-$id",
                plannedStartAt = id,
                actualStartAt = id,
                endsAt = id + 60_000L
            )
            assertTrue(CoreDataRepositoryOperations.saveActivitySession(repository, session).applied)
        }

        val reloaded = LegacyCoreDataRepository(PrototypeStore(context))
        val sessions = (reloaded.read() as CoreDataReadResult.Ready).snapshot.activitySessions
        assertEquals(60, sessions.size)
        assertEquals((1L..60L).toList(), sessions.map(ActivitySession::id))
        assertEquals(60, CoreDataRepositoryOperations.recentActivitySessions(reloaded, 60).size)
    }

    @Test
    fun `activated Room session write updates only Room and its count`() {
        installFixture("8.3-rc.12")
        val legacyBefore = corePayload()
        val database = newDatabase()
        val ready = runtime(
            database = database,
            activationStore = SharedPreferencesCoreDataActivationStore(context),
            migrationMarker = SharedPreferencesMigrationMarker(context),
            activationEnabled = true,
            now = sequenceOf(100L, 200L)
        ).resolve() as CoreDataRuntimeResolution.Ready
        val initialCount = database.activitySessionDao().all().size
        val newSession = ActivitySession(
            id = 9_001L,
            name = "next activity",
            plannedStartAt = 1_000L,
            actualStartAt = 1_000L,
            endsAt = 61_000L
        )

        val result = CoreDataRepositoryOperations.saveActivitySession(ready.repository, newSession)

        assertTrue(result.applied)
        assertEquals(legacyBefore, corePayload())
        assertEquals(initialCount + 1, database.activitySessionDao().all().size)
        assertEquals(
            initialCount + 1,
            requireNotNull(database.migrationStateDao().find(LegacyDataImporter.MIGRATION_KEY))
                .activitySessionCount
        )
        assertEquals(newSession, CoreDataRepositoryOperations.findActivitySession(ready.repository, newSession.id))
    }

    @Test
    fun `active Room query failure fails closed without constructing either writer`() {
        installFixture("8.3-rc.12")
        val database = newDatabase()
        val activationStore = SharedPreferencesCoreDataActivationStore(context)
        val activated = runtime(
            database = database,
            activationStore = activationStore,
            migrationMarker = SharedPreferencesMigrationMarker(context),
            activationEnabled = true,
            now = sequenceOf(100L, 200L)
        ).resolve()
        assertTrue(activated is CoreDataRuntimeResolution.Ready)
        database.openHelper.writableDatabase.execSQL("DROP TABLE tasks")

        var legacyFactories = 0
        var roomFactories = 0
        val coordinator = coordinator(
            database = database,
            activationStore = activationStore,
            migrationMarker = SharedPreferencesMigrationMarker(context),
            now = sequenceOf(300L)
        )
        val restarted = CoreDataRuntimeCompositionRoot(
            selectSource = { coordinator.selectSource(activationEnabled = false) },
            legacyRepositoryFactory = {
                legacyFactories++
                error("invalid active Room must not fall back to Legacy")
            },
            roomRepositoryFactory = {
                roomFactories++
                roomRepository(database)
            }
        ).resolve() as CoreDataRuntimeResolution.Blocked

        assertEquals(CoreDataRuntimeSource.NONE, restarted.decision.source)
        assertEquals(CoreDataActivationStatus.BLOCKED_ROOM_INVALID, restarted.decision.status)
        assertEquals(0, legacyFactories)
        assertEquals(0, roomFactories)
    }

    private fun assertFirstActivation(fixtureName: String) {
        installFixture(fixtureName)
        val legacyBefore = corePayload()
        val expected = legacySnapshot()
        val database = newDatabase()
        val ready = runtime(
            database = database,
            activationStore = SharedPreferencesCoreDataActivationStore(context),
            migrationMarker = SharedPreferencesMigrationMarker(context),
            activationEnabled = true,
            now = sequenceOf(100L, 200L)
        ).resolve() as CoreDataRuntimeResolution.Ready

        assertEquals(CoreDataRuntimeSource.ROOM, ready.repository.source)
        assertEquals(CoreDataActivationStatus.ROOM_ACTIVATED, ready.decision.status)
        assertEquals(MigrationStatus.IMPORTED, ready.decision.migrationStatus)
        assertEquals(CoreDataConsistencyStatus.CONSISTENT, ready.decision.consistencyStatus)
        assertEquals(expected, (ready.repository.read() as CoreDataReadResult.Ready).snapshot)
        assertEquals(legacyBefore, corePayload())
        assertEquals(expected.items.map(Item::id).sorted(), database.taskDao().allIds())
        assertEquals(expected.taskEvents.map(TaskEvent::id).sorted(), database.taskEventDao().allIds())
        assertEquals(expected.goals.map { it.id }.sorted(), database.planDao().allIds())
        assertEquals(expected.activitySessions.map { it.id }.sorted(), database.activitySessionDao().allIds())
        assertEquals(expected.courses.map { it.id }.sorted(), database.courseDao().allIds())
        assertTrue(database.recurrenceRuleDao().allIds().isEmpty())
        assertTrue(database.taskOccurrenceDao().allIds().isEmpty())
        assertFalse(CoreDataRuntimePolicy.ACTIVATION_ENABLED)
    }

    private fun runtime(
        database: FocusFlowDatabase,
        activationStore: CoreDataActivationStore,
        migrationMarker: MigrationCompletionMarker,
        activationEnabled: Boolean,
        now: Sequence<Long>
    ): CoreDataRuntimeCompositionRoot {
        val coordinator = coordinator(database, activationStore, migrationMarker, now)
        return CoreDataRuntimeCompositionRoot(
            selectSource = { coordinator.selectSource(activationEnabled) },
            legacyRepositoryFactory = { error("activation test must not construct the Legacy writer") },
            roomRepositoryFactory = { roomRepository(database) }
        )
    }

    private fun coordinator(
        database: FocusFlowDatabase,
        activationStore: CoreDataActivationStore,
        migrationMarker: MigrationCompletionMarker,
        now: Sequence<Long>
    ): CoreDataActivationCoordinator {
        val times = now.iterator()
        return CoreDataActivationCoordinator(
            legacyReader = { LegacyPreferencesReader.fromContext(context).read() },
            migrationRunner = CoreDataMigrationRunner {
                LegacyDataImporter(
                    reader = LegacyPreferencesReader.fromContext(context),
                    store = RoomLegacyMigrationStore(database),
                    marker = migrationMarker,
                    now = { 50L }
                ).importIfNeeded()
            },
            roomVerifier = DatabaseCoreDataRoomActivationVerifier(database),
            activationStore = activationStore,
            now = { check(times.hasNext()) { "test activation clock exhausted" }; times.next() }
        )
    }

    private fun roomRepository(database: FocusFlowDatabase): CoreDataRepository {
        val store = DatabaseRoomCoreDataWriteStore(database)
        return RoomCoreDataRepository(
            reader = RoomCoreDataReadRepository(store),
            writer = RoomCoreDataWriteRepository(store, currentWeekKey = { 1_789_603_200_000L })
        )
    }

    private fun newDatabase(): FocusFlowDatabase = Room.inMemoryDatabaseBuilder(
        context,
        FocusFlowDatabase::class.java
    ).allowMainThreadQueries().build().also(databases::add)

    private fun installFixture(name: String) {
        val editor = preferences.edit()
            .putString(LegacyPreferencesReader.KEY_ITEMS, fixture("migration/$name/items.json"))
            .putString(LegacyPreferencesReader.KEY_GOALS, fixture("migration/$name/goals.json"))
        val events = javaClass.classLoader?.getResource("migration/$name/task_events.json")
        if (events == null) editor.remove(LegacyPreferencesReader.KEY_TASK_EVENTS)
        else editor.putString(LegacyPreferencesReader.KEY_TASK_EVENTS, events.readText(Charsets.UTF_8))
        editor.putString(
            LegacyPreferencesReader.KEY_SESSIONS,
            fixture("migration/$name/sessions.json")
        )
        check(editor.commit())
    }

    private fun legacySnapshot(): CoreDataSnapshot {
        val snapshot = (LegacyPreferencesReader.fromContext(context).read() as LegacyReadResult.Success).snapshot
        return CoreDataSnapshot(
            items = snapshot.tasks.map(TaskEntity::toLegacy),
            taskEvents = snapshot.taskEvents.map { entity ->
                entity.toLegacy(requireNotNull(TaskEventType.fromKey(entity.type)))
            },
            goals = snapshot.plans.map(PlanEntity::toLegacy),
            activitySessions = snapshot.activitySessions.map(ActivitySessionEntity::toLegacy),
            courses = snapshot.courses.zip(snapshot.courseMeetingRules).map { (parent, rule) -> rule.toLegacy(parent) }
        )
    }

    private fun corePayload(): Map<String, String?> = listOf(
        LegacyPreferencesReader.KEY_ITEMS,
        LegacyPreferencesReader.KEY_TASK_EVENTS,
        LegacyPreferencesReader.KEY_GOALS,
        LegacyPreferencesReader.KEY_SESSIONS,
        LegacyPreferencesReader.KEY_COURSES
    ).associateWith { key -> preferences.getString(key, null) }

    private fun fixture(path: String): String = requireNotNull(javaClass.classLoader?.getResource(path))
        .readText(Charsets.UTF_8)

    private companion object {
        const val PREFERENCES_NAME = "focusflow"
    }
}

private class SwitchableMigrationMarker(var succeeds: Boolean) : MigrationCompletionMarker {
    var calls: Int = 0
        private set

    override fun markComplete(report: MigrationReport): Boolean {
        calls++
        return succeeds
    }
}
