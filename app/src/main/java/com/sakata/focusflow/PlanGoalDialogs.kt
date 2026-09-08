package com.sakata.focusflow

import android.Manifest
import android.app.AlarmManager
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.pow
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 8.1.0 草稿保险箱：弹窗误关后重开恢复正在填写的内容（会话内内存保存）。 */
private data class GoalEditorDraft(val title: String, val weekly: String, val duration: String, val metricType: String, val metricTarget: String, val desiredOutcome: String, val resourceTitle: String, val resourceUnit: String, val firstAction: String)

private data class ResourceEditorDraft(val title: String, val url: String, val note: String)

private data class FeedbackDraft(val difficulty: String, val barrier: String)

private data class ImprovementDraft(val text: String)

@Composable internal fun GoalEditorDialog(initialGoal: Goal?, initialTitle: String = "", initialDurationMinutes: Int? = null, initialOutcome: String = "", resources: List<LearningResource>, suggestedFirstAction: String, courses: List<Course>, profile: CommuteProfile, items: List<Item>, onDismiss: () -> Unit, onOpenFinder: (String, String) -> Unit, onSave: (Goal) -> Unit) {
    val vault = LocalDraftVault.current
    val draftKey = "goalEdit:${initialGoal?.id ?: "new"}"
    // 编辑既有目标或带预填（任务转换/查找建议）打开时视为新意图：不恢复旧草稿，以当前初值为准。
    val saved = if (initialGoal == null && initialTitle.isBlank() && initialOutcome.isBlank() && initialDurationMinutes == null && suggestedFirstAction.isBlank()) vault.load<GoalEditorDraft>(draftKey) else null
    var title by remember(initialGoal?.id, initialTitle) { mutableStateOf(saved?.title ?: initialGoal?.title ?: initialTitle) }
    var weekly by remember(initialGoal?.id) { mutableStateOf(saved?.weekly ?: initialGoal?.weeklyTarget?.toString() ?: "3") }
    var duration by remember(initialGoal?.id, initialDurationMinutes) { mutableStateOf(saved?.duration ?: initialGoal?.durationMinutes?.toString() ?: initialDurationMinutes?.coerceIn(5, 240)?.toString() ?: "30") }
    var metricType by remember(initialGoal?.id) { mutableStateOf(saved?.metricType ?: initialGoal?.metricType ?: "时长") }
    var metricTarget by remember(initialGoal?.id) { mutableStateOf(saved?.metricTarget ?: initialGoal?.metricTarget ?: "30 分钟") }
    var desiredOutcome by remember(initialGoal?.id, initialOutcome) { mutableStateOf(saved?.desiredOutcome ?: initialGoal?.desiredOutcome ?: initialOutcome) }
    var resourceTitle by remember(initialGoal?.id) { mutableStateOf(saved?.resourceTitle ?: initialGoal?.resourceTitle.orEmpty()) }
    var resourceUnit by remember(initialGoal?.id) { mutableStateOf(saved?.resourceUnit ?: initialGoal?.resourceUnit.orEmpty()) }
    var firstAction by remember(initialGoal?.id, suggestedFirstAction) { mutableStateOf(saved?.firstAction ?: suggestedFirstAction.ifBlank { initialGoal?.firstAction.orEmpty() }) }
    fun persist() = vault.save(draftKey, GoalEditorDraft(title, weekly, duration, metricType, metricTarget, desiredOutcome, resourceTitle, resourceUnit, firstAction))
    val weeklyNumber = weekly.toIntOrNull()
    val durationNumber = duration.toIntOrNull()
    val suggestedMinimum = GoalPlanner.suggestedMinimum(metricType, metricTarget, durationNumber ?: 30)
    // 数据式建议：按本周课程空挡统计可安排次数与单次时长（扣除已有安排；数据不足时不显示）。
    val gapSuggest = remember(courses, profile, durationNumber, items) {
        val confirmed = courses.filter { !it.needsConfirmation }
        val gaps = CourseGapPlanner.gaps(confirmed, profile, occupiedByWeekday(items)).filter { it.minutesFree >= 10 }
        if (gaps.isEmpty() || durationNumber == null) null
        else {
            val fit = gaps.filter { it.minutesFree >= durationNumber }
            val median = gaps.map { it.minutesFree }.sorted().let { it[it.size / 2] }
            Triple(fit.size, median, gaps.size)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initialGoal == null) "新增目标" else "编辑目标") },
        text = { Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("先描述你希望得到的结果；详细计划可以稍后由应用根据空档生成。")
            OutlinedTextField(value = title, onValueChange = { title = it; persist() }, label = { Text("目标名称") }, singleLine = true)
            OutlinedTextField(value = desiredOutcome, onValueChange = { desiredOutcome = it; persist() }, label = { Text("预期结果（例如：能稳定完成每周锻炼）") }, minLines = 2)
            initialGoal?.sourceNotes?.takeIf { it.isNotBlank() }?.let { Text("原始备注：$it", style = MaterialTheme.typography.bodySmall) }
            OutlinedTextField(value = weekly, onValueChange = { weekly = it.filter(Char::isDigit); persist() }, label = { Text("每周次数") }, singleLine = true)
            OutlinedTextField(value = duration, onValueChange = { duration = it.filter(Char::isDigit); persist() }, label = { Text("预计占用分钟") }, singleLine = true)
            gapSuggest?.let { (fit, median, total) ->
                Text("本周课程空挡可安排约 $fit 次（共 $total 段空挡，中位单次约 $median 分钟）${if (fit < (weeklyNumber ?: 3)) "；与每周 $weeklyNumber 次相比略紧，可考虑降低时长或次数。" else ""}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("完成标准")
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) { listOf("时长", "次数", "成果").forEach { type -> FilterChip(selected = metricType == type, onClick = { metricType = type; persist() }, label = { Text(type) }) } }
            OutlinedTextField(value = metricTarget, onValueChange = { metricTarget = it; persist() }, label = { Text("例如：20 道题／读完一节／30 分钟") }, singleLine = true)
            OutlinedTextField(value = firstAction, onValueChange = { firstAction = it; persist() }, label = { Text("第一步行动（例如：打开题库先做第 1 题）") }, minLines = 2)
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) { Column(Modifier.padding(12.dp)) {
                Text("建议最低版本", fontWeight = FontWeight.SemiBold)
                Text(suggestedMinimum)
                Text("这是应用按目标类型与预计时长给出的保守起点；之后会结合教程和你的反馈调整。", style = MaterialTheme.typography.bodySmall)
            } }
            if (title.isNotBlank() || desiredOutcome.isNotBlank()) {
                OutlinedButton(onClick = { onOpenFinder(title.trim(), desiredOutcome.trim()) }, modifier = Modifier.fillMaxWidth()) { Text("搜学习教程（AI 建议 / 手动搜索）") }
            }
            Text("执行资料（可选，每个目标独立选择）", fontWeight = FontWeight.SemiBold)
            if (resources.isEmpty()) {
                Text("资料库为空；不添加资料也能正常执行。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                resources.forEach { resource ->
                    FilterChip(
                        selected = resourceTitle == resource.title,
                        onClick = { resourceTitle = if (resourceTitle == resource.title) "" else resource.title; persist() },
                        label = { Text(resource.title) }
                    )
                }
            }
            if (resourceTitle.isNotBlank()) {
                OutlinedTextField(value = resourceUnit, onValueChange = { resourceUnit = it; persist() }, label = { Text("教程章节／练习（可选）") }, singleLine = true)
            }
        } },
        confirmButton = { Button(enabled = title.isNotBlank() && desiredOutcome.isNotBlank() && metricTarget.isNotBlank() && weeklyNumber != null && durationNumber != null && weeklyNumber in 1..7 && durationNumber in 5..240, onClick = { vault.clear(draftKey); onSave(Goal(id = initialGoal?.id ?: System.currentTimeMillis(), title = title, weeklyTarget = weeklyNumber ?: 1, durationMinutes = durationNumber ?: 5, metricType = metricType, metricTarget = metricTarget, minimumVersion = suggestedMinimum, resourceTitle = resourceTitle, resourceUnit = resourceUnit, completedThisWeek = initialGoal?.completedThisWeek ?: 0, minimumCompletionsThisWeek = initialGoal?.minimumCompletionsThisWeek ?: 0, completionWeekKey = initialGoal?.completionWeekKey ?: GoalPlanner.currentWeekKey(), desiredOutcome = desiredOutcome, firstAction = firstAction, sourceNotes = initialGoal?.sourceNotes.orEmpty())) }) { Text(if (initialGoal == null) "创建" else "保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable internal fun ResourceEditorDialog(onDismiss: () -> Unit, onSave: (LearningResource) -> Unit) {
    val vault = LocalDraftVault.current
    val draftKey = "resourceNew"
    val saved = vault.load<ResourceEditorDraft>(draftKey)
    var title by remember { mutableStateOf(saved?.title ?: "") }
    var url by remember { mutableStateOf(saved?.url ?: "") }
    var note by remember { mutableStateOf(saved?.note ?: "") }
    fun persist() = vault.save(draftKey, ResourceEditorDraft(title, url, note))
    AlertDialog(onDismissRequest = onDismiss, title = { Text("收集教程") }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("只保存你已经确认过的真实链接、材料或笔记；保存后再到目标编辑器中按目标关联。")
        OutlinedTextField(value = title, onValueChange = { title = it; persist() }, label = { Text("教程名称") }, singleLine = true)
        OutlinedTextField(value = url, onValueChange = { url = it; persist() }, label = { Text("链接（可选）") }, singleLine = true)
        OutlinedTextField(value = note, onValueChange = { note = it; persist() }, label = { Text("材料说明或笔记（链接为空时必填）") }, minLines = 2)
    } }, confirmButton = { Button(enabled = LearningResourcePolicy.canSave(title, url, note), onClick = { vault.clear(draftKey); onSave(LearningResource(title = title.trim(), url = url.trim(), summary = note.trim())) }) { Text("确认保存") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable internal fun CompletionDialog(item: Item, goal: Goal?, onDismiss: () -> Unit, onComplete: (String) -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("如何完成了这项任务？") }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(item.title)
        Text("完整标准：${goal?.metricTarget ?: "完成本次"}")
        goal?.minimumVersion?.takeIf { it.isNotBlank() }?.let { Text("最低版本：$it") }
    } }, confirmButton = { Button(onClick = { onComplete("完整完成") }) { Text("完整完成") } }, dismissButton = { Row { goal?.minimumVersion?.takeIf { it.isNotBlank() }?.let { TextButton(onClick = { onComplete("最低版本") }) { Text("完成最低版本") } }; TextButton(onClick = onDismiss) { Text("取消") } } })
}

