package com.sakata.focusflow

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** 抽取结果：三个可用的主题色（0xFFRRGGBB）+ 置信度（0–1，越高越"有把握"）。 */
internal data class ExtractedPalette(
    val primary: Int,
    val secondary: Int,
    val tertiary: Int,
    val confidence: Float
)

/**
 * 8.2.0「系统自抽主题色」：从导入的背景图里抽出一套可用的主题色。
 *
 * 纯函数（只吃 `IntArray`，不碰 Android 类型），因此可单测、可回归：
 * 固定像素数组 → 固定输出。调用方负责在后台线程降采样到 ~64×64 后再喂进来。
 *
 * 做法（刻意简单、可解释）：
 * 1. 丢掉透明、过暗、过亮、以及饱和度太低的像素（灰底、白墙、黑边不参与）；
 * 2. 按色相分 12 个桶，权重 = 饱和度 × 明度适中度，取权重最高的桶当主色；
 * 3. 主色的色相取该桶的**圆均值**（避免红跨 0° 时被平均成青色），饱和度/明度取该桶中位数；
 * 4. 副色 = 主色降饱和提亮；强调色 = 下一个**不相邻**的强势桶（画面里的第二种颜色）；
 * 5. 画面太素（有效像素太少或主色占比太低）时返回 null——调用方保持当前主题并如实提示。
 */
internal object PaletteExtractor {

    private const val HUE_BUCKETS = 12
    private const val MIN_SATURATION = 0.18f
    private const val MIN_VALUE = 0.15f
    private const val MAX_VALUE = 0.97f
    private const val MIN_USABLE_PIXELS = 24
    private const val MIN_CONFIDENCE = 0.12f

    fun extract(pixels: IntArray, maxSamples: Int = 4096): ExtractedPalette? {
        if (pixels.isEmpty()) return null
        val stride = max(1, pixels.size / maxSamples)
        val hueWeight = FloatArray(HUE_BUCKETS)
        val bucketSat = Array(HUE_BUCKETS) { ArrayList<Float>(16) }
        val bucketVal = Array(HUE_BUCKETS) { ArrayList<Float>(16) }
        // 色相圆均值：分别累加单位向量，最后 atan2 回角度
        val hueX = FloatArray(HUE_BUCKETS)
        val hueY = FloatArray(HUE_BUCKETS)
        var usable = 0

        var index = 0
        while (index < pixels.size) {
            val pixel = pixels[index]
            index += stride
            if ((pixel ushr 24) < 128) continue
            val r = ((pixel shr 16) and 0xFF) / 255f
            val g = ((pixel shr 8) and 0xFF) / 255f
            val b = (pixel and 0xFF) / 255f
            val maxC = max(r, max(g, b))
            val minC = min(r, min(g, b))
            val delta = maxC - minC
            val value = maxC
            val saturation = if (maxC <= 0f) 0f else delta / maxC
            if (saturation < MIN_SATURATION || value < MIN_VALUE || value > MAX_VALUE) continue
            val hue = hueOf(r, g, b, maxC, delta)
            val bucket = ((hue / 360f) * HUE_BUCKETS).toInt().coerceIn(0, HUE_BUCKETS - 1)
            // 明度适中度：太暗太亮都不算"主题色候选"
            val balance = 1f - abs(value - 0.6f) / 0.6f
            val weight = saturation * (0.5f + 0.5f * balance)
            hueWeight[bucket] += weight
            bucketSat[bucket].add(saturation)
            bucketVal[bucket].add(value)
            val radians = Math.toRadians(hue.toDouble())
            hueX[bucket] += (weight * kotlin.math.cos(radians)).toFloat()
            hueY[bucket] += (weight * kotlin.math.sin(radians)).toFloat()
            usable++
        }

        if (usable < MIN_USABLE_PIXELS) return null
        val totalWeight = hueWeight.sum()
        if (totalWeight <= 0f) return null

        fun ranked(): List<Int> = (0 until HUE_BUCKETS)
            .filter { hueWeight[it] > 0f }
            .sortedByDescending { hueWeight[it] }

        val order = ranked()
        val main = order.first()
        val confidence = hueWeight[main] / totalWeight
        if (confidence < MIN_CONFIDENCE) return null

        val primary = colorOf(main, hueX, hueY, bucketSat, bucketVal)
        // 强调色：跳过与主色相邻的桶（相邻往往是同一片颜色的过渡）
        val accentBucket = order.drop(1).firstOrNull { bucketDistance(it, main) >= 2 }
        val tertiary = accentBucket?.let { colorOf(it, hueX, hueY, bucketSat, bucketVal) }
            ?: shiftHue(primary, 150f, saturationScale = 0.85f, valueScale = 1.05f)

        return ExtractedPalette(
            primary = primary,
            secondary = shiftHue(primary, 18f, saturationScale = 0.55f, valueScale = 1.12f),
            tertiary = tertiary,
            confidence = confidence
        )
    }

