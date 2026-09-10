package com.sakata.focusflow

import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 8.2.0「丰富的动画与外观效果」总开关（维护者口径）。
 *
 * 这个开关存在的理由：那些效果都是逐帧的绘制成本，低端机上会吃掉流畅度。
 * 关掉时只留最基本的渲染，但**用户选过的东西不能被丢掉**——所以这里同时验证
 * "渲染回落"与"数据保留"两件事。
 */
class RichEffectsTest {

    @Test
    fun defaultsToOnSoExistingInstallsDoNotChange() {
        assertTrue("默认必须是开，否则老装机升级后外观会被悄悄改掉", AppearanceSpec.DEFAULT.richEffects)
        // 读不到这个键（老装机）也必须等于"开"
        val legacy = AppearanceSpec.fromKeys(
            null, null, 100, 100, 0, false, 0, 0, null, null, 0, null, 100, null
        )
        assertTrue(legacy.richEffects)
    }

    @Test
    fun turningItOffFallsBackToPlainRendering() {
        val rich = AppearanceSpec(
            pageBackdrop = BackdropKind.GRADIENT,
            cardMaterial = CardMaterial.PAPER,
            timetableBackdrop = BackdropKind.IMAGE,
            timetableImage = "t.png"
        )
        // 开着：原样
        assertEquals(BackdropKind.GRADIENT, rich.effectivePageBackdrop)
        assertEquals(CardMaterial.PAPER, rich.effectiveCardMaterial)
        assertEquals(BackdropKind.IMAGE, rich.effectiveTimetableBackdrop)

        val plain = rich.copy(richEffects = false)
        // 关掉：渐变/底图回落成主题纯色，材质回落成默认卡片
        assertEquals(BackdropKind.THEME, plain.effectivePageBackdrop)
        assertEquals(CardMaterial.TONAL, plain.effectiveCardMaterial)
        assertEquals(BackdropKind.THEME, plain.effectiveTimetableBackdrop)
    }

    @Test
    fun fixedColourSurvivesBecauseItIsJustAFlatFill() {
        // 固定颜色只是一块纯色填充，几乎没有绘制成本，而且是用户明确选过的页面主色，
        // 关掉丰富效果时不该把它一起砍掉（那看起来像"设置丢了"）。
        val plain = AppearanceSpec(pageBackdrop = BackdropKind.COLOR, richEffects = false)
        assertEquals(BackdropKind.COLOR, plain.effectivePageBackdrop)
    }

    @Test
    fun imageBackdropsAreNotDrawnWhenOff() {
        val withImage = AppearanceSpec(
            pageBackdrop = BackdropKind.IMAGE,
            pageImage = "p.png",
            backdropOpacity = 80,
            richEffects = false
        )
        // 图片是逐帧 drawImage，属于要省掉的那一类
        assertFalse(withImage.hasPageImage)
        assertEquals(BackdropKind.THEME, withImage.effectivePageBackdrop)
    }

    @Test
    fun theChoiceItselfIsNeverDiscarded() {
        // "回落"只发生在渲染层：原始偏好必须原样留着，否则再打开开关就回不来了。
        val off = AppearanceSpec(
            pageBackdrop = BackdropKind.GRADIENT,
            cardMaterial = CardMaterial.PAPER,
            gradientStrength = 180,
            richEffects = false
        )
        assertEquals(BackdropKind.GRADIENT, off.pageBackdrop)
        assertEquals(CardMaterial.PAPER, off.cardMaterial)
        assertEquals(180, off.gradientStrength)
        // 再打开 → 立刻恢复成原来那套
        assertEquals(BackdropKind.GRADIENT, off.copy(richEffects = true).effectivePageBackdrop)
    }

    @Test
    fun materialBrushIsNullOnlyForTonal() {
        val scheme = lightColorScheme()
        val base = scheme.surfaceContainerLow
        assertNull("默认材质不叠任何东西", materialBrush(CardMaterial.TONAL, base, scheme))
        // 另外三档都必须真的产出一层，否则就是"设置了却没变化"的假开关
        for (material in listOf(CardMaterial.GRADIENT, CardMaterial.SOFT, CardMaterial.PAPER)) {
            assertTrue(
                "$material 必须产出可见的一层",
                materialBrush(material, base, scheme) != null
            )
        }
    }

    @Test
    fun softLightReallyDiffersFromThePlainCard() {
        val scheme = lightColorScheme()
        val base = scheme.surfaceContainerLow
        // 柔光必须与"默认材质"真的不同 —— 这正是原先柔光完全看不出来的原因
        // （那时 SOFT 与 PAPER 都 `-> null`，只挂了一份同样的阴影，两者渲染完全相同）。
        val stops = softLightStops(base, scheme.onSurface)
        assertEquals("柔光是三站：顶亮 → 底色 → 底沉", 3, stops.size)
        assertTrue("顶站要比中间亮", stops[0].luminance() > stops[1].luminance())
        assertTrue("底站要比中间沉", stops[2].luminance() < stops[1].luminance())
        assertEquals("中间站就是底色本身", base, stops[1])
    }
}
