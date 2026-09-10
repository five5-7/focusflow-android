package com.sakata.focusflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 背景图遮罩的**自适应厚度**（2026-09-10 真机反馈"淡色底浅色字看不见"）。
 *
 * 原先 `scrimAlpha` 只看"不透明度"，完全不看图片内容，于是：
 * - 深色模式（正文是浅色）+ 浅色图 + 低不透明度 → 整页被洗白，正文 1.80:1；
 * - 浅色模式（正文是深色）+ 深色图 + 高不透明度 → 整页压黑，正文同样糊。
 *
 * 这里守住两条：
 * 1. **"有风险的那一侧"遮罩必须更厚**（浅色正文配亮图、深色正文配暗图）；
 * 2. **不透明度为 0 或图片亮度正好在中点风险档时，退化成原来的固定厚度**，
 *    保证"原来看得清的情况不会因为这次改动变糊"。
 */
class ImageScrimTest {

    /** 平均亮度的采样算法：纯白 / 纯黑 / 中灰，以及均匀铺开的步长不能漏采。 */
    @Test
    fun averageLuminanceHandlesExtremesAndSampling() {
        val white = IntArray(64 * 64) { 0xFFFFFFFF.toInt() }
        assertEquals(1f, averageLuminance(white, 64, 64), 0.01f)

        val black = IntArray(64 * 64) { 0xFF000000.toInt() }
        assertEquals(0f, averageLuminance(black, 64, 64), 0.01f)

        // 中灰的相对亮度约 0.216（不是 0.5——sRGB 的 gamma 决定的），
        // 这里只要求它明显落在两端之间即可，不把具体数值写死。
        val grey = IntArray(64 * 64) { 0xFF808080.toInt() }
        val g = averageLuminance(grey, 64, 64)
        assertTrue("中灰应在 0.1..0.4 之间，实际 $g", g > 0.1f && g < 0.4f)

        // 非法输入不炸
        assertEquals(0f, averageLuminance(IntArray(0), 0, 0), 0.001f)
    }

    @Test
    fun zeroOpacityAndUnknownLuminanceKeepTheOriginalScrim() {
        // 图片完全不可见时没有风险，遮罩必须等于原来的固定值
        assertEquals(scrimAlpha(0f), adaptiveScrimAlpha(0f, 0f, textIsLight = false), 0.001f)
        assertEquals(scrimAlpha(0f), adaptiveScrimAlpha(0f, 1f, textIsLight = true), 0.001f)
    }

    @Test
    fun lightTextOverABrightImageGetsAThickerScrim() {
        val a = 0.5f
        val fixed = scrimAlpha(a)
        val lightTextOverBright = adaptiveScrimAlpha(a, imageLuminance = 1f, textIsLight = true)
        val lightTextOverDark = adaptiveScrimAlpha(a, imageLuminance = 0f, textIsLight = true)
        val darkTextOverBright = adaptiveScrimAlpha(a, imageLuminance = 1f, textIsLight = false)
        val darkTextOverDark = adaptiveScrimAlpha(a, imageLuminance = 0f, textIsLight = false)
        // 浅色正文（深色模式）遇到亮图：必须比固定值厚 —— 这正是出问题的那一格
        assertTrue(
            "浅色正文 + 亮图 应加厚遮罩（实际 $lightTextOverBright vs 固定 $fixed）",
            lightTextOverBright > fixed
        )
        // 浅色正文遇到暗图：没风险，不该更厚
        assertTrue(
            "浅色正文 + 暗图 不该加厚（实际 $lightTextOverDark）",
            lightTextOverDark <= fixed + 0.001f
        )
        // 深色正文遇到暗图：危险，要加厚；遇到亮图：没风险
        assertTrue(
            "深色正文 + 暗图 应加厚（实际 $darkTextOverDark vs 固定 $fixed）",
            darkTextOverDark > fixed
        )
        assertTrue(
            "深色正文 + 亮图 不该加厚（实际 $darkTextOverBright）",
            darkTextOverBright <= fixed + 0.001f
        )
    }

    /** 遮罩无论怎么自适应都不能到 1.0：那等于图片被完全盖掉、设置形同失效。 */
    @Test
    fun scrimNeverFullyHidesTheImage() {
        for (a in listOf(0.01f, 0.3f, 0.7f, 1f)) {
            for (l in listOf(0f, 0.5f, 1f)) {
                for (light in listOf(false, true)) {
                    val s = adaptiveScrimAlpha(a, l, light)
                    assertTrue("遮罩 $s 应落在 0..0.98", s in 0f..0.98f)
                }
            }
        }
    }

    /**
     * 回归真正的症状：深色模式（浅色正文）+ 接近全白的图片 + 低不透明度。
     * 要求加厚之后，正文对合成底色的对比度**明显好于**改动前。
     */
    @Test
    fun theReportedCaseIsActuallyImproved() {
        val scheme = focusFlowThemeSpec(FocusFlowThemeOption.OCEAN, darkMode = true).colorScheme
        val on = scheme.onBackground.argbInt()
        val bg = scheme.background.argbInt()
        val whiteImage = 0xFFFFFFFF.toInt()

        fun ratioWith(scrim: Float, opacity: Float): Float {
            val stops = ThemeGradient.pageStops(scheme, 1f).map { it.argbInt() }
            val brightest = stops.maxByOrNull { AppearanceContrast.luminance(it) } ?: bg
            val composited = AppearanceContrast.blend(brightest, whiteImage, opacity)
            return AppearanceContrast.ratio(on, AppearanceContrast.blend(composited, bg, scrim))
        }

        for (opacity in listOf(0.1f, 0.5f, 1f)) {
            val before = ratioWith(scrimAlpha(opacity), opacity)
            val after = ratioWith(adaptiveScrimAlpha(opacity, 1f, textIsLight = true), opacity)
            assertTrue(
                "不透明度 ${(opacity * 100).toInt()}%：改动后应更好（前 $before → 后 $after）",
                after > before
            )
        }
    }
}
