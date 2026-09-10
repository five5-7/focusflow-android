package com.sakata.focusflow

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 「底栏跟随页面渐变取色」的**矩阵回归**（维护者口径：不要只针对一种情况打补丁）。
 *
 * 维护者原话：「导航栏没有相应调整的话，假如按默认渐变，下侧是偏暗的，导航栏就偏亮了。
 * 另外，确认其他的渐变方案不会出现类似问题，我希望你不只是配置了针对这个情况的一种变化。」
 *
 * 所以这里不测"顶亮底深时底栏要压暗"这一条特例，而是对
 * **7 主题 × 浅/深 × 全部 13 组预制 × 全部 6 个方向 × 3 档强度** 全枚举，断言同一件事：
 * 底栏相对**它背后那一点**的分离度，必须与"平坦页面"下的设计值一致。
 * 只要这条成立，任何一种渐变方案都不可能让底栏变得突兀。
 */
class NavBarOverGradientTest {

    private fun ratio(a: Color, b: Color): Float =
        AppearanceContrast.ratio(a.argbInt(), b.argbInt())

    private fun luminance(c: Color): Float = AppearanceContrast.luminance(c.argbInt())

    private fun allSpecs(): List<AppearanceSpec> {
        val out = mutableListOf<AppearanceSpec>()
        for (dir in GradientDirection.entries) {
            // 跟随主题（不自选端色）
            for (strength in listOf(100, 200, GRADIENT_STRENGTH_MAX)) {
                out += AppearanceSpec(
                    pageBackdrop = BackdropKind.GRADIENT,
                    gradientDirection = dir,
                    gradientStrength = strength
                )
            }
            // 每一组自选预制
            for ((top, bottom) in ThemeGradient.PAGE_GRADIENT_PAIRS) {
                out += AppearanceSpec(
                    pageBackdrop = BackdropKind.GRADIENT,
                    gradientDirection = dir,
                    gradientTop = top,
                    gradientBottom = bottom
                )
            }
        }
        return out
    }

    /**
     * 核心断言：底栏与"它背后那一点"的对比度，各主题/明暗/方向/配色之间**必须保持一致**。
     *
     * 这是"设计关系守恒"的直接表述——如果某个方向让底栏突兀，它的比值就会偏离其它情况。
     */
    @Test
    fun navBarKeepsTheSameSeparationFromWhateverIsBehindIt() {
        for (theme in FocusFlowThemeOption.builtInEntries()) {
            for (dark in listOf(false, true)) {
                val spec0 = focusFlowThemeSpec(theme, darkMode = dark)
                val scheme = spec0.colorScheme
                // 基准：平坦页面（跟随主题）下底栏与页面底色的分离度 = 设计值
                val baseline = ratio(spec0.navigationBarColor, scheme.background)
                assertTrue(
                    "${theme.label}（${if (dark) "深色" else "浅色"}）基准分离度 $baseline 应 > 1（底栏要能看出来）",
                    baseline > 1.01f
                )
                for (spec in allSpecs()) {
                    val nav = navBarColourOverBackdrop(spec, spec0)
                    val behind = backdropColourBehindNavBar(spec, scheme)
                    val actual = ratio(nav, behind)
                    // 允许一点浮点/取整误差，但不允许"跑到另一头"（那正是"偏亮"的观感来源）
                    assertTrue(
                        "${theme.label}（${if (dark) "深色" else "浅色"}）方向=${spec.gradientDirection} " +
                            "配色=${"%08X".format(spec.gradientTop)}/${"%08X".format(spec.gradientBottom)} " +
                            "强度=${spec.gradientStrength}%：底栏对背后底色 $actual，" +
                            "应贴近基准 $baseline",
                        abs(actual - baseline) < 0.08f
                    )
                }
            }
        }
    }

    /** 底栏在渐变最暗一端**不能反而变亮**：这就是维护者说的"下侧偏暗、导航栏偏亮"。 */
    @Test
    fun navBarIsNeverBrighterThanTheRegionItFloatsOver() {
        for (theme in FocusFlowThemeOption.builtInEntries()) {
            for (dark in listOf(false, true)) {
                val spec0 = focusFlowThemeSpec(theme, darkMode = dark)
                val scheme = spec0.colorScheme
                for (spec in allSpecs()) {
                    val nav = navBarColourOverBackdrop(spec, spec0)
                    val behind = backdropColourBehindNavBar(spec, scheme)
                    // 设计关系是"底栏比页面底色暗一档"；因为差值被原样搬过来，
                    // 所以只要基准是"底栏更暗"，任何渐变下都必须仍然更暗。
                    if (luminance(spec0.navigationBarColor) < luminance(scheme.background)) {
                        assertTrue(
                            "${theme.label}（${if (dark) "深色" else "浅色"}）方向=${spec.gradientDirection}：" +
                                "底栏亮度 ${luminance(nav)} 不该亮过它背后的 ${luminance(behind)}",
                            luminance(nav) <= luminance(behind) + 0.005f
                        )
                    }
                }
            }
        }
    }

