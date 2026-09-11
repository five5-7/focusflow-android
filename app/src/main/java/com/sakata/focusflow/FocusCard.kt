package com.sakata.focusflow

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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape

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
    val colors = CardDefaults.cardColors(
        containerColor = if (material == CardMaterial.TONAL) containerColor else Color.Transparent
    )
    val elevationSpec = CardDefaults.cardElevation(defaultElevation = elevation)
    fun body(): @Composable () -> Unit = {
        if (material == CardMaterial.TONAL) {
            Column(content = content)
        } else {
            Box {
                if (material == CardMaterial.ACRYLIC) {
                    // Recovered acrylic experiment: redraw the page backdrop below the material layer,
                    // then blur that copy. The original remote prototype used 40dp; the lost local
                    // rc.2 experiment reduced it to 18dp to preserve more background structure.
                    Box(
                        Modifier
                            .matchParentSize()
                            .blur(androidx.compose.ui.unit.Dp(18f))
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
                        .cardMaterialFill(containerColor, material, MaterialTheme.colorScheme, LocalAppearance.current.cardGradientReversed)
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
            border = border
        ) { body()() }
    } else {
        Card(
            modifier = modifier,
            shape = shape,
            colors = colors,
            elevation = elevationSpec,
            border = border
        ) { body()() }
    }
}

@Composable
private fun Modifier.cardMaterialFill(
    containerColor: Color,
    material: CardMaterial,
    scheme: ColorScheme,
    softReversed: Boolean
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
        if (material != CardMaterial.ACRYLIC) {
            drawRect(scheme.background)
            drawRect(containerColor)
        }
        if (layer != null) drawRect(layer)
        drawRect(edgeBrush)
        if (rim != null) {
            val w = androidx.compose.ui.unit.Dp(rimWidthDp).toPx()
            drawRect(
                color = rim,
                topLeft = Offset(w / 2f, w / 2f),
                size = androidx.compose.ui.geometry.Size(size.width - w, size.height - w),
                style = androidx.compose.ui.graphics.drawscope.Stroke(w)
            )
        }
    }
}

/** 卡片材质的展示名（设置页与测试共用，避免两处文案漂移）。 */
internal fun CardMaterial.label(): String = when (this) {
    CardMaterial.TONAL -> "默认"
    CardMaterial.GRADIENT -> "渐变"
    CardMaterial.SOFT -> "柔光"
    CardMaterial.ACRYLIC -> "亚克力"
}
