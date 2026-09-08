package com.sakata.focusflow

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
    ElevatedCard {
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
    Column(Modifier.fillMaxWidth()) {
        Text("最近事件", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if (events.isNotEmpty()) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { pendingDeleteIds = events.map { it.id }.toSet() }) {
                Text("清空全部", color = MaterialTheme.colorScheme.error)
            }
            TextButton(onClick = {
                selecting = !selecting
                selectedIds = emptySet()
            }) { Text(if (selecting) "完成" else "批量管理") }
        }
        if (events.isNotEmpty()) Text(
            "清空全部只删除历史事件，不删除现有任务；完成率、改期次数和本周摘要会按剩余记录重算。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    if (selecting && recent.isNotEmpty()) {
        val visibleIds = recent.map { it.id }.toSet()
        val allVisibleSelected = visibleIds.all { it in selectedIds }
        Column(Modifier.fillMaxWidth()) {
            Text("已选 ${selectedIds.size}/${recent.size} 条", style = MaterialTheme.typography.labelMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { selectedIds = if (allVisibleSelected) emptySet() else visibleIds }) {
                    Text(if (allVisibleSelected) "取消全选" else "全选当前")
                }
                TextButton(enabled = selectedIds.isNotEmpty(), onClick = { pendingDeleteIds = selectedIds }) {
                    Text("删除所选", color = MaterialTheme.colorScheme.error)
                }
            }
        }
        if (events.size > recent.size) Text("批量列表显示最近 ${recent.size} 条；“清空全部”会删除全部 ${events.size} 条。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    if (recent.isEmpty()) {
        Text(
            "还没有任务历史。之后的创建、安排、完成、改期、放回和删除等操作会在这里留下新记录。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    } else {
        recent.forEach { event ->
            ElevatedCard {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (selecting) Checkbox(
                        checked = event.id in selectedIds,
                        onCheckedChange = { checked -> selectedIds = if (checked) selectedIds + event.id else selectedIds - event.id }
                    )
                    Text(TaskRecorder.displayText(event), Modifier.weight(1f).padding(horizontal = 4.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
                    if (!selecting) TextButton(onClick = { pendingDeleteIds = setOf(event.id) }) {
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
    pendingDeleteIds?.let { targets ->
        val clearingAll = targets.size == events.size && events.all { it.id in targets }
        AlertDialog(
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
