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

@Composable internal fun CampusMapHelpDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("如何制作地点包") },
        text = {
            ScrollableDialogBox(maxHeight = 480.dp, spacing = 10.dp) {
                Text("一般情况下不需要自己制作地点包。紫金港目录由应用提供；自动地图搜索和地图点选接入后，它们会成为默认方式。", color = MaterialTheme.colorScheme.primary)
                Text("以下格式只用于迁移、备份或批量维护：", fontWeight = FontWeight.SemiBold)
                Text("1. 用任意文本编辑器新建 UTF-8 文件，并保存为 .json。")
                Text("2. 填写地点包名称和地点列表。每个地点需要名称、所属分区和类型。")
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))) {
                    Text(
                        "{\n  \"name\": \"我的校园\",\n  \"version\": 1,\n  \"places\": [\n    { \"name\": \"西1教学楼\", \"zone\": \"WEST_TEACHING\", \"kind\": \"教学楼\" },\n    { \"name\": \"图书馆\", \"zone\": \"LIBRARY\", \"kind\": \"学习\" }\n  ]\n}",
                        Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Text("分区代码")
                CampusZone.entries.forEach { zone -> Text("${zone.name}：${zone.label}", style = MaterialTheme.typography.bodySmall) }
                Text("3. 回到这里点击“导入地点包（JSON）”，从系统文件选择器选中该文件。")
                Text("导入前会检查版本、地点数量、重复名称和分区代码；失败时不会覆盖当前地点包。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                HorizontalDivider()
                Text("后续地图流程", fontWeight = FontWeight.Bold)
                Text("自动搜索会围绕所选校区获取教学楼、图书馆、运动、餐饮、宿舍与交通 POI，并根据地图类型推断用途。若结果缺失，用户只需在地图上点一下；逆地理编码会建议名称，用户确认即可。", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text("知道了") } }
    )
}

