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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.unit.sp

/** Nav text remains readable independently of the user-selected body text color. */
internal fun navigationContentColor(background: Color): Color =
    if (contrastRatio(Color.Black, background) >= contrastRatio(Color.White, background)) Color.Black else Color.White

internal fun navigationIndicatorColor(background: Color, primary: Color): Color =
    if (contrastRatio(background, primary) >= 1.5) primary.copy(alpha = 1f) else navigationContentColor(background)

/**
 * 8.1.0 底栏形变形状：平时为完整胶囊（四角半径=高度一半）；
 * 有导航历史时，上两角按 cornerProgress 动画收缩为小圆角（16dp），形成"上圆角矩形突出、下胶囊"轮廓。
 * 下两角始终为大圆角；左右竖直、上下平直。
 */
private class AsymmetricCapsuleShape(
    private val cornerProgress: Float,
    private val smallRadiusDp: Float
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val w = size.width
        val h = size.height
        val r2 = h / 2f
        val smallPx = with(density) { smallRadiusDp.dp.toPx() }
        val r1 = r2 + (smallPx - r2) * cornerProgress.coerceIn(0f, 1f)
        val path = Path().apply {
            // 从左下大圆角开始，顺时针一圈（sweep 负值=屏幕上逆时针转过该角）
            moveTo(0f, h / 2f)
            arcTo(Rect(0f, h - 2 * r2, 2 * r2, h), 180f, -90f, false)
            lineTo(w - r2, h)
            arcTo(Rect(w - 2 * r2, h - 2 * r2, w, h), 90f, -90f, false)
            lineTo(w, r1)
            arcTo(Rect(w - 2 * r1, 0f, w, 2 * r1), 0f, -90f, false)
            lineTo(r1, 0f)
            arcTo(Rect(0f, 0f, 2 * r1, 2 * r1), 270f, -90f, false)
            lineTo(0f, h / 2f)
            close()
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
    // 8.1.0 形变：有历史时上两角从小圆角"伸出"（平时为完整胶囊）；< > 符号与形状同步伸缩（同一进度）。
    val historyActive = canGoBack || canGoForward
    val cornerProgress by animateFloatAsState(
        if (historyActive) 1f else 0f,
        tween(motionMillis(240)),
        label = "cornerMorph"
    )
    val barShape = AsymmetricCapsuleShape(cornerProgress = cornerProgress, smallRadiusDp = 10f)
    BoxWithConstraints(
        modifier.fillMaxWidth().windowInsetsPadding(
            safeInsets.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)
        ), contentAlignment = Alignment.Center
    ) {
        val margin = FloatingNavigationLayout.horizontalMarginDp(maxWidth.value, LocalDensity.current.fontScale).dp
        Box(
            Modifier.padding(horizontal = margin, vertical = 8.dp)
                .widthIn(max = FloatingNavigationLayout.MAX_BAR_WIDTH_DP.dp)
                .fillMaxWidth()
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
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
                                            onClick = onAdd, modifier = Modifier.size(52.dp),
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
                    }
                }
            }
            // 8.1.0 顶角符号：裸 < / > 字符（不带圈），贴两顶角、与形状同步淡入；左角长按弹历史，右角 >。
            CornerSymbol(
                visible = canGoBack,
                progress = cornerProgress,
                symbol = "<",
                onClick = onBackHistory,
                onLongPress = onLongPressBack,
                description = "回退到上一个页面；长按查看历史",
                tint = navigationContentColor(background),
                modifier = Modifier.align(Alignment.TopStart).offset(x = (-7).dp, y = (-6).dp)
            )
            CornerSymbol(
                visible = canGoForward,
                progress = cornerProgress,
                symbol = ">",
                onClick = onForwardHistory,
                onLongPress = null,
                description = "折返到后一个页面",
                tint = navigationContentColor(background),
                modifier = Modifier.align(Alignment.TopEnd).offset(x = 7.dp, y = (-6).dp)
            )
        }
    }
}

/** 8.1.0 顶角符号：裸 < / > 字符（不带圈），与形状同一进度淡入；圆形波纹裁剪避免方形阴影。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CornerSymbol(
    visible: Boolean,
    progress: Float,
    symbol: String,
    onClick: () -> Unit,
    onLongPress: (() -> Unit)?,
    description: String,
    tint: Color,
    modifier: Modifier = Modifier
) {
    if (!visible) return
    val clickModifier = if (onLongPress != null) {
        Modifier.combinedClickable(onClick = onClick, onLongClick = onLongPress)
    } else {
        Modifier.combinedClickable(onClick = onClick)
    }
    Box(
        modifier = modifier
            .size(22.dp)
            .graphicsLayer { alpha = progress.coerceIn(0f, 1f) }
            .clip(CircleShape)
            .then(clickModifier)
            .semantics { stateDescription = description },
        contentAlignment = Alignment.Center
    ) {
        Text(
            symbol,
            color = tint,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp
        )
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
    // 8.1.0 副页（空心圆环态）时图标改用与底栏对比的深色；实心态按圆底色取对比色。
    val foreground = if (selected && hasSubpage) navigationContentColor(background) else navigationContentColor(fill)
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
            Modifier.size(44.dp).drawBehind {
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
