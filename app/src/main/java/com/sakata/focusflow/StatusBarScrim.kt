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
    val background = MaterialTheme.colorScheme.background
    Box(modifier.fillMaxWidth().height(safeTop + 12.dp)
        .background(Brush.verticalGradient(
            0f to background.copy(alpha = 0.96f),
            0.55f to background.copy(alpha = 0.80f),
            1f to background.copy(alpha = 0f)
        )).clearAndSetSemantics {})
}
