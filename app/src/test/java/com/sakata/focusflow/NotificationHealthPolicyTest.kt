package com.sakata.focusflow

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationHealthPolicyTest {
    @Test
    fun `healthy notification settings do not prompt`() {
        val health = NotificationHealthPolicy.evaluate(true, true, true)

        assertTrue(health.allReadableSettingsReady)
        assertNull(NotificationHealthPolicy.startupMessage(health))
    }

    @Test
    fun `disabled app or channel settings produce a prompt`() {
        val appDisabled = NotificationHealthPolicy.evaluate(false, true, true)
        val taskDisabled = NotificationHealthPolicy.evaluate(true, false, true)
        val mealDisabled = NotificationHealthPolicy.evaluate(true, true, false)

        assertTrue(NotificationHealthPolicy.startupMessage(appDisabled)!!.contains("通知未开启"))
        assertTrue(NotificationHealthPolicy.startupMessage(taskDisabled)!!.contains("日程横幅"))
        assertTrue(NotificationHealthPolicy.startupMessage(mealDisabled)!!.contains("饭点横幅"))
    }
}
