package com.sakata.focusflow

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

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
            Modifier.fillMaxSize().verticalScroll(scrollState).padding(padding),
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
    Box(Modifier.heightIn(max = maxHeight)) {
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
    Canvas(modifier.fillMaxHeight().width(6.dp).padding(vertical = 8.dp)) {
        val max = scrollState.maxValue
        if (max > 0) {
            val track = size.height
            val thumb = (track * track / (max + track)).coerceIn(24f, track)
            val top = scrollState.value.toFloat() / max * (track - thumb)
            drawRoundRect(
                color = Color(0x60727A80),
                topLeft = Offset(size.width - 3.dp.toPx(), top),
                size = Size(3.dp.toPx(), thumb),
                cornerRadius = CornerRadius(1.5.dp.toPx())
            )
        }
    }
}
