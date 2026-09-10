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

    /**
     * 纸纹**必须是亮度中性的**——这是真机目视复核抓出来的缺陷：
     * 旧实现用"纯黑/纯白像素 + 不同 alpha"，而 alpha 合成往下压是乘性、往上提是加性，
     * 两者不对称，于是一半黑一半白平均下来仍然把卡片整体压暗。
     * 实测纸感把绿卡压暗约 **26 灰阶**、白卡约 18 灰阶 —— 纸感变成了"换个更深的颜色"，
     * 而不是"同一张纸上加纹理"。
     *
     * 现在像素围绕中性灰 128 生成、配合 `BlendMode.Overlay` 绘制（128 = 恒等），
     * 所以平均灰阶必须非常接近 128。
     */
    @Test
    fun paperGrainIsLuminanceNeutralSoItNeverDarkensTheCard() {
        val pixels = noisePixels(alpha = DEFAULT_NOISE_ALPHA)
        val greys = pixels.map { it and 0xFF }
        // 全部像素都不透明：强度由"离 128 多远"承载，而不是靠 alpha
        assertTrue("纸纹像素应完全不透明", pixels.all { ((it ushr 24) and 0xFF) == 0xFF })
        val average = greys.average()
        assertTrue(
            "平均灰阶必须贴近 128（Overlay 的恒等点），实际 $average —— 偏离就会整体压暗或提亮",
            kotlin.math.abs(average - 128.0) < 2.0
        )
    }

    @Test
    fun noiseIsRoughlyBalancedBetweenLightAndDark() {
        val pixels = noisePixels()
        val bright = pixels.count { (it and 0xFF) > 127 }
        val total = pixels.size
        assertTrue("亮暗像素应大致各半，实际 $bright / $total", bright > total / 3 && bright < total * 2 / 3)
    }

    /** [alpha] 现在的含义是**纹理强度**：越大离中灰越远（纹理越粗），0 = 完全无纹理。 */
    @Test
    fun textureStrengthScalesTheDeviationFromNeutralGrey() {
        fun deviation(alpha: Float): Double {
            val greys = noisePixels(alpha = alpha).map { it and 0xFF }
            return greys.map { kotlin.math.abs(it - 128.0) }.average()
        }
        assertTrue("强度越大，偏离中灰越远", deviation(0.5f) > deviation(0.2f))
        // 强度 0 = 整片都是中性灰 = Overlay 恒等 = 完全不影响画面
        assertEquals("强度 0 应完全是中性灰", 0.0, deviation(0f), 0.001)
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
