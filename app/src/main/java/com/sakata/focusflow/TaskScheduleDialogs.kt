package com.sakata.focusflow

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.Locale

/** 收集箱编辑草稿：关闭后重开恢复正在填写的内容（8.1.0 草稿保险箱）。 */
private data class InboxEditDraft(val title: String, val detail: String, val duration: Int, val durationValid: Boolean, val priority: String)

/** 弹性规划弹窗草稿（8.1.0 草稿保险箱）。 */
private data class FlexiblePlanDraft(val duration: Int, val durationValid: Boolean)

/** 安排/调整收集箱弹窗草稿（8.1.0 草稿保险箱）。 */
private data class InboxScheduleDraft(val mode: String, val duration: Int, val durationValid: Boolean, val priority: String, val selectedWindow: ScheduleWindowOption?, val exactTime: Long?)

/** 改期弹窗草稿（8.1.0 草稿保险箱）。 */
private data class RescheduleDraft(val selected: Int, val customTime: Long?, val duration: Int, val durationValid: Boolean, val priority: String)

/** 目标安排弹窗草稿（8.1.0 草稿保险箱）。 */
private data class GoalScheduleDraft(val selected: Int, val customTime: Long?)

/** 课程编辑弹窗草稿（8.1.0 草稿保险箱）。 */
private data class CourseEditorDraft(
    val title: String,
    val weekday: Int,
    val startPeriod: String,
    val lessonCount: String,
    val place: CampusPlace?,
    val customSelected: Boolean,
    val customName: String,
    val enabled: Boolean,
    val effectiveFrom: Long?,
    val effectiveUntil: Long?
)

/** 快速记录弹窗草稿（8.1.0 草稿保险箱；与保存用的 QuickCaptureDraft 区分）。 */
private data class QuickCaptureEditorDraft(
    val text: String,
    val tomorrow: Boolean,
    val durationOverride: Int?,
    val durationValid: Boolean,
    val windowOverride: ScheduleWindowOption?,
    val exactOverride: Long?
)

