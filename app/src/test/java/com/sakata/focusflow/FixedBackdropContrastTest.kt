package com.sakata.focusflow

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **固定底色 + 丰富效果开/关** 下的正文对比度（目标第 1 项与第 5 项的正式回归）。
 *
 * 维护者原话：
 * - 「我选的是固定的底色，比如海盐蓝，在关闭丰富之后就会过亮，打开之后，其他颜色的字是黑色，反而过暗」
 * - 「对比度我主要指的是字和底色的关系」
 *
 * 这一条把"固定颜色"档在**两种明暗 × 丰富开/关**下的正文对比度全部锁住：
 * 背景实际怎么画由 `effectivePageBackdrop` + `adaptBackdropColor` 决定，
 * 这里直接复用生产函数算，避免"测试与实现各算一套"。
 */
class FixedBackdropContrastTest {

    /** 与 appearanceBackdrop 的 COLOR 分支同一条算式：底色 → 顶部提亮 6% 的竖向渐变。 */
    private fun colorBranchStops(spec: AppearanceSpec, scheme: androidx.compose.material3.ColorScheme): List<Int> {
        val auto = blendSrgb(scheme.background, scheme.primary, 0.10f)
        val picked = if (spec.pageColor != 0) Color(spec.pageColor) else auto
        val base = adaptBackdropColor(scheme, picked)
        return listOf(blendSrgb(base, Color.White, 0.06f).argbInt(), base.argbInt())
    }

    /**
     * 关键断言：**关掉丰富效果不能改变固定底色的渲染**。
     * `effectivePageBackdrop` 只把 GRADIENT/IMAGE 回落成 THEME，COLOR 是刻意保留的
     * （那只是一块纯色填充，几乎没有绘制成本，而且是用户明确选过的页面主色）。
     * 所以"关了过亮"如果真出现过，原因不会是这个开关，而是底色本身的对比度——那由下面第二条守。
     */
    @Test
    fun turningRichEffectsOffKeepsTheFixedColourOnScreen() {
        val fix = AppearanceSpec(pageBackdrop = BackdropKind.COLOR, pageColor = PAGE_BASE_PRESETS[1])
        val off = fix.copy(richEffects = false)
        assertEquals(
            "固定颜色不该被「丰富效果」开关砍掉",
            BackdropKind.COLOR,
            off.effectivePageBackdrop
        )
        assertEquals("原始偏好必须留着，才能再开回来", PAGE_BASE_PRESETS[1], off.pageColor)
        assertEquals(
            "再打开应立刻恢复",
            BackdropKind.COLOR,
            fix.copy(richEffects = true).effectivePageBackdrop
        )
    }

    /**
     * 固定底色的正文对比度：8 套主题 × 明暗 × 全部 8 个预设，**关/开丰富效果都要达标**。
     * 用最不利的一站（顶部提亮 6% 那一站对深色正文最不利）。
     */
    @Test
    fun fixedColourKeepsBodyTextReadableInEveryThemeModeAndPreset() {
        for (theme in FocusFlowThemeOption.builtInEntries()) {
            for (dark in listOf(false, true)) {
                val scheme = focusFlowThemeSpec(theme, darkMode = dark).colorScheme
                val on = scheme.onBackground.argbInt()
                for (preset in PAGE_BASE_PRESETS) {
                    for (rich in listOf(true, false)) {
                        val spec = AppearanceSpec(
                            pageBackdrop = BackdropKind.COLOR,
                            pageColor = preset,
                            richEffects = rich
                        )
                        for (bg in colorBranchStops(spec, scheme)) {
                            val ratio = AppearanceContrast.ratio(on, bg)
                            assertTrue(
                                "${theme.label}（${if (dark) "深色" else "浅色"}）固定底色 " +
                                    "%08X（丰富效果%s）正文只有 %.2f:1，应 >= %.1f:1"
                                        .format(preset, if (rich) "开" else "关", ratio, AppearanceContrast.AA_BODY),
                                ratio >= AppearanceContrast.AA_BODY
                            )
                        }
                    }
                }
            }
        }
    }

    /** 跟随主题（pageColor = 0）的自动派生底色同样要达标。 */
    @Test
    fun derivedFixedColourIsAlsoReadable() {
        for (theme in FocusFlowThemeOption.builtInEntries()) {
            for (dark in listOf(false, true)) {
                val scheme = focusFlowThemeSpec(theme, darkMode = dark).colorScheme
                val on = scheme.onBackground.argbInt()
                val spec = AppearanceSpec(pageBackdrop = BackdropKind.COLOR, pageColor = 0)
                for (bg in colorBranchStops(spec, scheme)) {
                    val ratio = AppearanceContrast.ratio(on, bg)
                    assertTrue(
                        "${theme.label}（${if (dark) "深色" else "浅色"}）派生底色正文只有 $ratio:1",
                        ratio >= AppearanceContrast.AA_BODY
                    )
                }
            }
        }
    }
}
