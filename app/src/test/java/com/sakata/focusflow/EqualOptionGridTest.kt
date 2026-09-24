package com.sakata.focusflow

import org.junit.Assert.assertEquals
import org.junit.Test

class EqualOptionGridTest {
    @Test fun fourOptionsStayBalancedOnPhonesAndExpandOnTablets() {
        assertEquals(2, equalOptionColumns(320f, 1f, 4))
        assertEquals(2, equalOptionColumns(540f, 1f, 4))
        assertEquals(4, equalOptionColumns(720f, 1f, 4))
    }

    @Test fun narrowScreensAndLargeTextUseOneColumn() {
        assertEquals(1, equalOptionColumns(270f, 1f, 4))
        assertEquals(1, equalOptionColumns(320f, 1.4f, 4))
        assertEquals(2, equalOptionColumns(420f, 1.4f, 4))
        assertEquals(1, equalOptionColumns(720f, 1f, 1))
    }
}
