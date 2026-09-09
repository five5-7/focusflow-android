package com.sakata.focusflow

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Only page scrollers consume this; dialogs never inherit a second navbar spacer. */
internal val LocalFloatingBottomPadding = staticCompositionLocalOf { 0.dp }

/** Initial breathing room belongs to scroll CONTENT, not the viewport. */
internal val LocalScrollingTopPadding = staticCompositionLocalOf { 0.dp }

/** 带可视滚动条的整页滚动容器：右侧绘制细滚动条，提示下方还有内容。 */
@Composable
internal fun ScrollableWithBar(
    modifier: Modifier = Modifier,
    scrollState: ScrollState,
    padding: Dp = 20.dp,
    spacing: Dp = 14.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    Box(modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(scrollState).padding(
                start = padding, end = padding, top = padding + LocalScrollingTopPadding.current,
                bottom = padding + LocalFloatingBottomPadding.current
            ),
            verticalArrangement = Arrangement.spacedBy(spacing),
            content = content
        )
        ScrollThumb(scrollState, Modifier.align(Alignment.CenterEnd))
    }
}

/** 对话框内带可视滚动条的内容容器（内容超高时右侧显示细滚动条）。 */
@Composable
internal fun ScrollableDialogBox(
    maxHeight: Dp,
    spacing: Dp = 8.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    val scrollState = rememberScrollState()
    // 键盘避让：输入类对话框内容被软键盘遮挡时向上收；若 Dialog 窗口不提供 IME inset 则等效 0，无副作用。
    Box(Modifier.heightIn(max = maxHeight).imePadding()) {
        Column(
            Modifier.heightIn(max = maxHeight).verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(spacing),
            content = content
        )
        ScrollThumb(scrollState, Modifier.align(Alignment.CenterEnd))
    }
}

@Composable
private fun ScrollThumb(scrollState: ScrollState, modifier: Modifier = Modifier) {
    val visible = scrollState.maxValue > 0
    val active = visible && scrollState.isScrollInProgress
    val alpha by animateFloatAsState(
        targetValue = when {
            !visible -> 0f
            active -> 0.72f
            else -> 0.22f
        },
        animationSpec = MotionSpec.quick(),
        label = "scroll-thumb-alpha"
    )
    val width by animateDpAsState(
        targetValue = if (active) 3.dp else 2.dp,
        animationSpec = MotionSpec.quick(),
        label = "scroll-thumb-width"
    )
    val color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha)
    Canvas(modifier.fillMaxHeight().width(6.dp).padding(vertical = 8.dp)) {
        val max = scrollState.maxValue
        if (max > 0) {
            val track = size.height
            val thumb = (track * track / (max + track)).coerceIn(24f, track)
            val top = scrollState.value.toFloat() / max * (track - thumb)
            drawRoundRect(
                color = color,
                topLeft = Offset(size.width - width.toPx(), top),
                size = Size(width.toPx(), thumb),
                cornerRadius = CornerRadius(width.toPx() / 2f)
            )
        }
    }
}

/** 统一的线性加载／进度条：null 为无法估算进度的加载态，否则为 0..1 的确定进度。 */
@Composable
internal fun FocusFlowProgressBar(
    progress: Float? = null,
    modifier: Modifier = Modifier,
    thickness: Dp = 4.dp
) {
    val barModifier = modifier.fillMaxWidth().height(thickness).clip(RoundedCornerShape(thickness / 2f))
    if (progress == null) {
        LinearProgressIndicator(
            modifier = barModifier,
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )
    } else {
        LinearProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = barModifier,
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )
    }
}
