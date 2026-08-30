package com.sakata.focusflow

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** 历史记录子页：近 7 天逐日统计 + 最近事件列表（计划/完成/改期/放回/删除全程留痕）。 */
@Composable
internal fun PlanHistorySection(events: List<TaskEvent>) {
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

    Text("最近事件", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    val recent = TaskHistory.recentEvents(events, limit = 50)
    if (recent.isEmpty()) {
        Text(
            "还没有任务历史。创建、安排、完成、改期、放回、删除等操作会在这里留下记录；已有数据的首次启动会自动补记可推断的历史。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    } else {
        recent.forEach { event ->
            ElevatedCard {
                Text(
                    TaskRecorder.displayText(event),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall
                )
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
