package com.sakata.focusflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 主题画廊守卫：维护者明确要求「原来的四个主题应予以保留，可以添加新的主题」。
 * 这里把这句话变成可回归的断言——老主题的取值被写死成金值，谁改动了就红。
 */
class ThemeGalleryTest {

    private val originalFour = listOf("ocean", "mint", "apricot", "twilight")
    private val addedIn820 = listOf("graphite", "sakura", "bamboo")

    @Test
    fun builtInGalleryKeepsTheOriginalFourAndAddsThree() {
        val keys = FocusFlowThemeOption.builtInEntries().map { it.storageKey }
        assertTrue("原有四套必须还在：$keys", keys.containsAll(originalFour))
        assertTrue("8.2.0 新增三套：$keys", keys.containsAll(addedIn820))
        assertEquals(7, keys.size)
        // storageKey 唯一（老装机升级后 fromStorageKey 才能稳定读回）
        assertEquals(keys.size, keys.toSet().size)
        // 自定义主题不算内置
        assertTrue(FocusFlowThemeOption.CUSTOM !in FocusFlowThemeOption.builtInEntries())
    }

    @Test
    fun originalFourThemesArePixelFrozen() {
        // 金值：任何改动原有主题的行为都会在这里失败
        assertEquals(0xFF0F6B7A.toInt(), FocusFlowThemeOption.OCEAN.colors.primaryAction.argbInt())
        assertEquals(0xFFF7FAFC.toInt(), FocusFlowThemeOption.OCEAN.colors.neutral.argbInt())
        assertEquals(0xFF2C6D5A.toInt(), FocusFlowThemeOption.MINT.colors.primaryAction.argbInt())
        assertEquals(0xFFF6FAF7.toInt(), FocusFlowThemeOption.MINT.colors.neutral.argbInt())
        assertEquals(0xFFA44F34.toInt(), FocusFlowThemeOption.APRICOT.colors.primaryAction.argbInt())
        assertEquals(0xFFFFF8F4.toInt(), FocusFlowThemeOption.APRICOT.colors.neutral.argbInt())
        assertEquals(0xFF65558F.toInt(), FocusFlowThemeOption.TWILIGHT.colors.primaryAction.argbInt())
        assertEquals(0xFFFAF7FC.toInt(), FocusFlowThemeOption.TWILIGHT.colors.neutral.argbInt())
    }

    @Test
    fun everyBuiltInThemeKeepsBodyTextReadable() {
        for (theme in FocusFlowThemeOption.builtInEntries()) {
            val spec = focusFlowThemeSpec(theme)
            val ratio = AppearanceContrast.ratio(
                spec.colorScheme.onBackground.argbInt(),
                spec.colorScheme.background.argbInt()
            )
            assertTrue("${theme.label} 正文对比度 $ratio 应 >= 10:1", ratio >= 10f)
        }
    }

    @Test
    fun everyBuiltInThemeHasADistinctPrimary() {
        val primaries = FocusFlowThemeOption.builtInEntries().map { it.colors.primaryAction.argbInt() }
        for (i in primaries.indices) {
            for (j in i + 1 until primaries.size) {
                val distance = AppearanceContrast.channelDistance(primaries[i], primaries[j])
                assertTrue("第 $i 与第 $j 套主题主色太接近（通道差 $distance）", distance >= 20)
            }
        }
    }

    @Test
    fun newThemesCarryTheSharedSemanticScheduleColors() {
        for (key in addedIn820) {
            val theme = FocusFlowThemeOption.entries.first { it.storageKey == key }
            val spec = focusFlowThemeSpec(theme)
            // 课程色跟随本主题，其余语义色保持统一（换主题不换家族）
            assertEquals(theme.colors.schedule.argbInt(), spec.schedulePalette.course.argbInt())
            assertEquals(0xFF2F8F5B.toInt(), spec.schedulePalette.exercise.argbInt())
            assertEquals(0xFF94A3B8.toInt(), spec.schedulePalette.completed.argbInt())
        }
    }

    @Test
    fun darkModeAppliesToNewThemesToo() {
        for (key in addedIn820) {
            val theme = FocusFlowThemeOption.entries.first { it.storageKey == key }
            val dark = focusFlowThemeSpec(theme, darkMode = true).colorScheme
            val ratio = AppearanceContrast.ratio(dark.onBackground.argbInt(), dark.background.argbInt())
            assertTrue("$key 深色模式正文对比度 $ratio 应 >= 10:1", ratio >= 10f)
        }
    }
}
