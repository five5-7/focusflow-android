package com.sakata.focusflow

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** 收集箱项 → 已有目标：选择归属目标并挂靠（6.8）。目标卡仍按 goalId 统计，项保留原时长/优先级。 */
@Composable
internal fun AttachToPlanDialog(
    goals: List<Goal>,
    onDismiss: () -> Unit,
    onAttach: (Goal) -> Unit
) {
    var selected by remember { mutableStateOf<Goal?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("转为计划的一部分") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "选择该项归属的目标；该项将进入弹性安排并从收集箱移出。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (goals.isEmpty()) {
                    Text("还没有目标。可用“转成目标”为这项创建一个新目标。", style = MaterialTheme.typography.bodySmall)
                } else {
                    Column(
                        Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        goals.forEach { goal ->
                            val isSelected = selected?.id == goal.id
                            Surface(
                                modifier = Modifier.fillMaxWidth().clickable { selected = goal },
                                color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                shape = MaterialTheme.shapes.small
                            ) {
                                Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                                    Text(goal.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        "每周 ${goal.weeklyTarget} 次 · 每次 ${goal.durationMinutes} 分钟 · 本周已完成 ${GoalPlanner.completedThisWeek(goal)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { selected?.let(onAttach) }, enabled = selected != null) { Text("归入计划") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
