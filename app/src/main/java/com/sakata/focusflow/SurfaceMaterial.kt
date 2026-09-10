package com.sakata.focusflow

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
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

/** 页面容器色：跟随主题时就是原来的 background；选了渐变/图片就交给背景层去画（透明）。 */
@Composable
internal fun pageContainerColor(): Color =
    if (LocalAppearance.current.pageBackdrop == BackdropKind.THEME) {
        MaterialTheme.colorScheme.background
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

    /** 页面渐变：顶部淡淡带一点主题色，向下回到页面底色。 */
    fun page(scheme: ColorScheme): Brush = Brush.verticalGradient(pageStops(scheme))

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

    fun pageStops(scheme: ColorScheme): List<Color> = listOf(
        blendSrgb(scheme.background, scheme.primary, 0.14f),
        blendSrgb(scheme.background, scheme.primary, 0.05f),
        scheme.background
    )

    fun cardStops(scheme: ColorScheme): List<Color> = listOf(
        blendSrgb(scheme.surfaceContainerLow, scheme.primary, 0.07f),
        blendSrgb(scheme.surfaceContainerLow, scheme.primary, 0.01f)
    )
}

/** 背景层要画在哪一层。 */
internal enum class BackdropRole { Page, Timetable }

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
    role: BackdropRole = BackdropRole.Page
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
    val picked = if (role == BackdropRole.Timetable) spec.timetableColor else 0
    return drawBehind {
        when (backdrop) {
            BackdropKind.GRADIENT -> drawRect(
                if (role == BackdropRole.Timetable) ThemeGradient.timetable(scheme) else ThemeGradient.page(scheme)
            )

            BackdropKind.COLOR -> {
                val base = if (picked != 0) Color(picked) else blendSrgb(scheme.background, scheme.primary, 0.10f)
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
