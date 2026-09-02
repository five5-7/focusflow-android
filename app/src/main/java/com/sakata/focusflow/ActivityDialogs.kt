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

/** 加号菜单：快速记录 / 安排空闲活动（触发方式，与原有入口不冲突）。 */
@Composable internal fun AddMenuDialog(onDismiss: () -> Unit, onQuickCapture: () -> Unit, onGamePlan: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            Card {
                Column(Modifier.fillMaxWidth().clickable(onClick = onQuickCapture).padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("快速记录", fontWeight = FontWeight.SemiBold)
                    Text("记一个想法，稍后再安排。", style = MaterialTheme.typography.bodySmall)
                }
            }
            Card {
                Column(Modifier.fillMaxWidth().clickable(onClick = onGamePlan).padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("安排空闲活动（时间）", fontWeight = FontWeight.SemiBold)
                    Text("游戏/视频/学习/休息/运动，按空闲安排时间，到点提醒开始与收尾（游戏/视频可检测前台）。", style = MaterialTheme.typography.bodySmall)
                }
            }
        } },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/** 活动标题提示：按类别给出示例。 */
internal fun activityTitleLabel(category: String): String = when (category) {
    "视频" -> "看什么（如：追剧/纪录片）"
    "学习" -> "学什么（如：复习高数）"
    "休息" -> "休息方式（如：午睡）"
    "运动" -> "运动项目（如：跑步）"
    "自定义" -> "活动名称"
    else -> "玩什么（如：原神）"
}

