package com.sakata.focusflow

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** 历史记录子页：近 7 天逐日统计 + 最近事件列表（计划/完成/改期/放回/删除全程留痕）。 */
@Composable
internal fun PlanHistorySection(events: List<TaskEvent>, onReplaceEvents: (List<TaskEvent>) -> Boolean) {
    var selecting by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(emptySet<Long>()) }
    var pendingDeleteIds by remember { mutableStateOf<Set<Long>?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val now = System.currentTimeMillis()
    val todayStart = TaskHistory.dayStartOf(now)
    val days = TaskHistory.lastDays(events, 7, now)
    // 收编：ElevatedCard → FocusCard，显式保留 surfaceContainerLow 底色与 1dp 默认阴影。
    FocusCard(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        elevation = 1.dp
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("近 7 天完成情况", fontWeight = FontWeight.SemiBold)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                days.forEach { day -> DayCell(day, isToday = day.dayStart == todayStart, modifier = Modifier.weight(1f)) }
            }
            Text(
                "近 7 天共完成 ${days.sumOf { it.completedCount }} 项 · 改期 ${days.sumOf { it.rescheduledCount }} 次（含延后）。" +
                    "完成率按安排日计算；删除或放回收集箱不会撤销当日计划。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    val recent = TaskHistory.recentEvents(events, limit = 50)
    val visibleIds = remember(recent) { recent.map { it.id }.toSet() }
    val visibleSelection = selectedIds.intersect(visibleIds)
    Column(Modifier.fillMaxWidth()) {
        Text("最近事件", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if (events.isNotEmpty()) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = {
                selecting = !selecting
                selectedIds = emptySet()
            }) { Text(if (selecting) "完成" else "批量管理") }
        }
        if (events.isNotEmpty() && !selecting) TextButton(onClick = { pendingDeleteIds = events.map { it.id }.toSet() }) {
            Text("清空全部", color = MaterialTheme.colorScheme.error)
        }
        if (events.isNotEmpty() && !selecting) Text(
            "清空全部只删除历史事件，不删除现有任务；完成率、改期次数和本周摘要会按剩余记录重算。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    AnimatedVisibility(
        visible = selecting && recent.isNotEmpty(),
        enter = expandVertically(animationSpec = MotionSpec.quick()) + fadeIn(animationSpec = MotionSpec.quick()),
        exit = shrinkVertically(animationSpec = MotionSpec.quick()) + fadeOut(animationSpec = MotionSpec.quick())
    ) {
        HistorySelectionToolbar(
            selectedCount = visibleSelection.size,
            visibleCount = recent.size,
            onToggleAll = { selectedIds = if (visibleSelection.size == recent.size) emptySet() else visibleIds },
            onDelete = { pendingDeleteIds = visibleSelection }
        )
    }
    if (selecting && events.size > recent.size) Text("批量列表显示最近 ${recent.size} 条；“清空全部”会删除全部 ${events.size} 条。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    if (recent.isEmpty()) {
        Text(
            "还没有任务历史。之后的创建、安排、完成、改期、放回和删除等操作会在这里留下新记录。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    } else {
        recent.forEach { event ->
            val checked = event.id in visibleSelection
            val rowColor by animateColorAsState(
                if (selecting && checked) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
                else MaterialTheme.colorScheme.surfaceContainerLow,
                MotionSpec.quick(), label = "historySelectionColor"
            )
            // 收编：ElevatedCard → FocusCard，显式保留 surfaceContainerLow 底色与 1dp 默认阴影。
            FocusCard(
                containerColor = rowColor,
                elevation = 1.dp
            ) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    AnimatedVisibility(
                        visible = selecting,
                        enter = expandHorizontally(animationSpec = MotionSpec.quick()) + fadeIn(animationSpec = MotionSpec.quick()),
                        exit = shrinkHorizontally(animationSpec = MotionSpec.quick()) + fadeOut(animationSpec = MotionSpec.quick())
                    ) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = { next -> selectedIds = if (next) selectedIds + event.id else selectedIds - event.id }
                        )
                    }
                    Text(TaskRecorder.displayText(event), Modifier.weight(1f).padding(horizontal = 4.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
                    AnimatedVisibility(
                        visible = !selecting,
                        enter = expandHorizontally(animationSpec = MotionSpec.quick()) + fadeIn(animationSpec = MotionSpec.quick()),
                        exit = shrinkHorizontally(animationSpec = MotionSpec.quick()) + fadeOut(animationSpec = MotionSpec.quick())
                    ) {
                        TextButton(onClick = { pendingDeleteIds = setOf(event.id) }) {
                            Text("删除", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
    pendingDeleteIds?.let { targets ->
        val clearingAll = targets.size == events.size && events.all { it.id in targets }
        AppDialog(
            onDismissRequest = { pendingDeleteIds = null },
            title = { Text(if (clearingAll) "清空全部历史？" else if (targets.size == 1) "删除这条历史？" else "删除所选 ${targets.size} 条历史？") },
            text = { Text("删除后将立即使用剩余事件重新计算近 7 天完成率、改期次数和本周执行摘要；任务本身不会删除。此操作不可撤销。") },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    onClick = {
                        val remaining = TaskHistory.without(events, targets)
                        if (onReplaceEvents(remaining)) {
                            selectedIds = emptySet()
                            selecting = false
                            errorMessage = null
                            pendingDeleteIds = null
                        } else {
                            errorMessage = "历史记录保存失败，尚未删除；请检查存储空间或数据保护状态。"
                            pendingDeleteIds = null
                        }
                    }
                ) { Text(if (clearingAll) "确认清空" else "确认删除") }
            },
            dismissButton = { TextButton(onClick = { pendingDeleteIds = null }) { Text("取消") } }
        )
    }
}

/** Select only the currently displayed 50 events; clearing the complete history stays a separate action. */
@Composable
private fun HistorySelectionToolbar(
    selectedCount: Int,
    visibleCount: Int,
    onToggleAll: () -> Unit,
    onDelete: () -> Unit
) {
    FocusCard(containerColor = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
            Text("已选 $selectedCount/$visibleCount 条", style = MaterialTheme.typography.labelMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onToggleAll) { Text(if (selectedCount == visibleCount) "取消全选" else "全选当前") }
                TextButton(enabled = selectedCount > 0, onClick = onDelete) {
                    Text("删除所选", color = if (selectedCount > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun DayCell(day: DayTaskSummary, isToday: Boolean, modifier: Modifier = Modifier) {
    val container = if (isToday) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
    Column(
        modifier.padding(2.dp).clip(RoundedCornerShape(8.dp)).background(container).padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            weekdayName(todayWeekday(day.dayStart)),
            style = MaterialTheme.typography.labelSmall
        )
        Text(
            day.completionPercent?.let { "$it%" } ?: "—",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
        Text(
            "${day.completedPlannedCount}/${day.scheduledCount}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
