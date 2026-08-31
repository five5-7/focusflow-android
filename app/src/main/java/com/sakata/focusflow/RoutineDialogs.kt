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

/** 按星期自动命名：连续段合并为“周一至周四”，其余逐列（如“周五”“周六、周日”）。 */
internal fun autoGroupName(days: Set<Int>): String {
    val sorted = days.sorted()
    if (sorted.isEmpty()) return "未命名"
    val ranges = mutableListOf<Pair<Int, Int>>()
    var start = sorted.first()
    var prev = start
    for (day in sorted.drop(1)) {
        if (day == prev + 1) prev = day
        else { ranges += start to prev; start = day; prev = day }
    }
    ranges += start to prev
    return ranges.joinToString("、") { (a, b) -> if (a == b) weekdayName(a) else "${weekdayName(a)}至${weekdayName(b)}" }
}

/** 作息分组向导：设定作息 → 选中要应用的星期 → 未分配的星期继续建下一组 → 全部覆盖后按星期命名保存。 */
@Composable
internal fun DayGroupWizardDialog(existingGroups: List<DayGroup>, defaultWake: Int, defaultSleep: Int, defaultMeals: List<MealTimeline>, onDismiss: () -> Unit, onSave: (List<DayGroup>) -> Unit) {
    var groups by remember { mutableStateOf(existingGroups) }
    var draftDays by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var draftWake by remember { mutableIntStateOf(defaultWake.coerceIn(300, 720)) }
    var draftSleep by remember { mutableIntStateOf(defaultSleep.coerceIn(1200, 1500)) }
    var draftMeals by remember { mutableStateOf(defaultMeals) }
    val assignedDays = groups.flatMap { it.days }.toSet()
    val unassigned = (1..7).filter { it !in assignedDays }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("作息分组向导") },
        text = { Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("步骤：先设定本组作息 → 选中要应用的星期 → “确定这一组”；未分配的星期继续下一组，最后按星期自动命名保存。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                (1..7).forEach { day ->
                    val group = groups.firstOrNull { day in it.days }
                    Surface(shape = RoundedCornerShape(8.dp), color = if (group == null) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primaryContainer) {
                        Text(weekdayName(day), Modifier.padding(horizontal = 6.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            if (unassigned.isEmpty()) {
                Text("7 天都已分配完成，点“保存”完成。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            } else {
                Text("本组应用到的星期（剩余：${unassigned.joinToString("、") { weekdayName(it) }}）", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    unassigned.forEach { day -> FilterChip(selected = day in draftDays, onClick = { draftDays = if (day in draftDays) draftDays - day else draftDays + day }, label = { Text(weekdayName(day)) }) }
                }
                Text("本组作息：起床 ${formatMinute(draftWake)} · 睡觉 ${formatMinute(draftSleep)}", fontWeight = FontWeight.SemiBold)
                Text("起床 ${formatMinute(draftWake)}")
                Slider(value = draftWake.toFloat(), onValueChange = { draftWake = it.toInt() }, valueRange = 300f..720f, steps = 27)
                Text("睡觉 ${formatMinute(draftSleep)}")
                Slider(value = draftSleep.toFloat(), onValueChange = { draftSleep = it.toInt() }, valueRange = 1200f..1500f, steps = 29)
                MealType.entries.forEach { type ->
                    val meal = draftMeals.firstOrNull { it.type == type }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(type.label, Modifier.width(48.dp))
                        Slider(
                            value = (meal?.typicalStartMinute ?: 480).toFloat(),
                            onValueChange = { minute -> draftMeals = (draftMeals.filterNot { it.type == type } + MealTimeline(type, minute.toInt(), meal?.typicalMinutes ?: 20)).sortedBy { it.type.ordinal } },
                            valueRange = 360f..1320f,
                            steps = 31,
                            modifier = Modifier.weight(1f)
                        )
                        Text(formatMinute(meal?.typicalStartMinute ?: 480), style = MaterialTheme.typography.labelSmall)
                    }
                }
                Button(enabled = draftDays.isNotEmpty(), onClick = {
                    groups = groups + DayGroup(autoGroupName(draftDays), draftDays, draftWake, draftSleep, draftMeals.filter { it.type in MealType.entries })
                    // 每组需显式选择星期：清空选中，避免下一组默认占用所有剩余天数。
                    draftDays = emptySet()
                }, modifier = Modifier.fillMaxWidth()) { Text("确定这一组（${autoGroupName(draftDays)}）") }
            }
            if (groups.isNotEmpty()) {
                Text("已建分组", fontWeight = FontWeight.SemiBold)
                groups.forEach { group ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(group.label, fontWeight = FontWeight.SemiBold)
                            Text("${group.days.sorted().joinToString("、") { weekdayName(it) }} · 起床 ${formatMinute(group.wakeMinute)} · 睡觉 ${formatMinute(group.sleepMinute)}", style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { groups = groups.filterNot { it.label == group.label } }) { Text("删除") }
                    }
                }
            }
        } },
        confirmButton = { Button(onClick = { onSave(groups) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable internal fun StatusCheckInDialog(
    initialEnergy: String,
    initialActivity: String,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit
) {
    var energy by remember { mutableStateOf(initialEnergy.takeIf { it in StatusCheckInCatalog.energies } ?: "正常") }
    var activity by remember { mutableStateOf(initialActivity.takeIf { it in StatusCheckInCatalog.activities } ?: "空闲") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("记录现在状态") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("精力", fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatusCheckInCatalog.energies.forEach { value ->
                        FilterChip(selected = energy == value, onClick = { energy = value }, label = { Text(value) })
                    }
                }
                Text("正在做什么", fontWeight = FontWeight.Bold)
                StatusCheckInCatalog.activities.chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { value ->
                            FilterChip(selected = activity == value, onClick = { activity = value }, label = { Text(value) })
                        }
                    }
                }
                Text("这次记录只用于当前推荐和以后可选的本机学习，不会自动移动固定日程。", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { Button(onClick = { onSave(energy, activity) }) { Text("保存状态") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("暂不记录") } }
    )
}

/** 今日页「现在做什么」的活动状态记录：只问正在做什么；娱乐类可顺手设置收尾提醒（remindMinutes 为 null 表示不设提醒）。 */
@Composable internal fun ActivityStatusDialog(
    initialActivity: String,
    onDismiss: () -> Unit,
    onSave: (activity: String, remindMinutes: Int?) -> Unit
) {
    var activity by remember { mutableStateOf(initialActivity.takeIf { it in StatusCheckInCatalog.activities } ?: "空闲") }
    var remind by remember { mutableStateOf<Int?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("记录现在状态") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("正在做什么", fontWeight = FontWeight.Bold)
                StatusCheckInCatalog.activities.chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { value ->
                            FilterChip(selected = activity == value, onClick = { activity = value }, label = { Text(value) })
                        }
                    }
                }
                if (activity == "娱乐") {
                    HorizontalDivider()
                    Text("娱乐类活动要不要提醒收尾？", fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(30, 60, 90).forEach { minutes ->
                            FilterChip(selected = remind == minutes, onClick = { remind = minutes }, label = { Text("$minutes 分钟") })
                        }
                        FilterChip(selected = remind == null, onClick = { remind = null }, label = { Text("不设提醒") })
                    }
                    Text("选了时间后，到点会提醒你结束（可处理到点或延长）。", style = MaterialTheme.typography.bodySmall)
                }
                Text("这次记录只用于当前推荐和以后可选的本机学习，不会自动移动固定日程。", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { Button(onClick = { onSave(activity, if (activity == "娱乐") remind else null) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("暂不记录") } }
    )
}

@Composable internal fun BaselineTimePickButton(label: String, minute: Int, onChange: (Int) -> Unit) {
    val context = LocalContext.current
    OutlinedButton(onClick = {
        TimePickerDialog(context, { _, hour, minuteOfHour ->
            onChange(hour * 60 + minuteOfHour)
        }, minute / 60, minute % 60, true).show()
    }) { Text("$label ${formatMinute(minute)}") }
}

@Composable internal fun BaselineOnboardingDialog(initial: BaselineProfile, onDismiss: () -> Unit, onSave: (BaselineProfile) -> Unit) {
    var step by remember { mutableIntStateOf(0) }
    var lifeStage by remember(initial) { mutableStateOf(initial.lifeStage) }
    var wakeMinute by remember(initial) { mutableIntStateOf(initial.wakeMinute.takeIf { it >= 0 } ?: 7 * 60) }
    var sleepMinute by remember(initial) { mutableIntStateOf(initial.sleepMinute.takeIf { it >= 0 } ?: 23 * 60) }
    var meals by remember(initial) {
        mutableStateOf(initial.meals.ifEmpty {
            listOf(
                MealTimeline(MealType.BREAKFAST, 8 * 60 + 30),
                MealTimeline(MealType.LUNCH, 12 * 60),
                MealTimeline(MealType.DINNER, 18 * 60)
            )
        })
    }
    var entertainment by remember(initial) { mutableStateOf(initial.entertainmentWindow) }
    val steps = listOf("生活阶段", "作息", "餐点", "娱乐")
    AlertDialog(
        onDismissRequest = {},
        title = { Text(if (initial.isComplete) "编辑习惯基线" else "先了解你的大致节奏（可跳过）") },
        text = {
            ScrollableDialogBox(maxHeight = 480.dp, spacing = 10.dp) {
                Text("${steps[step]} · ${step + 1} / ${steps.size}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                when (step) {
                    0 -> {
                        Text("记录的目的是将来给出更合适的提醒与安排，现在只需大致回答。")
                        Text("当前生活阶段", fontWeight = FontWeight.Bold)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            LifeStage.entries.forEach { stage ->
                                FilterChip(selected = lifeStage == stage, onClick = { lifeStage = stage }, label = { Text(stage.label) })
                            }
                        }
                        Text("假期和开学后的作息会分开学习，避免互相干扰。", style = MaterialTheme.typography.bodySmall)
                    }
                    1 -> {
                        Text("大致起床与睡觉时间；不用精确到分钟。", style = MaterialTheme.typography.bodySmall)
                        BaselineTimePickButton("起床", wakeMinute) { wakeMinute = it }
                        BaselineTimePickButton("睡觉", sleepMinute) { sleepMinute = it }
                    }
                    2 -> {
                        Text("每餐大约什么时候开始、通常吃多久？只记大致时间，之后会按你的实际确认自动调整。", style = MaterialTheme.typography.bodySmall)
                        meals.forEach { meal ->
                            ElevatedCard {
                                Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(meal.type.label, fontWeight = FontWeight.SemiBold)
                                    BaselineTimePickButton("开始", meal.typicalStartMinute) { minute ->
                                        meals = meals.map { if (it.type == meal.type) it.copy(typicalStartMinute = minute) else it }
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text("时长", style = MaterialTheme.typography.bodySmall)
                                        listOf(15, 20, 30, 45).forEach { minutes ->
                                            FilterChip(selected = meal.typicalMinutes == minutes, onClick = { meals = meals.map { if (it.type == meal.type) it.copy(typicalMinutes = minutes) else it } }, label = { Text("$minutes 分钟") })
                                        }
                                    }
                                }
                            }
                        }
                    }
                    else -> {
                        Text("常见的娱乐或放松时段（可选），例如“19:00-21:30”。")
                        OutlinedTextField(value = entertainment, onValueChange = { entertainment = it }, label = { Text("娱乐时段（可选）") }, placeholder = { Text("例如：19:00-21:30") }, singleLine = true)
                        Text("不需要今天就填得很准；之后任何时间都可以回来修正。", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            if (step < steps.size - 1) Button(enabled = step != 0 || lifeStage != null, onClick = { step += 1 }) { Text("下一步") }
            else Button(enabled = lifeStage != null, onClick = {
                onSave(BaselineProfile(lifeStage = lifeStage, wakeMinute = wakeMinute, sleepMinute = sleepMinute, meals = meals, entertainmentWindow = entertainment.trim()))
            }) { Text("完成") }
        },
        dismissButton = {
            Row {
                if (step > 0) TextButton(onClick = { step -= 1 }) { Text("上一步") }
                TextButton(onClick = onDismiss) { Text(if (initial.isComplete) "取消" else "跳过") }
            }
        }
    )
}

@Composable internal fun BaselineEventsDialog(events: List<BaselineEvent>, onDismiss: () -> Unit, onClear: () -> Unit, onDelete: (Long) -> Unit) {
    var list by remember { mutableStateOf(events) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("原始事件记录") },
        text = {
            ScrollableDialogBox(maxHeight = 460.dp, spacing = 8.dp) {
                Text("这些是你确认过的原始记录，按时间追加保存；学习算法不会覆盖它们。", style = MaterialTheme.typography.bodySmall)
                if (list.isEmpty()) {
                    Text("还没有记录。完成引导、开始活动、签到或确认通勤后会自动出现在这里。")
                } else {
                    list.takeLast(50).reversed().forEach { event ->
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))) {
                            Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(BaselineRecorder.displayPayload(event), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = { list = list.filterNot { it.id == event.id }; onDelete(event.id) }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                            }
                        }
                    }
                    Text("共 ${list.size} 条记录，仅保存在本机。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = {
            if (list.isNotEmpty()) OutlinedButton(onClick = onClear) { Text("清除全部记录") }
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

@Composable internal fun MealPromptDialog(type: MealType, plan: MealPlan, onDismiss: () -> Unit, onStarted: () -> Unit, onSnooze: () -> Unit, onSkip: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("准备${type.label}？") },
        text = {
            Text(if (plan.learned) "你最近 ${plan.sampleCount} 次${type.label}常在 ${formatMinute(plan.startMinute)} 左右开始，平均约 ${plan.minutes} 分钟。确认“已在吃”后，我会按这个时长在 ${formatMinute(plan.startMinute + plan.minutes)} 提醒你确认结束。" else "按你填写的大致时间，${formatMinute(plan.startMinute)} 附近是${type.label}时间。确认“已在吃”后，我会在 ${formatMinute(plan.startMinute + plan.minutes)} 提醒你确认结束。")
        },
        confirmButton = {
            Button(onClick = onStarted) { Text("已在吃") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onSnooze) { Text("稍后 20 分钟") }
                TextButton(onClick = onSkip) { Text("今天不需要") }
            }
        }
    )
}

@Composable internal fun MealFinishDialog(record: MealRecord, type: MealType, onDismiss: () -> Unit, onFinished: (MealDraft) -> Unit, onStillEating: () -> Unit, onNoRecord: () -> Unit) {
    var amountText by remember(record.id) { mutableStateOf("") }
    var rating by remember(record.id) { mutableIntStateOf(0) }
    var note by remember(record.id) { mutableStateOf("") }
    var location by remember(record.id) { mutableStateOf("") }
    var category by remember(record.id) { mutableStateOf("") }
    var merchant by remember(record.id) { mutableStateOf("") }
    var payMethod by remember(record.id) { mutableStateOf("") }
    val amount = amountText.toIntOrNull()?.coerceIn(0, 9999) ?: -1
    val categories = listOf("食堂", "外卖", "自己做饭", "便利店", "其他")
    val payMethods = listOf("微信", "支付宝", "校园卡", "现金", "其他")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${type.label}吃完了吗？") },
        text = {
            ScrollableDialogBox(maxHeight = 460.dp, spacing = 8.dp) {
                Text(if (EXPENSE_HIDDEN) "结束并记录用餐时间；地点、分类、商家、支付方式、评价和备注都是可选的，只保存在本机，不会自动生成账目。" else "结束并记录用餐时间；地点、分类、商家、支付方式、金额、评价和备注都是可选的消费草稿，只保存在本机，不会自动生成账目。", style = MaterialTheme.typography.bodySmall)
                if (!EXPENSE_HIDDEN) OutlinedTextField(value = amountText, onValueChange = { amountText = it.filter(Char::isDigit).take(4) }, label = { Text("金额（元，可选）") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(value = location, onValueChange = { location = it.take(20) }, label = { Text("地点（可选）") }, placeholder = { Text("例如：大食堂、临水、外卖") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("宿舍", "食堂", "外卖", "便利店").forEach { quick ->
                        FilterChip(selected = location == quick, onClick = { location = if (location == quick) "" else quick }, label = { Text(quick) })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("分类", style = MaterialTheme.typography.bodySmall)
                    categories.forEach { item ->
                        FilterChip(selected = category == item, onClick = { category = if (category == item) "" else item }, label = { Text(item) })
                    }
                }
                OutlinedTextField(value = merchant, onValueChange = { merchant = it.take(30) }, label = { Text("商家／食物名（可选）") }, placeholder = { Text("例如：临水餐厅、麦香鸡套餐") }, singleLine = true)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("支付方式", style = MaterialTheme.typography.bodySmall)
                    payMethods.forEach { method ->
                        FilterChip(selected = payMethod == method, onClick = { payMethod = if (payMethod == method) "" else method }, label = { Text(method) })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("评价", style = MaterialTheme.typography.bodySmall)
                    listOf(1, 2, 3, 4, 5).forEach { star ->
                        FilterChip(selected = rating == star, onClick = { rating = if (rating == star) 0 else star }, label = { Text("$star") })
                    }
                    if (rating == 0) Text("不评价", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedTextField(value = note, onValueChange = { note = it.take(80) }, label = { Text("备注（可选）") }, singleLine = true)
            }
        },
        confirmButton = {
            Button(onClick = {
                onFinished(MealDraft(amount = amount, rating = rating, note = note.trim(), location = location.trim(), category = category, merchant = merchant.trim(), payMethod = payMethod))
            }) { Text("吃完了") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onStillEating) { Text("还在吃") }
                TextButton(onClick = onNoRecord) { Text("不记录") }
            }
        }
    )
}

@Composable internal fun MealRecordsDialog(records: List<MealRecord>, onDismiss: () -> Unit, onDelete: (Long) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("就餐记录") },
        text = {
            ScrollableDialogBox(maxHeight = 460.dp, spacing = 8.dp) {
                Text("记录按时间追加保存，只有你确认的开始与结束时间会用于饭点学习；地点、分类、商家、支付方式与评价只作为草稿保留，不会自动生成账目。", style = MaterialTheme.typography.bodySmall)
                if (records.isEmpty()) {
                    Text("还没有记录。开始吃饭并确认吃完后会自动出现在这里。")
                } else {
                    records.takeLast(50).reversed().forEach { record ->
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))) {
                            Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                val time = java.text.SimpleDateFormat("M月d日 HH:mm", java.util.Locale.CHINA)
                                val detail = buildString {
                                    append(record.mealType.label)
                                    append(" · ").append(time.format(java.util.Date(record.startedAt)))
                                    record.endedAt?.let { append("–").append(time.format(java.util.Date(it))) }
                                    if (record.location.isNotBlank()) append(" · ").append(record.location)
                                    if (record.category.isNotBlank()) append(" · ").append(record.category)
                                    if (record.merchant.isNotBlank()) append(" · ").append(record.merchant)
                                    if (record.amount > 0) append(" · ").append(record.amount).append(" 元")
                                    if (record.payMethod.isNotBlank()) append(" · ").append(record.payMethod)
                                    if (record.rating > 0) append(" · ").append(record.rating).append(" 星")
                                    if (record.note.isNotBlank()) append(" · ").append(record.note)
                                }
                                Text(detail, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = { onDelete(record.id) }) { Text("删除") }
                            }
                        }
                    }
                    Text("共 ${records.size} 条记录，仅保存在本机。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}