    /** 非渐变档必须**完全等于**主题原本的底栏色（默认外观逐像素不变）。 */
    @Test
    fun nonGradientBackdropsKeepTheThemeNavBarColourExactly() {
        val spec0 = focusFlowThemeSpec(FocusFlowThemeOption.OCEAN)
        for (backdrop in listOf(BackdropKind.THEME, BackdropKind.COLOR)) {
            val spec = AppearanceSpec(pageBackdrop = backdrop, pageColor = PAGE_BASE_PRESETS[0])
            assertEquals(
                "$backdrop 档下底栏应原样用主题色",
                spec0.navigationBarColor.argbInt(),
                navBarColourOverBackdrop(spec, spec0).argbInt()
            )
        }
    }

    /** 关掉「丰富效果」时渐变不参与渲染，底栏也必须回到主题色。 */
    @Test
    fun richEffectsOffFallsBackToTheThemeNavBarColour() {
        val spec0 = focusFlowThemeSpec(FocusFlowThemeOption.MINT)
        val spec = AppearanceSpec(
            pageBackdrop = BackdropKind.GRADIENT,
            gradientDirection = GradientDirection.BOTTOM_UP,
            richEffects = false
        )
        assertEquals(
            "关掉丰富效果后渐变不画了，底栏也就该回到主题色",
            spec0.navigationBarColor.argbInt(),
            navBarColourOverBackdrop(spec, spec0).argbInt()
        )
    }

    /** 取色函数本身：六个方向在同一位置的取值必须与"铺的方向"一致。 */
    @Test
    fun gradientColourAtFollowsEveryDirection() {
        // 与真实设计同序：顶站最亮 → 中站 → 底站最暗（"一束光从上方打下来"）。
        // 用这个顺序，「底栏背后应偏暗」才是有意义的断言。
        val stops = listOf(Color(0xFFFFFFFF), Color(0xFF808080), Color(0xFF000000))
        // 左上角：上→下 取顶站；下→上 取底站
        assertEquals(stops[0].argbInt(), gradientColourAt(GradientDirection.TOP_DOWN, stops, 0f, 0f).argbInt())
        assertEquals(stops[2].argbInt(), gradientColourAt(GradientDirection.BOTTOM_UP, stops, 0f, 0f).argbInt())
        // 左上角：左→右 取顶站；右→左 取底站
        assertEquals(stops[0].argbInt(), gradientColourAt(GradientDirection.LEFT_RIGHT, stops, 0f, 0f).argbInt())
        assertEquals(stops[2].argbInt(), gradientColourAt(GradientDirection.RIGHT_LEFT, stops, 0f, 0f).argbInt())
        // 对角线：两端各取首尾
        assertEquals(stops[0].argbInt(), gradientColourAt(GradientDirection.DIAGONAL_DOWN, stops, 0f, 0f).argbInt())
        assertEquals(stops[2].argbInt(), gradientColourAt(GradientDirection.DIAGONAL_DOWN, stops, 1f, 1f).argbInt())
        assertEquals(stops[0].argbInt(), gradientColourAt(GradientDirection.DIAGONAL_UP, stops, 0f, 1f).argbInt())
        assertEquals(stops[2].argbInt(), gradientColourAt(GradientDirection.DIAGONAL_UP, stops, 1f, 0f).argbInt())
        // 底栏那一点（底部居中）在 上→下 时应该接近底站，而不是顶站
        val behindNav = gradientColourAt(GradientDirection.TOP_DOWN, stops, NAV_BAR_CENTRE_X, NAV_BAR_CENTRE_Y)
        assertTrue(
            "上→下时底栏背后应偏暗（接近底站），实际亮度 ${behindNav.luminance()}",
            behindNav.luminance() < 0.3f
        )
        assertFalse("不该等于顶站", behindNav.argbInt() == stops[0].argbInt())
    }
}
