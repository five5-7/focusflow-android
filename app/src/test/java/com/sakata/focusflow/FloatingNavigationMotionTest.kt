package com.sakata.focusflow

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FloatingNavigationMotionTest {
    @Test
    fun cornerSymbolRemainsWhileItsCollapseAnimationIsRunning() {
        assertTrue(cornerSymbolShouldRemain(visible = false, progress = 0.5f))
        assertTrue(cornerSymbolShouldRemain(visible = false, progress = 0.011f))
        assertFalse(cornerSymbolShouldRemain(visible = false, progress = 0f))
        assertTrue(cornerSymbolShouldRemain(visible = true, progress = 0f))
    }
}
