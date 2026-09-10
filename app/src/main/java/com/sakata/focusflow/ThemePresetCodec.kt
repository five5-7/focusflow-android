package com.sakata.focusflow

import org.json.JSONArray
import org.json.JSONObject

/**
 * 自定义主题预设的编解码（纯函数，可单测）。
 *
 * 8.2.0 第 7 项把预设从"只有六个颜色"升级成"可选地带上整套外观"：
 * - 新预设除配色外，还记录当时的外观（[AppearanceSpec]：渐变停靠色、背景模式与图片、
 *   不透明度、卡片材质、课表底色）；
 * - **老预设（只有 `name` + `colors`）读出来 `appearance == null`**，含义是"这套预设只管配色"，
 *   应用它时不动用户当前的背景/卡片外观 —— 升级前后行为完全一致。
 *
 * 容错口径与仓库既有约定一致：读不到／读坏了退回默认，不抛错、不清数据。
 */
internal object ThemePresetCodec {

    private const val KEY_APPEARANCE = "appearance"

    fun encode(presets: List<ThemePreset>): String = JSONArray().apply {
        presets.forEach { preset ->
            put(
                JSONObject().apply {
                    put("name", preset.name)
                    put("colors", ThemeColorsCodec.encode(preset.colors))
                    // 只有真的带了外观才写这个字段：老版本读回自己的存档时不会多出未知键。
                    preset.appearance?.let { put(KEY_APPEARANCE, encodeAppearance(it)) }
                }
            )
        }
    }.toString()

    fun decode(raw: String?): List<ThemePreset> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                ThemePreset(
                    name = item.getString("name"),
                    colors = ThemeColorsCodec.decode(item.getJSONObject("colors")),
                    appearance = item.optJSONObject(KEY_APPEARANCE)?.let { decodeAppearance(it) }
                )
            }
        }.getOrDefault(emptyList())
    }

    /** 记进预设的外观字段：全部是"怎么画"，不含任何用户内容。 */
    fun encodeAppearance(spec: AppearanceSpec): JSONObject = JSONObject().apply {
        put("pageBackdrop", spec.pageBackdrop.storageKey)
        put("pageImage", spec.pageImage)
        put("backdropOpacity", spec.backdropOpacity)
        put("gradientStrength", spec.gradientStrength)
        put("pageColor", spec.pageColor)
        put("gradientFollows", spec.gradientFollowsContent)
        put("gradientTop", spec.gradientTop)
        put("gradientBottom", spec.gradientBottom)
        put("cardMaterial", spec.cardMaterial.storageKey)
        put("timetableBackdrop", spec.timetableBackdrop.storageKey)
        put("timetableColor", spec.timetableColor)
        put("timetableImage", spec.timetableImage)
        put("timetableOpacity", spec.timetableOpacity)
    }

    /**
     * 逐字段容错解码：缺字段退回默认（= 现状），单个坏值不会让整条预设读不出来。
     * 抽取结果不记进预设（它是"上次抽了什么"的记录，不是外观的一部分）。
     */
    fun decodeAppearance(value: JSONObject): AppearanceSpec {
        val fallback = AppearanceSpec.DEFAULT
        return AppearanceSpec(
            pageBackdrop = BackdropKind.fromKey(value.optString("pageBackdrop", fallback.pageBackdrop.storageKey)),
            pageImage = value.optString("pageImage", fallback.pageImage),
            backdropOpacity = value.optInt("backdropOpacity", fallback.backdropOpacity),
            gradientStrength = value.optInt("gradientStrength", fallback.gradientStrength),
            pageColor = value.optInt("pageColor", fallback.pageColor),
            gradientFollowsContent = value.optBoolean("gradientFollows", fallback.gradientFollowsContent),
            gradientTop = value.optInt("gradientTop", fallback.gradientTop),
            gradientBottom = value.optInt("gradientBottom", fallback.gradientBottom),
            cardMaterial = CardMaterial.fromKey(value.optString("cardMaterial", fallback.cardMaterial.storageKey)),
            timetableBackdrop = BackdropKind.fromKey(
                value.optString("timetableBackdrop", fallback.timetableBackdrop.storageKey)
            ),
            timetableColor = value.optInt("timetableColor", fallback.timetableColor),
            timetableImage = value.optString("timetableImage", fallback.timetableImage),
            timetableOpacity = value.optInt("timetableOpacity", fallback.timetableOpacity)
        )
    }
}
