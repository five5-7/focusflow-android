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

    @Test
    fun `each idle window warms only the next pending tab`() {
        assertEquals(2, StartupWorkPolicy.nextPendingTab(visited = setOf(0, 1), current = 1))
        assertEquals(null, StartupWorkPolicy.nextPendingTab(visited = setOf(0, 1, 2, 3), current = 1))
    }

    @Test
    fun `interaction restarts a separate idle window`() {
        assertEquals(2_400L, StartupWorkPolicy.warmupIdleMs(hasInteracted = false))
        assertEquals(1_400L, StartupWorkPolicy.warmupIdleMs(hasInteracted = true))
        assertTrue(
            StartupWorkPolicy.warmupIdleMs(hasInteracted = false) >
                StartupWorkPolicy.warmupIdleMs(hasInteracted = true)
        )
    }
}
