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
    /** 渐变强度百分比：100 = 设计值（默认，与首次实现逐像素一致），0 = 等于纯色，200 = 最深。 */
    val gradientStrength: Int = 100,
    /** 页面固定背景色（[BackdropKind.COLOR] 用）；0 = 未选。 */
    val pageColor: Int = 0,
    /**
     * 渐变跟随内容滚动（维护者口径 8.2.0 §7.5）：
     * 关（默认）= 整条渐变正好一屏，颜色变化快；开 = 渐变铺满数屏内容，
     * 每屏只截取一小段，于是颜色变化更慢更缓和。默认关 = 现状逐像素不变。
     */
    val gradientFollowsContent: Boolean = false,
    /** 自选渐变配色（顶色 / 底色）；0 = 跟随主题派生。 */
    val gradientTop: Int = 0,
    val gradientBottom: Int = 0,
    val cardMaterial: CardMaterial = CardMaterial.TONAL,
    val timetableBackdrop: BackdropKind = BackdropKind.THEME,
    val timetableColor: Int = 0,
    val timetableImage: String = "",
    val timetableOpacity: Int = 100,
    val extractedColors: List<Long> = emptyList()
) {
    /** 背景图不透明度换算成 0..1，越界读数夹回合法区间。 */
    val imageAlpha: Float get() = backdropOpacity.coerceIn(0, 100) / 100f

    /** 渐变强度换算成倍率（0..2）。 */
    val gradientScale: Float get() = gradientStrength.coerceIn(0, 200) / 100f

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

    /** 页面选了固定背景色，且确实生效（选了色或非默认外观）。 */
    val pageUsesColor: Boolean
        get() = pageBackdrop == BackdropKind.COLOR

    /**
     * 引用了已不存在的图片时降级为「跟随主题」（纯函数，[exists] 由调用方注入便于单测）。
     *
     * 用在"应用预设"这条路径上：预设里记着当时那张背景图，用户后来把图删了，
     * 直接套用就会出现"模式是图片、文件却没了"的空窗。与「移除图片」按钮同口径，
     * 统一退回跟随主题，不抛错、不清任何文件。
     */
    fun withExistingImages(exists: (String) -> Boolean): AppearanceSpec {
        var result = this
        if (pageBackdrop == BackdropKind.IMAGE && pageImage.isNotBlank() && !exists(pageImage)) {
            result = result.copy(pageBackdrop = BackdropKind.THEME, pageImage = "")
        }
        if (timetableBackdrop == BackdropKind.IMAGE && timetableImage.isNotBlank() && !exists(timetableImage)) {
            result = result.copy(timetableBackdrop = BackdropKind.THEME, timetableImage = "")
        }
        return result
    }

    /** 一句话概括这套外观（预设列表里显示"这套预设带了什么"）。 */
    fun summary(): String {
        val background = when (pageBackdrop) {
            BackdropKind.THEME -> "标准底色"
            BackdropKind.GRADIENT -> buildString {
                append("渐变 ").append(gradientStrength).append('%')
                if (gradientTop != 0 || gradientBottom != 0) append("·自选色")
                if (gradientFollowsContent) append("·跟随内容")
            }
            BackdropKind.COLOR -> "固定底色"
            BackdropKind.IMAGE -> "图片底 " + backdropOpacity + '%'
        }
        return buildList {
            add(background)
            if (cardMaterial != CardMaterial.TONAL) add("卡片" + cardMaterial.label())
            if (timetableBackdrop != BackdropKind.THEME) add("课表底色")
        }.joinToString("·")
    }

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
            gradientStrength: Int,
            pageColor: Int,
            gradientFollowsContent: Boolean,
            gradientTop: Int,
            gradientBottom: Int,
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
            gradientStrength = gradientStrength,
            pageColor = pageColor,
            gradientFollowsContent = gradientFollowsContent,
            gradientTop = gradientTop,
            gradientBottom = gradientBottom,
            cardMaterial = CardMaterial.fromKey(cardMaterial),
            timetableBackdrop = BackdropKind.fromKey(timetableBackdrop),
            timetableColor = timetableColor,
            timetableImage = timetableImage.orEmpty(),
            timetableOpacity = timetableOpacity,
            extractedColors = decodeExtracted(extracted)
        )
    }
}
