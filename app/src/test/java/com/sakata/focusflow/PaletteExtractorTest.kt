package com.sakata.focusflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PaletteExtractorTest {

    private fun pixels(count: Int, color: Int): IntArray = IntArray(count) { color }
    private fun fill(target: IntArray, from: Int, to: Int, color: Int) {
        for (i in from until to) target[i] = color
    }

    private fun hueOf(color: Int): Float {
        val r = ((color shr 16) and 0xFF) / 255f
        val g = ((color shr 8) and 0xFF) / 255f
        val b = (color and 0xFF) / 255f
        val maxC = maxOf(r, g, b)
        val minC = minOf(r, g, b)
        val delta = maxC - minC
        if (delta <= 0f) return 0f
        val hue = when (maxC) {
            r -> 60f * (((g - b) / delta) % 6f)
            g -> 60f * (((b - r) / delta) + 2f)
            else -> 60f * (((r - g) / delta) + 4f)
        }
        return (hue + 360f) % 360f
    }

    @Test
    fun emptyOrTooSmallInputReturnsNull() {
        assertNull(PaletteExtractor.extract(IntArray(0)))
        assertNull(PaletteExtractor.extract(pixels(10, 0xFF2E6FB7.toInt())))
    }

    @Test
    fun greyImageHasNoThemeColor() {
        // 灰阶照片：没有可用的主题色，应当如实返回 null（调用方保持当前主题）
        val grey = IntArray(600) { i ->
            val v = 60 + (i % 120)
            0xFF000000.toInt() or (v shl 16) or (v shl 8) or v
        }
        assertNull(PaletteExtractor.extract(grey))
    }

    @Test
    fun dominantBlueIsPickedAsPrimary() {
        val blue = 0xFF2E6FB7.toInt()
        val palette = PaletteExtractor.extract(pixels(900, blue))
        assertNotNull(palette)
        val hue = hueOf(palette!!.primary)
        assertTrue("蓝色主色色相应接近 210°，实际 $hue", hue in 195f..230f)
        assertTrue("置信度应较高，实际 ${palette.confidence}", palette.confidence > 0.9f)
    }

    @Test
    fun redIsNotAveragedIntoCyan() {
        // 跨越 0° 的红色：圆均值必须留在红色附近，不能被平均成青色
        val redA = 0xFFC8323C.toInt()
        val redB = 0xFFC83C32.toInt()
        val data = IntArray(800)
        fill(data, 0, 400, redA)
        fill(data, 400, 800, redB)
        val palette = PaletteExtractor.extract(data)!!
        val hue = hueOf(palette.primary)
        assertTrue("红色主色色相应在 0° 或 355°–360° 附近，实际 $hue", hue <= 12f || hue >= 348f)
    }

    @Test
    fun secondStrongColorBecomesAccent() {
        val blue = 0xFF2E6FB7.toInt()
        val orange = 0xFFD97B27.toInt()
        val data = IntArray(1000)
        fill(data, 0, 700, blue)
        fill(data, 700, 1000, orange)
        val palette = PaletteExtractor.extract(data)!!
        val primaryHue = hueOf(palette.primary)
        val accentHue = hueOf(palette.tertiary)
        assertTrue("主色应是蓝，实际 $primaryHue", primaryHue in 195f..230f)
        assertTrue("强调色应来自橙色一族，实际 $accentHue", accentHue in 20f..45f)
    }

    @Test
    fun transparentPixelsAreIgnored() {
        val transparentBlue = 0x002E6FB7
        val opaqueBlue = 0xFF2E6FB7.toInt()
        val mostlyTransparent = IntArray(1000) { transparentBlue }
        fill(mostlyTransparent, 0, 40, opaqueBlue)
        // 只有 40 个有效像素，达到下限；主色仍是蓝色
        val palette = PaletteExtractor.extract(mostlyTransparent)
        assertNotNull(palette)
        assertTrue(hueOf(palette!!.primary) in 195f..230f)
    }

    @Test
    fun derivedColorsStayInGamut() {
        val palette = PaletteExtractor.extract(pixels(500, 0xFF3F8F5B.toInt()))!!
        for (color in listOf(palette.primary, palette.secondary, palette.tertiary)) {
            assertEquals("alpha 必须是不透明的", 0xFF, (color ushr 24) and 0xFF)
            assertTrue(AppearanceContrast.channelDistance(color, color) == 0)
        }
        assertTrue(PaletteExtractor.isColorful(palette.primary))
        assertFalse(PaletteExtractor.isColorful(0xFF808080.toInt()))
    }

    @Test
    fun extractionIsDeterministic() {
        val data = IntArray(1200) { i -> if (i % 3 == 0) 0xFF2E6FB7.toInt() else 0xFFD97B27.toInt() }
        val first = PaletteExtractor.extract(data)!!
        val second = PaletteExtractor.extract(data)!!
        assertEquals(first, second)
    }
}
