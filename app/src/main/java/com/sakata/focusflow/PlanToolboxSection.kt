package com.sakata.focusflow

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

@Composable
internal fun PlanToolboxSection(
    resources: List<LearningResource>,
    goals: List<Goal>,
    tutorialSearch: TutorialSearchSettings,
    onAddResource: () -> Unit,
    onVideoAnalysis: () -> Unit,
    onSearchTutorial: () -> Unit,
    onSelectResource: (LearningResource) -> Unit,
    onDeselectResource: () -> Unit,
    onDeleteResource: (LearningResource) -> Unit,
    onSummarizeResource: (LearningResource) -> Unit,
    onApplyStandardToAll: () -> Unit
) {
    Text("只在需要时使用；没有资料也不影响创建、安排和完成目标。")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = onAddResource) { Text("＋ 收集资料") }
        TextButton(onClick = onVideoAnalysis) { Text("视频分析") }
    }
    TextButton(
        enabled = tutorialSearch.enabled && tutorialSearch.apiKey.isNotBlank(),
        onClick = onSearchTutorial
    ) { Text("生成学习路径建议") }
    ResourcesPanel(
        resources = resources,
        goals = goals,
        tutorialSearch = tutorialSearch,
        onSelectResource = onSelectResource,
        onDeselectResource = onDeselectResource,
        onDeleteResource = onDeleteResource,
        onSummarizeResource = onSummarizeResource,
        onApplyStandardToAll = onApplyStandardToAll
    )
}
