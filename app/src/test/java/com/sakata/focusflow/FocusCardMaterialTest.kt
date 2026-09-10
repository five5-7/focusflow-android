package com.sakata.focusflow

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusCardMaterialTest {

    @Test
    fun noiseIsDeterministicAndSized() {
        assertEquals(NOISE_SIZE * NOISE_SIZE, noisePixels().size)
        // 同一种子逐像素一致（纯函数，可回归）
        assertArrayEquals(noisePixels(), noisePixels())
        // 不同种子应当不同
        assertNotEquals(
            noisePixels(seed = 1L).toList(),
            noisePixels(seed = 2L).toList()
        )
    }

    @Test
    fun noiseNeverDarkensTheCard() {
        val pixels = noisePixels(alpha = DEFAULT_NOISE_ALPHA)
        val alphas = pixels.map { (it ushr 24) and 0xFF }
        // 单像素最多允许 DEFAULT_NOISE_ALPHA；平均必须明显更低
        assertTrue("单像素 alpha 不应超过设定值", alphas.max() <= (DEFAULT_NOISE_ALPHA * 255f).toInt() + 1)
        val average = alphas.average()
        assertTrue("平均 alpha 应远低于上限，实际 $average", average < DEFAULT_NOISE_ALPHA * 255 * 0.7)
    }

    @Test
    fun noiseIsRoughlyBalancedBetweenLightAndDark() {
        val pixels = noisePixels()
        val bright = pixels.count { ((it shr 16) and 0xFF) > 127 }
        val total = pixels.size
        assertTrue("亮暗像素应大致各半，实际 $bright / $total", bright > total / 3 && bright < total * 2 / 3)
    }

    @Test
    fun noiseAlphaScalesWithTheParameter() {
        fun averageAlpha(alpha: Float) = noisePixels(alpha = alpha).map { (it ushr 24) and 0xFF }.average()
        assertTrue(averageAlpha(0.12f) > averageAlpha(0.04f))
        // 0 透明度 = 完全不影响画面
        assertEquals(0.0, averageAlpha(0f), 0.001)
    }

    @Test
    fun labelsCoverEveryMaterialAndStayDistinct() {
        val labels = CardMaterial.entries.map { it.label() }
        assertEquals(CardMaterial.entries.size, labels.size)
        assertEquals(labels.size, labels.toSet().size)
        assertEquals("默认", CardMaterial.TONAL.label())
    }

    /**
     * 纸感画刷是在 `drawBehind` 里取的：如果每次都重建，滚动时就是每帧一张 64×64 位图。
     * 这类抖动会被帧时间实测误读成"纸感本身很贵"，所以缓存语义要锁死。
     */
    @Test
    fun brushCacheCreatesEachKeyExactlyOnce() {
        val cache = SingleValueCache<String>()
        var created = 0
        fun value(seed: Long, alpha: Float) = cache.get(seed, alpha) {
            created++
            "brush-$seed-$alpha"
        }

        assertSame(value(DEFAULT_NOISE_SEED, DEFAULT_NOISE_ALPHA), value(DEFAULT_NOISE_SEED, DEFAULT_NOISE_ALPHA))
        assertEquals("同一个 key 只能创建一次", 1, created)

        // 不同种子 / 不同透明度是不同纹理，各建一次
        value(1L, DEFAULT_NOISE_ALPHA)
        value(DEFAULT_NOISE_SEED, 0.2f)
        assertEquals(3, created)

        // 回到旧 key 仍然复用，不会重建
        value(DEFAULT_NOISE_SEED, DEFAULT_NOISE_ALPHA)
        assertEquals(3, created)
    }
}
