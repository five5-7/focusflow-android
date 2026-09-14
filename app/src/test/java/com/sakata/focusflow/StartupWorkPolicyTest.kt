package com.sakata.focusflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupWorkPolicyTest {
    @Test
    fun `loading and dialogs block hidden tab warmup`() {
        assertFalse(StartupWorkPolicy.canWarmTabs(globalLoading = true, dialogVisible = false))
        assertFalse(StartupWorkPolicy.canWarmTabs(globalLoading = false, dialogVisible = true))
        assertTrue(StartupWorkPolicy.canWarmTabs(globalLoading = false, dialogVisible = false))
    }

    @Test
    fun `warmup excludes visible and already visited tabs`() {
        assertEquals(listOf(2, 3), StartupWorkPolicy.pendingTabs(visited = setOf(0, 1), current = 1))
    }
}