@Composable internal fun FeedbackDialog(level: String, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    val vault = LocalDraftVault.current
    // 签名中只有 level（无 item id，不能改签名/调用点），以 level 区分草稿实例。
    val draftKey = "feedback:$level"
    val saved = vault.load<FeedbackDraft>(draftKey)
    var difficulty by remember(level) { mutableStateOf(saved?.difficulty ?: "正常") }
    var barrier by remember(level) { mutableStateOf(saved?.barrier ?: "无") }
    fun persist() = vault.save(draftKey, FeedbackDraft(difficulty, barrier))
    AlertDialog(onDismissRequest = onDismiss, title = { Text("用几秒记录一下？") }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("$level。反馈用于调整下次安排，不用于评判。")
        Text("难度")
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { listOf("轻松", "正常", "吃力").forEach { value -> FilterChip(selected = difficulty == value, onClick = { difficulty = value; persist() }, label = { Text(value) }) } }
        Text("主要阻碍")
        listOf("无", "精力不足", "时间不够", "地点不合适", "被娱乐打断", "方法不清楚").chunked(3).forEach { row -> Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { row.forEach { value -> FilterChip(selected = barrier == value, onClick = { barrier = value; persist() }, label = { Text(value) }) } } }
    } }, confirmButton = { Button(onClick = { vault.clear(draftKey); onSave(difficulty, barrier) }) { Text("保存反馈") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("跳过") } })
}

@Composable internal fun ImprovementDialog(onDismiss: () -> Unit, onSave: (String) -> Unit) {
    val vault = LocalDraftVault.current
    val draftKey = "improvementNew"
    val saved = vault.load<ImprovementDraft>(draftKey)
    var text by remember { mutableStateOf(saved?.text ?: "") }
    fun persist() = vault.save(draftKey, ImprovementDraft(text))
    AlertDialog(onDismissRequest = onDismiss, title = { Text("记录改进想法") }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("例如：希望睡前模式在连续延期后自动提前减速提醒。")
        OutlinedTextField(value = text, onValueChange = { text = it; persist() }, placeholder = { Text("想深化、修复或新增什么？") }, minLines = 3)
    } }, confirmButton = { Button(enabled = text.isNotBlank(), onClick = { vault.clear(draftKey); onSave(text.trim()) }) { Text("保存") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
