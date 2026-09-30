package com.sakata.focusflow.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyPreferencesReaderTest {
    @Test
    fun `8_2_1 fixture receives safe defaults without changing IDs`() {
        val source = MapLegacySource(
            strings = mapOf(
                LegacyPreferencesReader.KEY_ITEMS to fixture("migration/8.2.1/items.json"),
                LegacyPreferencesReader.KEY_GOALS to fixture("migration/8.2.1/goals.json"),
                LegacyPreferencesReader.KEY_SESSIONS to fixture("migration/8.2.1/sessions.json")
            ),
            dataVersion = 1
        )

        val result = LegacyPreferencesReader(source) { _, _ -> true }.read() as LegacyReadResult.Success

        assertEquals(listOf(101L, 102L), result.snapshot.tasks.map { it.id })
        assertEquals(listOf(0, 1), result.snapshot.tasks.map { it.sourceOrder })
        assertEquals(TaskStatusKey.CAPTURED, result.snapshot.tasks[0].status)
        assertEquals(TaskStatusKey.SCHEDULED, result.snapshot.tasks[1].status)
        assertEquals("mid", result.snapshot.tasks[0].priority)
        assertEquals(201L, result.snapshot.tasks[1].planId)
        assertEquals(listOf(201L), result.snapshot.plans.map { it.id })
        assertEquals(listOf(0), result.snapshot.plans.map { it.sourceOrder })
        assertTrue(result.snapshot.taskEvents.isEmpty())
        assertTrue(result.snapshot.recurrenceRules.isEmpty())
        assertTrue(result.snapshot.taskOccurrences.isEmpty())
        assertEquals(listOf(701L), result.snapshot.activitySessions.map { it.id })
        assertEquals("旧版专注", result.snapshot.activitySessions.single().category)
        assertEquals(701L, result.snapshot.activitySessions.single().plannedStartAt)
    }

    @Test
    fun `8_3 fixture preserves current fields and detached history`() {
        val source = MapLegacySource(
            strings = mapOf(
                LegacyPreferencesReader.KEY_ITEMS to fixture("migration/8.3-rc.12/items.json"),
                LegacyPreferencesReader.KEY_TASK_EVENTS to fixture("migration/8.3-rc.12/task_events.json"),
                LegacyPreferencesReader.KEY_GOALS to fixture("migration/8.3-rc.12/goals.json"),
                LegacyPreferencesReader.KEY_SESSIONS to fixture("migration/8.3-rc.12/sessions.json")
            ),
            dataVersion = 1
        )

        val result = LegacyPreferencesReader(source) { _, _ -> true }.read() as LegacyReadResult.Success
        val task = result.snapshot.tasks.single()

        assertEquals(TaskStatusKey.COMPLETED, task.status)
        assertEquals("用户备注", task.userNote)
        assertEquals("high", task.priority)
        assertEquals(2, task.rescheduleCount)
        assertEquals(listOf(501L, 502L), result.snapshot.taskEvents.map { it.id })
        assertEquals(listOf(0, 1), result.snapshot.taskEvents.map { it.sourceOrder })
        assertTrue(result.snapshot.diagnostics.any { it.message.contains("deleted tasks") })
        assertFalse(result.snapshot.sourceFingerprint.isBlank())
        assertEquals(listOf(801L, 802L), result.snapshot.activitySessions.map { it.id })
        assertEquals(listOf(0, 1), result.snapshot.activitySessions.map { it.sourceOrder })
        assertEquals(1789996200000L, result.snapshot.activitySessions.first().actualEndAt)
        assertEquals(null, result.snapshot.activitySessions.last().actualEndAt)
        assertTrue(result.snapshot.recurrenceRules.isEmpty())
        assertTrue(result.snapshot.taskOccurrences.isEmpty())
    }

    @Test
    fun `corrupt fixture is backed up and blocks migration`() {
        val backups = mutableListOf<Pair<String, String>>()
        val source = MapLegacySource(
            strings = mapOf(LegacyPreferencesReader.KEY_ITEMS to fixture("migration/corrupt/items.json"))
        )

        val result = LegacyPreferencesReader(source) { key, raw ->
            backups += key to raw
            true
        }.read() as LegacyReadResult.Failure

        assertEquals(LegacyPreferencesReader.KEY_ITEMS, result.domain)
        assertTrue(result.backupSucceeded)
        assertEquals(1, backups.size)
    }

    @Test
    fun `duplicate or zero item IDs are rejected instead of renumbered`() {
        listOf(
            """[{"id":0,"title":"zero","detail":"","kind":"任务"}]""",
            """[{"id":7,"title":"a","detail":"","kind":"任务"},{"id":7,"title":"b","detail":"","kind":"任务"}]"""
        ).forEach { raw ->
            val result = LegacyPreferencesReader(
                MapLegacySource(mapOf(LegacyPreferencesReader.KEY_ITEMS to raw))
            ) { _, _ -> true }.read()
            assertTrue(result is LegacyReadResult.Failure)
        }
    }

    @Test
    fun `unknown event type blocks import instead of disappearing`() {
        val items = """[{"id":1,"title":"a","detail":"","kind":"任务"}]"""
        val events = """[{"id":2,"itemId":1,"type":"future_type","recordedAt":10}]"""
        val result = LegacyPreferencesReader(
            MapLegacySource(
                mapOf(
                    LegacyPreferencesReader.KEY_ITEMS to items,
                    LegacyPreferencesReader.KEY_TASK_EVENTS to events
                )
            )
        ) { _, _ -> true }.read()

        assertTrue(result is LegacyReadResult.Failure)
        assertEquals(LegacyPreferencesReader.KEY_TASK_EVENTS, (result as LegacyReadResult.Failure).domain)
    }

    @Test
    fun `invalid activity session blocks the whole import and is backed up`() {
        val backups = mutableListOf<Pair<String, String>>()
        val sessions = """[{"id":4,"name":"bad","endsAt":20,"status":"future_status"}]"""
        val result = LegacyPreferencesReader(
            MapLegacySource(mapOf(LegacyPreferencesReader.KEY_SESSIONS to sessions))
        ) { key, raw ->
            backups += key to raw
            true
        }.read()

        assertTrue(result is LegacyReadResult.Failure)
        assertEquals(LegacyPreferencesReader.KEY_SESSIONS, (result as LegacyReadResult.Failure).domain)
        assertEquals(listOf(LegacyPreferencesReader.KEY_SESSIONS to sessions), backups)
    }

    private fun fixture(path: String): String = requireNotNull(javaClass.classLoader?.getResource(path))
        .readText(Charsets.UTF_8)
}

internal class MapLegacySource(
    private val strings: Map<String, String> = emptyMap(),
    private val dataVersion: Int = 1
) : LegacyPreferencesSource {
    override fun getString(key: String): String? = strings[key]
    override fun getInt(key: String, defaultValue: Int): Int =
        if (key == LegacyPreferencesReader.KEY_DATA_VERSION) dataVersion else defaultValue
}
