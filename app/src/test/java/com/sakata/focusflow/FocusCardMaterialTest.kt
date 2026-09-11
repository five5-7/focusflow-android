package com.sakata.focusflow

import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 卡片材质的表现层回归。
 *
 * **「纸感」已于 2026-09-10 删除**（维护者口径：「纸感如果只是纹路的话就可以删掉了」），
 * 相关的纸纹/噪点测试（noisePixels / paperNoiseBrush / SingleValueCache）随实现一起删掉，
 * 不再保留"测已经不存在的东西"的测试。
 *
 * 删除原因留档：纸感与柔光的区别**只可能**是一层纹理（颜色关系相同），
 * 而纹理在近白卡片上无法既可见又保持亮度中性 —— 近白底没有"提亮"空间，
 * 任何纹理都只能压暗；压得少就看不见（Overlay 方案实测仅约 2 灰阶），
 * 压得多就变成"换个更深的颜色"（旧方案实测压暗 26 灰阶）。
 */
class FocusCardMaterialTest {

    @Test
    fun threeMaterialsAreLabelledDistinctly() {
        val labels = CardMaterial.entries.map { it.label() }
        assertEquals(CardMaterial.entries.size, labels.size)
        assertEquals("材质名不能重复", labels.size, labels.toSet().size)
        assertEquals("默认", CardMaterial.TONAL.label())
        assertEquals("渐变", CardMaterial.GRADIENT.label())
        assertEquals("柔光", CardMaterial.SOFT.label())
    }

    /** 纸感已删：老装机/老预设里存的 `"paper"` 必须**优雅降级**，不抛错、不清数据。 */
    @Test
    fun removedPaperMaterialDegradesToTonal() {
        assertEquals(
            "老存档里的 paper 应退回默认材质",
            CardMaterial.TONAL,
            CardMaterial.fromKey("paper")
        )
        assertEquals(CardMaterial.TONAL, CardMaterial.fromKey(null))
        assertEquals(CardMaterial.TONAL, CardMaterial.fromKey(""))
        assertEquals(CardMaterial.TONAL, CardMaterial.fromKey("nonsense"))
        // 现有三档仍能正常往返
        for (m in CardMaterial.entries) {
            assertEquals(m, CardMaterial.fromKey(m.storageKey))
        }
    }

    @Test
    fun everyRemainingMaterialProducesAVisibleLayer() {
        val scheme = lightColorScheme()
        val base = scheme.surfaceContainerLow
        assertNull("默认材质不叠任何东西（走原生 Card）", materialBrush(CardMaterial.TONAL, base, scheme))
        for (material in listOf(CardMaterial.GRADIENT, CardMaterial.SOFT)) {
            assertTrue("$material 必须产出可见的一层", materialBrush(material, base, scheme) != null)
        }
    }

    /**
     * 柔光必须是"顶亮 → 底色 → 底沉"的三站，方向不能反
     * （维护者反馈过"曲线反了"，所以这里把方向钉死）。
     *
     * **注意措辞**：这里的"曲线反了"指的是**整块均值偏暗**——柔光卡片看起来比默认卡片更暗，
     * 而不是顶底方向画反了。真正的亮度中性由 [softLightIsLuminanceNeutral] 守。
     */
    @Test
    fun softLightIsATopLitThreeStopGradient() {
        val scheme = lightColorScheme()
        val base = scheme.surfaceContainerLow
        val stops = softLightStops(base)
        assertEquals("柔光是三站", 3, stops.size)
        assertEquals("中间站就是底色本身", base, stops[1])
        assertTrue("顶站要比中间亮", stops[0].luminance() > stops[1].luminance())
        assertTrue("底站要比中间沉", stops[2].luminance() < stops[1].luminance())
    }

    /**
     * **柔光必须亮度中性**（维护者 T-1 口径，本轮返工的核心）。
     *
     * 三站等距铺开时整块的均值是 `(顶 + 4×中 + 底) / 6`。旧实现（顶 +9% 白 / 底 −5% `onSurface`）
     * 在浅色底上均值净暗约 1.5%、深色底上净亮约 26%，两个方向都是"整块变了色"而不是"被光照到"。
     *
     * 断言写成**单侧**的：允许极其轻微的偏亮（sRGB 传递函数是凸的，等量平移必然换来
     * 一点点相对亮度上升，方向安全），但**绝不允许偏暗**——那正是维护者看到的问题。
     */
    @Test
    fun softLightIsLuminanceNeutral() {
        for (theme in FocusFlowThemeOption.builtInEntries()) {
            for (dark in listOf(false, true)) {
                val scheme = focusFlowThemeSpec(theme, darkMode = dark).colorScheme
                val base = scheme.surfaceContainerLow
                val stops = softLightStops(base)
                val mean = (stops[0].luminance() + 4f * stops[1].luminance() + stops[2].luminance()) / 6f
                val target = stops[1].luminance()
                val drift = (mean - target) / target
                val label = "${theme.label}（${if (dark) "深色" else "浅色"}）"
                assertTrue(
                    "$label 柔光让整块变暗了 ${-drift * 100}% —— 这正是「曲线反了」的成因，不许出现",
                    drift >= -0.0001f
                )
                assertTrue(
                    "$label 柔光让整块变亮了 ${drift * 100}%，已经超出「亮度中性」",
                    drift <= 0.05f
                )
            }
        }
    }

