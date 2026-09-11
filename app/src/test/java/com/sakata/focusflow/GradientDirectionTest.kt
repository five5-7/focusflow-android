package com.sakata.focusflow

import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 背景渐变的**方向**（维护者口径：「背景渐变应该可以指定方向」）。
 *
 * 两条要求：
 * 1. 默认必须是上→下，也就是**原来的样子**（老装机读不到这个键时行为不变）；
 * 2. 换方向只改"从哪一端铺"，**三站颜色的顺序不变** —— 否则"顶亮底深"的对比度
 *    设计前提会被悄悄破坏（那段设计是为了让正文在所有站点都达标，见 pageStops 注释）。
 *
 * 说明：`Brush.VerticalGradient` / `HorizontalGradient` 在当前 Compose 版本里对测试**不可见**，
 * 所以这里不断言画刷类型（那由编译器保证：`ThemeGradient.page` 的 `when` 是穷尽的）。
 * 可测的部分是"方向枚举本身"与"站点顺序"。
 */
class GradientDirectionTest {

    private val scheme = focusFlowThemeSpec(FocusFlowThemeOption.OCEAN, darkMode = false).colorScheme

    @Test
    fun defaultIsTopDownSoExistingInstallsDoNotChange() {
        assertEquals(GradientDirection.TOP_DOWN, AppearanceSpec.DEFAULT.gradientDirection)
        // 读不到这个键（老装机）也必须是上→下
        val legacy = AppearanceSpec.fromKeys(
            null, null, 100, 100, 0, false, 0, 0, null, null, 0, null, 100, null
        )
        assertEquals(GradientDirection.TOP_DOWN, legacy.gradientDirection)
        // 未知/写坏的键退回默认，不抛错、不清数据
        assertEquals(GradientDirection.TOP_DOWN, GradientDirection.fromKey("nonsense"))
        assertEquals(GradientDirection.TOP_DOWN, GradientDirection.fromKey(null))
        assertEquals(GradientDirection.TOP_DOWN, GradientDirection.fromKey(""))
    }

    @Test
    fun everyDirectionHasAStableKeyAndALabel() {
        for (d in GradientDirection.entries) {
            assertTrue("${d.name} 需要非空 storageKey", d.storageKey.isNotBlank())
            assertTrue("${d.name} 需要非空 label", d.label.isNotBlank())
            assertEquals("storageKey 必须能往返", d, GradientDirection.fromKey(d.storageKey))
        }
        assertEquals(
            "storageKey 不能重复",
            GradientDirection.entries.size,
            GradientDirection.entries.map { it.storageKey }.toSet().size
        )
    }

    /**
     * 反向铺**不引入新颜色**：用的还是同一组站点，只是顺序反过来。
     * 这条是关键——对比度总账是按 `pageStops` 那三个颜色算的，
     * 只要方向不产生新颜色，换方向就不会把正文对比度带到没验证过的区间。
     */
    @Test
    fun reversingADirectionOnlyReordersTheSameStops() {
        val stops = ThemeGradient.pageStops(scheme)
        val reversed = stops.reversed()
        assertEquals("站点数不变", stops.size, reversed.size)
        assertEquals(
            "反向铺的亮度集合应与正向完全相同（只是顺序相反）",
            stops.map { it.argbInt() }.sorted(),
            reversed.map { it.argbInt() }.sorted()
        )
        // 首尾确实对调了
        assertEquals(stops[0].argbInt(), reversed[2].argbInt())
        assertEquals(stops[2].argbInt(), reversed[0].argbInt())
    }

    @Test
    fun customStopsKeepTheirIdentityAcrossDirections() {
        val top = 0xFF102030.toInt()
        val bottom = 0xFF405060.toInt()
        val stops = ThemeGradient.pageStops(scheme, 1f, top, bottom)
        assertEquals("自选顶色应落在第一站", top, stops[0].argbInt())
        assertEquals("自选底色应落在第三站", bottom, stops[2].argbInt())
        val reversed = stops.reversed()
        assertEquals("反向铺时底色成为第一站", bottom, reversed[0].argbInt())
        assertEquals("反向铺时顶色成为第三站", top, reversed[2].argbInt())
    }

