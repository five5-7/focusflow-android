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
}
