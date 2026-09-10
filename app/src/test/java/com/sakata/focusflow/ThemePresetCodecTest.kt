package com.sakata.focusflow

import androidx.compose.ui.graphics.Color
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 8.2.0 第 7 项：预设从"只有配色"升级成"可选地带整套外观"。
 *
 * 最重要的一条是**向后兼容**：老装机里已经存好的预设（只有 `name` + `colors`）必须
 * 原样读出来、原样写回去，含义仍是"这套预设只管配色"。
 */
class ThemePresetCodecTest {

    private val colors = FocusFlowThemeColors(
        primaryAction = Color(0xFF0F6B7A),
        secondary = Color(0xFF4062A8),
        accent = Color(0xFFA44F34),
        schedule = Color(0xFF2C6D5A),
        neutral = Color(0xFFF5F5F5),
        warning = Color(0xFFB3261E),
        text = Color(0xFF182124)
    )

    private val look = AppearanceSpec(
        pageBackdrop = BackdropKind.GRADIENT,
        gradientStrength = 140,
        gradientTop = 0xFFDCE9F2.toInt(),
        gradientBottom = 0xFFF7FBFF.toInt(),
        gradientFollowsContent = true,
        cardMaterial = CardMaterial.PAPER,
        timetableBackdrop = BackdropKind.COLOR,
        timetableColor = 0xFFF2ECE8.toInt()
    )

    @Test
    fun presetWithAppearanceRoundTrips() {
        val presets = listOf(ThemePreset("晨间", colors, look))
        assertEquals(presets, ThemePresetCodec.decode(ThemePresetCodec.encode(presets)))
    }

    @Test
    fun presetWithoutAppearanceStaysColourOnly() {
        val presets = listOf(ThemePreset("只有配色", colors))
        val decoded = ThemePresetCodec.decode(ThemePresetCodec.encode(presets))
        assertEquals(presets, decoded)
        assertNull(decoded.single().appearance)
    }

    /** 老版本写出来的存档：只有 name 与 colors 两个键。 */
    @Test
    fun legacyArchiveWithoutAppearanceStillReads() {
        val legacy = JSONArray().put(
            JSONObject()
                .put("name", "老预设")
                .put("colors", ThemeColorsCodec.encode(colors))
        ).toString()
        val decoded = ThemePresetCodec.decode(legacy)
        assertEquals(1, decoded.size)
        assertEquals("老预设", decoded.single().name)
        assertEquals(colors, decoded.single().colors)
        assertNull(decoded.single().appearance)
    }

    /** 写回去时不许凭空多出 appearance 键：老版本读自己的存档要还是老样子。 */
    @Test
    fun colourOnlyPresetsDoNotGainTheField() {
        val encoded = ThemePresetCodec.encode(listOf(ThemePreset("只有配色", colors)))
        assertTrue(!encoded.contains("appearance"))
    }

    @Test
    fun brokenArchiveFallsBackToEmptyList() {
        assertTrue(ThemePresetCodec.decode(null).isEmpty())
        assertTrue(ThemePresetCodec.decode("").isEmpty())
        assertTrue(ThemePresetCodec.decode("{ not json").isEmpty())
        // 单条坏掉整段丢弃，而不是抛错让设置页打不开
        assertTrue(ThemePresetCodec.decode("""[{"colors":{}}]""").isEmpty())
    }

    /** 外观字段逐项容错：缺字段用默认，坏枚举值退回现状。 */
    @Test
    fun appearanceFieldsFallBackIndividually() {
        val partial = JSONObject().put("pageBackdrop", "nonsense").put("gradientStrength", 175)
        val decoded = ThemePresetCodec.decodeAppearance(partial)
        assertEquals(BackdropKind.THEME, decoded.pageBackdrop)
        assertEquals(175, decoded.gradientStrength)
        assertEquals(CardMaterial.TONAL, decoded.cardMaterial)
        assertEquals(AppearanceSpec.DEFAULT.cardMaterial, decoded.cardMaterial)
        assertEquals(0, decoded.gradientTop)
        assertTrue(!decoded.gradientFollowsContent)
    }

    @Test
    fun appearanceRoundTripsEveryStoredField() {
        val decoded = ThemePresetCodec.decodeAppearance(ThemePresetCodec.encodeAppearance(look))
        assertEquals(look, decoded)
        // 抽取结果不属于外观，不该被预设带回来
        assertTrue(decoded.extractedColors.isEmpty())
    }
}