/** 安排空闲活动：类别 + 名称（可选，默认类别）+ 时长 + 建议/自定义时间；到点提醒开始（可选）与收尾。 */
@Composable internal fun GamePlanDialog(courses: List<Course>, profile: CommuteProfile, items: List<Item>, onDismiss: () -> Unit, onSave: (Item, GameSessionRecord) -> Unit) {
    val context = LocalContext.current
    var category by remember { mutableStateOf("游戏") }
    var title by remember { mutableStateOf("") }
    var duration by remember { mutableStateOf("60") }
    var selected by remember { mutableStateOf<GoalSuggestion?>(null) }
    var remindStart by remember { mutableStateOf(false) }
    var customTime by remember { mutableStateOf<Long?>(null) }
    val durationNumber = duration.toIntOrNull()
    val suggestions = remember(durationNumber, courses, profile, items) {
        GoalPlanner.suggestions(Goal(title = "活动", weeklyTarget = 1, durationMinutes = durationNumber ?: 60), courses, profile, occupiedByWeekday(items)).take(5)
    }
    val chosen = selected ?: customTime?.let { at ->
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = at }
        GoalSuggestion(weekdayOf(at), cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE), durationNumber ?: 60)
    } ?: suggestions.firstOrNull()
    val plannedAt = chosen?.let { s -> GoalPlanner.nextOccurrence(s.weekday, s.startMinute) }
    val plannedAdvice = remember(plannedAt, durationNumber) {
        plannedAt?.let { conflictAdvice(it, durationNumber ?: 60, courses, items, profile) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("安排空闲活动") },
        text = { ScrollableDialogBox(maxHeight = 480.dp, spacing = 8.dp) {
            Text("到点提醒开始（可选）；结束时按类别检测前台应用（游戏/视频）或直接提醒收尾，并记录实际结束与超时。", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                ScheduledActivityKind.selectableValues.forEach { c ->
                    FilterChip(selected = category == c, onClick = { category = c }, label = { Text(c) })
                }
            }
            OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text(activityTitleLabel(category)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("30", "60", "90").forEach { value ->
                    FilterChip(selected = duration == value, onClick = { duration = value }, label = { Text("$value 分钟") })
                }
            }
            OutlinedTextField(value = duration, onValueChange = { duration = it.filter(Char::isDigit).take(3) }, label = { Text("时长（分钟，5–300）") }, singleLine = true)
            FilterChip(selected = remindStart, onClick = { remindStart = !remindStart }, label = { Text(if (remindStart) "到点提醒开始 ✓" else "到点提醒开始（可选）") })
            HorizontalDivider()
            Text("建议时间（本周空闲时段，也可自定义）", fontWeight = FontWeight.SemiBold)
            if (suggestions.isEmpty() && customTime == null) {
                Text("本周暂无足够空闲时段；可点“自定义时间”自己选，或稍后到日程里改期。", style = MaterialTheme.typography.bodySmall)
            } else suggestions.forEach { suggestion ->
                FilterChip(selected = customTime == null && chosen?.weekday == suggestion.weekday && chosen?.startMinute == suggestion.startMinute, onClick = { customTime = null; selected = suggestion }, label = { Text("${weekdayName(suggestion.weekday)} ${GoalPlanner.displayTime(suggestion.startMinute)}（可用 ${suggestion.freeMinutes} 分钟）") }, modifier = Modifier.fillMaxWidth())
            }
            FilterChip(selected = customTime != null, onClick = {
                selected = null
                val cal = java.util.Calendar.getInstance()
                TimePickerDialog(context, { _, hour, minute ->
                    val chosenAt = java.util.Calendar.getInstance().apply {
                        set(java.util.Calendar.HOUR_OF_DAY, hour)
                        set(java.util.Calendar.MINUTE, minute)
                        set(java.util.Calendar.SECOND, 0)
                        set(java.util.Calendar.MILLISECOND, 0)
                        if (timeInMillis <= System.currentTimeMillis()) add(java.util.Calendar.DAY_OF_YEAR, 1)
                    }
                    customTime = chosenAt.timeInMillis
                }, cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE), true).show()
            }, label = { Text(customTime?.let { at ->
                val cal2 = java.util.Calendar.getInstance().apply { timeInMillis = at }
                "自定义：${GoalPlanner.displayTime(cal2.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal2.get(java.util.Calendar.MINUTE))}（最近一次）"
            } ?: "自定义时间…") }, modifier = Modifier.fillMaxWidth())
            plannedAdvice?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        } },
        confirmButton = {
            val d = durationNumber
            val s = chosen
            val durationOk = d != null && d in 5..300
            val finalTitle = title.trim().ifBlank { category }
            fun saveAt(at: Long) {
                val dd = d ?: return
                val ss = s ?: return
                val item = Item(title = finalTitle, detail = TaskScheduleText.activityDetail(category, at, dd), kind = "活动", scheduledAt = at, durationMinutes = dd)
                val session = GameSessionRecord(id = item.id, title = finalTitle, category = category, packageName = null, plannedStartAt = at, plannedEndAt = at + dd * 60_000L, remindStart = remindStart)
                onSave(item, session)
            }
            if (plannedAdvice != null && plannedAt != null) {
                val freeSlot = if (durationOk) ScheduleOccupation.nextFreeSlot(
                    ScheduleOccupation.weekdayOf(plannedAt),
                    ScheduleOccupation.minuteOfDay(plannedAt),
                    d, courses, items, profile
                ) else null
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    freeSlot?.let { slot ->
                        OutlinedButton(enabled = durationOk, onClick = {
                            saveAt(timeOnSameDayAs(plannedAt, slot))
                        }) { Text("调整到 ${formatTime(timeOnSameDayAs(plannedAt, slot))} 并保存") }
                    }
                    Button(enabled = durationOk, onClick = { saveAt(plannedAt) }) { Text("仍要保存") }
                }
            } else Button(enabled = durationOk && s != null, onClick = {
                plannedAt?.let { saveAt(it) }
            }) { Text("安排") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable internal fun ActivityDialog(
    suggestedNextStep: String,
    preset: ActivityLaunchPreset?,
    activityHistory: List<ActivitySession>,
    nextCommitment: ActivityCommitment?,
    energyLevel: String,
    onDismiss: () -> Unit,
    onStart: (category: String, name: String, endsAt: Long, nextStep: String) -> Unit
) {
    val context = LocalContext.current
    var category by remember(preset) { mutableStateOf(preset?.category ?: "游戏／娱乐") }
    var customName by remember(preset) { mutableStateOf(preset?.name.orEmpty()) }
    var timeMode by remember { mutableStateOf("时长") }
    var minutes by remember(preset) { mutableStateOf((preset?.minutes ?: 60).toString()) }
    var untilAt by remember { mutableLongStateOf(System.currentTimeMillis() + 60 * 60_000L) }
    var nextStep by remember(preset, suggestedNextStep) { mutableStateOf(preset?.nextStep ?: suggestedNextStep) }
    val activityName = when {
        preset != null -> customName.trim()
        category == "自定义" -> customName.trim()
        else -> category
    }
    val timeSuggestion = ActivityTimeAdvisor.suggest(
        category = category,
        name = activityName,
        history = activityHistory,
        nextCommitment = nextCommitment,
        energyLevel = energyLevel,
        plannedMinutes = preset?.minutes
    )
    val calculatedEnd = if (timeMode == "时长") System.currentTimeMillis() + (minutes.toIntOrNull()?.coerceIn(1, 600) ?: 60) * 60_000L else untilAt
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("开始活动") },
        text = {
            ScrollableDialogBox(maxHeight = 480.dp, spacing = 10.dp) {
                Text("先约定什么时候收尾，以及收尾后要去哪里。")
                preset?.let {
                    Text(if (it.minimumVersion) "已预填推荐任务的最低版本；结束活动后仍由你确认任务是否完成。" else "已预填推荐任务；结束活动后仍由你确认任务是否完成。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
                listOf("游戏／娱乐", "学习", "休息", "自定义").forEach { label ->
                    FilterChip(selected = category == label, onClick = { category = label }, label = { Text(label) })
                }
                if (category == "自定义" || preset != null) OutlinedTextField(value = customName, onValueChange = { customName = it }, label = { Text("活动名称") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = timeMode == "时长", onClick = { timeMode = "时长" }, label = { Text("预计时长") })
                    FilterChip(selected = timeMode == "截至", onClick = { timeMode = "截至" }, label = { Text("直到时间") })
                }
                if (timeMode == "时长") {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f))) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text("建议 ${timeSuggestion.minutes} 分钟 · 约 ${formatTime(System.currentTimeMillis() + timeSuggestion.minutes * 60_000L)} 结束", fontWeight = FontWeight.SemiBold)
                            Text(timeSuggestion.reason, style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { minutes = timeSuggestion.minutes.toString() }) { Text(if (minutes == timeSuggestion.minutes.toString()) "已采用建议" else "采用建议时间") }
                        }
                    }
                    OutlinedTextField(value = minutes, onValueChange = { minutes = it.filter(Char::isDigit).take(3) }, label = { Text("分钟") }, singleLine = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(15, 30, 60).forEach { value -> FilterChip(selected = minutes == value.toString(), onClick = { minutes = value.toString() }, label = { Text("$value 分") }) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(90, 120).forEach { value -> FilterChip(selected = minutes == value.toString(), onClick = { minutes = value.toString() }, label = { Text("$value 分") }) }
                    }
                } else {
                    OutlinedButton(onClick = {
                        val calendar = java.util.Calendar.getInstance().apply { timeInMillis = untilAt }
                        TimePickerDialog(context, { _, hour, minute ->
                            val chosen = java.util.Calendar.getInstance().apply {
                                set(java.util.Calendar.HOUR_OF_DAY, hour)
                                set(java.util.Calendar.MINUTE, minute)
                                set(java.util.Calendar.SECOND, 0)
                                set(java.util.Calendar.MILLISECOND, 0)
                                if (timeInMillis <= System.currentTimeMillis()) add(java.util.Calendar.DAY_OF_YEAR, 1)
                            }
                            untilAt = chosen.timeInMillis
                        }, calendar.get(java.util.Calendar.HOUR_OF_DAY), calendar.get(java.util.Calendar.MINUTE), true).show()
                    }) { Text("选择结束时间：${formatDateTime(untilAt)}") }
                }
                OutlinedTextField(value = nextStep, onValueChange = { nextStep = it }, label = { Text("结束后的下一步（可选）") }, placeholder = { Text("例如：洗漱，或开始复习") })
                if (suggestedNextStep.isNotBlank() && nextStep == suggestedNextStep) Text("已根据最近的固定安排或待办预填，可直接修改。", style = MaterialTheme.typography.labelSmall)
                Text("预计 ${formatDateTime(calculatedEnd)} 结束；到点不会自动判定失败，而是进入转场确认。", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { Button(enabled = activityName.isNotBlank() && calculatedEnd > System.currentTimeMillis(), onClick = { onStart(category, activityName, calculatedEnd, nextStep.trim()) }) { Text("开始活动") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable internal fun ActivityTransitionDialog(
    session: ActivitySession,
    maxExtensions: Int,
    upcomingCommitment: ActivityCommitment?,
    onDismiss: () -> Unit,
    onFinish: (actualEndAt: Long) -> Unit,
    onStartNext: () -> Unit,
    onExtend: (minutes: Int, reason: String) -> Unit,
    onReplan: () -> Unit
) {
    var extensionMinutes by remember { mutableIntStateOf(10) }
    var reason by remember { mutableStateOf("") }
    var endTimeChoice by remember { mutableStateOf("现在") }
    val canExtend = session.extensionCount < maxExtensions
    val extensionEnd = System.currentTimeMillis() + extensionMinutes * 60_000L
    val conflict = upcomingCommitment?.takeIf { it.startsAt < extensionEnd }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (System.currentTimeMillis() >= session.endsAt) "活动时间到了" else "结束或调整活动") },
        text = {
            ScrollableDialogBox(maxHeight = 480.dp, spacing = 10.dp) {
                Text("${session.name} · 原定 ${formatTime(session.endsAt)} 结束", fontWeight = FontWeight.SemiBold)
                if (System.currentTimeMillis() > session.endsAt + 60_000L) {
                    Text("实际什么时候结束？")
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = endTimeChoice == "现在", onClick = { endTimeChoice = "现在" }, label = { Text("刚刚") })
                        FilterChip(selected = endTimeChoice == "预计", onClick = { endTimeChoice = "预计" }, label = { Text("按预计时间") })
                    }
                }
                if (session.nextStep.isNotBlank()) {
                    Text("下一步：${session.nextStep}")
                    Button(onClick = onStartNext, modifier = Modifier.fillMaxWidth()) { Text("结束并开始下一步") }
                }
                HorizontalDivider()
                Text("需要更多时间")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(10, 20, 30).forEach { value -> FilterChip(selected = extensionMinutes == value, onClick = { extensionMinutes = value }, label = { Text("$value 分钟") }) }
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("还没结束", "不想停", "临时被打断").forEach { label -> FilterChip(selected = reason == label, onClick = { reason = label }, label = { Text(label) }) }
                }
                conflict?.let { Text("延长到 ${formatTime(extensionEnd)} 会碰到 ${formatTime(it.startsAt)} 的 ${it.title}；FocusFlow 不会自动改动它。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (canExtend) OutlinedButton(onClick = { onExtend(extensionMinutes, reason) }, modifier = Modifier.fillMaxWidth()) { Text("确认延长") }
                else Text("已达到设置中的连续延长提示上限。你仍可结束后重新开始，并重新作出约定。", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onReplan, modifier = Modifier.fillMaxWidth()) { Text("现在结束，但把下一步放回收集箱") }
            }
        },
        confirmButton = { Button(onClick = { onFinish(if (endTimeChoice == "预计") session.endsAt else System.currentTimeMillis()) }) { Text("确认结束") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("继续当前活动") } }
    )
}

internal data class ActivityLaunchPreset(
    val name: String,
    val category: String,
    val minutes: Int,
    val nextStep: String,
    val minimumVersion: Boolean
)
