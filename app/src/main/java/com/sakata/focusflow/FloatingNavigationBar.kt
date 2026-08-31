package com.sakata.focusflow

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/** Nav text remains readable independently of the user-selected body text color. */
internal fun navigationContentColor(background: Color): Color =
    if (contrastRatio(Color.Black, background) >= contrastRatio(Color.White, background)) Color.Black else Color.White

internal fun navigationIndicatorColor(background: Color, primary: Color): Color =
    if (contrastRatio(background, primary) >= 1.5) primary.copy(alpha = 1f) else navigationContentColor(background)

/** Overlay surface. Only the rounded bar receives input; exterior margins pass through. */
@Composable
internal fun FloatingNavigationBar(
    safeInsets: WindowInsets,
    containerColor: Color,
    selectedTab: Int,
    selectedPageDescription: String,
    onSelectTab: (Int) -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier
) {
    val background by animateColorAsState(containerColor, tween(220), label = "navigationTheme")
    val indicator = navigationIndicatorColor(background, MaterialTheme.colorScheme.primary)
    BoxWithConstraints(
        modifier.fillMaxWidth().windowInsetsPadding(
            safeInsets.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)
        ), contentAlignment = Alignment.Center
    ) {
        val margin = FloatingNavigationLayout.horizontalMarginDp(maxWidth.value, LocalDensity.current.fontScale).dp
        Surface(
            modifier = Modifier.padding(horizontal = margin, vertical = 8.dp)
                .widthIn(max = FloatingNavigationLayout.MAX_BAR_WIDTH_DP.dp).fillMaxWidth(),
            shape = RoundedCornerShape(FloatingNavigationLayout.OUTER_RADIUS_DP.dp),
            color = background, tonalElevation = 0.dp, shadowElevation = 6.dp,
            border = BorderStroke(1.dp, navigationContentColor(background).copy(alpha = 0.12f))
        ) {
            // Internal padding contains BOTH selected background and ripple within the outer corners.
            BoxWithConstraints(Modifier.padding(FloatingNavigationLayout.INNER_PADDING_DP.dp)) {
                val contentWidth = maxWidth.coerceAtLeast(FloatingNavigationLayout.MIN_CONTENT_WIDTH_DP.dp)
                Box(Modifier.horizontalScroll(rememberScrollState())) {
                    Row(
                        Modifier.width(contentWidth).selectableGroup(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val labels = listOf("今日", "日程", "计划", "设置")
                        val icons = listOf(Icons.Filled.Home, Icons.Filled.DateRange, Icons.Filled.List, Icons.Filled.Settings)
                        labels.forEachIndexed { index, label ->
                            if (index == 2) Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                Surface(
                                    onClick = onAdd, modifier = Modifier.size(48.dp),
                                    shape = RoundedCornerShape(16.dp),
                                    color = indicator, contentColor = navigationContentColor(indicator)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(Icons.Filled.Add, contentDescription = "添加")
                                    }
                                }
                            }
                            FloatingNavigationItem(
                                label, icons[index], selectedTab == index, background, indicator,
                                Modifier.weight(1f).semantics {
                                    if (selectedTab == index) stateDescription = selectedPageDescription
                                }, onClick = { onSelectTab(index) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FloatingNavigationItem(
    label: String, icon: ImageVector, selected: Boolean,
    background: Color, indicator: Color, modifier: Modifier, onClick: () -> Unit
) {
    // Animate each slot: no selection block travels across the independent central Add action.
    // Compose respects the system animation-duration scale, including disabled animations.
    val progress by animateFloatAsState(if (selected) 1f else 0f, tween(200), label = "navigationSelection")
    val fill = lerp(background, indicator, progress)
    val foreground = navigationContentColor(fill)
    Column(
        modifier.heightIn(min = FloatingNavigationLayout.MIN_ITEM_HEIGHT_DP.dp)
            .clip(RoundedCornerShape(FloatingNavigationLayout.ITEM_RADIUS_DP.dp))
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically)
    ) {
        Box(
            Modifier.size(40.dp).drawBehind {
                val side = size.minDimension * (0.88f + 0.12f * progress)
                drawRoundRect(
                    fill, Offset((size.width - side) / 2, (size.height - side) / 2),
                    Size(side, side), CornerRadius(12.dp.toPx())
                )
            }, contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = foreground,
                modifier = Modifier.size(24.dp).graphicsLayer {
                    scaleX = 0.96f + 0.04f * progress
                    scaleY = scaleX
                })
        }
        Text(label, color = navigationContentColor(background),
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
    }
}
