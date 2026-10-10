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
        assertEquals(8, FloatingNavigationLayout.horizontalMarginDp(393f, 1f))
    }
    @Test fun narrowPhoneAndLargeTextReduceMargins() {
        assertEquals(6, FloatingNavigationLayout.horizontalMarginDp(320f, 1f))
        assertEquals(6, FloatingNavigationLayout.horizontalMarginDp(393f, 1.5f))
        assertEquals(4, FloatingNavigationLayout.horizontalMarginDp(250f, 2f))
    }
    @Test fun historyArrowTotalMovementNeverExceedsAddedWidthOnEitherSide() {
        for ((width, scale, oldMargin) in listOf(
            Triple(393f, 1f, 16), Triple(320f, 1f, 8),
            Triple(393f, 1.5f, 8), Triple(250f, 2f, 4),
            Triple(660f, 1f, 16), Triple(1000f, 1f, 16)
        )) {
            val margin = FloatingNavigationLayout.horizontalMarginDp(width, scale)
            val oldWidth = minOf(FloatingNavigationLayout.MAX_BAR_WIDTH_DP.toFloat(), width - oldMargin * 2)
            val newWidth = minOf(FloatingNavigationLayout.MAX_BAR_WIDTH_DP.toFloat(), width - margin * 2)
            val addedWidth = (newWidth - oldWidth) / 2f
            // Compare absolute screen positions, including movement of the bar anchor.
            val oldLeft = (width - oldWidth) / 2f - 4
            val newLeft = (width - newWidth) / 2f - FloatingNavigationLayout.historyArrowOffsetDp(width, scale)
            val totalMovement = oldLeft - newLeft
            assertTrue(totalMovement >= 0f && totalMovement <= addedWidth)
            assertEquals(totalMovement, (width - newLeft) - (width - oldLeft), 0f)
        }
    }
    @Test fun minimumWidthKeepsFiveTouchTargetsUsable() {
        assertTrue(FloatingNavigationLayout.MIN_CONTENT_WIDTH_DP >= 5 * 48)
        assertTrue(FloatingNavigationLayout.MAX_BAR_WIDTH_DP >= FloatingNavigationLayout.MIN_CONTENT_WIDTH_DP)
    }
}
