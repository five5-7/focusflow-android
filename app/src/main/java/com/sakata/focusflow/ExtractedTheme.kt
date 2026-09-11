package com.sakata.focusflow

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.math.roundToInt

/**
 * 8.2.0「系统自抽主题色」的第二步：把 [ExtractedPalette] 变成一套**可用的完整配色**。
 *
 * 抽取只给出三个"画面里真实存在的颜色"，直接拿来当主题会踩两个坑：
 * 1. 中性壳（背景/卡片/分隔）没有着落 —— 这里由主色派生一层很浅的中性，保住"轻"的观感；
 * 2. 正文颜色可能正好落在中灰上，对比度不达标 —— 这里有一道 [ensureReadable] 兜底，
 *    不够就把文字往深里压，直到过 WCAG 目标值（默认 5.0，高于 AA 正文的 4.5）。
 *
 * 纯函数：同一个 [ExtractedPalette] 永远得到同一套配色，可单测、可回归。
 */
internal object ExtractedTheme {

    /** 正文目标对比度：略高于 AA 正文阈值，给渐变/图片背景留余量。 */
    const val TEXT_TARGET = 5.0f

    /**
     * 派生配色。[dark] 为 true 时文字走浅色（深色模式）。
     * [base] 只用来继承警示色等与外观无关的语义槽位。
     */
    fun derive(
        palette: ExtractedPalette,
        dark: Boolean = false,
        base: FocusFlowThemeColors = FocusFlowThemeOption.APRICOT.colors
    ): FocusFlowThemeColors {
        val primary = Color(palette.primary)
        val secondary = Color(palette.secondary)
        val accent = Color(palette.tertiary)
        // 中性壳：白里掺一点主色，很浅但能看出"同一家人"。
        val neutral = blendSrgb(Color.White, primary, 0.06f)
        val text = if (dark) {
            ensureReadable(blendSrgb(Color(0xFFF2F4F6), primary, 0.10f), Color(0xFF121417), TEXT_TARGET)
        } else {
            ensureReadable(blendSrgb(Color(0xFF16181A), primary, 0.20f), neutral, TEXT_TARGET)
        }
        return FocusFlowThemeColors(
            primaryAction = primary,
            secondary = secondary,
            accent = accent,
            // 日程色沿用"日程"槽位语义，用抽取到的强调色，保证课表块不跳。
            schedule = accent,
            neutral = neutral,
            warning = base.warning,
            text = text,
            navigationBar = defaultNavigationColor(neutral, primary)
        )
    }

    /**
     * 保证 [text] 压得住 [background]：不够就按步往深（浅色主题）或往浅（深色主题）走，最多 12 步。
     * 深色主题用 `luminance` 判断方向，避免把浅色文字压成黑。
     */
    fun ensureReadable(text: Color, background: Color, target: Float = TEXT_TARGET): Color {
        var current = text
        val goDarker = background.luminance() > 0.5f
        repeat(12) {
            val ratio = AppearanceContrast.ratio(current.argbInt(), background.argbInt())
            if (ratio >= target) return current
            current = blendSrgb(current, if (goDarker) Color.Black else Color.White, 0.18f)
        }
        return current
    }

    /** 抽取结果是否值得应用（置信度太低就别动用户的主题）。 */
    fun worthApplying(palette: ExtractedPalette?, minConfidence: Float = 0.18f): Boolean =
        palette != null && palette.confidence >= minConfidence && PaletteExtractor.isColorful(palette.primary)
}

/** Compose [Color] → 0xAARRGGBB，供对比度计算使用（避免依赖 Android 的 `toArgb`）。 */
internal fun Color.argbInt(): Int {
    fun channel(value: Float) = (value * 255f).roundToInt().coerceIn(0, 255)
    return (0xFF shl 24) or (channel(red) shl 16) or (channel(green) shl 8) or channel(blue)
}
