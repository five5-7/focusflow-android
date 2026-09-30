package com.sakata.focusflow

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sakata.focusflow.data.TrashGroupRecord

/**
 * Recycle bin for ordinary tasks and inbox captures. Grouped deletions show their retention left;
 * tombstones that carry no group keep their own section and are never swept automatically, because
 * no trustworthy deletion time exists for them.
 */
@Composable
internal fun TrashDialog(
    items: List<Item>,
    groups: List<TrashGroupRecord>,
    purgeable: Set<Long>,
    now: Long,
    onDismiss: () -> Unit,
    onRestore: (Set<Long>) -> Boolean,
    onPurge: (Set<Long>) -> Boolean
) {
    val view = remember(items, groups) { TrashViews.build(items, groups) }
    var pendingPurge by remember { mutableStateOf<List<Item>>(emptyList()) }

    AppDialog(onDismissRequest = onDismiss, title = { Text("数据与恢复 · 回收站") },
        text = { ScrollableDialogBox(maxHeight = 440.dp, spacing = 8.dp) {
            if (view.isEmpty) Text("没有最近删除的待办或收集箱记录。")
            // 契约 §4 规则 3 要求把「为什么不给永久删除」讲清楚，且不得给不清扫的记录显示保留期倒计时。
            if ((view.groups.flatMap { it.members } + view.ungrouped).any { it.id !in purgeable }) {
                Text("重复任务产生的记录暂不支持永久删除，仍可恢复；它们不受保留期影响。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            view.groups.forEach { group ->
                HorizontalDivider()
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (group.members.any { it.id in purgeable })
                            "${group.size} 项 · ${TrashViews.remainingLabel(group.expiresAt, now)}"
                        else "${group.size} 项 · 不受保留期影响")
                        if (group.partial) {
                            Text("部分已恢复（${group.restoredCount} 项）",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    TextButton(onClick = { onRestore(group.members.mapTo(mutableSetOf(), Item::id)) }) {
                        Text("全部恢复")
                    }
                    val targets = group.members.filter { it.id in purgeable }
                    if (targets.isNotEmpty()) {
                        TextButton(onClick = { pendingPurge = targets }) { Text("永久删除") }
                    }
                }
                group.members.forEach { item ->
                    TrashRow(item, removable = item.id in purgeable,
                        onRestore = { onRestore(setOf(item.id)) },
                        onPurge = { pendingPurge = listOf(item) })
                }
            }
            if (view.ungrouped.isNotEmpty()) {
                HorizontalDivider()
                Text("更早的删除记录", style = MaterialTheme.typography.titleSmall)
                Text("这些记录没有保留期，不会被自动清理；可随时恢复。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                view.ungrouped.forEach { item ->
                    TrashRow(item, removable = item.id in purgeable,
                        onRestore = { onRestore(setOf(item.id)) },
                        onPurge = { pendingPurge = listOf(item) })
                }
            }
        } },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } })

    if (pendingPurge.isNotEmpty()) {
        val targets = pendingPurge
        AlertDialog(
            onDismissRequest = { pendingPurge = emptyList() },
            title = { Text("永久删除？") },
            text = {
                Text(if (targets.size == 1) "《${targets.single().title}》将被永久删除，无法恢复；已有历史记录会保留。"
                else "选中的 ${targets.size} 项将被永久删除，无法恢复；已有历史记录会保留。")
            },
            confirmButton = { TextButton(onClick = {
                pendingPurge = emptyList()
                onPurge(targets.mapTo(mutableSetOf(), Item::id))
            }) { Text("永久删除") } },
            dismissButton = { TextButton(onClick = { pendingPurge = emptyList() }) { Text("取消") } }
        )
    }
}

@Composable
private fun TrashRow(
    item: Item,
    removable: Boolean,
    onRestore: () -> Unit,
    onPurge: () -> Unit
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${TrashActions.originalKind(item) ?: "记录"} · ${item.trashedAt?.let(::formatDateTime)}",
                style = MaterialTheme.typography.labelSmall)
        }
        TextButton(onClick = onRestore) { Text("恢复") }
        if (removable) TextButton(onClick = onPurge) { Text("永久删除") }
    }
}
