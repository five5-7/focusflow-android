package com.sakata.focusflow

import android.app.DatePickerDialog
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Calendar
import java.util.Locale
import kotlinx.coroutines.delay

private data class TodoCreateDraft(val text: String, val dateOnlyAt: Long?)

@Composable
internal fun TodoCreateDialog(onDismiss: () -> Unit, onSave: (String, Long?) -> Boolean) {
    val context = LocalContext.current
    val vault = LocalDraftVault.current
    val draftKey = "todoCreateLines"
    val saved = vault.load<TodoCreateDraft>(draftKey)
    var title by remember { mutableStateOf(saved?.text.orEmpty()) }
    var dateOnlyAt by remember { mutableStateOf(saved?.dateOnlyAt) }
    fun persist() = vault.save(draftKey, TodoCreateDraft(title, dateOnlyAt))
    val lines = title.lines().map(String::trim).filter(String::isNotBlank)
    AppDialog(
        onDismissRequest = onDismiss,
        title = { Text("新增待办") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it; persist() },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("要做什么（一行一项）") },
                minLines = 2,
                maxLines = 6
            )
            Text(if (lines.size <= 1) "只填标题即可；多行粘贴将分别创建待办。" else "将创建 ${lines.size} 项待办（最多 50 项）。",
                style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("不指定" to null, "今天" to 0, "明天" to 1).forEach { (label, offset) ->
                    val chosen = offset?.let { TaskHistory.dayStartOf(dateAt(it, 12)) }
                    FilterChip(selected = dateOnlyAt == chosen, onClick = { dateOnlyAt = chosen; persist() }, label = { Text(label) })
                }
            }
            OutlinedButton(onClick = {
                val calendar = Calendar.getInstance().apply { timeInMillis = dateOnlyAt ?: System.currentTimeMillis() }
                DatePickerDialog(context, { _, year, month, day ->
                    dateOnlyAt = Calendar.getInstance().apply {
                        set(year, month, day, 12, 0, 0)
                        set(Calendar.MILLISECOND, 0)
                    }.timeInMillis.let(TaskHistory::dayStartOf)
                    persist()
                }, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH)).show()
            }) { Text(dateOnlyAt?.let { "日期：${SimpleDateFormat("M月d日", Locale.CHINA).format(Date(it))}（无到点提醒）" } ?: "其他日期") }
        } },
        confirmButton = {
            Button(enabled = lines.isNotEmpty() && lines.size <= 50 && lines.all { it.length <= 200 }, onClick = {
                if (onSave(title, dateOnlyAt)) {
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
    onDetail: (Item) -> Unit,
    onBatchAction: (Set<Long>, TodoBatchAction) -> Boolean
) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var showCompleted by remember { mutableStateOf(false) }
    var selecting by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(emptySet<Long>()) }
    val selectable = items.filter { it.kind == "任务" && !it.done && it.goalId == null && it.parentCaptureId == null &&
        items.none { child -> child.parentCaptureId == it.id } }.mapTo(mutableSetOf()) { it.id }
    LaunchedEffect(selectable) { selectedIds = selectedIds.intersect(selectable) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(60_000)
        }
    }
    val groups = remember(items, now / 60_000L) { groupTodos(items, now) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text("未完成 ${groups.pendingCount} 项", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (selectable.isNotEmpty()) TextButton(onClick = {
                selecting = !selecting; selectedIds = emptySet()
            }) { Text(if (selecting) "完成整理" else "整理多项") }
            Button(onClick = onAdd) { Text("新增待办") }
        }
    }
    if (selecting) {
        Text("已选 ${selectedIds.size} 项 · 关联目标的任务请单独处理", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { selectedIds = if (selectedIds == selectable) emptySet() else selectable }) {
            Text(if (selectedIds == selectable) "取消全选" else "全选普通待办")
        }
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp), maxItemsInEachRow = 2) {
            TodoBatchAction.entries.forEach { action ->
                val canRun = selectedIds.isNotEmpty() && (action != TodoBatchAction.CLEAR_TIME ||
                    items.filter { it.id in selectedIds }.all { it.scheduledAt != null })
                OutlinedButton(enabled = canRun, onClick = {
                    if (onBatchAction(selectedIds, action)) { selecting = false; selectedIds = emptySet() }
                }) { Text(action.label) }
            }
        }
    }
    if (groups.pendingCount == 0) {
        Text("还没有待办。记下标题就可以开始，时间以后再安排。", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    fun toggle(item: Item) { selectedIds = if (item.id in selectedIds) selectedIds - item.id else selectedIds + item.id }
    TodoGroup("已过安排", groups.overdue, now, onComplete, onDetail, selecting, selectable, selectedIds, ::toggle, { selecting = true; toggle(it) })
    TodoGroup("已安排", groups.scheduled, now, onComplete, onDetail, selecting, selectable, selectedIds, ::toggle, { selecting = true; toggle(it) })
    TodoGroup("未安排", groups.unscheduled, now, onComplete, onDetail, selecting, selectable, selectedIds, ::toggle, { selecting = true; toggle(it) })
    if (groups.completed.isNotEmpty()) {
        TextButton(onClick = { showCompleted = !showCompleted }) { Text("已完成 · ${groups.completed.size} ${if (showCompleted) "收起" else "展开"}") }
        if (showCompleted) TodoGroup("已完成", groups.completed, now, onComplete, onDetail, selecting, selectable, selectedIds, ::toggle, { selecting = true; toggle(it) })
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TodoGroup(
    title: String,
    items: List<Item>,
    now: Long,
    onComplete: (Item) -> Unit,
    onDetail: (Item) -> Unit,
    selecting: Boolean,
    selectable: Set<Long>,
    selectedIds: Set<Long>,
    onSelect: (Item) -> Unit,
    onLongSelect: (Item) -> Unit
) {
    if (items.isEmpty()) return
    Text("$title · ${items.size}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    FocusCard(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            items.forEachIndexed { index, item ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(Modifier.fillMaxWidth().heightIn(min = 54.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = if (selecting && item.id in selectable) item.id in selectedIds else item.done,
                        enabled = if (selecting) item.id in selectable else !item.done,
                        onCheckedChange = { if (selecting) onSelect(item) else onComplete(item) })
                    Column(
                        Modifier.weight(1f).combinedClickable(
                            onClick = { if (selecting && item.id in selectable) onSelect(item) else if (!selecting) onDetail(item) },
                            onLongClick = { if (item.id in selectable) onLongSelect(item) }
                        ).padding(vertical = 7.dp),
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
