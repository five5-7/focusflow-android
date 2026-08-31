package com.sakata.focusflow

import androidx.compose.ui.graphics.Color
import org.json.JSONObject

/** Existing keys/packed Color values are retained; legacy ARGB numbers remain readable. */
internal object ThemeColorsCodec {
    fun decode(value: JSONObject): FocusFlowThemeColors {
        fun color(key: String, fallback: Color? = null): Color {
            if (!value.has(key) || value.isNull(key)) return requireNotNull(fallback)
            val stored = value.getLong(key)
            // Compose's packed sRGB value lives in the high 32 bits, not an ARGB Long.
            return if (stored in Int.MIN_VALUE.toLong()..0xFFFFFFFFL) Color(stored)
            else Color(stored.toULong())
        }
        val primary = color("primaryAction")
        val neutral = color("neutral")
        return FocusFlowThemeColors(
            primaryAction = primary,
            secondary = color("secondary", Color(0xFF5C4B9A)),
            accent = color("accent"), schedule = color("schedule"), neutral = neutral,
            warning = color("warning"), text = color("text", Color(0xFF182124)),
            navigationBar = color("navigationBar", defaultNavigationColor(neutral, primary))
        )
    }

    fun encode(colors: FocusFlowThemeColors): JSONObject = JSONObject().apply {
        put("primaryAction", colors.primaryAction.value.toLong())
        put("secondary", colors.secondary.value.toLong())
        put("accent", colors.accent.value.toLong())
        put("schedule", colors.schedule.value.toLong())
        put("neutral", colors.neutral.value.toLong())
        put("warning", colors.warning.value.toLong())
        put("text", colors.text.value.toLong())
        put("navigationBar", colors.navigationBar.value.toLong())
    }
}
