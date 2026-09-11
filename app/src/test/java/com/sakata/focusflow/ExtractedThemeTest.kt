package com.sakata.focusflow

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtractedThemeTest {

    private val blue = ExtractedPalette(
        primary = 0xFF2E6FB7.toInt(),
        secondary = 0xFF5A86C4.toInt(),
        tertiary = 0xFFD97B27.toInt(),
        confidence = 0.72f
    )

    private val magenta = ExtractedPalette(
        primary = 0xFFB7266E.toInt(),
        secondary = 0xFFC4588F.toInt(),
        tertiary = 0xFF2E9E86.toInt(),
        confidence = 0.55f
    )

    @Test
    fun derivedThemeKeepsTheExtractedPrimary() {
        val theme = ExtractedTheme.derive(blue)
        assertEquals(blue.primary, theme.primaryAction.argbInt())
        assertEquals(blue.secondary, theme.secondary.argbInt())
        assertEquals(blue.tertiary, theme.accent.argbInt())
        // 日程色跟随强调色，课表块不会突然换家族
        assertEquals(blue.tertiary, theme.schedule.argbInt())
    }

    @Test
    fun derivedNeutralIsLightAndTinted() {
        val theme = ExtractedTheme.derive(blue)
        val neutral = theme.neutral.argbInt()
        // 够浅：作为页面底色必须明显亮于中灰
        assertTrue("中性壳应很浅，实际 ${AppearanceContrast.luminance(neutral)}", AppearanceContrast.luminance(neutral) > 0.75f)
        // 带一点主色：与纯白有可分辨的差别，但不至于变成彩色底
        val distance = AppearanceContrast.channelDistance(neutral, 0xFFFFFFFF.toInt())
        assertTrue("应带一点主色，实际通道差 $distance", distance in 3..40)
    }

    @Test
    fun bodyTextAlwaysPassesTheTarget() {
        for (palette in listOf(blue, magenta)) {
            val theme = ExtractedTheme.derive(palette)
            val ratio = AppearanceContrast.ratio(theme.text.argbInt(), theme.neutral.argbInt())
            assertTrue("正文对比度应 >= ${ExtractedTheme.TEXT_TARGET}，实际 $ratio", ratio >= ExtractedTheme.TEXT_TARGET)
        }
    }

    @Test
    fun darkVariantIsReadableToo() {
        val theme = ExtractedTheme.derive(blue, dark = true)
        val ratio = AppearanceContrast.ratio(theme.text.argbInt(), 0xFF121417.toInt())
        assertTrue("深色模式正文同样要达标，实际 $ratio", ratio >= ExtractedTheme.TEXT_TARGET)
        // 深色模式下文字必须比背景亮
        assertTrue(theme.text.luminance() > Color(0xFF121417).luminance())
    }

    @Test
    fun ensureReadablePushesTowardTheSafeDirection() {
        // 浅底 + 中灰字：应被压深到达标
        val fixed = ExtractedTheme.ensureReadable(Color(0xFF808080), Color(0xFFF5F5F5))
        assertTrue(AppearanceContrast.ratio(fixed.argbInt(), 0xFFF5F5F5.toInt()) >= ExtractedTheme.TEXT_TARGET)
        assertTrue("应比原来更深", fixed.luminance() < Color(0xFF808080).luminance())

        // 深底 + 中灰字：应被提亮到达标
        val lifted = ExtractedTheme.ensureReadable(Color(0xFF808080), Color(0xFF141414))
        assertTrue(AppearanceContrast.ratio(lifted.argbInt(), 0xFF141414.toInt()) >= ExtractedTheme.TEXT_TARGET)
        assertTrue("应比原来更亮", lifted.luminance() > Color(0xFF808080).luminance())

        // 已经达标就一个字节都不动
        val untouched = ExtractedTheme.ensureReadable(Color(0xFF202020), Color(0xFFF5F5F5))
        assertEquals(0xFF202020.toInt(), untouched.argbInt())
    }

    @Test
    fun derivationIsDeterministic() {
        assertEquals(ExtractedTheme.derive(blue).primaryAction, ExtractedTheme.derive(blue).primaryAction)
        assertEquals(ExtractedTheme.derive(blue).text.argbInt(), ExtractedTheme.derive(blue).text.argbInt())
    }

    @Test
    fun lowConfidenceOrGreyPalettesAreNotWorthApplying() {
        assertFalse(ExtractedTheme.worthApplying(null))
        assertFalse(ExtractedTheme.worthApplying(blue.copy(confidence = 0.05f)))
        // 灰扑扑的主色（不够"有颜色"）也不该覆盖用户主题
        assertFalse(ExtractedTheme.worthApplying(blue.copy(primary = 0xFF8A8A8A.toInt())))
        assertTrue(ExtractedTheme.worthApplying(blue))
        assertTrue(ExtractedTheme.worthApplying(magenta))
    }
}
