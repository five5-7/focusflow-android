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

/**
 * Visual treatment only: never owns insets, height of the viewport, or pointer input.
 *
 * **渐变档下这一层什么都不画。**
 *
 * 维护者连续三轮反馈"通知栏的渲染分层问题"（切割感 / 接缝 / 依然分层）。查下来根因在设计本身：
 * 这一层是**第二次**把同一个渐变画一遍——即使几何算得再准，两层独立抗锯齿、独立像素对齐，
 * 边界处必然出现可辨的接缝；前两轮我都在调"这一次画得准不准"，属于治标。
 *
 * 正解是**不要重复画**：页面背景层（`MainActivity` 的根 `Box` + `appearanceBackdrop`）
 * 本来就是整屏绘制、已经覆盖状态栏区域，那里的颜色天然是对的。所以渐变档直接不画，
 * "分层"就无从产生。
 *
 * 跟随主题 / 固定颜色档保留原来的淡出：那是**默认外观**的一部分，必须逐像素不变；
 * 而且纯色档下本层与背后颜色本来就一致，不存在分层问题。
 */
@Composable
internal fun StatusBarScrim(safeTop: Dp, modifier: Modifier = Modifier) {
    if (safeTop <= 0.dp) return
    val appearance = LocalAppearance.current
    // 渐变档：交给页面背景层去呈现，本层不参与（见上文说明）。
    if (appearance.effectivePageBackdrop == BackdropKind.GRADIENT) return

    val base = MaterialTheme.colorScheme.background
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
}
