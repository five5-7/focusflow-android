package com.sakata.focusflow

import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
    fun pageGradientRunsFromTintedTopToPlainBottom() {
        val stops = ThemeGradient.pageStops(apricot)
        assertEquals(3, stops.size)
        // 最后一站就是页面底色本身
        assertEquals(argb(apricot.background), argb(stops.last()))
        // 第一站确实带上了主题色（与底色可分辨），而且是往下越来越淡
        assertNotEquals(argb(apricot.background), argb(stops.first()))
        val topGap = AppearanceContrast.channelDistance(argb(stops[0]), argb(apricot.background))
        val midGap = AppearanceContrast.channelDistance(argb(stops[1]), argb(apricot.background))
        assertTrue("顶部应比中部更重（$topGap vs $midGap）", topGap > midGap)
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
    fun defaultAppearanceChangesNothing() {
        val spec = AppearanceSpec.DEFAULT
        assertTrue(!spec.backsPageWithSomething())
        // 默认下页面容器色仍由主题决定（这里只断言枚举语义，颜色本身在真机截图里比对）
        assertEquals(BackdropKind.THEME, spec.pageBackdrop)
        assertTrue(sameColorWithin(0xFF112233.toInt(), 0xFF112234.toInt(), 2))
        assertTrue(!sameColorWithin(0xFF112233.toInt(), 0xFF223344.toInt(), 2))
    }
}
