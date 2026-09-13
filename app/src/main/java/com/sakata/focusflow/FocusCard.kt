package com.sakata.focusflow

import android.os.Build
import android.graphics.Shader
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity

/**
 * 8.2.0 的统一卡片：把「卡片材质」集中在一处实现（见 docs/8.2.0-appearance-plan.md）。
 *
 * [containerColor] 传调用点原来的底色 —— 默认材质 [CardMaterial.TONAL] 下就是原来的
 * `Card(containerColor = …)`，**逐像素不变**；其余材质在这层底色之上叠加。
 *
 * 为什么要有这个组件：全应用有 80 处卡片容器、各自写 `CardDefaults.cardColors`，
 * 想改"卡片长什么样"就得改 80 个地方。收编进来的调用点以后只描述"底色是什么"。
 *
 * **2026-09-11 起全部收编完毕**（`FocusCard` 调用点 35 → 80），只剩课表/日程表 2 处底板
 * 有意不收编（底色归「课表与日程表底色」设置管）。每处都带 `// 收编：` 注释，可按需回退子集。
 * 那次之所以要补收 45 处，是因为早先的盘点用带括号的 `Card(` grep，
 * **漏掉了尾随 lambda 写法** `ElevatedCard {` / `Card {`。
 */
@Composable
internal fun FocusCard(
    containerColor: Color,
    modifier: Modifier = Modifier,
    shape: Shape = CardDefaults.shape,
    border: BorderStroke? = null,
    elevation: androidx.compose.ui.unit.Dp = androidx.compose.ui.unit.Dp(0f),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val material = LocalAppearance.current.effectiveCardMaterial
    val effectiveBorder = cardBorderForMaterial(material, border)
    val colors = CardDefaults.cardColors(
        containerColor = if (material == CardMaterial.TONAL) containerColor else Color.Transparent
    )
    val elevationSpec = CardDefaults.cardElevation(defaultElevation = elevation)
    fun body(): @Composable () -> Unit = {
        if (material == CardMaterial.TONAL) {
            Column(content = content)
        } else {
            Box {
                if (material.samplesPageBackdrop) {
                    // 8.2.1：玻璃类材质先重画卡片所覆盖的页面底图，再只模糊这份副本。
                    // 亚克力保留较多轮廓，毛玻璃扩散更强；两者不模糊正文与交互内容。
                    Box(
                        Modifier
                            .matchParentSize()
                            .clampedBackdropBlur(material.backdropBlurRadiusDp)
                            .appearanceBackdrop(
                                LocalAppearance.current,
                                MaterialTheme.colorScheme,
                                LocalBackdropBitmap.current
                            )
                    )
                }
                Box(
                    Modifier
                        .matchParentSize()
                        .cardMaterialFill(
                            containerColor,
                            material,
                            MaterialTheme.colorScheme,
                            LocalAppearance.current.cardGradientReversed,
                            shape
                        )
                )
                Column(content = content)
            }
        }
    }
    if (onClick != null) {
        Card(
            onClick = onClick,
            modifier = modifier,
            shape = shape,
            colors = colors,
            elevation = elevationSpec,
            border = effectiveBorder
        ) { body()() }
    } else {
        Card(
            modifier = modifier,
            shape = shape,
            colors = colors,
            elevation = elevationSpec,
            border = effectiveBorder
        ) { body()() }
    }
}

/**
 * 只模糊背景副本，并把画布边缘像素向外延展。
 *
 * Compose 的 `Modifier.blur` 默认在边缘外使用透明像素。玻璃卡片的模糊半径达到 18/30dp 时，
 * 边缘会因此逐渐透明，Surface 的底色或 elevation 色层从四周透出，形成“厚灰框 + 内层矩形”。
 * Android 12+ 的原生 RenderEffect 可以使用 CLAMP：模糊核超出画布时继续取最外侧像素，
 * 不再混入透明色；最终圆角仍由外层 Material Card 的同一个 Shape 裁切。
 * Android 12 以下与原有 Compose blur 一样无法提供实时模糊，保留透明染色层安全回退。
 */
@Composable
private fun Modifier.clampedBackdropBlur(radiusDp: Float): Modifier {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || radiusDp <= 0f) return this
    val density = LocalDensity.current
    val radiusPx = with(density) { androidx.compose.ui.unit.Dp(radiusDp).toPx() }
    val effect = remember(radiusPx) {
        android.graphics.RenderEffect.createBlurEffect(
            radiusPx,
            radiusPx,
            Shader.TileMode.CLAMP
        ).asComposeRenderEffect()
    }
    return graphicsLayer { renderEffect = effect }
}

/**
 * 玻璃材质必须独占边缘绘制：亚克力不画边，毛玻璃由材质层沿真实 Shape 画亮边。
 * 调用点遗留的 OutlinedCard 边框若继续传给 Material Card，会让亚克力出现错误描边、
 * 毛玻璃叠成双边；计划主页的入口卡片正是这类调用点最集中的区域。
 */
internal fun cardBorderForMaterial(material: CardMaterial, border: BorderStroke?): BorderStroke? =
    if (material.samplesPageBackdrop) null else border

@Composable
private fun Modifier.cardMaterialFill(
    containerColor: Color,
    material: CardMaterial,
    scheme: ColorScheme,
    softReversed: Boolean,
    shape: Shape
): Modifier {
    val layer = remember(material, containerColor, scheme, softReversed) {
        materialBrush(material, containerColor, scheme, softReversed)
    }
    val edge = remember(scheme) { scheme.onSurface.copy(alpha = 0.03f) }
    val rim = remember(material, containerColor) { materialRimColor(material, containerColor) }
    val rimWidthDp = remember(material) { materialRimWidthDp(material) }
    val edgeBrush = remember(edge) {
        Brush.horizontalGradient(
            0f to edge,
            0.06f to Color.Transparent,
            0.94f to Color.Transparent,
            1f to edge
        )
    }
    return drawBehind {
        if (!material.samplesPageBackdrop) {
            drawRect(scheme.background)
            drawRect(containerColor)
        }
        if (layer != null) drawRect(layer)
        drawRect(edgeBrush)
        if (rim != null) {
            drawMaterialRim(shape, rim, rimWidthDp)
        }
    }
}

/** 卡片材质的展示名（设置页与测试共用，避免两处文案漂移）。 */
internal fun CardMaterial.label(): String = when (this) {
    CardMaterial.TONAL -> "默认"
    CardMaterial.GRADIENT -> "渐变"
    CardMaterial.SOFT -> "柔光"
    CardMaterial.ACRYLIC -> "亚克力"
    CardMaterial.FROSTED -> "毛玻璃"
}

/** 只有玻璃类材质需要在卡片里重画页面底图；其余材质继续铺自己的不透明底色。 */
internal val CardMaterial.samplesPageBackdrop: Boolean
    get() = this == CardMaterial.ACRYLIC || this == CardMaterial.FROSTED

/** 两种玻璃材质的可感知差异：亚克力留轮廓，毛玻璃做更强扩散。 */
internal val CardMaterial.backdropBlurRadiusDp: Float
    get() = when (this) {
        CardMaterial.ACRYLIC -> 18f
        CardMaterial.FROSTED -> 30f
        else -> 0f
    }
