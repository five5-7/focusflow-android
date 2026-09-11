package com.sakata.focusflow

import androidx.compose.foundation.background
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 8.2.0 外观系统的**唯一渲染出口**（见 docs/8.2.0-appearance-plan.md）。
 *
 * 页面、卡片、弹窗、底栏、课表都从这里拿"这一层该怎么画"：底色／渐变／图片 + 主题遮罩。
 * 好处只有一个但很关键：对比度只在一处把守，参数只在一处调。
 *
 * 默认（[AppearanceSpec.DEFAULT]）与 8.1.1 **逐像素一致**：页面仍是纯色 background、
 * 卡片仍是容器色，[Modifier.appearanceBackdrop] 在默认外观下不新增任何绘制。
 */
internal val LocalAppearance = staticCompositionLocalOf { AppearanceSpec.DEFAULT }

/** 当前解码好的背景图（没设图或解码失败就是 null）。由应用根提供，深层页面不再各自解码。 */
internal val LocalBackdropBitmap = staticCompositionLocalOf<ImageBitmap?> { null }

/**
 * 页面层的**不透明**背景。
 *
 * 动画期间两层会同时在场（副页放大/缩小、主页左右平移、收起中的页签），每层必须自己是不透明的，
 * 否则会互相透出来——真机复现：主题渐变下副页放大时整屏像变透明了。
 * 默认外观下就是原来的纯色（逐像素不变）；非默认外观下每层**各画一遍**同一个背景。
 */
@Composable
internal fun Modifier.pageLayerBackground(flatColor: Color): Modifier {
    val appearance = LocalAppearance.current
    // 「渐变跟随内容」时渐变由滚动内容自己按内容高度铺（见 ScrollableWithBar），
    // 但**层本身仍必须不透明**——否则会退回 8.1.1 修过的"转场时两层互相透出来"。
    // 这里用主题页面底色兜底：内容会盖住它；内容比视口短时下方也是干净的页面底色，
    // 不会露出对不上的渐变。
    return if (appearance.effectivePageBackdrop == BackdropKind.THEME ||
        (appearance.effectivePageBackdrop == BackdropKind.GRADIENT && appearance.gradientFollowsContent)
    ) {
        background(flatColor)
    } else {
        appearanceBackdrop(
            appearance,
            MaterialTheme.colorScheme,
            LocalBackdropBitmap.current,
            // 按图片真实亮度决定遮罩厚度：固定厚度会让"浅图 + 低不透明度"把正文洗没。
            imageLuminance = rememberImageLuminance(LocalBackdropBitmap.current)
        )
    }
}

/** 页面容器色：跟随主题时就是原来的 background；选了渐变/图片就交给背景层去画（透明）。 */
@Composable
internal fun pageContainerColor(): Color =
    if (LocalAppearance.current.effectivePageBackdrop == BackdropKind.THEME) {
        MaterialTheme.colorScheme.background
    } else {
        Color.Transparent
    }

/**
 * 课表 / 日程表底板的卡片色。
 *
 * 选了底色或底图时让出容器色（透明），由 [Modifier.appearanceBackdrop] 的 Timetable 角色去画；
 * 跟随主题时保持原来的 surface —— 默认外观逐像素不变。
 */
@Composable
internal fun timetableContainerColor(): Color =
    if (LocalAppearance.current.effectiveTimetableBackdrop == BackdropKind.THEME) {
        MaterialTheme.colorScheme.surface
    } else {
        Color.Transparent
    }

/**
 * 在 sRGB 分量上按 [alpha] 把 [overlay] 混进 [base]。
 *
 * 显式实现（而不是直接用 Compose 的 `lerp`）是为了让混合系数能被单测和文档直接对上账；
 * 实测 Compose 的 `lerp` 在 sRGB 颜色上就是逐分量插值，二者结果一致。
 * 注意 [Color] 的分量是 0..1 的浮点，别和 0..255 的整数写法混用。
 */
internal fun blendSrgb(base: Color, overlay: Color, alpha: Float): Color {
    val a = alpha.coerceIn(0f, 1f)
    return Color(
        red = base.red + (overlay.red - base.red) * a,
        green = base.green + (overlay.green - base.green) * a,
        blue = base.blue + (overlay.blue - base.blue) * a,
        alpha = base.alpha
    )
}

/**
 * 渐变强度的可选上限（百分数）。100 = 设计值，0 = 纯色。
 *
 * 2026-09-10 由 200 提到 240（维护者口径「强度可选范围扩大一点」）。
 *
 * **这个数字是算出来的，不是拍的。** 强度直接乘在"顶亮/底深"的混色权重上，
 * 调大就会把最不利底色压穿。实测各档的"全主题 · 明暗"最低正文对比度：
 *
 * | 强度 | 最低对比度 | 最不利格子 |
 * |---|---|---|
 * | 200% | 8.07 | 海盐蓝·深色 |
 * | **240%** | **6.97** | 海盐蓝·深色（底站） |
 * | 250% | 6.68 | 同上 |
 * | 240% | 6.97 | 同上（差一点点，仍然不过 7:1） |
 * | 300% | 5.54 | 同上 |
 *
 * 上限取"仍能守住 7:1（AAA）的最大档"：240% 实测 6.972 仍差一点点，
 * 所以停在 225%。再往上就必须放宽
 * `AppearanceContrastMatrixTest.nonImageBackdropsStayAtSevenToOne` 这条预算，
 * 而那属于产品取舍，不该顺手改掉。
 */
internal const val GRADIENT_STRENGTH_MAX = 225

/** 主题渐变：按当前配色派生，不写死色值，自定义主题与深色模式自动跟着变。 */
internal object ThemeGradient {

    /**
     * 页面渐变：**顶亮 → 中为底色 → 底略深**，像一束光从上方打下来。
     *
     * 方向是刻意的：深色文字在浅底上对比度最高，所以"变亮"放在上面、"变深"放在下面，
     * 整条渐变里正文对比度都不低于纯色页面（真机实测见 docs/8.2.0-appearance-plan.md）。
     * 幅度也必须够大，否则会被看成"背景整体变深了一档"而不是渐变（维护者真机反馈过这一点）。
     *
     * [strength] 强度倍率：0 = 纯色，1 = 设计值，2 = 最深。
     *
     * 这里只负责"整条渐变正好一屏"。「渐变跟随内容」不经过本函数：那种模式下渐变由滚动
     * 内容自己按**内容高度**铺（`ScrollableWithBar`），视口这层在 [appearanceBackdrop] 里
     * 就提前返回了。早期那套"按累计滚动量截窗口"的实现（phase/window 两个参数）已经删除，
     * 它在生产路径上永远不可达；历史在 git `cfbfaab` 之前。
     */
    /**
     * [direction] 决定"从哪一端开始铺"（维护者口径：渐变应可指定方向，含斜向）。
     * 默认 [GradientDirection.TOP_DOWN] = 原来的 `Brush.verticalGradient`，逐像素不变。
     * 三站颜色的**顺序不变**，只是铺的方向不同。
     *
     * 斜向需要画布尺寸，所以这个重载只处理正方向的四种；斜向请用 [pageBrushFor]。
     */
    fun page(
        scheme: ColorScheme,
        strength: Float = 1f,
        top: Int = 0,
        bottom: Int = 0,
        direction: GradientDirection = GradientDirection.TOP_DOWN
    ): Brush {
        val stops = pageStops(scheme, strength, top, bottom)
        return when (direction) {
            GradientDirection.TOP_DOWN -> Brush.verticalGradient(stops)
            GradientDirection.BOTTOM_UP -> Brush.verticalGradient(stops.reversed())
            GradientDirection.LEFT_RIGHT -> Brush.horizontalGradient(stops)
            GradientDirection.RIGHT_LEFT -> Brush.horizontalGradient(stops.reversed())
            // 斜向落到这里说明调用方没给尺寸；退化成左上→右下（Compose 对
            // linearGradient 默认 start=Zero / end=Infinite 就是这条对角线）。
            GradientDirection.DIAGONAL_DOWN, GradientDirection.DIAGONAL_UP ->
                Brush.linearGradient(stops, start = Offset.Zero, end = Offset.Infinite)
        }
    }

