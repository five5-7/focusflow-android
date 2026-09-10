package com.sakata.focusflow

import androidx.compose.foundation.background
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
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
    return if (appearance.pageBackdrop == BackdropKind.THEME) {
        background(flatColor)
    } else {
        appearanceBackdrop(appearance, MaterialTheme.colorScheme, LocalBackdropBitmap.current)
    }
}

/**
 * 三站渐变在 [t]∈[0,1] 处的颜色（纯函数，分段线性）。
 *
 * 抽出来是为了实现"渐变跟随内容"：把一条**长**渐变按当前滚动位置截出一个窗口，
 * 窗口内只发生整条渐变的一小段颜色变化，所以每屏看起来更缓。
 */
internal fun gradientAt(stops: List<Color>, t: Float): Color {
    if (stops.isEmpty()) return Color.Unspecified
    if (stops.size == 1) return stops[0]
    val clamped = t.coerceIn(0f, 1f)
    val scaled = clamped * (stops.size - 1)
    val index = scaled.toInt().coerceIn(0, stops.size - 2)
    val local = scaled - index
    return blendSrgb(stops[index], stops[index + 1], local)
}

/**
 * 把基础渐变按窗口 `[from, from + window]` 截成三站颜色。
 *
 * [window] = 1 表示窗口正好等于整条渐变（= "固定在一屏"的现状）；
 * 窗口越小，窗口内首尾的颜色差越小 —— 即"颜色变化更慢更缓和"。
 */
internal fun windowStops(stops: List<Color>, from: Float, window: Float): List<Color> {
    val w = window.coerceIn(0f, 1f)
    val start = from.coerceIn(0f, 1f - w)
    return listOf(
        gradientAt(stops, start),
        gradientAt(stops, start + w / 2f),
        gradientAt(stops, start + w)
    )
}

/** 跟随内容滚动时，渐变一共铺多少屏（越大越缓）。 */
internal const val GRADIENT_SCROLL_SPAN = 3.2f

/** 页面容器色：跟随主题时就是原来的 background；选了渐变/图片就交给背景层去画（透明）。 */
@Composable
internal fun pageContainerColor(): Color =
    if (LocalAppearance.current.pageBackdrop == BackdropKind.THEME) {
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
    if (LocalAppearance.current.timetableBackdrop == BackdropKind.THEME) {
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
     * [phase] 与 [window] 用于"渐变跟随内容"（维护者口径）：
     * 基础渐变铺满 [GRADIENT_SCROLL_SPAN] 屏，[phase] 是已经滚过的比例、[window] 是当前一屏占整条的比例。
     * 固定模式传 `phase = 0f, window = 1f`（即现状：整条渐变正好一屏）。
     */
    fun page(
        scheme: ColorScheme,
        strength: Float = 1f,
        phase: Float = 0f,
        window: Float = 1f,
        top: Int = 0,
        bottom: Int = 0
    ): Brush {
        val stops = pageStops(scheme, strength, top, bottom)
        return Brush.verticalGradient(if (window >= 1f) stops else windowStops(stops, phase, window))
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
        val s = strength.coerceIn(0f, 2f)
        // 深色模式单独一套幅度（维护者提醒"注意适配深色模式"）：
        // 深色页面上"顶亮 85%"会变成一条刺眼亮带，而且深色模式的正文是浅色的，
        // 浅底会直接把可读性吃掉。所以深色下只做"顶上微微提亮、底下压深"。
        val dark = scheme.background.luminance() < 0.5f
        val autoTop = if (dark) {
            blendSrgb(scheme.background, Color.White, 0.10f * s)
        } else {
            blendSrgb(scheme.background, Color.White, 0.85f * s)
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

    /** 自选渐变配色（顶色 → 底色）；选了以后 [pageStops] 就用它，不再按主题派生。 */
    internal val PAGE_GRADIENT_PAIRS: List<Pair<Int, Int>> = listOf(
        0xFFFFFCF8.toInt() to 0xFFECE4DE.toInt(), // 暖白 → 暖灰
        0xFFF5F9FC.toInt() to 0xFFDEE8F0.toInt(), // 雾蓝 → 浅蓝
        0xFFF8FBF4.toInt() to 0xFFE2ECDC.toInt(), // 淡竹 → 浅竹
        0xFFFDF7F9.toInt() to 0xFFF3E2E8.toInt(), // 藕粉 → 浅粉
        0xFFFAF8FD.toInt() to 0xFFE8E3F2.toInt(), // 浅薰 → 淡紫
        0xFFFCF9F2.toInt() to 0xFFEEE5D6.toInt(), // 亚麻 → 燕麦
        0xFFF7FAFB.toInt() to 0xFFE2E7E9.toInt(), // 青灰 → 雾灰
        0xFFFFFAF0.toInt() to 0xFFF2E2CE.toInt()  // 晨曦 → 暖沙
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
     * 已滚过多少屏（仅"渐变跟随内容"时生效）。固定模式传 0。
     * 值由页面级 nestedScroll 累计，见 MainActivity 的 scrollTracker。
     */
    scrolledScreens: Float = 0f
): Modifier {
    val backdrop = when (role) {
        BackdropRole.Page -> spec.pageBackdrop
        BackdropRole.Timetable -> spec.timetableBackdrop
    }
    if (backdrop == BackdropKind.THEME) return this
    val alpha = when (role) {
        BackdropRole.Page -> spec.imageAlpha
        BackdropRole.Timetable -> spec.timetableAlpha
    }
    val picked = when (role) {
        BackdropRole.Timetable -> spec.timetableColor
        // 8.2.0 §7.5：页面也能选固定背景色了（0 = 未选时退回按主题派生的浅色）。
        BackdropRole.Page -> spec.pageColor
    }
    // 渐变跟随内容：整条渐变铺 GRADIENT_SCROLL_SPAN 屏，当前一屏只截取其中 1/span 的一段，
    // 于是每屏的颜色变化比"固定一屏"慢得多、缓和得多（维护者口径）。
    val follows = role == BackdropRole.Page && spec.gradientFollowsContent
    val window = if (follows) 1f / GRADIENT_SCROLL_SPAN else 1f
    val phase = if (follows) scrolledScreens / GRADIENT_SCROLL_SPAN else 0f
    return drawBehind {
        when (backdrop) {
            BackdropKind.GRADIENT -> drawRect(
                if (role == BackdropRole.Timetable) {
                    ThemeGradient.timetable(scheme)
                } else {
                    ThemeGradient.page(scheme, spec.gradientScale, phase, window, spec.gradientTop, spec.gradientBottom)
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
                    drawRect(scheme.background.copy(alpha = scrimAlpha(alpha)))
                }
            }

            BackdropKind.THEME -> Unit
        }
    }
}

/** 图片不透明度越高，遮罩越厚；0.34–0.78 之间，既有图感又保得住文字。 */
internal fun scrimAlpha(imageAlpha: Float): Float = 0.34f + 0.44f * imageAlpha.coerceIn(0f, 1f)

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
 * 卡片材质对应的画刷。
 *
 * [CardMaterial.TONAL] 返回 null（= 现在的容器色）；柔光与纸感在阶段 E 落地，
 * 落地前一律按现状渲染，避免出现"设置了却没变化"的假开关。
 */
@Composable
internal fun cardMaterialBrush(material: CardMaterial): Brush? = when (material) {
    CardMaterial.TONAL -> null
    CardMaterial.GRADIENT -> ThemeGradient.card(MaterialTheme.colorScheme)
    CardMaterial.SOFT, CardMaterial.PAPER -> null
}

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
