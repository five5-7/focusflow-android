package com.sakata.focusflow

import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

class SurfaceMaterialTest {

    private val apricot = lightColorScheme(
        primary = Color(0xFFA44F34),
        background = Color(0xFFF2ECE8),
        surfaceContainerLow = Color(0xFFFFFBF9)
    )

    private fun argb(color: Color): Int {
        fun c(v: Float) = (v * 255f).roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (c(color.red) shl 16) or (c(color.green) shl 8) or c(color.blue)
    }

    @Test
    fun blendEndpointsAndMiddle() {
        val base = Color(0xFF000000)
        val overlay = Color(0xFFFFFFFF)
        assertEquals(0xFF000000.toInt(), argb(blendSrgb(base, overlay, 0f)))
        assertEquals(0xFFFFFFFF.toInt(), argb(blendSrgb(base, overlay, 1f)))
        assertEquals(0xFF808080.toInt(), argb(blendSrgb(base, overlay, 0.5f)))
        // 越界读数夹回合法区间，不抛错
        assertEquals(0xFF000000.toInt(), argb(blendSrgb(base, overlay, -3f)))
        assertEquals(0xFFFFFFFF.toInt(), argb(blendSrgb(base, overlay, 7f)))
    }

    @Test
    fun pageGradientIsActuallyPerceptible() {
        val stops = ThemeGradient.pageStops(apricot)
        assertEquals(3, stops.size)
        val top = argb(stops[0])
        val middle = argb(stops[1])
        val bottom = argb(stops[2])
        // 中站就是页面底色本身
        assertEquals(argb(apricot.background), middle)
        // 顶部更亮、底部更深：深色文字在浅底上对比度最高，方向不能反
        assertTrue("顶部应比底色亮", AppearanceContrast.luminance(top) > AppearanceContrast.luminance(middle))
        assertTrue("底部应比底色深", AppearanceContrast.luminance(bottom) < AppearanceContrast.luminance(middle))
        // 幅度要够大，否则会被看成"背景整体变深了一档"而不是渐变（维护者真机反馈过）
        val topGap = AppearanceContrast.channelDistance(top, middle)
        val bottomGap = AppearanceContrast.channelDistance(bottom, middle)
        assertTrue("顶部与底色至少要差 8 级，实际 $topGap", topGap >= 8)
        assertTrue("底部与底色至少要差 8 级，实际 $bottomGap", bottomGap >= 8)
    }

    @Test
    fun pageGradientNeverHurtsBodyTextContrast() {
        val stops = ThemeGradient.pageStops(apricot)
        val text = 0xFF241D1A.toInt()
        val plain = AppearanceContrast.ratio(text, argb(apricot.background))
        // 顶部更亮 → 对比度只会更好
        assertTrue(AppearanceContrast.ratio(text, argb(stops[0])) >= plain)
        // 底部更深 → 对比度会略降，但必须仍然远超 AAA（7:1）
        for (stop in stops) {
            val ratio = AppearanceContrast.ratio(text, argb(stop))
            assertTrue("渐变任意一点的正文对比度都要 ≥ 10:1，实际 $ratio", ratio >= 10f)
        }
    }

    @Test
    fun cardGradientStaysCloseToCardColor() {
        val stops = ThemeGradient.cardStops(apricot)
        assertEquals(2, stops.size)
        for (stop in stops) {
            val distance = AppearanceContrast.channelDistance(argb(stop), argb(apricot.surfaceContainerLow))
            assertTrue("卡片渐变必须很轻，实际通道差 $distance", distance in 1..24)
        }
    }

    @Test
    fun scrimGrowsWithImageOpacity() {
        assertEquals(0.34f, scrimAlpha(0f), 0.001f)
        assertEquals(0.78f, scrimAlpha(1f), 0.001f)
        assertTrue(scrimAlpha(0.5f) > scrimAlpha(0.2f))
        // 越界读数不炸
        assertEquals(0.34f, scrimAlpha(-1f), 0.001f)
        assertEquals(0.78f, scrimAlpha(9f), 0.001f)
    }

    @Test
    fun scrimKeepsDarkTextReadableOverADarkPhoto() {
        // 最坏情况：纯黑照片 + 浅色主题的遮罩 → 深色正文必须仍然过 AA
        val scrimmed = blendSrgb(Color(0xFF000000), apricot.background, scrimAlpha(1f))
        val text = 0xFF241D1A.toInt()
        val ratio = AppearanceContrast.ratio(text, argb(scrimmed))
        assertTrue("遮罩后对比度应达标，实际 $ratio", ratio >= AppearanceContrast.AA_BODY)
    }