    /** 卡片渐变：左上到右下，比页面更轻，保证卡片仍然"更亮一层"。 */
    fun card(scheme: ColorScheme): Brush = Brush.linearGradient(
        colors = cardStops(scheme),
        start = Offset.Zero,
        end = Offset.Infinite
    )

    /** 课表底板渐变：比页面更收，避免抢课表块。 */
    fun timetable(scheme: ColorScheme): Brush = Brush.verticalGradient(
        0f to blendSrgb(scheme.background, scheme.primary, 0.09f),
        1f to blendSrgb(scheme.background, scheme.primary, 0.02f)
    )

    fun pageStops(scheme: ColorScheme, strength: Float = 1f, top: Int = 0, bottom: Int = 0): List<Color> {
        val s = strength.coerceIn(0f, GRADIENT_STRENGTH_MAX / 100f)
        // 深色模式单独一套幅度（维护者提醒"注意适配深色模式"）：
        // 深色页面上"顶亮 85%"会变成一条刺眼亮带，而且深色模式的正文是浅色的，
        // 浅底会直接把可读性吃掉。所以深色下只做"顶上微微提亮、底下压深"。
        val dark = scheme.background.luminance() < 0.5f
        val rawTop = if (dark) {
            blendSrgb(scheme.background, Color.White, 0.10f * s)
        } else {
            blendSrgb(scheme.background, Color.White, 0.85f * s)
        }
        // 「顶亮」不能亮过卡片层 —— 否则**页面顶部比卡片还亮**，卡片在最上面会消失、
        // 往下才突然"浮"出来，看起来就是"渐变越暗的地方卡片反而越亮"。
        //
        // 实测（薄荷绿·浅色）：页面底色 #EAEEEB、卡片层 surfaceContainerLow #FAFCFB，
        // 而旧算式把顶站算成 #FCFCFC —— 比卡片还亮 2/255，两者几乎糊在一起；
        // 而底站是 #B0B3B0，卡片在那一端又亮得突兀。整页的"底/面"关系被翻转了。
        //
        // 修法：顶站一旦亮过卡片层，就压到"卡片层 → 页面底色"之间偏卡片的一侧，
        // 保证 页面 ≤ 卡片 始终成立（浅色模式下卡片永远是更亮的那一层）。
        val cardLayer = scheme.surfaceContainerLow
        val autoTop = if (!dark && rawTop.luminance() > cardLayer.luminance()) {
            blendSrgb(cardLayer, scheme.background, 0.35f)
        } else {
            rawTop
        }
        val autoBottom = if (dark) {
            blendSrgb(scheme.background, Color.Black, 0.25f * s)
        } else {
            blendSrgb(scheme.background, Color.Black, 0.07f * s)
        }
        // 自选渐变色（维护者要求）：选了什么就用什么；深色模式下先适配（见 adaptBackdropColor），
        // 强度继续作为"向页面底色回退"的倍率，于是"强度 = 0"在自选配色下依然是纯色。
        val chosenTop = if (top != 0) adaptBackdropColor(scheme, Color(top), s) else autoTop
        val chosenBottom = if (bottom != 0) adaptBackdropColor(scheme, Color(bottom), s) else autoBottom
        val middle = if (top != 0 || bottom != 0) blendSrgb(chosenTop, chosenBottom, 0.5f) else scheme.background
        return listOf(chosenTop, middle, chosenBottom)
    }

    /**
     * 自选渐变配色（顶色 → 底色）；选了以后 [pageStops] 就用它，不再按主题派生。
     *
     * 原先八组全是"接近白的浅色"，两两差别很小、真机上挑不出明显不同的效果
     * （维护者反馈"颜色可选范围扩大一点"）。现在扩到 13 组，并按"两端的色差"排序：
     * 前段是原来那批极浅的，中段加大明度落差，后段是**明显有色**的纸色。
     *
     * 深色模式下这一切仍然成立：两端都会经 [adaptBackdropColor] 以 0.22 权重压到深色底上，
     * 保留色相、整页仍是深色。深色模式需要的"深色渐变"由主题渐变按明暗各派生一套幅度。
     */
    internal val PAGE_GRADIENT_PAIRS: List<Pair<Int, Int>> = listOf(
        // —— 极浅纸色（原有八组，保持在前，老用户的位置不变）——
        0xFFFFFCF8.toInt() to 0xFFECE4DE.toInt(), // 暖白 → 暖灰
        0xFFF5F9FC.toInt() to 0xFFDEE8F0.toInt(), // 雾蓝 → 浅蓝
        0xFFF8FBF4.toInt() to 0xFFE2ECDC.toInt(), // 淡竹 → 浅竹
        0xFFFDF7F9.toInt() to 0xFFF3E2E8.toInt(), // 藕粉 → 浅粉
        0xFFFAF8FD.toInt() to 0xFFE8E3F2.toInt(), // 浅薰 → 淡紫
        0xFFFCF9F2.toInt() to 0xFFEEE5D6.toInt(), // 亚麻 → 燕麦
        0xFFF7FAFB.toInt() to 0xFFE2E7E9.toInt(), // 青灰 → 雾灰
        0xFFFFFAF0.toInt() to 0xFFF2E2CE.toInt(), // 晨曦 → 暖沙
        // —— 中段：加大明度落差，渐变看得更清楚 ——
        0xFFFFFDF7.toInt() to 0xFFDCD3C4.toInt(), // 素笺 → 灰卡
        0xFFFBFDFF.toInt() to 0xFFC9D8E4.toInt(), // 霜白 → 远山
        0xFFF6FBF3.toInt() to 0xFFC8DCC4.toInt(), // 新芽 → 苔痕
        // —— 后段：明显有色 / 略深，两端差得开 ——
        0xFFF3F6FA.toInt() to 0xFFB9C7D6.toInt(), // 铅灰 → 石青
        0xFFF7F1EE.toInt() to 0xFFCBB4A8.toInt()  // 陶土 → 赭石
        // 注意：这里**不放**"深色档"的配色（例如 墨夜→深空）。深色模式下所有自选端色
        // 都会被 adaptBackdropColor 以 0.22 权重压到深色底上，一个本来就很深的组合压完
        // 会和页面底色几乎重合（实测 rgb(22,24,27) vs 页面 rgb(18,20,23)）——等于选了个没反应。
        // 深色模式要的"深色渐变"由主题渐变自己派生（pageStops 在深色下另有一套幅度）。
    )

