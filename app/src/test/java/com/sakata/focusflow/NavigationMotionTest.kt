package com.sakata.focusflow

import org.junit.Assert.assertEquals
import org.junit.Test

class NavigationMotionTest {
    @Test fun depthSelectsForwardBackAndSibling() {
        assertEquals(1, NavigationMotion.direction(0, 1))
        assertEquals(1, NavigationMotion.direction(1, 2))
        assertEquals(-1, NavigationMotion.direction(2, 1))
        assertEquals(-1, NavigationMotion.direction(1, 0))
        assertEquals(0, NavigationMotion.direction(2, 2))
    }

    @Test fun nestedSettingsHistory_preservesAllParentsAndAvoidsDuplicateTaps() {
        var history = NavigationMotion.historyAfterOpen(emptyList<String>(), null, "advanced")
        history = NavigationMotion.historyAfterOpen(history, "advanced", "commute")
        history = NavigationMotion.historyAfterOpen(history, "commute", "places")
        assertEquals(listOf("advanced", "commute"), history)
        assertEquals(history, NavigationMotion.historyAfterOpen(history, "places", "places"))
        assertEquals("commute", history.lastOrNull())
        assertEquals("advanced", history.dropLast(1).lastOrNull())
        assertEquals(emptyList<String>(), NavigationMotion.historyAfterOpen(history, "places", "advanced"))
    }
}
