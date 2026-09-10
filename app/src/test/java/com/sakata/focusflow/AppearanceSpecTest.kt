package com.sakata.focusflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppearanceSpecTest {

    @Test
    fun defaultsMatchTheCurrentLook() {
        val spec = AppearanceSpec.DEFAULT
        assertEquals(BackdropKind.THEME, spec.pageBackdrop)
        assertEquals(BackdropKind.THEME, spec.timetableBackdrop)
        assertEquals(CardMaterial.TONAL, spec.cardMaterial)
        assertEquals(1f, spec.imageAlpha, 0.0001f)
        assertFalse(spec.hasPageImage)
        assertFalse(spec.hasTimetableImage)
        assertFalse(spec.timetableUsesColor)
        assertTrue(spec.extractedColors.isEmpty())
    }

    @Test
    fun unknownKeysFallBackToDefaults() {
        val spec = AppearanceSpec.fromKeys(
            pageBackdrop = "nonsense",
            pageImage = null,
            backdropOpacity = 100,
            gradientStrength = 100,
            cardMaterial = "unknown-material",
            timetableBackdrop = null,
            timetableColor = 0,
            timetableImage = null,
            timetableOpacity = 100,
            extracted = null
        )
        assertEquals(BackdropKind.THEME, spec.pageBackdrop)
        assertEquals(CardMaterial.TONAL, spec.cardMaterial)
        assertEquals(BackdropKind.THEME, spec.timetableBackdrop)
    }

    @Test
    fun opacityIsClampedAndGatesTheImage() {
        assertTrue(AppearanceSpec(pageBackdrop = BackdropKind.IMAGE, pageImage = "a.png", backdropOpacity = 60).hasPageImage)
        // 透明度拉到 0 等于"只用主题底色"，不再算有背景图
        assertFalse(AppearanceSpec(pageBackdrop = BackdropKind.IMAGE, pageImage = "a.png", backdropOpacity = 0).hasPageImage)
        // 选了图片但没导入：不算
        assertFalse(AppearanceSpec(pageBackdrop = BackdropKind.IMAGE, pageImage = "  ", backdropOpacity = 100).hasPageImage)
        // 越界读数夹回合法区间
        assertEquals(1f, AppearanceSpec(backdropOpacity = 250).imageAlpha, 0.0001f)
        assertEquals(0f, AppearanceSpec(backdropOpacity = -8).imageAlpha, 0.0001f)
    }

    @Test
    fun gradientStrengthDefaultsAndClamps() {
        assertEquals(100, AppearanceSpec.DEFAULT.gradientStrength)
        assertEquals(1f, AppearanceSpec.DEFAULT.gradientScale, 0.0001f)
        assertEquals(2f, AppearanceSpec(gradientStrength = 500).gradientScale, 0.0001f)
        assertEquals(0f, AppearanceSpec(gradientStrength = -20).gradientScale, 0.0001f)
        // 老装机（没有这个键）读出来必须是设计值，外观不变
        assertEquals(100, AppearanceSpec.fromKeys(null, null, 100, 100, null, null, 0, null, 100, null).gradientStrength)
    }

    @Test
    fun timetableColorOnlyCountsWhenChosen() {
        assertFalse(AppearanceSpec(timetableBackdrop = BackdropKind.COLOR, timetableColor = 0).timetableUsesColor)
        assertTrue(
            AppearanceSpec(timetableBackdrop = BackdropKind.COLOR, timetableColor = 0xFF112233.toInt()).timetableUsesColor
        )
        // 图片模式下选色不参与
        assertFalse(
            AppearanceSpec(timetableBackdrop = BackdropKind.IMAGE, timetableColor = 0xFF112233.toInt()).timetableUsesColor
        )
    }

    @Test
    fun extractedColorsRoundTrip() {
        val colors = listOf(0xFFA44F34L, 0xFF1B6FA8L, 0xFF3F8F5BL)
        val encoded = AppearanceSpec.encodeExtracted(colors)
        assertEquals(colors, AppearanceSpec.decodeExtracted(encoded))
        assertTrue(AppearanceSpec.decodeExtracted(null).isEmpty())
        assertTrue(AppearanceSpec.decodeExtracted("").isEmpty())
        // 坏数据只丢坏的那一段，不整段失败
        assertEquals(listOf(0xFFA44F34L), AppearanceSpec.decodeExtracted("FFA44F34;zz;12"))
    }
}
