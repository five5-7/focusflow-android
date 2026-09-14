package com.sakata.focusflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionCenterPolicyTest {
    @Test fun vendorOnlySettingsAreNeverReportedAsAutomaticallyReady() {
        val entries = PermissionCenterPolicy.entries(
            notificationsAllowed = true,
            taskChannelReady = true,
            exactAlarmAllowed = true,
            batteryUnrestricted = true,
            appDetectionEnabled = false,
            usageAccessAllowed = false
        ).associateBy { it.item }

        assertEquals(PermissionCenterStatus.MANUAL_CHECK, entries[PermissionCenterItem.BANNER]?.status)
        assertEquals(PermissionCenterStatus.MANUAL_CHECK, entries[PermissionCenterItem.AUTOSTART]?.status)
        assertEquals(PermissionCenterStatus.NOT_IN_USE, entries[PermissionCenterItem.USAGE_ACCESS]?.status)
    }

    @Test fun readableMissingSettingsRemainActionable() {
        val entries = PermissionCenterPolicy.entries(
            notificationsAllowed = false,
            taskChannelReady = false,
            exactAlarmAllowed = false,
            batteryUnrestricted = false,
            appDetectionEnabled = true,
            usageAccessAllowed = false
        )

        assertEquals(5, entries.count { it.status == PermissionCenterStatus.ACTION_NEEDED })
        assertTrue(PermissionCenterPolicy.summary(entries).contains("5 项待设置"))
        assertTrue(entries.filter { it.item.group == PermissionCenterGroup.REQUIRED_FOR_REMINDERS }
            .all { it.status == PermissionCenterStatus.ACTION_NEEDED })
    }
}
