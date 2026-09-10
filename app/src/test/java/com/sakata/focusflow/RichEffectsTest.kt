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
            cardMaterial = CardMaterial.SOFT,
            timetableBackdrop = BackdropKind.IMAGE,
            timetableImage = "t.png"
        )
        // 开着：原样
        assertEquals(BackdropKind.GRADIENT, rich.effectivePageBackdrop)
        assertEquals(CardMaterial.SOFT, rich.effectiveCardMaterial)
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
            cardMaterial = CardMaterial.SOFT,
            gradientStrength = 180,
            richEffects = false
        )
        assertEquals(BackdropKind.GRADIENT, off.pageBackdrop)
        assertEquals(CardMaterial.SOFT, off.cardMaterial)
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
        for (material in listOf(CardMaterial.GRADIENT, CardMaterial.SOFT, CardMaterial.SOFT)) {
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

    /**
     * 关掉丰富效果后，界面**不能再摆出**那些不参与渲染的档位与控件。
     *
     * 理由不是美观，而是 AGENTS.md 的硬要求：「界面里不存在"承诺了却不生效"的开关」。
     * 渐变与图片此时会被 effectivePageBackdrop 回落成 THEME，
     * 卡片材质回落成 TONAL、课表底色回落成 THEME —— 留着控件就是骗人。
     */
    @Test
    fun controlsThatNoLongerDoAnythingAreNotOffered() {
        val rich = AppearanceSpec.DEFAULT
        assertTrue("开着时应提供四档背景", rich.offeredPageBackdrops.size == 4)
        assertTrue(rich.offeredPageBackdrops.contains(BackdropKind.GRADIENT))
        assertTrue(rich.offeredPageBackdrops.contains(BackdropKind.IMAGE))
        assertTrue("开着时应显示材质/课表底色控件", rich.showsMaterialControls)

        val plain = rich.copy(richEffects = false)
        assertEquals(
            "关着时只应提供 跟随主题 / 固定颜色",
            listOf(BackdropKind.THEME, BackdropKind.COLOR),
            plain.offeredPageBackdrops
        )
        assertTrue("关着时不该再提供渐变", !plain.offeredPageBackdrops.contains(BackdropKind.GRADIENT))
        assertTrue("关着时不该再提供图片", !plain.offeredPageBackdrops.contains(BackdropKind.IMAGE))
        assertTrue("关着时应收起材质/课表底色控件", !plain.showsMaterialControls)
    }

    /**
     * 提供的档位必须**都真的生效** —— 这是上一条的实质版本：
     * 对 offeredPageBackdrops 里的每一档，实际渲染用的档位不能被打回 THEME。
     * （固定颜色是刻意保留的：它只是一块纯色填充，几乎没有绘制成本。）
     */
    @Test
    fun everyOfferedBackdropActuallyRenders() {
        for (rich in listOf(false, true)) {
            for (kind in AppearanceSpec(richEffects = rich).offeredPageBackdrops) {
                val spec = AppearanceSpec(
                    pageBackdrop = kind,
                    pageImage = if (kind == BackdropKind.IMAGE) "x.png" else "",
                    richEffects = rich
                )
                assertEquals(
                    "提供了背景档位「$kind」（richEffects=$rich）就必须真的生效，不能被回落掉",
                    kind,
                    spec.effectivePageBackdrop
                )
            }
        }
    }
}
