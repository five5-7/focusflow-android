package com.sakata.focusflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppearanceContrastTest {

    private val black = 0xFF000000.toInt()
    private val white = 0xFFFFFFFF.toInt()

    @Test
    fun extremesMatchWcag() {
        assertEquals(21f, AppearanceContrast.ratio(black, white), 0.001f)
        assertEquals(1f, AppearanceContrast.ratio(white, white), 0.001f)
    }

    @Test
    fun currentThemeBodyTextStillPasses() {
        // 8.1.1 的四套主题实测值：页面底色压深后正文仍远高于 AA
        val cases = listOf(
            0xFF182124.toInt() to 0xFFEBEEEF.toInt(),
            0xFF1A211E.toInt() to 0xFFEAEEEB.toInt(),
            0xFF241D1A.toInt() to 0xFFF2ECE8.toInt(),
            0xFF211E24.toInt() to 0xFFEEEBEF.toInt()
        )
        for ((text, page) in cases) {
            val ratio = AppearanceContrast.ratio(text, page)
            assertTrue("$ratio 应 >= 13", ratio >= 13f)
            assertTrue(AppearanceContrast.passes(text, page))
        }
    }

    @Test
    fun blendInterpolatesBetweenColors() {
        assertEquals(white, AppearanceContrast.blend(black, white, 1f))
        assertEquals(black, AppearanceContrast.blend(black, white, 0f))
        assertEquals(0xFF808080.toInt(), AppearanceContrast.blend(black, white, 0.5f))
    }

    @Test
    fun scrimIsZeroWhenAlreadyReadable() {
        assertEquals(0f, AppearanceContrast.scrimAlphaFor(white, black), 0.001f)
    }

    @Test
    fun scrimGrowsUntilTextPasses() {
        // 中性亮底 + 深色文字：本来就达标，不需要遮罩
        assertEquals(0f, AppearanceContrast.scrimAlphaFor(0xFFF6F1EC.toInt(), 0xFF241D1A.toInt()), 0.001f)

        // 中灰照片 + 白色文字：对比度不足，需要一层黑色遮罩压暗背景，加完必须真达标
        val grey = 0xFF9E9E9E.toInt()
        assertFalse(AppearanceContrast.passes(white, grey))
        val alpha = AppearanceContrast.scrimAlphaFor(grey, white)
        assertTrue("需要遮罩: $alpha", alpha > 0f && alpha < 1f)
        assertTrue(AppearanceContrast.passes(white, AppearanceContrast.blend(grey, black, alpha)))

        // 深色照片 + 深色文字：黑遮罩帮不上忙（越压越接近文字），返回 1 让调用方换方案
        assertEquals(1f, AppearanceContrast.scrimAlphaFor(0xFF6E6660.toInt(), 0xFF241D1A.toInt()), 0.001f)
    }

    @Test
    fun worstCasePicksTheLowestContrastSample() {
        val text = 0xFF241D1A.toInt()
        val samples = listOf(0xFFF2ECE8.toInt(), 0xFF6E6660.toInt(), 0xFFCFC7C1.toInt())
        val worst = AppearanceContrast.worstCaseBackground(samples, text)
        assertEquals(0xFF6E6660.toInt(), worst)
    }

    @Test
    fun channelDistanceComparesMaxChannelDelta() {
        assertEquals(0, AppearanceContrast.channelDistance(0xFF112233.toInt(), 0xFF112233.toInt()))
        assertEquals(0x11, AppearanceContrast.channelDistance(0xFF112233.toInt(), 0xFF002233.toInt()))
    }

    @Test
    fun largeTextThresholdIsMoreLenient() {
        // 中灰底：深色文字的对比度落在 3–4.5 之间，正文不达标、大字号达标
        val background = 0xFF757575.toInt()
        val text = 0xFF241D1A.toInt()
        val ratio = AppearanceContrast.ratio(text, background)
        assertTrue("对比度应落在 3–4.5 之间，实际 $ratio", ratio > AppearanceContrast.AA_LARGE && ratio < AppearanceContrast.AA_BODY)
        assertFalse(AppearanceContrast.passes(text, background, AppearanceContrast.AA_BODY))
        assertTrue(AppearanceContrast.passes(text, background, AppearanceContrast.AA_LARGE))
    }
}
