package com.sakata.focusflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FloatingNavigationLayoutTest {
    @Test fun regularPhoneKeepsFloatingMargins() {
        assertEquals(16, FloatingNavigationLayout.horizontalMarginDp(393f, 1f))
    }
    @Test fun narrowPhoneAndLargeTextReduceMargins() {
        assertEquals(8, FloatingNavigationLayout.horizontalMarginDp(320f, 1f))
        assertEquals(8, FloatingNavigationLayout.horizontalMarginDp(393f, 1.5f))
        assertEquals(4, FloatingNavigationLayout.horizontalMarginDp(250f, 2f))
    }
    @Test fun minimumWidthKeepsFiveTouchTargetsUsable() {
        assertTrue(FloatingNavigationLayout.MIN_CONTENT_WIDTH_DP >= 5 * 48)
        assertTrue(FloatingNavigationLayout.MAX_BAR_WIDTH_DP >= FloatingNavigationLayout.MIN_CONTENT_WIDTH_DP)
    }
}
