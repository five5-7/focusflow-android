package com.sakata.focusflow

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import org.junit.Assert.*
import org.junit.Test

class NavigationThemeTest {
    @Test fun eachBuiltInHasItsOwnNavAndCardColors() {
        for (dark in listOf(false, true)) {
            val colors = FocusFlowThemeOption.builtInEntries().map { focusFlowThemeSpec(it, darkMode = dark).navigationBarColor }
            assertEquals(4, colors.distinct().size)
        }
        val cards = FocusFlowThemeOption.builtInEntries().map { focusFlowThemeSpec(it).colorScheme.surfaceContainerLow }
        assertEquals(4, cards.distinct().size)
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
