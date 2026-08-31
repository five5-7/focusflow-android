package com.sakata.focusflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FloatingNavigationLayoutTest {
    @Test fun selectedIconFitsInsideItsTouchSlotAndOuterCorners() {
        assertTrue(FloatingNavigationLayout.INNER_PADDING_DP >= 8)
        assertTrue(FloatingNavigationLayout.MIN_CONTENT_WIDTH_DP / 5 >= 40 + 8)
        assertTrue(FloatingNavigationLayout.ITEM_RADIUS_DP + FloatingNavigationLayout.INNER_PADDING_DP <= FloatingNavigationLayout.OUTER_RADIUS_DP)
        assertTrue(FloatingNavigationLayout.MIN_ITEM_HEIGHT_DP >= 48)
    }
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
