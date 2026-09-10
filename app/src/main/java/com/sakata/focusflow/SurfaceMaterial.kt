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
    GradientDirection.DIAGONAL_DOWN -> Brush.linearGradient(
        colors = stops,
        start = Offset.Zero,
        end = Offset(size.width, size.height)
    )
    GradientDirection.DIAGONAL_UP -> Brush.linearGradient(
        colors = stops,
        // 左下 → 右上。这两条对角线是镜像关系，不能用"反向站点"替代（那只是同一条线倒着走）。
        start = Offset(0f, size.height),
        end = Offset(size.width, 0f)
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
 * 柔光与纸感**必须有可见的填充变化**：原先两者都 `-> null`，只在 [FocusCard] 里挂了同一份
 * 阴影，结果"柔光"和"纸感"渲染出来完全一样，而柔光本身又看不出任何变化（真机反馈：
 * "柔光和纸感似乎是一样的"）。现在两者都走 [materialBrush] 的柔光底（顶面提亮 → 底部微沉），
 * 纸感在其之上再叠一层噪点（见 [FocusCard]）。
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
internal fun materialBrush(material: CardMaterial, base: Color, scheme: ColorScheme): Brush? =
    when (material) {
        CardMaterial.TONAL -> null
        CardMaterial.GRADIENT -> Brush.verticalGradient(listOf(blendSrgb(base, scheme.primary, 0.07f), base))
        CardMaterial.SOFT, CardMaterial.PAPER -> softLightBrush(base, scheme.onSurface)
    }

/**
 * 把材质叠层画在**调用方自己的形状里**。
 *
 * 必须用 `clip(shape)` 再 `drawBehind`：起初底栏是直接 `drawBehind { drawRect(brush) }` 的，
 * 而 `drawBehind` 画在 Surface 的形状裁剪**之外**，于是底栏上出现了一整块矩形底色
 * （维护者反馈："用材质时悬浮栏会出现一块矩形底"）。这里统一裁到形状内，杜绝同一类错误。
 *
 * [CardMaterial.PAPER] 额外叠一层噪点（纸感 = 柔光 + 纸纹）。
 */
@Composable
internal fun Modifier.surfaceMaterialFill(
    material: CardMaterial,
    base: Color,
    shape: Shape
): Modifier {
    val scheme = MaterialTheme.colorScheme
    val layer = materialBrush(material, base, scheme)
    if (layer == null) return this
    return this
        .clip(shape)
        .drawBehind {
            drawRect(layer)
            if (material == CardMaterial.PAPER) {
                // 纸感 = 柔光 + 纸纹 + 一点点整体压深。
                // 那层压深是"可量化的区别"：只靠噪点的话，两者在深色底上仍然很难分辨
                // （维护者连续两轮反馈"柔光和纸感没区别"）。压深很淡，不会让正文变糊。
                drawRect(scheme.onSurface.copy(alpha = PAPER_SHEEN_ALPHA))
                drawPaperGrain()
            }
        }
}

/**
 * 柔光的顶面高光强度（白色混入比例）与底部压深强度（onSurface 混入比例）。
 *
 * **2026-09-10 加大**：真机目视复核发现，原来 5.5% / 3.0% 时柔光的卡面与"默认"只差
 * **2~3 灰阶**，肉眼几乎等于默认——当时柔光唯一看得出来的地方是卡片外那一圈投影，
 * 而那圈投影恰恰是被误读成"矩形色差"的缺陷（已删除）。所以柔光必须**靠自己卡面**立住：
 * 现在 9% / 5%，上下落差约 20 灰阶，一眼能看出"顶亮底沉"。
 *
 * 上限仍受可读性约束：底部压深会让深色正文对比度变差，所以底部权重始终小于顶部。
 * `RichEffectsTest` 与对比度总账一起守着这条。
 */
internal const val SOFT_TOP_LIGHT = 0.09f
internal const val SOFT_BOTTOM_SHADE = 0.05f

/**
 * 柔光的底色层：顶面微亮、底部微沉，像被上方的光轻轻照到。
 *
 * 深色模式下同样成立——提亮是"往白里混"、压深是"往文字色里混"，
 * 两者都朝各自明暗的反方向走，所以深色表面是"顶上稍亮、底下稍暗"，不会发灰。
 */
/**
 * 柔光的三个站点（顶亮 → 底色 → 底沉）。
 *
 * 单独抽出来是因为 `Brush.VerticalGradient.colorStops` 在当前 Compose 版本里
 * 对测试不可见——想断言"柔光到底画了什么"就只能从纯函数这一层拿。
 * 画刷与测试都从这一个地方取，不会出现"文档/测试与实现漂移"。
 */
internal fun softLightStops(base: Color, shade: Color): List<Color> = listOf(
    blendSrgb(base, Color.White, SOFT_TOP_LIGHT),
    base,
    blendSrgb(base, shade, SOFT_BOTTOM_SHADE)
)

internal fun softLightBrush(base: Color, shade: Color): Brush =
    Brush.verticalGradient(softLightStops(base, shade))

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
