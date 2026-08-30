package com.sakata.focusflow

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun PlanGoalsSection(
    goals: List<Goal>,
    resources: List<LearningResource>,
    planningCourses: List<Course>,
    profile: CommuteProfile,
    items: List<Item>,
    feedback: List<TaskFeedback>,
    autoPlanMessage: String?,
    onAddGoal: () -> Unit,
    onEditGoal: (Goal) -> Unit,
    onDeleteGoal: (Goal) -> Unit,
    onScheduleGoal: (Goal, GoalSuggestion) -> Unit,
    onChooseTime: (Goal) -> Unit,
    onAutoPlanGoals: () -> Unit
) {
    val context = LocalContext.current
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("目标与执行", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        TextButton(onClick = onAddGoal) { Text("＋ 新增目标") }
    }
    TextButton(onClick = onAutoPlanGoals) { Text("按空挡自动排本周目标（本地判断）") }
    autoPlanMessage?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (goals.isEmpty()) Text("从预期结果、每周次数和单次时长开始。")
    goals.forEach { goal ->
        GoalExecutionCard(
            goal = goal,
            resources = resources,
            planningCourses = planningCourses,
            profile = profile,
            items = items,
            feedback = feedback,
            onOpenResource = { url ->
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            },
            onEditGoal = onEditGoal,
            onDeleteGoal = onDeleteGoal,
            onScheduleGoal = onScheduleGoal,
            onChooseTime = onChooseTime
        )
    }
}

