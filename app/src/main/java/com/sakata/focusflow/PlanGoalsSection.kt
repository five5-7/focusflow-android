package com.sakata.focusflow

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
    store: PrototypeStore,
    onAddGoal: () -> Unit,
    onEditGoal: (Goal) -> Unit,
    onDeleteGoal: (Goal) -> Unit,
    onScheduleGoal: (Goal, GoalSuggestion) -> Unit,
    onChooseTime: (Goal) -> Unit,
    onAutoPlanGoals: () -> Unit,
    onCreateWanted: (String) -> Boolean,
    onEditWanted: (Goal, String, String, String) -> Boolean,
    onStartWanted: (Goal, String) -> Boolean,
    onAddPlanTask: (Goal, String) -> Boolean,
    onMovePlanTask: (Goal, Item, String) -> Boolean,
    onFocusPlanTask: (Goal, Item?) -> Boolean,
    onChangeState: (Goal, PlanState) -> Unit
) {
    val context = LocalContext.current
    var addingWanted by remember { mutableStateOf(false) }
    var wantedTitle by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<Goal?>(null) }
    var editTitle by remember { mutableStateOf("") }
    var editOutcome by remember { mutableStateOf("") }
    var editNotes by remember { mutableStateOf("") }
    var starting by remember { mutableStateOf<Goal?>(null) }
    var addingTaskTo by remember { mutableStateOf<Goal?>(null) }
    var taskTitle by remember { mutableStateOf("") }
    var review by remember { mutableStateOf(store.loadWantedReviewSettings()) }
    var reviewSettingsOpen by remember { mutableStateOf(false) }
    val wantedCount = goals.count { it.state == PlanState.WANTED }
    LaunchedEffect(wantedCount) {
        if (wantedCount > 0 && review.lastReviewedAt == 0L) {
            val started = review.copy(lastReviewedAt = System.currentTimeMillis())
            if (store.saveWantedReviewSettings(started)) review = started
        }
    }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("目标与执行", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        TextButton(onClick = onAddGoal) { Text("＋ 新增目标") }
    }
    TextButton(onClick = { addingWanted = true }) { Text("＋ 记下想做（只需名称）") }
    if (wantedCount > 0) {
        if (WantedReviewPolicy.due(review, wantedCount, System.currentTimeMillis())) {
            FocusCard(containerColor = MaterialTheme.colorScheme.secondaryContainer) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Text("想做列表已有 $wantedCount 项，可以集中看一眼。", style = MaterialTheme.typography.bodyMedium)
                    Text("仅在此页面提示，不逐项催促。", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = {
                        val updated = review.copy(lastReviewedAt = System.currentTimeMillis())
                        if (store.saveWantedReviewSettings(updated)) review = updated
                    }) { Text("已回顾") }
                }
            }
        }
        TextButton(onClick = { reviewSettingsOpen = true }) { Text("想做回顾：${if (review.enabled) "每 ${review.intervalMonths} 个月" else "已关闭"}") }
    }
    val active = goals.filter { it.state == PlanState.IN_PROGRESS }
    TextButton(onClick = onAutoPlanGoals, enabled = active.any { it.weeklyTarget > 0 }) { Text("按空挡自动排本周目标（本地判断）") }
    autoPlanMessage?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (goals.isEmpty()) Text("先记下想做的事；准备执行时再安排。")
    goals.filter { it.state == PlanState.WANTED }.takeIf { it.isNotEmpty() }?.let { wanted ->
        Text("想做 · ${wanted.size}", style = MaterialTheme.typography.titleMedium)
        wanted.forEach { plan ->
            FocusCard(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Text(plan.title, fontWeight = FontWeight.SemiBold)
                    if (plan.desiredOutcome.isNotBlank()) Text("期望结果：${plan.desiredOutcome}", style = MaterialTheme.typography.bodySmall)
                    if (plan.sourceNotes.isNotBlank()) Text(plan.sourceNotes, style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { taskTitle = ""; starting = plan }) { Text("开始") }
                        TextButton(onClick = {
                            editTitle = plan.title; editOutcome = plan.desiredOutcome; editNotes = plan.sourceNotes; editing = plan
                        }) { Text("编辑") }
                        TextButton(onClick = { onChangeState(plan, PlanState.PAUSED) }) { Text("暂停") }
                        TextButton(onClick = { onDeleteGoal(plan) }) { Text("删除") }
                    }
                }
            }
        }
    }
    if (active.isNotEmpty()) Text("进行中 · ${active.size}", style = MaterialTheme.typography.titleMedium)
    active.forEach { goal ->
        if (goal.weeklyTarget == 0) {
            FocusCard(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(goal.title, fontWeight = FontWeight.SemiBold)
                    if (goal.desiredOutcome.isNotBlank()) Text("期望结果：${goal.desiredOutcome}", style = MaterialTheme.typography.bodySmall)
                    if (goal.sourceNotes.isNotBlank()) Text(goal.sourceNotes, style = MaterialTheme.typography.bodySmall)
                    val linked = items.filter { it.goalId == goal.id && it.kind == "任务" }
                    val pending = linked.filterNot { it.done }
                    val near = pending.filter { it.planBucket != "later" }
                    val later = pending.filter { it.planBucket == "later" }
                    Text("近期任务 · ${near.size}", style = MaterialTheme.typography.labelMedium)
                    near.forEach { task -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(task.title + if (task.planFocus) " · 当前重点" else "", Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        TextButton(onClick = { onFocusPlanTask(goal, if (task.planFocus) null else task) }) { Text(if (task.planFocus) "取消重点" else "设为重点") }
                        TextButton(onClick = { onMovePlanTask(goal, task, "later") }) { Text("稍后") }
                    } }
                    Text("稍后 · ${later.size}", style = MaterialTheme.typography.labelMedium)
                    later.forEach { task -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(task.title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        TextButton(onClick = { onMovePlanTask(goal, task, "near") }) { Text("放入近期") }
                    } }
                    if (linked.any { it.done }) Text("已完成 ${linked.count { it.done }} 项", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { taskTitle = ""; addingTaskTo = goal }) { Text("＋近期任务") }
                        TextButton(onClick = { onChangeState(goal, PlanState.PAUSED) }) { Text("暂停") }
                        TextButton(onClick = { onChangeState(goal, PlanState.COMPLETED) }) { Text("完成") }
                    }
                }
            }
        } else {
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
            onChooseTime = onChooseTime,
            onChangeState = onChangeState
        )
        }
    }
    if (goals.any { it.state == PlanState.PAUSED }) Text(
        "暂停后不再参与自动排程；此前已安排的任务仍在日程中，可逐项调整。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    goals.filter { it.state == PlanState.PAUSED || it.state == PlanState.COMPLETED }
        .groupBy { it.state }.forEach { (state, plans) ->
            Text("${state.label} · ${plans.size}", style = MaterialTheme.typography.titleMedium)
            plans.forEach { plan ->
                FocusCard(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(plan.title, Modifier.weight(1f))
                        if (state == PlanState.PAUSED) {
                            TextButton(onClick = { onChangeState(plan, PlanState.IN_PROGRESS) }) { Text("继续") }
                            TextButton(onClick = { onChangeState(plan, PlanState.COMPLETED) }) { Text("完成") }
                        }
                    }
                }
            }
        }
    if (addingWanted) AppDialog(
        onDismissRequest = { addingWanted = false },
        title = { Text("记下想做") },
        text = { OutlinedTextField(wantedTitle, { wantedTitle = it }, label = { Text("名称") }, singleLine = true) },
        confirmButton = { Button(enabled = wantedTitle.isNotBlank(), onClick = {
            if (onCreateWanted(wantedTitle)) { wantedTitle = ""; addingWanted = false }
        }) { Text("保存") } },
        dismissButton = { TextButton(onClick = { addingWanted = false }) { Text("取消") } }
    )
    editing?.let { plan -> AppDialog(
        onDismissRequest = { editing = null }, title = { Text("编辑想做") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(editTitle, { editTitle = it }, label = { Text("名称") }, singleLine = true)
            OutlinedTextField(editOutcome, { editOutcome = it }, label = { Text("期望结果（选填）") })
            OutlinedTextField(editNotes, { editNotes = it }, label = { Text("原始笔记（选填）") })
        } },
        confirmButton = { Button(enabled = editTitle.isNotBlank(), onClick = {
            if (onEditWanted(plan, editTitle, editOutcome, editNotes)) editing = null
        }) { Text("保存") } },
        dismissButton = { TextButton(onClick = { editing = null }) { Text("取消") } }
    ) }
    starting?.let { plan -> AppDialog(
        onDismissRequest = { starting = null }, title = { Text("开始《${plan.title}》") },
        text = { OutlinedTextField(taskTitle, { taskTitle = it }, label = { Text("第一项近期任务（选填）") }, supportingText = { Text("可以稍后再添加；不会自动排入日程。") }) },
        confirmButton = { Button(enabled = taskTitle.trim().length <= 200, onClick = {
            if (onStartWanted(plan, taskTitle)) starting = null
        }) { Text("开始") } },
        dismissButton = { TextButton(onClick = { starting = null }) { Text("取消") } }
    ) }
    addingTaskTo?.let { plan -> AppDialog(
        onDismissRequest = { addingTaskTo = null }, title = { Text("添加近期任务") },
        text = { OutlinedTextField(taskTitle, { taskTitle = it }, label = { Text("任务名称") }, singleLine = true) },
        confirmButton = { Button(enabled = taskTitle.isNotBlank() && taskTitle.trim().length <= 200, onClick = {
            if (onAddPlanTask(plan, taskTitle)) addingTaskTo = null
        }) { Text("添加") } },
        dismissButton = { TextButton(onClick = { addingTaskTo = null }) { Text("取消") } }
    ) }
    if (reviewSettingsOpen) AppDialog(
        onDismissRequest = { reviewSettingsOpen = false }, title = { Text("想做列表回顾") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("只在打开计划页时显示到期摘要，不发送逐项通知。")
            listOf(0 to "关闭", 1 to "每月", 3 to "每三个月", 6 to "每六个月").forEach { (months, label) ->
                Row(Modifier.fillMaxWidth().clickable {
                        val updated = review.copy(enabled = months != 0,
                            intervalMonths = if (months == 0) review.intervalMonths else months,
                            lastReviewedAt = System.currentTimeMillis())
                        if (store.saveWantedReviewSettings(updated)) review = updated
                    }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = if (months == 0) !review.enabled else review.enabled && review.intervalMonths == months,
                        onClick = null)
                    Text(label)
                }
            }
        } },
        confirmButton = { TextButton(onClick = { reviewSettingsOpen = false }) { Text("完成") } }
    )
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
    // 收编：无显式底色的 Card → FocusCard，显式保留 Card 默认底色 surfaceContainerHighest。
    FocusCard(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest) {
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
    FocusCard(
        containerColor = if (favorite != null) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        }
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
    // 收编：ElevatedCard → FocusCard，显式保留 surfaceContainerLow 底色与 1dp 默认阴影。
    FocusCard(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        elevation = 1.dp
    ) {
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
    onChooseTime: (Goal) -> Unit,
    onChangeState: (Goal, PlanState) -> Unit
) {
    val suggestions = GoalPlanner.suggestions(goal, planningCourses, profile, items)
    // 收编：ElevatedCard → FocusCard，显式保留 surfaceContainerLow 底色与 1dp 默认阴影。
    FocusCard(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        elevation = 1.dp
    ) {
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { onChangeState(goal, PlanState.PAUSED) }) { Text("暂停") }
                TextButton(onClick = { onChangeState(goal, PlanState.COMPLETED) }) { Text("标记完成") }
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