    @Test
    fun coverRectCropsTheLongerSideAndKeepsAspect() {
        // 宽图放进竖屏：裁宽度、居中留边
        val wide = coverSourceRect(2000, 1000, 1080f, 2000f)
        assertEquals(0, wide[1])
        assertEquals(1000, wide[3])
        assertTrue("宽度应被裁", wide[2] < 2000)
        assertEquals(730, wide[0])
        // 裁完的比例要贴住目标比例
        val targetRatio = 1080f / 2000f
        assertEquals(targetRatio, wide[2].toFloat() / wide[3], 0.01f)

        // 竖图放进竖屏：裁高度
        val tall = coverSourceRect(1000, 3000, 1080f, 2000f)
        assertEquals(0, tall[0])
        assertEquals(1000, tall[2])
        assertTrue("高度应被裁", tall[3] < 3000)
        assertEquals(targetRatio, tall[2].toFloat() / tall[3], 0.01f)
    }

    @Test
    fun coverRectHandlesDegenerateInput() {
        assertEquals(listOf(0, 0, 0, 0), coverSourceRect(0, 0, 100f, 100f).toList())
        val same = coverSourceRect(500, 1000, 500f, 1000f)
        assertEquals(0, same[0])
        assertEquals(0, same[1])
        assertEquals(500, same[2])
        assertEquals(1000, same[3])
    }

    @Test
    fun gradientStrengthScalesTheAmplitude() {
        val plain = argb(apricot.background)
        // 0% = 纯色：三站全是底色
        for (stop in ThemeGradient.pageStops(apricot, 0f)) {
            assertEquals(plain, argb(stop))
        }
        // 100% 就是设计值：与不带参数的调用完全一致
        assertEquals(ThemeGradient.pageStops(apricot), ThemeGradient.pageStops(apricot, 1f))
        // 强度越大，上下两端的幅度越大
        fun topGap(s: Float) = AppearanceContrast.channelDistance(argb(ThemeGradient.pageStops(apricot, s)[0]), plain)
        fun bottomGap(s: Float) = AppearanceContrast.channelDistance(argb(ThemeGradient.pageStops(apricot, s)[2]), plain)
        assertTrue(topGap(2f) > topGap(1f))
        assertTrue(topGap(1f) > topGap(0.5f))
        assertTrue(bottomGap(2f) > bottomGap(1f))
        assertTrue(bottomGap(1f) > bottomGap(0.5f))
        // 越界读数夹回 0..2，不抛错
        assertEquals(ThemeGradient.pageStops(apricot, 0f), ThemeGradient.pageStops(apricot, -5f))
        assertEquals(ThemeGradient.pageStops(apricot, 2f), ThemeGradient.pageStops(apricot, 9f))
    }

    @Test
    fun strongestGradientStillKeepsBodyTextAtAaaLevel() {
        val text = 0xFF241D1A.toInt()
        for (stop in ThemeGradient.pageStops(apricot, 2f)) {
            val ratio = AppearanceContrast.ratio(text, argb(stop))
            assertTrue("最强档也要 ≥7:1（AAA），实际 $ratio", ratio >= 7f)
        }
    }

    @Test
    fun timetableBasePresetsAreAllReadable() {
        assertEquals(8, TIMETABLE_BASE_PRESETS.size)
        for (preset in TIMETABLE_BASE_PRESETS) {
            assertTrue("预设底色必须压得住格线小字：$preset", timetableBaseIsReadable(preset))
            // 也必须是"很浅的纸色"，不能深到抢课程块
            assertTrue("预设底色要够浅，实际亮度 ${AppearanceContrast.luminance(preset)}", AppearanceContrast.luminance(preset) > 0.75f)
        }
        // 深色会如实被判为不达标（UI 据此提示）
        assertTrue(!timetableBaseIsReadable(0xFF3A3A3A.toInt()))
    }

    @Test
    fun pageBasePresetsAreAllReadable() {
        assertEquals(8, PAGE_BASE_PRESETS.size)
        for (preset in PAGE_BASE_PRESETS) {
            assertTrue("页面预设底色要压得住正文：$preset", timetableBaseIsReadable(preset))
            assertTrue(AppearanceContrast.luminance(preset) > 0.7f)
        }
    }

