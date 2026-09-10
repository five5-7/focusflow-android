package com.sakata.focusflow

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import org.junit.Assert.*
import org.junit.Test

class NavigationThemeTest {
    @Test fun eachBuiltInHasItsOwnNavAndCardColors() {
        val themes = FocusFlowThemeOption.builtInEntries()
        // 全量只守"两两不同"（同色说明主题没带自己的中性壳）。
        for (dark in listOf(false, true)) {
            val values = themes.map { focusFlowThemeSpec(it, darkMode = dark).navigationBarColor.argbInt() }
            assertEquals("导航栏色应两两不同（深色=$dark）", values.size, values.distinct().size)
        }
        val cards = themes.map { focusFlowThemeSpec(it).colorScheme.surfaceContainerLow.argbInt() }
        assertEquals("每套主题都该有自己的卡片底色", cards.size, cards.distinct().size)

        // 8.2.0 新增主题另加一条硬要求：**浅色模式**下与其它任何主题的导航栏色通道差 ≥ 6。
        // 只要求浅色，是因为深色模式的导航栏色统一被 lerp(Black, nav, 0.22) 压扁
        // （原有四套之间在深色下也只差 1 级）——那是既有行为，不能为新主题去改。
        // 原有四套的配色是维护者要求冻结的，同样不能为了这条去改它们；
        // 但也正因如此，新主题不能只是"数值上不同、看着却一样"。
        val added = listOf("graphite", "sakura", "bamboo")
        val specs = themes.map { it.storageKey to focusFlowThemeSpec(it).navigationBarColor.argbInt() }
        for ((key, value) in specs.filter { it.first in added }) {
            for ((otherKey, otherValue) in specs.filter { it.first != key }) {
                val distance = AppearanceContrast.channelDistance(value, otherValue)
                assertTrue("新主题 $key 与 $otherKey 的导航栏色太接近（通道差 $distance）", distance >= 6)
            }
        }
    }
    @Test fun lightModeUsesExactSixthColorAndDarkModeDimsIt() {
        val custom = FocusFlowThemeOption.CUSTOM.colors.copy(navigationBar = Color(0xFF654321))
        assertEquals(custom.navigationBar, focusFlowThemeSpec(FocusFlowThemeOption.CUSTOM, custom).navigationBarColor)
        assertEquals(lerp(Color.Black, custom.navigationBar, 0.22f),
            focusFlowThemeSpec(FocusFlowThemeOption.CUSTOM, custom, true).navigationBarColor)
    }
    @Test fun navTextAndIconContrastSurvivesThemeAndSelectionTransitions() {
        val backgrounds = FocusFlowThemeOption.entries.map { it.colors.navigationBar } + listOf(Color.Black, Color.White, Color(0xFF777777))
        for (background in backgrounds) for (primary in backgrounds) {
            val indicator = navigationIndicatorColor(background, primary)
            assertTrue(contrastRatio(background, indicator) >= 1.5)
            for (step in 0..20) {
                val fill = lerp(background, indicator, step / 20f)
                assertTrue(contrastRatio(fill, navigationContentColor(fill)) >= 4.5)
            }
        }
    }
    @Test fun sixthColorDoesNotAlterTaskPalette() {
        val base = FocusFlowThemeOption.CUSTOM.colors
        assertEquals(focusFlowThemeSpec(FocusFlowThemeOption.CUSTOM, base).schedulePalette,
            focusFlowThemeSpec(FocusFlowThemeOption.CUSTOM, base.copy(navigationBar = Color.Black)).schedulePalette)
    }
    @Test fun previewsContainSixthColor() {
        val spec = focusFlowThemeSpec(FocusFlowThemeOption.APRICOT)
        assertEquals(6, previewColors(spec).size)
        assertEquals(spec.navigationBarColor, previewColors(spec).last())
    }
}