@Composable internal fun RouteCalibrationDialog(from: CampusPlace, to: CampusPlace, mode: String, currentMinutes: Int, history: List<Int>, onDismiss: () -> Unit, onSave: (Int) -> Unit) {
    var minutes by remember(from, to, mode) { mutableStateOf(currentMinutes.toString()) }
    val parsed = minutes.toIntOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("确认本次通勤耗时") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${from.name} → ${to.name} · $mode")
                Text("填写从出发到到达的实际总分钟数，包含进出楼和找教室时间。不读取定位。")
                if (history.isNotEmpty()) Text("已有 ${history.size} 次确认记录；学习值使用最近记录的中位数，单次异常不会直接覆盖结果。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                OutlinedTextField(value = minutes, onValueChange = { minutes = it.filter(Char::isDigit).take(3) }, label = { Text("实际分钟数") }, singleLine = true)
                Text("保存后，同一出行方式和分区组合会新增一条确认记录；课程空挡、目标候选时间与路线预览会使用学习后的中位数。", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { Button(enabled = parsed != null && parsed in 1..180, onClick = { parsed?.let(onSave) }) { Text("保存本次记录") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable internal fun CampusPlacePickerDialog(title: String, places: List<CampusPlace>, selectedName: String?, onDismiss: () -> Unit, onSelect: (CampusPlace) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            ScrollableDialogBox(maxHeight = 420.dp, spacing = 6.dp) {
                places.groupBy(CampusPlace::kind).forEach { (kind, groupedPlaces) ->
                    Text(kind, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    groupedPlaces.forEach { place ->
                        OutlinedButton(onClick = { onSelect(place) }, modifier = Modifier.fillMaxWidth()) {
                            Text(if (place.name == selectedName) "✓ ${place.name}" else place.name)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable internal fun AddInstalledAppDialog(onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    val context = LocalContext.current
    val installed = remember {
        runCatching {
            val pm = context.packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(intent, 0)
                .map { it.activityInfo.packageName }
                .distinct()
                .filter { it != context.packageName }
                .map { pkg -> pkg to AppLibrary.appLabel(context, pkg) }
                .sortedBy { it.second }
        }.getOrDefault(emptyList())
    }
    var query by remember { mutableStateOf("") }
    var selectedPkg by remember { mutableStateOf<String?>(null) }
    val filtered = if (query.isBlank()) installed else installed.filter { it.first.contains(query, true) || it.second.contains(query, true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加本机应用") },
        text = {
            Column(Modifier.heightIn(max = 440.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("搜索应用名或包名") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                val selected = selectedPkg
                if (selected == null) {
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        filtered.take(80).forEach { (pkg, label) ->
                            Card(modifier = Modifier.fillMaxWidth().clickable { selectedPkg = pkg }) {
                                Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(label, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
                                    Text(pkg, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                } else {
                    Text("选择分类（《${AppLibrary.appLabel(context, selected)}》）：", style = MaterialTheme.typography.bodySmall)
                    val auto = autoCategoryByLabel(AppLibrary.appLabel(context, selected))
                    if (auto != null) Text("按应用名识别为：${auto.label}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        listOf(AppCategory.GAME, AppCategory.VIDEO, AppCategory.SOCIAL, AppCategory.STUDY, AppCategory.OTHER, AppCategory.UNKNOWN).forEach { c ->
                            FilterChip(selected = false, onClick = { onSave(selected, c.name); onDismiss() }, label = { Text(c.label) })
                        }
                    }
                    TextButton(onClick = { selectedPkg = null }) { Text("返回列表") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

internal enum class SettingsSubPage(val title: String) {
    ADVANCED("高级工具"),
    ROADMAP("版本路线图"), CAMPUS_PLACES("校园地点"), COMMUTE_PLACES("通勤与地点"), TUTORIAL_SEARCH("学习路径建议"),
    COURSE_VISION("课表识别（视觉模型）"), APP_DETECTION("前台应用检测"), STABILITY("稳定性与崩溃"),
    APPEARANCE("外观"), ACTIVITY_REMINDERS("日程与活动提醒"), QUIET_HOURS("提醒打扰控制"), CUSTOM_THEME("自定义主题"),
    AI_WEEKLY_SUMMARY("AI 周总结")
}

internal fun categorizedInstalledApps(context: Context, userCategories: Map<String, String>): List<Triple<String, String, AppCategory>> = runCatching {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    pm.queryIntentActivities(intent, 0)
        .map { it.activityInfo.packageName }
        .distinct()
        .filter { it != context.packageName }
        .map { pkg -> Triple(pkg, AppLibrary.appLabel(context, pkg), AppLibrary.categoryOf(context, pkg, userCategories)) }
        .sortedBy { it.second }
}.getOrDefault(emptyList())

@Composable internal fun SettingsScreen(modifier: Modifier, settingsScrollState: ScrollState, themeOption: FocusFlowThemeOption, commuteProfile: CommuteProfile, campusLifeEnabled: Boolean, campusMapPackage: CampusMapPackage?, currentCampusPlace: String?, improvementNotes: List<ImprovementNote>, activitySettings: ActivityReminderSettings, statusCheckInSettings: StatusCheckInSettings, windDownEnabled: Boolean, checkIns: List<StatusCheckIn>, baselineProfile: BaselineProfile, mealRecords: List<MealRecord>, mealReminderEnabled: Boolean, subPage: SettingsSubPage?, onSubPageChange: (SettingsSubPage?) -> Unit, onThemeChange: (FocusFlowThemeOption) -> Unit, customThemeColors: FocusFlowThemeColors, onCustomThemeColorsChange: (FocusFlowThemeColors) -> Unit, themePresets: List<ThemePreset>, onThemePresetsChange: (List<ThemePreset>) -> Unit, onRestoreDefaultTheme: () -> Unit, onCommuteChange: (CommuteProfile) -> Unit, onCampusLifeEnabledChange: (Boolean) -> Unit, onCampusMapPackageChange: (CampusMapPackage?) -> Unit, onCurrentCampusPlaceChange: (String?) -> Unit, allPlaces: List<CampusPlace>, customPlaces: List<CampusPlace>, onCustomPlacesChange: (List<CampusPlace>) -> Unit, hiddenPlaces: Set<String>, onToggleHiddenPlace: (String) -> Unit, amapKey: String, onAmapKeyChange: (String) -> Unit, campusCenter: CampusCenter, onCampusCenterChange: (CampusCenter) -> Unit, tutorialSearch: TutorialSearchSettings, onTutorialSearchSettingsChange: (TutorialSearchSettings) -> Unit, aiWeeklySummary: AiWeeklySummarySettings, onAiWeeklySummarySettingsChange: (AiWeeklySummarySettings) -> Unit, courseVision: CourseVisionSettings, onCourseVisionSettingsChange: (CourseVisionSettings) -> Unit, courseVisionGuideOpen: Boolean, onCourseVisionGuideOpenChange: (Boolean) -> Unit, pendingPlaces: List<String>, onAddPendingPlace: (String) -> Unit, onRemovePendingPlace: (String) -> Unit, onActivitySettingsChange: (ActivityReminderSettings) -> Unit, quietHours: QuietHoursSettings, onQuietHoursChange: (QuietHoursSettings) -> Unit, quickCaptureEnabled: Boolean, onQuickCaptureEnabledChange: (Boolean) -> Unit, onStatusCheckInSettingsChange: (StatusCheckInSettings) -> Unit, onWindDownEnabledChange: (Boolean) -> Unit, onAddImprovement: () -> Unit, onOpenBaselineEditor: () -> Unit, onOpenBaselineEvents: () -> Unit, onResetBaseline: () -> Unit, onOpenFeatureIntro: () -> Unit, baselineVariants: List<BaselineProfile>, onSaveBaselineVariant: (String) -> Unit, onSwitchBaselineVariant: (BaselineProfile) -> Unit, onDeleteBaselineVariant: (BaselineProfile) -> Unit, onDayGroupsChange: (List<DayGroup>) -> Unit, baselineVariantNameOpen: Boolean, onBaselineVariantNameOpenChange: (Boolean) -> Unit, onMealReminderEnabledChange: (Boolean) -> Unit, onOpenMealRecords: () -> Unit, recordBaselineEvent: (BaselineEventType, String) -> Unit, gameDetectionEnabled: Boolean, onGameDetectionEnabledChange: (Boolean) -> Unit, appCategories: Map<String, String>, onAppCategoriesChange: (Map<String, String>) -> Unit, hiddenApps: Set<String>, onToggleHiddenApp: (String) -> Unit, videoAnalysisModel: String, onVideoAnalysisModelChange: (String) -> Unit, darkMode: Boolean, onDarkModeChange: (Boolean) -> Unit, onGlobalLoadingChange: (Boolean) -> Unit) {
    val context = LocalContext.current
    val settingsStore = remember(context) { PrototypeStore(context) }
    val settingsLifecycleOwner = LocalLifecycleOwner.current
    var settingsNotificationHealth by remember { mutableStateOf(NotificationChannelSettings.health(context)) }
    DisposableEffect(settingsLifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) settingsNotificationHealth = NotificationChannelSettings.health(context)
        }
        settingsLifecycleOwner.lifecycle.addObserver(observer)
        onDispose { settingsLifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val campusPlaces = allPlaces
    var importStatus by remember { mutableStateOf<String?>(null) }
    var campusMapHelpOpen by remember { mutableStateOf(false) }
    var advancedCampusImportOpen by remember { mutableStateOf(false) }
    var routeCalibrationTarget by remember { mutableStateOf<Pair<CampusPlace, CampusPlace>?>(null) }
    var choosingCurrentPlace by remember { mutableStateOf(false) }
    var choosingDestination by remember { mutableStateOf(false) }
    var helpBlock by remember { mutableStateOf<SettingsBlock?>(null) }
    var baselineVariantsExpanded by remember { mutableStateOf(false) }
    var dayGroupWizardOpen by remember { mutableStateOf(false) }
    var previewDestination by remember(campusPlaces, currentCampusPlace) {
        mutableStateOf(campusPlaces.firstOrNull { it.name != currentCampusPlace }?.name)
    }
    val importCampusMap = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    ?: throw IllegalArgumentException("无法读取文件")
                CampusMapPackageCodec.parse(text)
            }.onSuccess { imported ->
                onCampusMapPackageChange(imported)
                importStatus = "已导入 ${imported.name}，共 ${imported.places.size} 个地点"
            }.onFailure { error ->
                importStatus = "导入失败：${error.message ?: "文件格式不正确"}"
            }
        }
    }
    Box(modifier.fillMaxSize()) {
    AnimatedVisibility(
        visible = subPage == null,
        enter = slideInHorizontally(animationSpec = tween(260), initialOffsetX = { -it / 4 }) + fadeIn(tween(180)),
        exit = slideOutHorizontally(animationSpec = tween(220), targetOffsetX = { -it / 4 }) + fadeOut(tween(150))
    ) {
    ScrollableWithBar(scrollState = settingsScrollState) {
        Text("设置", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
        PlanHubItem("外观", "当前主题：${themeOption.label}") { onSubPageChange(SettingsSubPage.APPEARANCE) }
        HorizontalDivider()
        PlanHubItem(
            "日程与活动提醒",
            when {
                !settingsNotificationHealth.allReadableSettingsReady -> "通知或横幅待检查"
                !activitySettings.notificationsEnabled -> "活动提醒已关闭"
                else -> "日程提前 ${activitySettings.scheduleAdvanceMinutes} 分钟"
            }
        ) { onSubPageChange(SettingsSubPage.ACTIVITY_REMINDERS) }
        HorizontalDivider()
        PlanHubItem("提醒打扰控制", if (quietHours.enabled) "免打扰 ${formatMinute(quietHours.startMinute)}–${formatMinute(quietHours.endMinute)}" else if (quietHours.isMuted()) "已静音" else "未开启") { onSubPageChange(SettingsSubPage.QUIET_HOURS) }
        HorizontalDivider()
        SettingSwitch("常驻快速记录通知", "通知栏常驻一条通知，随时一键快速记录到收集箱；关闭后通知消失", quickCaptureEnabled, onQuickCaptureEnabledChange)
        HorizontalDivider()
        SettingsSectionHeader("状态询问", onHelp = { helpBlock = SettingsBlock.STATUS_CHECK_IN })
        SettingSwitch("每日低打扰询问", "询问精力与当前活动；关闭后不会删除已有记录", statusCheckInSettings.enabled) {
            onStatusCheckInSettingsChange(statusCheckInSettings.copy(enabled = it))
        }
        if (statusCheckInSettings.enabled) {
            Text("每天约 ${statusCheckInSettings.promptHour}:00 询问")
            Slider(
                value = statusCheckInSettings.promptHour.toFloat(),
                onValueChange = { onStatusCheckInSettingsChange(statusCheckInSettings.copy(promptHour = it.toInt(), promptHourAutoAdjusted = false)) },
                valueRange = 8f..22f,
                steps = 13
            )
            if (statusCheckInSettings.promptHourAutoAdjusted) {
                Text("已自动调整：根据你的 ${checkIns.size} 次签到设为 ${statusCheckInSettings.promptHour}:00（手动调整后不再自动）", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
            Text("主动选择稍后时，推迟 ${statusCheckInSettings.snoozeMinutes} 分钟")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(30, 60, 120).forEach { minutes ->
                    FilterChip(
                        selected = statusCheckInSettings.snoozeMinutes == minutes,
                        onClick = { onStatusCheckInSettingsChange(statusCheckInSettings.copy(snoozeMinutes = minutes)) },
                        label = { Text("$minutes 分钟") }
                    )
                }
            }
            CheckInInsights.suggestedPromptHour(checkIns)?.let { hour ->
                if (hour != statusCheckInSettings.promptHour) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("根据你的 ${checkIns.size} 次签到，建议询问时间设为 ${hour}:00", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = { onStatusCheckInSettingsChange(statusCheckInSettings.copy(promptHour = hour, promptHourAutoAdjusted = false)) }) { Text("采纳") }
                    }
                }
            }
        }
        HorizontalDivider()
        SettingsSectionHeader("睡前减速", onHelp = { helpBlock = SettingsBlock.WIND_DOWN })
        SettingSwitch("睡前减速提醒", "每晚按你填写的睡觉时间提前 40 分钟提醒开始收尾；关闭后不会删除已有记录", windDownEnabled, onWindDownEnabledChange)
        if (baselineProfile.isComplete) {
            WindDownInsights.windDownMinute(baselineProfile)?.let { minute ->
                Text("减速 ${WindDownInsights.formatMinute(minute)} · 睡觉 ${formatMinute(baselineProfile.sleepMinute)}", style = MaterialTheme.typography.bodySmall)
            }
            Text("明早第 1–2 节有课时会在今日页提示注意休息；没有早课则可稍晚收尾。", style = MaterialTheme.typography.bodySmall)
        } else {
            Text("完成习惯基线引导后，按睡觉锚点提醒。", style = MaterialTheme.typography.bodySmall)
        }
        HorizontalDivider()
        SettingsSectionHeader("习惯基线", onHelp = { helpBlock = SettingsBlock.BASELINE })
        ElevatedCard {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (baselineProfile.isComplete) {
                    Text("当前生活阶段：${baselineProfile.lifeStage?.label}", fontWeight = FontWeight.SemiBold)
                    Text("起床 ${formatMinute(baselineProfile.wakeMinute)} · 睡觉 ${formatMinute(baselineProfile.sleepMinute)}")
                    baselineProfile.meals.forEach { meal -> Text("${meal.type.label} 约 ${formatMinute(meal.typicalStartMinute)} · 约 ${meal.typicalMinutes} 分钟", style = MaterialTheme.typography.bodySmall) }
                    if (baselineProfile.entertainmentWindow.isNotBlank()) Text("常见娱乐时段：${baselineProfile.entertainmentWindow}", style = MaterialTheme.typography.bodySmall)
                } else {
                    Text("尚未完成引导；可以现在补上。", fontWeight = FontWeight.SemiBold)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = onOpenBaselineEditor) { Text("编辑") }
                    TextButton(onClick = onResetBaseline) { Text("重建基线") }
                }
                if (baselineProfile.isComplete) {
                    TextButton(onClick = { onBaselineVariantNameOpenChange(true) }) { Text("另存当前方案") }
                } else {
                    Text("完成习惯基线引导（起床、睡觉、餐次）后，可在此把当前作息“另存为方案”，同一生活阶段可存多套并一键切换。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (baselineVariants.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth().clickable { baselineVariantsExpanded = !baselineVariantsExpanded }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("已存方案（${baselineVariants.size}）", fontWeight = FontWeight.SemiBold)
                        Text(if (baselineVariantsExpanded) "收起 ▴" else "展开 ▾", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                    if (baselineVariantsExpanded) {
                        baselineVariants.forEach { variant ->
                            val isCurrent = baselineProfile.variantName.isNotBlank() && baselineProfile.variantName == variant.variantName
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(variant.variantName, fontWeight = FontWeight.SemiBold)
                                        if (isCurrent) {
                                            Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.primary) {
                                                Text("当前", Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimary)
                                            }
                                        }
                                    }
                                    Text("${variant.lifeStage?.label ?: "未定"} · 起床 ${formatMinute(variant.wakeMinute)} · 睡觉 ${formatMinute(variant.sleepMinute)} · ${variant.dayGroups.size} 个作息分组", style = MaterialTheme.typography.bodySmall)
                                }
                                if (isCurrent) {
                                    Text("已切换", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                } else {
                                    TextButton(onClick = { onSwitchBaselineVariant(variant) }) { Text("切换") }
                                }
                                TextButton(onClick = { onDeleteBaselineVariant(variant) }) { Text("删除") }
                            }
                        }
                    }
                }
                HorizontalDivider()
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("作息分组（按星期）", fontWeight = FontWeight.SemiBold)
                    TextButton(onClick = { dayGroupWizardOpen = true }) { Text("＋ 添加/调整") }
                }
                if (baselineProfile.dayGroups.isEmpty()) {
                    Text("每天作息不同可用“添加/调整”按星期设置独立起床/睡觉/餐次（如周五课少≈周末、周末晚起）。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    baselineProfile.dayGroups.forEach { group ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(group.label, fontWeight = FontWeight.SemiBold)
                                Text("${group.days.sorted().joinToString("、") { weekdayName(it) }} · 起床 ${formatMinute(group.wakeMinute)} · 睡觉 ${formatMinute(group.sleepMinute)}", style = MaterialTheme.typography.bodySmall)
                            }
                            TextButton(onClick = { onDayGroupsChange(baselineProfile.dayGroups.filterNot { it.label == group.label }) }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            }
        }
        HorizontalDivider()
        SettingsSectionHeader("饭点学习", onHelp = { helpBlock = SettingsBlock.MEAL_LEARNING })
        SettingSwitch("饭点提醒", "接近预测饭点时询问是否开始吃饭；只有你确认的时间才会用于学习", mealReminderEnabled, onMealReminderEnabledChange)
        if (baselineProfile.lifeStage == null) {
            Text("完成习惯基线引导后，这里会按“生活阶段 × 星期 × 餐次”展示学到的饭点；数据不足时只用宽松提醒，不会假装精确预测。", style = MaterialTheme.typography.bodySmall)
        } else {
            val mealWeekday = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_WEEK)
            MealType.entries.forEach { type ->
                val plan = MealLearning.todayPlan(mealRecords, baselineProfile, mealWeekday, type)
                val stageLabel = baselineProfile.lifeStage?.label.orEmpty()
                Text(
                    if (plan.learned) "${type.label} · $stageLabel 最近 ${plan.sampleCount} 次：${formatMinute(plan.startMinute)} 开始 · 约 ${plan.minutes} 分钟"
                    else "${type.label} · $stageLabel 数据不足（${plan.sampleCount} 次），暂用你填写的 ${formatMinute(plan.startMinute)} · 约 ${plan.minutes} 分钟",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            TextButton(onClick = onOpenMealRecords) { Text("就餐记录") }
        }
        HorizontalDivider()
        PlanHubItem("高级工具", "习惯数据、地点、AI、识别、应用检测与稳定性") { onSubPageChange(SettingsSubPage.ADVANCED) }
        HorizontalDivider()
        SettingsSectionHeader("改进清单", onHelp = { helpBlock = SettingsBlock.IMPROVEMENTS })
        TextButton(onClick = onAddImprovement) { Text("＋ 记录改进想法") }
        improvementNotes.takeLast(3).reversed().forEach { note -> ElevatedCard { Text(note.text, Modifier.padding(10.dp)) } }
        HorizontalDivider()
        PlanHubItem("快速入门", "几步上手的核心流程介绍") { onOpenFeatureIntro() }
        HorizontalDivider()
        PlanHubItem("版本路线图", "当前 ${BuildConfig.VERSION_NAME} · 构建 #${BuildConfig.CI_RUN_NUMBER} · 版本演进") { onSubPageChange(SettingsSubPage.ROADMAP) }
        Text("通知异常时请到“日程与活动提醒”查看检测结果和当前设备的手动路径；精确闹钟按设备支持情况自动处理。")
    }
    }
    SubpageMotion(subPage, depth = { destination ->
        when (destination) {
            SettingsSubPage.ADVANCED, SettingsSubPage.ROADMAP, SettingsSubPage.APPEARANCE,
            SettingsSubPage.ACTIVITY_REMINDERS, SettingsSubPage.QUIET_HOURS -> 1
            SettingsSubPage.CAMPUS_PLACES -> 3
            else -> 2
        }
    }) { current ->
        if (current != null) {
            PlanSubpageFrame(
                Modifier.fillMaxSize(), current.title,
                // 外观与自定义主题页的问号放在标题行右侧，避免单独一行悬在内容上方。
                titleAction = when (current) {
                    SettingsSubPage.APPEARANCE -> { { HelpToggleButton(onClick = { helpBlock = SettingsBlock.APPEARANCE }) } }
                    SettingsSubPage.CUSTOM_THEME -> { { HelpToggleButton(onClick = { helpBlock = SettingsBlock.CUSTOM_THEME }) } }
                    else -> null
                }
            ) {
                when (current) {
                    SettingsSubPage.ADVANCED -> {
                        Text("这些能力只在配置后参与日常流程；关闭或不配置时不会占用今日页空间。", style = MaterialTheme.typography.bodySmall)
                        PlanHubItem("习惯原始事件", "查看用于形成作息建议的本地记录") { onOpenBaselineEvents() }
                        if (!EXPENSE_HIDDEN) {
                            val expense = ExpenseInsights.summarize(mealRecords)
                            ElevatedCard {
                                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("可选消费记录", fontWeight = FontWeight.Bold)
                                    if (expense.withAmountCount == 0) {
                                        Text("就餐结束时可选填金额；没有记录时不会出现在日常流程。", style = MaterialTheme.typography.bodySmall)
                                    } else {
                                        Text("${expense.withAmountCount} 笔草稿 · 合计 ¥${expense.totalAmount}；本月 ¥${expense.monthAmount}", style = MaterialTheme.typography.bodySmall)
                                        if (expense.topCategories.isNotEmpty()) Text("分类：${expense.topCategories.joinToString(" · ") { "${it.first} ¥${it.second}" }}", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                        PlanHubItem("通勤与地点", if (campusLifeEnabled) "校园生活 开" else "校园生活 关") { onSubPageChange(SettingsSubPage.COMMUTE_PLACES) }
                        PlanHubItem("学习路径建议", if (tutorialSearch.enabled) "已开启${if (tutorialSearch.apiKey.isNotBlank()) " · 已填 key" else ""}" else "未开启") { onSubPageChange(SettingsSubPage.TUTORIAL_SEARCH) }
                        PlanHubItem("AI 周总结", if (aiWeeklySummary.enabled) "已开启${if (aiWeeklySummary.apiKey.isNotBlank()) " · 独立 key" else " · 复用学习路径 key"}" else "未开启") { onSubPageChange(SettingsSubPage.AI_WEEKLY_SUMMARY) }
                        PlanHubItem("课表识别（视觉模型）", if (courseVision.enabled) "已开启${if (tutorialSearch.apiKey.isNotBlank()) " · 已填 key" else " · 未填 key"}" else "未开启") { onSubPageChange(SettingsSubPage.COURSE_VISION) }
                        PlanHubItem("前台应用检测", if (gameDetectionEnabled) "已开启 · 应用分类" else "未开启") { onSubPageChange(SettingsSubPage.APP_DETECTION) }
                        PlanHubItem("稳定性与崩溃", "本地记录崩溃栈 · 可复制反馈") { onSubPageChange(SettingsSubPage.STABILITY) }
                    }
                    SettingsSubPage.ROADMAP -> RoadmapSubpageContent()
                    SettingsSubPage.CAMPUS_PLACES -> CampusPlacesEditorContent(
                        allPlaces = campusPlaces,
                        customPlaces = customPlaces,
                        hasPackage = campusMapPackage != null,
                        amapKey = amapKey,
                        campusCenter = campusCenter,
                        currentCampusPlace = currentCampusPlace,
                        hiddenPlaces = hiddenPlaces,
                        onToggleHiddenPlace = onToggleHiddenPlace,
                        onAmapKeyChange = { onAmapKeyChange(it) },
                        onSavePlace = { originalName, place ->
                            val updated = if (originalName == null) {
                                customPlaces.filterNot { it.name.lowercase() == place.name.lowercase() } + place
                            } else {
                                customPlaces.map { if (it.name == originalName) place else it }
                            }
                            onCustomPlacesChange(updated)
                        },
                        onDeletePlace = { name ->
                            onCustomPlacesChange(customPlaces.filterNot { it.name == name })
                        }
                    )
                    SettingsSubPage.APPEARANCE -> {
                        SettingSwitch(
                            "深色模式",
                            "在当前主题基础上调暗背景与文字，保留主/副/强调色",
                            darkMode,
                            onDarkModeChange
                        )
                        HorizontalDivider()
                        FocusFlowThemeOption.builtInEntries().forEach { option ->
                            val preview = focusFlowThemeSpec(option)
                            Card(
                                modifier = Modifier.fillMaxWidth().clickable { onThemeChange(option) },
                                colors = CardDefaults.cardColors(
                                    containerColor = if (themeOption == option) preview.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f)
                                )
                            ) {
                                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    ThemeSwatchPreview(previewColors(preview))
                                    Column(Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Text(option.label, fontWeight = FontWeight.SemiBold)
                                            if (themeOption == option) {
                                                Text("已选择", color = preview.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                                            }
                                        }
                                        Text(option.description, style = MaterialTheme.typography.bodySmall)
                                    }
                                    // 以此改色：以内置主题的全局色为基底进入自定义编辑器（暂不切换主题，
                                    // 确认由编辑器内"应用此配色"完成，避免界面提前变色造成违和）。
                                    // 轻量文字按钮与预设卡"应用"一致，避免喧宾夺主。
                                    TextButton(
                                        onClick = {
                                            onCustomThemeColorsChange(option.colors)
                                            onSubPageChange(SettingsSubPage.CUSTOM_THEME)
                                        },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                                    ) { Text("以此改色", style = MaterialTheme.typography.bodySmall) }
                                }
                            }
                        }
                        // 自定义主题：点卡只进入编辑器，不切主题；确认由编辑器内"应用此配色"完成，
                        // 与内置主题"以此改色"一致，避免点卡即应用造成违和。
                        val customPreview = focusFlowThemeSpec(FocusFlowThemeOption.CUSTOM, customThemeColors)
                        Card(
                            modifier = Modifier.fillMaxWidth().clickable {
                                onSubPageChange(SettingsSubPage.CUSTOM_THEME)
                            },
                            colors = CardDefaults.cardColors(
                                containerColor = if (themeOption == FocusFlowThemeOption.CUSTOM) customPreview.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f)
                            )
                        ) {
                            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                ThemeSwatchPreview(previewColors(customPreview))
                                Column(Modifier.weight(1f)) {
                                    Text(FocusFlowThemeOption.CUSTOM.label, fontWeight = FontWeight.SemiBold)
                                    Text("从预设色板自由搭配 · 点此编辑", style = MaterialTheme.typography.bodySmall)
                                }
                                Text("编辑", color = if (themeOption == FocusFlowThemeOption.CUSTOM) customPreview.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                    SettingsSubPage.CUSTOM_THEME -> {
                        CustomThemeEditorContent(
                            colors = customThemeColors,
                            onColorsChange = onCustomThemeColorsChange,
                            presets = themePresets,
                            onPresetsChange = onThemePresetsChange,
                            onRestoreDefault = onRestoreDefaultTheme,
                            customActive = themeOption == FocusFlowThemeOption.CUSTOM,
                            onApplyCustom = { onThemeChange(FocusFlowThemeOption.CUSTOM) }
                        )
                    }
                    SettingsSubPage.ACTIVITY_REMINDERS -> {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            HelpToggleButton(onClick = { helpBlock = SettingsBlock.ACTIVITY_REMINDERS })
                        }
                        // 权限状态随前台恢复刷新：从系统设置页返回后立即更新文案。
                        val lifecycleOwner = LocalLifecycleOwner.current
                        var notificationHealth by remember { mutableStateOf(NotificationChannelSettings.health(context)) }
                        val notificationGuidance = remember { NotificationGuidancePolicy.forDevice(Build.MANUFACTURER, Build.BRAND) }
                        var notifGranted by remember { mutableStateOf(context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) }
                        var exactAllowed by remember { mutableStateOf(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()) }
                        var batteryUnrestricted by remember { mutableStateOf(context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)) }
                        var reminderTestProbe by remember { mutableStateOf(settingsStore.loadReminderTestProbe()) }
                        var reminderDiagnosticsRevision by remember { mutableIntStateOf(0) }
                        var taskTestMessage by remember { mutableStateOf<String?>(null) }
                        val pendingTaskReminders = remember(activitySettings, reminderDiagnosticsRevision) {
                            TaskReminderPolicy.pendingReminders(settingsStore.loadItems(), activitySettings)
                        }
                        val notificationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
                            notifGranted = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                            notificationHealth = NotificationChannelSettings.health(context)
                            ReminderScheduler.restoreTaskReminders(context)
                            reminderDiagnosticsRevision += 1
                        }
                        LaunchedEffect(reminderTestProbe?.expectedAt) {
                            repeat(120) {
                                delay(1_000L)
                                val latest = settingsStore.loadReminderTestProbe()
                                if (latest != reminderTestProbe) reminderTestProbe = latest
                                if (latest?.deliveredAt != null) return@LaunchedEffect
                            }
                        }
                        DisposableEffect(lifecycleOwner) {
                            val observer = LifecycleEventObserver { _, event ->
                                if (event == Lifecycle.Event.ON_RESUME) {
                                    notifGranted = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                                    notificationHealth = NotificationChannelSettings.health(context)
                                    exactAllowed = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
                                    batteryUnrestricted = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
                                    reminderTestProbe = settingsStore.loadReminderTestProbe()
                                    ReminderScheduler.restoreTaskReminders(context)
                                    reminderDiagnosticsRevision += 1
                                }
                            }
                            lifecycleOwner.lifecycle.addObserver(observer)
                            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                        }
                        if (!notifGranted) {
                            Text("通知权限未开启，提醒无法弹出。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            OutlinedButton(onClick = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                else context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS, Uri.parse("package:${context.packageName}")))
                            }) { Text("申请通知权限") }
                            TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS, Uri.parse("package:${context.packageName}"))) }) { Text("已拒绝？去系统设置开启") }
                        } else {
                            Text(
                                if (notificationHealth.allReadableSettingsReady) "Android 可读取的通知与两个渠道均已开启。"
                                else NotificationHealthPolicy.startupMessage(notificationHealth) ?: "通知设置需要检查。",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (notificationHealth.allReadableSettingsReady) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                            )
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(notificationGuidance.title, fontWeight = FontWeight.SemiBold)
                                    notificationGuidance.steps.forEachIndexed { index, step ->
                                        Text("${index + 1}. $step", style = MaterialTheme.typography.bodySmall)
                                    }
                                    Text(notificationGuidance.limitation, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        Text("活动结束提醒", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        SettingSwitch("活动结束提醒", "关闭后仍会保留活动记录和手动转场", activitySettings.notificationsEnabled) { onActivitySettingsChange(activitySettings.copy(notificationsEnabled = it)) }
                        SettingSwitch("明确的到点提醒", "到达约定时间时使用更醒目的提醒", activitySettings.strongerEndReminder) { onActivitySettingsChange(activitySettings.copy(strongerEndReminder = it)) }
                        Text("活动结束前预告：${activitySettings.previewMinutes} 分钟")
                        Slider(
                            value = activitySettings.previewMinutes.toFloat(),
                            onValueChange = { onActivitySettingsChange(activitySettings.copy(previewMinutes = (it / 5).toInt() * 5)) },
                            valueRange = 0f..30f,
                            steps = 5
                        )
                        HorizontalDivider()
                        Text("日程开始提醒", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        SettingSwitch("日程开始提醒", "课程以外的定时任务、目标安排会在开始前预告并在到点时再次提醒；重启后自动恢复", activitySettings.scheduleRemindersEnabled) {
                            onActivitySettingsChange(activitySettings.copy(scheduleRemindersEnabled = it))
                        }
                        Text("日程开始前预告：${activitySettings.scheduleAdvanceMinutes} 分钟")
                        Slider(
                            value = activitySettings.scheduleAdvanceMinutes.toFloat(),
                            onValueChange = { onActivitySettingsChange(activitySettings.copy(scheduleAdvanceMinutes = (it / 5).toInt() * 5)) },
                            valueRange = 0f..30f,
                            steps = 5
                        )
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("日程提醒诊断", fontWeight = FontWeight.SemiBold)
                                Text(
                                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || exactAllowed) "当前使用精确提醒。"
                                    else "当前使用普通后台提醒，系统省电策略可能造成延迟。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || exactAllowed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                                )
                                Text(
                                    if (batteryUnrestricted) "电池后台：Android 检测为不受电池优化限制。" else "电池后台：仍受系统电池优化，后台提醒可能延迟。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (batteryUnrestricted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                                )
                                Text(
                                    "厂商后台／自启动：Android 没有统一的可读取接口，不能直接判断开关；FocusFlow 以下方实测结果判断是否能准时唤醒。",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (!activitySettings.scheduleRemindersEnabled) {
                                    Text("日程提醒已关闭。", style = MaterialTheme.typography.bodySmall)
                                } else if (pendingTaskReminders.isEmpty()) {
                                    Text("目前没有未来的定时任务提醒。", style = MaterialTheme.typography.bodySmall)
                                } else {
                                    pendingTaskReminders.firstOrNull { it.stage == TaskReminderStage.ADVANCE }?.let {
                                        Text("下一次提前提醒：${it.title} · ${formatDateTime(it.triggerAt)}", style = MaterialTheme.typography.bodySmall)
                                    }
                                    pendingTaskReminders.firstOrNull { it.stage == TaskReminderStage.DUE }?.let {
                                        Text("下一次到点提醒：${it.title} · ${formatDateTime(it.triggerAt)}", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                                OutlinedButton(
                                    enabled = notifGranted,
                                    onClick = {
                                        val mode = ReminderScheduler.scheduleTaskReminderTest(context)
                                        reminderTestProbe = settingsStore.loadReminderTestProbe()
                                        taskTestMessage = when (mode) {
                                            AlarmDeliveryMode.ALARM_CLOCK -> "已安排强唤醒测试，1 分钟后应出现；系统可能显示闹钟标识。"
                                            AlarmDeliveryMode.EXACT -> "强唤醒被系统拒绝，已回退到精确测试提醒。"
                                            AlarmDeliveryMode.INEXACT -> "强唤醒与精确提醒均不可用，已使用普通后台测试，可能延迟。"
                                        }
                                    }
                                ) { Text("1 分钟后测试通知") }
                                taskTestMessage?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }
                                val testResult = TaskReminderPolicy.testResult(reminderTestProbe)
                                val testResultText = when (testResult) {
                                    ReminderTestResult.NONE -> "后台实测：尚未测试。"
                                    ReminderTestResult.PENDING -> "后台实测：等待测试提醒送达，请退回桌面。"
                                    ReminderTestResult.ON_TIME -> "后台实测：最近一次按时送达。"
                                    ReminderTestResult.DELAYED -> {
                                        val probe = reminderTestProbe
                                        val delaySeconds = if (probe?.deliveredAt != null) ((probe.deliveredAt - probe.expectedAt) / 1_000L).coerceAtLeast(1L) else 0L
                                        "后台实测：最近一次延迟约 $delaySeconds 秒；不能视为后台正常。"
                                    }
                                    ReminderTestResult.OVERDUE -> "后台实测：已超过预期 30 秒仍未送达；后台唤醒可能受限。"
                                }
                                Text(
                                    testResultText,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (testResult == ReminderTestResult.ON_TIME) MaterialTheme.colorScheme.primary else if (testResult in setOf(ReminderTestResult.DELAYED, ReminderTestResult.OVERDUE)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Text("连续延长提示上限：${activitySettings.maxExtensions} 次")
                        Slider(
                            value = activitySettings.maxExtensions.toFloat(),
                            onValueChange = { onActivitySettingsChange(activitySettings.copy(maxExtensions = it.toInt())) },
                            valueRange = 0f..6f,
                            steps = 5
                        )
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            when {
                                exactAllowed -> Text("精确提醒已由系统启用，无需额外设置。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                                Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2 -> OutlinedButton(onClick = {
                                    runCatching { context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))) }
                                }) { Text("允许精确提醒") }
                                else -> Text("系统未授予精确提醒，已使用普通后台提醒。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                    SettingsSubPage.QUIET_HOURS -> {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            HelpToggleButton(onClick = { helpBlock = SettingsBlock.QUIET_HOURS })
                        }
                        SettingSwitch(
                            "免打扰时段",
                            "时段内静音状态询问、饭点提醒与睡前减速；活动到点和任务提醒保持时间敏感，不静音",
                            quietHours.enabled,
                            { enabled -> onQuietHoursChange(quietHours.copy(enabled = enabled)) }
                        )
                        if (quietHours.enabled) {
                            Text("免打扰 ${formatMinute(quietHours.startMinute)} – ${formatMinute(quietHours.endMinute)}（跨天按到次日处理）")
                            Text("开始时间", style = MaterialTheme.typography.labelSmall)
                            Slider(value = quietHours.startMinute.toFloat(), onValueChange = { onQuietHoursChange(quietHours.copy(startMinute = ((it / 30).toInt() * 30).coerceIn(0, 1439))) }, valueRange = 0f..1439f, steps = 47)
                            Text("结束时间", style = MaterialTheme.typography.labelSmall)
                            Slider(value = quietHours.endMinute.toFloat(), onValueChange = { onQuietHoursChange(quietHours.copy(endMinute = ((it / 30).toInt() * 30).coerceIn(0, 1439))) }, valueRange = 0f..1439f, steps = 47)
                            SettingSwitch("静音状态询问", "免打扰时段内不弹低打扰状态询问", quietHours.suppressStatusCheckIn) { v -> onQuietHoursChange(quietHours.copy(suppressStatusCheckIn = v)) }
                            SettingSwitch("静音饭点提醒", "“准备吃饭／吃完了吗”提醒不弹出", quietHours.suppressMeal) { v -> onQuietHoursChange(quietHours.copy(suppressMeal = v)) }
                            SettingSwitch("静音睡前减速", "睡前减速提醒不弹出", quietHours.suppressWindDown) { v -> onQuietHoursChange(quietHours.copy(suppressWindDown = v)) }
                        }
                        Text("一次性静音", fontWeight = FontWeight.SemiBold)
                        Text("立即静音低打扰提醒（状态询问、饭点提醒、睡前减速）一段时间；任务提醒与活动到点提醒不会被静音。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilledTonalButton(onClick = { onQuietHoursChange(quietHours.copy(muteUntil = System.currentTimeMillis() + 3_600_000L)) }) { Text("1 小时") }
                            FilledTonalButton(onClick = { onQuietHoursChange(quietHours.copy(muteUntil = System.currentTimeMillis() + 3 * 3_600_000L)) }) { Text("3 小时") }
                            FilledTonalButton(onClick = { onQuietHoursChange(quietHours.copy(muteUntil = nextMorning())) }) { Text("到明早 7 点") }
                        }
                        if (quietHours.isMuted()) {
                            Text("已静音至 ${formatDateTime(quietHours.muteUntil)}（低打扰提醒静音）", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            TextButton(onClick = { onQuietHoursChange(quietHours.copy(muteUntil = 0L)) }) { Text("取消静音") }
                        }
                    }
                    SettingsSubPage.COMMUTE_PLACES -> {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            HelpToggleButton(onClick = { helpBlock = SettingsBlock.COMMUTE_PLACES })
                        }
                        SettingSwitch("校园生活", "控制校内出行、地点包和手动位置工具；关闭不会删除已有数据", campusLifeEnabled, onCampusLifeEnabledChange)
                        if (campusLifeEnabled) {
                            ElevatedCard(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("出行参数", fontWeight = FontWeight.SemiBold)
                                    SettingSwitch("为通勤预留时间", "只保存大致时长，不读取定位", commuteProfile.enabled) { onCommuteChange(commuteProfile.copy(enabled = it)) }
                                    if (commuteProfile.enabled) {
                                        Text("单程约 ${commuteProfile.oneWayMinutes} 分钟")
                                        Slider(value = commuteProfile.oneWayMinutes.toFloat(), onValueChange = { onCommuteChange(commuteProfile.copy(oneWayMinutes = (it / 5).toInt() * 5)) }, valueRange = 5f..120f, steps = 22)
                                        Text("校内主要方式", fontWeight = FontWeight.SemiBold)
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            listOf("步行", "自行车", "电动车").forEach { mode -> FilterChip(selected = commuteProfile.campusMode == mode, onClick = { onCommuteChange(commuteProfile.copy(campusMode = mode)) }, label = { Text(mode) }) }
                                        }
                                        Text("教学楼进出与找教室缓冲：${commuteProfile.buildingBufferMinutes} 分钟", fontWeight = FontWeight.SemiBold)
                                        Slider(value = commuteProfile.buildingBufferMinutes.toFloat(), onValueChange = { onCommuteChange(commuteProfile.copy(buildingBufferMinutes = it.toInt())) }, valueRange = 1f..10f, steps = 8)
                                        if (commuteProfile.campusMode == "电动车") {
                                            Text("电动车电量", fontWeight = FontWeight.SemiBold)
                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                listOf("充足", "一般", "偏低", "未知").forEach { level -> FilterChip(selected = commuteProfile.eBikeBattery == level, onClick = { onCommuteChange(commuteProfile.copy(eBikeBattery = level)) }, label = { Text(level) }) }
                                            }
                                            if (commuteProfile.eBikeBattery == "偏低") {
                                                Text("电量偏低：排程会避免安排需要骑车的远距离连续行程；建议在长空档充电（见 计划→空挡建议）。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                        }
                                    }
                                }
                            }
                            ElevatedCard(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                        Text("校园地点来源", fontWeight = FontWeight.SemiBold)
                                        OutlinedButton(
                                            onClick = { campusMapHelpOpen = true },
                                            modifier = Modifier.size(30.dp),
                                            shape = CircleShape,
                                            contentPadding = PaddingValues(0.dp)
                                        ) { Text("?", fontWeight = FontWeight.Bold) }
                                    }
                                    Text(
                                        campusMapPackage?.let { "高级地点包：${it.name} · ${it.places.size} 个地点" } ?: "已自动使用内置紫金港目录 · ${ZijingangTravel.places.size} 个地点",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    TextButton(onClick = { onSubPageChange(SettingsSubPage.CAMPUS_PLACES) }) { Text("管理校园地点（增删改 / 搜索）") }
                                    TextButton(onClick = { advancedCampusImportOpen = !advancedCampusImportOpen }) { Text(if (advancedCampusImportOpen) "收起高级导入" else "高级：导入已有地点数据") }
                                    if (advancedCampusImportOpen) {
                                        OutlinedButton(onClick = { importCampusMap.launch(arrayOf("application/json", "text/plain")) }) { Text("导入地点包（JSON）") }
                                        if (campusMapPackage != null) TextButton(onClick = {
                                            onCampusMapPackageChange(null)
                                            importStatus = "已恢复内置紫金港地点"
                                        }) { Text("恢复内置地点") }
                                        importStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = if (it.startsWith("导入失败")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) }
                                        Text("圆形问号中保留完整格式示例，供迁移或批量维护时使用。", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                            if (pendingPlaces.isNotEmpty()) {
                                ElevatedCard(Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text("课表识别发现的新地点", fontWeight = FontWeight.SemiBold)
                                        Text("识别结果中出现、还没加入地点目录的教室/楼名；加入后可用于课程空档与路程估算，也可在“管理校园地点”里改分区。", style = MaterialTheme.typography.bodySmall)
                                        pendingPlaces.forEach { place ->
                                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                                Text(place, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                                TextButton(onClick = { onAddPendingPlace(place) }) { Text("加入") }
                                                TextButton(onClick = { onRemovePendingPlace(place) }) { Text("忽略") }
                                            }
                                        }
                                    }
                                }
                            }
                            ElevatedCard(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("校区中心与手动位置", fontWeight = FontWeight.SemiBold)
                                    Text("校区中心（POI 搜索范围）", fontWeight = FontWeight.SemiBold)
                                    var centerLatText by remember(campusCenter) { mutableStateOf(campusCenter.lat.toString()) }
                                    var centerLngText by remember(campusCenter) { mutableStateOf(campusCenter.lng.toString()) }
                                    var centerCityText by remember(campusCenter) { mutableStateOf(campusCenter.city) }
                                    fun commitCenter() {
                                        val lat = centerLatText.trim().toDoubleOrNull()
                                        val lng = centerLngText.trim().toDoubleOrNull()
                                        if (lat != null && lng != null && lat in -90.0..90.0 && lng in -180.0..180.0 && centerCityText.isNotBlank()) {
                                            onCampusCenterChange(CampusCenter(lat, lng, centerCityText.trim()))
                                        }
                                    }
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedTextField(value = centerLatText, onValueChange = { centerLatText = it; commitCenter() }, label = { Text("纬度") }, singleLine = true, modifier = Modifier.weight(1f))
                                        OutlinedTextField(value = centerLngText, onValueChange = { centerLngText = it; commitCenter() }, label = { Text("经度") }, singleLine = true, modifier = Modifier.weight(1f))
                                    }
                                    OutlinedTextField(value = centerCityText, onValueChange = { centerCityText = it; commitCenter() }, label = { Text("校区所在城市") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                                    Text("POI 搜索以这里为中心（3 公里内），空结果再按城市全城搜索；默认浙大紫金港 · 杭州，其他学校请改成自己学校。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    HorizontalDivider()
                                    Text("手动当前位置", fontWeight = FontWeight.SemiBold)
                                    OutlinedButton(onClick = { choosingCurrentPlace = true }, modifier = Modifier.fillMaxWidth()) {
                                        Text(currentCampusPlace?.let { "当前位置：$it" } ?: "选择当前位置")
                                    }
                                    if (currentCampusPlace != null) {
                                        OutlinedButton(onClick = { choosingDestination = true }, modifier = Modifier.fillMaxWidth()) {
                                            Text(previewDestination?.let { "预览目的地：$it" } ?: "选择预览目的地")
                                        }
                                        val from = campusPlaces.firstOrNull { it.name == currentCampusPlace }
                                        val to = campusPlaces.firstOrNull { it.name == previewDestination }
                                        if (from != null && to != null) {
                                            val key = ZijingangTravel.routeKey(from.zone, to.zone, commuteProfile.campusMode)
                                            val minutes = ZijingangTravel.estimateMinutes(from.zone, to.zone, commuteProfile)
                                            val calibrated = ZijingangTravel.calibratedMinutes(from.zone, to.zone, commuteProfile)
                                            val observations = commuteProfile.routeObservations[key].orEmpty()
                                            Text("${from.name} → ${to.name}：按${commuteProfile.campusMode}${if (calibrated == null) "估计" else "学习后"}约 $minutes 分钟（含楼内缓冲）。", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                                            if (observations.isNotEmpty()) Text("基于 ${observations.size} 次确认记录：${observations.takeLast(5).joinToString("、")} 分钟${if (observations.size > 5) "（显示最近 5 次）" else ""}。", style = MaterialTheme.typography.labelSmall)
                                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                TextButton(onClick = { routeCalibrationTarget = Pair(from, to) }) { Text("记录本次耗时") }
                                                if (observations.isNotEmpty()) TextButton(onClick = { onCommuteChange(CommuteLearning.undoLatest(commuteProfile, key)) }) { Text("撤销最近记录") }
                                            }
                                            if (calibrated != null) TextButton(onClick = { onCommuteChange(CommuteLearning.clear(commuteProfile, key)) }) { Text("清除该路线学习") }
                                        }
                                        TextButton(onClick = { onCurrentCampusPlaceChange(null) }) { Text("清除当前位置") }
                                    }
                                }
                            }
                        }
                    }
                    SettingsSubPage.TUTORIAL_SEARCH -> {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            HelpToggleButton(onClick = { helpBlock = SettingsBlock.TUTORIAL_SEARCH })
                        }
                        SettingSwitch(
                            "教程联网搜索",
                            "为学习目标从网上搜集候选教程并比较来源；使用你填写的硅基流动 key，仅发往 api.siliconflow.cn",
                            tutorialSearch.enabled,
                            { enabled -> onTutorialSearchSettingsChange(tutorialSearch.copy(enabled = enabled)) }
                        )
                        if (tutorialSearch.enabled) {
                            OutlinedTextField(
                                value = tutorialSearch.apiKey,
                                onValueChange = { onTutorialSearchSettingsChange(tutorialSearch.copy(apiKey = it)) },
                                label = { Text("硅基流动 API key") },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text("常用模型（点选即切换，也可手填）", style = MaterialTheme.typography.labelMedium)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                                TUTORIAL_MODEL_PRESETS.forEach { (id, label) ->
                                    FilterChip(
                                        selected = tutorialSearch.model == id,
                                        onClick = { onTutorialSearchSettingsChange(tutorialSearch.copy(model = id)) },
                                        label = { Text(label) }
                                    )
                                }
                            }
                            OutlinedTextField(
                                value = tutorialSearch.model,
                                onValueChange = { onTutorialSearchSettingsChange(tutorialSearch.copy(model = it)) },
                                label = { Text("模型名（硅基流动可用模型 ID）") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(if (tutorialSearch.apiKey.isBlank()) "填写 key 后，在“目标与执行”里可为学习目标生成学习路径建议。" else "key 已保存本机；在“目标与执行”里可生成学习路径建议。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            HorizontalDivider()
                            Text("视频分析模型（一站式整理视频教程）", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text("在“计划 → 资料工具箱 → 视频分析”里确认视频链接或材料后生成候选要点并保存；模型模式同前（免费/推荐/自定义）。", style = MaterialTheme.typography.bodySmall)
                            Text("常用模型（点选即切换，也可手填）", style = MaterialTheme.typography.labelMedium)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                                VIDEO_ANALYSIS_MODEL_PRESETS.forEach { (id, label) ->
                                    FilterChip(
                                        selected = videoAnalysisModel == id,
                                        onClick = { onVideoAnalysisModelChange(id) },
                                        label = { Text(label) }
                                    )
                                }
                            }
                            OutlinedTextField(
                                value = videoAnalysisModel,
                                onValueChange = onVideoAnalysisModelChange,
                                label = { Text("视频分析模型名（硅基流动可用模型 ID）") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                    SettingsSubPage.AI_WEEKLY_SUMMARY -> {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            HelpToggleButton(onClick = { helpBlock = SettingsBlock.AI_WEEKLY_SUMMARY })
                        }
                        SettingSwitch(
                            "AI 周总结",
                            "“计划 → 本周回顾”里按本周真实记录生成本周 AI 复盘；使用硅基流动 key，仅发往 api.siliconflow.cn",
                            aiWeeklySummary.enabled,
                            { enabled -> onAiWeeklySummarySettingsChange(aiWeeklySummary.copy(enabled = enabled)) }
                        )
                        if (aiWeeklySummary.enabled) {
                            OutlinedTextField(
                                value = aiWeeklySummary.apiKey,
                                onValueChange = { onAiWeeklySummarySettingsChange(aiWeeklySummary.copy(apiKey = it)) },
                                label = { Text("硅基流动 API key（留空则共用学习路径建议的 key）") },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text("留空时自动使用“学习路径建议”里的 key；想与教程搜索分开管理时可填独立的 key。key 仅保存在本机。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    SettingsSubPage.COURSE_VISION -> {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            HelpToggleButton(onClick = { helpBlock = SettingsBlock.COURSE_VISION })
                        }
                        SettingSwitch(
                            "课表识别用硅基流动视觉模型",
                            "开启后，导入课表截图时改用视觉模型识别（不再内置本地识别）；识别失败会提示原因：检查 key、模型名或网络后重试。图片只发往 api.siliconflow.cn",
                            courseVision.enabled,
                            { enabled -> onCourseVisionSettingsChange(courseVision.copy(enabled = enabled)) }
                        )
                        if (courseVision.enabled) {
                            OutlinedTextField(
                                value = tutorialSearch.apiKey,
                                onValueChange = { onTutorialSearchSettingsChange(tutorialSearch.copy(apiKey = it)) },
                                label = { Text("硅基流动 API key（与教程搜索共用）") },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                modifier = Modifier.fillMaxWidth()
                            )
                            TextButton(onClick = { onCourseVisionGuideOpenChange(true) }) { Text("如何获取 key（新用户 2 分钟）") }
                            Text("常用模型（点选即切换，也可在下面手填）", style = MaterialTheme.typography.labelMedium)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                                VISION_MODEL_PRESETS.forEach { (id, label) ->
                                    FilterChip(
                                        selected = courseVision.model == id,
                                        onClick = { onCourseVisionSettingsChange(courseVision.copy(model = id)) },
                                        label = { Text(label) }
                                    )
                                }
                            }
                            OutlinedTextField(
                                value = courseVision.model,
                                onValueChange = { onCourseVisionSettingsChange(courseVision.copy(model = it)) },
                                label = { Text("视觉模型名（硅基流动模型 ID）") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text("默认 Qwen/Qwen3-VL-8B-Instruct（在线免费，识别课表足够）；识别率不满意可换 Qwen/Qwen3-VL-32B-Instruct（是否计费以硅基流动为准）。旧版 Qwen2.5-VL 系列已下线，保存过旧模型名会自动迁移。key 仅存本机，只发往 api.siliconflow.cn，关闭开关后导入课表不再联网。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    SettingsSubPage.APP_DETECTION -> {
                        val context = LocalContext.current
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            HelpToggleButton(onClick = { helpBlock = SettingsBlock.APP_DETECTION })
                        }
                        // 从系统“使用情况访问”设置页返回后刷新状态。
                        val lifecycleOwner = LocalLifecycleOwner.current
                        var usageGranted by remember { mutableStateOf(AppLibrary.hasUsageAccess(context)) }
                        DisposableEffect(lifecycleOwner) {
                            val observer = LifecycleEventObserver { _, event ->
                                if (event == Lifecycle.Event.ON_RESUME) usageGranted = AppLibrary.hasUsageAccess(context)
                            }
                            lifecycleOwner.lifecycle.addObserver(observer)
                            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                        }
                        SettingSwitch(
                            "前台应用检测",
                            "开启后，游戏安排到点时识别前台应用：还在玩游戏类应用就提醒收尾；未授权时只提醒不检测",
                            gameDetectionEnabled,
                            onGameDetectionEnabledChange
                        )
                        if (gameDetectionEnabled) {
                            if (!usageGranted) {
                                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f))) {
                                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text("未授予“使用情况访问”", fontWeight = FontWeight.SemiBold)
                                        Text("到系统设置开启后，才能识别当前前台应用（判断只在本机完成，不上传）。", style = MaterialTheme.typography.bodySmall)
                                        Button(onClick = { AppLibrary.openUsageAccessSettings(context) }) { Text("去系统开启") }
                                    }
                                }
                            } else {
                                Text("已授予使用情况访问；到点会识别前台应用是否属于游戏类。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                        HorizontalDivider()
                        Text("应用分类", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("按本机已安装应用生成清单：内置常见应用归类 + 应用名自动识别；识别不对的可手动归类。分类只用于收尾提醒判断。", style = MaterialTheme.typography.bodySmall)
                        var addAppOpen by remember { mutableStateOf(false) }
                        TextButton(onClick = { addAppOpen = true }) { Text("＋ 添加本机应用") }
                        if (addAppOpen) AddInstalledAppDialog(
                            onDismiss = { addAppOpen = false },
                            onSave = { pkg, category -> onAppCategoriesChange(appCategories + (pkg to category)) }
                        )
                        var installedApps by remember { mutableStateOf(emptyList<Triple<String, String, AppCategory>>()) }
                        LaunchedEffect(context, appCategories, hiddenApps) {
                            onGlobalLoadingChange(true)
                            installedApps = withContext(Dispatchers.IO) { categorizedInstalledApps(context, appCategories).filterNot { it.first in hiddenApps } }
                            onGlobalLoadingChange(false)
                        }
                        Text("本机共扫描到 ${installedApps.size} 个应用；内置常见应用归类 + 应用名自动识别，识别不对或未识别的可手动归类。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        var categorizedExpanded by remember { mutableStateOf(true) }
                        var uncategorizedExpanded by remember { mutableStateOf(false) }
                        var hiddenExpanded by remember { mutableStateOf(false) }
                        val categorizedApps = installedApps.filter { it.third != AppCategory.UNKNOWN }
                        Row(Modifier.fillMaxWidth().clickable { categorizedExpanded = !categorizedExpanded }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("已分类应用（${categorizedApps.size}）", fontWeight = FontWeight.SemiBold)
                            Text(if (categorizedExpanded) "收起 ▴" else "展开 ▾", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        }
                        if (categorizedExpanded) {
                            AppCategory.entries.filter { it != AppCategory.UNKNOWN }.forEach { category ->
                                val apps = categorizedApps.filter { it.third == category }
                                if (apps.isNotEmpty()) {
                                    Text("${category.label}（${apps.size}）", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                                    apps.forEach { (pkg, label, _) ->
                                        Card {
                                            Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                                    Column(Modifier.weight(1f)) {
                                                        Text(label, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
                                                        Text(pkg, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                    }
                                                    if (appCategories.containsKey(pkg)) TextButton(onClick = { onAppCategoriesChange(appCategories - pkg) }) { Text("恢复自动") }
                                                    TextButton(onClick = { onToggleHiddenApp(pkg) }) { Text("忽略", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                                }
                                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                                                    AppCategory.entries.filter { it != AppCategory.UNKNOWN }.forEach { c ->
                                                        FilterChip(
                                                            selected = appCategories[pkg] == c.name || (!appCategories.containsKey(pkg) && category == c),
                                                            onClick = { onAppCategoriesChange(appCategories + (pkg to c.name)) },
                                                            label = { Text(c.label) }
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        val unknownApps = installedApps.filter { it.third == AppCategory.UNKNOWN }
                        if (unknownApps.isNotEmpty()) {
                            Row(Modifier.fillMaxWidth().clickable { uncategorizedExpanded = !uncategorizedExpanded }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("待分类应用（${unknownApps.size}）", fontWeight = FontWeight.SemiBold)
                                Text(if (uncategorizedExpanded) "收起 ▴" else "展开 ▾", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            }
                            if (uncategorizedExpanded) {
                                Text("没有自动识别出分类；给它们归类后，到点检测才会把它们算作游戏/视频等。", style = MaterialTheme.typography.bodySmall)
                                unknownApps.forEach { (pkg, label, _) ->
                                    Card {
                                        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                                Column(Modifier.weight(1f)) {
                                                    Text(label, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
                                                    Text(pkg, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                }
                                                TextButton(onClick = { onToggleHiddenApp(pkg) }) { Text("忽略", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                            }
                                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                                                listOf(AppCategory.GAME, AppCategory.VIDEO, AppCategory.SOCIAL, AppCategory.STUDY, AppCategory.OTHER).forEach { c ->
                                                    FilterChip(selected = false, onClick = { onAppCategoriesChange(appCategories + (pkg to c.name)) }, label = { Text(c.label) })
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        if (hiddenApps.isNotEmpty()) {
                            Row(Modifier.fillMaxWidth().clickable { hiddenExpanded = !hiddenExpanded }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("已忽略应用（${hiddenApps.size}）", fontWeight = FontWeight.SemiBold)
                                Text(if (hiddenExpanded) "收起 ▴" else "展开 ▾", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            }
                            if (hiddenExpanded) {
                                hiddenApps.sortedBy { AppLibrary.appLabel(context, it) }.forEach { pkg ->
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                        Column(Modifier.weight(1f)) {
                                            Text(AppLibrary.appLabel(context, pkg), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
                                            Text(pkg, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        TextButton(onClick = { onToggleHiddenApp(pkg) }) { Text("恢复") }
                                    }
                                }
                            }
                        }
                        Text("分类会记住你的选择；未设置的应用按内置清单或应用名自动识别。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    SettingsSubPage.STABILITY -> {
                        val context = LocalContext.current
                        var crashText by remember { mutableStateOf(CrashReporter.read(context)) }
                        Text("崩溃只记录在本机（filesDir/crash.log），不上传、不引第三方 SDK；把崩溃栈发给我即可定位。", style = MaterialTheme.typography.bodySmall)
                        if (crashText.isBlank()) {
                            Text("暂无崩溃记录。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = {
                                    val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("focusflow_crash", crashText))
                                    android.widget.Toast.makeText(context, "已复制崩溃记录", android.widget.Toast.LENGTH_SHORT).show()
                                }) { Text("复制") }
                                OutlinedButton(onClick = {
                                    CrashReporter.clear(context)
                                    crashText = CrashReporter.read(context)
                                }) { Text("清空", color = MaterialTheme.colorScheme.error) }
                            }
                            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))) {
                                Text(crashText.takeLast(4000), Modifier.fillMaxWidth().padding(10.dp), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
    helpBlock?.let { block ->
        HelpDialog(
            title = block.title,
            sections = listOfNotNull(HelpCatalog.settings[block]),
            onDismiss = { helpBlock = null },
            dismissButton = if (block == SettingsBlock.COURSE_VISION) {
                { TextButton(onClick = { helpBlock = null; onCourseVisionGuideOpenChange(true) }) { Text("如何获取 key") } }
            } else null
        )
    }
    if (courseVisionGuideOpen) CourseVisionKeyGuideDialog(onDismiss = { onCourseVisionGuideOpenChange(false) })
    if (baselineVariantNameOpen) {
        var variantName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { onBaselineVariantNameOpenChange(false) },
            title = { Text("另存当前方案") },
            text = { OutlinedTextField(value = variantName, onValueChange = { variantName = it }, label = { Text("方案名称，如“假期·早睡版”") }, singleLine = true) },
            confirmButton = {
                Button(enabled = variantName.isNotBlank(), onClick = { onSaveBaselineVariant(variantName.trim()); onBaselineVariantNameOpenChange(false) }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { onBaselineVariantNameOpenChange(false) }) { Text("取消") } }
        )
    }
    if (dayGroupWizardOpen) DayGroupWizardDialog(
        existingGroups = baselineProfile.dayGroups,
        defaultWake = baselineProfile.wakeMinute,
        defaultSleep = baselineProfile.sleepMinute,
        defaultMeals = baselineProfile.meals,
        onDismiss = { dayGroupWizardOpen = false },
        onSave = { groups ->
            onDayGroupsChange(groups)
            dayGroupWizardOpen = false
        }
    )
    if (choosingCurrentPlace) CampusPlacePickerDialog(
        title = "选择当前位置",
        places = campusPlaces,
        selectedName = currentCampusPlace,
        onDismiss = { choosingCurrentPlace = false },
        onSelect = { selected -> onCurrentCampusPlaceChange(selected.name); choosingCurrentPlace = false }
    )
    if (choosingDestination) CampusPlacePickerDialog(
        title = "选择预览目的地",
        places = campusPlaces,
        selectedName = previewDestination,
        onDismiss = { choosingDestination = false },
        onSelect = { selected -> previewDestination = selected.name; choosingDestination = false }
    )
    if (campusMapHelpOpen) CampusMapHelpDialog(onDismiss = { campusMapHelpOpen = false })
    routeCalibrationTarget?.let { (from, to) ->
        RouteCalibrationDialog(
            from = from,
            to = to,
            mode = commuteProfile.campusMode,
            currentMinutes = ZijingangTravel.estimateMinutes(from.zone, to.zone, commuteProfile),
            history = commuteProfile.routeObservations[ZijingangTravel.routeKey(from.zone, to.zone, commuteProfile.campusMode)].orEmpty(),
            onDismiss = { routeCalibrationTarget = null },
            onSave = { minutes ->
                val key = ZijingangTravel.routeKey(from.zone, to.zone, commuteProfile.campusMode)
                onCommuteChange(CommuteLearning.record(commuteProfile, key, minutes))
                recordBaselineEvent(BaselineEventType.COMMUTE_CONFIRMED, "${from.name} → ${to.name} · ${commuteProfile.campusMode} · $minutes 分钟")
                routeCalibrationTarget = null
            }
        )
    }
    }
}

@Composable internal fun SettingSwitch(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) { Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.SemiBold); Text(detail) }; Switch(checked = checked, onCheckedChange = onChange) } }
