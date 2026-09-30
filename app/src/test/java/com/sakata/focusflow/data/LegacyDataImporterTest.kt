package com.sakata.focusflow.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyDataImporterTest {
    @Test
    fun `imports all domains once and marks complete only after verification`() {
        val reader = readerFor83()
        val store = FakeMigrationStore()
        val marker = RecordingMarker()

        val first = LegacyDataImporter(reader, store, marker) { 1234L }.importIfNeeded()
        val second = LegacyDataImporter(reader, store, marker) { 9999L }.importIfNeeded()

        assertEquals(MigrationStatus.IMPORTED, first.status)
        assertEquals(1, first.taskCount)
        assertEquals(2, first.taskEventCount)
        assertEquals(1, first.planCount)
        assertEquals(0, first.recurrenceRuleCount)
        assertEquals(0, first.taskOccurrenceCount)
        assertEquals(2, first.activitySessionCount)
        assertEquals(MigrationStatus.ALREADY_IMPORTED, second.status)
        assertEquals(1, store.importCalls)
        assertEquals(2, marker.reports.size)
        assertEquals(1234L, store.state?.completedAt)
    }

    @Test
    fun `corrupt source never opens a database transaction or marker write`() {
        val store = FakeMigrationStore()
        val marker = RecordingMarker()
        val reader = LegacyPreferencesReader(
            MapLegacySource(mapOf(LegacyPreferencesReader.KEY_ITEMS to "not-json"))
        ) { _, _ -> true }

        val report = LegacyDataImporter(reader, store, marker).importIfNeeded()

        assertEquals(MigrationStatus.BLOCKED_CORRUPT_SOURCE, report.status)
        assertEquals(0, store.importCalls)
        assertTrue(marker.reports.isEmpty())
    }

    @Test
    fun `database content without migration state blocks overwrite`() {
        val store = FakeMigrationStore().apply { taskIds += 99L }
        val marker = RecordingMarker()

        val report = LegacyDataImporter(readerFor83(), store, marker).importIfNeeded()

        assertEquals(MigrationStatus.BLOCKED_DATABASE_NOT_EMPTY, report.status)
        assertEquals(listOf(99L), store.taskIds)
        assertEquals(0, store.importCalls)
        assertTrue(marker.reports.isEmpty())
    }

    @Test
    fun `marker failure is retryable without importing twice`() {
        val store = FakeMigrationStore()
        val marker = RecordingMarker(succeeds = false)

        val first = LegacyDataImporter(readerFor83(), store, marker).importIfNeeded()
        marker.succeeds = true
        val retry = LegacyDataImporter(readerFor83(), store, marker).importIfNeeded()

        assertEquals(MigrationStatus.MARKER_WRITE_FAILED, first.status)
        assertEquals(MigrationStatus.ALREADY_IMPORTED, retry.status)
        assertEquals(1, store.importCalls)
        assertEquals(2, marker.reports.size)
    }

    @Test
    fun `changed legacy payload after import is reported instead of dual written`() {
        val store = FakeMigrationStore()
        val marker = RecordingMarker()
        LegacyDataImporter(readerFor83(), store, marker).importIfNeeded()
        val changed = LegacyPreferencesReader(
            MapLegacySource(
                mapOf(
                    LegacyPreferencesReader.KEY_ITEMS to """[{"id":301,"title":"changed","detail":"","kind":"任务"}]""",
                    LegacyPreferencesReader.KEY_GOALS to fixture("migration/8.3-rc.12/goals.json"),
                    LegacyPreferencesReader.KEY_TASK_EVENTS to fixture("migration/8.3-rc.12/task_events.json")
                )
            )
        ) { _, _ -> true }

        val report = LegacyDataImporter(changed, store, marker).importIfNeeded()

        assertEquals(MigrationStatus.BLOCKED_SOURCE_CHANGED, report.status)
        assertEquals(1, store.importCalls)
    }

    @Test
    fun `new install initializes an empty database explicitly`() {
        val store = FakeMigrationStore()
        val marker = RecordingMarker()
        val reader = LegacyPreferencesReader(MapLegacySource()) { _, _ -> true }

        val report = LegacyDataImporter(reader, store, marker).importIfNeeded()

        assertEquals(MigrationStatus.INITIALIZED_EMPTY, report.status)
        assertFalse(report.sourceFingerprint.isBlank())
        assertEquals(1, store.importCalls)
        assertEquals(1, marker.reports.size)
    }

    private fun readerFor83(): LegacyPreferencesReader = LegacyPreferencesReader(
        MapLegacySource(
            mapOf(
                LegacyPreferencesReader.KEY_ITEMS to fixture("migration/8.3-rc.12/items.json"),
                LegacyPreferencesReader.KEY_TASK_EVENTS to fixture("migration/8.3-rc.12/task_events.json"),
                LegacyPreferencesReader.KEY_GOALS to fixture("migration/8.3-rc.12/goals.json"),
                LegacyPreferencesReader.KEY_SESSIONS to fixture("migration/8.3-rc.12/sessions.json")
            )
        )
    ) { _, _ -> true }

    private fun fixture(path: String): String = requireNotNull(javaClass.classLoader?.getResource(path))
        .readText(Charsets.UTF_8)
}

private class RecordingMarker(var succeeds: Boolean = true) : MigrationCompletionMarker {
    val reports = mutableListOf<MigrationReport>()
    override fun markComplete(report: MigrationReport): Boolean {
        reports += report
        return succeeds
    }
}

private class FakeMigrationStore : LegacyMigrationStore {
    var state: MigrationStateEntity? = null
    val taskIds = mutableListOf<Long>()
    val taskEventIds = mutableListOf<Long>()
    val planIds = mutableListOf<Long>()
    val recurrenceRuleIds = mutableListOf<Long>()
    val taskOccurrenceIds = mutableListOf<Long>()
    val activitySessionIds = mutableListOf<Long>()
    var importCalls = 0

    override fun findState(key: String): MigrationStateEntity? = state?.takeIf { it.migrationKey == key }

    override fun summary(): DatabaseMigrationSummary = DatabaseMigrationSummary(
        taskIds.sorted(),
        taskEventIds.sorted(),
        planIds.sorted(),
        recurrenceRuleIds.sorted(),
        taskOccurrenceIds.sorted(),
        activitySessionIds.sorted()
    )

    override fun importAtomically(
        snapshot: LegacySnapshot,
        state: MigrationStateEntity
    ): AtomicImportOutcome {
        importCalls++
        if (this.state != null) return AtomicImportOutcome.ALREADY_PRESENT
        if (!summary().isEmpty) return AtomicImportOutcome.DATABASE_NOT_EMPTY
        planIds += snapshot.plans.map { it.id }
        taskIds += snapshot.tasks.map { it.id }
        taskEventIds += snapshot.taskEvents.map { it.id }
        recurrenceRuleIds += snapshot.recurrenceRules.map { it.id }
        taskOccurrenceIds += snapshot.taskOccurrences.map { it.id }
        activitySessionIds += snapshot.activitySessions.map { it.id }
        this.state = state
        return AtomicImportOutcome.INSERTED
    }
}
