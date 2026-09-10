package com.sakata.focusflow

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Visual treatment only: never owns insets, height of the viewport, or pointer input. */
@Composable
internal fun StatusBarScrim(safeTop: Dp, modifier: Modifier = Modifier) {
    if (safeTop <= 0.dp) return
    // 8.2.0：这层压在屏幕最上方，颜色必须跟页面背景一致。
    // 跟随主题时就是原来的页面底色；主题渐变时用渐变的**顶部色**——否则最上面一截仍是旧颜色，
    // 看起来像"顶部功能栏没跟着变"（维护者真机反馈过这一点）。
    val appearance = LocalAppearance.current
    val base = when (appearance.pageBackdrop) {
        BackdropKind.GRADIENT -> ThemeGradient.pageStops(MaterialTheme.colorScheme, appearance.gradientScale).first()
        else -> MaterialTheme.colorScheme.background
    }
    Box(modifier.fillMaxWidth().height(safeTop + 12.dp)
        .background(Brush.verticalGradient(
            0f to base.copy(alpha = 0.96f),
            0.55f to base.copy(alpha = 0.80f),
            1f to base.copy(alpha = 0f)
        )).clearAndSetSemantics {})
}
