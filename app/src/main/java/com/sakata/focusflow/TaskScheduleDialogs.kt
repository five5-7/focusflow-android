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

@Composable internal fun InboxEditDialog(item: Item, onDismiss: () -> Unit, onSave: (String, String, Int, String) -> Unit) {
    var title by remember(item.id) { mutableStateOf(item.title) }
    var detail by remember(item.id) { mutableStateOf(item.detail.removePrefix("刚刚记录 · ")) }
    var duration by remember(item.id) { mutableIntStateOf(item.durationMinutes.coerceIn(5, 360)) }
    var durationValid by remember(item.id) { mutableStateOf(true) }
    var priority by remember(item.id) { mutableStateOf(item.priority) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑收集箱项目") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("事情") }, singleLine = true)
            OutlinedTextField(value = detail, onValueChange = { detail = it }, label = { Text("备注（可选）") }, minLines = 2)
            Text("预计用时", fontWeight = FontWeight.SemiBold)
            key(item.id) {
                DurationPicker(
                    initialMinutes = item.durationMinutes.coerceIn(5, 360),
                    onChange = { parsed ->
                        if (parsed != null) { duration = parsed; durationValid = true } else durationValid = false
                    }
                )
            }
            Text("优先级", fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ItemPriority.entries.forEach { entry ->
                    FilterChip(
                        selected = priority == entry.storageKey,
                        onClick = { priority = entry.storageKey },
                        label = { Text(entry.label) }
                    )
                }
            }
        } },
        confirmButton = { Button(enabled = title.isNotBlank() && durationValid, onClick = { onSave(title.trim(), detail.trim().ifBlank { "稍后决定安排" }, duration, priority) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

internal fun dateAt(dayOffset: Int, hour: Int): Long {
    val calendar = java.util.Calendar.getInstance()
    calendar.add(java.util.Calendar.DAY_OF_YEAR, dayOffset)
    calendar.set(java.util.Calendar.HOUR_OF_DAY, hour)
    calendar.set(java.util.Calendar.MINUTE, 0)
    calendar.set(java.util.Calendar.SECOND, 0)
    calendar.set(java.util.Calendar.MILLISECOND, 0)
    return calendar.timeInMillis
}

internal data class ScheduleWindowOption(val label: String, val startsAt: Long, val endsAt: Long)

internal fun dateAtMinute(dayOffset: Int, minuteOfDay: Int): Long = java.util.Calendar.getInstance().apply {
    add(java.util.Calendar.DAY_OF_YEAR, dayOffset)
    set(java.util.Calendar.HOUR_OF_DAY, minuteOfDay / 60)
    set(java.util.Calendar.MINUTE, minuteOfDay % 60)
    set(java.util.Calendar.SECOND, 0)
    set(java.util.Calendar.MILLISECOND, 0)
}.timeInMillis

internal fun scheduleWindowOptions(now: Long = System.currentTimeMillis()): List<ScheduleWindowOption> {
    val earliest = ((now + 15 * 60_000L + 14 * 60_000L) / (15 * 60_000L)) * (15 * 60_000L)
    val todayAfternoon = ScheduleWindowOption("今天下午", maxOf(earliest, dateAtMinute(0, 13 * 60)), dateAtMinute(0, 18 * 60))
    val todayEvening = ScheduleWindowOption("今天晚上", maxOf(earliest, dateAtMinute(0, 18 * 60)), dateAtMinute(0, 23 * 60 + 30))
    val tomorrowMorning = ScheduleWindowOption("明天上午", dateAtMinute(1, 8 * 60), dateAtMinute(1, 12 * 60))
    val tomorrowAfternoon = ScheduleWindowOption("明天下午", dateAtMinute(1, 13 * 60), dateAtMinute(1, 18 * 60))
    val weekEnd = java.util.Calendar.getInstance().apply {
        timeInMillis = now
        val weekday = when (get(java.util.Calendar.DAY_OF_WEEK)) { java.util.Calendar.SUNDAY -> 7 else -> get(java.util.Calendar.DAY_OF_WEEK) - 1 }
        add(java.util.Calendar.DAY_OF_YEAR, 7 - weekday)
        set(java.util.Calendar.HOUR_OF_DAY, 23)
        set(java.util.Calendar.MINUTE, 30)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis
    val thisWeek = ScheduleWindowOption("本周内", earliest, weekEnd)
    return listOf(todayAfternoon, todayEvening, tomorrowMorning, tomorrowAfternoon, thisWeek)
        .filter { it.endsAt > it.startsAt + 15 * 60_000L }
}

@Composable internal fun FlexiblePlanDialog(
    item: Item,
    items: List<Item>,
    courses: List<Course>,
    energyLevel: String,
    profile: CommuteProfile,
    onDismiss: () -> Unit,
    onSelect: (FlexibleTimeSuggestion) -> Unit
) {
    var duration by remember(item.id) { mutableIntStateOf(item.durationMinutes.coerceIn(5, 360)) }
    var durationValid by remember(item.id) { mutableStateOf(true) }
    val suggestions = remember(item.id, duration) { FlexiblePlanner.suggestions(item.copy(durationMinutes = duration), items, courses, energyLevel, profile = profile) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("为弹性任务初步规划") },
        text = {
            ScrollableDialogBox(maxHeight = 520.dp, spacing = 10.dp) {
                Text(item.title, fontWeight = FontWeight.SemiBold)
                Text("预计用时", fontWeight = FontWeight.SemiBold)
                key(item.id) {
                    DurationPicker(
                        initialMinutes = item.durationMinutes.coerceIn(5, 360),
                        onChange = { parsed ->
                            if (parsed != null) { duration = parsed; durationValid = true } else durationValid = false
                        }
                    )
                }
                Text("这些时间已避开课程和定时任务，并保留前后缓冲。选择只是初步安排，之后仍可改期。", style = MaterialTheme.typography.bodySmall)
                if (suggestions.isEmpty()) {
                    Text("未来七天暂时没有足够连续的空档。任务会继续保留为弹性安排。")
                } else suggestions.forEach { suggestion ->
                    ElevatedCard(Modifier.fillMaxWidth().clickable { onSelect(suggestion) }) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(formatDateTime(suggestion.startsAt), fontWeight = FontWeight.Bold)
                            Text(suggestion.reason, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Text("提示：修改时长后建议会按新长度重新计算。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(enabled = durationValid, onClick = onDismiss) { Text("保持弹性") } }
    )
}

/** 任意分钟时长选择：少量常用快捷 chips + 自定义输入，统一 5–360；非法输入回调 null。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DurationPicker(initialMinutes: Int, onChange: (Int?) -> Unit) {
    var input by remember { mutableStateOf(initialMinutes.coerceIn(5, 360).toString()) }
    val parsed = input.toIntOrNull()?.takeIf { it in 5..360 }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            maxItemsInEachRow = 2,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf(15, 30, 60, 90).forEach { minutes ->
                FilterChip(
                    modifier = Modifier.weight(1f),
                    selected = parsed == minutes,
                    onClick = { input = minutes.toString(); onChange(minutes) },
                    label = { Text("$minutes 分", maxLines = 1) }
                )
            }
        }
        OutlinedTextField(
            value = input,
            onValueChange = { new ->
                input = new.filter(Char::isDigit).take(3)
                onChange(input.toIntOrNull()?.takeIf { it in 5..360 })
            },
            label = { Text("自定义分钟（5–360）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun InboxScheduleDialog(
    item: Item,
    items: List<Item>,
    courses: List<Course>,
    profile: CommuteProfile,
    energyLevel: String,
    onDismiss: () -> Unit,
    onSchedule: (Long, Int, String, String) -> Unit,
    onKeepWindow: (Long, Long, Int, String, String) -> Unit,
    initialExactTime: Long? = null
) {
    val context = LocalContext.current
    val existingWindow = if (item.windowStartAt != null && item.windowEndAt != null) ScheduleWindowOption("当前范围", item.windowStartAt, item.windowEndAt) else null
    var mode by remember(item.id, initialExactTime) { mutableStateOf(if (initialExactTime != null) "精确时间" else if (existingWindow == null) "推荐空档" else "大致时间") }
    var duration by remember(item.id) { mutableIntStateOf(item.durationMinutes.coerceIn(5, 360)) }
    var durationValid by remember(item.id) { mutableStateOf(true) }
    var priority by remember(item.id) { mutableStateOf(item.priority) }
    var selectedWindow by remember(item.id) { mutableStateOf(existingWindow) }
    var exactTime by remember(item.id, initialExactTime) { mutableStateOf(initialExactTime) }
    val windowOptions = scheduleWindowOptions().let { options -> if (existingWindow == null) options else listOf(existingWindow) + options }
    val planningItem = item.copy(
        durationMinutes = duration,
        scheduledAt = null,
        windowStartAt = if (mode == "大致时间") selectedWindow?.startsAt else null,
        windowEndAt = if (mode == "大致时间") selectedWindow?.endsAt else null
    )
    val suggestions = FlexiblePlanner.suggestions(planningItem, items, courses, energyLevel, profile = profile)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (item.kind == "收集箱") "安排收集箱任务" else "调整弹性安排") },
        text = {
            ScrollableDialogBox(maxHeight = 520.dp, spacing = 10.dp) {
                Text(item.title.removePrefix("重新安排："), fontWeight = FontWeight.SemiBold)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    maxItemsInEachRow = 2,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf("推荐空档", "大致时间", "精确时间").forEach { option ->
                        FilterChip(
                            modifier = Modifier.weight(1f),
                            selected = mode == option,
                            onClick = { mode = option },
                            label = { Text(option, maxLines = 1) }
                        )
                    }
                }
                Text("预计用时", fontWeight = FontWeight.SemiBold)
                key(item.id) {
                    DurationPicker(
                        initialMinutes = item.durationMinutes.coerceIn(5, 360),
                        onChange = { parsed ->
                            if (parsed != null) { duration = parsed; durationValid = true } else durationValid = false
                        }
                    )
                }
                Text("优先级", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ItemPriority.entries.forEach { entry ->
                        FilterChip(
                            selected = priority == entry.storageKey,
                            onClick = { priority = entry.storageKey },
                            label = { Text(entry.label) }
                        )
                    }
                }
                when (mode) {
                    "推荐空档" -> {
                        Text("参考已确认课程、未完成的定时任务和当前精力，并保留 15 分钟缓冲。", style = MaterialTheme.typography.bodySmall)
                        if (suggestions.isEmpty()) Text("未来七天没有足够连续的空档；可以改用大致时间继续保持弹性。")
                        suggestions.forEach { suggestion ->
                            ElevatedCard(Modifier.fillMaxWidth().clickable { onSchedule(suggestion.startsAt, duration, formatDateTime(suggestion.startsAt), priority) }) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text(formatDateTime(suggestion.startsAt), fontWeight = FontWeight.Bold)
                                    Text(suggestion.reason, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                    "大致时间" -> {
                        Text("只保存可接受的时间范围，不创建提醒，也不会在时间轴上伪装成固定日程。", style = MaterialTheme.typography.bodySmall)
                        windowOptions.forEach { option ->
                            FilterChip(
                                selected = selectedWindow == option,
                                onClick = { selectedWindow = option },
                                label = { Text("${option.label} · ${formatDateTime(option.startsAt)}–${formatTime(option.endsAt)}") }
                            )
                        }
                        selectedWindow?.let { window ->
                            val first = suggestions.firstOrNull()
                            Text(first?.let { "该范围内目前可优先考虑 ${formatDateTime(it.startsAt)}；保存范围后仍可稍后确认。" } ?: "该范围内暂时没有完整空档；可以先保存范围，日程变化后再尝试。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    else -> {
                        Text("选择一个明确时间后，任务会写入日程并创建提醒。", style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            listOf("明早 9:00" to dateAt(1, 9), "明晚 18:00" to dateAt(1, 18)).forEach { option ->
                                FilterChip(selected = exactTime == option.second, onClick = { exactTime = option.second }, label = { Text(option.first) })
                            }
                        }
                        OutlinedButton(onClick = {
                            val calendar = java.util.Calendar.getInstance()
                            DatePickerDialog(context, { _, year, month, day ->
                                TimePickerDialog(context, { _, hour, minute ->
                                    exactTime = java.util.Calendar.getInstance().apply {
                                        set(year, month, day, hour, minute, 0)
                                        set(java.util.Calendar.MILLISECOND, 0)
                                    }.timeInMillis
                                }, calendar.get(java.util.Calendar.HOUR_OF_DAY), calendar.get(java.util.Calendar.MINUTE), true).show()
                            }, calendar.get(java.util.Calendar.YEAR), calendar.get(java.util.Calendar.MONTH), calendar.get(java.util.Calendar.DAY_OF_MONTH)).show()
                        }) { Text(exactTime?.let { "已选：${formatDateTime(it)}" } ?: "自选日期与时间") }
                        exactTime?.let { chosen ->
                            conflictAdvice(chosen, duration, courses, items, profile, excludeId = item.id)?.let {
                                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            when (mode) {
                "大致时间" -> Button(enabled = selectedWindow != null && durationValid, onClick = { selectedWindow?.let { onKeepWindow(it.startsAt, it.endsAt, duration, it.label, priority) } }) { Text("保存范围") }
                "精确时间" -> {
                    val chosen = exactTime
                    val advice = chosen?.let { conflictAdvice(it, duration, courses, items, profile, excludeId = item.id) }
                    if (advice != null && chosen != null) {
                        val freeSlot = ScheduleOccupation.nextFreeSlot(
                            ScheduleOccupation.weekdayOf(chosen),
                            ScheduleOccupation.minuteOfDay(chosen),
                            duration, courses, items, profile, excludeId = item.id
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            freeSlot?.let { slot ->
                                OutlinedButton(enabled = durationValid, onClick = {
                                    val at = timeOnSameDayAs(chosen, slot)
                                    onSchedule(at, duration, formatDateTime(at), priority)
                                }) { Text("调整到 ${formatTime(timeOnSameDayAs(chosen, slot))} 并保存") }
                            }
                            Button(enabled = chosen > System.currentTimeMillis() && durationValid, onClick = { onSchedule(chosen, duration, formatDateTime(chosen), priority) }) { Text("仍要保存") }
                        }
                    } else Button(enabled = chosen?.let { it > System.currentTimeMillis() } == true && durationValid, onClick = { chosen?.let { onSchedule(it, duration, formatDateTime(it), priority) } }) { Text("确认安排") }
                }
                else -> {}
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable internal fun RescheduleTimeDialog(
    item: Item,
    items: List<Item>,
    courses: List<Course>,
    profile: CommuteProfile,
    onDismiss: () -> Unit,
    onSave: (Long, Int, String, String) -> Unit
) {
    val context = LocalContext.current
    var selected by remember { mutableStateOf(1) }
    var customTime by remember { mutableStateOf<Long?>(null) }
    var duration by remember { mutableIntStateOf(item.durationMinutes.coerceIn(5, 360)) }
    var durationValid by remember { mutableStateOf(true) }
    var priority by remember(item.id) { mutableStateOf(item.priority) }
    val options = listOf(
        Triple("明早 9:00", dateAt(1, 9), "明早 9:00"),
        Triple("明晚 18:00", dateAt(1, 18), "明晚 18:00"),
        Triple("后天 18:00", dateAt(2, 18), "后天 18:00")
    )
    val chosenTime = customTime ?: options[selected].second
    val advice = remember(selected, customTime, duration) {
        conflictAdvice(chosenTime, duration, courses, items, profile, excludeId = item.id)
    }
    val freeSlot = remember(selected, customTime, duration, advice) {
        advice?.let {
            ScheduleOccupation.nextFreeSlot(
                ScheduleOccupation.weekdayOf(chosenTime),
                ScheduleOccupation.minuteOfDay(chosenTime),
                duration, courses, items, profile, excludeId = item.id
            )
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("什么时候再提醒？") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(item.title.removePrefix("重新安排："))
            options.forEachIndexed { index, option -> FilterChip(selected = selected == index && customTime == null, onClick = { selected = index; customTime = null }, label = { Text(option.first) }) }
            TextButton(onClick = {
                val calendar = java.util.Calendar.getInstance()
                DatePickerDialog(context, { _, year, month, day ->
                    TimePickerDialog(context, { _, hour, minute ->
                        val chosen = java.util.Calendar.getInstance()
                        chosen.set(year, month, day, hour, minute, 0)
                        chosen.set(java.util.Calendar.MILLISECOND, 0)
                        customTime = chosen.timeInMillis
                    }, calendar.get(java.util.Calendar.HOUR_OF_DAY), calendar.get(java.util.Calendar.MINUTE), true).show()
                }, calendar.get(java.util.Calendar.YEAR), calendar.get(java.util.Calendar.MONTH), calendar.get(java.util.Calendar.DAY_OF_MONTH)).show()
            }) { Text(customTime?.let { "已选：${formatDateTime(it)}" } ?: "自选日期与时间") }
            Text("预计用时", fontWeight = FontWeight.SemiBold)
            key(item.id) {
                DurationPicker(
                    initialMinutes = item.durationMinutes.coerceIn(5, 360),
                    onChange = { parsed ->
                        if (parsed != null) { duration = parsed; durationValid = true } else durationValid = false
                    }
                )
            }
            Text("优先级", fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ItemPriority.entries.forEach { entry ->
                    FilterChip(
                        selected = priority == entry.storageKey,
                        onClick = { priority = entry.storageKey },
                        label = { Text(entry.label) }
                    )
                }
            }
            advice?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        } },
        confirmButton = {
            val original = {
                customTime?.let { onSave(it, duration, "${formatDateTime(it)} · ${duration}分钟", priority) }
                    ?: onSave(options[selected].second, duration, "${options[selected].third} · ${duration}分钟", priority)
            }
            if (advice != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    freeSlot?.let { slot ->
                        OutlinedButton(enabled = durationValid, onClick = {
                            val at = timeOnSameDayAs(chosenTime, slot)
                            onSave(at, duration, "${formatDateTime(at)} · ${duration}分钟", priority)
                        }) { Text("调整到 ${formatTime(timeOnSameDayAs(chosenTime, slot))} 并保存") }
                    }
                    Button(enabled = durationValid, onClick = original) { Text("仍要保存") }
                }
            } else Button(enabled = durationValid, onClick = original) { Text("确认安排") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/** 目标安排对话框：算法建议之外可按自己的日期与时间排目标（交互同改期弹窗）。 */
@Composable
internal fun GoalScheduleDialog(
    goal: Goal,
    items: List<Item>,
    courses: List<Course>,
    profile: CommuteProfile,
    onDismiss: () -> Unit,
    onScheduleAt: (Long) -> Unit
) {
    val context = LocalContext.current
    var selected by remember { mutableStateOf(1) }
    var customTime by remember { mutableStateOf<Long?>(null) }
    val options = listOf(
        Triple("明早 9:00", dateAt(1, 9), "明早 9:00"),
        Triple("明晚 18:00", dateAt(1, 18), "明晚 18:00"),
        Triple("后天 18:00", dateAt(2, 18), "后天 18:00")
    )
    val chosenTime = customTime ?: options[selected].second
    val pastTime = chosenTime < System.currentTimeMillis()
    val advice = remember(selected, customTime) {
        if (pastTime) "所选时间已过去；请选择今天稍后或之后的时间。"
        else conflictAdvice(chosenTime, goal.durationMinutes, courses, items, profile)
    }
    val freeSlot = remember(selected, customTime, advice) {
        advice?.let {
            ScheduleOccupation.nextFreeSlot(
                ScheduleOccupation.weekdayOf(chosenTime),
                ScheduleOccupation.minuteOfDay(chosenTime),
                goal.durationMinutes, courses, items, profile
            )
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("安排《${goal.title}》") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("每次 ${goal.durationMinutes} 分钟 · 定时后会写入日程并创建提醒。")
                options.forEachIndexed { index, option ->
                    FilterChip(selected = selected == index && customTime == null, onClick = { selected = index; customTime = null }, label = { Text(option.first) })
                }
                TextButton(onClick = {
                    val calendar = java.util.Calendar.getInstance()
                    val dateDialog = DatePickerDialog(context, { _, year, month, day ->
                        TimePickerDialog(context, { _, hour, minute ->
                            val chosen = java.util.Calendar.getInstance()
                            chosen.set(year, month, day, hour, minute, 0)
                            chosen.set(java.util.Calendar.MILLISECOND, 0)
                            customTime = chosen.timeInMillis
                        }, calendar.get(java.util.Calendar.HOUR_OF_DAY), calendar.get(java.util.Calendar.MINUTE), true).show()
                    }, calendar.get(java.util.Calendar.YEAR), calendar.get(java.util.Calendar.MONTH), calendar.get(java.util.Calendar.DAY_OF_MONTH))
                    // 禁止选过去日期：开始日期设为今天 00:00
                    dateDialog.datePicker.minDate = calendar.apply {
                        set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
                    }.timeInMillis
                    dateDialog.show()
                }) { Text(customTime?.let { "已选：${formatDateTime(it)}" } ?: "自选日期与时间") }
                advice?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            val original = {
                customTime?.let { onScheduleAt(it) } ?: onScheduleAt(options[selected].second)
            }
            if (pastTime) {
                // 过去时间不允许保存：改期弹窗也未必能救回，直接禁用。
                Button(enabled = false, onClick = original) { Text("确认安排") }
            } else if (advice != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    freeSlot?.let { slot ->
                        OutlinedButton(onClick = {
                            onScheduleAt(timeOnSameDayAs(chosenTime, slot))
                        }) { Text("调整到 ${formatTime(timeOnSameDayAs(chosenTime, slot))} 并保存") }
                    }
                    Button(onClick = original) { Text("仍要保存") }
                }
            } else Button(onClick = original) { Text("确认安排") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

internal fun formatTime(time: Long): String = java.text.SimpleDateFormat("HH:mm", java.util.Locale.CHINA).format(java.util.Date(time))

/** 与 target 同一天钟表 minute 的时刻（冲突一键"调整到 X:XX 并保存"用）。 */
internal fun timeOnSameDayAs(target: Long, minute: Int): Long =
    java.util.Calendar.getInstance().apply {
        timeInMillis = target
        set(java.util.Calendar.HOUR_OF_DAY, minute / 60)
        set(java.util.Calendar.MINUTE, minute % 60)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis

@Composable internal fun CourseEditorDialog(existing: Course?, places: List<CampusPlace>, onDismiss: () -> Unit, onSave: (Course) -> Unit) {
    var title by remember { mutableStateOf(existing?.title ?: "") }
    var weekday by remember { mutableIntStateOf(existing?.weekday ?: 1) }
    var startPeriod by remember { mutableStateOf(existing?.startPeriod?.toString() ?: "1") }
    var endPeriod by remember { mutableStateOf(existing?.endPeriod?.toString() ?: "1") }
    val availablePlaces = places.ifEmpty { ZijingangTravel.places }
    val existingPlace = availablePlaces.firstOrNull { it.name == existing?.building }
    var place by remember(availablePlaces, existing?.building) { mutableStateOf(existingPlace) }
    var customSelected by remember(existing?.building) { mutableStateOf(existing?.building != null && existingPlace == null) }
    var customName by remember(existing?.building) { mutableStateOf(if (existingPlace == null) (existing?.building ?: "") else "") }
    val parsedStart = startPeriod.toIntOrNull()
    val parsedEnd = endPeriod.toIntOrNull()
    val buildingName = if (customSelected) customName.trim() else (place?.name ?: "")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "新增课程" else "编辑课程") },
        text = { Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("课程名称") }, singleLine = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { (1..5).forEach { day -> FilterChip(selected = weekday == day, onClick = { weekday = day }, label = { Text(weekdayName(day)) }) } }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(modifier = Modifier.weight(1f), value = startPeriod, onValueChange = { startPeriod = it.filter(Char::isDigit) }, label = { Text("开始节次") }, singleLine = true)
                OutlinedTextField(modifier = Modifier.weight(1f), value = endPeriod, onValueChange = { endPeriod = it.filter(Char::isDigit) }, label = { Text("结束节次") }, singleLine = true)
            }
            Text("教学楼")
            availablePlaces.chunked(3).forEach { row -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { row.forEach { candidate -> FilterChip(selected = !customSelected && place == candidate, onClick = { place = candidate; customSelected = false }, label = { Text(candidate.name.removeSuffix("教学楼")) }) } } }
            FilterChip(selected = customSelected, onClick = { customSelected = true }, label = { Text("其他") })
            if (customSelected) OutlinedTextField(value = customName, onValueChange = { customName = it }, label = { Text("教学楼名称（自填，按东/西/北自动猜分区）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        } },
        confirmButton = { Button(enabled = title.isNotBlank() && parsedStart != null && parsedEnd != null && parsedStart in 1..13 && parsedEnd in parsedStart..13 && buildingName.isNotBlank(), onClick = {
            val zone = if (customSelected) CourseScreenshotParser.zoneByPrefix(buildingName) else (place?.zone ?: CampusZone.WEST_TEACHING)
            onSave(Course(title, weekday, parsedStart ?: 1, parsedEnd ?: 1, buildingName, zone, false))
        }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/** 快速记录解析草稿：预览确认后作为收集箱项保存（明天路径只取 title）。 */
internal data class QuickCaptureDraft(
    val title: String,
    val durationMinutes: Int? = null,
    val windowStartAt: Long? = null,
    val windowEndAt: Long? = null
)

/** 收集箱项描述：携带时段/时长时重组摘要，否则保持“刚刚记录”原文。 */
internal fun quickCaptureDetail(draft: QuickCaptureDraft): String {
    val parts = mutableListOf<String>()
    draft.windowStartAt?.let { start -> parts += "时段 ${formatDateTime(start)}–${formatTime(draft.windowEndAt!!)}" }
    draft.durationMinutes?.let { parts += "预计 $it 分钟" }
    parts += "稍后决定安排"
    return (if (draft.windowStartAt == null && draft.durationMinutes == null) "刚刚记录 · " else "") + parts.joinToString(" · ")
}

/**
 * 快速记录弹窗（6.7 预览式）：输入行实时用 QuickInputParser 解析，预览标题/时长/时段/精确时间；
 * 有精确时间时提供「直接安排」，确认后预置 InboxScheduleDialog 精确时间模式。
 * 「明天要做」优先级最高：勾选后忽略解析，走既有 kind="任务" dayOnly 路径。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun QuickCaptureDialog(onDismiss: () -> Unit, onSave: (QuickCaptureDraft, Boolean) -> Unit, onDirectSchedule: (QuickCaptureDraft, Long) -> Unit) {
    val context = LocalContext.current
    var text by remember { mutableStateOf("") }
    var tomorrow by remember { mutableStateOf(false) }
    val now = System.currentTimeMillis()
    val parsed = QuickInputParser.parse(text, now)
    // 用户 chips 覆盖解析值；输入变化时重置，避免旧调整串台
    var durationOverride by remember(text) { mutableStateOf<Int?>(null) }
    var durationValid by remember(text) { mutableStateOf(true) }
    var windowOverride by remember(text) { mutableStateOf<ScheduleWindowOption?>(null) }
    var exactOverride by remember(text) { mutableStateOf<Long?>(null) }
    val effectiveDuration = durationOverride ?: parsed.durationMinutes
    val effectiveWindow = windowOverride ?: parsed.windowStartAt?.let { start -> parsed.windowEndAt?.let { end -> ScheduleWindowOption(parsed.periodLabel ?: "时段", start, end) } }
    val effectiveExact = exactOverride ?: parsed.exactAt
    val windowOptions = scheduleWindowOptions(now)
    fun draft(): QuickCaptureDraft = QuickCaptureDraft(
        title = if (tomorrow) text.trim() else parsed.title,
        durationMinutes = effectiveDuration,
        windowStartAt = effectiveWindow?.startsAt,
        windowEndAt = effectiveWindow?.endsAt
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("快速记录") },
        text = { Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("先保存想法，安排可以以后再说；像“晚上看半小时高数”这样写，还能顺带提取时段和时长。")
            OutlinedTextField(value = text, onValueChange = { text = it }, placeholder = { Text("例如：晚上看半小时高数") }, singleLine = false)
            FilterChip(selected = tomorrow, onClick = { tomorrow = !tomorrow }, label = { Text("明天要做（不定时间）") })
            if (tomorrow) {
                Text("明天上午会温和提醒；你再决定具体什么时候做。", style = MaterialTheme.typography.bodySmall)
            } else if (effectiveWindow != null || effectiveDuration != null || effectiveExact != null) {
                Text("已解析：${parsed.title}${effectiveDuration?.let { " · 预计 $it 分钟" }.orEmpty()}${effectiveWindow?.let { " · ${it.label} ${formatDateTime(it.startsAt)}–${formatTime(it.endsAt)}" }.orEmpty()}${effectiveExact?.let { " · ${formatDateTime(it)}" }.orEmpty()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                Text("预计用时", fontWeight = FontWeight.SemiBold)
                key(text) {
                    DurationPicker(initialMinutes = effectiveDuration ?: 60, onChange = { parsedDuration ->
                        durationOverride = parsedDuration
                        durationValid = parsedDuration != null
                    })
                }
                Text("时段", fontWeight = FontWeight.SemiBold)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    maxItemsInEachRow = 2,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    windowOptions.forEach { option ->
                        FilterChip(
                            selected = effectiveWindow?.startsAt == option.startsAt && effectiveWindow?.endsAt == option.endsAt,
                            onClick = { windowOverride = if (effectiveWindow?.startsAt == option.startsAt && effectiveWindow?.endsAt == option.endsAt) null else option },
                            label = { Text(option.label, maxLines = 1) }
                        )
                    }
                }
                Text("精确时间", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    listOf("明早 9:00" to dateAt(1, 9), "明晚 18:00" to dateAt(1, 18)).forEach { option ->
                        FilterChip(selected = effectiveExact == option.second, onClick = { exactOverride = if (effectiveExact == option.second) null else option.second }, label = { Text(option.first) })
                    }
                }
                OutlinedButton(onClick = {
                    val calendar = java.util.Calendar.getInstance()
                    DatePickerDialog(context, { _, year, month, day ->
                        TimePickerDialog(context, { _, hour, minute ->
                            val picked = java.util.Calendar.getInstance().apply {
                                set(year, month, day, hour, minute, 0)
                                set(java.util.Calendar.MILLISECOND, 0)
                            }.timeInMillis
                            exactOverride = if (picked > now) picked else exactOverride
                        }, calendar.get(java.util.Calendar.HOUR_OF_DAY), calendar.get(java.util.Calendar.MINUTE), true).show()
                    }, calendar.get(java.util.Calendar.YEAR), calendar.get(java.util.Calendar.MONTH), calendar.get(java.util.Calendar.DAY_OF_MONTH)).show()
                }) { Text(effectiveExact?.let { "已选：${formatDateTime(it)}" } ?: "自选日期与时间") }
            }
        } },
        confirmButton = {
            if (!tomorrow && effectiveExact != null) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(enabled = text.isNotBlank(), onClick = { onSave(draft(), false) }) { Text("稍后决定") }
                Button(enabled = text.isNotBlank() && durationValid, onClick = { onDirectSchedule(draft(), effectiveExact) }) { Text("直接安排") }
            } else Button(enabled = text.isNotBlank(), onClick = { onSave(draft(), tomorrow) }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