    /**
     * 上下两站必须关于底色**逐通道严格等量**。
     *
     * 等量是亮度中性的充分条件，也是它唯一的解释；这里三个通道一起查，
     * 因为"顶站被纯白夹住"只会发生在个别通道上（暖杏浅色的底板红通道就是 255），
     * 只查一个通道会漏掉这一类。
     */
    @Test
    fun softLightStopsAreSymmetricAboutTheBase() {
        val eps = 0.5f / 255f
        for (theme in FocusFlowThemeOption.builtInEntries()) {
            for (dark in listOf(false, true)) {
                val scheme = focusFlowThemeSpec(theme, darkMode = dark).colorScheme
                val base = scheme.surfaceContainerLow
                val stops = softLightStops(base)
                val label = "${theme.label}（${if (dark) "深色" else "浅色"}）"
                assertEquals("$label 红通道柔光上下不等量", stops[0].red - base.red, base.red - stops[2].red, eps)
                assertEquals("$label 绿通道柔光上下不等量", stops[0].green - base.green, base.green - stops[2].green, eps)
                assertEquals("$label 蓝通道柔光上下不等量", stops[0].blue - base.blue, base.blue - stops[2].blue, eps)
            }
        }
    }

    /**
     * 「卡面渐变方向」必须只做**一次整体翻转**，不能顺手改颜色。
     *
     * 这条同时把"哪一端在顶部"的歧义从代码里消掉，依据是**页面渐变**：
     * `pageBrushFor` 的 `TOP_DOWN` 就是 `Brush.verticalGradient(stops)`、`BOTTOM_UP` 是
     * `stops.reversed()`，而维护者已多次验收「端色 = 所选的顶色→底色」，
     * 所以 `Brush.verticalGradient` 的 `stops[0]` 一定画在**顶部**。
     * 卡面沿用同一条约定：`stops[0]` = 顶站。
     */
    @Test
    fun reversingTheCardGradientOnlyFlipsIt() {
        for (theme in FocusFlowThemeOption.builtInEntries()) {
            for (dark in listOf(false, true)) {
                val scheme = focusFlowThemeSpec(theme, darkMode = dark).colorScheme
                val base = scheme.surfaceContainerLow
                val label = "${theme.label}（${if (dark) "深色" else "浅色"}）"
                val forward = softLightStops(base)
                val backward = softLightStops(base, reversed = true)
                assertEquals("$label 反向必须只是把三站倒过来，不许换颜色", forward.reversed(), backward)
                assertTrue("$label 正向时顶站更亮", forward[0].luminance() > forward[2].luminance())
                assertTrue("$label 反向时顶站更暗", backward[0].luminance() < backward[2].luminance())
            }
        }
    }

    /**
     * 毛玻璃与亚克力已于 2026-09-11 按维护者口径删除，
     * 它们各自的回归（毛玻璃的面积守恒 / 亚克力的染色）随之移除 ——
     * 留着就是测一个不存在的材质。删除原因见 `AppearanceSpec.CardMaterial` 的注释。
     */
    @Test
    fun removedMaterialsAreGoneFromTheEnum() {
        val keys = CardMaterial.entries.map { it.storageKey }
        assertTrue("毛玻璃已删除", !keys.contains("frosted"))
        assertTrue("亚克力已删除", !keys.contains("acrylic"))
        // 老装机/老预设里存过这两个值，必须优雅降级成默认，不抛错、不清数据。
        assertEquals(CardMaterial.TONAL, CardMaterial.fromKey("frosted"))
        assertEquals(CardMaterial.TONAL, CardMaterial.fromKey("acrylic"))
    }

    /** 柔光要真的看得出来：顶底落差不能小到不可感知。 */
    @Test
    fun softLightIsStrongEnoughToBeVisible() {
        for (theme in FocusFlowThemeOption.builtInEntries()) {
            for (dark in listOf(false, true)) {
                val scheme = focusFlowThemeSpec(theme, darkMode = dark).colorScheme
                val stops = softLightStops(scheme.surfaceContainerLow)
                val delta = kotlin.math.abs(stops[0].luminance() - stops[2].luminance())
                assertTrue(
                    "${theme.label}（${if (dark) "深色" else "浅色"}）柔光顶底亮度差只有 $delta，太小会看不出来",
                    delta > 0.01f
                )
            }
        }
    }
}
