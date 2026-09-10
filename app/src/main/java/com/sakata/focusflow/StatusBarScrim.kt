package com.sakata.focusflow

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Visual treatment only: never owns insets, height of the viewport, or pointer input. */
@Composable
internal fun StatusBarScrim(safeTop: Dp, modifier: Modifier = Modifier) {
    if (safeTop <= 0.dp) return
    // 8.2.0：这层压在屏幕最上方，颜色必须跟页面背景一致。
    //
    // 跟随主题（默认）：**保持原样**——一层页面底色的纵向淡出，逐像素不变。
    //
    // 主题渐变：原来写死 `pageStops(...).first()`，只取"顶部色"，
    // 于是 下→上 取到了底站、左→右 与斜向更是取单一颜色去代表一条**横向变化**的颜色，
    // 表现为"左右方向的渐变覆盖不到通知栏"（维护者反馈）。
    // 现在用**与页面同一个画刷、同一套几何**：画刷按整屏尺寸算，再只画本层这一条，
    // 于是通知栏那一条与页面顶部逐像素对齐，与方向无关。
    val appearance = LocalAppearance.current
    val scheme = MaterialTheme.colorScheme
    val isGradient = appearance.effectivePageBackdrop == BackdropKind.GRADIENT
    if (!isGradient) {
        val base = scheme.background
        Box(
            modifier.fillMaxWidth().height(safeTop + 12.dp)
                .background(
                    Brush.verticalGradient(
                        0f to base.copy(alpha = 0.96f),
                        0.55f to base.copy(alpha = 0.80f),
                        1f to base.copy(alpha = 0f)
                    )
                ).clearAndSetSemantics {}
        )
        return
    }

    val stops = ThemeGradient.pageStops(
        scheme, appearance.gradientScale, appearance.gradientTop, appearance.gradientBottom
    )
    val direction = appearance.gradientDirection
    // 整屏高度：渐变必须按整屏几何取色，否则把整条渐变压进通知栏这一窄条就完全不对了。
    val screenHeightDp = LocalConfiguration.current.screenHeightDp
    val screenHeightPx = with(LocalDensity.current) { screenHeightDp.dp.toPx() }

    Box(
        modifier.fillMaxWidth().height(safeTop + 12.dp)
            .drawBehind {
                // 用整屏尺寸当画刷几何，只在本层范围内作画 → 取到的是页面顶部的真实那一段。
                val brush = pageBrushFor(direction, stops, Size(size.width, screenHeightPx))
                drawRect(brush, topLeft = Offset.Zero, size = size)
                // 向下淡出成页面底色，让状态栏与正文衔接（与默认档的淡出观感一致）。
                drawRect(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.55f to scheme.background.copy(alpha = 0.35f),
                        1f to scheme.background.copy(alpha = 1f)
                    ),
                    topLeft = Offset.Zero,
                    size = size
                )
            }
            .clearAndSetSemantics {}
    )
}
