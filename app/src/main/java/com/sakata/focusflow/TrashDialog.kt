package com.sakata.focusflow

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun TrashDialog(items: List<Item>, onDismiss: () -> Unit, onRestore: (Long) -> Boolean) {
    val deleted = items.filter { it.kind == "回收站" && it.trashedAt != null }.sortedByDescending { it.trashedAt }
    AppDialog(onDismissRequest = onDismiss, title = { Text("数据与恢复 · 最近删除") },
        text = { ScrollableDialogBox(maxHeight = 440.dp, spacing = 8.dp) {
            if (deleted.isEmpty()) Text("没有最近删除的待办或收集箱记录。")
            deleted.forEach { item ->
                HorizontalDivider()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text(item.title, maxLines = 1)
                        Text("${TrashActions.originalKind(item) ?: "记录"} · ${item.trashedAt?.let(::formatDateTime)}",
                            style = MaterialTheme.typography.labelSmall)
                    }
                    TextButton(onClick = { onRestore(item.id) }) { Text("恢复") }
                }
            }
        } },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } })
}
