package com.sakata.focusflow

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationHealthPolicyTest {
    @Test
    fun `healthy notification settings do not prompt`() {
        val health = NotificationHealthPolicy.evaluate(true, true, true)

        assertTrue(health.allReadableSettingsReady(mealReminderRequired = true))
        assertNull(NotificationHealthPolicy.startupMessage(health, mealReminderRequired = true))
    }

    @Test
    fun `disabled app or channel settings produce a prompt`() {
        val appDisabled = NotificationHealthPolicy.evaluate(false, true, true)
        val taskDisabled = NotificationHealthPolicy.evaluate(true, false, true)
        val mealDisabled = NotificationHealthPolicy.evaluate(true, true, false)

        assertTrue(NotificationHealthPolicy.startupMessage(appDisabled, true)!!.contains("通知未开启"))
        assertTrue(NotificationHealthPolicy.startupMessage(taskDisabled, true)!!.contains("日程横幅"))
        assertTrue(NotificationHealthPolicy.startupMessage(mealDisabled, true)!!.contains("饭点横幅"))
    }

    @Test
    fun `disabled optional meal feature does not demand its notification channel`() {
        val mealChannelDisabled = NotificationHealthPolicy.evaluate(true, true, false)

        assertTrue(mealChannelDisabled.allReadableSettingsReady(mealReminderRequired = false))
        assertNull(NotificationHealthPolicy.startupMessage(mealChannelDisabled, mealReminderRequired = false))
    }
}
