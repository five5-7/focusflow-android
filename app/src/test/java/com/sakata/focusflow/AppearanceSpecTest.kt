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
            pageColor = 0,
            gradientFollowsContent = false,
            gradientTop = 0,
            gradientBottom = 0,
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
        // 上限 = GRADIENT_STRENGTH_MAX（2026-09-10 由 200 调到 300）：越界读数夹到上限
        assertEquals(GRADIENT_STRENGTH_MAX / 100f, AppearanceSpec(gradientStrength = 500).gradientScale, 0.0001f)
        assertEquals(0f, AppearanceSpec(gradientStrength = -20).gradientScale, 0.0001f)
        // 老装机（没有这个键）读出来必须是设计值，外观不变
        assertEquals(100, AppearanceSpec.fromKeys(null, null, 100, 100, 0, false, 0, 0, null, null, 0, null, 100, null).gradientStrength)
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

    /** 应用预设时的降级：预设里记的图片被删了，就退回跟随主题，而不是套一个画不出来的模式。 */
    @Test
    fun missingImagesDegradeToThemeBackdrop() {
        val spec = AppearanceSpec(
            pageBackdrop = BackdropKind.IMAGE,
            pageImage = "gone.jpg",
            backdropOpacity = 70,
            cardMaterial = CardMaterial.SOFT,
            timetableBackdrop = BackdropKind.IMAGE,
            timetableImage = "gone2.jpg"
        )
        val degraded = spec.withExistingImages { false }
        assertEquals(BackdropKind.THEME, degraded.pageBackdrop)
        assertEquals("", degraded.pageImage)
        assertEquals(BackdropKind.THEME, degraded.timetableBackdrop)
        assertEquals("", degraded.timetableImage)
        // 只降级"图片"这一件事：材质、不透明度等其余设置原样保留
        assertEquals(CardMaterial.SOFT, degraded.cardMaterial)
        assertEquals(70, degraded.backdropOpacity)

        // 文件都在：一个字段都不许动
        assertEquals(spec, spec.withExistingImages { true })
        // 不是图片模式时也不该被影响（哪怕文件名是空的）
        val plain = AppearanceSpec(pageBackdrop = BackdropKind.GRADIENT, gradientStrength = 150)
        assertEquals(plain, plain.withExistingImages { false })
    }

    /** 预设列表里那一行"这套预设带了什么"要说人话，且区分"仅配色"。 */
    @Test
    fun summaryDescribesTheLook() {
        assertEquals("标准底色", AppearanceSpec.DEFAULT.summary())
        assertEquals(
            "渐变 120%·自选色·卡片柔光·课表底色",
            AppearanceSpec(
                pageBackdrop = BackdropKind.GRADIENT,
                gradientStrength = 120,
                gradientTop = 0xFF112233.toInt(),
                gradientBottom = 0xFFEEDDCC.toInt(),
                cardMaterial = CardMaterial.SOFT,
                timetableBackdrop = BackdropKind.COLOR
            ).summary()
        )
        assertEquals("图片底 40%", AppearanceSpec(pageBackdrop = BackdropKind.IMAGE, backdropOpacity = 40).summary())
        assertTrue(AppearanceSpec(gradientFollowsContent = true, pageBackdrop = BackdropKind.GRADIENT).summary().contains("跟随内容"))
    }

    /** 真机上开关状态读不准，靠这行文字判定"当前到底怎么铺"。 */
    @Test
    fun gradientSpanLabelStatesTheActiveMode() {
        assertTrue(AppearanceSpec.DEFAULT.gradientSpanLabel().startsWith("固定一屏"))
        assertTrue(
            AppearanceSpec(gradientFollowsContent = true).gradientSpanLabel().startsWith("跟随内容")
        )
    }
}
