package com.sakata.focusflow

import android.Manifest
import android.app.Application
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = FocusFlowApplication::class)
class QuickCaptureServiceTest {
    private lateinit var context: Context

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("focusflow", Context.MODE_PRIVATE).edit().clear().commit()
        Shadows.shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    @Test fun backgroundStartDenialDoesNotClearUserPreferenceOrEscapeToReceiver() {
        PrototypeStore(context).saveQuickCaptureEnabled(true)
        val blocked = object : ContextWrapper(context) {
            override fun startForegroundService(service: Intent): ComponentName? =
                throw IllegalStateException("background start disallowed")
        }
        assertFalse(QuickCaptureService.start(blocked))
        assertTrue(PrototypeStore(context).loadQuickCaptureEnabled())
        assertTrue(QuickCaptureService.start(context))
    }

    @Test fun securityDenialIsRecoverable() {
        val blocked = object : ContextWrapper(context) {
            override fun startForegroundService(service: Intent): ComponentName? = throw SecurityException("denied")
        }
        assertFalse(QuickCaptureService.start(blocked))
    }

    @Test fun missingNotificationPermissionDoesNotAttemptServiceStart() {
        Shadows.shadowOf(context as Application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        var attempted = false
        val wrapped = object : ContextWrapper(context) {
            override fun startForegroundService(service: Intent): ComponentName? {
                attempted = true
                return ComponentName(context, QuickCaptureService::class.java)
            }
        }
        assertFalse(QuickCaptureService.start(wrapped))
        assertFalse(attempted)
    }

    @Test fun stickyRestartAfterUserDisabledServiceStopsWithoutForegroundNotification() {
        PrototypeStore(context).saveQuickCaptureEnabled(false)
        val controller = Robolectric.buildService(QuickCaptureService::class.java).create()
        try {
            val service = controller.get()
            assertEquals(Service.START_NOT_STICKY, service.onStartCommand(null, 0, 1))
            assertTrue(Shadows.shadowOf(service).isStoppedBySelf)
        } finally {
            controller.destroy()
        }
    }
}
