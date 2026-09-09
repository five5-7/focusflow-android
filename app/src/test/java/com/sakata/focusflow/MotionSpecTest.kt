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
        assertEquals(MotionSpec.SUBPAGE_HOME_MS, tweenOf(MotionSpec.collapseHome<Float>()).durationMillis)
        assertEquals(MotionSpec.SUBPAGE_HOME_MS, tweenOf(MotionSpec.grow<Float>()).durationMillis)
        assertEquals(MotionSpec.SUBPAGE_CROSS_MS, tweenOf(MotionSpec.collapseAcross<Float>()).durationMillis)
    }

    @Test fun everySpecFollowsTheUserScale() {
        MotionSettings.update(0.5f)
        assertEquals(MotionSpec.ENTER_MS / 2, tweenOf(MotionSpec.enter<Float>()).durationMillis)
        assertEquals(MotionSpec.MORPH_MS / 2, tweenOf(MotionSpec.morph<Float>()).durationMillis)
        assertEquals(MotionSpec.SUBPAGE_HOME_MS / 2, tweenOf(MotionSpec.collapseHome<Float>()).durationMillis)
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
        assertTrue(MotionSpec.collapseHome<Float>() is SnapSpec<*>)
        assertTrue(MotionSpec.collapseAcross<Float>() is SnapSpec<*>)
        assertTrue(MotionSpec.grow<Float>() is SnapSpec<*>)
        assertTrue(!MotionSpec.animationsEnabled)
    }

    @Test fun enterAndExitKeepTheirOwnCurves() {
        MotionSettings.update(1f)
        assertSame(MotionSpec.enterEasing, tweenOf(MotionSpec.enter<Float>()).easing)
        assertSame(MotionSpec.exitEasing, tweenOf(MotionSpec.exit<Float>()).easing)
        assertSame(MotionSpec.enterEasing, tweenOf(MotionSpec.move<Float>()).easing)
        assertSame(MotionSpec.shrinkEasing, tweenOf(MotionSpec.collapseHome<Float>()).easing)
        assertSame(MotionSpec.shrinkEasing, tweenOf(MotionSpec.collapseAcross<Float>()).easing)
        assertSame(MotionSpec.enterEasing, tweenOf(MotionSpec.grow<Float>()).easing)
    }

    @Test fun subpageReturnTiersSpeed() {
        // 用户要求：回到自己主页要"快于切换到其他主页、慢于原来的 200ms"。
        assertTrue(MotionSpec.SUBPAGE_HOME_MS > 200)
        assertTrue(MotionSpec.SUBPAGE_HOME_MS < MotionSpec.SUBPAGE_CROSS_MS)
        // 副页收放要比整页平移快（缩放拖沓、平动过快的反馈）。
        assertTrue(MotionSpec.SUBPAGE_HOME_MS < MotionSpec.MOVE_MS)
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
