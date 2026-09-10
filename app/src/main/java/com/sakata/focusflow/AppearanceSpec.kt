package com.sakata.focusflow

/**
 * 页面渐变的方向（维护者口径：背景渐变应该可以指定方向）。
 *
 * 默认 [TOP_DOWN] = 现状逐像素不变（老装机读不到这个键就是原来的样子）。
 * 方向只影响"从哪一端开始铺"，三站颜色的顺序不变。
 */
internal enum class GradientDirection(val storageKey: String, val label: String) {
    TOP_DOWN("topdown", "上→下"),
    BOTTOM_UP("bottomup", "下→上"),
    LEFT_RIGHT("leftright", "左→右"),
    RIGHT_LEFT("rightleft", "右→左");

    companion object {
        fun fromKey(key: String?): GradientDirection =
            entries.firstOrNull { it.storageKey == key } ?: TOP_DOWN
    }
}

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
    val extractedColors: List<Long> = emptyList(),
    /**
     * 丰富的动画与外观效果（维护者口径 8.2.0）。
     *
     * 默认 **true = 保持现状**，老装机读不到这个键就等于现在的样子。
     * 关掉之后只保留最基本的渲染：背景退成纯色（渐变/图片/课表底图都不画）、
     * 卡片材质回落成默认纯色卡片、各种装饰性动画与底栏形变走最简形态。
     * 存在的理由：这些效果是逐帧的绘制/动画成本，低端机上会吃掉流畅度，
     * 需要一个"我要流畅，不要花哨"的开关——而不是逼用户去猜某个具体档位。
     */
    val richEffects: Boolean = true,
    /** 页面渐变方向；默认上→下（与首次实现逐像素一致）。 */
    val gradientDirection: GradientDirection = GradientDirection.TOP_DOWN
) {
    /** 背景图不透明度换算成 0..1，越界读数夹回合法区间。 */
    val imageAlpha: Float get() = backdropOpacity.coerceIn(0, 100) / 100f

    /** 渐变强度换算成倍率（0..[GRADIENT_STRENGTH_MAX]/100）。 */
    val gradientScale: Float get() = gradientStrength.coerceIn(0, GRADIENT_STRENGTH_MAX) / 100f

    val timetableAlpha: Float get() = timetableOpacity.coerceIn(0, 100) / 100f

    /**
     * 实际要画的页面背景档位：关掉丰富效果时，除了"固定颜色"以外的花哨档位
     * （主题渐变 / 自选图片）一律回落成 [BackdropKind.THEME]。
     *
     * 保留固定颜色是有意的：那只是一块纯色填充，几乎没有绘制成本，
     * 而且是用户明确选过的"页面主色"，砍掉反而像是把设置弄丢了。
     */
    val effectivePageBackdrop: BackdropKind
        get() = if (richEffects) pageBackdrop else when (pageBackdrop) {
            BackdropKind.COLOR -> BackdropKind.COLOR
            else -> BackdropKind.THEME
        }

    /** 实际要画的课表底色档位：关掉丰富效果时只保留"跟随主题"。 */
    val effectiveTimetableBackdrop: BackdropKind
        get() = if (richEffects) timetableBackdrop else BackdropKind.THEME

    /** 实际要用的卡片材质：关掉丰富效果时回落成默认纯色卡片。 */
    val effectiveCardMaterial: CardMaterial
        get() = if (richEffects) cardMaterial else CardMaterial.TONAL

    /** 页面此刻是否真的有一张可画的背景图。 */
    val hasPageImage: Boolean
        get() = effectivePageBackdrop == BackdropKind.IMAGE && pageImage.isNotBlank() && backdropOpacity > 0

    /** 课表此刻是否真的有一张可画的背景图。 */
    val hasTimetableImage: Boolean
        get() = effectiveTimetableBackdrop == BackdropKind.IMAGE && timetableImage.isNotBlank() && timetableOpacity > 0

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

    /**
     * 「渐变跟随内容」当前到底怎么铺。
     *
     * 真机上光看开关的开关状态判断不出效果（dump 读 Switch 不稳、只能靠像素反推），
     * 所以把结论做成一句可以直接截图的文字，放在开关下面。
     */
    fun gradientSpanLabel(): String =
        if (gradientFollowsContent) "跟随内容（渐变铺满整段内容，每屏只走一小段）" else "固定一屏（整条渐变正好一屏）"

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
            extracted: String?,
            richEffects: Boolean = true,
            gradientDirection: String? = null
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
            extractedColors = decodeExtracted(extracted),
            richEffects = richEffects,
            gradientDirection = GradientDirection.fromKey(gradientDirection)
        )
    }
}