    fun cardStops(scheme: ColorScheme): List<Color> = listOf(
        blendSrgb(scheme.surfaceContainerLow, scheme.primary, 0.07f),
        blendSrgb(scheme.surfaceContainerLow, scheme.primary, 0.01f)
    )
}

/**
 * 把用户选的**浅色**适配到当前明暗（维护者提醒"注意适配深色模式"）。
 *
 * 浅色模式下原样使用；深色模式下按低权重压到深色底色上——保留用户选的色相，
 * 但整页仍然是深色，浅色的正文才读得清。预设色板与"固定背景色"都走这里。
 */
internal fun adaptBackdropColor(scheme: ColorScheme, chosen: Color, scale: Float = 1f): Color =
    if (scheme.background.luminance() < 0.5f) {
        blendSrgb(scheme.background, chosen, 0.22f * scale.coerceIn(0f, 1f))
    } else {
        blendSrgb(scheme.background, chosen, scale.coerceIn(0f, 1f))
    }

/** 背景层要画在哪一层。 */
internal enum class BackdropRole { Page, Timetable }

/**
 * 课表 / 日程表底色预设。
 *
 * 刻意全部取"很浅的中性纸色"：课表上压着格线、节次小字和各色课程块，
 * 底色一旦偏深就会和它们打架。单测逐个校验它们与浅色主题的副文本色对比度达标。
 */
internal val TIMETABLE_BASE_PRESETS: List<Int> = listOf(
    0xFFF2ECE8.toInt(), // 暖纸
    0xFFEFF2F5.toInt(), // 冷纸
    0xFFF3F0EA.toInt(), // 沙
    0xFFEDF3EF.toInt(), // 薄荷
    0xFFF2EEF6.toInt(), // 丁香
    0xFFF0F1F3.toInt(), // 石墨
    0xFFEAF0F4.toInt(), // 天青
    0xFFF5F0EE.toInt()  // 玫瑰
)

/**
 * 页面固定背景色预设（8.2.0 §7.5）。
 *
 * 比课表底色允许更有个性一点（页面不被格线压着），但仍然全部是浅色，
 * 保证正文（深色）对比度达标——单测逐个校验。
 */
internal val PAGE_BASE_PRESETS: List<Int> = listOf(
    0xFFF6F1EC.toInt(), // 米白
    0xFFEFF3F6.toInt(), // 雾蓝
    0xFFF2F5EF.toInt(), // 淡竹
    0xFFF7F0F2.toInt(), // 藕粉
    0xFFF1F0F6.toInt(), // 浅薰
    0xFFF6F3EA.toInt(), // 亚麻
    0xFFEDF2F3.toInt(), // 青灰
    0xFFF4EFEA.toInt()  // 燕麦
)

/** 课表底色是否压得住格线与小字（浅色主题的副文本色为参照）。 */
internal fun timetableBaseIsReadable(base: Int, textColor: Int = 0xFF44565B.toInt()): Boolean =
    AppearanceContrast.passes(textColor, base)

/**
 * 在当前内容之下画背景层。
 *
 * 只有非默认外观才会画东西：`THEME` 时原样返回，不新增任何绘制节点，
 * 因此"默认外观不变"是结构上成立的，不靠调参。
 */
internal fun Modifier.appearanceBackdrop(
    spec: AppearanceSpec,
    scheme: ColorScheme,
    bitmap: ImageBitmap?,
    role: BackdropRole = BackdropRole.Page,
    /**
     * 背景图的平均亮度（0..1），由 [rememberImageLuminance] 采样得到。
     *
     * 默认值分角色给：**页面**取 0.5（"未知"时的保守中点），**课表底板**取 1f。
     * 课表取 1f 是刻意的：`AppearanceContrastMatrixTest` 里课表/日程表那一档的对比度
     * 是拿 `scrimAlpha`（不看图片亮度）算出来的既定预算，传 1f 正好让
     * `adaptiveScrimAlpha` 退化成与 `scrimAlpha` 完全相同的结果，**不会悄悄改掉已验证的不变量**。
     * 课表底图要按真实亮度自适应，得连那张表一起重算，属于独立改动。
     */
    imageLuminance: Float = if (role == BackdropRole.Timetable) 1f else 0.5f
): Modifier {
    val backdrop = when (role) {
        // 用 effective*：关掉「丰富效果」时渐变/图片一律回落成主题纯色
        // （固定颜色例外，它只是一块纯色填充，几乎没有绘制成本）。
        BackdropRole.Page -> spec.effectivePageBackdrop
        BackdropRole.Timetable -> spec.effectiveTimetableBackdrop
    }
    if (backdrop == BackdropKind.THEME) return this
    // 全透明：这层什么都不画，页面背景原样透出（容器色那边已经让成透明）。
    if (backdrop == BackdropKind.TRANSPARENT) return this
    // 「渐变跟随内容」时视口这层不画渐变：改由滚动内容自己按内容高度铺（见 ScrollableWithContainers），
    // 否则内容比视口短时会在下方露出一条对不上的固定渐变。
    if (backdrop == BackdropKind.GRADIENT && role == BackdropRole.Page && spec.gradientFollowsContent) return this
    val alpha = when (role) {
        BackdropRole.Page -> spec.imageAlpha
        BackdropRole.Timetable -> spec.timetableAlpha
    }
    val picked = when (role) {
        BackdropRole.Timetable -> spec.timetableColor
        // 8.2.0 §7.5：页面也能选固定背景色了（0 = 未选时退回按主题派生的浅色）。
        BackdropRole.Page -> spec.pageColor
    }
    return drawBehind {
        when (backdrop) {
            BackdropKind.GRADIENT -> drawRect(
                if (role == BackdropRole.Timetable) {
                    ThemeGradient.timetable(scheme)
                } else {
                    // 走 pageBrushFor：斜向需要画布尺寸，而这里（drawBehind 内）正好有 size。
                    pageBrushFor(
                        spec.gradientDirection,
                        ThemeGradient.pageStops(scheme, spec.gradientScale, spec.gradientTop, spec.gradientBottom),
                        size
                    )
                }
            )

            BackdropKind.COLOR -> {
                val auto = blendSrgb(scheme.background, scheme.primary, 0.10f)
                val base = adaptBackdropColor(scheme, if (picked != 0) Color(picked) else auto)
                drawRect(Brush.verticalGradient(listOf(blendSrgb(base, Color.White, 0.06f), base)))
            }

            BackdropKind.IMAGE -> {
                drawRect(ThemeGradient.page(scheme))
                if (bitmap != null && alpha > 0f) {
                    drawImageCover(bitmap, alpha)
                    // 图片之上永远压一层主题遮罩：正文对比度靠它保住（见 AppearanceContrast）。
                    // 厚度按图片实际明暗自适应——固定厚度会让"浅图@低不透明度"把正文洗没。
                    drawRect(
                        scheme.background.copy(
                            // textIsLight = 正文是浅色的（深色模式），此时亮图最危险。
                            alpha = adaptiveScrimAlpha(alpha, imageLuminance, scheme.onBackground.luminance() > 0.5f)
                        )
                    )
                }
            }

            BackdropKind.THEME -> Unit
            BackdropKind.TRANSPARENT -> Unit
        }
    }
}