@Composable internal fun InboxEditDialog(item: Item, onDismiss: () -> Unit, onSave: (String, String, Int, String) -> Unit) {
    val vault = LocalDraftVault.current
    val draftKey = "inboxEdit:${item.id}"
    val saved = vault.load<InboxEditDraft>(draftKey)
    var title by remember(item.id) { mutableStateOf(saved?.title ?: item.title) }
    var detail by remember(item.id) { mutableStateOf(saved?.detail ?: item.editableNote()) }
    var duration by remember(item.id) { mutableIntStateOf(saved?.duration ?: item.durationMinutes.coerceIn(5, 360)) }
    var durationValid by remember(item.id) { mutableStateOf(saved?.durationValid ?: true) }
    var priority by remember(item.id) { mutableStateOf(saved?.priority ?: item.priority) }
    fun persist() = vault.save(draftKey, InboxEditDraft(title, detail, duration, durationValid, priority))
    AppDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑收集箱项目") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(value = title, onValueChange = { title = it; persist() }, label = { Text("事情") }, singleLine = true)
            OutlinedTextField(value = detail, onValueChange = { detail = it; persist() }, label = { Text("备注（可选）") }, minLines = 2)
            Text("预计用时", fontWeight = FontWeight.SemiBold)
            key(item.id) {
                DurationPicker(
                    initialMinutes = saved?.duration ?: item.durationMinutes.coerceIn(5, 360),
                    onChange = { parsed ->
                        if (parsed != null) { duration = parsed; durationValid = true } else durationValid = false
                        persist()
                    }
                )
            }
            Text("优先级", fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ItemPriority.entries.forEach { entry ->
                    FilterChip(
                        selected = priority == entry.storageKey,
                        onClick = { priority = entry.storageKey; persist() },
                        label = { Text(entry.label) }
                    )
                }
            }
        } },
        confirmButton = { Button(enabled = title.isNotBlank() && durationValid, onClick = {
            vault.clear(draftKey)
            onSave(title.trim(), detail.trim().ifBlank { "稍后决定安排" }, duration, priority)
        }) { Text("保存") } },
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
    val vault = LocalDraftVault.current
    val draftKey = "flexiblePlan:${item.id}"
    val saved = vault.load<FlexiblePlanDraft>(draftKey)
    var duration by remember(item.id) { mutableIntStateOf(saved?.duration ?: item.durationMinutes.coerceIn(5, 360)) }
    var durationValid by remember(item.id) { mutableStateOf(saved?.durationValid ?: true) }
    fun persist() = vault.save(draftKey, FlexiblePlanDraft(duration, durationValid))
    val suggestions = remember(item.id, duration) { FlexiblePlanner.suggestions(item.copy(durationMinutes = duration), items, courses, energyLevel, profile = profile) }
    AppDialog(
        onDismissRequest = onDismiss,
        title = { Text("为弹性任务初步规划") },
        text = {
            ScrollableDialogBox(maxHeight = 520.dp, spacing = 10.dp) {
                Text(item.title, fontWeight = FontWeight.SemiBold)
                Text("预计用时", fontWeight = FontWeight.SemiBold)
                key(item.id) {
                    DurationPicker(
                        initialMinutes = saved?.duration ?: item.durationMinutes.coerceIn(5, 360),
                        onChange = { parsed ->
                            if (parsed != null) { duration = parsed; durationValid = true } else durationValid = false
                            persist()
                        }
                    )
                }
                Text("这些时间已避开课程和定时任务，并保留前后缓冲。选择只是初步安排，之后仍可改期。", style = MaterialTheme.typography.bodySmall)
                if (suggestions.isEmpty()) {
                    Text("未来七天暂时没有足够连续的空档。任务会继续保留为弹性安排。")
                } else suggestions.forEach { suggestion ->
                    ElevatedCard(Modifier.fillMaxWidth().clickable { vault.clear(draftKey); onSelect(suggestion) }) {
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
    val vault = LocalDraftVault.current
    val draftKey = "inboxSchedule:${item.id}"
    // 预填了精确时间（快速记录「直接安排」）时视为新意图：不恢复旧草稿，以预填为初值
    val saved = if (initialExactTime == null) vault.load<InboxScheduleDraft>(draftKey) else null
    val context = LocalContext.current
    val existingWindow = if (item.windowStartAt != null && item.windowEndAt != null) ScheduleWindowOption("当前范围", item.windowStartAt, item.windowEndAt) else null
    var mode by remember(item.id, initialExactTime) { mutableStateOf(saved?.mode ?: if (initialExactTime != null) "精确时间" else if (existingWindow == null) "推荐空档" else "大致时间") }
    var duration by remember(item.id) { mutableIntStateOf(saved?.duration ?: item.durationMinutes.coerceIn(5, 360)) }
    var durationValid by remember(item.id) { mutableStateOf(saved?.durationValid ?: true) }
    var priority by remember(item.id) { mutableStateOf(saved?.priority ?: item.priority) }
    var selectedWindow by remember(item.id) { mutableStateOf(saved?.selectedWindow ?: existingWindow) }
    var exactTime by remember(item.id, initialExactTime) { mutableStateOf(saved?.exactTime ?: initialExactTime) }
    fun persist() = vault.save(draftKey, InboxScheduleDraft(mode, duration, durationValid, priority, selectedWindow, exactTime))
    val windowOptions = scheduleWindowOptions().let { options -> if (existingWindow == null) options else listOf(existingWindow) + options }
    val planningItem = item.copy(
        durationMinutes = duration,
        scheduledAt = null,
        windowStartAt = if (mode == "大致时间") selectedWindow?.startsAt else null,
        windowEndAt = if (mode == "大致时间") selectedWindow?.endsAt else null
    )
    val suggestions = FlexiblePlanner.suggestions(planningItem, items, courses, energyLevel, profile = profile)

    AppDialog(
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
                            onClick = { mode = option; persist() },
                            label = { Text(option, maxLines = 1) }
                        )
                    }
                }
                // **用时/优先级排在模式内容之后**：弹窗从系统窗口改成页内浮层（8.1.0 `e66b6c5`）之后，
                // 正文可视高度只剩约 480dp（要扣掉底栏高度与卡片内边距），
                // 而「预计用时 + 优先级」这两段固定内容约 230dp。它们原来排在模式内容**前面**，
                // 于是「精确时间 → 自选日期与时间」被挤到折叠线以下 ——
                // 维护者反馈「为收集箱任务安排时间时不能选择具体时间了」。
                // 模式内容是这个弹窗的正事，必须一进来就看得见；用时/优先级往后放。
                when (mode) {
                    "推荐空档" -> {
                        Text("参考已确认课程、未完成的定时任务和当前精力，并保留 15 分钟缓冲。", style = MaterialTheme.typography.bodySmall)
                        if (suggestions.isEmpty()) Text("未来七天没有足够连续的空档；可以改用大致时间继续保持弹性。")
                        suggestions.forEach { suggestion ->
                            ElevatedCard(Modifier.fillMaxWidth().clickable { vault.clear(draftKey); onSchedule(suggestion.startsAt, duration, formatDateTime(suggestion.startsAt), priority) }) {
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
                                onClick = { selectedWindow = option; persist() },
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
                                FilterChip(selected = exactTime == option.second, onClick = { exactTime = option.second; persist() }, label = { Text(option.first) })
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
                                    persist()
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
                // 次要设置放在模式内容之后（见上面的说明）：这里滚到看不到也不影响主流程。
                Text("预计用时", fontWeight = FontWeight.SemiBold)
                key(item.id) {
                    DurationPicker(
                        initialMinutes = saved?.duration ?: item.durationMinutes.coerceIn(5, 360),
                        onChange = { parsed ->
                            if (parsed != null) { duration = parsed; durationValid = true } else durationValid = false
                            persist()
                        }
                    )
                }
                Text("优先级", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ItemPriority.entries.forEach { entry ->
                        FilterChip(
                            selected = priority == entry.storageKey,
                            onClick = { priority = entry.storageKey; persist() },
                            label = { Text(entry.label) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            when (mode) {
                "大致时间" -> Button(enabled = selectedWindow != null && durationValid, onClick = { vault.clear(draftKey); selectedWindow?.let { onKeepWindow(it.startsAt, it.endsAt, duration, it.label, priority) } }) { Text("保存范围") }
                "精确时间" -> {
                    val chosen = exactTime
                    val advice = chosen?.let { conflictAdvice(it, duration, courses, items, profile, excludeId = item.id) }
                    if (advice != null && chosen != null) {
                        val freeSlot = ScheduleOccupation.nextFreeSlot(
                            ScheduleOccupation.weekdayOf(chosen),
                            ScheduleOccupation.minuteOfDay(chosen),
                            duration, courses, items, profile, excludeId = item.id, targetDay = chosen
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            freeSlot?.let { slot ->
                                OutlinedButton(enabled = durationValid, onClick = {
                                    vault.clear(draftKey)
                                    val at = timeOnSameDayAs(chosen, slot)
                                    onSchedule(at, duration, formatDateTime(at), priority)
                                }) { Text("调整到 ${formatTime(timeOnSameDayAs(chosen, slot))} 并保存") }
                            }
                            Button(enabled = chosen > System.currentTimeMillis() && durationValid, onClick = { vault.clear(draftKey); onSchedule(chosen, duration, formatDateTime(chosen), priority) }) { Text("仍要保存") }
                        }
                    } else Button(enabled = chosen?.let { it > System.currentTimeMillis() } == true && durationValid, onClick = { vault.clear(draftKey); chosen?.let { onSchedule(it, duration, formatDateTime(it), priority) } }) { Text("确认安排") }
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
    val vault = LocalDraftVault.current
    val draftKey = "reschedule:${item.id}"
    val saved = vault.load<RescheduleDraft>(draftKey)
    val context = LocalContext.current
    var selected by remember { mutableIntStateOf(saved?.selected ?: 1) }
    var customTime by remember { mutableStateOf<Long?>(saved?.customTime) }
    var duration by remember { mutableIntStateOf(saved?.duration ?: item.durationMinutes.coerceIn(5, 360)) }
    var durationValid by remember { mutableStateOf(saved?.durationValid ?: true) }
    var priority by remember(item.id) { mutableStateOf(saved?.priority ?: item.priority) }
    fun persist() = vault.save(draftKey, RescheduleDraft(selected, customTime, duration, durationValid, priority))
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
                duration, courses, items, profile, excludeId = item.id, targetDay = chosenTime
            )
        }
    }
    AppDialog(
        onDismissRequest = onDismiss,
        title = { Text("什么时候再提醒？") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(item.title.removePrefix("重新安排："))
            options.forEachIndexed { index, option -> FilterChip(selected = selected == index && customTime == null, onClick = { selected = index; customTime = null; persist() }, label = { Text(option.first) }) }
            TextButton(onClick = {
                val calendar = java.util.Calendar.getInstance()
                DatePickerDialog(context, { _, year, month, day ->
                    TimePickerDialog(context, { _, hour, minute ->
                        val chosen = java.util.Calendar.getInstance()
                        chosen.set(year, month, day, hour, minute, 0)
                        chosen.set(java.util.Calendar.MILLISECOND, 0)
                        customTime = chosen.timeInMillis
                        persist()
                    }, calendar.get(java.util.Calendar.HOUR_OF_DAY), calendar.get(java.util.Calendar.MINUTE), true).show()
                }, calendar.get(java.util.Calendar.YEAR), calendar.get(java.util.Calendar.MONTH), calendar.get(java.util.Calendar.DAY_OF_MONTH)).show()
            }) { Text(customTime?.let { "已选：${formatDateTime(it)}" } ?: "自选日期与时间") }
            Text("预计用时", fontWeight = FontWeight.SemiBold)
            key(item.id) {
                DurationPicker(
                    initialMinutes = saved?.duration ?: item.durationMinutes.coerceIn(5, 360),
                    onChange = { parsed ->
                        if (parsed != null) { duration = parsed; durationValid = true } else durationValid = false
                        persist()
                    }
                )
            }
            Text("优先级", fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ItemPriority.entries.forEach { entry ->
                    FilterChip(
                        selected = priority == entry.storageKey,
                        onClick = { priority = entry.storageKey; persist() },
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
                vault.clear(draftKey)
                customTime?.let { onSave(it, duration, "${formatDateTime(it)} · ${duration}分钟", priority) }
                    ?: onSave(options[selected].second, duration, "${options[selected].third} · ${duration}分钟", priority)
            }
            if (advice != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    freeSlot?.let { slot ->
                        OutlinedButton(enabled = durationValid, onClick = {
                            vault.clear(draftKey)
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
    val vault = LocalDraftVault.current
    val draftKey = "goalSchedule:${goal.id}"
    val saved = vault.load<GoalScheduleDraft>(draftKey)
    val context = LocalContext.current
    var selected by remember { mutableIntStateOf(saved?.selected ?: 1) }
    var customTime by remember { mutableStateOf<Long?>(saved?.customTime) }
    fun persist() = vault.save(draftKey, GoalScheduleDraft(selected, customTime))
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
                goal.durationMinutes, courses, items, profile, targetDay = chosenTime
            )
        }
    }
    AppDialog(
        onDismissRequest = onDismiss,
        title = { Text("安排《${goal.title}》") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("每次 ${goal.durationMinutes} 分钟 · 定时后会写入日程并创建提醒。")
                options.forEachIndexed { index, option ->
                    FilterChip(selected = selected == index && customTime == null, onClick = { selected = index; customTime = null; persist() }, label = { Text(option.first) })
                }
                TextButton(onClick = {
                    val calendar = java.util.Calendar.getInstance()
                    val dateDialog = DatePickerDialog(context, { _, year, month, day ->
                        TimePickerDialog(context, { _, hour, minute ->
                            val chosen = java.util.Calendar.getInstance()
                            chosen.set(year, month, day, hour, minute, 0)
                            chosen.set(java.util.Calendar.MILLISECOND, 0)
                            customTime = chosen.timeInMillis
                            persist()
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
                vault.clear(draftKey)
                customTime?.let { onScheduleAt(it) } ?: onScheduleAt(options[selected].second)
            }
            if (pastTime) {
                // 过去时间不允许保存：改期弹窗也未必能救回，直接禁用。
                Button(enabled = false, onClick = original) { Text("确认安排") }
            } else if (advice != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    freeSlot?.let { slot ->
                        OutlinedButton(onClick = {
                            vault.clear(draftKey)
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

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun CourseEditorDialog(existing: Course?, places: List<CampusPlace>, maxPeriod: Int = 13, onDismiss: () -> Unit, onOpenCommutePlaces: () -> Unit, onSave: (Course) -> Unit) {
    val vault = LocalDraftVault.current
    val draftKey = if (existing != null) "courseEdit:${existing.id}" else "addCourse"
    val saved = vault.load<CourseEditorDraft>(draftKey)
    val context = LocalContext.current
    var title by remember { mutableStateOf(saved?.title ?: (existing?.title ?: "")) }
    var weekday by remember { mutableIntStateOf(saved?.weekday ?: (existing?.weekday ?: 1)) }
    var startPeriod by remember { mutableStateOf(saved?.startPeriod ?: (existing?.startPeriod?.toString() ?: "1")) }
    var lessonCount by remember { mutableStateOf(saved?.lessonCount ?: (((existing?.endPeriod ?: 1) - (existing?.startPeriod ?: 1) + 1).toString())) }
    val availablePlaces = places
    val existingPlace = availablePlaces.firstOrNull { it.name == existing?.building }
    var place by remember(availablePlaces, existing?.building) { mutableStateOf(saved?.place ?: existingPlace) }
    var customSelected by remember(existing?.building) { mutableStateOf(saved?.customSelected ?: (existing?.building != null && existingPlace == null)) }
    var customName by remember(existing?.building) { mutableStateOf(saved?.customName ?: (if (existingPlace == null) (existing?.building ?: "") else "")) }
    var enabled by remember(existing) { mutableStateOf(saved?.enabled ?: (existing?.enabled ?: true)) }
    var effectiveFrom by remember(existing) { mutableStateOf(saved?.effectiveFrom ?: existing?.effectiveFromEpochDay) }
    var effectiveUntil by remember(existing) { mutableStateOf(saved?.effectiveUntil ?: existing?.effectiveUntilEpochDay) }
    fun persist() = vault.save(draftKey, CourseEditorDraft(title, weekday, startPeriod, lessonCount, place, customSelected, customName, enabled, effectiveFrom, effectiveUntil))
    val parsedStart = startPeriod.toIntOrNull()
    val parsedCount = lessonCount.toIntOrNull()
    val parsedEnd = parsedStart?.let { start -> parsedCount?.let { start + it - 1 } }
    val buildingName = if (customSelected) customName.trim() else (place?.name ?: "")
    AppDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "新增课程" else "编辑课程") },
        text = { Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = title, onValueChange = { title = it; persist() }, label = { Text("课程名称") }, singleLine = true)
            Text("课程会按星期、开始节和连续节数排入课表与日程；当前节次表共 $maxPeriod 节。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { (1..7).forEach { day -> FilterChip(selected = weekday == day, onClick = { weekday = day; persist() }, label = { Text(weekdayName(day)) }) } }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(modifier = Modifier.weight(1f), value = startPeriod, onValueChange = { startPeriod = it.filter(Char::isDigit); persist() }, label = { Text("第几节开始") }, singleLine = true)
                OutlinedTextField(modifier = Modifier.weight(1f), value = lessonCount, onValueChange = { lessonCount = it.filter(Char::isDigit); persist() }, label = { Text("连续几节") }, singleLine = true)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text("课程生效", fontWeight = FontWeight.SemiBold)
                    Text("关闭后保留课程资料，但不参与课表、日程和空挡计算。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = enabled, onCheckedChange = { enabled = it; persist() })
            }
            Text("生效期（可选）", fontWeight = FontWeight.SemiBold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = { showCourseDatePicker(context, effectiveFrom) { effectiveFrom = it; persist() } }) {
                    Text(effectiveFrom?.let { "开始 ${formatCourseDate(it)}" } ?: "设置开始日期")
                }
                OutlinedButton(onClick = { showCourseDatePicker(context, effectiveUntil) { effectiveUntil = it; persist() } }) {
                    Text(effectiveUntil?.let { "结束 ${formatCourseDate(it)}" } ?: "设置结束日期")
                }
                if (effectiveFrom != null || effectiveUntil != null) TextButton(onClick = { effectiveFrom = null; effectiveUntil = null; persist() }) { Text("清除生效期") }
            }
            if (effectiveFrom != null && effectiveUntil != null && effectiveFrom!! > effectiveUntil!!) {
                Text("结束日期不能早于开始日期", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Text("地点", fontWeight = FontWeight.SemiBold)
            Text("地点用于课程显示和已开启的出行时间估算；没有地点包时可直接自填。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            availablePlaces.chunked(3).forEach { row -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { row.forEach { candidate -> FilterChip(selected = !customSelected && place == candidate, onClick = { place = candidate; customSelected = false; persist() }, label = { Text(candidate.name.removeSuffix("教学楼")) }) } } }
            FilterChip(selected = customSelected, onClick = { customSelected = true; persist() }, label = { Text("其他") })
            if (customSelected) OutlinedTextField(value = customName, onValueChange = { customName = it; persist() }, label = { Text("地点名称（自填，按东/西/北自动猜分区）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            TextButton(onClick = onOpenCommutePlaces) { Text("管理地点与出行参数") }
        } },
        confirmButton = { Button(enabled = title.isNotBlank() && parsedStart != null && parsedCount != null && parsedEnd != null && parsedStart in 1..maxPeriod && parsedCount in 1..maxPeriod && parsedEnd in parsedStart..maxPeriod && buildingName.isNotBlank() && (effectiveFrom == null || effectiveUntil == null || effectiveFrom!! <= effectiveUntil!!), onClick = {
            vault.clear(draftKey)
            val zone = if (customSelected) CourseScreenshotParser.zoneByPrefix(buildingName) else (place?.zone ?: CampusZone.WEST_TEACHING)
            onSave(
                Course(
                    title = title,
                    weekday = weekday,
                    startPeriod = parsedStart ?: 1,
                    endPeriod = parsedEnd ?: 1,
                    building = buildingName,
                    zone = zone,
                    needsConfirmation = false,
                    enabled = enabled,
                    effectiveFromEpochDay = effectiveFrom,
                    effectiveUntilEpochDay = effectiveUntil,
                    id = existing?.id ?: newItemId()
                )
            )
        }) { Text("保存") } },
        dismissButton = {
            if (existing == null) TextButton(onClick = {
                vault.clear(draftKey)
                title = ""
                weekday = 1
                startPeriod = "1"
                lessonCount = "1"
                place = null
                customSelected = false
                customName = ""
                enabled = true
                effectiveFrom = null
                effectiveUntil = null
            }) { Text("清空") }
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

private fun formatCourseDate(epochDay: Long): String = java.time.LocalDate.ofEpochDay(epochDay).toString()

private fun showCourseDatePicker(context: Context, initialEpochDay: Long?, onSelect: (Long) -> Unit) {
    val initial = initialEpochDay?.let(java.time.LocalDate::ofEpochDay) ?: java.time.LocalDate.now()
    DatePickerDialog(
        context,
        { _, year, month, day -> onSelect(java.time.LocalDate.of(year, month + 1, day).toEpochDay()) },
        initial.year,
        initial.monthValue - 1,
        initial.dayOfMonth
    ).show()
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
    val vault = LocalDraftVault.current
    val draftKey = "quickCapture"
    val saved = vault.load<QuickCaptureEditorDraft>(draftKey)
    val context = LocalContext.current
    var text by remember { mutableStateOf(saved?.text ?: "") }
    var tomorrow by remember { mutableStateOf(saved?.tomorrow ?: false) }
    val now = System.currentTimeMillis()
    val parsed = QuickInputParser.parse(text, now)
    // 用户 chips 覆盖解析值；输入变化时重置，避免旧调整串台；草稿恢复只对草稿原文生效
    var durationOverride by remember(text) { mutableStateOf(if (text == saved?.text) saved?.durationOverride else null) }
    var durationValid by remember(text) { mutableStateOf(if (text == saved?.text) (saved?.durationValid ?: true) else true) }
    var windowOverride by remember(text) { mutableStateOf(if (text == saved?.text) saved?.windowOverride else null) }
    var exactOverride by remember(text) { mutableStateOf(if (text == saved?.text) saved?.exactOverride else null) }
    fun persist() = vault.save(draftKey, QuickCaptureEditorDraft(text, tomorrow, durationOverride, durationValid, windowOverride, exactOverride))
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
    AppDialog(
        onDismissRequest = onDismiss,
        title = { Text("快速记录") },
        text = { Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("先保存想法，安排可以以后再说；像“晚上看半小时高数”这样写，还能顺带提取时段和时长。")
            OutlinedTextField(value = text, onValueChange = {
                text = it
                durationOverride = null; durationValid = true; windowOverride = null; exactOverride = null
                persist()
            }, placeholder = { Text("例如：晚上看半小时高数") }, singleLine = false)
            FilterChip(selected = tomorrow, onClick = { tomorrow = !tomorrow; persist() }, label = { Text("明天要做（不定时间）") })
            if (tomorrow) {
                Text("明天上午会温和提醒；你再决定具体什么时候做。", style = MaterialTheme.typography.bodySmall)
            } else if (effectiveWindow != null || effectiveDuration != null || effectiveExact != null) {
                Text("已解析：${parsed.title}${effectiveDuration?.let { " · 预计 $it 分钟" }.orEmpty()}${effectiveWindow?.let { " · ${it.label} ${formatDateTime(it.startsAt)}–${formatTime(it.endsAt)}" }.orEmpty()}${effectiveExact?.let { " · ${formatDateTime(it)}" }.orEmpty()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                Text("预计用时", fontWeight = FontWeight.SemiBold)
                key(text) {
                    DurationPicker(initialMinutes = effectiveDuration ?: 60, onChange = { parsedDuration ->
                        durationOverride = parsedDuration
                        durationValid = parsedDuration != null
                        persist()
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
                            onClick = { windowOverride = if (effectiveWindow?.startsAt == option.startsAt && effectiveWindow?.endsAt == option.endsAt) null else option; persist() },
                            label = { Text(option.label, maxLines = 1) }
                        )
                    }
                }
                Text("精确时间", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    listOf("明早 9:00" to dateAt(1, 9), "明晚 18:00" to dateAt(1, 18)).forEach { option ->
                        FilterChip(selected = effectiveExact == option.second, onClick = { exactOverride = if (effectiveExact == option.second) null else option.second; persist() }, label = { Text(option.first) })
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
                            persist()
                        }, calendar.get(java.util.Calendar.HOUR_OF_DAY), calendar.get(java.util.Calendar.MINUTE), true).show()
                    }, calendar.get(java.util.Calendar.YEAR), calendar.get(java.util.Calendar.MONTH), calendar.get(java.util.Calendar.DAY_OF_MONTH)).show()
                }) { Text(effectiveExact?.let { "已选：${formatDateTime(it)}" } ?: "自选日期与时间") }
            }
        } },
        confirmButton = {
            if (!tomorrow && effectiveExact != null) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(enabled = text.isNotBlank(), onClick = { vault.clear(draftKey); onSave(draft(), false) }) { Text("稍后决定") }
                Button(enabled = text.isNotBlank() && durationValid, onClick = { vault.clear(draftKey); onDirectSchedule(draft(), effectiveExact) }) { Text("直接安排") }
            } else Button(enabled = text.isNotBlank(), onClick = { vault.clear(draftKey); onSave(draft(), tomorrow) }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = {
                vault.clear(draftKey)
                text = ""
                tomorrow = false
                durationOverride = null
                durationValid = true
                windowOverride = null
                exactOverride = null
            }) { Text("清空") }
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
