package com.sakata.focusflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationGuidancePolicyTest {
    @Test fun `common brands map to expected families`() {
        assertEquals(NotificationDeviceFamily.SAMSUNG, NotificationGuidancePolicy.family("samsung", "SM-S9280"))
        assertEquals(NotificationDeviceFamily.XIAOMI, NotificationGuidancePolicy.family("Xiaomi", "POCO"))
        assertEquals(NotificationDeviceFamily.XIAOMI, NotificationGuidancePolicy.family("unknown", "Redmi"))
        assertEquals(NotificationDeviceFamily.HUAWEI_HONOR, NotificationGuidancePolicy.family("HONOR", "magic"))
        assertEquals(NotificationDeviceFamily.COLOR_OS, NotificationGuidancePolicy.family("OnePlus", "OnePlus"))
        assertEquals(NotificationDeviceFamily.COLOR_OS, NotificationGuidancePolicy.family("realme", "RMX"))
        assertEquals(NotificationDeviceFamily.VIVO, NotificationGuidancePolicy.family("vivo", "iQOO"))
        assertEquals(NotificationDeviceFamily.PIXEL_AOSP, NotificationGuidancePolicy.family("Google", "Pixel"))
        assertEquals(NotificationDeviceFamily.GENERIC, NotificationGuidancePolicy.family("Nothing", "A063"))
    }

    @Test fun `every device gets common instructions and the API limitation`() {
        val identities = listOf("samsung", "xiaomi", "huawei", "oppo", "vivo", "google", "unknown")
        identities.forEach { identity ->
            val guidance = NotificationGuidancePolicy.forDevice(identity, identity)
            assertTrue(guidance.steps.joinToString().contains("允许通知"))
            assertTrue(guidance.steps.joinToString().contains("FocusFlow 任务提醒"))
            assertTrue(guidance.limitation.contains("无法由应用读取"))
        }
    }
}