/**
 * **已知画布尺寸**时的页面渐变画刷——斜向必须走这里。
 *
 * `verticalGradient` / `horizontalGradient` 不需要尺寸，但斜向需要知道画布宽高
 * 才能定出对角线的两端，所以绘制点必须用 `drawBehind` 这类能拿到 `size` 的地方，
 * 而不是 `Modifier.background(brush)`。三个绘制点（根背景、跟随内容、预览块）都走这个函数。
 */
internal fun pageBrushFor(
    direction: GradientDirection,
    stops: List<Color>,
    size: Size
): Brush = when (direction) {
    GradientDirection.TOP_DOWN -> Brush.verticalGradient(stops)
    GradientDirection.BOTTOM_UP -> Brush.verticalGradient(stops.reversed())
    GradientDirection.LEFT_RIGHT -> Brush.horizontalGradient(stops)
    GradientDirection.RIGHT_LEFT -> Brush.horizontalGradient(stops.reversed())
    // **斜向必须按 45° 取轴，不能"角对角"。**
    //
    // 维护者连续两轮反馈"对角渐变未实装 / 还是只是上下渐变"——原因就在这里：
    // 手机屏是 1440×3168（高是宽的 2.2 倍），角对角那条轴与竖直方向只差约 24°，
    // 于是横向只有约 41% 的色程，肉眼看起来就是一个**近乎竖直**的渐变。
    // 真正的"斜着来"要按 45° 定轴：轴长取矩形在 45° 方向上的投影 (w + h) / √2，
    // 起点/终点按中心 ± 半轴算，这样整条色程跨满屏幕，横向变化一眼可见。
    GradientDirection.DIAGONAL_DOWN -> diagonalBrush(stops, size, downRight = true)
    GradientDirection.DIAGONAL_UP -> diagonalBrush(stops, size, downRight = false)
}

/** 45° 斜向渐变的轴：中心 ± (轴长/2) · 单位方向。 */
private fun diagonalBrush(stops: List<Color>, size: Size, downRight: Boolean): Brush {
    val cx = size.width / 2f
    val cy = size.height / 2f
    // 45° 单位向量
    val ux = 0.70710678f
    val uy = 0.70710678f
    // 矩形在 45° 轴上的投影长度，保证整条色程覆盖整块画布
    val axis = (size.width + size.height) * 0.5f * 1.41421356f
    val half = axis / 2f
    // 起点恒在 -方向 一侧：左上→右下 时起点是左上；左下→右上 时起点是左下。
    val sx = -ux
    val sy = if (downRight) -uy else uy
    return Brush.linearGradient(
        colors = stops,
        start = Offset(cx + sx * half, cy + sy * half),
        end = Offset(cx - sx * half, cy - sy * half)
    )
}

/**
 * 渐变在**归一化位置** `(fx, fy)` 处的颜色（左上为原点，两者都在 0..1）。
 *
 * 存在的理由：悬浮在页面之上的东西（底栏、以及以后别的浮层）不能拿"固定主题色"去配，
 * 因为页面渐变让它背后的底色**随位置变化**——顶亮底深时底栏正好压在最暗的一段上，
 * 于是固定色的底栏就显得偏亮（维护者反馈）。
 *
 * 这里按方向把 (fx, fy) 投影到渐变轴上，再在三站之间做与 Compose 一致的分段线性插值
 * （sRGB 逐分量），于是**任何方向、任何配色**都能问出"这一点背后是什么颜色"，
 * 不需要为某种方向单独写一套。
 */
internal fun gradientColourAt(
    direction: GradientDirection,
    stops: List<Color>,
    fx: Float,
    fy: Float
): Color {
    if (stops.isEmpty()) return Color.Unspecified
    if (stops.size == 1) return stops[0]
    val x = fx.coerceIn(0f, 1f)
    val y = fy.coerceIn(0f, 1f)
    // t 沿渐变轴：0 = 起点站，1 = 终点站
    val t = when (direction) {
        GradientDirection.TOP_DOWN -> y
        GradientDirection.BOTTOM_UP -> 1f - y
        GradientDirection.LEFT_RIGHT -> x
        GradientDirection.RIGHT_LEFT -> 1f - x
        // 轴 (0,0)→(1,1)：投影 = (x + y) / 2
        GradientDirection.DIAGONAL_DOWN -> (x + y) / 2f
        // 轴 (0,1)→(1,0)：投影 = (x - y + 1) / 2
        GradientDirection.DIAGONAL_UP -> (x - y + 1f) / 2f
    }.coerceIn(0f, 1f)
    val scaled = t * (stops.size - 1)
    val index = scaled.toInt().coerceIn(0, stops.size - 2)
    return blendSrgb(stops[index], stops[index + 1], scaled - index)
}

/**
 * 浮层（底栏）在页面渐变之下该用什么颜色。
 *
 * 做法：取浮层**背后那一点**的渐变色，再叠上主题原本设计好的"浮层相对页面底色"的差值。
 * 这样"底栏比页面暗一档"这个设计关系**在任何渐变、任何方向、任何明暗下都保持不变**，
 * 而不是只对"顶亮底深 + 底栏在底部"这一种情况打补丁。
 *
 * [flatNav] / [flatBackground] 是主题给的固定值，两者之差就是那层设计关系。
 */
internal fun floatingSurfaceOverGradient(
    base: Color,
    deltaFrom: Color,
    deltaTo: Color
): Color = Color(
    red = (base.red + (deltaTo.red - deltaFrom.red)).coerceIn(0f, 1f),
    green = (base.green + (deltaTo.green - deltaFrom.green)).coerceIn(0f, 1f),
    blue = (base.blue + (deltaTo.blue - deltaFrom.blue)).coerceIn(0f, 1f),
    alpha = base.alpha
)

/** 底栏在屏幕上的归一化中心（底部居中，取实测的 93% 高度处）。 */
internal const val NAV_BAR_CENTRE_Y = 0.93f
internal const val NAV_BAR_CENTRE_X = 0.5f

/**
 * 「跟随内容」时渐变一共铺多少屏（与 `GRADIENT_SCROLL_SPAN` 同一口径）。
 *
 * 这个模式下渐变铺在**滚动内容自己的高度**上（内容比视口长），
 * 所以视口底部那一点对应的归一化位置要按这个跨度折算，而不是按"一屏"。
 */
internal const val FOLLOWS_CONTENT_SPAN = 3.2f

/**
 * 页面背景此刻在**任意归一化位置** `(fx, fy)` 的颜色（非渐变档就是页面底色）。
 *
 * 这是"浮层取色"的唯一入口：把"该在哪取色"和"页面到底是什么背景"分开，
 * 于是浮层不需要知道下面用的是渐变/图片/纯色，也不需要按方向写分支。
 */
