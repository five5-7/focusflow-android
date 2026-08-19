package com.sakata.focusflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WindDownInsightsTest {
    @Test fun windDownMinute_40BeforeSleep() {
        val profile = BaselineProfile(wakeMinute = 480, sleepMinute = 23 * 60)
        assertEquals(22 * 60 + 20, WindDownInsights.windDownMinute(profile, 1))
    }

    @Test fun windDownMinute_crossMidnight() {
        // 睡觉锚点 0:30（跨午夜）：减速开始 23:50
        val profile = BaselineProfile(wakeMinute = 8 * 60, sleepMinute = 30)
        assertEquals(23 * 60 + 50, WindDownInsights.windDownMinute(profile, 1))
    }

    @Test fun windDownMinute_invalidSleep_returnsNull() {
        val profile = BaselineProfile(wakeMinute = -1, sleepMinute = -1)
        assertNull(WindDownInsights.windDownMinute(profile, 1))
    }

    @Test fun formatMinute() {
        assertEquals("22:20", WindDownInsights.formatMinute(1340))
        assertEquals("00:05", WindDownInsights.formatMinute(5))
    }
}
