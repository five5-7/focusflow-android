package com.sakata.focusflow

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.List
import androidx.compose.material.icons.outlined.Settings
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/** Nav text remains readable independently of the user-selected body text color. */
internal fun navigationContentColor(background: Color): Color =
    if (contrastRatio(Color.Black, background) >= contrastRatio(Color.White, background)) Color.Black else Color.White

internal fun navigationIndicatorColor(background: Color, primary: Color): Color =
    if (contrastRatio(background, primary) >= 1.5) primary.copy(alpha = 1f) else navigationContentColor(background)

/**
 * 8.1.0 底栏形状：胶囊本体不变（上下界不变），顶部两角按进度生长出圆形小耳（回退/折返）。
 * 耳半径随 progress 从 0 动画生长，描边与阴影都跟随整体轮廓。
 */
private class CornerEarCapsuleShape(
    private val progressL: Float,
    private val progressR: Float,
    private val earRadiusDp: Float,
    private val earCenterXDp: Float,
    private val earCenterYDp: Float
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            // 胶囊本体
            addOval(Rect(0f, 0f, w, h))
            // 左耳：生长中的圆，圆心贴近左上角（向左上突出）
            if (progressL > 0.01f) {
                val er = with(density) { earRadiusDp.dp.toPx() } * progressL
                val cx = with(density) { earCenterXDp.dp.toPx() }
                val cy = with(density) { earCenterYDp.dp.toPx() }
                addOval(Rect(cx - er, cy - er, cx + er, cy + er))
            }
            if (progressR > 0.01f) {
                val er = with(density) { earRadiusDp.dp.toPx() } * progressR
                val cx = w - with(density) { earCenterXDp.dp.toPx() }
                val cy = with(density) { earCenterYDp.dp.toPx() }
                addOval(Rect(cx - er, cy - er, cx + er, cy + er))
            }
        }
        return Outline.Generic(path)
    }
}

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
    val background by animateColorAsState(containerColor, tween(motionMillis(220)), label = "navigationTheme")
    val indicator = navigationIndicatorColor(background, MaterialTheme.colorScheme.primary)
    // 8.1.0 顶角双耳：有历史时从胶囊上两角生长出来（形状本身变形），耳内为 < / > 符号。
    val backEar by animateFloatAsState(
        targetValue = if (canGoBack) 1f else 0f,
        animationSpec = if (canGoBack) spring(dampingRatio = 0.72f, stiffness = 420f) else tween(motionMillis(140)),
        label = "backEar"
    )
    val forwardEar by animateFloatAsState(
        targetValue = if (canGoForward) 1f else 0f,
        animationSpec = if (canGoForward) spring(dampingRatio = 0.72f, stiffness = 420f) else tween(motionMillis(140)),
        label = "forwardEar"
    )
    val barShape = CornerEarCapsuleShape(
        progressL = backEar,
        progressR = forwardEar,
        earRadiusDp = 18f,
        earCenterXDp = 10f,
        earCenterYDp = 10f
    )
    BoxWithConstraints(
        modifier.fillMaxWidth().windowInsetsPadding(
            safeInsets.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)
        ), contentAlignment = Alignment.Center
    ) {
        val margin = FloatingNavigationLayout.horizontalMarginDp(maxWidth.value, LocalDensity.current.fontScale).dp
        Surface(
            modifier = Modifier.padding(horizontal = margin, vertical = 8.dp)
                .widthIn(max = FloatingNavigationLayout.MAX_BAR_WIDTH_DP.dp).fillMaxWidth(),
            shape = barShape,
            color = background, tonalElevation = 0.dp, shadowElevation = 6.dp,
            border = BorderStroke(1.dp, navigationContentColor(background).copy(alpha = 0.12f))
        ) {
            // Internal padding contains BOTH selected background and ripple within the outer corners.
            BoxWithConstraints(Modifier.padding(FloatingNavigationLayout.INNER_PADDING_DP.dp)) {
                val contentWidth = maxWidth.coerceAtLeast(FloatingNavigationLayout.MIN_CONTENT_WIDTH_DP.dp)
                Box {
                    Box(Modifier.horizontalScroll(rememberScrollState())) {
                        Row(
                            Modifier.width(contentWidth).selectableGroup(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val labels = listOf("今日", "日程", "计划", "设置")
                            val icons = listOf(Icons.Outlined.Home, Icons.Outlined.DateRange, Icons.Outlined.List, Icons.Outlined.Settings)
                            labels.forEachIndexed { index, label ->
                                if (index == 2) Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                    Surface(
                                        onClick = onAdd, modifier = Modifier.size(48.dp),
                                        shape = CircleShape,
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
                    // 8.1.0 耳内符号：随耳生长淡入；左耳 < 长按弹历史，右耳 >。
                    EarSymbol(
                        progress = backEar,
                        onClick = onBackHistory,
                        onLongPress = onLongPressBack,
                        icon = Icons.AutoMirrored.Outlined.ArrowBack,
                        description = "回退到上一个页面；长按查看历史",
                        tint = navigationContentColor(background),
                        modifier = Modifier.offset(x = (-11).dp, y = (-11).dp)
                    )
                    EarSymbol(
                        progress = forwardEar,
                        onClick = onForwardHistory,
                        onLongPress = null,
                        icon = Icons.AutoMirrored.Outlined.ArrowForward,
                        description = "折返到后一个页面",
                        tint = navigationContentColor(background),
                        modifier = Modifier.align(Alignment.TopEnd).offset(x = 11.dp, y = (-11).dp)
                    )
                }
            }
        }
    }
}

/** 8.1.0 耳内符号：随耳生长进度淡入；点击回退/折返，长按（左耳）弹历史。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EarSymbol(
    progress: Float,
    onClick: () -> Unit,
    onLongPress: (() -> Unit)?,
    icon: ImageVector,
    description: String,
    tint: Color,
    modifier: Modifier = Modifier
) {
    if (progress <= 0.01f) return
    val clickModifier = if (onLongPress != null) {
        Modifier.combinedClickable(onClick = onClick, onLongClick = onLongPress)
    } else {
        Modifier.combinedClickable(onClick = onClick)
    }
    Box(
        modifier = modifier
            .size(26.dp)
            .graphicsLayer { alpha = progress.coerceIn(0f, 1f) }
            .then(clickModifier)
            .semantics { stateDescription = description },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(15.dp))
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
    val progress by animateFloatAsState(if (selected) 1f else 0f, tween(motionMillis(200)), label = "navigationSelection")
    val fill = lerp(background, indicator, progress)
    val foreground = navigationContentColor(fill)
    val subpageProgress by animateFloatAsState(if (hasSubpage) 1f else 0f, tween(motionMillis(200)), label = "navigationDepth")
    val destinationPulse = remember { Animatable(1f) }
    var previousDestination by remember { mutableStateOf(destinationKey) }
    LaunchedEffect(destinationKey) {
        val changed = destinationKey != previousDestination
        previousDestination = destinationKey
        if (selected && changed) {
            destinationPulse.animateTo(0.90f, tween(motionMillis(80)))
            destinationPulse.animateTo(1f, tween(motionMillis(140)))
        } else destinationPulse.snapTo(1f)
    }
    Column(
        modifier.heightIn(min = FloatingNavigationLayout.MIN_ITEM_HEIGHT_DP.dp)
            .clip(RoundedCornerShape(percent = 50))
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically)
    ) {
        Box(
            Modifier.size(40.dp).drawBehind {
                // 8.1.0 选中态：主页=实心圆；子页=空心圆环（标签同步替换为子页名）。
                val radius = size.minDimension / 2 * (0.86f + 0.14f * progress) * destinationPulse.value
                if (hasSubpage && selected) {
                    drawCircle(fill, radius = radius, center = center, style = Stroke(width = 3.dp.toPx()))
                } else {
                    drawCircle(fill, radius = radius, center = center)
                }
            }, contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = foreground,
                modifier = Modifier.size(24.dp).graphicsLayer {
                    scaleX = 0.96f + 0.04f * progress
                    scaleY = scaleX
                })
        }
        Text(
            // 8.1.0 副页表示：选中且处于子页时，标签替换为子页名（如「设置」→「外观」）。
            if (selected && hasSubpage) destinationKey else label,
            color = navigationContentColor(background),
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
