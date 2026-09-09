package com.sakata.focusflow

import androidx.compose.animation.core.SnapSpec
import androidx.compose.animation.core.TweenSpec
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** 8.1.0 第三轮：动效规范必须随「外观 → 动画速度」整体缩放，关闭时不得残留过渡。 */
class MotionSpecTest {
    @After fun restoreDefaultScale() {
        MotionSettings.update(1f)
    }

    private fun tweenOf(spec: Any): TweenSpec<Float> {
        assertTrue("expected tween, got $spec", spec is TweenSpec<*>)
        @Suppress("UNCHECKED_CAST")
        return spec as TweenSpec<Float>
    }

    @Test fun standardScaleUsesBaseDurations() {
        MotionSettings.update(1f)
        assertEquals(MotionSpec.ENTER_MS, tweenOf(MotionSpec.enter<Float>()).durationMillis)
        assertEquals(MotionSpec.EXIT_MS, tweenOf(MotionSpec.exit<Float>()).durationMillis)
        assertEquals(MotionSpec.MOVE_MS, tweenOf(MotionSpec.move<Float>()).durationMillis)
        assertEquals(MotionSpec.QUICK_MS, tweenOf(MotionSpec.quick<Float>()).durationMillis)
        assertEquals(MotionSpec.MORPH_MS, tweenOf(MotionSpec.morph<Float>()).durationMillis)
    }

    @Test fun everySpecFollowsTheUserScale() {
        MotionSettings.update(0.5f)
        assertEquals(MotionSpec.ENTER_MS / 2, tweenOf(MotionSpec.enter<Float>()).durationMillis)
        assertEquals(MotionSpec.MORPH_MS / 2, tweenOf(MotionSpec.morph<Float>()).durationMillis)
        MotionSettings.update(1.5f)
        assertEquals((MotionSpec.ENTER_MS * 1.5f).toInt(), tweenOf(MotionSpec.enter<Float>()).durationMillis)
        assertEquals((MotionSpec.EXIT_MS * 1.5f).toInt(), tweenOf(MotionSpec.exit<Float>()).durationMillis)
    }

    @Test fun disablingMotionSnapsInsteadOfAnimating() {
        MotionSettings.update(0f)
        assertTrue(MotionSpec.enter<Float>() is SnapSpec<*>)
        assertTrue(MotionSpec.exit<Float>() is SnapSpec<*>)
        assertTrue(MotionSpec.move<Float>() is SnapSpec<*>)
        assertTrue(MotionSpec.quick<Float>() is SnapSpec<*>)
        assertTrue(MotionSpec.morph<Float>() is SnapSpec<*>)
        assertTrue(!MotionSpec.animationsEnabled)
    }

    @Test fun enterAndExitKeepTheirOwnCurves() {
        MotionSettings.update(1f)
        assertSame(MotionSpec.enterEasing, tweenOf(MotionSpec.enter<Float>()).easing)
        assertSame(MotionSpec.exitEasing, tweenOf(MotionSpec.exit<Float>()).easing)
        assertSame(MotionSpec.enterEasing, tweenOf(MotionSpec.move<Float>()).easing)
    }

    @Test fun userScaleIsClampedToTheSupportedRange() {
        MotionSettings.update(9f)
        assertEquals(1.5f, MotionSettings.durationScale, 0.0001f)
        MotionSettings.update(-3f)
        assertEquals(0f, MotionSettings.durationScale, 0.0001f)
    }

    @Test fun morphOutlastsEnterWhichOutlastsExit() {
        assertTrue(MotionSpec.MORPH_MS > MotionSpec.ENTER_MS)
        assertTrue(MotionSpec.ENTER_MS > MotionSpec.EXIT_MS)
    }
}
