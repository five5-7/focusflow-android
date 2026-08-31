package com.sakata.focusflow

import androidx.compose.ui.graphics.Color
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ThemeColorsCodecTest {
    @Test fun packedSixColorsRoundTripWithoutLosingBits() {
        FocusFlowThemeOption.entries.forEach { option ->
            val colors = option.colors.copy(navigationBar = Color(0xFFBDCAFE))
            assertEquals(colors, ThemeColorsCodec.decode(JSONObject(ThemeColorsCodec.encode(colors).toString())))
        }
    }
    @Test fun fiveColorUpgradePreservesEveryExistingColor() {
        val colors = FocusFlowThemeOption.APRICOT.colors
        val old = ThemeColorsCodec.encode(colors).apply { remove("navigationBar") }
        assertEquals(colors, ThemeColorsCodec.decode(old))
    }
    @Test fun legacyArgbNumbersAreAlsoSupported() {
        val old = JSONObject("""{"primaryAction":4283461428,"accent":4283407738,"schedule":4283530149,"neutral":4294965492,"warning":4289930782}""")
        val decoded = ThemeColorsCodec.decode(old)
        assertEquals(Color(4283461428L), decoded.primaryAction)
        assertEquals(Color(4294965492L), decoded.neutral)
        assertEquals(defaultNavigationColor(decoded.neutral, decoded.primaryAction), decoded.navigationBar)
        assertEquals(Color(0xFF5C4B9A), decoded.secondary)
        assertEquals(Color(0xFF182124), decoded.text)
    }
    @Test fun presetColorsUseSameCodecAndKeepIndependentSixthColor() {
        val original = FocusFlowThemeOption.MINT.colors.copy(navigationBar = Color.Black)
        val preset = JSONObject().put("name", "我的主题").put("colors", ThemeColorsCodec.encode(original))
        assertEquals(original, ThemeColorsCodec.decode(JSONObject(preset.toString()).getJSONObject("colors")))
    }
    @Test fun nullSixthColorFallsBackWithoutResettingTheme() {
        val colors = FocusFlowThemeOption.TWILIGHT.colors
        val value = ThemeColorsCodec.encode(colors).put("navigationBar", JSONObject.NULL)
        assertEquals(colors, ThemeColorsCodec.decode(value))
    }
    @Test fun editingSixthSlotDoesNotChangeOtherColors() {
        val base = FocusFlowThemeOption.OCEAN.colors
        assertEquals(base.copy(navigationBar = Color.White), ThemeSlot.NAVIGATION.set(base, Color.White))
        assertEquals(6, ThemeSlot.entries.size)
    }
}
