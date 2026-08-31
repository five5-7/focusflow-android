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
}