internal fun backdropColourAt(
    appearance: AppearanceSpec,
    scheme: ColorScheme,
    fx: Float,
    fy: Float
): Color = when (appearance.effectivePageBackdrop) {
    BackdropKind.GRADIENT -> {
        // 「跟随内容」时渐变铺在**滚动内容自己的高度**上（约 FOLLOWS_CONTENT_SPAN 屏），
        // 视口里的 fy 要先折算到那条长渐变上的位置。
        val span = if (appearance.gradientFollowsContent) FOLLOWS_CONTENT_SPAN else 1f
        gradientColourAt(
            appearance.gradientDirection,
            ThemeGradient.pageStops(
                scheme, appearance.gradientScale, appearance.gradientTop, appearance.gradientBottom
            ),
            fx,
            fy / span
        )
    }
    else -> scheme.background
}

/**
 * 底栏该用的**画刷**——**目前恒返回 null，即底栏一律走 Surface 的单色填充路径**。
 *
 * 为什么停用（如实记录，别急着再打开）：
 * 为了表达左右渐变，曾让底栏在自己身上画一层水平渐变（并把 Surface 底色置透明）。
 * 真机截图（`v3_sched.png`）逐像素量下来，底栏变成了**三层**：
 * 外圈暗带 `rgb(176..191)`、内层亮胶囊 `x=162..1277 / y=2728..3035` `rgb(218,230,225)`、
 * 而且 x=80..97 与 x=1342..1359 各有一条**18px 纯白带**（背景透出来的）。
 * 也就是自绘的那层没有与 Surface 的形状/尺寸对齐，看起来就是"中间留了个胶囊状空白"。
 *
 * 结论：**单色 + Surface 自身绘制**是唯一被验证过的可靠路径；
 * 左右渐变这个能力要有，但必须先解决"自绘层与 Surface 层如何对齐"，而不是继续在这条路上打补丁。
 * 在解决之前，宁可接受"底栏不跟随左右渐变"，也不要一个视觉坏掉的底栏。
 */
internal fun navBarBrushOverBackdrop(
    appearance: AppearanceSpec,
    themeSpec: FocusFlowThemeSpec
): Brush? {
    // 只有页面**真的是渐变**时才有"左右"可言。跟随主题／固定颜色／图片都没有横向色程，
    // 返回 null 让底栏走原来的纯色路径 —— 那几个档位因此逐像素不变。
    if (appearance.effectivePageBackdrop != BackdropKind.GRADIENT) return null
    val scheme = themeSpec.colorScheme
    val span = if (appearance.gradientFollowsContent) FOLLOWS_CONTENT_SPAN else 1f
    val fy = NAV_BAR_CENTRE_Y / span
    // 沿底栏横向等距采 9 个点，每点取"页面在该点的颜色，再叠加导航栏色系相对页面底色的偏移"，
    // 与 navBarColourOverBackdrop 同一套口径（中心点 fx = NAV_BAR_CENTRE_X 处两者必然相等，
    // 所以换成画刷不会在中间接出一道缝）。
    //
    // 维护者口径：「不要只针对这一种情况打补丁」——所以这里是**逐点采样**，
    // 六个方向（含斜向）全都自动跟着走，不需要按方向写分支。
    val segments = 8
    val stops = (0..segments).map { i ->
        floatingSurfaceOverGradient(
            base = backdropColourAt(appearance, scheme, i.toFloat() / segments, fy),
            deltaFrom = scheme.background,
            deltaTo = themeSpec.navigationBarColor
        )
    }
    return Brush.horizontalGradient(stops)
}

/**
 * 底栏带的**中心色**（供需要单一颜色的地方及单测使用）。
 *
 * 真正的绘制请用 [navBarBrushOverBackdrop]——它才表达得出左右渐变。
 */
internal fun navBarColourOverBackdrop(
    appearance: AppearanceSpec,
    themeSpec: FocusFlowThemeSpec
): Color = floatingSurfaceOverGradient(
    base = backdropColourAt(
        appearance, themeSpec.colorScheme, NAV_BAR_CENTRE_X, NAV_BAR_CENTRE_Y /
            (if (appearance.gradientFollowsContent) FOLLOWS_CONTENT_SPAN else 1f)
    ),
    deltaFrom = themeSpec.colorScheme.background,
    deltaTo = themeSpec.navigationBarColor
)

/** 图片不透明度越高，遮罩越厚；0.34–0.78 之间，既有图感又保得住文字。 */internal fun scrimAlpha(imageAlpha: Float): Float = 0.34f + 0.44f * imageAlpha.coerceIn(0f, 1f)

/**
 * 按**图片实际明暗**决定的遮罩厚度。
 *
 * 为什么需要它：原先的 [scrimAlpha] 只跟"不透明度"有关，完全不看图片内容。
 * 于是"浅色图 + 低不透明度"会把整页洗白，而深色模式的正文是浅色的——
 * 真机实测：白图 @1% 时正文对底色只有 **1.80:1**、@50% **3.44:1**，完全读不清
 * （维护者反馈"淡色底浅色字看不见"）。
 *
 * 判别口径是**正文是深还是浅**（[textIsLight]），不是"页面是深还是浅"。
 * 道理：正文是浅色的，就需要它背后是暗的 → **图片越亮越危险**，遮罩要更厚；
 * 正文是深色的，需要背景是亮的 → 图片越暗越危险。
 * （一开始我把这条写反了，写成"页面越深越危险"，单测当场抓住：
 * 深色模式 + 亮图时遮罩丝毫没加厚。）
 *
 * 下限 [scrimAlpha] 保持不变，所以原来的观感只会更清楚、不会更花。
 */
internal fun adaptiveScrimAlpha(imageAlpha: Float, imageLuminance: Float, textIsLight: Boolean): Float {
    val base = scrimAlpha(imageAlpha)
    val lum = imageLuminance.coerceIn(0f, 1f)
    // 正文浅 → 亮图危险（risky = lum）；正文深 → 暗图危险（risky = 1 - lum）。
    val risky = if (textIsLight) lum else 1f - lum
    // 图片几乎不可见时谈不上风险，用 alpha 加权，n=0 时严格等于旧值。
    val weight = imageAlpha.coerceIn(0f, 1f)
    return (base + (1f - base) * risky * weight).coerceIn(0f, 0.98f)
}

/**
 * 把一张背景图取色成"平均亮度"（0..1）。
 *
 * 最多采 32×32 个点：这是每帧要用的量，不能遍历整张原图（1440×3168 会有 450 万个像素）。
 * 采样点均匀铺开，所以即使图很大也只需要约一千次读数。
 */
internal fun averageLuminance(pixels: IntArray, width: Int, height: Int): Float {
    if (width <= 0 || height <= 0 || pixels.isEmpty()) return 0f
    val step = maxOf(1, minOf(width, height) / 32)
    var sum = 0.0
    var n = 0
    var y = 0
    while (y < height) {
        var x = 0
        while (x < width) {
            val p = pixels[y * width + x]
            val r = ((p shr 16) and 0xFF) / 255f
            val g = ((p shr 8) and 0xFF) / 255f
            val b = (p and 0xFF) / 255f
            // 与 AppearanceContrast.luminance 同一套权重（相对亮度，不是简单平均）
            sum += (0.2126f * srgbToLinear(r) + 0.7152f * srgbToLinear(g) + 0.0722f * srgbToLinear(b))
            n++
            x += step
        }
        y += step
    }
    return if (n == 0) 0f else (sum / n).toFloat().coerceIn(0f, 1f)
}

