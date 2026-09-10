package com.sakata.focusflow

/**
 * 8.2.0 外观系统的数据模型：只描述"外观怎么画"，不含任何用户内容。
 *
 * 三条不变量（见 docs/8.2.0-appearance-plan.md）：
 * - 全部可选，默认值等于现在的样子（老装机升级后外观不变）；
 * - 读不到／读坏了就退回默认，不抛错、不清数据；
 * - 课程块颜色等既有数据契约一个字不动。
 */
internal enum class BackdropKind(val storageKey: String) {
    /** 跟随主题（现状）。 */
    THEME("theme"),

    /** 主题渐变（页面）或选定的颜色（课表）。 */
    GRADIENT("gradient"),

    /** 纯选色（课表用）。 */
    COLOR("color"),

    /** 自导入图片。 */
    IMAGE("image");

    companion object {
        fun fromKey(key: String?): BackdropKind =
            entries.firstOrNull { it.storageKey == key } ?: THEME
    }
}

/** 卡片材质（表现层）。 */
internal enum class CardMaterial(val storageKey: String) {
    /** 现状：单一容器色。 */
    TONAL("tonal"),

    /** 主题渐变卡面。 */
    GRADIENT("gradient"),

    /** 柔光：顶面高光 + 主题染色阴影 + 细描边。 */
    SOFT("soft"),

    /** 纸感：柔光 + 极淡噪点纹理。 */
    PAPER("paper");

    companion object {
        fun fromKey(key: String?): CardMaterial =
            entries.firstOrNull { it.storageKey == key } ?: TONAL
    }
}

/**
 * 当前外观偏好。
 *
 * [backdropOpacity] 是**背景图自身的不透明度**（0–100）：0 等于只用主题底色，
 * 100 等于图片完全覆盖（此时仍会叠一层主题遮罩保正文对比度）。
 */
internal data class AppearanceSpec(
    val pageBackdrop: BackdropKind = BackdropKind.THEME,
    val pageImage: String = "",
    val backdropOpacity: Int = 100,
    val cardMaterial: CardMaterial = CardMaterial.TONAL,
    val timetableBackdrop: BackdropKind = BackdropKind.THEME,
    val timetableColor: Int = 0,
    val timetableImage: String = "",
    val timetableOpacity: Int = 100,
    val extractedColors: List<Long> = emptyList()
) {
    /** 背景图不透明度换算成 0..1，越界读数夹回合法区间。 */
    val imageAlpha: Float get() = backdropOpacity.coerceIn(0, 100) / 100f

    val timetableAlpha: Float get() = timetableOpacity.coerceIn(0, 100) / 100f

    /** 页面此刻是否真的有一张可画的背景图。 */
    val hasPageImage: Boolean
        get() = pageBackdrop == BackdropKind.IMAGE && pageImage.isNotBlank() && backdropOpacity > 0

    /** 课表此刻是否真的有一张可画的背景图。 */
    val hasTimetableImage: Boolean
        get() = timetableBackdrop == BackdropKind.IMAGE && timetableImage.isNotBlank() && timetableOpacity > 0

    /** 课表是否用了自选颜色。 */
    val timetableUsesColor: Boolean
        get() = timetableBackdrop == BackdropKind.COLOR && timetableColor != 0

    companion object {
        val DEFAULT = AppearanceSpec()

        /** 抽取结果编解码：`PRIMARY;SECONDARY;TERTIARY`（各 8 位十六进制 ARGB）。 */
        fun encodeExtracted(colors: List<Long>): String =
            colors.map { String.format("%08X", it and 0xFFFFFFFFL) }.joinToString(";")

        fun decodeExtracted(raw: String?): List<Long> =
            raw.orEmpty()
                .split(';')
                .mapNotNull { part ->
                    val text = part.trim()
                    if (text.length == 8) text.toLongOrNull(16) else null
                }

        /** 从偏好键构造；任何缺失或非法值都退回默认。 */
        fun fromKeys(
            pageBackdrop: String?,
            pageImage: String?,
            backdropOpacity: Int,
            cardMaterial: String?,
            timetableBackdrop: String?,
            timetableColor: Int,
            timetableImage: String?,
            timetableOpacity: Int,
            extracted: String?
        ): AppearanceSpec = AppearanceSpec(
            pageBackdrop = BackdropKind.fromKey(pageBackdrop),
            pageImage = pageImage.orEmpty(),
            backdropOpacity = backdropOpacity,
            cardMaterial = CardMaterial.fromKey(cardMaterial),
            timetableBackdrop = BackdropKind.fromKey(timetableBackdrop),
            timetableColor = timetableColor,
            timetableImage = timetableImage.orEmpty(),
            timetableOpacity = timetableOpacity,
            extractedColors = decodeExtracted(extracted)
        )
    }
}
