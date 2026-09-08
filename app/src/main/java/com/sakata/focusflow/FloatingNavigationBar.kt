package com.sakata.focusflow

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
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
    hasSubpage: Boolean,
    selectedPageDescription: String,
    onSelectTab: (Int) -> Unit,
    onAdd: () -> Unit,
    canGoBack: Boolean = false,
    canGoForward: Boolean = false,
    onBackHistory: () -> Unit = {},
    onForwardHistory: () -> Unit = {},
    onLongPressBack: () -> Unit = {},
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
                Box {
                    // 8.1.0 底栏形变：两端圆瓣在页签下层缩放淡入，出现/消失不挤压任何页签。
                    HistoryLobe(
                        visible = canGoBack,
                        onClick = onBackHistory,
                        onLongPress = onLongPressBack,
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        description = "回退到上一个页面；长按查看历史",
                        background = background,
                        modifier = Modifier.align(Alignment.CenterStart)
                    )
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
                                        if (selectedTab == index) stateDescription = selectedPageDescription +
                                            if (hasSubpage) "；再次点击返回${label}主页" else ""
                                    },
                                    hasSubpage = selectedTab == index && hasSubpage,
                                    destinationKey = if (selectedTab == index) selectedPageDescription else label,
                                    onClick = { onSelectTab(index) }
                                )
                            }
                        }
                    }
                    HistoryLobe(
                        visible = canGoForward,
                        onClick = onForwardHistory,
                        onLongPress = null,
                        icon = Icons.AutoMirrored.Filled.ArrowForward,
                        description = "折返到后一个页面",
                        background = background,
                        modifier = Modifier.align(Alignment.CenterEnd)
                    )
                }
            }
        }
    }
}

/** 8.1.0 底栏两端圆瓣：与底栏同色同描边，缩放淡入；先于页签组合（绘制在下层，不遮挡图标主体）。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryLobe(
    visible: Boolean,
    onClick: () -> Unit,
    onLongPress: (() -> Unit)?,
    icon: ImageVector,
    description: String,
    background: Color,
    modifier: Modifier = Modifier
) {
    val progress by animateFloatAsState(if (visible) 1f else 0f, tween(220), label = "historyLobe")
    if (progress <= 0.01f) return
    val lobeColor = navigationContentColor(background)
    val clickModifier = if (onLongPress != null) {
        Modifier.combinedClickable(onClick = onClick, onLongClick = onLongPress)
    } else {
        Modifier.combinedClickable(onClick = onClick)
    }
    Box(
        modifier = modifier
            .size(44.dp)
            .graphicsLayer {
                scaleX = progress
                scaleY = progress
                alpha = progress
            }
            .drawBehind {
                drawCircle(background)
                drawCircle(lobeColor.copy(alpha = 0.14f), style = Stroke(width = 1.dp.toPx()))
            }
            .then(clickModifier)
            .semantics { stateDescription = description },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = description, tint = lobeColor, modifier = Modifier.size(20.dp))
    }
}

/** 8.1.0 会话页面历史列表：最近的在最上，点选直达并截断其后历史。 */
@Composable
internal fun HistoryListDialog(
    entries: List<PageSnapshot>,
    current: PageSnapshot,
    onSelect: (PageSnapshot) -> Unit,
    onDismiss: () -> Unit
) {
    val reversed = entries.reversed()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("本次会话的页面历史") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                reversed.forEach { snapshot ->
                    val isCurrent = snapshot == current
                    Surface(
                        onClick = { onSelect(snapshot) },
                        shape = RoundedCornerShape(8.dp),
                        color = if (isCurrent) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else Color.Transparent
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                snapshot.label,
                                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                color = if (isCurrent) MaterialTheme.colorScheme.primary else Color.Unspecified
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

@Composable
private fun FloatingNavigationItem(
    label: String, icon: ImageVector, selected: Boolean,
    background: Color, indicator: Color, modifier: Modifier,
    hasSubpage: Boolean, destinationKey: String, onClick: () -> Unit
) {
    // Animate each slot: no selection block travels across the independent central Add action.
    // Compose respects the system animation-duration scale, including disabled animations.
    val progress by animateFloatAsState(if (selected) 1f else 0f, tween(200), label = "navigationSelection")
    val fill = lerp(background, indicator, progress)
    val foreground = navigationContentColor(fill)
    val subpageProgress by animateFloatAsState(if (hasSubpage) 1f else 0f, tween(200), label = "navigationDepth")
    val destinationPulse = remember { Animatable(1f) }
    var previousDestination by remember { mutableStateOf(destinationKey) }
    LaunchedEffect(destinationKey) {
        val changed = destinationKey != previousDestination
        previousDestination = destinationKey
        if (selected && changed) {
            destinationPulse.animateTo(0.90f, tween(80))
            destinationPulse.animateTo(1f, tween(140))
        } else destinationPulse.snapTo(1f)
    }
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
                val side = size.minDimension * (0.88f + 0.12f * progress) * destinationPulse.value
                drawRoundRect(
                    fill, Offset((size.width - side) / 2, (size.height - side) / 2),
                    Size(side, side), CornerRadius(12.dp.toPx())
                )
                if (subpageProgress > 0f) {
                    val center = Offset(size.width - 4.dp.toPx(), 4.dp.toPx())
                    drawCircle(background, 5.dp.toPx() * subpageProgress, center)
                    drawCircle(navigationContentColor(background), 3.dp.toPx() * subpageProgress, center)
                }
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