private fun srgbToLinear(v: Float): Float =
    if (v <= 0.03928f) v / 12.92f else Math.pow(((v + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()

/**
 * 取一张背景图的平均亮度，**结果按位图缓存**。
 *
 * 缓存是必需的：`appearanceBackdrop` 挂在 `drawBehind` 之前、每次重组都会求值，
 * 而读像素是 O(n) 的操作。同一张图只采一次，换图才重算。
 * 用 `ImageBitmap` 本身当 key：它是个有身份的对象，换图必然是不同实例。
 */
@Composable
internal fun rememberImageLuminance(bitmap: ImageBitmap?): Float =
    remember(bitmap) { bitmap?.let { sampleLuminance(it) } ?: 0.5f }

private fun sampleLuminance(bitmap: ImageBitmap): Float = runCatching {
    val w = bitmap.width
    val h = bitmap.height
    if (w <= 0 || h <= 0) return 0.5f
    // 整张读进来再按步长跳采。读的是已经降采样过的背景图（≤1440×3168），
    // 一次分配 + 一次读比"按块多次 readPixels"简单得多，而且不会漏掉图的任意一角
    // （只读左上角的话，一张"上半白下半黑"的图会被判成全白，遮罩算错）。
    val pixels = IntArray(w * h)
    bitmap.readPixels(pixels, startX = 0, startY = 0, width = w, height = h)
    averageLuminance(pixels, w, h)
}.getOrDefault(0.5f)

/** 背景图按"保持比例、居中裁切"铺满：算源矩形，目标永远是整块画布（不会出现负偏移）。 */
private fun DrawScope.drawImageCover(bitmap: ImageBitmap, alpha: Float) {
    val srcW = bitmap.width
    val srcH = bitmap.height
    if (srcW <= 0 || srcH <= 0) return
    val targetRatio = size.width / size.height
    val srcRatio = srcW.toFloat() / srcH.toFloat()
    val cropW: Int
    val cropH: Int
    if (srcRatio > targetRatio) {
        cropH = srcH
        cropW = (srcH * targetRatio).roundToInt().coerceIn(1, srcW)
    } else {
        cropW = srcW
        cropH = (srcW / targetRatio).roundToInt().coerceIn(1, srcH)
    }
    drawImage(
        image = bitmap,
        srcOffset = IntOffset((srcW - cropW) / 2, (srcH - cropH) / 2),
        srcSize = IntSize(cropW, cropH),
        dstOffset = IntOffset.Zero,
        dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
        alpha = alpha,
        filterQuality = FilterQuality.Medium
    )
}

/**
 * 卡片材质对应的画刷（在卡片底色之上叠的那一层）。
 *
 * [CardMaterial.TONAL] 返回 null（= 直接用原来的容器色卡片）。
 *
 * 柔光**必须有可见的填充变化**：它曾经和已删除的「纸感」一起 `-> null`，
 * 结果两者渲染完全相同、柔光本身也看不出变化（真机反馈"柔光和纸感似乎是一样的"）。
 * 现在走 [materialBrush] 的柔光底（顶面提亮 → 底部微沉）。
 */
@Composable
internal fun cardMaterialBrush(material: CardMaterial): Brush? =
    materialBrush(material, MaterialTheme.colorScheme.surfaceContainerLow, MaterialTheme.colorScheme)
/**
 * 材质叠层（通用）：给定**底色**，返回该材质要在它上面画的一层；[CardMaterial.TONAL] 返回 null。
 *
 * 抽成"给定底色"而不是写死 `surfaceContainerLow`，是因为同一套材质还要用在
 * **底栏**、**弹窗**上——它们的底色分别是 `navigationBarColor` / `surfaceContainerHigh`，
 * 都不是卡片色。维护者口径：「材质也影响导航栏」「弹窗也没有材质渲染」。
 * 卡片、底栏、弹窗都从这里取，只有一份实现。
 */
internal fun materialBrush(
    material: CardMaterial,
    base: Color,
    scheme: ColorScheme,
    /** 柔光渐变方向：false = 上→下（顶亮底沉），true = 下→上。维护者口径的两个方向。 */
    softReversed: Boolean = false
): Brush? =
    when (material) {
        CardMaterial.TONAL -> null
        // 7% → 14%：维护者口径「材质都没做好（做出区别）」。
        // 原来 7% 与柔光放在一起都是"平滑竖渐变"，一眼分不出；
        // 现在渐变是**明显的颜色晕染**（顶端比底色偏 23 级、一路衰减到底），
        // 而柔光是**亮度斜坡、色相不变**——"变色"与"打光"的区别，两档不会再混。
        CardMaterial.GRADIENT -> Brush.verticalGradient(listOf(blendSrgb(base, scheme.primary, 0.14f), base))
        CardMaterial.SOFT -> softLightBrush(base, softReversed)
        CardMaterial.FROSTED -> frostedBrush(base, softReversed)
        CardMaterial.ACRYLIC -> acrylicBrush(base, scheme, softReversed)
    }

/**
 * 材质的内描边（"玻璃的边"）。
 *
 * **这是毛玻璃能不能读出"玻璃"的关键。** 维护者反馈「毛玻璃没有玻璃效果」——
 * 原因不是不透明度，而是：页面底色是**平滑渐变**时，半透明本身**看不出来**
 * （背后是平滑的，透过去还是那片平滑）。真正让人读出玻璃的，是那**一圈被光照亮的边**
 * —— 玻璃的截面把光折过来，边总是比面亮。
 *
 * 返回 0 = 该材质不画边（默认/渐变/柔光/亚克力都不画，各靠自己的形态立住）。
 */
internal fun materialRimWidthDp(material: CardMaterial): Float = when (material) {
    CardMaterial.FROSTED -> 1.5f
    else -> 0f
}

/** 内描边的颜色；null = 不画。见 [materialRimWidthDp]。 */
internal fun materialRimColor(material: CardMaterial, base: Color): Color? = when (material) {
    CardMaterial.FROSTED -> shiftGreyLevels(base, 14f).copy(alpha = 0.9f)
    else -> null
}

/**
 * 毛玻璃的高光带宽（占卡面高度的比例）。
 *
 * 毛玻璃与柔光的区别**不在幅度、在剖面**：柔光是上下对称的均匀斜坡，
 * 毛玻璃是"高光集中在顶部边缘、其余部分平缓回落"——真实的玻璃边正是这样。
 * 见 [frostedStops]。
 */
internal const val FROSTED_BAND = 0.18f

/**
 * 毛玻璃的三个站点（带**位置**，因为它不是等距铺的）。
 *
 * 亮度中性仍然要守（T-1 的教训：整块净暗会看起来"曲线反了"）：
 * 高光带只占 [FROSTED_BAND] 的高度，剩下 `1 - FROSTED_BAND` 的高度用一点点压深
 * 把面积补回来，即 `a · band / 2 = b · (1 - band) / 2` ⇒ `b = a · band / (1 - band)`。
 * 于是高光是窄而亮的、压深是宽而浅的——这正是"光泽面"与"整体变暗"的区别。
 */
internal fun frostedStops(base: Color, reversed: Boolean = false): List<Pair<Float, Color>> {
    val top = shiftGreyLevels(base, softLightAmplitude(base))
    // **按实际涨幅镜像**（与 softLightStops 同一个道理）：
    // 近白底片的顶站会被纯白夹住，实际涨幅小于 softLightAmplitude；
    // 压深必须照着"实际涨了多少"按面积守恒补回来，否则夹紧就会变成整块净暗。
    val k = FROSTED_BAND / (1f - FROSTED_BAND)
    val bottom = Color(
        red = base.red - (top.red - base.red) * k,
        green = base.green - (top.green - base.green) * k,
        blue = base.blue - (top.blue - base.blue) * k,
        alpha = base.alpha
    )
    // **半透明才是毛玻璃的本体**：不透明的话底下页面根本透不上来，
    // 那它只是"另一种渐变"，跟柔光分不开（维护者："可以强化一下亚克力和柔光的不同"，毛玻璃同理）。
    // 0.55 的白纱：背后看得见，正文对比度又不会被吃掉。
    // 配套：`FocusCard.cardMaterialFill` 对毛玻璃**不铺不透明底**，
    // 否则这层纱下面仍然是卡片自己的底色，等于没透。
    val veil = 0.55f
    val stops = listOf(
        0f to top.copy(alpha = veil),
        FROSTED_BAND to base.copy(alpha = veil),
        1f to bottom.copy(alpha = veil)
    )
    // 反向 = 把位置镜像过来（高光跑到下边缘），而不是换一组颜色。
    return if (reversed) stops.map { (f, c) -> (1f - f) to c }.reversed() else stops
}

internal fun frostedBrush(base: Color, reversed: Boolean = false): Brush =
    Brush.verticalGradient(*frostedStops(base, reversed).toTypedArray())

/**
 * 亚克力：一整块**平**的哑光板 + 顶部极窄的一条环境光，并带一点主题染色。
 *
 * 与毛玻璃的区别是"平"：毛玻璃靠顶部高光做出光泽与厚度，亚克力几乎不做起伏，
 * 靠**染色**（往主题主色混 6%）与那道窄高光与默认材质区分开。
 * 染色会跟着主题走，这是亚克力的特征，也是它肉眼可辨的地方。
 */
internal fun acrylicStops(
    base: Color,
    scheme: ColorScheme,
    reversed: Boolean = false
): List<Pair<Float, Color>> {
    // 染色 6% → 10% → **16%**：维护者口径是「亚克力和**渐变**的差别有点小了」。
    // 渐变材质是"7% 主题色 → 底色"的平滑斜坡，平均浓度只有 3.5%；
    // 亚克力要读起来是"一整块亚克力板"而不是"另一种渐变"，所以浓度必须明显高出一档，
    // 而且是**平的**（不随高度衰减）——"板"与"晕染"的区别就在这里。
    val tinted = blendSrgb(base, scheme.primary, 0.16f)
    // 顶部那条**很窄**的亮线是与渐变最直观的第二个区别：
    // 渐变没有任何硬边，亚克力有一条锐利的玻璃边线（Fluent 亚克力的观感）。
    // 0.012 × 卡高 ≈ 9px（density 4），是一条看得清的发丝高光。
    val edge = shiftGreyLevels(tinted, 9f)
    // 亚克力也要**真的透光**（维护者：「亚克力不透光」）：0.78 的不透明度，
    // 比毛玻璃的 0.55 厚一些，读起来是"致密的塑料板"而不是"薄纱"。
    val veil = 0.78f
    val stops = listOf(
        0f to edge.copy(alpha = veil),
        0.012f to tinted.copy(alpha = veil),
        1f to tinted.copy(alpha = veil)
    )
    return if (reversed) stops.map { (f, c) -> (1f - f) to c }.reversed() else stops
}

internal fun acrylicBrush(base: Color, scheme: ColorScheme, reversed: Boolean = false): Brush =
    Brush.verticalGradient(*acrylicStops(base, scheme, reversed).toTypedArray())

/**
 * 把材质叠层画在**调用方自己的形状里**。
 *
 * 必须用 `clip(shape)` 再 `drawBehind`：起初底栏是直接 `drawBehind { drawRect(brush) }` 的，
 * 而 `drawBehind` 画在 Surface 的形状裁剪**之外**，于是底栏上出现了一整块矩形底色
 * （维护者反馈："用材质时悬浮栏会出现一块矩形底"）。这里统一裁到形状内，杜绝同一类错误。
 *
 * **还有一个更隐蔽的坑：`drawBehind` 排在 Material3 `Surface` 内部 `.background(color)`
 * 之前。** 所以把它挂在 `Surface(modifier = …)` 上时，画出来的材质层会被 Surface 自己的
 * 不透明底色**整块盖住**——从外面看就是"这个控件没有材质渲染"。
 * 维护者连续两轮报的「弹窗还是没有渲染」正是这个；底栏（"材质也影响导航栏"）同样中招。
 * `FocusCard` 之所以一直正常，只是因为它恰好把 Surface 底色设成了 `Transparent`。
 *
 * 调用方二选一，两种都能修：
 * 1. 让 Surface 的 `color = Color.Transparent`（[FloatingNavigationBar] 的做法）——
 *    底栏 `tonalElevation` 本来就是 0，没有副作用；
 * 2. 把材质层画进 Surface **内容**里的一个 `matchParentSize()` Box（[AppDialog] 的做法）——
 *    连 tonalElevation 都不用动，底色一枚像素不变。
 *
 * 本函数自己会先铺一层 [base]，所以修法 1 不会在底下留个洞。
 */
@Composable
internal fun Modifier.surfaceMaterialFill(
    material: CardMaterial,
    base: Color,
    shape: Shape,
    /**
     * 材质层的不透明度。底栏在"跟随页面渐变"时传 < 1：
     * 材质画刷是不透明的，全强度会把底下的渐变画刷整块盖住。
     */
    alpha: Float = 1f
): Modifier {
    val scheme = MaterialTheme.colorScheme
    val reversed = LocalAppearance.current.cardGradientReversed
    // 与 FocusCard.cardMaterialFill 同一个理由：渐变画刷必须跨帧复用。
    // 建在 drawBehind 里 = 每帧新建 Brush 并重编 shader（底栏与弹窗都是常驻/频繁重绘的）。
    val layer = remember(material, base, scheme, reversed) {
        materialBrush(material, base, scheme, reversed)
    }
    if (layer == null) return this
    return this
        .clip(shape)
        .drawBehind {
            // **只画材质，不铺底色。** 这一层总是叠在调用方自己的底色之上：
            // 弹窗靠 Surface 自己的 `color`，底栏靠下面那层渐变画刷或 Surface 的纯色。
            // 早先这里还画过一层 `drawRect(base)`（为了让"把 Surface 让成透明"那种接法不留洞），
            // 那条接法已经废弃，留着它反而会把底栏的渐变画刷盖掉。
            drawRect(layer, alpha = alpha)
        }
}

/**
 * 柔光的最大幅度：以底色为中心，上下各平移这么多 **sRGB 灰阶级**。
 *
 * 旧实现用的是两个**混色比例**（顶 9% 白 / 底 5% `onSurface`），它有两个病，同源——
 * **拿比例当幅度**：
 *
 * 1. **浅色底净暗**（维护者实测「看起来就是曲线反了」）：底 `250` 往白里混 9% 只涨
 *    **0.45 级**（上方只剩 5 级就到纯白），往深里混 5% 却掉 **11.2 级**；
 *    三站均值净暗约 4 级 → 柔光卡片看起来比默认卡片**更暗**，而不是"被光照到"。
 * 2. **深色底净亮**：深色模式下 `onSurface` 是**浅色**，所谓"底部压深"那一站其实在
 *    往亮里混，于是顶底两站都高于底色、整块反而变亮。方向名义上对，实际是反的。
 *
 * 所以幅度改由**底色自己**决定，并且**上下严格等量**——等量 ⇒ 三站关于底色对称
 * ⇒ 均值恒等于底色 ⇒ **亮度中性**（这正是维护者 T-1 要的）。
 * 近白卡片上方本来就只有几级余量，物理上就只能给到几级；
 * 旧实现错在"下方不受这个约束"，把单方面的物理限制转嫁成了整块净暗。
 */
internal const val SOFT_LIGHT_LEVELS = 10f

/**
 * 在 sRGB 分量上整体平移 [levels] 个灰阶（越界夹紧），alpha 不变。
 *
 * 用"平移级数"而不是"混色比例"表达幅度，是因为**可感知性按级数算**，
 * 而"往白里混 x%"在亮底与暗底上换来的级数相差一个数量级（见 [SOFT_LIGHT_LEVELS]）。
 */
internal fun shiftGreyLevels(color: Color, levels: Float): Color = Color(
    red = ((color.red * 255f + levels) / 255f).coerceIn(0f, 1f),
    green = ((color.green * 255f + levels) / 255f).coerceIn(0f, 1f),
    blue = ((color.blue * 255f + levels) / 255f).coerceIn(0f, 1f),
    alpha = color.alpha
)

/**
 * 柔光在 [base] 上**打算**用的幅度（灰阶级数）。
 *
 * 只受两个约束：配置上限 [SOFT_LIGHT_LEVELS]，以及到纯黑的余量。
 * **不**受"到纯白的余量"约束——顶站被纯白夹住是允许的（近白卡片必然如此），
 * 夹住只会让实际幅度变小，不会破坏亮度中性（见 [softLightStops] 的镜像做法）。
 * 早先按"最大通道到纯白的余量"卡过一次，结果暖杏浅色的底板红通道已经是 255，
 * 幅度被算成 **0**，柔光整个消失——那是把"某个通道没空间"错当成了"整块没空间"。
 */
internal fun softLightAmplitude(base: Color): Float {
    val lo = minOf(base.red, base.green, base.blue) * 255f
    return minOf(SOFT_LIGHT_LEVELS, lo)
}

/**
 * 柔光的三个站点（顶亮 → 底色 → 底沉），像被上方的光轻轻照到。
 *
 * 做法是**镜像**而不是"上下各平移固定级数"：
 * 先算出顶站，再看它**实际**涨了几级（被纯白夹住时会小于 [softLightAmplitude]），
 * 底站就落几级。于是逐通道严格等量 ⇒ 三站等距铺开时均值恒等于底色 ⇒ **亮度中性**。
 *
 * 这样夹紧永远不会破坏中性：它只是把幅度自动收窄，而不会像旧实现那样
 * 让"提亮"单方面失效、"压深"照常生效——那正是"净暗 4 灰阶 / 曲线反了"的成因。
 *
 * 单独抽成纯函数是因为 `Brush.VerticalGradient.colorStops` 在当前 Compose 版本里
 * 对测试不可见——想断言"柔光到底画了什么"就只能从这一层拿。
 * 画刷与测试都从这一个地方取，不会出现"文档/测试与实现漂移"。
 */
internal fun softLightStops(base: Color, reversed: Boolean = false): List<Color> {
    val top = shiftGreyLevels(base, softLightAmplitude(base))
    // 逐通道镜像。底站因此永远不会被夹死：实际涨幅 ≤ 到纯黑的余量 ≤ 每个通道的值。
    val bottom = Color(
        red = base.red - (top.red - base.red),
        green = base.green - (top.green - base.green),
        blue = base.blue - (top.blue - base.blue),
        alpha = base.alpha
    )
    val stops = listOf(top, base, bottom)
    // 反向 = 把三站倒过来铺（底下变亮、顶上微沉），而不是换一组新颜色——
    // 这样"换了方向"不会引入没被对比度总账覆盖过的颜色。
    return if (reversed) stops.reversed() else stops
}

internal fun softLightBrush(base: Color, reversed: Boolean = false): Brush =
    // **径向**而不是竖直：维护者反馈「柔光挺亮但和渐变没有本质差距」——
    // 因为两者原来都是线性竖直斜坡，只是"打光"与"变色"的区别，形态上是同一类。
    // 真正的柔光是从**中心散开**的光晕（柔光箱/无影灯），所以改成径向：
    // 中心最亮、向四周柔和衰减。默认的 center/radius（Unspecified/无限大）
    // 会让 Compose 按绘制区中心与尺寸解析，所以这个画刷仍然与尺寸无关、可以 remember 复用。
    Brush.radialGradient(softLightStops(base, reversed))

/** 供测试：Crop 铺满时源图应取的矩形（与 [drawImageCover] 同一套算法）。 */
internal fun coverSourceRect(srcW: Int, srcH: Int, dstW: Float, dstH: Float): IntArray {
    if (srcW <= 0 || srcH <= 0 || dstW <= 0f || dstH <= 0f) return intArrayOf(0, 0, srcW, srcH)
    val targetRatio = dstW / dstH
    val srcRatio = srcW.toFloat() / srcH.toFloat()
    val cropW: Int
    val cropH: Int
    if (srcRatio > targetRatio) {
        cropH = srcH
        cropW = (srcH * targetRatio).roundToInt().coerceIn(1, srcW)
    } else {
        cropW = srcW
        cropH = (srcW / targetRatio).roundToInt().coerceIn(1, srcH)
    }
    return intArrayOf((srcW - cropW) / 2, (srcH - cropH) / 2, cropW, cropH)
}

/** 供 UI 判断"这个背景到底会不会改变画面"。 */
internal fun AppearanceSpec.backsPageWithSomething(): Boolean = pageBackdrop != BackdropKind.THEME

/** 页签/卡片测试里常用的"最大通道差"比较（复用对比度模块的实现，避免两套算法）。 */
internal fun sameColorWithin(a: Int, b: Int, tolerance: Int): Boolean =
    AppearanceContrast.channelDistance(a, b) <= tolerance

/** 亮度差（0–255），用于断言"渐变确实有梯度"这类性质。 */
internal fun luminanceGap(a: Color, b: Color): Int =
    max(
        kotlin.math.abs((a.red * 255).roundToInt() - (b.red * 255).roundToInt()),
        max(
            kotlin.math.abs((a.green * 255).roundToInt() - (b.green * 255).roundToInt()),
            kotlin.math.abs((a.blue * 255).roundToInt() - (b.blue * 255).roundToInt())
        )
    )
