package com.sakata.focusflow

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * 8.2.0 外观系统的对比度守卫（纯函数，见 docs/8.2.0-appearance-plan.md）。
 *
 * 自选背景、渐变、卡片材质都会改变文字背后的颜色，因此所有外观改动都必须过这里：
 * - 自定义工具里当场提示（低于阈值标红）；
 * - 导入图片时自动算需要多厚的主题遮罩。
 *
 * 全部用 0xAARRGGBB 的 Int 表示颜色，方便直接喂给 Compose 的 `Color(int)`。
 */
internal object AppearanceContrast {
    /** 正文阈值（WCAG AA）。 */
    const val AA_BODY = 4.5f

    /** 大字号标题阈值（WCAG AA Large）。 */
    const val AA_LARGE = 3f

    /** 取三位小数，避免测试里浮点尾差。 */
    private fun round3(value: Float): Float = Math.round(value * 1000f) / 1000f

    /** sRGB 相对亮度（WCAG 定义：((c + 0.055) / 1.055)^2.4）。 */
    fun luminance(color: Int): Float {
        fun channel(value: Int): Float {
            val c = (value and 0xFF) / 255f
            return if (c <= 0.03928f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
        }
        val r = channel(color shr 16)
        val g = channel(color shr 8)
        val b = channel(color)
        return 0.2126f * r + 0.7152f * g + 0.0722f * b
    }

    /** WCAG 对比度（1.0–21.0）。 */
    fun ratio(foreground: Int, background: Int): Float {
        val a = luminance(foreground)
        val b = luminance(background)
        return round3((max(a, b) + 0.05f) / (min(a, b) + 0.05f))
    }

    fun passes(foreground: Int, background: Int, target: Float = AA_BODY): Boolean =
        ratio(foreground, background) >= target - 0.0005f

    /** 把 [overlay] 按 [alpha]（0..1）叠在 [base] 上，返回实际显示出来的颜色。 */
    fun blend(base: Int, overlay: Int, alpha: Float): Int {
        val a = alpha.coerceIn(0f, 1f)
        fun mix(shift: Int): Int {
            val b = (base shr shift) and 0xFF
            val o = (overlay shr shift) and 0xFF
            return Math.round(b + (o - b) * a).coerceIn(0, 255)
        }
        return (0xFF shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }

    /**
     * 需要多厚的遮罩（[scrim] 叠在 [background] 上）才能让 [foreground] 达到 [target]。
     * 二分求最小 alpha；已经达标返回 0；叠满仍不达标返回 1（调用方应改用深色文字或别的底色）。
     */
    fun scrimAlphaFor(
        background: Int,
        foreground: Int,
        scrim: Int = 0xFF000000.toInt(),
        target: Float = AA_BODY
    ): Float {
        if (passes(foreground, background, target)) return 0f
        if (!passes(foreground, blend(background, scrim, 1f), target)) return 1f
        var low = 0f
        var high = 1f
        repeat(12) {
            val mid = (low + high) / 2f
            if (passes(foreground, blend(background, scrim, mid), target)) high = mid else low = mid
        }
        // 向上取整到三位小数：取整方向朝"更厚"的一侧，调用方拿到的值一定真的达标。
        return (kotlin.math.ceil(high * 1000f) / 1000f).coerceAtMost(1f)
    }

    /**
     * 两张图（例如导入的背景图）叠加后的"最坏情况"底色：
     * 取四角与中心五个采样点里，相对 [foreground] 对比度最低的那个，用于保守判断。
     */
    fun worstCaseBackground(samples: List<Int>, foreground: Int): Int =
        samples.minByOrNull { ratio(foreground, it) } ?: 0xFF000000.toInt()

    /** 颜色差异（0–255 的通道最大差），用于"底色是否几乎一样"这类提示。 */
    fun channelDistance(a: Int, b: Int): Int {
        fun diff(shift: Int) = abs(((a shr shift) and 0xFF) - ((b shr shift) and 0xFF))
        return max(diff(16), max(diff(8), diff(0)))
    }
}
