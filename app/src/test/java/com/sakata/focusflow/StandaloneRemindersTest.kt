package com.sakata.focusflow

import android.app.AlarmManager
import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class StandaloneRemindersTest {
    @Test fun `independent reminder survives reload and stale broadcast cannot replay or complete another instance`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("standalone_reminders", Context.MODE_PRIVATE).edit().clear().commit()
        val now = 1_800_000_000_000L
        assertFalse(StandaloneReminders.create(context, "", now + 60_000, now))
        assertTrue(StandaloneReminders.create(context, "核对实验", now + 60_000, now))
        val entry = StandaloneReminders.all(context).single()
        assertEquals("核对实验", entry.title)
        assertNull(StandaloneReminders.markDelivered(context, entry.id, now + 120_000, now + 60_001))
        assertNull(StandaloneReminders.markDelivered(context, entry.id, entry.triggerAt, now))
        assertNotNull(StandaloneReminders.markDelivered(context, entry.id, entry.triggerAt, now + 60_001))
        assertNull(StandaloneReminders.markDelivered(context, entry.id, entry.triggerAt, now + 60_002))
        assertFalse(StandaloneReminders.complete(context, entry.id, now + 120_000))
        assertTrue(StandaloneReminders.complete(context, entry.id, entry.triggerAt, now + 60_003))
        assertFalse(StandaloneReminders.complete(context, entry.id, entry.triggerAt, now + 60_004))
        assertNotNull(StandaloneReminders.all(context).single().completedAt)
    }

    @Test fun `snooze moves only notification and rejects replayed actions`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("standalone_reminders", Context.MODE_PRIVATE).edit().clear().commit()
        val now = 1_800_000_000_000L
        assertTrue(StandaloneReminders.create(context, "开会", now + 60_000, now))
        val original = StandaloneReminders.all(context).single()
        val delivered = StandaloneReminders.markDelivered(context, original.id, original.triggerAt, now + 60_001)!!
        assertFalse(StandaloneReminders.snooze(context, original.id, original.triggerAt,
            delivered.deliveredAt!! - 1, now = now + 60_002))
        assertTrue(StandaloneReminders.snooze(context, original.id, original.triggerAt,
            delivered.deliveredAt!!, now = now + 60_002))
        val snoozed = StandaloneReminders.all(context).single()
        assertEquals(original.triggerAt, snoozed.triggerAt)
        assertEquals(now + 11 * 60_000L + 2, snoozed.scheduledAt)
        assertFalse(StandaloneReminders.snooze(context, original.id, original.triggerAt,
            delivered.deliveredAt!!, now = now + 60_003))
        assertFalse(StandaloneReminders.complete(context, original.id, original.triggerAt))
        assertNull(StandaloneReminders.markDelivered(context, original.id, original.triggerAt, snoozed.scheduledAt))
        assertNotNull(StandaloneReminders.markDelivered(context, original.id, snoozed.scheduledAt, snoozed.scheduledAt))
        assertTrue(StandaloneReminders.complete(context, original.id, snoozed.scheduledAt))
    }

    @Test fun `create rejects blank overlong and non-future entries and trims titles`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("standalone_reminders", Context.MODE_PRIVATE).edit().clear().commit()
        val now = 1_800_000_000_000L
        assertFalse(StandaloneReminders.create(context, "   ", now + 60_000, now))
        assertFalse(StandaloneReminders.create(context, "x".repeat(201), now + 60_000, now))
        assertFalse(StandaloneReminders.create(context, "开会", now, now))
        assertFalse(StandaloneReminders.create(context, "开会", now - 1, now))
        assertTrue(StandaloneReminders.create(context, "  开会  ", now + 60_000, now))
        assertTrue(StandaloneReminders.create(context, "x".repeat(200), now + 60_000, now))
        assertEquals(listOf("开会", "x".repeat(200)), StandaloneReminders.all(context).map { it.title })
    }

    @Test fun `all returns empty for corrupted payload and filters invalid entries`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("standalone_reminders", Context.MODE_PRIVATE)
        prefs.edit().clear().putString("entries", "{not-json").commit()
        assertTrue(StandaloneReminders.all(context).isEmpty())

        val raw = """[{"id":0,"title":"bad","triggerAt":10},{"id":5,"title":"good","triggerAt":10},{"id":6,"title":"bad","triggerAt":0},{"id":7,"title":"good2","triggerAt":20}]"""
        prefs.edit().putString("entries", raw).commit()
        assertEquals(listOf(5L, 7L), StandaloneReminders.all(context).map { it.id })
    }

    @Test fun `snooze rejects out-of-range minutes and completed entries`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("standalone_reminders", Context.MODE_PRIVATE).edit().clear().commit()
        val now = 1_800_000_000_000L
        assertTrue(StandaloneReminders.create(context, "开会", now + 60_000, now))
        val entry = StandaloneReminders.all(context).single()
        val delivered = StandaloneReminders.markDelivered(context, entry.id, entry.triggerAt, now + 60_001)!!

        assertFalse(StandaloneReminders.snooze(context, entry.id, entry.triggerAt, delivered.deliveredAt!!, minutes = 4, now = now + 60_002))
        assertFalse(StandaloneReminders.snooze(context, entry.id, entry.triggerAt, delivered.deliveredAt!!, minutes = 181, now = now + 60_002))
        assertTrue(StandaloneReminders.snooze(context, entry.id, entry.triggerAt, delivered.deliveredAt!!, minutes = 5, now = now + 60_002))
        val snoozed = StandaloneReminders.all(context).single()
        assertNull(snoozed.deliveredAt)
        assertEquals(now + 60_002 + 5 * 60_000L, snoozed.scheduledAt)

        assertTrue(StandaloneReminders.complete(context, entry.id, snoozed.scheduledAt, now + 60_003))
        assertFalse(StandaloneReminders.snooze(context, entry.id, snoozed.scheduledAt, now + 60_004, minutes = 10, now = now + 60_004))
    }

    @Test fun `restore schedules only future undelivered entries`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("standalone_reminders", Context.MODE_PRIVATE).edit().clear().commit()
        val now = 1_800_000_000_000L
        val shadow = Shadows.shadowOf(context.getSystemService(AlarmManager::class.java))

        assertTrue(StandaloneReminders.create(context, "A", now + 60_000, now))
        val deliveredEntry = StandaloneReminders.all(context).single()
        assertNotNull(StandaloneReminders.markDelivered(context, deliveredEntry.id, deliveredEntry.triggerAt, now + 60_001))

        assertTrue(StandaloneReminders.create(context, "B", now + 60_000, now))
        val completedEntry = StandaloneReminders.all(context).first { it.id != deliveredEntry.id }
        assertTrue(StandaloneReminders.complete(context, completedEntry.id, completedEntry.triggerAt, now + 60_001))

        assertTrue(StandaloneReminders.create(context, "C", now + 60_000, now))
        val elapsedEntry = StandaloneReminders.all(context)
            .first { it.id != deliveredEntry.id && it.id != completedEntry.id }
        val elapsedDelivered = StandaloneReminders.markDelivered(context, elapsedEntry.id, elapsedEntry.triggerAt, now + 60_001)!!
        assertTrue(StandaloneReminders.snooze(context, elapsedEntry.id, elapsedEntry.triggerAt, elapsedDelivered.deliveredAt!!, minutes = 10, now = now + 60_002))

        assertTrue(StandaloneReminders.create(context, "E", now + 60_000, now))
        val futureSnoozed = StandaloneReminders.all(context)
            .first { it.id != deliveredEntry.id && it.id != completedEntry.id && it.id != elapsedEntry.id }
        val snoozeDelivered = StandaloneReminders.markDelivered(context, futureSnoozed.id, futureSnoozed.triggerAt, now + 60_001)!!
        assertTrue(StandaloneReminders.snooze(context, futureSnoozed.id, futureSnoozed.triggerAt, snoozeDelivered.deliveredAt!!, minutes = 30, now = now + 60_002))

        assertTrue(StandaloneReminders.create(context, "D", now + 1_200_000, now))
        val plainFuture = StandaloneReminders.all(context)
            .first { it.id != deliveredEntry.id && it.id != completedEntry.id && it.id != elapsedEntry.id && it.id != futureSnoozed.id }
        val futureSnoozedNow = StandaloneReminders.all(context).first { it.id == futureSnoozed.id }

        StandaloneReminders.restore(context, now + 720_001)

        val scheduled = shadow.scheduledAlarms.map { it.triggerAtMs }.sorted()
        assertEquals(listOf(plainFuture.scheduledAt, futureSnoozedNow.scheduledAt).sorted(), scheduled)
        val elapsedNow = StandaloneReminders.all(context).first { it.id == elapsedEntry.id }
        assertFalse(scheduled.contains(elapsedNow.scheduledAt))
    }

    @Test fun `quiet hours do not block restore scheduling`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("standalone_reminders", Context.MODE_PRIVATE).edit().clear().commit()
        val now = 1_800_000_000_000L
        PrototypeStore(context).saveQuietHoursSettings(
            QuietHoursSettings(enabled = true, muteUntil = now + 10 * 60_000L)
        )
        assertTrue(StandaloneReminders.create(context, "静音期提醒", now + 60_000, now))

        StandaloneReminders.restore(context, now)

        val shadow = Shadows.shadowOf(context.getSystemService(AlarmManager::class.java))
        assertEquals(1, shadow.scheduledAlarms.size)
    }
}