@Composable
internal fun ResourcesPanel(
    resources: List<LearningResource>,
    tutorialSearch: TutorialSearchSettings,
    onSelectResource: (LearningResource) -> Unit,
    onDeselectResource: () -> Unit,
    onDeleteResource: (LearningResource) -> Unit,
    onSummarizeResource: (LearningResource) -> Unit
) {
    val favorite = resources.firstOrNull { it.selected }
    var expanded by remember { mutableStateOf(false) }
    Card {
        Column(
            Modifier.fillMaxWidth().clickable { expanded = !expanded }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "已收集 ${resources.size} 项" +
                        (favorite?.let { " · 常用：${it.title}" } ?: ""),
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    if (expanded) "收起 ▴" else "展开 ▾",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            if (expanded) {
                ResourceSetupHint(tutorialSearch)
                FavoriteResourceCard(favorite)
                Text(
                    "常用标记只帮助你在资料库里定位，不会自动套用到任何目标。请在目标编辑器中为每个目标单独选择资料。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (resources.isEmpty()) Text("尚未收集教程。", style = MaterialTheme.typography.bodySmall)
                resources.forEach { resource ->
                    ResourceCard(
                        resource,
                        tutorialSearch,
                        onSelectResource,
                        onDeselectResource,
                        onDeleteResource,
                        onSummarizeResource
                    )
                }
            }
        }
    }
}

@Composable
private fun ResourceSetupHint(settings: TutorialSearchSettings) {
    val message = when {
        !settings.enabled -> "在设置页开启“教程联网搜索”并填写硅基流动 key 后，可为学习目标生成学习路径建议。"
        settings.apiKey.isBlank() -> "已开启但未填 key：请到设置页填写硅基流动 API key。"
        else -> null
    }
    message?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun FavoriteResourceCard(favorite: LearningResource?) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (favorite != null) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
            }
        )
    ) {
        Column(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                favorite?.let { "常用资料：${it.title}" } ?: "尚未标记常用资料",
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "目标资料彼此独立；这个标记不会改变已有或新建目标。",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun ResourceCard(
    resource: LearningResource,
    settings: TutorialSearchSettings,
    onSelect: (LearningResource) -> Unit,
    onDeselect: () -> Unit,
    onDelete: (LearningResource) -> Unit,
    onSummarize: (LearningResource) -> Unit
) {
    ElevatedCard {
        Column(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(resource.title, fontWeight = FontWeight.SemiBold)
                    Text(resource.url, style = MaterialTheme.typography.bodySmall)
                }
                if (resource.selected) {
                    TextButton(onClick = onDeselect) { Text("取消常用") }
                } else {
                    TextButton(onClick = { onSelect(resource) }) { Text("标记常用") }
                }
                TextButton(onClick = { onDelete(resource) }) { Text("删除") }
            }
            if (resource.summary.isNotBlank()) {
                Text(
                    "AI 总结：${resource.summary}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (settings.enabled && settings.apiKey.isNotBlank()) {
                TextButton(onClick = { onSummarize(resource) }) { Text("AI 总结") }
            }
        }
    }
}

@Composable
private fun GoalExecutionCard(
    goal: Goal,
    resources: List<LearningResource>,
    planningCourses: List<Course>,
    profile: CommuteProfile,
    items: List<Item>,
    feedback: List<TaskFeedback>,
    onOpenResource: (String) -> Unit,
    onEditGoal: (Goal) -> Unit,
    onDeleteGoal: (Goal) -> Unit,
    onScheduleGoal: (Goal, GoalSuggestion) -> Unit,
    onChooseTime: (Goal) -> Unit
) {
    val suggestions = GoalPlanner.suggestions(goal, planningCourses, profile, occupiedByWeekday(items))
    ElevatedCard {
        Column(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(goal.title, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                TextButton(onClick = { onEditGoal(goal) }) { Text("编辑") }
                TextButton(onClick = { onDeleteGoal(goal) }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            }
            val completed = GoalPlanner.completedThisWeek(goal)
            val pending = items.count { it.goalId == goal.id && it.kind == "任务" && !it.done }
            val remaining = (goal.weeklyTarget - completed - pending).coerceAtLeast(0)
            if (goal.desiredOutcome.isNotBlank()) Text("预期结果：${goal.desiredOutcome}")
            Text("本周 $completed / ${goal.weeklyTarget} 次 · 已安排 $pending · 待安排 $remaining")
            Text(
                "每次 ${goal.durationMinutes} 分钟 · ${goal.metricType}：" +
                    goal.metricTarget.ifBlank { "完成本次" },
                style = MaterialTheme.typography.bodySmall
            )
            if (goal.minimumVersion.isNotBlank()) {
                Text("最低版本：${goal.minimumVersion}", style = MaterialTheme.typography.bodySmall)
            }
            if (goal.firstAction.isNotBlank()) {
                Text("第一步：${goal.firstAction}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            GoalResourceRow(goal, resources, onOpenResource)
            feedback.filter { it.goalId == goal.id && it.barrier != "无" }
                .groupingBy { it.barrier }
                .eachCount()
                .maxByOrNull { it.value }
                ?.let { (barrier, count) ->
                    Text("最近常见阻碍：$barrier（$count 次）", style = MaterialTheme.typography.bodySmall)
                }
            when {
                completed >= goal.weeklyTarget ->
                    Text("本周目标已达成。", color = MaterialTheme.colorScheme.primary)
                remaining == 0 -> Text("剩余次数均已安排，可在日程中逐次完成或改期。")
                else -> {
                    val suggestion = suggestions.firstOrNull { candidate ->
                        items.none { item ->
                            item.goalId == goal.id && !item.done &&
                                item.scheduledAt?.let {
                                    todayWeekday(it) == candidate.weekday &&
                                        minuteOfDay(it) == candidate.startMinute
                                } == true
                        }
                    }
                    if (suggestion == null) {
                        Text("暂未找到足够连续的空档。")
                    } else {
                        Text(
                            "建议：${weekdayName(suggestion.weekday)} " +
                                "${GoalPlanner.displayTime(suggestion.startMinute)}，" +
                                "可用 ${suggestion.freeMinutes} 分钟"
                        )
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(onClick = { onScheduleGoal(goal, suggestion) }) {
                                Text("安排第 ${completed + pending + 1} / ${goal.weeklyTarget} 次")
                            }
                            TextButton(onClick = { onChooseTime(goal) }) { Text("自定义时间") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GoalResourceRow(
    goal: Goal,
    resources: List<LearningResource>,
    onOpenResource: (String) -> Unit
) {
    if (goal.resourceTitle.isBlank()) {
        Text(
            "未设教程依据",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }
    val linked = resources.firstOrNull { it.title == goal.resourceTitle }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "依据：${goal.resourceTitle}" +
                (goal.resourceUnit.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f)
        )
        if (linked?.url?.isNotBlank() == true) {
            TextButton(onClick = { onOpenResource(linked.url) }) { Text("打开教程") }
        }
    }
}
