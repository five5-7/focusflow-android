package com.sakata.focusflow

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「页面是底、卡片是面」的层次**在整条渐变上都必须成立**。
 *
 * 维护者反馈：「为什么像外观这类卡片在渐变色暗的地方反而会亮一些，是不是写反了？」
 * —— 查下来确实反了，而且不是"卡片写反"，是**页面渐变的顶站亮过了卡片层**。
 *
 * 修前实测（薄荷绿·浅色）：
 * - 页面底色 `#EAEEEB`、卡片层 `surfaceContainerLow` `#FAFCFB`
 * - 顶站旧算式 = 底色混 85% 白 = `#FCFCFC` → **比卡片还亮 2/255**，卡片在页顶消失
 * - 底站 = `#B0B3B0`，卡片在那一端又亮得突兀
 * → 于是"卡片越往下越亮"，看起来就是反的。
 *
 * 这条测试把不变量钉死：浅色模式下 **渐变两端都不允许亮过卡片层**。
 */
class GradientCardLayeringTest {

    /** 与 pageStops 同一条算式：浅色模式的顶站在亮过卡片层时会被压回来。 */
    private fun pageTopFor(scheme: androidx.compose.material3.ColorScheme, s: Float): Color {
        val raw = blendSrgb(scheme.background, Color.White, 0.85f * s)
        val card = scheme.surfaceContainerLow
        return if (raw.luminance() > card.luminance()) {
            blendSrgb(card, scheme.background, 0.35f)
        } else {
            raw
        }
    }

    private fun lightSchemes() = FocusFlowThemeOption.builtInEntries()
        .map { it.label to focusFlowThemeSpec(it, darkMode = false).colorScheme }

    /**
     * 核心断言：**浅色模式下，页面渐变的任何一站都不能亮过卡片层。**
     * 只要这条成立，卡片在整页上就始终是"更亮的那一层"，不会再出现"暗处反而亮"。
     */
    @Test
    fun inLightModeThePageNeverOutshinesTheCardLayer() {
        for ((label, scheme) in lightSchemes()) {
            val card = scheme.surfaceContainerLow
            val stops = ThemeGradient.pageStops(scheme, strength = 1f)
            for ((i, stop) in stops.withIndex()) {
                assertTrue(
                    "$label 浅色：渐变第 $i 站亮度 ${stop.luminance()} 不该超过卡片层 ${card.luminance()}" +
                        "（超了卡片就会在那一端消失、在另一端突然变亮）",
                    stop.luminance() <= card.luminance() + 0.0005f
                )
            }
        }
    }

    /** 顶站还必须**明显低于**页面底色之外的卡片层之外——也就是顶站不能等于白。 */
    @Test
    fun theTopStopIsNotBlownOutToWhite() {
        for ((label, scheme) in lightSchemes()) {
            val top = ThemeGradient.pageStops(scheme, strength = 1f).first()
            assertTrue(
                "$label 浅色：顶站不该接近纯白（实际亮度 ${top.luminance()}）",
                top.luminance() < 0.99f
            )
        }
    }

    /** 深色模式方向相反：页面顶站可以比卡片稍亮，但不能亮成一条刺眼亮带。 */
    @Test
    fun inDarkModeTheTopStopStaysDark() {
        for (theme in FocusFlowThemeOption.builtInEntries()) {
            val scheme = focusFlowThemeSpec(theme, darkMode = true).colorScheme
            val top = ThemeGradient.pageStops(scheme, strength = GRADIENT_STRENGTH_MAX / 100f).first()
            assertTrue(
                "${theme.label} 深色：顶站在最大强度下也要保持够暗（实际 ${top.luminance()}）",
                top.luminance() < 0.35f
            )
        }
    }

    /** 强度为 0 时仍是纯色（这条不变量不能被上面的"压回"逻辑破坏）。 */
    @Test
    fun strengthZeroStillMeansPlainColour() {
        for ((label, scheme) in lightSchemes()) {
            val stops = ThemeGradient.pageStops(scheme, strength = 0f)
            val first = stops.first().argbInt()
            for (stop in stops) {
                assertTrue(
                    "$label 浅色：强度 0 时三站必须同色",
                    stop.argbInt() == first
                )
            }
        }
    }
}
