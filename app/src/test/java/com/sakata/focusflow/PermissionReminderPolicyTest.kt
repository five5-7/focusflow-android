package com.sakata.focusflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionReminderPolicyTest {
    @Test fun onlyMissingOrRequiredPermissionsAreShown() {
        assertEquals(
            listOf(MissingPermission.NOTIFICATIONS, MissingPermission.EXACT_ALARMS),
            PermissionReminderPolicy.missing(false, false, usageAccessRequired = false, usageAccessGranted = false)
        )
        assertEquals(
            listOf(MissingPermission.USAGE_ACCESS),
            PermissionReminderPolicy.missing(true, true, usageAccessRequired = true, usageAccessGranted = false)
        )
        assertTrue(PermissionReminderPolicy.missing(true, true, true, true).isEmpty())
    }

    @Test fun summaryNamesEveryMissingPermission() {
        assertEquals(
            "待设置：通知权限、使用情况访问",
            PermissionReminderPolicy.summary(listOf(MissingPermission.NOTIFICATIONS, MissingPermission.USAGE_ACCESS))
        )
    }

    @Test fun notificationIsRequiredButDeliveryPrecisionIsOptional() {
        assertEquals(PermissionPriority.REQUIRED, MissingPermission.NOTIFICATIONS.priority)
        assertEquals(PermissionPriority.OPTIONAL, MissingPermission.EXACT_ALARMS.priority)
        assertEquals(PermissionPriority.OPTIONAL, MissingPermission.USAGE_ACCESS.priority)
    }
}