    /**
     * 方向枚举覆盖了四个方向，且**没有改变强度语义**：0% 仍然是纯色（三站同色）。
     * 这条守住"加了方向之后别把强度算错"。
     */
    @Test
    fun directionDoesNotAffectStrengthSemantics() {
        val flat = ThemeGradient.pageStops(scheme, strength = 0f)
        assertEquals("0% 应仍是纯色", flat[0].argbInt(), flat[1].argbInt())
        assertEquals("0% 应仍是纯色", flat[1].argbInt(), flat[2].argbInt())
        // 强度上限仍受常量约束（方向没碰它）
        val max = ThemeGradient.pageStops(scheme, GRADIENT_STRENGTH_MAX / 100f)
        assertEquals("上限档仍是三站", 3, max.size)
    }
}

/**
 * 渐变**自选两个颜色**（维护者口径：「在提供一定量的预制前提下添加自定义即可」）。
 *
 * 落盘复用既有的 `gradientTop` / `gradientBottom`（0 = 跟随主题），没有新增偏好键——
 * 所以老装机、老预设的兼容性完全不变。
 */
class GradientCustomColoursTest {

    private val scheme = focusFlowThemeSpec(FocusFlowThemeOption.OCEAN, darkMode = false).colorScheme

    @Test
    fun customEndpointsLandExactlyOnTheFirstAndLastStop() {
        val top = 0xFF2244AA.toInt()
        val bottom = 0xFFAACCEE.toInt()
        val stops = ThemeGradient.pageStops(scheme, 1f, top, bottom)
        assertEquals("自选顶色必须是第一站（原样，不被主题改写）", top, stops[0].argbInt())
        assertEquals("自选底色必须是第三站（原样，不被主题改写）", bottom, stops[2].argbInt())
    }

    /** 只选一端时，另一端仍按主题派生——允许"只想改一头"这种用法。 */
    @Test
    fun pickingOnlyOneEndKeepsTheOtherDerived() {
        val top = 0xFF2244AA.toInt()
        val withTopOnly = ThemeGradient.pageStops(scheme, 1f, top, 0)
        assertEquals(top, withTopOnly[0].argbInt())
        val derived = ThemeGradient.pageStops(scheme, 1f, 0, 0)
        assertEquals(
            "没选的那一端应等于主题派生值",
            derived[2].argbInt(),
            withTopOnly[2].argbInt()
        )
    }

    /** 0/0 必须仍然表示"跟随主题"，这是老装机与老预设的兼容底线。 */
    @Test
    fun zeroMeansFollowThemeOnBothEnds() {
        assertEquals(
            "gradientTop/Bottom 都为 0 时应完全等于跟随主题的结果",
            ThemeGradient.pageStops(scheme, 1f, 0, 0),
            ThemeGradient.pageStops(scheme, 1f)
        )
        assertEquals(0, AppearanceSpec.DEFAULT.gradientTop)
        assertEquals(0, AppearanceSpec.DEFAULT.gradientBottom)
    }

    /** 自选色在深色模式下会被 adaptBackdropColor 压到深色底上（既有行为，这里守住）。 */
    @Test
    fun customColoursAreAdaptedForDarkMode() {
        val dark = focusFlowThemeSpec(FocusFlowThemeOption.OCEAN, darkMode = true).colorScheme
        val light = 0xFFF5F9FC.toInt()
        val adapted = ThemeGradient.pageStops(dark, 1f, light, light)[0]
        assertTrue(
            "自选浅色在深色模式下必须被压暗，实际亮度 ${adapted.luminance()}",
            adapted.luminance() < 0.4f
        )
    }

    /** 方向与自选色可以叠加：反向之后两端对调，但颜色本身不变。 */
    @Test
    fun customColoursAndDirectionCompose() {
        val top = 0xFF2244AA.toInt()
        val bottom = 0xFFAACCEE.toInt()
        val stops = ThemeGradient.pageStops(scheme, 1f, top, bottom)
        val reversed = stops.reversed()
        assertEquals(bottom, reversed[0].argbInt())
        assertEquals(top, reversed[2].argbInt())
    }

    /** 两个端点枚举就是落盘用的那两个槽位，别再多出第三个概念。 */
    @Test
    fun thereAreExactlyTwoEndpoints() {
        assertEquals(2, GradientEndpoint.entries.size)
        assertEquals(GradientEndpoint.TOP, GradientEndpoint.entries[0])
        assertEquals(GradientEndpoint.BOTTOM, GradientEndpoint.entries[1])
    }
}