    @Test
    fun darkModeGradientStaysDarkAndReadable() {
        val dark = focusFlowThemeSpec(FocusFlowThemeOption.APRICOT, darkMode = true).colorScheme
        val stops = ThemeGradient.pageStops(dark, 1f)
        for (stop in stops) {
            assertTrue("深色模式下渐变各点都必须是深色，实际亮度 ${stop.luminance()}", stop.luminance() < 0.35f)
            val ratio = AppearanceContrast.ratio(dark.onBackground.argbInt(), argb(stop))
            assertTrue("深色模式正文在渐变上要达标，实际 $ratio", ratio >= 7f)
        }
        // 方向仍然是"上亮下深"（那束光还在，只是幅度收得很小）
        assertTrue(stops[0].luminance() > stops[2].luminance())
        // 最强档也不能翻车
        for (stop in ThemeGradient.pageStops(dark, 2f)) {
            assertTrue(stop.luminance() < 0.4f)
            assertTrue(AppearanceContrast.ratio(dark.onBackground.argbInt(), argb(stop)) >= 7f)
        }
    }

    @Test
    fun customLightColoursAreAdaptedInDarkMode() {
        val dark = focusFlowThemeSpec(FocusFlowThemeOption.APRICOT, darkMode = true).colorScheme
        val (top, bottom) = ThemeGradient.PAGE_GRADIENT_PAIRS[1] // 雾蓝 → 浅蓝
        val stops = ThemeGradient.pageStops(dark, 1f, top, bottom)
        for (stop in stops) {
            assertTrue("自选浅色在深色模式下必须被压暗，实际亮度 ${stop.luminance()}", stop.luminance() < 0.4f)
            assertTrue(AppearanceContrast.ratio(dark.onBackground.argbInt(), argb(stop)) >= 7f)
        }
        // 浅色模式仍然原样使用用户选的颜色
        val light = focusFlowThemeSpec(FocusFlowThemeOption.APRICOT).colorScheme
        assertEquals(top, argb(ThemeGradient.pageStops(light, 1f, top, bottom)[0]))
    }

    @Test
    fun fixedPageColourIsAdaptedInDarkModeToo() {
        val dark = focusFlowThemeSpec(FocusFlowThemeOption.APRICOT, darkMode = true).colorScheme
        val preset = PAGE_BASE_PRESETS[0]
        val adapted = adaptBackdropColor(dark, androidx.compose.ui.graphics.Color(preset))
        assertTrue("深色模式下固定背景色也要压到深色", adapted.luminance() < 0.4f)
        // 浅色模式不变
        val light = focusFlowThemeSpec(FocusFlowThemeOption.APRICOT).colorScheme
        assertEquals(preset, adaptBackdropColor(light, androidx.compose.ui.graphics.Color(preset)).argbInt())
    }

    /**
     * 替换被删掉的 windowStops 系列断言：`page()` 现在只负责"整条渐变正好一屏"，
     * 颜色必须完全等于 `pageStops` —— 跟随内容那条路由滚动内容自己按内容高度铺
     * （`ScrollableWithBar`），不经过这里。
     *
     * 不能去读 Brush 内部的 colorStops（`Brush.VerticalGradient.colorStops` 在这个
     * Compose 版本里不可见），所以改成对 `pageStops` 本身做等价断言：站点数、
     * 站点取值、以及强度/自选端色的传递。这正是 `page()` 的全部职责。
     */
    @Test
    fun pageStopsAreTheWholePageGradient() {
        val stops = ThemeGradient.pageStops(apricot)
        assertEquals("页面渐变是三站（顶亮→底色→底深）", 3, stops.size)
        // 方向：顶亮 → 中为底色 → 底深
        assertTrue(
            "顶端应比中点亮、中点应比底端亮",
            stops[0].luminance() > stops[1].luminance() && stops[1].luminance() > stops[2].luminance()
        )
        // 强度 0 = 纯色（三站同色），这是"调到 0% 等于纯色"的实现依据
        val flat = ThemeGradient.pageStops(apricot, strength = 0f)
        assertEquals(argb(flat[0]), argb(flat[1]))
        assertEquals(argb(flat[1]), argb(flat[2]))
        // 自选端色必须原样落在首尾两站上
        val custom = ThemeGradient.pageStops(apricot, 1f, top = 0xFF102030.toInt(), bottom = 0xFF405060.toInt())
        assertEquals(0xFF102030.toInt(), argb(custom[0]))
        assertEquals(0xFF405060.toInt(), argb(custom[2]))
    }

    @Test
    fun defaultAppearanceChangesNothing() {
        val spec = AppearanceSpec.DEFAULT
        assertTrue(!spec.backsPageWithSomething())
        // 默认下页面容器色仍由主题决定（这里只断言枚举语义，颜色本身在真机截图里比对）
        assertEquals(BackdropKind.THEME, spec.pageBackdrop)
        assertTrue(sameColorWithin(0xFF112233.toInt(), 0xFF112234.toInt(), 2))
        assertTrue(!sameColorWithin(0xFF112233.toInt(), 0xFF223344.toInt(), 2))
    }
}
