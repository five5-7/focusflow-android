package com.sakata.focusflow

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/** Lives in Scaffold.bottomBar: its entire measured height remains reserved for page content. */
@Composable
internal fun FloatingNavigationBar(
    safeInsets: WindowInsets,
    content: @Composable RowScope.() -> Unit
) {
    BoxWithConstraints(
        Modifier.fillMaxWidth().windowInsetsPadding(
            safeInsets.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)
        ),
        contentAlignment = Alignment.Center
    ) {
        val margin = FloatingNavigationLayout.horizontalMarginDp(maxWidth.value, LocalDensity.current.fontScale).dp
        Surface(
            modifier = Modifier.padding(horizontal = margin, vertical = 8.dp)
                .widthIn(max = FloatingNavigationLayout.MAX_BAR_WIDTH_DP.dp).fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            tonalElevation = 0.dp,
            shadowElevation = 6.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        ) {
            BoxWithConstraints {
                // Very narrow split windows scroll instead of squeezing five touch targets.
                val contentWidth = maxWidth.coerceAtLeast(FloatingNavigationLayout.MIN_CONTENT_WIDTH_DP.dp)
                Box(Modifier.horizontalScroll(rememberScrollState())) {
                    NavigationBar(
                        modifier = Modifier.width(contentWidth),
                        containerColor = Color.Transparent,
                        tonalElevation = 0.dp,
                        windowInsets = WindowInsets(0, 0, 0, 0),
                        content = content
                    )
                }
            }
        }
    }
}
