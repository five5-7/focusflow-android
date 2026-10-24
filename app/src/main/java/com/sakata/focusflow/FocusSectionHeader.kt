package com.sakata.focusflow

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp

/** Shared hierarchy for content sections across the four primary pages. */
@Composable
internal fun FocusSectionHeader(
    title: String,
    count: Int? = null,
    modifier: Modifier = Modifier,
    keepActionInline: Boolean = false,
    action: (@Composable () -> Unit)? = null
) {
    if (action == null) {
        FocusSectionTitle(title, count)
        return
    }
    SubcomposeLayout(modifier.fillMaxWidth()) { constraints ->
        val fullWidth = constraints.maxWidth
        val gapPx = 8.dp.roundToPx()
        val stackedGapPx = 4.dp.roundToPx()
        val compactMaxPx = 64.dp.roundToPx()
        val touchTargetPx = 48.dp.roundToPx()

        // 先量动作本身：只有知道它的真实宽度，才能判断"紧凑动作"（单字符/箭头）。
        val actionPlaceable = subcompose(SectionHeaderSlot.Action) {
            Row { action.invoke() }
        }.first().measure(Constraints(maxWidth = fullWidth))
        // 紧凑动作只有十几 dp，让它掉到标题下一行没有任何可读性收益，还会白白占一行高度。
        val compactAction = actionPlaceable.width <= compactMaxPx
        val narrow = fullWidth < 380.dp.roundToPx() || fontScale >= 1.3f

        if (!keepActionInline && !compactAction && narrow) {
            // 文本类长动作：窄屏/大字号下移到标题下方，保证标签完整可读（rc.44 之前的行为）。
            val titlePlaceable = subcompose(SectionHeaderSlot.TitleStacked) {
                FocusSectionTitle(title, count)
            }.first().measure(Constraints(maxWidth = fullWidth))
            val totalHeight = titlePlaceable.height + stackedGapPx + actionPlaceable.height
            layout(fullWidth, constraints.constrainHeight(totalHeight)) {
                titlePlaceable.place(0, 0)
                actionPlaceable.place(fullWidth - actionPlaceable.width, titlePlaceable.height + stackedGapPx)
            }
        } else {
            // 同行：keepActionInline 保留 48dp 触摸区；紧凑动作紧贴右侧，不占多余宽度。
            val slotWidth = if (keepActionInline) maxOf(touchTargetPx, actionPlaceable.width) else actionPlaceable.width
            val slotHeight = if (keepActionInline) maxOf(touchTargetPx, actionPlaceable.height) else actionPlaceable.height
            val titleWidth = (fullWidth - gapPx - slotWidth).coerceAtLeast(0)
            // 紧凑动作不挤压标题：允许标题折行到完整高度，避免"课程与固定安排 · 12"被省略成"…"。
            val wrapTitle = keepActionInline || compactAction
            val titlePlaceable = subcompose(if (wrapTitle) SectionHeaderSlot.TitleWrap else SectionHeaderSlot.TitleClip) {
                FocusSectionTitle(
                    title,
                    count,
                    maxLines = if (wrapTitle) Int.MAX_VALUE else 2
                )
            }.first().measure(Constraints(maxWidth = titleWidth))
            val rowHeight = maxOf(titlePlaceable.height, slotHeight)
            layout(fullWidth, constraints.constrainHeight(rowHeight)) {
                titlePlaceable.place(0, (rowHeight - titlePlaceable.height) / 2)
                // keepActionInline 的动作在 48dp 槽位内居中靠右，其余动作垂直居中贴右边缘。
                actionPlaceable.place(
                    fullWidth - actionPlaceable.width,
                    (rowHeight - slotHeight) / 2 + (slotHeight - actionPlaceable.height) / 2
                )
            }
        }
    }
}

private enum class SectionHeaderSlot { Action, TitleStacked, TitleWrap, TitleClip }

@Composable
private fun FocusSectionTitle(
    title: String,
    count: Int?,
    modifier: Modifier = Modifier,
    maxLines: Int = 2
) {
    Text(
        buildString {
            append(title)
            count?.let { append(" · ").append(it) }
        },
        modifier = modifier,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis
    )
}
