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
     * 注意：它**不是**亮度中性的——近白卡片没有提亮空间，所以净效果是略偏暗。
     * 这是物理约束而不是缺陷；真正要守的是"方向正确"与"可见"。
     */
    @Test
    fun softLightIsATopLitThreeStopGradient() {
        val scheme = lightColorScheme()
        val base = scheme.surfaceContainerLow
        val stops = softLightStops(base, scheme.onSurface)
        assertEquals("柔光是三站", 3, stops.size)
        assertEquals("中间站就是底色本身", base, stops[1])
        assertTrue("顶站要比中间亮", stops[0].luminance() > stops[1].luminance())
        assertTrue("底站要比中间沉", stops[2].luminance() < stops[1].luminance())
    }

    /** 柔光要真的看得出来：顶底落差不能小到不可感知。 */
    @Test
    fun softLightIsStrongEnoughToBeVisible() {
        for (theme in FocusFlowThemeOption.builtInEntries()) {
            for (dark in listOf(false, true)) {
                val scheme = focusFlowThemeSpec(theme, darkMode = dark).colorScheme
                val stops = softLightStops(scheme.surfaceContainerLow, scheme.onSurface)
                val delta = kotlin.math.abs(stops[0].luminance() - stops[2].luminance())
                assertTrue(
                    "${theme.label}（${if (dark) "深色" else "浅色"}）柔光顶底亮度差只有 $delta，太小会看不出来",
                    delta > 0.01f
                )
            }
        }
    }
}
