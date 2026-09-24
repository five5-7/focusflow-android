package com.sakata.focusflow

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

@Composable
internal fun TodoCreateDialog(onDismiss: () -> Unit, onSave: (String) -> Boolean) {
    val vault = LocalDraftVault.current
    val draftKey = "todoCreate"
    var title by remember { mutableStateOf(vault.load<String>(draftKey).orEmpty()) }
    AppDialog(
        onDismissRequest = onDismiss,
        title = { Text("新增待办") },
        text = {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it; vault.save(draftKey, it) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("要做什么") },
                singleLine = true
            )
        },
        confirmButton = {
            Button(enabled = title.isNotBlank(), onClick = {
                if (onSave(title.trim())) {
                    vault.clear(draftKey)
                    onDismiss()
                }
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
internal fun TodoListSection(
    items: List<Item>,
    onAdd: () -> Unit,
    onComplete: (Item) -> Unit,
    onDetail: (Item) -> Unit
) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var showCompleted by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(60_000)
        }
    }
    val groups = remember(items, now / 60_000L) { groupTodos(items, now) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text("未完成 ${groups.pendingCount} 项", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Button(onClick = onAdd) { Text("新增待办") }
    }
    if (groups.pendingCount == 0) {
        Text("还没有待办。记下标题就可以开始，时间以后再安排。", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    TodoGroup("已过安排", groups.overdue, now, onComplete, onDetail)
    TodoGroup("已安排", groups.scheduled, now, onComplete, onDetail)
    TodoGroup("未安排", groups.unscheduled, now, onComplete, onDetail)
    if (groups.completed.isNotEmpty()) {
        TextButton(onClick = { showCompleted = !showCompleted }) { Text("已完成 · ${groups.completed.size} ${if (showCompleted) "收起" else "展开"}") }
        if (showCompleted) TodoGroup("已完成", groups.completed, now, onComplete, onDetail)
    }
}

@Composable
private fun TodoGroup(
    title: String,
    items: List<Item>,
    now: Long,
    onComplete: (Item) -> Unit,
    onDetail: (Item) -> Unit
) {
    if (items.isEmpty()) return
    Text("$title · ${items.size}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    FocusCard(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            items.forEachIndexed { index, item ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(Modifier.fillMaxWidth().heightIn(min = 54.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = item.done, enabled = !item.done, onCheckedChange = { onComplete(item) })
                    Column(
                        Modifier.weight(1f).clickable { onDetail(item) }.padding(vertical = 7.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                        Text(todoFact(item, now), maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

private fun todoFact(item: Item, now: Long): String {
    if (item.done) return item.completedAt?.let { "完成于 ${formatDateTime(it)}" } ?: "已完成"
    val at = item.scheduledAt ?: return "尚未安排时间"
    if (item.dayOnly) {
        val date = SimpleDateFormat("M月d日", Locale.CHINA).format(Date(at))
        return if (ScheduleOccupation.sameDate(at, now)) "今天 · 不定时间" else "$date · 不定时间"
    }
    return formatDateTime(at)
}

@Composable
internal fun TodoDetailDialog(
    item: Item,
    onDismiss: () -> Unit,
    onSchedule: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    AppDialog(
        onDismissRequest = onDismiss,
        title = { Text(item.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(todoFact(item, System.currentTimeMillis()), color = MaterialTheme.colorScheme.onSurfaceVariant)
                item.editableNote().takeIf { it.isNotBlank() && it != "尚未安排具体时间" }?.let { Text(it) }
                Text("预计 ${item.durationMinutes} 分钟 · 优先级 ${ItemPriority.fromKey(item.priority).label}", style = MaterialTheme.typography.bodySmall)
                if (!item.done) OutlinedButton(onClick = onSchedule, modifier = Modifier.fillMaxWidth()) { Text("安排时间") }
                OutlinedButton(onClick = onEdit, modifier = Modifier.fillMaxWidth()) { Text("编辑") }
                TextButton(onClick = onDelete, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("删除") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}