    private fun bucketDistance(a: Int, b: Int): Int {
        val raw = abs(a - b)
        return min(raw, HUE_BUCKETS - raw)
    }

    private fun colorOf(
        bucket: Int,
        hueX: FloatArray,
        hueY: FloatArray,
        saturations: Array<ArrayList<Float>>,
        values: Array<ArrayList<Float>>
    ): Int {
        val hue = (Math.toDegrees(kotlin.math.atan2(hueY[bucket].toDouble(), hueX[bucket].toDouble())).toFloat() + 360f) % 360f
        val saturation = median(saturations[bucket]).coerceIn(0.25f, 0.95f)
        val value = median(values[bucket]).coerceIn(0.25f, 0.92f)
        return hsvToRgb(hue, saturation, value)
    }

    private fun median(values: List<Float>): Float {
        if (values.isEmpty()) return 0.5f
        val sorted = values.sorted()
        return sorted[sorted.size / 2]
    }

    private fun hueOf(r: Float, g: Float, b: Float, maxC: Float, delta: Float): Float {
        if (delta <= 0f) return 0f
        val hue = when (maxC) {
            r -> 60f * (((g - b) / delta) % 6f)
            g -> 60f * (((b - r) / delta) + 2f)
            else -> 60f * (((r - g) / delta) + 4f)
        }
        return (hue + 360f) % 360f
    }

    /** 只做色相旋转 + 饱和度/明度缩放，用于派生副色/强调色。 */
    private fun shiftHue(color: Int, degrees: Float, saturationScale: Float, valueScale: Float): Int {
        val r = ((color shr 16) and 0xFF) / 255f
        val g = ((color shr 8) and 0xFF) / 255f
        val b = (color and 0xFF) / 255f
        val maxC = max(r, max(g, b))
        val minC = min(r, min(g, b))
        val delta = maxC - minC
        val hue = (hueOf(r, g, b, maxC, delta) + degrees + 360f) % 360f
        val saturation = if (maxC <= 0f) 0f else (delta / maxC) * saturationScale
        val value = (maxC * valueScale).coerceAtMost(1f)
        return hsvToRgb(hue, saturation.coerceIn(0f, 1f), value)
    }

    private fun hsvToRgb(hue: Float, saturation: Float, value: Float): Int {
        val h = ((hue % 360f) + 360f) % 360f / 60f
        val c = value * saturation
        val x = c * (1f - abs(h % 2f - 1f))
        val m = value - c
        val (r1, g1, b1) = when (h.toInt()) {
            0 -> Triple(c, x, 0f)
            1 -> Triple(x, c, 0f)
            2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c)
            4 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        fun channel(v: Float) = ((v + m) * 255f).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (channel(r1) shl 16) or (channel(g1) shl 8) or channel(b1)
    }

    /** 判断一个颜色够不够"有颜色"（供 UI 提示用）。 */
    fun isColorful(color: Int): Boolean {
        val r = ((color shr 16) and 0xFF) / 255f
        val g = ((color shr 8) and 0xFF) / 255f
        val b = (color and 0xFF) / 255f
        val maxC = max(r, max(g, b))
        val minC = min(r, min(g, b))
        val saturation = if (maxC <= 0f) 0f else (maxC - minC) / maxC
        return saturation >= MIN_SATURATION && sqrt(maxC) > 0.1f
    }
}
