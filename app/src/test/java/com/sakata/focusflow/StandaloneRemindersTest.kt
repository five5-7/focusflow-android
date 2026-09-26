package com.sakata.focusflow

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
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
}
