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
